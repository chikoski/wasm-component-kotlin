# Example 04: 代数的データ型 (`Record`, `Variant`, `Flags`, `option` → `T?`, `WitResult`) と `InMemoryWasmtimeSession` による単体テスト

WIT の豊富な型システム（`record`, `enum`, `flags`, `variant`, `tuple`, `typealias`, `option`, `result`, `resource`）の操作方法と、`.wasm` バイナリがなくても `InMemoryWasmtimeSession` を使ってホストアプリのロジックやリソース解放漏れを単体テストする方法を示すサンプルです。

---

## 1. WIT 定義 ([calculator.wit](../../fixtures/calculator.wit))

```wit
package example:calc@0.1.0;

interface types {
    record point { x: f32, y: f32 }
    enum color { red, green, blue }
    flags permissions { read, write, execute }
    variant status { ok, err(string), code(u32) }
    type user-id = u64;
}

/// Resources the component defines (exported): the host holds handles to them.
interface counters {
    resource counter {
        constructor(init: u32);
        increment: func();
        get-value: func() -> u32;
        merge: static func(a: borrow<counter>, b: own<counter>) -> counter;
    }
}

world calculator {
    use types.{point, color, permissions, status, user-id};

    import log: func(msg: string);
    export counters;
    export add: func(a: s32, b: s32) -> s32;
    export compute: func(
        p: point,
        opt: option<string>,
        list-data: list<u8>,
        tup: tuple<u32, string>
    ) -> result<status, string>;
}
```

---

## 2. バインディングの生成コマンド

```bash
./gradlew run --args="fixtures/calculator.wit -o build/examples/04-algebraic-types-and-mock -p com.example.wasm"
```

---

## 3. `InMemoryWasmtimeSession` を使った実行・テストコード ([Main.kt](Main.kt))

`WasmtimeSession.factory` に `InMemoryWasmtimeSession` を設定することで、ネイティブライブラリや `.wasm` バイナリを使わずにすべての型とリソースライフサイクルをテストできます。

```kotlin
package com.example.wasm

import com.example.wasm.example.calc.types.Permissions
import com.example.wasm.example.calc.types.Point
import com.example.wasm.example.calc.types.Status
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import wasm2kt.runtime.Linker
import java.nio.file.Paths

fun main() {
    var activeSession: InMemoryWasmtimeSession? = null

    WasmtimeSession.factory = { _, imports ->
        val hostImports = imports as Linker
        InMemoryWasmtimeSession(imports).apply {
            activeSession = this
            registerFunction("add") { args ->
                val a = args[0] as Int
                val b = args[1] as Int
                hostImports.dispatchHostImport("log", JsonArray(listOf(JsonPrimitive("add($a, $b)"))))
                a + b
            }
            registerFunction("compute") { args ->
                val p = args[0] as Point
                val opt = args[1] as String?
                val bytes = args[2] as ByteArray
                val tup = args[3] as Tuple2<UInt, String>
                if (p.x < 0f) {
                    WitResult.Err("negative x: $opt")
                } else {
                    WitResult.Ok(Status.Code(tup.f0 + bytes.size.toUInt()))
                }
            }
        }
    }

    val imports = CalculatorWorld.imports(
        world = {
            object : CalculatorWorld.Imports {
                override fun log(msg: String) = println("[HOST LOG] $msg")
            }
        },
    )

    CalculatorWorld.withSession(Paths.get("mock.wasm"), imports) {
        val perms = Permissions.READ or Permissions.EXECUTE
        println("Permissions: read=${perms.read}, write=${perms.write}, execute=${perms.execute}")

        val res = CalculatorWorld.compute(
            p = Point(1.5f, 2.5f),
            opt = "sample",
            listData = byteArrayOf(1, 2, 3),
            tup = Tuple2(100u, "tag"),
        )

        when (res) {
            is WitResult.Ok -> when (val status = res.value) {
                is Status.Ok -> println("Status: OK")
                is Status.Err -> println("Status Err: ${status.value}")
                is Status.Code -> println("Status Code: ${status.value}")
            }
            is WitResult.Err -> println("Compute failed: ${res.error}")
        }
    }

    println("Session closed properly: ${activeSession?.isClosed}")
}
```
