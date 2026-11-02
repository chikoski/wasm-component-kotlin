# Example 01: 基本的な四則演算コンポーネントの実行 (`arithmetic`)

インポートを持たない最小構成の WIT (`arithmetic.wit`) から Kotlin ホストバインディングを生成し、ネイティブ Wasmtime 上で四則演算（`add`, `sub`, `mul`, `div`）を実行するサンプルです。

---

## 1. WIT 定義 ([arithmetic.wit](../../fixtures/arithmetic.wit))

```wit
package example:arithmetic@0.1.0;

/// Four basic arithmetic operations on 32-bit signed integers (s32 -> Kotlin Int)
world arithmetic {
    export add: func(a: s32, b: s32) -> s32;
    export sub: func(a: s32, b: s32) -> s32;
    export mul: func(a: s32, b: s32) -> s32;
    export div: func(a: s32, b: s32) -> s32;
}
```

---

## 2. バインディングの生成コマンド

```bash
./gradlew run --args="fixtures/arithmetic.wit -o build/examples/01-arithmetic -p com.example.arithmetic"
```

### 生成される `ArithmeticWorld.kt`（抜粋）
```kotlin
package com.example.arithmetic

import java.lang.AutoCloseable
import java.nio.file.Path
import kotlin.Int
import kotlin.reflect.typeOf

context(session: Session)
fun add(a: Int, b: Int): Int =
    session.runtimeSession.invoke("add", typeOf<Int>(), a, b)

context(session: Session)
fun sub(a: Int, b: Int): Int =
    session.runtimeSession.invoke("sub", typeOf<Int>(), a, b)

context(session: Session)
fun mul(a: Int, b: Int): Int =
    session.runtimeSession.invoke("mul", typeOf<Int>(), a, b)

context(session: Session)
fun div(a: Int, b: Int): Int =
    session.runtimeSession.invoke("div", typeOf<Int>(), a, b)

object ArithmeticWorld {
    fun openSession(wasmPath: Path, imports: ImportsFactory = ImportsFactory.EMPTY): Session { /* ... */ }

    inline fun <R> withSession(
        wasmPath: Path,
        imports: ImportsFactory = ImportsFactory.EMPTY,
        block: context(Session) () -> R,
    ): R = openSession(wasmPath, imports).use { session -> context(session) { block() } }
    // ...
}
```

---

## 3. ホスト側からの呼び出しコード (`Main.kt`)

[Main.kt](Main.kt) にある通り、`withSession(wasmPath)` のブロック内で `add(...)` などの拡張関数を直接呼び出せます。

```kotlin
package com.example.arithmetic

import java.nio.file.Paths

fun main() {
    val wasmPath = Paths.get("fixtures/arithmetic.wasm")

    withSession(wasmPath) {
        val sum = add(20, 7)
        val diff = sub(50, 8)
        val prod = mul(-6, -7)
        val quot = div(84, 2)

        println("20 + 7   = $sum")   // 27
        println("50 - 8   = $diff")  // 42
        println("-6 * -7  = $prod")  // 42
        println("84 / 2   = $quot")  // 42
    }
}
```
