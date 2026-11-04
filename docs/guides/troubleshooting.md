# トラブルシューティング

よくある症状と原因・対処方法をまとめています。

## ビルド・環境まわり

### `What went wrong: 25.0.1` などで Gradle が起動直後に失敗する

* **原因**: Gradle 8.12 は JDK 25 上では動作しません。また `wasm2kt` のビルド時ツール（`tooling` と `gradle-plugin`）は JDK 21 向けにコンパイルされています
* **対処**:
  1. プロジェクトに `gradle/gradle-daemon-jvm.properties`（`toolchainVersion=21`）があることを確認してください（このリポジトリと `samples/android` には設定済みです）
  2. JDK 21 がインストールされているにもかかわらず Gradle が見つけられない場合は、`~/.gradle/gradle.properties` に JDK 21 のパスを指定してください（macOS Homebrew の例）：
     ```properties
     org.gradle.java.installations.paths=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
     ```

### `wasm-tools exited with code ...` または `wasm-tools` が見つからない

* **原因**: CLI（`wasm2kt generate` / `wasm2kt bridge`）や `integration-tests` は、`.wit` / `.wasm` から JSON AST を抽出するために `wasm-tools` を呼び出します（Gradle プラグインの Chicory 経路では `wasm-tools` は使われません）
* **対処**: `wasm-tools`（CI は `1.258.0`）をインストールするか、`--wasm-tools-path`（テスト時は環境変数 `WASM_TOOLS_BIN`）でバイナリのパスを指定してください
  ```bash
  cargo install wasm-tools --version 1.258.0 --locked
  ```

### 同じ package の異なるバージョンを含む WIT で生成が失敗する

* **原因**: Kotlin のパッケージ名にはバージョン番号を含めないため（`<base>.<ns>.<pkg>.<iface>`）、1 回の生成で同じパッケージの異なるバージョン（例: `wasi:http@0.2.0` と `wasi:http@0.3.0`）を同時に扱うことはできません（[ADR-0011](../adr/0011-qualified-interface-keys.md)）
* **対処**: 使用する WIT 依存のバージョンを揃えるか、world ごとに生成を分けてください

---

## Wasmtime バックエンド（実行時）

### ネイティブブリッジ（`libwasm2kt_wasmtime_bridge`）が見つからない

* **原因**: `runtime-wasmtime` が共有ライブラリを見つけられていません
* **対処**: `BridgeLibrary` は以下の順序で探します。いずれかの場所に配置してください（[#65](https://github.com/chikoski/wasm-component-kotlin/issues/65)）
  1. JVM システムプロパティ `-Dwasm2kt.bridge.lib=/path/to/libwasm2kt_wasmtime_bridge.dylib`（Linux は `.so`、Windows は `.dll`）
  2. 環境変数 `WASM2KT_BRIDGE_LIB`
  3. クラスパス上の `META-INF/wasm2kt/native/<platform>/`（`wasm2kt bridge --build --resources-output` で出力したディレクトリ）
  4. リポジトリルートの `rust-bridge/target/release/`（事前に `cargo build --release --manifest-path rust-bridge/Cargo.toml` が必要です）

### `UnsupportedOperationException` や `IllegalCallerException`（`java.lang.foreign`）が出る

* **原因**: Java 21 で FFM API を使うための JVM 引数が不足しています
* **対処**: 実行時の `jvmArgs` に `--enable-preview` と `--enable-native-access=ALL-UNNAMED` を追加してください

### `withSession` に `.cwasm` を渡すと失敗する

* **原因**: `.cwasm` は未検証のネイティブ機械語を含むため、意図しない読み込みを防ぐ目的で `withSession` / `openSession` からは拒否されます（[#33](https://github.com/chikoski/wasm-component-kotlin/issues/33)）
* **対処**: 自分のビルドで作成した信頼できる `.cwasm` を読み込む場合は、`withPrecompiledSession` / `openPrecompiledSession` を使ってください

---

## Chicory バックエンド（ビルド時・実行時）

### ビルド時に `uses features the Chicory backend does not support yet` と警告が出る

* **原因**: コンポーネントが Chicory バックエンドの未対応機能（async、子コンポーネント間のリソース転送など）を含んでいます（[#39](https://github.com/chikoski/wasm-component-kotlin/issues/39)）
* **対処**: 該当するコード経路を実行時に呼ばなければ動作しますが、呼ぶと例外になります。ビルド時点で失敗させたい場合は `wasm2kt { failOnUnsupportedFeatures.set(true) }` を設定してください

### ビルド時に `are too large for a JVM method and will be interpreted` と警告が出る / 大きな wasm 関数を含むコンポーネントが遅い

* **原因**:
  1. コンパイル後の JVM バイトコードが 64 KB を超える関数は、JVM のクラスファイル制限によりインタプリタ実行にフォールバックします
  2. 8,000 バイトを超えるメソッドは、HotSpot JVM の既定では JIT コンパイルされません（[#57](https://github.com/chikoski/wasm-component-kotlin/issues/57)）
* **対処**: デスクトップ / サーバーの HotSpot JVM で実行する場合は、JVM 引数に `-XX:-DontCompileHugeMethods` を追加すると 8,000 バイト超のメソッドも JIT コンパイルされます（[性能とサイズ](../explanation/performance.md#3-計算の性能heavy)）
