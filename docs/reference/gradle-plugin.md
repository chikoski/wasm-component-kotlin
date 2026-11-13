# Gradle プラグイン リファレンス (`io.github.wasm2kt`)

`io.github.wasm2kt` は、`.wasm` コンポーネントをビルド時に分解・AOT コンパイルし、Chicory バックエンド向けの Kotlin バインディングを生成する Gradle プラグインです。
Kotlin/JVM（`org.jetbrains.kotlin.jvm`）と Android（`com.android.application` / `com.android.library`）に対応しています。

## 1. 設定 DSL (`wasm2kt`)

```kotlin
plugins {
    id("io.github.wasm2kt")
}

wasm2kt {
    // 省略可（以下はすべて既定値）
    addRuntimeDependency.set(true)
    failOnUnsupportedFeatures.set(false)
    useLibraryInterfaces.set(true)

    components {
        register("calculator") {
            source.set(file("src/main/wasm/calculator.wasm"))
            packageName.set("com.example.calculator")
            // world.set("calculator")
            // with("wasi:filesystem/types@0.3.0", "wasm2kt.wasi.wasi.filesystem.types")
        }
    }
}
```

### `Wasm2KtExtension` のプロパティ

| プロパティ | 型 | 既定値 | 説明 |
| :--- | :--- | :--- | :--- |
| `addRuntimeDependency` | `Property<Boolean>` | `true` | プラグインと同じバージョンの `io.github.wasm2kt:runtime-chicory` を `implementation` に追加します。自分で依存関係を管理する場合は `false` にします |
| `failOnUnsupportedFeatures` | `Property<Boolean>` | `false` | コンポーネントが Chicory バックエンドの未対応機能（未対応の組み込み関数、型）を使っている場合に、分解タスクでビルドを失敗させます。`false` の場合はビルド時に警告のみを表示し、実行時に該当箇所が呼ばれた時点で例外を投げます（[#39](https://github.com/chikoski/wasm-component-kotlin/issues/39)） |
| `useLibraryInterfaces` | `Property<Boolean>` | `true` | コンパイルクラスパス上の依存 jar に `META-INF/wasm2kt/interfaces/*.json` マニフェストが含まれている場合、コンポーネントが同じバージョンの interface を import していればコード生成を省略し、ライブラリのパッケージを利用します（[ADR-0014](../adr/0014-interface-mappings.md)） |
| `components` | `NamedDomainObjectContainer<Wasm2KtComponent>` | 空 | ビルド対象のコンポーネント定義のコンテナです |

### `Wasm2KtComponent`（`components.register("<name>")`）のプロパティ・メソッド

| プロパティ / メソッド | 型 | 必須 | 説明 |
| :--- | :--- | :--- | :--- |
| `source` | `RegularFileProperty` | 必須 | 入力となる `.wasm` コンポーネントバイナリのファイルです |
| `packageName` | `Property<String>` | 必須 | 生成される Kotlin バインディングと `<Name>Component` の出力先パッケージ名です |
| `world` | `Property<String>` | 任意 | コンポーネントの WIT に複数の world がある場合に、生成対象の world 名を指定します（省略時はコンポーネント自身の world） |
| `interfaceMappings` | `MapProperty<String, String>` | 任意 | import した完全修飾 interface 名（バージョン付き）から既存 Kotlin パッケージへの対応表です |
| `with(interfaceName, packageName)` | 関数 | — | `interfaceMappings.put(interfaceName, packageName)` の糖衣構文です（例: `with("wasi:filesystem/types@0.3.0", "wasm2kt.wasi.wasi.filesystem.types")`） |

---

## 2. 生成物

`register("calculator")` のように登録したコンポーネントごとに、以下が生成されます。

| 生成物 | 出力先 | 内容 |
| :--- | :--- | :--- |
| Kotlin バインディング（`RootWorld.kt` など） | `build/wasm2kt/<name>/kotlin/` | WIT から生成された型、`Session`、`withSession`、`openSession`、`imports(...)`、エクスポート関数（Wasmtime 用セッションは含まない） |
| `<Name>Component`（例: `CalculatorComponent`） | 同上（`<packageName>` 直下） | `ComponentBackendFactory` の実装オブジェクト。`withSession(CalculatorComponent)` のように渡します |
| `wasm2kt-<name>.jar` | `build/wasm2kt/<name>/wasm2kt-<name>.jar` | AOT コンパイルされた core モジュールのクラス（`<packageName>.wasm2kt.<name>.CoreNMachine` 等）、関数本体を除いた `.meta`、および `plan.json` を収めた jar。`implementation` に自動追加されます |

プラグインはあわせて Kotlin コンパイルタスクに `-Xcontext-parameters` を自動で追加します。

---

## 3. 登録される Gradle タスク

コンポーネント名 `<name>` をパスカルケースにしたものを `<Cap>`（例: `calculator` → `Calculator`）とすると、以下のタスクが登録されます。

| タスク名 | 型 | 内容 |
| :--- | :--- | :--- |
| `wasm2ktDecompose<Cap>` | `Wasm2KtDecomposeTask` | 同梱の `wasm2kt-decompose.wasm` を Chicory 上で実行し、`.wasm` を `core*.wasm`・`plan.json`・`wit.json` に分解して未対応機能を検査します |
| `wasm2ktCompile<Cap>` | `Wasm2KtCompileTask` | Chicory のビルド時コンパイラで各 core モジュールを JVM バイトコードへコンパイルし、`wasm2kt-<name>.jar` を出力します |
| `wasm2ktGenerate<Cap>`（Android ではバリアントごとに配線） | `Wasm2KtGenerateTask` | `wit.json` と `plan.json` から Kotlin ソースコードを生成します |
| `wasm2ktDecomposeWarnings<Cap>` / `wasm2ktCompileWarnings<Cap>` | `Wasm2KtWarningsTask` | 分解・コンパイル時の警告（未対応機能や 64 KB 超の関数によるインタプリタフォールバック）を表示します |

---

## 4. キャッシュとクラスローダー分離

* **Build Cache (`@CacheableTask`)**: `Decompose`、`Compile`、`Generate` の 3 タスクはすべて `@CacheableTask` に対応しており、出力される jar も固定タイムスタンプで再現可能なバイト列になります
* **キャッシュヒット時の警告表示**: `Wasm2KtWarningsTask` は出力を持たない `finalizedBy` タスクとして定義されているため、`Decompose` や `Compile` が `UP-TO-DATE` または `FROM-CACHE` になった 2 回目以降のビルドでも警告が表示されます（[#78](https://github.com/chikoski/wasm-component-kotlin/issues/78)）
* **Configuration Cache**: 設定時に `tooling` のクラスを参照せず、すべてのタスクが Configuration Cache に対応しています
* **Worker クラスローダー分離**: ビルド時ツール（Chicory コンパイラ、ASM、KotlinPoet）は `wasm2ktTooling` 構成で解決され、Gradle Worker API の `classLoaderIsolation` で実行されるため、Android Gradle Plugin 本体の ASM バージョンと衝突しません（[ADR-0004](../adr/0004-isolated-tooling-classloader.md)）
