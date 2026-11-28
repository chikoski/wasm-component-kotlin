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

### 7.6 制約と Phase 2・Phase 3 への展開

Phase 1 の `AOT` バックエンドには、構築時点での技術的制約と設計上のトレードオフがありました。

* 非同期コンポーネント非対応
* バイナリワイヤ境界の残存
* `ByteArray` によるロードとストアの実装

Phase 1 では非同期コンポーネントと WASI 0.3 に未対応であり、生成バインディングとバックエンドの間にも `WitWriter` と `WitReader` の直列化が残っていました。同期コンポーネント向けのバイナリ境界除去は `#187` の Phase 2 で完了し、非同期コンポーネントと WASI 0.3 の実行対応は `#188` の Phase 3 で完了しています。一方、`LinearMemory` は Android API 26 互換のため `ByteArray` へのバイト単位シフトで 4 バイトや 8 バイトのアクセスを実装しており、高頻度メモリアクセスに対する代替手段の評価は今後の検討課題です。

---

## 8. Phase 2 静的直結 AOT の実測結果

`#187` により、World DSL から生成済みの Core Wasm 関数を直接呼び出す静的 Canonical ABI 配線を導入しました。Phase 2 の `AOT` では `WitWriter`、`WitReader`、および `ComponentBackend.invoke` を通らず、`DirectCanonicalAbi` と生成済み Lower や Lift コードが直接 `LinearMemory` と Core Wasm 関数を結ぶ構造です。

### 8.1 Host JVM における Phase 2 と Phase 1 の定常呼び出し比較

Apple Silicon macOS 環境の OpenJDK 21.0.13 で、Phase 2 経路の `aot/dsl` と Phase 1 経路の `aot-p1/dsl`、および `chicory-aot/dsl` や Wasmtime を同一プロセス設定で計測しました。

| 呼び出し | `aot/dsl` | `aot-p1/dsl` | `chicory-aot/dsl` | `chicory-interp/dsl` | `wasmtime-ffm/dsl` | `wasmtime-jni/dsl` |
| :--- | ---: | ---: | ---: | ---: | ---: | ---: |
| `add(s32, s32)` | 14.8 ns | 183.5 ns | 196.7 ns | 270.2 ns | 736.6 ns | 660.8 ns |
| `echo-name` 5 文字 | 77.9 ns | 339.7 ns | 453.6 ns | 4.15 µs | 895.4 ns | 820.1 ns |
| `echo-bytes` 16 B | 42.3 ns | 326.3 ns | 408.3 ns | 3.85 µs | 1.24 µs | 1.18 µs |
| `echo-bytes` 1 KiB | 73.6 ns | 342.5 ns | 443.1 ns | 3.60 µs | 19.63 µs | 19.44 µs |
| `echo-bytes` 64 KiB | 3.01 µs | 9.61 µs | 12.08 µs | 15.53 µs | 1.11 ms | 1.10 ms |
| `echo-blob` 1 KiB record | 214.5 ns | 669.9 ns | 797.9 ns | 8.39 µs | 20.44 µs | 19.87 µs |
| `lcg-sum` 100 万回 | 311.2 µs | 311.3 µs | 314.2 µs | 60.95 ms | 332.3 µs | 331.7 µs |
| `regex-count` 16 B | 41.9 µs | 43.2 µs | 132.6 µs | 2.74 ms | 10.8 µs | 10.7 µs |
| `regex-count` 14 KiB | 178.1 µs | 174.1 µs | 371.7 µs | 8.25 ms | 54.3 µs | 52.6 µs |
| `json-roundtrip` 13 KiB | 376.3 µs | 374.6 µs | 621.0 µs | 25.56 ms | 109.0 µs | 108.4 µs |
| `sort-bytes` 64 KiB | 579.4 µs | 585.1 µs | 4.64 ms | 92.47 ms | 1.38 ms | 1.38 ms |

`add(s32, s32)` は `aot-p1/dsl` の `183.5 ns` から `14.8 ns` へ短縮しました。`echo-bytes` の `1 KiB` や `echo-blob` といった境界の往復が重い呼び出しでも、バイナリ直列化と中間ボックス化を省いたことで呼び出しオーバーヘッドが小さくなっています。一方で、`regex-count` や `json-roundtrip` のように Wasm 内部の計算が支配的な処理は、Phase 1 と Phase 2 で実行時間はほぼ同等です。

### 8.2 R8 による未使用エクスポートとワイヤ層の除去

Phase 2 で `Session` と `<Name>Component` を直接結んだことで、Android のリリースビルド時に R8 がアプリから呼ばれないエクスポートと到達不能な Core Wasm 関数を除去できるようになりました。`isMinifyEnabled = true` の空の `Activity` を用いて、`heavy.wasm` と `png-glitch-component.wasm` の R8 縮小結果を計測しました。

| 対象アプリと公開呼び出し構成 | リリース APK | 非圧縮 DEX | DEX クラス数 | DEX メソッド ID | 残存するワイヤ系クラス | 残存する `__export_` メソッド |
| :--- | ---: | ---: | ---: | ---: | ---: | :--- |
| `heavy` で `lcgSum` のみ呼び出し | 153,084 B | 26,224 B | 15 | 54 | 0 件 | `__export_2_lcgSum` のみ |
| `heavy` で全 4 エクスポート呼び出し | 791,673 B | 1,324,716 B | 23 | 1,039 | 0 件 | 全 4 種 |
| `png-glitch` で `validateRecipe` のみ呼び出し | 1,274,059 B | 2,407,276 B | 42 | 1,587 | 0 件 | `__export_10_validateRecipe` のみ |

`heavy` で `lcgSum` のみを呼ぶ構成では、全 4 エクスポートを呼ぶ構成と比べて `classes.dex` が `1,324,716 B` から `26,224 B` へ減少し、メソッド数も `1,039` 件から `54` 件へ減りました。`WitWriter`、`WitReader`、`ComponentBackend`、`Linker`、`CanonicalAbi`、`FuncType`、`WitType` といった動的ワイヤ系のクラスも APK に残りません。ただし、`elem` セグメント経由で関数テーブルへ登録される間接呼び出し対象関数は、モジュール初期化処理 `<init>` から参照されるため、現状でも R8 による到達不能判定の対象外として残ります。

### 8.3 初回起動と Android 16 ARM64 ART での実測

Host JVM での 9 回実行中央値における Phase 1 `aot-p1` と Phase 2 `aot` の初回起動比較を以下に示します。

| 対象とフェーズ | `aot-p1` | `aot` |
| :--- | ---: | ---: |
| `arithmetic` 初回 open | 18.65 ms | 5.24 ms |
| `arithmetic` 初回 total | 41.44 ms | 24.48 ms |
| `heavy` 初回 open | 26.47 ms | 12.95 ms |
| `heavy` 初回 total | 49.92 ms | 34.27 ms |

ローカルの ARM64 Android 16 エミュレーター `Medium_Phone_API_36.1` 上で、`CHICORY` と Phase 2 `AOT` を `ColdStartProbe` および `CallBenchmark` で比較した結果は以下のとおりです。

| ART 計測項目 | `CHICORY` | Phase 2 `AOT` |
| :--- | ---: | ---: |
| `arithmetic` 初回 open / 合計 | 3.687 ms / 3.836 ms | 0.185 ms / 0.238 ms |
| `heavy` 初回 open / 合計 | 80.531 ms / 84.646 ms | 4.952 ms / 5.607 ms |
| `CallBenchmark` `add` 中央値 | 864.3 ns | 11.6 ns |
| `CallBenchmark` `echoName5` 中央値 | 7,124.8 ns | 500.2 ns |
| `CallBenchmark` `echoBytes1024` 中央値 | 1,325.2 ns | 2,193.5 ns |
| `CallBenchmark` `echoBlob1024` 中央値 | 2,462.2 ns | 655.9 ns |

ART 上の `echoBytes1024` は最小値が Phase 2 で `748.4 ns`、`CHICORY` で `1,184.4 ns` だったものの、中央値は `CHICORY` のほうが小さく出ました。この原因調査は [#205](https://github.com/chikoski/wasm-component-kotlin/issues/205) で実施し、以降の再計測結果と要因を次項に整理しています。

#### `#205` の `echoBytes1024` 原因調査と改善後の再計測

同じ `Medium_Phone_API_36.1` 上で `dex2oat` のコンパイル状態、`oatdump` の機械語生成、DEX バイトコード、`logcat`、および再計測の分布を調べた結果、上記の中央値逆転は再計測では再現しませんでした。`#187` 時点で使った `run-on-device.sh` は `cmd package compile -m speed` を実行しない `verify` 状態のままで `CallBenchmark` 全 31 テストを連続実行しており、`benchmark-junit4:1.3.4` で既定で有効になる Perfetto トレースとメソッドトレースの出力が端末側のストレージを消費して `ENOSPC` や強い I/O 競合を引き起こしうる条件になっていました。一方、今回の検証では `CallBenchmark` 全件一括実行を `verify` 状態で再現した際に 11 テストで合計約 794 MB のトレース出力後に `trace_config.pb` 作成で `ENOSPC` が発生しました。

`dex2oat` を `speed` モードで事前適用した上で `oatdump` によりコンパイル結果を検査したところ、`TypesAotComponent$Instance.__export_2_echoBytes`、`Core0FuncGroup_0.func_21`、`AotComponentRuntime.lowerByteArray`、`DirectCanonicalAbi.liftByteArray`、および `LinearMemory.readBytes` と `writeBytes` はいずれも ARM64 ネイティブコードへコンパイルされており、インタプリタフォールバックは見つかりませんでした。

あわせて DEX バイトコードの点検により、呼び出し回数の多い経路で不要な間接呼び出しと測定ループ内の確保が見つかりました。`AotComponentRuntime` の `lowerByteArray` と `lowerString` では `mem.writeBytes` のデフォルト引数により `CanonicalMemory.writeBytes$default` を経由する呼び出しが生成されていたため、4 引数指定の直接呼び出しへ改めました。また、`CallBenchmark` で測定ループ内にあった `Payloads.bytes.getValue` による `Int` ボックス化と `Blob` 生成をループ外へ移動し、`run-on-device.sh` にも `ColdStartProbe` 後の `cmd package compile -m speed -f`、既定での `profiling.mode None` 指定、および計測前の出力ディレクトリ削除を追加しました。

修正後の APK に対して `cmd package compile -m speed -f` と `profiling.mode None` を適用し、出力ディレクトリをテストごとに削除しながら対象メソッドを 1 件ずつ呼び出して、`echoBytes1024` を順序交互で 3 回、あわせて近傍項目を 1 回ずつ再計測した結果を以下に示します。なお、`run-on-device.sh` では既定の `profiling.mode None` で全 31 テストを 1 回の実行へまとめています。

| 修正後の ART 計測項目 | `CHICORY` 最小値 | `CHICORY` 中央値 | `CHICORY` アロケーション | Phase 2 `AOT` 最小値 | Phase 2 `AOT` 中央値 | Phase 2 `AOT` p90 | Phase 2 `AOT` アロケーション |
| :--- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| `add` | 427.0 ns | 469.6 ns | 22 回 | 15.2 ns | 15.6 ns | 16.0 ns | 0 回 |
| `echoName5` | 1,101.1 ns | 1,184.3 ns | 28 回 | 174.6 ns | 180.9 ns | 195.2 ns | 5 回 |
| `echoBytes16` | 1,447.0 ns | 1,492.3 ns | 27 回 | 126.8 ns | 138.0 ns | 155.6 ns | 1 回 |
| `echoBytes1024` 1 回目 | 1,894.7 ns | 1,997.3 ns | 29 回 | 208.3 ns | 285.7 ns | 351.1 ns | 1 回 |
| `echoBytes1024` 2 回目 | 1,485.4 ns | 1,609.0 ns | 29 回 | 343.1 ns | 376.2 ns | 429.3 ns | 1 回 |
| `echoBytes1024` 3 回目 | 1,439.7 ns | 1,522.8 ns | 29 回 | 256.8 ns | 286.7 ns | 609.6 ns | 1 回 |
| `echoBytes65536` | 70,686.0 ns | 78,579.6 ns | 34 回 | 16,102.7 ns | 17,333.7 ns | 19,929.4 ns | 1 回 |
| `echoBlob1024` | 3,099.0 ns | 3,353.7 ns | 39 回 | 470.1 ns | 504.9 ns | 543.1 ns | 7 回 |

修正後の `echoBytes1024` では Phase 2 `AOT` の中央値が `285.7 ns` から `376.2 ns` の範囲に収まり、`CHICORY` の `1,522.8 ns` から `1,997.3 ns` に対して一貫して短い結果になりました。アロケーション回数も `echoBytes1024` と `echoBytes65536` で 2 回から戻り値 `ByteArray` 分のみの 1 回へ、`echoBlob1024` で 10 回から 7 回へ減っています。なお、修正前の `verify` 状態における単独計測でも Phase 2 `AOT` の `echoBytes1024` 中央値は `262.5 ns` から `338.5 ns`、修正前の `speed` 状態における 3 回計測でも `356.5 ns` から `370.2 ns` であり、いずれの条件でも `AOT` のほうが短く推移しました。

同じ ARM64 Android 16 エミュレーター `Medium_Phone_API_36.1` で `benchmark-release-androidTest.apk` を導入し、`cmd package compile -m speed -f com.example.wasm2kt.bench.test` の後に計算系 5 組を追加計測しました。端末環境は `google/sdk_gphone64_arm64/emu64a:16/BP41.250916.009.A1/14246511:user/release-keys`、アーキテクチャは `aarch64` です。ストレージ枯渇を防ぐため、`am instrument` に `-e androidx.benchmark.suppressErrors EMULATOR,LOW-BATTERY,UNLOCKED` と `-e androidx.benchmark.profiling.mode None` を指定して `CallBenchmark` の対象メソッドを 1 件ずつ実行し、計測ごとに端末側の出力ディレクトリを削除して取得しました。

| ART 計算計測項目 | `CHICORY` 中央値 | `CHICORY` アロケーション | Phase 2 `AOT` 中央値 | Phase 2 `AOT` アロケーション |
| :--- | ---: | ---: | ---: | ---: |
| `lcgSum1e6` | 2.18 ms | 25 回 | 1.43 ms | 0 回 |
| `regexCount16B` | 797.6 µs | 8,802 回 | 132.4 µs | 6 回 |
| `regexCount14KiB` | 1.17 ms | 9,349 回 | 908.2 µs | 6 回 |
| `jsonRoundtrip13KiB` | 5.51 ms | 9,341 回 | 1.26 ms | 4 回 |
| `sortBytes64KiB` | 12.90 ms | 34 回 | 5.06 ms | 1 回 |

計算系 5 項目のすべてで Phase 2 `AOT` の中央値が `CHICORY` を下回りました。`lcgSum1e6` の最小値は `CHICORY` の `2.14 ms` に対し Phase 2 `AOT` が `291.4 µs` です。`regexCount16B`、`regexCount14KiB`、および `jsonRoundtrip13KiB` ではアロケーション回数が 8,802〜9,349 回から 4〜6 回へ減少し、`lcgSum1e6` では 0 回になっています。

---

## 9. Phase 3 非同期と WASI 0.3 の AOT 実測結果

`#188` と `#207` に伴い、Phase 3 で追加された自作 `AOT` バックエンドの非同期および WASI 0.3 実行経路について、`CHICORY` バックエンドとの比較計測を実施しました。ここではビルド時生成物、Host JVM の定常呼び出しと初回起動、および Android R8 リリースビルドのサイズを示します。

### 9.1 ビルド時生成物とコンパイル時間

`async-stream`、`async-pipeline`、および `wasi-03` の 3 コンポーネントについて、Apple Silicon macOS 上の OpenJDK 21.0.13 で両バックエンドの生成サイズとビルド時間を比較しました。`CHICORY` では全コンポーネントともインタプリタ実行関数が 0 件でしたが、`.meta` と `plan.json` が出力されます。なお `PrepareAsync` における `CHICORY` 側のビルド時間は `ComponentBuild.run` による分解工程を含む計測であり、`AOT` 側は分解済み出力を再利用して `AotComponentCompiler` と `AotComponentCodegen` のみを計測しています。

| コンポーネントとバックエンド | 関数数 | 分割対象と補助メソッド | 生成クラス数と合計サイズ | メタまたはデータ領域 | ビルド時間 |
| :--- | ---: | :--- | :--- | :--- | ---: |
| `async-stream` Chicory | 16 | — | 6 件 / 91,862 B | `.meta` 604 B / `plan.json` 12,862 B | 2,212 ms |
| `async-stream` AOT | 16 | 0 / 0 | 4 件 / 9,603 B | `.data` 0 B | 124 ms |
| `async-pipeline` Chicory | 217 | — | 12 件 / 270,149 B | `.meta` 11,234 B / `plan.json` 23,765 B | 164 ms |
| `async-pipeline` AOT | 217 | 2 / 5 | 11 件 / 244,765 B | `.data` 7,780 B | 374 ms |
| `wasi-03` Chicory | 206 | — | 12 件 / 282,310 B | `.meta` 15,323 B / `plan.json` 46,580 B | 230 ms |
| `wasi-03` AOT | 206 | 3 / 7 | 10 件 / 267,044 B | `.data` 11,008 B | 223 ms |

`async-stream` の Chicory ビルド時間 2,212 ms には、同一プロセス内で最初に動作する Chicory と `wasm-tools` 関連のウォームアップが含まれます。`AOT` 側では `.meta` と `plan.json` が生成されず、`async-pipeline` と `wasi-03` でも静的初期化用の `.data` のみが出力されます。

### 9.2 Host JVM の定常呼び出しと初回起動

同じ Apple Silicon macOS の OpenJDK 21.0.13 環境で、定常状態の呼び出し中央値と新規 JVM プロセス 9 回の初回起動中央値を計測しました。`async-stream` フィクスチャの `count-up` は解放を行わないバンプアロケータを 1 ページ 64 KiB のメモリ上で使う設計のため、数千回以上の連続呼び出しを行う定常ベンチマークでは `double` と `sum` を対象にしています。`wasi-03` の `run-summary` には 1.0 ms の `monotonic_clock::wait_for` 待機が含まれ、タイマー待機と入出力が支配的なため定常計測では `aot` と `chicory-aot` の 2 系統のみを比較しています。

| 対象とバックエンド | `aot` | `chicory-aot` | `chicory-interp` |
| :--- | ---: | ---: | ---: |
| `async-stream` `double(21)` | 18.5 µs | 19.2 µs | 16.6 µs |
| `async-stream` `sum(32)` | 195.3 µs | 199.1 µs | 219.2 µs |
| `async-pipeline` `process-items(3)` | 114.4 µs | 122.5 µs | 558.5 µs |
| `wasi-03` `run-summary` | 1.98 ms | 1.96 ms | — |

定常状態では、非同期スケジューリングや `run_summary` 内の 1.0 ms タイマー待機が支配的な部分で `aot` と `chicory-aot` が近接しています。一方、新規 JVM プロセスでの初回起動時間は `AOT` が短くなりました。

| 対象とフェーズ | `aot` 中央値 | `chicory-aot` 中央値 |
| :--- | ---: | ---: |
| `async-pipeline` 初回 `open` | 36.25 ms | 99.57 ms |
| `async-pipeline` 初回合計 | 93.66 ms | 149.33 ms |
| `async-pipeline` 2 回目 `open` | 0.33 ms | 1.48 ms |
| `wasi-03` 初回 `open` | 75.03 ms | 123.40 ms |
| `wasi-03` 初回合計 | 135.43 ms | 180.77 ms |
| `wasi-03` 2 回目 `open` | 0.41 ms | 1.50 ms |

### 9.3 Android R8 リリース APK と DEX サイズ

`compileSdk 35`、`minSdk 26`、`JavaVersion.VERSION_17`、`isMinifyEnabled = true`、および `proguard-android-optimize.txt` の条件で、空の `Activity` から非同期エクスポートを呼び出す最小 Android アプリを計測用の一時 composite build 上に構築し、`assembleRelease` の出力サイズを取得しました。対象は `async-pipeline` と `addWasiDependency` を有効にした `wasi-03` で、それぞれ `CHICORY` と `AOT` のリリース APK を計測しています。なおこの計測用プロジェクトは計測後に削除しており、リポジトリ内の常設検証は `Wasm2KtPluginFunctionalTest` が担います。

| 構成とバックエンド | リリース APK | 非圧縮 DEX | DEX クラス数 | DEX メソッド ID | `.meta` と `plan.json` | `.data` 資源 | `wasm2ktCompile` | R8 縮小時間 |
| :--- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| `async-pipeline` Chicory | 313,234 B | 634,020 B | 657 | 3,481 | 34,999 B | 0 B | 0.291 s | 9.941 s |
| `async-pipeline` AOT | 184,337 B | 329,308 B | 375 | 1,832 | 0 B | 7,780 B | 0.495 s | 8.302 s |
| `wasi-03` Chicory | 339,633 B | 677,476 B | 727 | 3,754 | 61,903 B | 0 B | 0.361 s | 7.419 s |
| `wasi-03` AOT | 218,026 B | 398,052 B | 462 | 2,238 | 0 B | 11,008 B | 0.522 s | 4.286 s |

`AOT` の採用により、`async-pipeline` では APK サイズが `313,234 B` から `184,337 B` へ、非圧縮 DEX が `634,020 B` から `329,308 B` へ縮小しました。`wasi-03` でも APK サイズが `339,633 B` から `218,026 B` へ、非圧縮 DEX が `677,476 B` から `398,052 B` へ減っています。ただし、現在の Phase 3 非同期経路は `AotComponentBackend` 経由で `WitWriter`、`WitReader`、`ComponentBackend`、`AsyncAbi`、`CanonicalAbi` を使うため、R8 後の DEX にワイヤ層および非同期 ABI 関連クラスが残存します。このワイヤ層除去の検討は [#206](https://github.com/chikoski/wasm-component-kotlin/issues/206) で追跡しています。

---

## 10. 再計測の手順

```bash
./gradlew -Pwasm2kt.bench :benchmarks:bench          # 定常状態（JVM）。-Pwasm2kt.bench.filter=<正規表現> で絞り込み可
./gradlew -Pwasm2kt.bench :benchmarks:benchTyped     # 型付きブリッジの計測（cargo でビルド）
./gradlew -Pwasm2kt.bench :benchmarks:coldStart      # 初回インスタンス化（JVM）
./gradlew -Pwasm2kt.bench :benchmarks:benchAsync     # 非同期と WASI 0.3 の定常状態（JVM）
./gradlew -Pwasm2kt.bench :benchmarks:coldStartAsync # 非同期と WASI 0.3 の初回起動（JVM）
./gradlew -Pwasm2kt.bench :benchmarks:profile -Pwasm2kt.bench.filter=regex
./gradlew :gradle-plugin:functionalTest --tests "*Wasm2KtPluginFunctionalTest*"  # R8 のワイヤ層クラス除去を含む検証
./gradlew -p samples/android :benchmark:assembleReleaseAndroidTest && samples/android/benchmark/run-on-device.sh
```
