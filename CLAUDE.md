# CLAUDE.md

コーディングエージェント（Claude Code など）向けの作業メモです。人間向けの説明は README.md と docs/ にあります。
現状とやることは [docs/STATUS.md](docs/STATUS.md)、設計判断は [docs/adr/](docs/adr/) を先に読んでください。

## プロジェクト

WebAssembly コンポーネント（WIT）から Kotlin ホストバインディングを生成します。実行バックエンドは 2 つあります。
Wasmtime（`rust-bridge` + FFM / JNI）と、Chicory（純 JVM。core モジュールをビルド時にコンパイルし、Android 対応）です。

## モジュール

* `:`（ルート）: 集約のみ（コードなし）。共通の publishing 設定
* `ast`: WIT JSON のモデル（`wasm2kt.ast`）。JVM 21
* `generator`: KotlinPoet によるバインディング生成（`wasm2kt.generator`）。JVM 21
* `cli`: `wasm2kt` コマンド（`wasm2kt.cli`）。サブコマンドは `generate`（サブコマンドなしも同じ）と `bridge`（型付きブリッジ）。`./gradlew run --args=...` はリポジトリルート基準
* `runtime-wasmtime`: Wasmtime ブリッジの Kotlin 側（FFM / JNI、`wasm2kt.runtime`）。JVM 21 + preview
* `runtime-api`: `ComponentBackend`、`WitHostImportDispatcher`、値のバイナリ形式（`WitWriter` / `WitReader`、ADR-0015）。JVM 11 を維持（Android で使うため）
* `runtime-chicory`: Chicory バックエンド。JVM 11 を維持
* `tooling`: 分解（`Decomposer`）、AOT コンパイル（`CoreModuleCompiler`）、生成（`ComponentCodegen`）、一括実行（`ComponentBuild`）、型付きブリッジのクレートの生成と `cargo build`（`TypedBridge`、ADR-0016）
* `gradle-plugin`: `io.github.wasm2kt`
* `decomposer/`: Rust（ツールチェーンは `rust-toolchain.toml` で固定）。`dist/wasm2kt-decompose.wasm` は tooling のリソースとして同梱
* `rust-bridge/`: Rust のワークスペース。`core/`（`wasm2kt-bridge-core`: セッション、形式、C ABI / JNI を `export_bridge!` で公開）と、汎用ブリッジの cdylib（`src/`）。`core/` と `Cargo.lock` は tooling のリソースとして型付きブリッジに同梱される
* `test-support`: テスト専用。plan.json の型に沿って JSON とバイナリ形式を変換し、テストを JSON で書けるようにする
* `integration-tests`: CLI で生成 → コンパイル → Wasmtime / Chicory で実行する E2E テストと差分テスト（テストの作業ディレクトリはリポジトリルート）
* `conformance/`: Component Model の公式テストスイートの写し（`component-model/`）と既知の失敗（`skip.txt`）。`ConformanceTest`（integration-tests）が Chicory で実行する（ADR-0017）
* `fixtures/`: テスト用のコンポーネント（`.wasm` / `.wit`）。integration-tests、tooling、gradle-plugin、decomposer で共有
* `samples/android`: composite build。ルートの wrapper で `./gradlew -p samples/android ...`

## コマンド

```bash
./gradlew test                                  # 全モジュールのテスト（generator のユニットテストは fixtures/*.json を入力に使う）
./gradlew check                                 # 上記 + gradle-plugin の functionalTest（TestKit）
./gradlew :runtime-chicory:test                 # Chicory バックエンド単体
./gradlew :integration-tests:test --tests 'wasm2kt.ChicoryDifferentialTest'   # Wasmtime との差分テスト
./gradlew :integration-tests:test --tests 'wasm2kt.conformance.ConformanceTest'   # 公式テストスイート（Chicory）
conformance/update.sh <commit>                 # 公式テストスイートを更新する（そのあと skip.txt を見直す）
./gradlew :integration-tests:typedBridgeTest    # 型付きブリッジで同じ差分テスト（cargo で fixtures の型付きブリッジを作る。別の JVM）
./gradlew -p samples/android :app:testDebugUnitTest :app:assembleRelease
# ART での確認（ヘッドレスエミュレーター、Android CLI）は samples/android/README.md を参照
cargo build --release --manifest-path rust-bridge/Cargo.toml  # integration-tests に必要
decomposer/build-wasm.sh                        # decomposer を変更したら必ず実行（dist を更新。再現可能ビルド）
decomposer/regenerate-fixtures.sh               # 同上（runtime-chicory のテスト用分解結果を更新）
./gradlew -Pwasm2kt.bench :benchmarks:bench     # 性能の計測（JVM）。ART は samples/android/benchmark。docs/performance.md
./gradlew -Pwasm2kt.bench :benchmarks:benchTyped  # 型付きブリッジの計測（cargo で作る）
```

CI（`.github/workflows/ci.yml`）は jvm / decomposer / native / android / emulator の 5 ジョブです。decomposer ジョブは、同梱 wasm とテスト用分解結果が最新でないと失敗します。ローカルで同じことを確認するには上のコマンドを使います。
integration-tests には `wasm-tools` が必要です（CI は 1.258.0 に固定。`cargo install wasm-tools --version 1.258.0 --locked`。`WASM_TOOLS_BIN` か `~/.cargo/bin` か PATH から探します）。
`rust-bridge` と `decomposer` は `rust-toolchain.toml` で同じ Rust に固定し、CI で `cargo clippy -- -D warnings` と `cargo test` を実行します。
Wasmtime は、decomposer の `wasmtime-environ` と rust-bridge の `wasmtime` を、同じ LTS の同じパッチ版に固定します（今は `=48.0.8`。ADR-0010）。上げるときは両方を同じ PR で上げ、`build-wasm.sh` と `regenerate-fixtures.sh` も実行します。
Gradle デーモンは `gradle/gradle-daemon-jvm.properties` の指定で **JDK 21** で動きます（`./gradlew` を起動した JDK や `JAVA_HOME` とは無関係）。
JDK 21 がインストールされていれば、Gradle が自動で見つけます。見つからない場合は `~/.gradle/gradle.properties` に
`org.gradle.java.installations.paths=<JDK 21 のパス>`（Homebrew なら `/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home`）を追加してください。
Gradle 8.12 は JDK 25 上では動かない（`What went wrong: 25.0.1` になる）ため、この設定を外さないでください。

## 守ること

* `runtime-api` と `runtime-chicory` に JVM 11 を超える API や `java.lang.foreign` を持ち込まない。Android の API 26 にない API も使わない（`check` の `animalsnifferMain` が検査する。#80）
* `plan.json` の形式を変えるときは、`decomposer/src/main.rs` の `PLAN_VERSION` と `ComponentPlan.SUPPORTED_VERSION` を同時に上げる。
  `decomposer/regenerate-fixtures.sh` で `runtime-chicory/src/test/resources/components/` の分解結果も再生成する
* gradle-plugin では、設定時（configuration time）に tooling のクラスを参照しない（ADR-0004）。Kotlin プラグインの型も直接使わない
* 値のバイナリ形式、関数名の解決、ホスト import のキーは両バックエンドで揃える（ADR-0015、ADR-0011）。Chicory 側の挙動を変えたら差分テストを通す
* 汎用ブリッジと型付きブリッジは同じ C ABI / JNI の関数を公開する（`rust-bridge/core` の `export_bridge!`）。resource の規則は `core` の関数を両方から使う。`core` か `TypedBridge.kt` を変えたら `typedBridgeTest` も通す（ADR-0016）
* Chicory の挙動を直したら `ConformanceTest` も通し、通るようになった項目を `conformance/skip.txt` から消す（ADR-0017）
* `runtime-chicory` の Canonical ABI は仕様の `trap_if` に従う。ゲスト起因の違反は `ComponentTrapException`（インスタンスを poison）、ホストの値の誤りは `IllegalArgumentException` / `IllegalStateException`（poison しない）。ゲストのコードを動かす箇所は `guest { }` で囲む（ADR-0007）
* ホスト定義 resource（world 直下か、import した interface の resource）は、値の境界では rep（整数）で受け渡す。オブジェクトとの対応は生成セッションが束縛する `HostResourceTable` が持ち、drop は `[resource-drop]<name>` の dispatch で伝える。両バックエンドで揃える（ADR-0008）
* async と WASI 0.3 は Chicory バックエンドだけで実装する。Wasmtime バックエンドは同期の機能の参照実装として維持する（ADR-0005）
* 生成コードの公開 API（ワールド DSL）を変えるときは、Wasmtime 側と Chicory 側の両方の E2E テストを確認する
* commit メッセージは Conventional Commits 形式（`feat(scope): ...`、`fix: ...`）。本文に理由を書く
* 作業は GitHub Issues（優先度ラベル `P0`/`P1`/`P2`、分野ラベル `area:*`）で管理する。commit や PR では `#番号` で関連 Issue を参照する。状態が変わったら docs/STATUS.md を更新する。設計判断をしたら docs/adr/ に追加する
