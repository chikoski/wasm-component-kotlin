# WIT 型マッピングと DSL リファレンス

このドキュメントでは、`wasm2kt` が WIT（WebAssembly Interface Type）の各要素をどのように Kotlin コードへ変換するかを定義します。
決定の経緯は [ADR-0006](../adr/0006-type-mapping.md)、[ADR-0011](../adr/0011-qualified-interface-keys.md)、[ADR-0012](../adr/0012-world-at-package-top-level.md) にあります。

## 対応表（早見表）

| WIT | Kotlin |
| :--- | :--- |
| `world foo` / `world foo-world` | 指定した package のトップレベルの `Session`、`withSession`、`openSession`、`imports(...)`、world の関数と型。ファイル名は `FooWorld.kt`（名前が `World` で終わる場合は付け足さない）。1 回の生成で 1 つの world（[ADR-0012](../adr/0012-world-at-package-top-level.md)） |
| import した `interface ns:pkg/iface` | package `<base>.<ns>.<pkg>.<iface>` の型と、ホストが実装する `interface Iface`、`fun Linker.add(impl: Iface)`（[ADR-0011](../adr/0011-qualified-interface-keys.md)） |
| export した `interface ns:pkg/iface` | package `<base>.exports.<ns>.<pkg>.<iface>` の型と、マーカー `IfaceExports` を context に取るトップレベルの関数 |
| package の名前のセグメント | 小文字で `-` を `_` に（`outgoing-handler` → `outgoing_handler`）。Kotlin のキーワードは末尾に `_`（`object` → `object_`） |
| `bool` / `s8`〜`s64` / `u8`〜`u64` / `f32` / `f64` | `Boolean` / `Byte`〜`Long` / `UByte`〜`ULong` / `Float` / `Double` |
| `char` | `CodePoint`（`value: Int`。`CodePoint.of('a')`、`CodePoint.of("🎌")`） |
| `string` | `String` |
| `list<u8>` | `ByteArray` |
| `list<T>` | `List<T>` |
| `option<T>` | `T?` |
| `option<T>`（`T` が null を取りうる。例: `option<option<U>>`） | `WitOption<T>`（例: `WitOption<U?>`） |
| `result<T, E>` | `WitResult<T, E>`（`getOrThrow()` / `getOrNull()` / `errorOrNull()` / `fold` / `map` …） |
| `tuple<A, B>` | `Tuple2<A, B>` |
| `record` / `variant` / `enum` / `flags` | `data class` / `sealed interface` / `enum class` / `@JvmInline value class` |
| `resource`（コンポーネントが export） | `AutoCloseable` なクラス（ホストはハンドルを持つ） |
| `resource`（コンポーネントが import。world 直下か import した interface） | ホストが実装する `interface`（`WitHostResource`）。constructor / static は、その interface（world 直下なら `Imports`）のメンバー |
| `async func` / `future<T>` / `stream<T>` | `suspend fun` / `Deferred<T>` / `Flow<T>`（予定。Chicory のみ。[#16](https://github.com/chikoski/wasm-component-kotlin/issues/16)） |

生成される関数の KDoc には、元の WIT の定義（例: ``WIT: `lookup: func(k: string) -> option<option<s32>>` ``）が書き出されます。

---

## 1. World と実行セッション (`Session` & Context Parameters)

WIT の `world` は、生成時に指定したパッケージ（`-p` / `packageName`）の**トップレベル**に生成されます（[ADR-0012](../adr/0012-world-at-package-top-level.md)）。
ファイル名は `<WorldName>World.kt` です（名前がすでに `World` で終わる場合は付け足しません）。
トップレベルに置くため、1 回の生成で扱う world は 1 つです。WIT に複数の world があるときは `--world`（または Gradle プラグインの `world.set(...)`）で選び、world ごとに別のパッケージに生成します。

```kotlin
package com.example.calc

/** world 直下の import（ホストが実装する） */
interface Imports {
    fun log(msg: String)
}

/** world が import するものを、すべて必須の引数として受け取る */
fun imports(world: () -> Imports): ImportsFactory = ...

/** export した interface のマーカー（CountersExports など）も実装する */
class Session internal constructor(runtimeSession: WasmtimeSession) : WitSession(runtimeSession), CountersExports

fun openSession(wasmPath: Path, imports: ImportsFactory): Session = ...

inline fun <R> withSession(
    wasmPath: Path,
    imports: ImportsFactory,
    block: context(Session) () -> R,
): R = openSession(wasmPath, imports).use { session -> context(session) { block() } }

context(session: Session)
fun add(a: Int, b: Int): Int = ...
```

2 つのコンポーネントを同じファイルで使うときは、`withSession` などが重なるため、`import ... as` で別名を付けるか、パッケージ名で修飾します。

### 1.1 `withSession` と `openSession`

* **`withSession`**: ブロック内で自動的に `context(session: Session)` が提供され、ブロックを抜けるとセッションが自動的に `close()` されます
* **`openSession`**: 長期間セッションを保持したい場合は、`val session = openSession(...)` で取得し、呼び出し時に `context(session) { func() }` を使います。使い終わったら `session.close()` を呼びます

WIT の `world` にホスト import がない場合、`imports` 引数にはデフォルト値 `ImportsFactory.EMPTY` が設定されるため、`withSession(component) { ... }` のように第 2 引数を省略できます。

### 1.2 interface とパッケージ構造（[ADR-0011](../adr/0011-qualified-interface-keys.md)）

interface は、WIT の `namespace:package/interface` をそのまま Kotlin のパッケージに写した場所に生成されます。短い名前が同じ interface（`wasi:http/types` と `wasi:filesystem/types`）もパッケージで区別されます。

```text
<base>/
  CalculatorWorld.kt                         world（Session、withSession、imports(...)、world の関数と型）
  WitSupport.kt                              共通の型（WitOption、WitResult、WitSession など）
  example/calc/types/Types.kt                import した example:calc/types の型と、ホストが実装する interface
  exports/example/calc/counters/Counters.kt  export した example:calc/counters の型、マーカー CountersExports、トップレベル関数
```

* パッケージのセグメントは小文字で `-` を `_` に変換し、Kotlin のキーワードには末尾に `_` を付けます（`object` → `object_`）
* 同じ interface を import と export の両方で使う world では、別々のパッケージに生成されます
* 同じパッケージの異なるバージョン（`wasi:http@0.2.0` と `@0.3.0`）を 1 回の生成で使うとエラーになります
* interface の名前と同じ名前の型があるとき（`wasi:io/error` の `resource error`）は、interface 側に `Interface` を付けます（`ErrorInterface`）

export した interface の関数は、その interface ごとのマーカー（例: `MathApiExports`）を context に取るトップレベル関数として生成されます。マーカーはその interface を export する world の `Session` だけが実装するため、export していない world のセッションからは呼べません（[ADR-0012](../adr/0012-world-at-package-top-level.md)）。

```kotlin
import com.example.app.exports.example.roundtrip.math_api.scale

withSession(component, imports) {
    scale(7) // context(exports: MathApiExports)
}
```

ホスト import の実装方法と `with` による interface 共有の詳細は、[ホスト import と interface の共有](../guides/host-imports.md)を参照してください。

---

## 2. プリミティブ型マッピング

| WIT 型 | Kotlin 型 | 備考 |
| :--- | :--- | :--- |
| `bool` | `kotlin.Boolean` | |
| `s8` / `u8` | `kotlin.Byte` / `kotlin.UByte` | |
| `s16` / `u16` | `kotlin.Short` / `kotlin.UShort` | |
| `s32` / `u32` | `kotlin.Int` / `kotlin.UInt` | |
| `s64` / `u64` | `kotlin.Long` / `kotlin.ULong` | |
| `f32` (`float32`) | `kotlin.Float` | |
| `f64` (`float64`) | `kotlin.Double` | |
| `char` | `CodePoint`（`@JvmInline value class`、`value: Int`） | WIT の `char` は Unicode スカラー値（U+10FFFF まで）。Kotlin の `Char` ではサロゲートペア領域を 1 値で表せないため |
| `string` | `kotlin.String` | UTF-8 文字列 |
| `list<u8>` | `kotlin.ByteArray` | それ以外の `list<T>` は `List<T>`。`ByteArray` を持つ record / variant の `equals` / `hashCode` / `toString` は内容ベースで生成されます |

---

## 3. 複合型・代数的データ型マッピング

### 3.1 `record` → `data class`

WIT のフィールド名（`kebab-case`）は Kotlin のプロパティ名（`camelCase`）に変換されます。フィールドが空の record は通常の `class` になります。

```wit
record point {
    x: f32,
    y: f32,
}
```

```kotlin
data class Point(
    val x: Float,
    val y: Float,
)
```

### 3.2 `enum` → `enum class`

各ケースは `UPPER_SNAKE_CASE` に変換されます。

```wit
enum color { red, green, blue }
```

```kotlin
enum class Color {
    RED,
    GREEN,
    BLUE,
}
```

### 3.3 `flags` → `@JvmInline value class`

各フラグの `Boolean` プロパティと `or` / `and` 中置演算子、定数が生成されます（32 個以下のフラグは `UInt`、33 個以上は `ULong`）。

```wit
flags permissions { read, write, execute }
```

```kotlin
@JvmInline
value class Permissions(val raw: UInt = 0u) {
    val read: Boolean get() = (raw and (1u shl 0)) != 0u
    val write: Boolean get() = (raw and (1u shl 1)) != 0u
    val execute: Boolean get() = (raw and (1u shl 2)) != 0u

    infix fun or(other: Permissions): Permissions = Permissions(raw or other.raw)
    infix fun and(other: Permissions): Permissions = Permissions(raw and other.raw)

    companion object {
        val NONE: Permissions = Permissions(0u)
        val READ: Permissions = Permissions((1u shl 0))
        val WRITE: Permissions = Permissions((1u shl 1))
        val EXECUTE: Permissions = Permissions((1u shl 2))
    }
}
```

### 3.4 `variant` → `sealed interface`

ペイロードを持たないケースは `data object`、ペイロードを持つケースは `data class` として生成されます。

```wit
variant status {
    ok,
    err(string),
    code(u32),
}
```

```kotlin
sealed interface Status {
    data object Ok : Status
    data class Err(val value: String) : Status
    data class Code(val value: UInt) : Status
}
```

### 3.5 `option<T>` → `T?`（ネスト時のみ `WitOption<T>`）

`option<T>` は Kotlin の null 許容型 `T?` になります。
ただし `option<option<U>>` のように内側の型自体が `null` を取りうる場合は、外側の `none` と内側の `none`（`Some(null)`）を区別するため、外側が `WitOption<T>` になります。

```kotlin
sealed interface WitOption<out T> {
    fun getOrNull(): T? = when (this) {
        is Some -> value
        is None -> null
    }
    data class Some<out T>(val value: T) : WitOption<T>
    data object None : WitOption<Nothing>
}
```

### 3.6 `result<T, E>` → `WitResult<T, E>`

WIT のエラー型 `E` を型付きで保持するため、`WitResult<out T, out E>` にマッピングされます。成功型や失敗型が省略された箇所は `Unit` になります。

```kotlin
sealed interface WitResult<out T, out E> {
    data class Ok<out T>(val value: T) : WitResult<T, Nothing>
    data class Err<out E>(val error: E) : WitResult<Nothing, E>
}
```

| 補助関数・プロパティ | 内容 |
| :--- | :--- |
| `getOrThrow()` | `Ok` の値を返す。`Err` なら `WitResultException`（`error` プロパティに WIT のエラー値）を投げる |
| `getOrNull()` / `errorOrNull()` | `Ok` / `Err` の値。該当しなければ `null` |
| `getOrElse { e -> ... }` | `Ok` の値、または `Err` から計算した代替値 |
| `map { }` / `mapError { }` / `fold({ }, { })` | 値の変換と分岐 |
| `isOk` / `isErr` | 判定 |

### 3.7 `tuple<T0, T1, ...>` と `type` エイリアス

* **タプル**: 要素数に応じて `data class Tuple2<out T0, out T1>(val f0: T0, val f1: T1)` などが生成されます
* **型エイリアス**: `type user-id = u64` は `typealias UserId = ULong` として出力されます

---

## 4. リソース (`resource`)

* **コンポーネントが export する `resource`**: `AutoCloseable` を実装したクラスとして生成されます。使い方と `borrow` / `own` の規則は [リソースと所有権](../guides/resources-and-ownership.md) を参照してください
* **コンポーネントが import する `resource`（ホスト定義）**: `WitHostResource` を継承する `interface` として生成されます。実装方法は [ホスト import と interface の共有](../guides/host-imports.md#3-ホスト定義-resourceimport-された-resource) を参照してください
