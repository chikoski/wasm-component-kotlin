# 性能とサイズの計測結果

このドキュメントでは、Chicory バックエンド（JVM / Android ART）と Wasmtime バックエンド（汎用ブリッジ / 型付きブリッジ）の呼び出し時間、初回インスタンス化時間、およびバイナリサイズ（APK サイズ）の計測結果をまとめます。

## 1. 要点（現行実装: バイナリ境界 + 型付きブリッジ）

生成コードとバックエンドの間をバイナリ形式（`WitWriter` / `WitReader`、[ADR-0015](../adr/0015-binary-value-boundary.md)）にし、Wasmtime に型付きブリッジ（[ADR-0016](../adr/0016-typed-bridge-from-wit-json.md)）を導入した後の主な数値です。

* **小さな呼び出し（`add(s32, s32) -> s32`）**:
  * Chicory AOT（JVM）: 約 **0.19〜0.31 µs**
  * Chicory AOT（ART エミュレーター）: 約 **2.3〜5.8 µs**
  * Wasmtime（JVM、FFM / JNI）: 約 **0.48〜1.05 µs**
* **64 KiB の `list<u8>` の往復（`echo-bytes`）**:
  * Chicory AOT（JVM）: 約 **12〜27 µs**
  * Wasmtime 型付きブリッジ（JVM）: 約 **28〜29 µs**
  * Wasmtime 汎用ブリッジ（JVM）: 約 **3.4〜5.7 ms**（`Val::U8` を要素ごとに変換するため）
  * Chicory AOT（ART エミュレーター）: 約 **0.26〜0.38 ms**
* **計算性能**:
  * 小さな関数のループ（`lcg-sum`）は、Chicory AOT と Wasmtime でほぼ同等（約 1.1 倍）です（インタプリタ比で約 168 倍高速）
  * 大きな wasm 関数への対応については、ビルド時の `JvmMethodSplitter` が 7,500 バイトを目安にメソッドを自動分割します。分割の設計と背景は [ADR-0021](../adr/0021-split-large-chicory-methods.md) に記載しています
  * 分割後も 8,000 バイトを超えるディスパッチメソッドなどはデスクトップ JVM では `-XX:-DontCompileHugeMethods` で改善し、64 KB を超える関数は Chicory のインタプリタにフォールバックします
* **初回の `openSession`**:
  * ART で 57 ms（最小コンポーネント）〜 239 ms（1.2 MB のコンポーネント）。2 回目以降は 0.2〜10 ms
* **Android APK サイズ（release、R8 適用後）**:
  * ランタイム一式 + サンプル 2 コンポーネントで **+181 KB**、1.2 MB の `heavy` コンポーネントで **+920 KB**

---

## 2. 計測環境と方法

| 項目 | 内容 |
| :--- | :--- |
| JVM | GitHub Actions `ubuntu-latest`（2 vCPU）、Temurin 21.0.13。2 秒ウォームアップ後、0.7 秒 × 5 回の中央値 |
| ART | 同じランナー上の Android 14（API 34、x86_64）エミュレーター（KVM、4 コア）、Jetpack Microbenchmark 1.3.4、非 debuggable の release テスト APK |
| コンポーネント | `arithmetic`（最小）、`types`（文字列・バイト列・record）、`heavy`（Rust の `std` + `regex` + `serde_json`、1.19 MB、[`fixtures/src/heavy`](../../fixtures/src/heavy)） |
| 初回計測 | 新しいプロセスで `openSession` と最初の呼び出しにかかる時間を計り、9 回の中央値を取得 |

> ART の数値はエミュレーター上のものであり、実機ではありません。また GitHub Actions ランナーの CPU 個体差により、ジョブ間で絶対値が 30〜70% 前後変動するため、同じ表の中での比較を基準にしてください。

---

## 3. 呼び出しとデータ受け渡しの時間

### 3.1 バイナリ境界（JVM と ART）

以下の表は `#58` の最適化前に GitHub Actions CI 上で取得した計測値です。最適化前後の比較値は同じ節の後半に掲載しています。

| 呼び出し | Chicory AOT (JVM) | Wasmtime 汎用 FFM (JVM) | Chicory AOT (ART) |
| :--- | ---: | ---: | ---: |
| `add(s32, s32)`（core 直接） | 50 ns | — | 43〜49 ns |
| `add(s32, s32)`（`ComponentBackend.invoke`） | 153 ns | — | 0.86〜1.9 µs |
| `add(s32, s32)`（生成 DSL） | **186 ns** | **705 ns**（JNI: 479 ns） | **2.3〜5.8 µs** |
| `echo-name`（5 文字の文字列） | 1.5 µs | 0.97 µs | 8.3〜29 µs |
| `echo-bytes`（16 B） | 0.76 µs | 1.4 µs | 3.9〜4.8 µs |
| `echo-bytes`（1 KiB） | 0.99 µs | 29 µs | 6.1〜9.2 µs |
| `echo-bytes`（64 KiB） | **12.4 µs** | **3.4 ms** | **0.26〜0.38 ms** |
| `echo-blob`（record、1 KiB を含む） | 4.1 µs | 30 µs | 21〜43 µs |
| `sort-bytes`（64 KiB） | 4.9 ms | 2.3 ms | 25〜27 ms |
| `json-roundtrip`（13 KiB 文字列） | 752 µs | 119 µs | 4.2〜4.5 ms |

64 KiB の `echo-bytes`（JVM Chicory AOT: 12.4 µs）の内訳は、生成コード側の `WitWriter` / `WitReader` 変換が 5.3 µs、`ComponentBackend.invoke`（lower / lift と wasm 実行）が 6.9 µs です。

上表の取得時点では、5 文字の文字列を渡す `echo-name` が 16 B の `echo-bytes` より時間を要していました。`echo-bytes` の 16 B は JVM で 0.76 µs、ART で 3.9〜4.8 µs でした。同じ表で `echo-name` は JVM で 1.5 µs、ART で 8.3〜29 µs でした。

原因の切り分けと最適化前後の比較は、ローカルの Android 16 ARM64 エミュレーター上の非 debuggable な release テスト APK で実施しました。変更前のトレースでは、`WitWriter.string` や `Utf8.isValid` のほかに `echo-name` の `option<string>` を扱う `CanonicalAbi` のレイアウト計算と `Intrinsics.areEqual` 連鎖が繰り返し実行されていました。`echoName5_aot_dsl` のトレースには `Intrinsics.areEqual` が 430 回、`WitType$Str.equals` が 221 回、`WitType$Variant.equals` が 169 回、`WitType$Tuple.equals` が 39 回、`Arrays$ArrayItr.hasNext` が 51 回、`Integer.valueOf` が 44 回含まれていました。

この結果を受けて `runtime-chicory` の境界処理を最適化しました。`WitType` のアラインメント、サイズ、フラット化表現、および `FuncType` の引数型とフラット要素数を各インスタンスに遅延キャッシュしています。これにより呼び出しごとの型レイアウト再計算と `equals` の連鎖を省きました。あわせて `Lowerer` と `Lifter` で `variant` やリスト処理における `MutableList<Long>`、`LongArray`、`Pair`、`Triple` の一時確保を減らしました。ただし `WitReader.region` は非インライン関数のため `storeString` の戻り値で `Long` のボックス化は残ります。さらに `ChicoryComponentInstance` では `resolve` 時に `ExportFunction`、`CanonOptions`、`Memory`、`cabi_realloc`、`post_return` を保持します。`Context` 内のリソース追跡用コレクションもハンドルが渡されたときだけ生成する形へ改めました。

同じ Android 16 ARM64 エミュレーター上の比較では、再計算の削減と一時確保の抑制により実行時間とアロケーション回数が減少しました。

| 指標 | `echoName5_aot_dsl` 変更前 | `echoName5_aot_dsl` 変更後 | `echoBytes16_aot_dsl` 変更前 | `echoBytes16_aot_dsl` 変更後 |
| :--- | ---: | ---: | ---: | ---: |
| 最小値 | 8,309 ns | 770.71 ns | 5,170 ns | 721.58 ns |
| 中央値 | 11,331.95 ns | 846.18 ns | 6,899.99 ns | 794.56 ns |
| アロケーション | 113.0 回 | 26.0 回 | 61.0 回 | 25.0 回 |
| メソッドエントリ | 2,915 回 | 1,229 回 | 1,877 回 | 1,147 回 |

変更後のエミュレーター計測で `echoName5_aot_dsl` と `echoBytes16_aot_dsl` の差は最小値で 49.13 ns、アロケーション回数で 1 回になりました。これにより `#58` が対象とした文字列受け渡しの追加コストは解消しています。なお上記の結果はエミュレーター上の値です。実機での検証やセクション 3.1 全体の CI 基準表の更新は、セクション 7 の手順による定常更新として別に対応します。

変更後の実装について Apple Silicon 機上の Host JVM でも `echo` 系ベンチマークを計測しました。macOS 上の OpenJDK 21 環境における `chicory-aot/dsl` の計測結果は、`echo-name` が 355 ns、`echo-bytes` の 16 B が 328 ns、1 KiB が 354 ns、64 KiB が 7.17 µs、`echo-blob` の 1 KiB レコードが 713 ns です。同じ環境で `echo-name` は `wasmtime-jni/dsl` が 638 ns、`wasmtime-ffm/dsl` が 853 ns でした。同じ Apple Silicon 機での最適化前ベンチマークは取得していません。そのため CI の 1.5 µs との前後比較ではなく、変更後の同一環境での参照値として記載しています。

単一コンポーネントを扱う `echo-name` とは別経路として、複数のメモリを持つコンポーネントを合成する際には `Transcoder` が使われます。`echo-name` 自体は `Transcoder` を通りませんが、関連課題の整理として `Transcoder` の実装も変更しました。以前は UTF-8 検証で生成していた `CharsetDecoder` と、UTF-16 から UTF-8 への変換で確保していた中間 `String` を削除しています。現在は `Utf8.isValid` と `Utf8.isValidUtf16` を使い、UTF-8 バイト列を直接メモリへ書き出します。なお `readUtf16` の `CharArray` や `ByteArrayOutputStream` の確保は残ります。この `Transcoder` 変更後の Android ART 上での再計測は未実施です。

### 3.2 Wasmtime の汎用ブリッジと型付きブリッジの比較（JVM）

同じランナー（AMD EPYC 7763）上で、同じブリッジライブラリに対して汎用経路と型付き経路（[ADR-0016](../adr/0016-typed-bridge-from-wit-json.md)）を比較した結果です。

| 呼び出し | 汎用 FFM | 型付き FFM | 汎用 JNI | 型付き JNI | Chicory AOT（参考） |
| :--- | ---: | ---: | ---: | ---: | ---: |
| `add(s32, s32)` | 1.05 µs | 872 ns | 837 ns | 654 ns | 313 ns |
| `echo-name`（5 文字） | 1.47 µs | 1.21 µs | 1.33 µs | 1.07 µs | — |
| `echo-bytes`（16 B） | 2.28 µs | 1.18 µs | 2.14 µs | 1.02 µs | 1.33 µs |
| `echo-bytes`（1 KiB） | 57 µs | **1.6 µs** | 58 µs | 1.5 µs | 1.6 µs |
| `echo-bytes`（64 KiB） | 5.72 ms | **28.8 µs** | 5.74 ms | 28.3 µs | 27.4 µs |
| `echo-blob`（record、1 KiB） | 61 µs | 2.35 µs | 61 µs | 2.19 µs | 6.8 µs |
| `sort-bytes`（64 KiB） | 4.84 ms | **1.27 ms** | — | — | 8.9 ms |
| `json-roundtrip`（13 KiB） | 218 µs | 217 µs | — | — | 1.2 ms |
| `regex-count`（14 KiB） | 106 µs | 106 µs | — | — | — |

---

## 4. 計算の性能と大きな wasm 関数の影響（`heavy`）

| 呼び出し | Chicory AOT (JVM) | Chicory AOT (`-XX:-DontCompileHugeMethods`) | Chicory インタプリタ (JVM) | Wasmtime FFM | Chicory AOT (ART) |
| :--- | ---: | ---: | ---: | ---: | ---: |
| `lcg-sum`（整数ループ 100 万回） | 593 µs | 611 µs | 99.7 ms | 513 µs | 5.6 ms |
| `json-roundtrip`（13 KiB） | 752〜914 µs | 937 µs | 51.3 ms | 119〜306 µs | 4.2〜6.3 ms |
| `regex-count`（16 B、主にパターンコンパイル） | 1.2 ms | 1.7 ms | 5.8 ms | 20 µs | 2.0 ms |
| `regex-count`（14 KiB） | 3.7 ms | **1.1 ms** | 16.7 ms | 70 µs | 16.4 ms |

* 上記の表は `JvmMethodSplitter` 導入前のベースライン計測値です
* 分割前は `regex-count` 関連を含む 13 個のメソッドが JVM バイトコードで 8,000 バイトを超え、HotSpot の既定である `-XX:+DontCompileHugeMethods` 下では JIT コンパイルされませんでした。`-XX:-DontCompileHugeMethods` を指定したときは 14 KiB の `regex-count` が 3.7 ms から 1.1 ms に短縮されていました

### 4.1 メソッド分割後の比較と ART 上限への対応

ビルド時の `JvmMethodSplitter` は、7,500 バイトを超えるメソッドを自動分割します。この分割により、`heavy` コンポーネントでは 13 個のメソッドが分割され、HotSpot 上限超過のメソッドは 1 個、最大 8,121 バイトに減りました。Android ART の命令数上限である 16,383 DEX コード単位を超えるメソッドは 0 個になっています。詳細は [ADR-0021](../adr/0021-split-large-chicory-methods.md) にまとめています。

`samples/android-glitch-camera` の `png-glitch-component.wasm` では、分割前にコアモジュールごとに 31 個のメソッドが HotSpot の 8,000 バイト上限を超えていました。Android の `nonMinifiedRelease` ビルドを `emulator-5554` で AOT コンパイルすると、`dex2oat64` は 4 つの `func_*` メソッドをコンパイル対象外としてスキップしました。分割後はコアモジュール 0 の 34 個のメソッドが分割され、すべての `func_*` メソッドが 6,724 DEX コード単位以下に収まりました。ディスパッチ用の `call_dispatch_0` は名前フィルタにより分割対象外となるため 15,377 JVM バイト、13,440 DEX コード単位のまま残りますが、`dex2oat64` の上限超過ログは `app-nonMinifiedRelease` と R8 適用後の `app-release` の双方で 0 件になりました。

`cyber_sort` プリセットを用い、解像度 960×540、`PngFilterOption.NONE`、ウォームアップ 10 回、本計測 20 回の条件で 1 フレームあたりの処理時間を計測しました。デスクトップは OpenJDK 21、Android は `emulator-5554` 上の非 `debuggable` である `nonMinifiedRelease` ビルドを使用しました。

| 実行環境と条件 | 分割前の中央値 | 分割後の中央値 | 分割前の平均値 | 分割後の平均値 |
| :--- | ---: | ---: | ---: | ---: |
| Host JVM 既定設定 | 198.27 ms | 10.50 ms | 195.75 ms | 10.57 ms |
| Host JVM huge メソッド制限無効 | 19.71 ms | 10.99 ms | 19.48 ms | 11.02 ms |
| Android ART AOT 最大最適化 | 558.92 ms | 334.01 ms | 569.87 ms | 335.11 ms |
| Android ART 検証のみ JIT 併用 | 528.24 ms | 337.19 ms | 544.23 ms | 348.75 ms |

デスクトップ Host JVM では既定設定のまま 198.27 ms から 10.50 ms へ短縮し、`-XX:-DontCompileHugeMethods` なしの状態で同等以上の速度が出ています。Android ART でも、`dex2oat64` と JIT の両方で 4 メソッドのインタプリタフォールバックが解消し、558.92 ms から 334.01 ms へ短縮しました。

なお、`debuggable` が有効な `debug` ビルドを同じエミュレーターで実行した場合、`cmd package compile -m everything` を実行しても `dexopt` は `verify` 状態のままになります。そのため `debug` ビルド上の同じ計測では 1,148.60 ms から 1,173.16 ms かかり、Android 実環境での性能確認には非 `debuggable` ビルドが必要です。

---

## 5. 初回のインスタンス化

| 項目 | Chicory AOT (JVM) | Wasmtime FFM (JVM) | Chicory AOT (ART) |
| :--- | ---: | ---: | ---: |
| `arithmetic`: 最初の `openSession` | 147〜205 ms | 244 ms | 57 ms |
| `arithmetic`: 2 回目の `openSession` | 0.2 ms | 2.2 ms | 0.2 ms |
| `heavy`: 最初の `openSession` | 276〜373 ms | 853〜1144 ms | 239 ms |
| `heavy`: 2 回目の `openSession` | 1.9 ms | 904 ms | 9.8 ms |

* Chicory では、初回のみマシンクラスのロードと `.meta`・`plan.json` の解析が行われ、2 回目以降はインスタンスの生成のみで完了します
* Wasmtime の `openSession` は毎回 `.wasm` をコンパイルします（事前に `precompileComponent` で `.cwasm` を作って `openPrecompiledSession` を使えば省略できます）

---

## 6. サイズ

### 6.1 ビルド時の出力（Chicory AOT）

| コンポーネント | `.wasm` | 関数数 | インタプリタ関数 | マシンクラス | `.meta` | ビルド時間 |
| :--- | ---: | ---: | ---: | ---: | ---: | ---: |
| `arithmetic` | 0.4 KB | 4 | 0 | 43 KB | 0.1 KB | — |
| `types` | 19 KB | 70 | 0 | 83 KB | 1.6 KB | 0.3 s |
| `heavy` | 1.19 MB | 1,012 | 1 | 1.33 MB | 432 KB | 1.6 s |

### 6.2 Android APK サイズ（release、R8 適用後）

| アプリ構成 | APK サイズ | 増加分 |
| :--- | ---: | ---: |
| コンポーネントなし（空の Activity のみ） | 24.6 KB | — |
| `samples/android`（`roundtrip` + `counter`） | 205 KB | **+181 KB** |
| `heavy` のみ | 944 KB | **+920 KB** |

`samples/android` の dex（333 KB、R8 適用後）の内訳は、Chicory（runtime + wasm）が 147 KB、Kotlin 標準ライブラリが 68 KB、`kotlinx.serialization`（`plan.json` 解析用）が 59 KB、`wasm2kt` ランタイムが 44 KB、生成コードが 11 KB です。

---

## 7. Chicory バックエンドと AOT バックエンドの同一コミット比較

`#186` に伴い、同じコミット上で `CHICORY` バックエンドと `AOT` バックエンドを計測し比較しました。[ADR-0023](../adr/0023-custom-wasm-compiler-and-static-jvm-backend.md) の Phase 1 では、まだ `ComponentBackend` 経由のバイナリワイヤ境界を経由します。したがって、本節は R8 最適化・ART 事前コンパイル・初回起動・Core Wasm 実行の差分に焦点を当てて記載します。

### 7.1 計測環境と対象

デスクトップ計測には macOS Apple Silicon 環境の OpenJDK 21.0.13 を使用しました。ビルド統計では `arithmetic`、`types`、`heavy` を対象に `Prepare` で両バックエンドの生成時間と出力サイズを記録しています。

Android のリリースサイズ比較には `compileSdk 35`、`minSdk 26`、`JavaVersion.VERSION_17`、`isMinifyEnabled = true`、および `proguard-android-optimize.txt` の条件を用いました。対象は `samples` に相当する `roundtrip` と `counter` の組、`heavy.wasm` 単体、および `1.8 MiB` の `png-glitch-component.wasm` です。それぞれ純粋な `CHICORY` のみ、および純粋な `AOT` のみのリリース APK を個別に生成して比較しました。

Android ART の実行時間と `dex2oat64` 検査には、ローカルの ARM64 Android 16 エミュレーター `Medium_Phone_API_36.1` を使用しました。非 `debuggable` のリリース用テスト APK および R8 縮小済みリリース APK を導入し、`adb shell cmd package compile -m speed -f` による事前コンパイルも確認しています。

### 7.2 ビルド時出力と R8 適用後 APK サイズ

以下の表は、Host JVM 向けビルドにおける両バックエンドの出力差分です。

| コンポーネント | 関数数 | インタプリタ実行関数 | 分割対象・補助メソッド数 | 生成クラス合計サイズ | メタまたはデータ領域サイズ | ビルド時間 |
| :--- | ---: | ---: | ---: | ---: | ---: | ---: |
| `arithmetic` Chicory | 4 | 0 | — | 42,999 B | `.meta` 70 B | 1,452 ms |
| `arithmetic` AOT | 4 | 0 | 0 / 0 | 1,755 B | `.data` 0 B | 35 ms |
| `types` Chicory | 70 | 0 | — | 83,062 B | `.meta` 1,562 B | 97 ms |
| `types` AOT | 70 | 0 | 1 / 2 | 65,492 B | `.data` 640 B | 107 ms |
| `heavy` Chicory | 1,012 | 1 | — | 1,349,649 B | `.meta` 432,255 B | 1,774 ms |
| `heavy` AOT | 1,012 | 0 | 46 / 185 | 3,044,102 B | `.data` 369,924 B | 877 ms |

`heavy` のような大規模コンポーネントでは、ビルド直後の生 `.class` サイズは `AOT` のほうが `3.04 MB` と大きくなります。これは、`AOT` がすべての Core Wasm 関数を静的分割してバイトコード化するためです。その結果、Chicory で残っていたインタプリタ実行関数と 8,000 バイト上限超過の警告は、`AOT` では 0 件になりました。`heavy` のコンパイル時間も `1,774 ms` から `877 ms` へ短縮しています。

Android アプリに組み込んで R8 で縮小すると、`runtime-chicory`、`kotlinx.serialization`、および `.meta` と `plan.json` の除去が効いて傾向が逆転します。

| 構成とバックエンド | リリース APK | 非圧縮 DEX | DEX クラス数 | DEX メソッド ID | `.meta` と `plan.json` | `.data` 資源 | `wasm2ktCompile` | R8 縮小時間 | 全体ビルド時間 |
| :--- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| `samples` Chicory | 243,797 B | 488,664 B | 589 | 3,061 | 19,492 B | 0 B | 0.22 s | 9.18 s | 18.85 s |
| `samples` AOT | 55,847 B | 76,468 B | 103 | 618 | 0 B | 24 B | 0.15 s | 3.15 s | 13.53 s |
| `heavy` Chicory | 990,410 B | 1,859,204 B | 580 | 5,059 | 436,851 B | 0 B | 2.08 s | 15.43 s | 26.18 s |
| `heavy` AOT | 805,797 B | 1,359,168 B | 82 | 1,429 | 0 B | 369,924 B | 0.84 s | 13.60 s | 24.10 s |
| `png-glitch` Chicory | 1,524,654 B | 3,209,448 B | 604 | 6,992 | 168,179 B | 0 B | 4.45 s | 25.28 s | 39.07 s |
| `png-glitch` AOT | 1,290,954 B | 2,454,168 B | 122 | 2,144 | 0 B | 125,200 B | 1.42 s | 16.95 s | 29.28 s |

`samples` 構成では APK が `243,797 B` から `55,847 B` へ、非圧縮 DEX が `488,664 B` から `76,468 B` へ縮小しました。`heavy` と `png-glitch` でも DEX クラス数とメソッド ID 数が 7 割前後減少し、`wasm2ktCompile` と R8 の処理時間もともに短縮しています。これは R8 が静的配線を解析して未使用の型やヘルパーを除去できるためです。

### 7.3 ARM64 `dex2oat64` による事前コンパイル

`heavy` と `png-glitch` の R8 縮小済みリリース APK を `emulator-5554` へ導入し、`cmd package compile -m speed -f` を実行しました。4 つの APK はいずれも終了コード 0 で `dex2oat64` を完了し、命令数上限超過によるメソッドのスキップログは 0 件でした。

ただし `Chicory` 版では関数インデックス 262 がビルド時点でインタプリタフォールバックに回っており、`dex2oat` 対象外の関数が残っています。一方の `AOT` 版では全関数が DEX へ出力され、`heavy` で `dex2oat` 壁時計時間が `1,810 ms` から `652 ms` へ、`.odex` サイズが `8,199,224 B` から `5,262,696 B` へ減りました。`png-glitch` でも `1,421 ms` から `1,026 ms` へ、`.odex` サイズが `13,601,108 B` から `8,611,148 B` へ縮小しています。

### 7.4 初回インスタンス化と `.data` 読み込みコスト

新規プロセスでの初回起動時間を、Host JVM は 9 回の中央値、ARM64 ART は 5 回の中央値で比較しました。

| 対象と環境 | `CHICORY` 初回 open | `CHICORY` 初回合計 | `AOT` 初回 open | `AOT` 初回合計 |
| :--- | ---: | ---: | ---: | ---: |
| `arithmetic` Host JVM | 79.34 ms | 93.46 ms | 18.62 ms | 41.55 ms |
| `heavy` Host JVM | 125.69 ms | 155.83 ms | 26.39 ms | 49.94 ms |
| `arithmetic` ARM64 ART | 7.312 ms | 7.613 ms | 0.501 ms | 0.662 ms |
| `heavy` ARM64 ART | 74.184 ms | 75.446 ms | 3.841 ms | 4.357 ms |

`AOT` では実行時の `plan.json` 解析、`.meta` パース、および Chicory マシンの初期化が不要になり、静的データセグメントの `.data` 読み込みのみが残ります。特に ART 上では `heavy` の初回合計が `75.446 ms` から `4.357 ms` へ短縮しました。`heavy` の 2 回目以降の `openSession` は Host JVM で `0.59 ms`、ART で `0.920 ms` です。

### 7.5 定常状態実行と NaN ビット保存の検証

同じ Apple Silicon Host JVM 上で計測した、`aot/dsl` と他バックエンドの定常呼び出し中央値です。

| 呼び出し | `aot/dsl` | `chicory-aot/dsl` | `chicory-interp/dsl` | `wasmtime-ffm/dsl` | `wasmtime-jni/dsl` |
| :--- | ---: | ---: | ---: | ---: | ---: |
| `add(s32, s32)` | 169.3 ns | 181.8 ns | 256.0 ns | 701.3 ns | 622.5 ns |
| `echo-name` 5 文字 | 334.4 ns | 442.6 ns | 3.42 µs | 859.8 ns | 788.1 ns |
| `echo-bytes` 16 B | 311.9 ns | 371.6 ns | 3.18 µs | 1.20 µs | 1.13 µs |
| `echo-bytes` 1 KiB | 320.0 ns | 402.3 ns | 3.01 µs | 19.21 µs | 19.01 µs |
| `echo-bytes` 64 KiB | 9.44 µs | 11.93 µs | 14.66 µs | 1.08 ms | 1.08 ms |
| `echo-blob` 1 KiB record | 649.8 ns | 770.9 ns | 6.95 µs | 19.63 µs | 19.53 µs |
| `lcg-sum` 100 万回 | 303.5 µs | 307.9 µs | 62.82 ms | 324.2 µs | 324.0 µs |
| `regex-count` 16 B | 42.0 µs | 132.0 µs | 2.73 ms | 10.6 µs | 10.5 µs |
| `regex-count` 14 KiB | 179.3 µs | 403.2 µs | 8.17 ms | 51.3 µs | 49.1 µs |
| `json-roundtrip` 13 KiB | 366.5 µs | 626.9 µs | 25.66 ms | 106.9 µs | 107.3 µs |
| `sort-bytes` 64 KiB | 582.0 µs | 4.19 ms | 91.35 ms | 1.35 ms | 1.36 ms |

`png-glitch-component.wasm` に対する 256×256 ピクセルの `Invert` プリセット計測でも、`CHICORY` の `open+listFilters` `119.15 ms`、`processFrame` `2.727 ms`、PNG `glitch` `11.271 ms` に対し、`AOT` はそれぞれ `32.53 ms`、`1.732 ms`、`10.410 ms` でした。`sort-bytes` や `regex-count` で `AOT` が有利な一方、`regex-count` のような正規表現処理ではなお Wasmtime のほうが約 3.6 倍高速です。

ARM64 Android 16 エミュレーター上の `CallBenchmark` では、Perfetto トレースのストレージ不足で全 32 ケースのうち 8 ケースが完走しました。完了した指標の中央値は、`add_staticAot_dsl` が `263.53 ns`、`add_staticAot_backend` が `97.56 ns`、`add_aot_core` が `14.72 ns`、`add_interp_dsl` が `548.94 ns`、`codecBytes1024_wire` が `345.82 ns`、`echoBlob1024_aot_dsl` が `2,295.26 ns`、`regexCount16B_staticAot_dsl` が `135.24 µs`、`jsonRoundtrip13KiB_staticAot_dsl` が `825.86 µs` です。残り 24 ケースの同一エミュレーター上ペア比較は今回のストレージ枯渇により未取得であり、Linux KVM の CI や実機での後続計測に残しています。

なお、`emulator-5554` 上の `AotComponentInstrumentedTest` により、`AOT` コンポーネントの E2E 動作と、ART 上における NaN ビット表現の保存を確認しました。ローカルの ARM64 環境では、`WasmNumerics` の `reinterpret` 群と `LinearMemory` の `F32`/`F64` ロード・ストア、および `AOT` コンパイル済みコードと同等の `intBitsToFloat`/`floatToRawIntBits` 系変換でシグナリング NaN を含むビット列が破壊されず保持されます。一方、CI の `emulator` ジョブは `x86_64` 構成で実行されるため、実機および ARM64 での検証は本節のローカル計測が基準です。

### 7.6 制約と Phase 2 への課題

Phase 1 の `AOT` バックエンドにはいくつかの技術的制約と設計上のトレードオフがあります。

* 非同期コンポーネント非対応
* バイナリワイヤ境界の残存
* `ByteArray` によるロードとストアの実装

非同期コンポーネントと WASI 0.3 はまだ `AOT` で実行できず、Gradle プラグインは設定値にかかわらずビルド時にエラーで拒否します。また、Phase 1 では生成バインディングとバックエンドの間に `WitWriter` と `WitReader` の直列化が残っているため、境界でのコピーとボックス化をなくす直接の Lower と Lift の生成は `#187` で進めます。さらに `LinearMemory` は Android API 26 互換のため `VarHandle` を使わず `ByteArray` へのバイト単位シフトで 4 バイトや 8 バイトのアクセスを実装しています。この構造でも `sort-bytes` や `processFrame` では高速に動作しましたが、高頻度メモリアクセスに対する `ByteBuffer` や `Unsafe` 相当手段の評価は Phase 2 以降の検討課題です。

---

## 8. 再計測の手順

```bash
./gradlew -Pwasm2kt.bench :benchmarks:bench        # 定常状態（JVM）。-Pwasm2kt.bench.filter=<正規表現> で絞り込み可
./gradlew -Pwasm2kt.bench :benchmarks:benchTyped   # 型付きブリッジの計測（cargo でビルド）
./gradlew -Pwasm2kt.bench :benchmarks:coldStart    # 初回インスタンス化（JVM）
./gradlew -Pwasm2kt.bench :benchmarks:profile -Pwasm2kt.bench.filter=regex
./gradlew -p samples/android :benchmark:assembleReleaseAndroidTest && samples/android/benchmark/run-on-device.sh
```
