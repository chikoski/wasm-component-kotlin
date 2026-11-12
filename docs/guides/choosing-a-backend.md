# バックエンドの選び方

`wasm2kt` は、同じ生成コード（ワールド DSL）に対して **Chicory** と **Wasmtime**（汎用ブリッジ / 型付きブリッジ）の実行バックエンドを提供します。
実行環境、ビルド時のツール要件、コンポーネントの特性に合わせて選んでください。

## 比較

| 項目 | Chicory（Gradle プラグイン） | Wasmtime（汎用ブリッジ） | Wasmtime（型付きブリッジ） |
| :--- | :--- | :--- | :--- |
| **実行方式** | ビルド時に core モジュールを JVM バイトコードへ変換し、純 JVM で実行 | 実行時に `.wasm`（または `.cwasm`）を Wasmtime でネイティブ実行。値は `Val` を経由 | 実行時に Wasmtime でネイティブ実行。値は生成した Rust の型で直接受け渡す |
| **対象環境** | Android（`minSdk 26+`）、JVM 11 以上 | デスクトップ / サーバー JVM（JDK 21+） | デスクトップ / サーバー JVM（JDK 21+） |
| **ビルド時の要件** | JDK 21 のみ（`cargo`・`wasm-tools` 不要） | `cargo`（1 回のみ）、`wasm-tools` | コンポーネント変更時に `cargo`、`wasm-tools` |
| **ネイティブライブラリ** | 不要（`.jar` / `.dex` のみ） | 共通の共有ライブラリ 1 つ | プロジェクト専用の共有ライブラリ 1 つ |
| **小さな呼び出し（`add(s32, s32)`）** | JVM 約 0.2 µs / ART 約 2〜6 µs | JVM 約 0.5〜1.0 µs | JVM 約 0.7〜0.9 µs |
| **64 KiB の `list<u8>` 往復** | JVM 約 12〜27 µs / ART 約 0.3 ms | JVM 約 3.4〜5.7 ms | JVM 約 29 µs |
| **計算性能（小さな関数）** | Wasmtime の約 1.1〜3 倍 | 基準（1x） | 基準（1x） |
| **大きな wasm 関数（例: regex）** | HotSpot の JIT 上限や 64 KB メソッド上限により遅くなることがある（最大約 50 倍） | 影響なし | 影響なし |
| **async / WASI 0.3** | async は対応（[ADR-0018](../adr/0018-async-abi-on-chicory.md)）。WASI 0.3 は予定（[#13](https://github.com/chikoski/wasm-component-kotlin/issues/13)） | 予定なし（同期の参照実装として維持。[ADR-0005](../adr/0005-async-and-wasi03-chicory-only.md)） | 予定なし |

数値の計測条件と内訳は [性能とサイズ](../explanation/performance.md) にあります。

## 選び方の目安

### 1. Chicory（Gradle プラグイン）を選ぶ場合

* **Android アプリで動かす場合**: ネイティブライブラリの ABI 別ビルドや JNI 配置が不要で、R8 による縮小もそのまま適用できます（APK のランタイム増加分は約 180 KB）
* **純 JVM で配布したい場合**: 実行環境に JDK 21 の FFM フラグやネイティブライブラリを要求せず、JVM 11 以上で動かせます
* **ビルド環境に Rust や `wasm-tools` を入れたくない場合**: 分解ツールは WebAssembly としてプラグインに同梱されています

**注意点**: コンポーネント内に巨大な wasm 関数（バイトコードで 8,000 バイト超や 64 KB 超）が含まれる場合、JVM の JIT 制限やインタプリタへのフォールバックにより計算が遅くなることがあります（[トラブルシューティング](troubleshooting.md#大きな-wasm-関数を含むコンポーネントが遅い)）。

### 2. Wasmtime（汎用ブリッジ）を選ぶ場合

* **サーバー / デスクトップ JVM（JDK 21+）で、大きな計算を行うコンポーネントを動かす場合**: Wasmtime のネイティブ JIT（Cranelift）でコンパイルされるため、関数の大きさに左右されません
* **実行時に任意の `.wasm` ファイルを読み替えたい場合**: `rust-bridge` を 1 度ビルドしておけば、どのコンポーネントでも再ビルドなしで実行できます

### 3. Wasmtime（型付きブリッジ）を選ぶ場合

* **Wasmtime を使いつつ、`list<u8>` や複合型で大きなデータをやり取りする場合**: 汎用ブリッジでは `list<u8>` が要素ごとに `Val::U8` へ変換されるため 64 KiB で数 ms かかりますが、型付きブリッジ（`wasm2kt bridge`、[ADR-0016](../adr/0016-typed-bridge-from-wit-json.md)）では `Vec<u8>` としてまとめてコピーされるため数十 µs になります
