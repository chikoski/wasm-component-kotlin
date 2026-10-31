# CLAUDE.md

コーディングエージェント（Claude Code など）向けの作業メモです。人間向けの説明は README.md と docs/ にあります。
現状とやることは [docs/STATUS.md](docs/STATUS.md)、設計判断は [docs/adr/](docs/adr/) を先に読んでください。

## プロジェクト

WebAssembly コンポーネント（WIT）から Kotlin ホストバインディングを生成します。実行バックエンドは 2 つあります。
Wasmtime（`rust-bridge` + FFM / JNI）と、Chicory（純 JVM。core モジュールをビルド時にコンパイルし、Android 対応）です。

## モジュール

* `:`（ルート）: `wasm2kt.ast` / `wasm2kt.generator` / `wasm2kt.cli` / `wasm2kt.runtime`（Wasmtime ブリッジ）。JVM 21
* `runtime-api`: `ComponentBackend`、`WitHostImportDispatcher`、`FfmJsonCodec`。JVM 11 を維持（Android で使うため）
* `runtime-chicory`: Chicory バックエンド。JVM 11 を維持
* `tooling`: 分解（`Decomposer`）、AOT コンパイル（`CoreModuleCompiler`）、生成（`ComponentCodegen`）、一括実行（`ComponentBuild`）
* `gradle-plugin`: `io.github.wasm2kt`
* `decomposer/`: Rust。`dist/wasm2kt-decompose.wasm` は tooling のリソースとして同梱
* `rust-bridge/`: Rust。Wasmtime ブリッジ
* `samples/android`: composite build。ルートの wrapper で `./gradlew -p samples/android ...`

## コマンド

```bash
./gradlew test                                  # ルート + 各モジュールのテスト
./gradlew check                                 # 上記 + gradle-plugin の functionalTest（TestKit）
./gradlew :runtime-chicory:test                 # Chicory バックエンド単体
./gradlew :test --tests 'wasm2kt.ChicoryDifferentialTest'   # Wasmtime との差分テスト
./gradlew -p samples/android :app:testDebugUnitTest :app:assembleRelease
cargo build --release --manifest-path rust-bridge/Cargo.toml  # ルートのテストに必要
decomposer/build-wasm.sh                        # decomposer を変更したら必ず実行（dist を更新）
```

ルートのテストには `wasm-tools` が必要です（1.220 以降。`WASM_TOOLS_BIN` か `~/.cargo/bin` か PATH から探します）。
Gradle デーモンは `gradle/gradle-daemon-jvm.properties` の指定で **JDK 21** で動きます（`./gradlew` を起動した JDK や `JAVA_HOME` とは無関係）。
JDK 21 がインストールされていれば、Gradle が自動で見つけます。見つからない場合は `~/.gradle/gradle.properties` に
`org.gradle.java.installations.paths=<JDK 21 のパス>`（Homebrew なら `/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home`）を追加してください。
Gradle 8.12 は JDK 25 上では動かない（`What went wrong: 25.0.1` になる）ため、この設定を外さないでください。

## 守ること

* `runtime-api` と `runtime-chicory` に JVM 11 を超える API や `java.lang.foreign` を持ち込まない
* `plan.json` の形式を変えるときは、`decomposer/src/main.rs` の `PLAN_VERSION` と `ComponentPlan.SUPPORTED_VERSION` を同時に上げる。
  `runtime-chicory/src/test/resources/components/` の分解結果も再生成する
* gradle-plugin では、設定時（configuration time）に tooling のクラスを参照しない（ADR-0004）。Kotlin プラグインの型も直接使わない
* 値の JSON 形式、関数名の解決、ホスト import のキーは Wasmtime ブリッジに揃える（ADR-0003）。Chicory 側の挙動を変えたら差分テストを通す
* 生成コードの公開 API（ワールド DSL）を変えるときは、Wasmtime 側と Chicory 側の両方の E2E テストを確認する
* commit メッセージは Conventional Commits 形式（`feat(scope): ...`、`fix: ...`）。本文に理由を書く
* 作業は GitHub Issues（優先度ラベル `P0`/`P1`/`P2`、分野ラベル `area:*`）で管理する。commit や PR では `#番号` で関連 Issue を参照する。状態が変わったら docs/STATUS.md を更新する。設計判断をしたら docs/adr/ に追加する
