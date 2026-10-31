# wasm2kt Specification (SPEC.md)

## 1. Project Overview
* **Project Name**: `wasm2kt` (Repository: `wasm-component-kotlin`)
* **Primary Goal**: Generate type-safe, idiomatic Kotlin **host-side bindings** (worlds, interfaces, functions, records, variants, flags, tuples, resources) from WebAssembly Components / WIT definitions, targeting the **Wasmtime** runtime.
* **Architecture Strategy**:
   * Leverage `wasm-tools component wit --json` for robust, upstream-compatible WIT parsing into JSON AST.
   * Implement the core generator and CLI in Kotlin, using **KotlinPoet** for AST-to-code generation and `kotlinx.serialization` for AST ingestion.
   * Represent each WIT `world` as a Kotlin **singleton object (`object`)**:
     * Exported functions are generated as **extension functions** on the world singleton object, taking the execution context (`Session`) via **Kotlin Context Parameters** (`context(session: <World>.Session)`).
     * Resources and composite types are generated as **nested classes/types** inside the world singleton object (or interface namespace).
     * Resource instantiation captures the `Session` from the context parameter so that `java.lang.AutoCloseable` (`close()`) and member methods work seamlessly with Kotlin's `.use { ... }`.

---

## 2. Technical Stack
* **Language & Runtime**: Kotlin 2.2+ (with `-Xcontext-parameters` enabled), Targeting JVM 21+ (leveraging Panama Foreign Function & Memory API / Wasmtime C API bridge).
* **Target Wasm Runtime**: **Wasmtime** (Component Model support).
* **Build System**: Gradle (Kotlin DSL)
* **Key Dependencies**:
   * `com.squareup:kotlinpoet`: Safe, idiomatic Kotlin code generation.
   * `org.jetbrains.kotlinx:kotlinx-serialization-json`: Fast decoding of `wasm-tools` AST JSON (`ignoreUnknownKeys = true`).
   * `com.github.ajalt.clikt:clikt`: CLI argument parsing.
* **External Dependency**: `wasm-tools` executable (assumed available on `PATH`, or passed via stdin/pre-extracted JSON).

---

## 3. Architecture & Data Flow
```text
+--------------------+
|  .wit / .wasm file |
+--------------------+
          |
          |  (1) execute `wasm-tools component wit --json <input>`
          v
+--------------------+
|  WIT AST (JSON)    |
+--------------------+
          |
          |  (2) decode via kotlinx.serialization
          v
+--------------------+
| WitAst Data Models |
+--------------------+
          |
          |  (3) traverse & build specs via KotlinPoet
          v
+--------------------+
| KotlinPoet Specs   | (FileSpec / TypeSpec / FunSpec)
+--------------------+
          |
          |  (4) write to disk
          v
+--------------------+
| Generated .kt files| (World Object + Context-parameterized Host Bindings)
+--------------------+
```

---

## 4. CLI Interface Design
Command name: `wasm2kt`

```bash
# Basic usage with a .wit or .wasm file
wasm2kt <input-path> -o <output-dir> --package <package-name> [--world <world-name>]

# Advanced / Piped usage (reading JSON AST directly from stdin)
wasm-tools component wit --json component.wasm | wasm2kt --from-json -o src/main/kotlin --package com.example.wasm
```

Options:
* `<input-path>`: Path to `.wit` file, directory containing WIT packages, or `.wasm` component binary (optional when `--from-json` reads from `stdin`).
* `-o, --output <dir>`: Directory where generated `.kt` files will be placed (Required).
* `-p, --package <name>`: Target Kotlin package name (Required).
* `-w, --world <name>`: Specific WIT `world` name to generate bindings for (Optional; defaults to all worlds or the single default world).
* `--from-json`: Flag to indicate input is already a `wasm-tools` JSON AST (reads from `<input-path>` if provided, or `stdin` otherwise).
* `--wasm-tools-path <path>`: Optional override for the `wasm-tools` binary path.

---

## 5. Binding & Type Mapping Specifications

### 5.1 World Structure & Execution Context (`Session`)
Each WIT `world` is mapped to a Kotlin singleton `object`.
* **World Singleton (`object <WorldName>`)**:
  * Acts as the namespace for types, resources, and imported/exported interfaces belonging to the world.
  * Provides `openSession(...)` and a scoped `withSession(...)` helper that manages the underlying Wasmtime `Store` and component `Instance`.
* **Execution Context (`<WorldName>.Session`)**:
  * Implements `java.lang.AutoCloseable` and wraps the Wasmtime runtime state (`Store`, `Instance`, resource tables).
  * Passed implicitly to world functions and resource constructors via Kotlin's `context(session: <WorldName>.Session)`.
* **Imports (`<WorldName>.Imports`)**:
  * Host-provided imports are represented as Kotlin `interface`s that the host application implements and passes when opening a `Session`.

#### Example Generated World Skeleton:
```kotlin
object CalculatorWorld {
    interface Imports {
        fun log(msg: String)
    }

    class Session internal constructor(
        internal val runtimeSession: WasmtimeSession
    ) : AutoCloseable {
        override fun close() {
            runtimeSession.close()
        }
    }

    fun openSession(wasmPath: Path, imports: Imports): Session = ...

    inline fun <R> withSession(
        wasmPath: Path,
        imports: Imports,
        block: context(Session) () -> R
    ): R = openSession(wasmPath, imports).use { session ->
        block(session)
    }
}

// Exported world functions are extension functions on the World object with Context Parameters:
context(session: CalculatorWorld.Session)
fun CalculatorWorld.add(a: Int, b: Int): Int {
    return session.runtimeSession.invoke("add", a, b)
}
```

### 5.2 Primitive Types
| WIT Type | Kotlin Type | Notes |
| :--- | :--- | :--- |
| `bool` | `kotlin.Boolean` | |
| `u8` / `s8` | `kotlin.UByte` / `kotlin.Byte` | |
| `u16` / `s16` | `kotlin.UShort` / `kotlin.Short` | |
| `u32` / `s32` | `kotlin.UInt` / `kotlin.Int` | |
| `u64` / `s64` | `kotlin.ULong` / `kotlin.Long` | |
| `f32` (`float32`) | `kotlin.Float` | |
| `f64` (`float64`) | `kotlin.Double` | |
| `char` | `CodePoint` (value class over `Int`) | Unicode Scalar Value; `kotlin.Char` cannot hold non-BMP code points ([ADR-0006](docs/adr/0006-type-mapping.md)) |
| `string` | `kotlin.String` | UTF-8 encoded in Canonical ABI |

### 5.3 Composite & Algebraic Types
* **`record` -> Kotlin `data class`**
  * Example: `record point { x: f32, y: f32 }` ->
    ```kotlin
    data class Point(val x: Float, val y: Float)
    ```
* **`variant` -> Kotlin `sealed interface` + nested `data class` / `data object`**
  * Example: `variant status { ok, err(string) }` ->
    ```kotlin
    sealed interface Status {
        data object Ok : Status
        data class Err(val value: String) : Status
    }
    ```
* **`enum` -> Kotlin `enum class`**
  * Example: `enum color { red, green, blue }` ->
    ```kotlin
    enum class Color { RED, GREEN, BLUE }
    ```
* **`flags` -> Kotlin `@JvmInline value class` with bitwise operations**
  * Backed by `UInt` (or `ULong` for >32 flags) with boolean property accessors and bitwise combinators (`or`, `and`, `inv`).
  * Example: `flags permissions { read, write, execute }` ->
    ```kotlin
    @JvmInline
    value class Permissions(val raw: UInt = 0u) {
        val read: Boolean get() = (raw and 1u) != 0u
        val write: Boolean get() = (raw and 2u) != 0u
        val execute: Boolean get() = (raw and 4u) != 0u

        infix fun or(other: Permissions): Permissions = Permissions(raw or other.raw)
        infix fun and(other: Permissions): Permissions = Permissions(raw and other.raw)

        companion object {
            val NONE = Permissions(0u)
            val READ = Permissions(1u shl 0)
            val WRITE = Permissions(1u shl 1)
            val EXECUTE = Permissions(1u shl 2)
        }
    }
    ```
* **`tuple<T1, T2, ...>` -> Auto-generated generic `data class TupleN`**
  * Example: `tuple<u32, string>` -> `Tuple2<UInt, String>`
    ```kotlin
    data class Tuple2<out T0, out T1>(val f0: T0, val f1: T1)
    ```
* **Type Aliases (`type` / `use`) -> Kotlin `typealias`**
  * Example: `type user-id = u64` ->
    ```kotlin
    typealias UserId = ULong
    ```
* **`option<T>` -> Kotlin nullable `T?`** (superseded by [ADR-0006](docs/adr/0006-type-mapping.md))
  * Only when `T` itself can be null (e.g. `option<option<U>>`) is the outer option a `WitOption<T>`, so nested options stay unambiguous.
  * Originally this spec mapped every `option<T>` to `WitOption<out T>`.
    ```kotlin
    sealed interface WitOption<out T> {
        data class Some<out T>(val value: T) : WitOption<T>
        data object None : WitOption<Nothing>
    }
    ```
* **`result<T, E>` -> Sealed interface `WitResult<out T, out E>`**
  * Supports full type safety for arbitrary WIT error types `E` (where `Unit` is used when `T` or `E` is omitted in WIT, e.g., `result<_, string>` -> `WitResult<Unit, String>`).
    ```kotlin
    sealed interface WitResult<out T, out E> {
        data class Ok<out T>(val value: T) : WitResult<T, Nothing>
        data class Err<out E>(val error: E) : WitResult<Nothing, E>
    }
    ```
* **`list<T>` -> `kotlin.collections.List<T>`**, except **`list<u8>` -> `ByteArray`** ([ADR-0006](docs/adr/0006-type-mapping.md)).

### 5.4 Functions & Interfaces
* **Top-level exported functions**: Generated as extension functions on the world singleton `object` requiring `context(session: <WorldName>.Session)`.
* **Exported WIT `interface`**: Generated as a nested `object` (or property namespace) inside the world `object`, with its functions defined as context-parameterized functions (`context(session: <WorldName>.Session)`).
* **Imported WIT `interface` / functions**: Generated as Kotlin `interface` definitions to be implemented by the host and passed into `openSession` / `withSession`.

### 5.5 Resources (`resource`, `own<T>`, `borrow<T>`)
* **WIT `resource` -> Nested Kotlin `class` inside the World `object` implementing `java.lang.AutoCloseable`**:
  * Captures `private val session: <WorldName>.Session` upon creation and holds `val handle: Int`.
  * **Constructors**: Exposed via `companion object` `operator fun invoke(...)` (or factory methods) annotated with `context(session: <WorldName>.Session)` so callers can instantiate resources naturally as `MyWorld.MyResource(...)`.
  * **Static Functions**: Exposed as functions on the resource's `companion object` with `context(session: <WorldName>.Session)`.
  * **Resource Methods**: Member functions on the resource class (using the captured `session` and `handle`).
  * **Lifecycle (`close()`)**: Overrides `AutoCloseable.close()` using the captured `session` to invoke the Wasmtime resource drop function, enabling standard `.use { ... }` blocks without requiring an extra context parameter at close time.

#### Example Generated Resource:
```kotlin
object DbWorld {
    // ... Session definition ...

    class Connection internal constructor(
        private val session: Session,
        val handle: Int
    ) : AutoCloseable {

        fun query(sql: String): List<String> {
            return session.runtimeSession.invokeMethod(handle, "query", sql)
        }

        override fun close() {
            session.runtimeSession.dropResource(handle)
        }

        companion object {
            context(session: Session)
            operator fun invoke(url: String): Connection {
                val handle = session.runtimeSession.newResource("connection", url)
                return Connection(session, handle)
            }
        }
    }
}
```

### 5.6 Naming Conventions
* **Worlds, Interfaces, Records, Variants, Enums, Flags, Resources**: `kebab-case` -> `PascalCase` (e.g., `http-types` -> `HttpTypes`).
* **Functions, Method Names, Record Fields, Flag Properties**: `kebab-case` -> `camelCase` (e.g., `get-user-id` -> `getUserId`).
* **Enum Entries & Flag Constants**: `kebab-case` -> `UPPER_SNAKE_CASE` (e.g., `not-found` -> `NOT_FOUND`).
* **Documentation**: WIT doc comments (`///`) are preserved and emitted as Kotlin KDoc comments via KotlinPoet.

---

## 6. Implementation Milestones

### Phase 1: Core AST Modeling & Offline Code Generation (MVP)
* Set up Gradle project with Kotlin 2.2+ (`-Xcontext-parameters`), KotlinPoet, `kotlinx.serialization`, and Clikt.
* Define `kotlinx.serialization` models for `wasm-tools component wit --json` output (worlds, interfaces, packages, types, functions).
* Implement `WitToKotlinPoetConverter`:
   * Generate World singleton `object`, `Session` skeleton, and `withSession` DSL.
   * Map primitive types, `record` (`data class`), `enum` (`enum class`), `variant` (`sealed interface`), `flags` (`@JvmInline value class`), `tuple` (`TupleN`), `typealias`, `option`, and `result` (`WitResult`).
   * Generate context-parameterized extension functions (`context(session: Session)`) for exported functions.
* Add golden-file unit tests verifying generated Kotlin code against fixed JSON AST fixtures.

### Phase 2: Resources & CLI / `wasm-tools` Execution Bridge
* Support WIT `resource` generation (nested class implementing `AutoCloseable`, `companion object` constructor with `context(session: Session)`, `own<T>` / `borrow<T>` handling).
* Implement Clikt CLI handling arguments (`<input-path>`, `--output`, `--package`, `--world`, `--from-json`, `--wasm-tools-path`).
* Implement subprocess execution to invoke `wasm-tools component wit --json` when `.wit` or `.wasm` files are supplied.

### Phase 3: Wasmtime Runtime Bridge & End-to-End Integration
* Implement the Wasmtime runtime bridge (`WasmtimeSession` / Canonical ABI lifting & lowering via JVM FFM API or JNI).
* Connect generated `Session`, host `Imports`, exported extension functions, and resource handles to live Wasmtime component instances.

---

## 7. Testing Strategy
* **Unit Tests**: Feed captured `wasm-tools` JSON AST fragments into the generator and assert that the resulting KotlinPoet output matches golden `.kt` expectations.
* **Compilation Tests**: Invoke `wasm2kt` against sample `.wit` files (e.g., `calculator.wit`, `key-value.wit`) and verify that the generated `.kt` files compile cleanly with `kotlinc -Xcontext-parameters`.
* **Runtime Integration Tests (Phase 3)**: Execute compiled Kotlin bindings against actual `.wasm` components running on Wasmtime.
