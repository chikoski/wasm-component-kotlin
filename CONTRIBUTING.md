# Contributing to `wasm2kt`

`wasm2kt` の開発環境・ビルドとテストの手順・開発時のルールをまとめています。
プロジェクトの現在の状態と優先課題は [docs/STATUS.md](docs/STATUS.md)、設計判断の理由は [docs/adr/](docs/adr/README.md)、全体構造は [docs/explanation/architecture.md](docs/explanation/architecture.md) を参照してください。

## 1. 必要なツール

* JDK 21 を使用します。Gradle デーモンは `gradle/gradle-daemon-jvm.properties` の指定により JDK 21 で起動します。Gradle が JDK 21 を見つけられない場合は、`~/.gradle/gradle.properties` に `org.gradle.java.installations.paths=<JDK 21 のパス>` を追加してください
* **Rust ツールチェーン (`1.97.0`)**: `rust-bridge/rust-toolchain.toml` と `decomposer/rust-toolchain.toml` で同じバージョンに固定されています
* **`wasm-tools` (`1.258.0`)**: `integration-tests` の実行に必要です（`WASM_TOOLS_BIN`、`~/.cargo/bin`、または `PATH` から探索します）
  ```bash
  cargo install wasm-tools --version 1.258.0 --locked
  ```
* Android SDK

`gradle-plugin` の `functionalTest` を含む `./gradlew check` と `samples/android` のビルドには、Android SDK Platform 35 と `ANDROID_HOME` 環境変数が必要です。`samples/android` 単体のビルドでは `local.properties` の `sdk.dir` 指定にも対応しています。

## 2. よく使うコマンド

```bash
# Rust 製の汎用 Wasmtime ブリッジのビルド（integration-tests の前に必要）
cargo build --release --manifest-path rust-bridge/Cargo.toml

# テストの実行
./gradlew test                                  # 全モジュールのユニットテスト + integration-tests
./gradlew check                                 # 上記 + animalsniffer + gradle-plugin の functionalTest
./gradlew :runtime-chicory:test                 # Chicory バックエンド単体テスト
./gradlew :integration-tests:test --tests 'wasm2kt.ChicoryDifferentialTest'       # Wasmtime との差分テスト
./gradlew :integration-tests:test --tests 'wasm2kt.conformance.ConformanceTest'   # Component Model 公式テストスイート
./gradlew :integration-tests:typedBridgeTest    # 型付きブリッジでの差分テスト（cargo でビルドして別 JVM で実行）

# Android サンプルのテスト・ビルド
./gradlew -p samples/android :app:testDebugUnitTest :app:assembleRelease
# エミュレーター（ART）での connectedAndroidTest 手順は samples/android/README.md を参照

# decomposer を変更したとき（両方とも実行してコミットに含める）
decomposer/build-wasm.sh                        # dist/wasm2kt-decompose.wasm を更新（再現可能ビルド）
decomposer/regenerate-fixtures.sh               # runtime-chicory のテスト用分解結果を再生成

# Rust コードの検査
cargo clippy --manifest-path rust-bridge/Cargo.toml --all-targets -- -D warnings
cargo test --manifest-path rust-bridge/Cargo.toml
cargo clippy --manifest-path decomposer/Cargo.toml --all-targets -- -D warnings
cargo test --manifest-path decomposer/Cargo.toml

# 差分ミューテーション検査用の cargo-mutants 実行
# 新規ファイルがある場合は先に git add -N で追跡予定へ加えます
mkdir -p .tmp
git diff --no-ext-diff --no-color --relative=rust-bridge "$(git merge-base origin/main HEAD)" > .tmp/mutants-rust-bridge.diff
cargo mutants --manifest-path rust-bridge/Cargo.toml --workspace --in-diff .tmp/mutants-rust-bridge.diff -o .tmp/mutants-rust-bridge
git diff --no-ext-diff --no-color --relative=decomposer "$(git merge-base origin/main HEAD)" > .tmp/mutants-decomposer.diff
WASM2KT_FIXTURES="$(pwd)/fixtures" cargo mutants --manifest-path decomposer/Cargo.toml --in-diff .tmp/mutants-decomposer.diff -o .tmp/mutants-decomposer

# ベンチマーク（詳細は docs/explanation/performance.md）
./gradlew -Pwasm2kt.bench :benchmarks:bench
./gradlew -Pwasm2kt.bench :benchmarks:benchTyped
```

`rust-bridge` と `decomposer` にはそれぞれ `.cargo/mutants.toml` を配置しています。FFI と JNI のエントリポイント群および `main` 関数を除外しつつ、変更差分の境界検査強度を確認できます。`cargo-mutants` が未インストールの環境では差分検査を省略して通常の検証へ進めます。固定のミューテーションスコアによる失敗ゲートは設けません。生き残った変異体はコードレビュー時のテスト補強判断に用います。

CI（[`.github/workflows/ci.yml`](.github/workflows/ci.yml)）は `jvm`・`decomposer`・`native`・`android`・`emulator` の 5 ジョブで構成されています。`decomposer` ジョブは、同梱の `dist/wasm2kt-decompose.wasm` とテスト用の分解結果が最新でない場合に失敗します。

## 3. 守ること（設計・実装の不変条件）

* **JVM 17 と Android API 26 の維持**: `runtime-api`、`runtime-canonical`、`runtime-aot`、および `runtime-chicory` には JVM 17 を超える API や `java.lang.foreign` を持ち込まないでください。また Android API 26 に存在しない API も使えません（`./gradlew check` の `animalsnifferMain` で検査されます）
* **`plan.json` のバージョン**: `plan.json` のスキーマを変更するときは、`decomposer/src/main.rs` の `PLAN_VERSION` と `ComponentPlan.SUPPORTED_VERSION` を同時に上げ、`decomposer/build-wasm.sh` と `decomposer/regenerate-fixtures.sh` を実行してください
* **Wasmtime の LTS 固定**: `decomposer` の `wasmtime-environ` と `rust-bridge` の `wasmtime` は、同じ LTS の同じパッチ版（現在は `=48.0.10`）に揃えます（[ADR-0010](docs/adr/0010-wasmtime-lts.md)）
* **Gradle プラグインのクラスローダー分離**: `gradle-plugin` では設定時（configuration time）に `tooling` のクラスや Kotlin Gradle Plugin の型を直接参照しないでください（[ADR-0004](docs/adr/0004-isolated-tooling-classloader.md)）
* **両バックエンドの整合性**:
  * 値のバイナリ形式（`WitWriter` / `WitReader`）、関数名の解決、ホスト import のキーは Chicory と Wasmtime で揃えます（[ADR-0011](docs/adr/0011-qualified-interface-keys.md)、[ADR-0015](docs/adr/0015-binary-value-boundary.md)）。Chicory 側の挙動を変えたら `ChicoryDifferentialTest` と `ConformanceTest` を通してください。例外として、[ADR-0023](docs/adr/0023-custom-wasm-compiler-and-static-jvm-backend.md) に基づく `AOT` バックエンドの同期コンポーネントでは、エクスポートとワールドローカルなホスト import に `DirectCanonicalAbi` と静的直接結合を使用し、`with` 指定の外部ライブラリ interface のみ `AotMappedImportBridge` で `Linker` へ中継します。非同期コンポーネントでは `AotComponentBackend` と `:runtime-canonical` の共有 `AsyncAbi` を使用します。いずれの経路でも ADR-0011 のキー規則、ADR-0007 のトラップと poison、および ADR-0008 の資源寿命規則は維持します
  * 汎用ブリッジと型付きブリッジは `rust-bridge/core` の `export_bridge!` で同じ C ABI / JNI を公開します。`core` または `TypedBridge.kt` を変更したら `typedBridgeTest` を通してください（[ADR-0016](docs/adr/0016-typed-bridge-from-wit-json.md)）
  * Canonical ABI の検査は仕様の `trap_if` に従います。ゲスト起因の違反は `ComponentTrapException`（インスタンスを poison）、ホスト側の引数エラーは `IllegalArgumentException` / `IllegalStateException`（poison しない）とし、ゲストのコードを動かす箇所は `guest { }` で囲みます（[ADR-0007](docs/adr/0007-canonical-abi-validation.md)）
  * async と WASI 0.3 は Chicory バックエンドおよびオプトイン `AOT` バックエンドで実装し、Wasmtime バックエンドは同期機能の参照実装として維持します（[ADR-0005](docs/adr/0005-async-and-wasi03-chicory-only.md)、[ADR-0023](docs/adr/0023-custom-wasm-compiler-and-static-jvm-backend.md) Phase 3）

## 4. コミット・PR・ドキュメントの更新

* コミットメッセージは Conventional Commits 形式（`feat(scope): ...`、`fix(scope): ...`、破壊的変更は `!` を付与）で書き、本文に背景と理由を記載してください
* 作業は GitHub Issues（優先度ラベル `P0` / `P1` / `P2`、分野ラベル `area:*`）で管理します。コミットや PR では `#番号` で関連 Issue を参照してください
* プロジェクトの現状が変わったら [docs/STATUS.md](docs/STATUS.md) を、破壊的変更や主要な変更があれば [CHANGELOG.md](CHANGELOG.md) を更新し、重要な設計判断を行った場合は [docs/adr/](docs/adr/README.md) に新しい ADR を追加してください

PR を作成する際は [.github/pull_request_template.md](.github/pull_request_template.md) を使用します。関連する Issue 番号やドキュメントの更新有無をテンプレートの項目に沿って確認してください。変更箇所に応じたテストも実行し、テンプレートのチェックリストで確認します。
