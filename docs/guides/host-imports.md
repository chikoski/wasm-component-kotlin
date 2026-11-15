# ホスト import と interface の共有

このガイドでは、コンポーネントが import する関数・interface・ホスト定義 `resource` を Kotlin 側で実装して渡す方法と、複数の world やライブラリ間で interface の型を共有する方法を説明します。

## 1. ホスト import の実装と `imports(...)`

WIT で import された関数や interface は、ホスト側（Kotlin）が実装してセッション開始時に渡します（[ADR-0011](../adr/0011-qualified-interface-keys.md)）。

* **world 直下の import**: 生成されたパッケージ直下の `interface Imports` を実装します
* **import された interface（例: `example:roundtrip/env-provider`）**: `<base>.<ns>.<pkg>.<iface>` パッケージに生成された Kotlin interface（例: `EnvProvider`）を実装します

world のファイルには、必要な import をすべて必須引数として受け取る `imports(...)` 関数が生成されます。どれかを渡し忘れるとコンパイルエラーになります。

```kotlin
import com.example.roundtrip.Imports
import com.example.roundtrip.RoundtripComponent
import com.example.roundtrip.example.roundtrip.env_provider.EnvProvider
import com.example.roundtrip.imports
import com.example.roundtrip.withSession

val hostImports = imports(
    exampleRoundtripEnvProvider = {
        object : EnvProvider {
            override fun getMultiplier(): Int = 6
            override fun formatTag(prefix: String, code: UInt): String = "$prefix#$code"
        }
    },
    // world 直下の import がある場合は world = { object : Imports { ... } } も引数に並ぶ
)

withSession(RoundtripComponent, hostImports) {
    // ...
}
```

各ラムダはセッションを開くたびに呼ばれるため、状態を持つ実装でもセッション間で混ざりません。セッション間で同じインスタンスを共有したい場合は、ラムダから同じオブジェクトを返します。

## 2. `Linker` を直接組み立てる

`imports(...)` の下層では、interface ごとに生成される拡張関数 `fun Linker.add(impl)` が、完全修飾名のキー（`wasi:http/types@0.2.0#handle` など）で実装を `Linker` に登録しています。
共通の実装をまとめて登録したい場合は、`Linker` を直接組み立てて `withSession` / `openSession` に渡すこともできます。

```kotlin
import com.example.roundtrip.example.roundtrip.env_provider.add
import wasm2kt.runtime.Linker

val linker = Linker().apply {
    add(MyEnvProvider())
}

withSession(RoundtripComponent, linker) {
    // ...
}
```

`Linker` を直接渡した場合、world が必要とするキーがすべて揃っているかはセッション開始時に検査され、不足があれば `IllegalArgumentException` が投げられます。

## 3. ホスト定義 `resource`（import された `resource`）

world 直下、または import した interface にある `resource` は、ホストが実装します（[ADR-0008](../adr/0008-host-defined-resources.md)）。

* リソース本体は `WitHostResource`（`AutoCloseable` を継承）を実装する Kotlin `interface` として生成され、メソッドのみを持ちます
* `constructor`（`new<Resource>`）と `static` 関数（`<resource><Func>`）は、そのリソースを含むホスト用 interface（または `Imports`）のメンバーになります

```wit
package example:host-res@0.1.0;

interface store {
    resource blob {
        constructor(init: list<u8>);
        size: func() -> u32;
        read: func() -> list<u8>;
        merge: static func(a: borrow<blob>, b: own<blob>) -> blob;
    }
}

world host-res {
    import store;
    export measure: func(b: borrow<blob>) -> u32;
}
```

```kotlin
import com.example.hostres.example.host_res.store.Blob
import com.example.hostres.example.host_res.store.Store
import com.example.hostres.imports
import com.example.hostres.measure
import com.example.hostres.withSession

class MemoryBlob(private val bytes: ByteArray) : Blob {
    override fun size(): UInt = bytes.size.toUInt()
    override fun read(): ByteArray = bytes
    override fun close() {
        // コンポーネントが最後の own ハンドルを drop したとき、またはセッション終了時に呼ばれる
    }
}

val hostImports = imports(
    exampleHostResStore = {
        object : Store {
            override fun newBlob(init: ByteArray): Blob = MemoryBlob(init)
            override fun blobMerge(a: Blob, b: Blob): Blob = MemoryBlob(a.read() + b.read())
        }
    },
)

withSession(component, hostImports) {
    // borrow: この呼び出しの間だけ貸し出され、呼び出し後もホスト側で使える
    val len = measure(MemoryBlob(byteArrayOf(1, 2, 3)))
}
```

### 所有権とライフサイクルの規則

* **own でゲストへ渡す**: 所有権がコンポーネントへ移り、コンポーネントが drop した時点で `close()` が呼ばれます。一度 own で渡してコンポーネントが保持している同じオブジェクトを、再度 own や borrow で渡すとエラーになります（[#37](https://github.com/chikoski/wasm-component-kotlin/issues/37)）
* **own でゲストから返される**: ホストの手元に所有権が戻るため、`close()` は呼ばれません
* **borrow でゲストへ渡す**: その呼び出しの間だけ貸し出されます。呼び出しが終わると貸し出しは解除され、ホスト側でそのまま使い続けられます
* **セッション終了時**: コンポーネントがまだ保持している own ハンドルのオブジェクトに対して `close()` が呼ばれます
* **スレッド制約**: ホスト定義 `resource` の受け渡しは、そのセッションの呼び出し中（同じスレッド）に限られます。同一セッションに対する複数スレッドからの呼び出しはセッション内で直列化されます（[#75](https://github.com/chikoski/wasm-component-kotlin/issues/75)）

## 4. 既存のパッケージの interface を共有する（`with`）

複数のコンポーネントが同じ WIT interface（たとえば `wasi:cli` や共通の型定義 interface）を import する場合、デフォルトでは各コンポーネントの出力先パッケージごとに別々の型が生成されます。
`with` を使うと、import した interface を既存のパッケージに対応付け、同じ型と `Linker.add` 実装を複数のコンポーネントで共有できます（[ADR-0014](../adr/0014-interface-mappings.md)）。

### Gradle プラグインでの共有

1. **自動検出（デフォルト有効）**: コンパイルクラスパス上の依存ライブラリ（jar または別モジュール）に `META-INF/wasm2kt/interfaces/*.json` マニフェストが含まれており、コンポーネントが同じバージョンの interface を import している場合、プラグインは自動的にそのライブラリのパッケージを使います（`wasm2kt { useLibraryInterfaces.set(false) }` で無効化できます）
2. **明示的な指定**: コンポーネント定義の中で `with(interfaceName, packageName)` を指定します

```kotlin
wasm2kt {
    components {
        register("app") {
            source.set(file("src/main/wasm/app.wasm"))
            packageName.set("com.example.app")
            with("example:shared/types@0.1.0", "com.example.shared.example.shared.types")
        }
    }
}
```

### CLI での共有

共有用の interface ライブラリを作るには `--interfaces-only` を使い、利用側では `--library` または `--with` を指定します。

```bash
# 1. 共有したい interface とマニフェストだけを生成する
./gradlew run --args="generate shared.wit -o build/shared-src --resources-output build/shared-res -p com.example.shared --interfaces-only"

# 2. マニフェストを含むディレクトリや jar を指定して生成する（または --with で個別に指定する）
./gradlew run --args="generate app.wasm -o build/app-src -p com.example.app --library build/shared-res"
```

> **制約**: `with` で共有できるのは **import した interface のみ**です。export した interface の型は world ごとに生成されます。

## 5. WASI 0.3 のホスト実装を利用する

Chicory バックエンドでは `runtime-wasi` モジュールが WASI 0.3 の標準ホスト実装を提供します。対象パッケージは次の 4 つです。

* `wasi:random`
* `wasi:clocks`
* `wasi:cli`
* `wasi:filesystem`

`wasi:random` は暗号論的および非暗号論的乱数を生成します。`wasi:clocks` は単調時計とシステム時計、および非同期待機を提供します。`wasi:cli` は環境変数、引数、終了コード、標準入出力の非同期ストリームを提供します。`wasi:filesystem` はホストディレクトリへの能力ベースのサンドボックスマウントを提供します。設計方針の詳細は [ADR-0020](../adr/0020-wasi03-filesystem-sandboxing-and-mounts.md) と [ADR-0022](../adr/0022-wasi03-scope-and-host-configuration.md) を参照してください。

### Gradle プラグインでの設定

Gradle プラグインで `addWasiDependency` を有効にすると、プラグインと同じバージョンの `runtime-wasi` が依存関係に追加されます。`useLibraryInterfaces` が有効であれば、`runtime-wasi` に同梱されたマニフェストが検出され、WASI 0.3 の interface 型生成は自動で省略されます。

```kotlin
wasm2kt {
    addWasiDependency.set(true)
    components {
        register("wasi-app") {
            source.set(file("src/main/wasm/app.wasm"))
            packageName.set("com.example.app")
        }
    }
}
```

### `wasi03Imports` と生成済み `imports` の合成

`wasi03Imports` 関数は、セッションごとに独立した WASI 0.3 の環境を構築する `ImportsFactory` を返します。設定ブロックは `withSession` や `openSession` で新しいセッションを開始するたびに再評価されます。そのため、`stdin` や `stdout` にプロバイダ形式を指定すればセッション単位で新しいストリームを割り当てられます。

`wasi03Imports` の出力を生成済みワールドの `imports` 関数の `base` 引数に渡すと、WASI 以外のアプリ固有の import だけを追加で指定できます。マッピング済みの WASI 引数に個別ファクトリを渡した場合は `base` の実装を差し替えられます。`ImportsFactory` 同士を `+` 演算子や `Linker.addAll` で組み合わせることもできます。

```kotlin
val sessionOutputs = mutableListOf<ByteArrayOutputStream>()

val wasiBase = wasi03Imports {
    args("app", "--verbose")
    env("MODE" to "prod")
    stdin { ByteArrayInputStream("hello".encodeToByteArray()) }
    stdout { ByteArrayOutputStream().also { sessionOutputs += it } }
    mounts {
        readOnly("/assets", Paths.get("assets"))
        readWrite("/work", Paths.get("work"))
    }
}

val appImports = imports(
    base = wasiBase,
    exampleWasiAppAudit = { MyAuditImpl() },
)

withSession(AppComponent, appImports) {
    runSummary()
}
```

公開する WASI パッケージ群を制限したい場合は `includeRandom`、`includeClocks`、`includeCli`、`includeFilesystem` フラグを `false` に設定します。既定値はすべて `true` です。`includeFilesystem` を `false` にした場合は `mounts` などを同時に設定できません。`wasi:http` と `wasi:sockets` は P2 で追跡しています。
