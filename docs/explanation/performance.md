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
  * 巨大な wasm 関数を含むコード（`regex-count`）は、HotSpot JVM の既定では 8,000 バイト超のメソッドが JIT されないこと、および 64 KB 超の関数がインタプリタにフォールバックすることにより、Wasmtime より最大約 50 倍遅くなります（`-XX:-DontCompileHugeMethods` で改善します。[#57](https://github.com/chikoski/wasm-component-kotlin/issues/57)）
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

* `heavy` コンポーネントの 1,012 関数のうち 1 つ（`func_366`、wasm で 56 KB）が JVM メソッドの上限（64 KB）を超え、インタプリタにフォールバックします（パターンのコンパイル部分がこれに該当します）
* また 13 個のメソッドが JVM バイトコードで 8,000 バイトを超え、HotSpot の既定（`-XX:+DontCompileHugeMethods`）では JIT コンパイルされません。`-XX:-DontCompileHugeMethods` を指定すると 14 KiB の `regex-count` は 3.7 ms から 1.1 ms に短縮されます（[#57](https://github.com/chikoski/wasm-component-kotlin/issues/57)）

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

## 7. 再計測の手順

```bash
./gradlew -Pwasm2kt.bench :benchmarks:bench        # 定常状態（JVM）。-Pwasm2kt.bench.filter=<正規表現> で絞り込み可
./gradlew -Pwasm2kt.bench :benchmarks:benchTyped   # 型付きブリッジの計測（cargo でビルド）
./gradlew -Pwasm2kt.bench :benchmarks:coldStart    # 初回インスタンス化（JVM）
./gradlew -Pwasm2kt.bench :benchmarks:profile -Pwasm2kt.bench.filter=regex
./gradlew -p samples/android :benchmark:assembleReleaseAndroidTest && samples/android/benchmark/run-on-device.sh
```
