# WIT 型マッピング & Kotlin DSL ガイド

このドキュメントでは、`wasm2kt` が WIT (WebAssembly Interface Type) の各要素をどのように Kotlin コードへ変換するか、また生成された DSL の使い方を詳しく解説します。

---

## 1. World と実行セッション (`Session` & Context Parameters)

WIT の `world` は、Kotlin の **シングルトンオブジェクト (`object <WorldName>World`)** にマッピングされます。

```kotlin
object CalculatorWorld {
    interface Imports {
        fun log(msg: String)
    }

    class Session internal constructor(
        internal val runtimeSession: WasmtimeSession,
    ) : AutoCloseable {
        override fun close() {
            runtimeSession.close()
        }
    }

    fun openSession(wasmPath: Path, imports: Imports): Session = ...

    inline fun <R> withSession(
        wasmPath: Path,
        imports: Imports,
        block: context(Session) () -> R,
    ): R = openSession(wasmPath, imports).use { session -> context(session) { block() } }
}
```

### 1.1 スコープ自動管理 (`withSession`) vs 手動管理 (`openSession`)
* **`withSession`（推奨）**: ブロック内で自動的に `context(session: Session)` が提供され、ブロックを抜けると Wasmtime の `Store` とメモリが確実に解放（`close()`）されます。
* **`openSession`**: GUI アプリやサーバーなどで長期間セッションを保持したい場合は、`val session = MyWorld.openSession(...)` で取得し、呼び出し時に `context(session) { MyWorld.func() }` を使います。使い終わったら `session.close()` を呼びます。

### 1.2 インポートがない World の省略呼び出し
WIT の `world` にホスト関数インポートが存在しない場合（例: `arithmetic.wit`）、`Imports` には `Imports.EMPTY` がデフォルト引数として設定されるため、第2引数を省略して `ArithmeticWorld.withSession(wasmPath) { ... }` と簡潔に書けます。

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
| `char` | `kotlin.Char` | |
| `string` | `kotlin.String` | UTF-8 文字列 |

---

## 3. 複合型・代数的データ型マッピング

### 3.1 `record` → `data class`
WIT のフィールド名（`kebab-case`）は Kotlin のプロパティ名（`camelCase`）に変換されます。

```wit
/// 2D point
record point {
    x: f32,
    y: f32,
}
```
↓
```kotlin
/**
 * 2D point
 */
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
↓
```kotlin
enum class Color {
    RED,
    GREEN,
    BLUE,
}
```

### 3.3 `flags` → `@JvmInline value class`（ビットフラグ）
ボクシングが発生しないインライン `value class` として生成され、各フラグの Boolean プロパティと `or` / `and` 中置演算子、定数が用意されます（32個以下のフラグは `UInt`、33個以上は `ULong`）。

```wit
flags permissions { read, write, execute }
```
↓
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
**使用例:**
```kotlin
val perms = MyWorld.Permissions.READ or MyWorld.Permissions.EXECUTE
if (perms.read && !perms.write) { ... }
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
↓
```kotlin
sealed interface Status {
    data object Ok : Status
    data class Err(val value: String) : Status
    data class Code(val value: UInt) : Status
}
```

### 3.5 `option<T>` と `result<T, E>` → `sealed interface WitOption` / `WitResult`
Nullable (`T?`) や `kotlin.Result` の制約（`option<option<T>>` が潰れる問題や、任意のエラー型 `E` を持てない問題）を解消するため、一貫して `sealed interface` で表現します。

```kotlin
sealed interface WitOption<out T> {
    fun getOrNull(): T? = when (this) {
        is Some -> value
        is None -> null
    }
    data class Some<out T>(val value: T) : WitOption<T>
    data object None : WitOption<Nothing>
}

sealed interface WitResult<out T, out E> {
    data class Ok<out T>(val value: T) : WitResult<T, Nothing>
    data class Err<out E>(val error: E) : WitResult<Nothing, E>
}
```
* WIT で `result<_, string>` のように成功型や失敗型が省略された箇所は `kotlin.Unit` にマッピングされます（例: `WitResult<Unit, String>`）。

### 3.6 `tuple<T0, T1, ...>` と `type` エイリアス
* **タプル**: 使用された要素数に応じて `data class Tuple2<out T0, out T1>(val f0: T0, val f1: T1)` などが自動生成されます。
* **型エイリアス**: `type user-id = u64` は Kotlin の `typealias UserId = ULong` として出力されます。

---

## 4. リソース (`resource`) と所有権 (`own<T>` / `borrow<T>`)

WIT の `resource` は World オブジェクト内部のクラスとして生成され、`java.lang.AutoCloseable` を実装します。

```kotlin
class Counter internal constructor(
    private val session: Session,
    val handle: Int,
) : AutoCloseable {
    var isClosed: Boolean = false
        internal set

    internal fun consumeOwnership(): Int {
        check(!isClosed) { "Resource Counter(handle=$handle) is already closed or moved" }
        isClosed = true
        return handle
    }

    fun increment() { ... }
    fun getValue(): Int { ... }

    override fun close() {
        if (!isClosed) {
            isClosed = true
            session.runtimeSession.dropResource(handle)
        }
    }

    companion object {
        context(session: Session)
        operator fun invoke(init: Int): Counter { ... }

        context(session: Session)
        fun merge(a: Counter, b: Counter): Counter {
            val resultHandle = session.runtimeSession.invoke<Int>(
                "[static]counter.merge",
                typeOf<Int>(),
                a.handle,              // borrow<counter>: ハンドルのみ渡す
                b.consumeOwnership(),  // own<counter>: 所有権を移動し b.isClosed = true にする
            )
            return Counter(session, resultHandle)
        }
    }
}
```

### 所有権のポイント
1. **自然なインスタンス化**: `companion object` の `operator fun invoke` が `context(session: Session)` を受け取るため、`CounterWorldWorld.Counter(10)` と通常のコンストラクタ呼び出しのように書けます。
2. **`.use { ... }` とのシームレスな統合**: 生成時に `Session` を内部フィールド `private val session` にキャプチャするため、`override fun close()` には追加のコンテキストパラメータが不要で、Kotlin 標準の `.use { ... }` がそのまま使えます。
3. **`borrow<T>` vs `own<T>`**:
   * `borrow<T>` 引数には `a.handle` が渡され、呼び出し後も `a` は引き続き使用可能です。
   * `own<T>` 引数には `b.consumeOwnership()` が渡され、`b.isClosed = true` となります。これにより、所有権を手放した後に誤ってメソッドを呼ぶと即座に `IllegalStateException` で検知でき、また外側の `.use` ブロック終了時に二重解放されることもありません。
