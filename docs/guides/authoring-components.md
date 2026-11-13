# コンポーネント作者向けガイド

このガイドでは、Rust などで WebAssembly コンポーネント（`.wasm`）を作成し、`wasm2kt` のホストから呼び出す際の条件と注意点を説明します。

## 1. コンポーネントの条件

`wasm2kt` が受け取る `.wasm` は、**WebAssembly Component Model 形式のバイナリ**である必要があります（Core WebAssembly モジュール単体ではありません）。
対応している WIT の型や機能の詳細は [機能の対応表](../reference/feature-support.md) にまとめています。

特に Chicory バックエンド（Gradle プラグイン / Android）で動かす場合は、以下の点に注意してください。

* **WASI の import**: 現時点では `wasi:cli` や `wasi:io` などのホスト実装を同梱していません（[#13](https://github.com/chikoski/wasm-component-kotlin/issues/13) で WASI 0.3 を予定）。そのため、Rust でゲストを書く場合は `wasm32-unknown-unknown` ターゲット + `wit-bindgen` で WASI に依存しないコンポーネントにするか、import される WASI インターフェースをホスト側で実装して渡す必要があります
* **33 個以上の `flags`**: `wasm-tools` 1.258 以降は 33 個以上のフラグを持つ `flags` 型を仕様上の制限として拒否します
* **合成コンポーネント**: メモリを持つ子コンポーネント同士の合成（文字列や `list` の受け渡し）には対応していますが（[ADR-0013](../adr/0013-multi-memory-adapters.md)）、子コンポーネント間で `resource` の所有権を受け渡す組み込み関数（`resource.transfer-own` など）には未対応です（[#70](https://github.com/chikoski/wasm-component-kotlin/issues/70)）

## 2. Rust でコンポーネントを作る例

このリポジトリの `fixtures/` と同様に、`wasm32-unknown-unknown` と `wit-bindgen`、`wasm-tools component new` を使う最小の手順です。

### `Cargo.toml`

```toml
[package]
name = "my-component"
version = "0.1.0"
edition = "2021"

[lib]
crate-type = ["cdylib"]

[dependencies]
wit-bindgen = "0.41"
```

### ビルド手順

```bash
# 1. Core wasm モジュールをビルドする
cargo build --release --target wasm32-unknown-unknown

# 2. WIT 定義を埋め込み、Component Model の .wasm に変換する
wasm-tools component embed wit/ target/wasm32-unknown-unknown/release/my_component.wasm -o embedded.wasm
wasm-tools component new embedded.wasm -o my-component.wasm
```

`cargo component` を使う場合で、WASI の import を含めたくないときは、`wasm32-unknown-unknown` ターゲットを使うか、不要な標準出力・環境変数アクセスを避けてください。

## 3. 未対応機能の事前検査（`failOnUnsupportedFeatures`）

Gradle プラグインでビルドする際、`wasm2ktDecompose<Name>` タスクは分解後の `plan.json` を検査し、Chicory バックエンドが未対応の機能（`map<K, V>` などの型、未対応の組み込み関数）が含まれていれば警告を出します（[#39](https://github.com/chikoski/wasm-component-kotlin/issues/39)、[#78](https://github.com/chikoski/wasm-component-kotlin/issues/78)）。

CI などで未対応機能の使用をビルドエラーにしたい場合は、`build.gradle.kts` で `failOnUnsupportedFeatures.set(true)` を設定します。

```kotlin
wasm2kt {
    failOnUnsupportedFeatures.set(true)
    components {
        register("myComponent") {
            source.set(file("src/main/wasm/my-component.wasm"))
            packageName.set("com.example.mycomponent")
        }
    }
}
```

## 4. 関数の大きさとパフォーマンス（Chicory バックエンド）

Chicory のビルド時コンパイラは、wasm の各関数を JVM のメソッドへ変換します。

* **JVM バイトコードで 64 KB を超える巨大な wasm 関数**: JVM のメソッドサイズ上限を超えるため、その関数だけインタプリタ実行にフォールバックします（ビルド時に `wasm2ktCompile<Name>` が警告を表示します）
* **JVM バイトコードで 8,000 バイトを超える関数**: HotSpot JVM の既定設定（`-XX:+DontCompileHugeMethods`）では JIT コンパイルされません（[#57](https://github.com/chikoski/wasm-component-kotlin/issues/57)）

たとえば `regex` クレートの DFA のような巨大な関数を含むコンポーネントで計算時間が問題になる場合は、デスクトップ / サーバー JVM では `-XX:-DontCompileHugeMethods` を付与するか、Wasmtime バックエンドの利用を検討してください（[性能とサイズ](../explanation/performance.md#3-計算の性能heavy)）。
