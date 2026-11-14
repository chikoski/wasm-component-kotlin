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

## 4. 関数の大きさとパフォーマンス

Chicory のビルド時コンパイラは wasm の各関数を JVM メソッドへ変換し、さらにビルドパイプライン内の `JvmMethodSplitter` が 7,500 バイトを目安に大きなメソッドを自動分割します。これにより、多くの関数で HotSpot JVM と Android ART の双方がネイティブコンパイルを適用できます。自動分割の設計は [ADR-0021](../adr/0021-split-large-chicory-methods.md) にまとめています。

ビルドパイプラインには 4 つのサイズ境界があります。

* JVM クラスファイル上限の 64 KB
* HotSpot JIT 上限の 8,000 バイト
* Android ART コンパイラ上限の 16,383 DEX コード単位
* 自動分割の目標上限である 7,500 バイト

Chicory のコード生成段階で 64 KB を超えた関数は、バイトコードが出力されず Chicory のインタプリタ実行へフォールバックします。この状態の関数は `JvmMethodSplitter` でも分割できません。`regex` クレートの DFA のような巨大関数でこの警告が出た場合は、ゲスト側の関数分割を検討してください。

分割後も 21,000 JVM バイト以上または 8,000 JVM バイト超のメソッドが残った場合は、`wasm2ktCompileWarnings<Name>` タスクが ART 上限や HotSpot 上限の警告を表示します。デスクトップやサーバーの JVM で HotSpot 上限の警告が出た場合は `-XX:-DontCompileHugeMethods` の指定が有効です。詳細は [トラブルシューティング](troubleshooting.md) と [性能とサイズ](../explanation/performance.md) に記載しています。
