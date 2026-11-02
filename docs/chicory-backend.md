# Chicory バックエンドと Gradle プラグイン (Android 対応)

`wasm2kt` には、Wasmtime（ネイティブ）に加えて **[Chicory](https://github.com/dylibso/chicory)（純 JVM の WebAssembly ランタイム）** で
コンポーネントを動かすバックエンドがあります。Gradle プラグイン `io.github.wasm2kt` を使うと、コンポーネントの core モジュールを
**ビルド時に JVM バイトコードへコンパイル**し、生成されたバインディングからそのまま呼び出せます。ネイティブライブラリも JNI も不要なので、
Android（ART）でもデスクトップ JVM と同じコードが動きます。

生成されるワールド DSL は Wasmtime 版と同じです。違うのは `withSession` に渡すものだけです。

```kotlin
// Wasmtime（ネイティブ）
withSession(Paths.get("calculator.wasm")) { add(1, 2) }

// Chicory（ビルド時コンパイル済み、Android 可）
withSession(CalculatorComponent) { add(1, 2) }
```

---

## 1. Gradle プラグインの使い方

```kotlin
// build.gradle.kts（Kotlin/JVM でも Android でも同じ）
plugins {
    id("com.android.application")          // または kotlin("jvm")
    id("org.jetbrains.kotlin.android")
    id("io.github.wasm2kt")
}

wasm2kt {
    components {
        register("calculator") {
            source.set(file("src/main/wasm/calculator.wasm"))
            packageName.set("com.example.calculator")
            // world.set("calculator")        // WIT に world が複数ある場合のみ
        }
    }
}
```

コンポーネントごとに次が生成されます。

| 生成物 | 内容 |
| :--- | :--- |
| `com.example.calculator.RootWorld` など | WIT から生成したバインディング（Wasmtime セッションを含まない版） |
| `com.example.calculator.CalculatorComponent` | `ComponentBackendFactory` の実装。`withSession` / `openSession` に渡す |
| `wasm2kt-calculator.jar`（`implementation` に自動追加） | core モジュールの AOT コンパイル結果、`.meta`、`plan.json` |

プラグインは次も自動で行います。

* `io.github.wasm2kt:runtime-chicory`（プラグインと同じバージョン）を `implementation` に追加（`wasm2kt { addRuntimeDependency.set(false) }` で無効化）
* Kotlin コンパイラに `-Xcontext-parameters` を追加（生成 DSL が Context Parameters を使うため）
* コンポーネントが Chicory バックエンドの未対応の機能（コンポーネントの組み込み関数、型、async）を使っていれば、分解のタスクで警告する。`wasm2kt { failOnUnsupportedFeatures.set(true) }` にすると、ビルドを失敗させる

> `.wasm` から生成した場合、ワールド名はコンポーネント自身の world（通常 `root` → `RootWorld`）になります。

### 要件

* Gradle デーモンを **JDK 21 以上**で実行すること（ツール群が JDK 21 向けにビルドされているため）。このリポジトリとサンプルは `gradle/gradle-daemon-jvm.properties` で JDK 21 を指定しています
* アプリ側の実行環境: JVM 11 以上 / Android **minSdk 26** 以上
* `wasm-tools` や Rust ツールチェーンは**不要**です（下記の分解ツールは WebAssembly として同梱）

---

## 2. ビルド時のパイプライン

```text
calculator.wasm
   │  wasm2ktDecomposeCalculator   … 同梱の wasm2kt-decompose.wasm を Chicory (WASI) 上で実行
   ▼
decomposed/  core0.wasm … coreN.wasm, plan.json, wit.json
   │                                   │
   │  wasm2ktCompileCalculator          │  wasm2ktGenerateCalculator(<Variant>)
   ▼                                   ▼
wasm2kt-calculator.jar               Kotlin ソース（バインディング + CalculatorComponent）
  (CoreNMachine.class, CoreN.meta, plan.json)
```

* **分解 (decompose)**: `decomposer/`（Rust）が `wasmtime-environ` でコンポーネントを平坦化し、core モジュール群と
  インスタンス化手順 `plan.json`、WIT の JSON `wit.json`（`wasm-tools component wit --json` と同一形式）を出力します。
  jco と同じ手法です。プラグインには `wasm32-wasip1` 向けにビルドした分解ツールが同梱されていて、Chicory 上で動きます。
* **コンパイル (compile)**: Chicory の build-time compiler で各 core モジュールを `Machine` クラスにコンパイルし、
  関数本体を除いた `.meta` モジュールと `plan.json` と一緒に jar にまとめます。JVM メソッドに収まらない巨大な関数だけは
  インタプリタにフォールバックします（その場合はビルド時に警告が出ます）。
* **生成 (generate)**: `wit.json` からバインディングを、`plan.json` から `CalculatorComponent` を生成します。

3 つのタスクはいずれも `@CacheableTask` で、configuration cache にも対応しています。ツール群（Chicory のコンパイラ、ASM、
KotlinPoet）は Gradle ワーカー内の**独立したクラスローダー**で動くため、Android Gradle Plugin の ASM と衝突しません。
ツールの依存は `wasm2ktTooling` 構成で解決されます。

## 3. 実行時の仕組み (`runtime-chicory`)

`ChicoryComponent` が `plan.json` を再生して core インスタンスを生成・配線し（関数・メモリ・テーブル・グローバル、
トランポリン、インスタンスフラグ）、Canonical ABI で値を lift / lower します。

* 値の受け渡しは Wasmtime ブリッジと同じ JSON 形式（`runtime-api` の `ComponentBackend`）なので、生成コードは共通です
* 文字列（utf8 / utf16 / latin1+utf16）、list、record、variant / enum / option / result（flat 表現の join を含む）、flags、
  16 個を超える flat 引数・2 個以上の flat 戻り値（線形メモリ経由）、post-return に対応
* ホスト import（ディスパッチキーは Wasmtime ブリッジと同じ）、コンポーネントが定義する resource（`resource.new/rep/drop`、
  デストラクタ、ホスト側ハンドル）、ホストが定義する resource（import された resource。own / borrow の受け渡し、
  drop 時の `[resource-drop]<name>` 呼び出し。[ADR-0008](adr/0008-host-defined-resources.md)）に対応
* `ChicoryComponent.fromDirectory(dir)` を使うと、分解結果をインタプリタで直接実行することもできます（テスト・検証用）

### エラーとトラップ

Canonical ABI の検証は仕様（`definitions.py` の `trap_if`）に従います（[ADR-0007](adr/0007-canonical-abi-validation.md)）。

| 状況 | 例外 | インスタンス |
| :--- | :--- | :--- |
| ゲストが規則を破った（不正な `char` / 判別子 / 文字列エンコーディング、範囲外やアラインされていないポインタ、上限を超える長さ、`cabi_realloc` や post-return 中の import 呼び出し、ホスト import からの再入）、core wasm のトラップ | `ComponentTrapException` | 使えなくなる（以降の呼び出しも `ComponentTrapException`） |
| ゲストの実行中にホスト import が例外を投げた | その例外 | 使えなくなる |
| ホストの値が WIT の型に合わない（範囲外の整数、1 文字でない `char`、孤立サロゲートを含む文字列など）、未知のハンドル、引数の数の違い | `IllegalArgumentException` / `IllegalStateException` | 使い続けられる |

`ComponentTrapException`（`runtime-api`）は `IllegalStateException` を継承しています。トラップしたら新しいセッションを開いてください。
浮動小数点の NaN は、lift / lower のどちらでも正規の NaN に揃えます。

Wasmtime ブリッジとの差分テスト（`ChicoryDifferentialTest`）で、同じ呼び出し列に対して同じ結果になることを確認しています。

### 未対応

* async（WASI P3: `future` / `stream`）とコールバック
* WASI のホスト実装（`wasi:cli` などを import するコンポーネントは、現時点では import をホストで実装する必要があります）
* 子コンポーネントの間で resource を受け渡す合成コンポーネント（アダプターが resource の移動や同期呼び出しの組み込み関数を使うため）

未対応の機能は、使われた時点で分かりやすい例外になります。Gradle プラグインは、plan から分かるもの（未対応の組み込み関数・型・async）を、ビルド時に警告として表示します（`failOnUnsupportedFeatures` でエラーにもできます）。ビルドのキャッシュから復元されたときは、警告は表示されません。

---

## 4. サンプル

* `samples/android`: Android アプリ（composite build でこのリポジトリのプラグインを使用）。roundtrip / counter コンポーネントを
  Activity から呼び出します。

```bash
# リポジトリのルートで
./gradlew -p samples/android :app:testDebugUnitTest       # ホスト JVM で実行
./gradlew -p samples/android :app:assembleRelease         # R8 で縮小したリリース APK
./gradlew -p samples/android :app:connectedAndroidTest    # 接続した端末 / エミュレータ（ART）で実行
```

Android CLI でヘッドレスエミュレーターを使う手順や、release ビルドの起動・スクリーンショットの取り方は
[samples/android/README.md](../samples/android/README.md) にまとめています。

## 5. 開発者向け

| モジュール | 役割 |
| :--- | :--- |
| `ast` / `generator` / `cli` | WIT モデル、バインディング生成、`wasm2kt` コマンド（Wasmtime 版と共通） |
| `runtime-wasmtime` | Wasmtime ブリッジ（FFM / JNI） |
| `integration-tests` | Wasmtime / Chicory の E2E テストと差分テスト |
| `fixtures/` | テスト用コンポーネント（各モジュールと decomposer で共有） |
| `decomposer/` | 分解ツール（Rust）。`build-wasm.sh` で `dist/wasm2kt-decompose.wasm` を再生成（再現可能ビルド。CI で検証）、`regenerate-fixtures.sh` でテスト用の分解結果を再生成 |
| `runtime-api` | `ComponentBackend` / `WitHostImportDispatcher` / `FfmJsonCodec`（JVM 11） |
| `runtime-chicory` | Chicory バックエンド（JVM 11、Android 対応） |
| `tooling` | 分解・AOT コンパイル・コード生成のパイプライン（`ComponentBuild.run` で一括実行も可） |
| `gradle-plugin` | `io.github.wasm2kt` プラグイン。`functionalTest` で TestKit による実ビルドを検証 |
