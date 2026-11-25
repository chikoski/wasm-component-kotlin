# Gradle プラグイン リファレンス (`io.github.wasm2kt`)

`io.github.wasm2kt` は、`.wasm` コンポーネントをビルド時に分解・コンパイルし、Kotlin バインディングを生成する Gradle プラグインです。
Kotlin/JVM と Android のアプリケーションおよびライブラリプロジェクトに対応しています。バックエンドは `CHICORY` が既定で、`backend` プロパティにより `AOT` へ切り替えられます。

## 1. 設定 DSL (`wasm2kt`)

```kotlin
import wasm2kt.gradle.Wasm2KtBackend

plugins {
    id("io.github.wasm2kt")
}

wasm2kt {
    // 省略可（以下はすべて既定値）
    backend.set(Wasm2KtBackend.CHICORY)
    addRuntimeDependency.set(true)
    failOnUnsupportedFeatures.set(false)
    useLibraryInterfaces.set(true)

    components {
        register("calculator") {
            source.set(file("src/main/wasm/calculator.wasm"))
            packageName.set("com.example.calculator")
            // backend.set(Wasm2KtBackend.AOT)
            // world.set("calculator")
            // with("wasi:filesystem/types@0.3.0", "wasm2kt.wasi.wasi.filesystem.types")
        }
    }
}
```

### `Wasm2KtExtension` のプロパティ

| プロパティ | 型 | 既定値 | 説明 |
| :--- | :--- | :--- | :--- |
| `backend` | `Property<Wasm2KtBackend>` | `Wasm2KtBackend.CHICORY` | 個別指定がないコンポーネントに適用する既定のバックエンドです |
| `addRuntimeDependency` | `Property<Boolean>` | `true` | 登録されたコンポーネントのバックエンドに応じて `runtime-chicory` と `runtime-aot` の必要な方だけを `implementation` に追加します |
| `failOnUnsupportedFeatures` | `Property<Boolean>` | `false` | 検査タスクが未対応の型や組み込み関数を検出したときに警告ではなくビルド失敗とします。なお `AOT` の非同期拒否は本設定にかかわらず常に失敗します |
| `useLibraryInterfaces` | `Property<Boolean>` | `true` | クラスパス上の `META-INF/wasm2kt/interfaces/*.json` を読み取り、重複する interface のコード生成を省略します |
| `addWasiDependency` | `Property<Boolean>` | `false` | `io.github.wasm2kt:runtime-wasi` を `implementation` に追加します。すべてのコンポーネントが `AOT` の場合は互換性の警告を出力します |
| `components` | `NamedDomainObjectContainer<Wasm2KtComponent>` | 空 | ビルド対象のコンポーネント定義を登録するコンテナです |

`addRuntimeDependency` が有効で、登録したすべてのコンポーネントが `Wasm2KtBackend.AOT` を選択した場合、`runtime-chicory` は `implementation` へ追加されません。混在構成では `runtime-chicory` と `runtime-aot` の双方が追加されます。WASI 0.3 は `AOT` で未対応の非同期機能を利用するため、全コンポーネントが `AOT` の状態で `addWasiDependency` を有効にすると設定時に警告を出します。

### `Wasm2KtComponent`（`components.register("<name>")`）のプロパティ・メソッド

| プロパティ / メソッド | 型 | 必須 | 説明 |
| :--- | :--- | :--- | :--- |
| `source` | `RegularFileProperty` | 必須 | 入力となる `.wasm` コンポーネントバイナリのファイルです |
| `packageName` | `Property<String>` | 必須 | 生成される Kotlin バインディングと `<Name>Component` の出力先パッケージ名です |
| `backend` | `Property<Wasm2KtBackend>` | 任意 | このコンポーネント専用のバックエンドです。省略時は `wasm2kt.backend` の値を継承します |
| `world` | `Property<String>` | 任意 | コンポーネントの WIT に複数の world がある場合に、生成対象の world 名を指定します |
| `interfaceMappings` | `MapProperty<String, String>` | 任意 | import した完全修飾 interface 名から既存 Kotlin パッケージへの対応表です |
| `with(interfaceName, packageName)` | 関数 | — | `interfaceMappings.put(interfaceName, packageName)` の糖衣構文です |

---

## 2. 生成物

`register("calculator")` のように登録したコンポーネントごとに、以下が生成されます。

| 生成物 | 出力先 | 内容 |
| :--- | :--- | :--- |
| Kotlin バインディング | `build/wasm2kt/<name>/kotlin/` | WIT から生成された型、`Session`、`withSession`、`openSession`、`imports(...)`、エクスポート関数です |
| `<Name>Component` | 同上、`packageName` 直下 | `ComponentBackendFactory` の実装オブジェクトです |
| `wasm2kt-<name>.jar` | `build/wasm2kt/<name>/wasm2kt-<name>.jar` | コンパイル済み core モジュールと資源を収めた jar で、`implementation` に自動追加されます |

`wasm2kt-<name>.jar` の中身は選択したバックエンドによって異なります。`CHICORY` では core クラス群に加えて `.meta` ファイルと `plan.json` を同梱します。`AOT` では ASM で生成した `CoreNInstance` 系クラス群と静的データセグメントの `.data` ファイルのみを収め、`.meta` や `plan.json` は含みません。プラグインはあわせて Kotlin コンパイルタスクに `-Xcontext-parameters` を自動で追加します。

---

## 3. 登録される Gradle タスク

コンポーネント名 `<name>` をパスカルケースにしたものを `<Cap>` とすると、以下のタスクが登録されます。

| タスク名 | 型 | 内容 |
| :--- | :--- | :--- |
| `wasm2ktDecompose<Cap>` | `Wasm2KtDecomposeTask` | 同梱の `wasm2kt-decompose.wasm` で `.wasm` を `core*.wasm`・`plan.json`・`wit.json` に分解します |
| `wasm2ktCheck<Cap>` | `Wasm2KtCheckTask` | 分解結果を選択バックエンドに対して検証し、未対応機能や `AOT` の非同期使用を検査します |
| `wasm2ktCompile<Cap>` | `Wasm2KtCompileTask` | 選択バックエンドに応じて Chicory AOT または自前 AOT コンパイラで JVM バイトコードを生成し、jar を出力します |
| `wasm2ktGenerate<Cap>` | `Wasm2KtGenerateTask` | Kotlin バインディングとバックエンドに応じた `<Name>Component` 静的配線を生成します |
| `wasm2ktCheckWarnings<Cap>` / `wasm2ktCompileWarnings<Cap>` | `Wasm2KtWarningsTask` | キャッシュヒット時を含めて検査およびコンパイル時の警告をビルドごとに再表示します |

未対応機能の検査を `wasm2ktCheck<Cap>` に分離しているため、同じ `.wasm` ファイルのまま `CHICORY` と `AOT` を切り替えても `wasm2ktDecompose<Cap>` のキャッシュは共有されます。`wasm2ktCheck<Cap>` は [#39](https://github.com/chikoski/wasm-component-kotlin/issues/39) の未対応機能検査に加えて、`Wasm2KtBackend.AOT` で `usesAsync = true` のコンポーネントを検出すると `failOnUnsupportedFeatures` の値にかかわらずビルドをエラーで停止します。[#78](https://github.com/chikoski/wasm-component-kotlin/issues/78) の再表示機構により、検査結果とコンパイル結果がキャッシュから復元されたビルドでも警告が出力されます。

Chicory バックエンドの `wasm2ktCompile<Cap>` は [ADR-0021](../adr/0021-split-large-chicory-methods.md) に基づいて `JvmMethodSplitter` を実行し、7,500 JVM バイトを超える `func_<N>` メソッドを自動分割します。`call_dispatch_0` などにより 8,000 バイト上限の警告が出た場合でも、デスクトップやサーバーの JVM であれば `-XX:-DontCompileHugeMethods` を指定することで JIT コンパイルの対象にできます。一方の `AOT` バックエンドでは構造化 CFG 上で事前に IR 分割を行うため、64 KB インタプリタフォールバック自体は存在せず、`AotComponentCompiler.warningsFor` は Android ART の命令上限と HotSpot の 8,000 バイト上限の双方を超過したメソッドのみを警告として報告します。

---

## 4. キャッシュとクラスローダー分離

ビルド時キャッシュとクラスローダー分離に関する主な仕組みは次のとおりです。

* ビルドキャッシュへの対応
* キャッシュヒット時の警告再表示
* 設定キャッシュへの対応
* Worker によるクラスローダー分離

`Decompose`、`Check`、`Compile`、`Generate` の 4 タスクはいずれも `@CacheableTask` に対応し、jar 出力も固定タイムスタンプで再現性を保ちます。出力を持たない `Wasm2KtWarningsTask` を `finalizedBy` で接続しているため、上位タスクが `UP-TO-DATE` や `FROM-CACHE` になった再ビルドでも警告が出力されます。また Gradle の設定フェーズでは `tooling` のクラスを参照せず、バックエンド指定も文字列名として Worker へ渡すため Configuration Cache が壊れません。ビルド時ツールは `wasm2ktTooling` 構成と `classLoaderIsolation` の Worker 内で実行され、Android Gradle Plugin の ASM バージョンと衝突しません。詳細は [ADR-0004](../adr/0004-isolated-tooling-classloader.md) にあります。
