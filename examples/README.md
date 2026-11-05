# Examples

`wasm-component-kotlin` の機能別サンプルコード一覧です。すべての `examples/*/Main.kt` は CI でバインディング生成・コンパイル・実行が検証されています。

| ディレクトリ | 概要 | バックエンド / ビルド手段 |
|---|---|---|
| [01-arithmetic](01-arithmetic/README.md) | 最小構成の四則演算コンポーネントの呼び出し | Wasmtime (FFM) / CLI |
| [02-host-imports](02-host-imports/README.md) | ホスト関数のインポート（`Imports` / `imports(...)`）とコールバック | Wasmtime (FFM) / CLI |
| [03-counter-resource](03-counter-resource/README.md) | `resource` のライフサイクル管理（`use` / `borrow` / `own`） | Wasmtime (FFM) / CLI |
| [04-algebraic-types-and-mock](04-algebraic-types-and-mock/README.md) | 代数的データ型（`record`, `variant`, `flags`, `option`, `result`）と `InMemoryWasmtimeSession` による単体テスト | `InMemoryWasmtimeSession` |
| [05-chicory-gradle-plugin](05-chicory-gradle-plugin/README.md) | `io.github.wasm2kt` Gradle プラグインによる `.wasm` 分解・AOT コンパイルと Pure JVM 実行 | Chicory (Pure JVM) / Gradle プラグイン |

Android アプリへの組み込みサンプルは [samples/android](../samples/android/README.md) を参照してください。
