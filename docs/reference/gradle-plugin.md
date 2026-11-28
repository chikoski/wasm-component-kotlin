# Gradle プラグイン リファレンス (`io.github.wasm2kt`)

`io.github.wasm2kt` は、`.wasm` コンポーネントをビルド時に分解・コンパイルし、Kotlin バインディングを生成する Gradle プラグインです。
Kotlin/JVM と Android のアプリケーションおよびライブラリプロジェクトに対応しています。バックエンドは `AOT` が既定で、`backend` プロパティにより `CHICORY` へ切り替えられます。

## 1. 設定 DSL (`wasm2kt`)

```kotlin
import wasm2kt.gradle.Wasm2KtBackend

plugins {
    id("io.github.wasm2kt")
}

wasm2kt {
    // 省略可（以下はすべて既定値）
    backend.set(Wasm2KtBackend.AOT)
    addRuntimeDependency.set(true)
    failOnUnsupportedFeatures.set(false)
    useLibraryInterfaces.set(true)

    components {
        register("calculator") {
            source.set(file("src/main/wasm/calculator.wasm"))
            packageName.set("com.example.calculator")
            // backend.set(Wasm2KtBackend.CHICORY)
            // world.set("calculator")
            // with("wasi:filesystem/types@0.3.0", "wasm2kt.wasi.wasi.filesystem.types")
        }
    }
}
```

### `Wasm2KtExtension` のプロパティ

| プロパティ | 型 | 既定値 | 説明 |
| :--- | :--- | :--- | :--- |
| `backend` | `Property<Wasm2KtBackend>` | `Wasm2KtBackend.AOT` | 個別指定がないコンポーネントに適用する既定のバックエンドです |
| `addRuntimeDependency` | `Property<Boolean>` | `true` | 登録されたコンポーネントのバックエンドに応じて `runtime-chicory` と `runtime-aot` の必要な方だけを `implementation` に追加します |
| `failOnUnsupportedFeatures` | `Property<Boolean>` | `false` | 検査タスクが未対応の型や組み込み関数を検出したときに警告ではなくビルド失敗とします |
| `useLibraryInterfaces` | `Property<Boolean>` | `true` | クラスパス上の `META-INF/wasm2kt/interfaces/*.json` を読み取り、重複する interface のコード生成を省略します |
| `addWasiDependency` | `Property<Boolean>` | `false` | `io.github.wasm2kt:runtime-wasi` を `implementation` に追加します |
| `components` | `NamedDomainObjectContainer<Wasm2KtComponent>` | 空 | ビルド対象のコンポーネント定義を登録するコンテナです |

`addRuntimeDependency` が有効で、登録したすべてのコンポーネントが既定の `Wasm2KtBackend.AOT` を選択した場合、`runtime-aot` だけが `implementation` へ追加され、`runtime-chicory` は追加されません。混在構成では `runtime-aot` と `runtime-chicory` の双方が追加されます。`addWasiDependency` を有効にした場合、WASI 0.3 コンポーネントは `AOT` と `CHICORY` の双方で追加設定なしに動作します。

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
| `<Name>Component` | 同上、`packageName` 直下 | `CHICORY` と `AOT` 非同期では `ComponentBackendFactory`、`AOT` 同期では `AotComponentFactory` の実装オブジェクトです |
| `wasm2kt-<name>.jar` | `build/wasm2kt/<name>/wasm2kt-<name>.jar` | コンパイル済み core モジュールと資源を収めた jar で、`implementation` に自動追加されます |

`wasm2kt-<name>.jar` の中身は選択したバックエンドによって異なります。`AOT` では ASM で生成した `CoreNInstance` 系クラス群と静的データセグメントの `.data` ファイルのみを収め、`.meta` や `plan.json` は含みません。`CHICORY` では core クラス群に加えて `.meta` ファイルと `plan.json` を同梱します。同期コンポーネントが既定の `AOT` を選択した場合、`openSession`、`withSession`、および `withSessionBlocking` は `AotComponentFactory` だけを受け取る型になり、`ComponentBackendFactory` や `Path` を渡すとコンパイル時に拒否されます。非同期コンポーネントと WASI 0.3 コンポーネントに `AOT` を選択した場合、生成される `<Name>Component` が `ComponentBackendFactory` を実装して内部で `AotComponentBackend` を構成するため、既存の非同期セッション API をそのまま利用できます。プラグインはあわせて Kotlin コンパイルタスクに `-Xcontext-parameters` を自動で追加します。

---

## 3. 登録される Gradle タスク

コンポーネント名 `<name>` をパスカルケースにしたものを `<Cap>` とすると、以下のタスクが登録されます。

| タスク名 | 型 | 内容 |
| :--- | :--- | :--- |
| `wasm2ktDecompose<Cap>` | `Wasm2KtDecomposeTask` | 同梱の `wasm2kt-decompose.wasm` で `.wasm` を `core*.wasm`・`plan.json`・`wit.json` に分解します |
| `wasm2ktCheck<Cap>` | `Wasm2KtCheckTask` | 分解結果を選択バックエンドに対して検証し、未対応機能を検査します |
| `wasm2ktCompile<Cap>` | `Wasm2KtCompileTask` | 選択バックエンドに応じて Chicory AOT または自前 AOT コンパイラで JVM バイトコードを生成し、jar を出力します |
| `wasm2ktGenerate<Cap>` | `Wasm2KtGenerateTask` | Kotlin バインディングとバックエンドに応じた `<Name>Component` 静的配線を生成します |
| `wasm2ktCheckWarnings<Cap>` / `wasm2ktCompileWarnings<Cap>` | `Wasm2KtWarningsTask` | キャッシュヒット時を含めて検査およびコンパイル時の警告をビルドごとに再表示します |

未対応機能の検査を `wasm2ktCheck<Cap>` に分離しているため、同じ `.wasm` ファイルのまま `CHICORY` と `AOT` を切り替えても `wasm2ktDecompose<Cap>` のキャッシュは共有されます。`wasm2ktCheck<Cap>` は [#39](https://github.com/chikoski/wasm-component-kotlin/issues/39) の未対応機能検査を実行し、`Wasm2KtBackend.AOT` でも同期コンポーネントと非同期コンポーネントの双方を受け付けます。[#78](https://github.com/chikoski/wasm-component-kotlin/issues/78) の再表示機構により、検査結果とコンパイル結果がキャッシュから復元されたビルドでも警告が出力されます。

Chicory バックエンドの `wasm2ktCompile<Cap>` は [ADR-0021](../adr/0021-split-large-chicory-methods.md) に基づいて `JvmMethodSplitter` を実行し、7,500 JVM バイトを超える `func_<N>` メソッドを自動分割します。`call_dispatch_0` などにより 8,000 バイト上限の警告が出た場合でも、デスクトップやサーバーの JVM であれば `-XX:-DontCompileHugeMethods` を指定することで JIT コンパイルの対象にできます。一方の `AOT` バックエンドでは構造化 CFG 上で事前に IR 分割を行うため、64 KB インタプリタフォールバック自体は存在せず、`AotComponentCompiler.warningsFor` は Android ART の命令上限と HotSpot の 8,000 バイト上限の双方を超過したメソッドのみを警告として報告します。

---

## 4. キャッシュとクラスローダー分離

ビルド時キャッシュとクラスローダー分離に関する主な仕組みは次のとおりです。

* ビルドキャッシュへの対応
* キャッシュヒット時の警告再表示
* 設定キャッシュへの対応
* Worker によるクラスローダー分離

`Decompose`、`Check`、`Compile`、`Generate` の 4 タスクはいずれも `@CacheableTask` に対応し、jar 出力も固定タイムスタンプで再現性を保ちます。出力を持たない `Wasm2KtWarningsTask` を `finalizedBy` で接続しているため、上位タスクが `UP-TO-DATE` や `FROM-CACHE` になった再ビルドでも警告が出力されます。また Gradle の設定フェーズでは `tooling` のクラスを参照せず、バックエンド指定も文字列名として Worker へ渡すため Configuration Cache が壊れません。ビルド時ツールは `wasm2ktTooling` 構成と `classLoaderIsolation` の Worker 内で実行され、Android Gradle Plugin の ASM バージョンと衝突しません。詳細は [ADR-0004](../adr/0004-isolated-tooling-classloader.md) にあります。

---

## 5. `CHICORY` からの移行ガイド

バージョン `0.2.0-SNAPSHOT` 以降では、既定のバックエンドが `Wasm2KtBackend.CHICORY` から `Wasm2KtBackend.AOT` へ切り替わりました。従来の設定や呼び出しコードを新しい既定構成へ合わせる場合、または一部コンポーネントを `CHICORY` へ戻す場合は、次の項目を確認してください。

* `openSession`、`withSession`、および `withSessionBlocking` の引数型変更
* 未対応 Core Wasm 機能によるビルドエラーとオプトアウト方法
* `addRuntimeDependency.set(false)` 利用時の依存関係変更
* `with` マッピングとライブラリ共有時の注意

### `AotComponentFactory` への入口型変更

同期コンポーネントで既定の `AOT` が選択されると、生成コードの `openSession`、`withSession`、および `withSessionBlocking` は `ComponentBackendFactory` ではなく `AotComponentFactory` だけを受け取ります。`ComponentBackendFactory` 型に抽象化して渡していた呼び出し元や、`ChicoryComponent.fromDirectory` をテストから直接渡していたコードはコンパイルエラーになります。既存の `ComponentBackendFactory` 契約をそのまま使うコンポーネントや `fromDirectory` テストでは、対象コンポーネントへ `Wasm2KtBackend.CHICORY` を明示してください。非同期コンポーネントと WASI 0.3 コンポーネントでは、既定の `AOT` のままでも `<Name>Component` が引き続き `ComponentBackendFactory` を実装します。

```kotlin
// build.gradle.kts で対象コンポーネントだけを CHICORY へ戻す例
wasm2kt {
    components {
        register("legacyComponent") {
            source.set(file("src/main/wasm/legacy-component.wasm"))
            packageName.set("com.example.legacy")
            backend.set(Wasm2KtBackend.CHICORY)
        }
    }
}
```

### 未対応 Core Wasm 機能検出時の `UnsupportedWasmFeatureException` 対処

既定の `AOT` バックエンドでサポート外の Core Wasm 命令や機能が検出された場合、`wasm2ktCompile` タスクまたはインメモリ生成処理が `UnsupportedWasmFeatureException` を送出して停止します。エラーメッセージにはモジュール番号、関数番号、および機能名に加えて、回避策となる `backend.set(Wasm2KtBackend.CHICORY)` の指定案内が出力されます。該当コンポーネントをすぐに `AOT` 非対応命令なしへ再コンパイルできない場合は、`wasm2kt` 拡張ブロック全体またはコンポーネント単位で `Wasm2KtBackend.CHICORY` を設定して回避できます。

```kotlin
// プロジェクト全体の既定を CHICORY へ切り替える例
wasm2kt {
    backend.set(Wasm2KtBackend.CHICORY)
}
```

### `addRuntimeDependency.set(false)` 指定時の依存関係

`addRuntimeDependency` が既定の `true` の場合は、選択されたバックエンドに応じて `runtime-aot` や `runtime-chicory` が自動追加されるため変更は不要です。`addRuntimeDependency.set(false)` を指定してランタイム依存を手動で宣言している場合は、`io.github.wasm2kt:runtime-chicory:0.2.0-SNAPSHOT` ではなく `io.github.wasm2kt:runtime-aot:0.2.0-SNAPSHOT` を `implementation` に追加してください。`AOT` と `CHICORY` をコンポーネントごとに混在させる場合は双方を追加します。

```kotlin
dependencies {
    implementation("io.github.wasm2kt:runtime-aot:0.2.0-SNAPSHOT")
    // CHICORY を併用するコンポーネントがある場合のみ追加
    // implementation("io.github.wasm2kt:runtime-chicory:0.2.0-SNAPSHOT")
}
```

### `with` マッピングと外部ライブラリ利用時の挙動

外部ライブラリとインターフェースを共有する `with` 指定済みコンポーネントでも、既定の `AOT` は追加設定なしで動作します。ローカルのエクスポート関数とローカルのホスト import は `DirectCanonicalAbi` で直接結合され、`with` で共有された外部インターフェースの境界だけ `AotMappedImportBridge` を介して `Linker` へ接続されます。純粋な同期コンポーネントで `with` マッピングも使用しない構成では、release ビルド時の R8 縮小により `WitWriter`、`Linker`、および `AsyncAbi` 関連クラスが除去されます。
