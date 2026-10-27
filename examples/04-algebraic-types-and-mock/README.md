# Example 04: 代数的データ型 (`Record`, `Variant`, `Flags`, `WitOption`, `WitResult`) と `InMemoryWasmtimeSession` による単体テスト

WIT の豊富な型システム（`record`, `enum`, `flags`, `variant`, `tuple`, `typealias`, `option`, `result`, `resource`）の操作方法と、`.wasm` バイナリがなくても `InMemoryWasmtimeSession` を使ってホストアプリのロジックやリソース解放漏れを単体テストする方法を示すサンプルです。

---

## 1. WIT 定義 ([calculator.wit](../../src/test/resources/fixtures/calculator.wit))

```wit
package example:calc@0.1.0;

interface types {
    record point { x: f32, y: f32 }
    enum color { red, green, blue }
    flags permissions { read, write, execute }
    variant status { ok, err(string), code(u32) }
    type user-id = u64;

    resource counter {
        constructor(init: u32);
        increment: func();
        get-value: func() -> u32;
        merge: static func(a: borrow<counter>, b: own<counter>) -> counter;
    }
}

world calculator {
    use types.{point, color, permissions, status, user-id, counter};

    import log: func(msg: string);
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
./gradlew run --args="src/test/resources/fixtures/calculator.wit -o build/examples/04-algebraic-types-and-mock -p com.example.wasm"
```

---

## 3. `InMemoryWasmtimeSession` を使った実行・テストコード ([Main.kt](Main.kt))

`WasmtimeSession.factory` に `InMemoryWasmtimeSession` を設定することで、ネイティブライブラリや `.wasm` バイナリを使わずにすべての型とリソースライフサイクルをテストできます。

```kotlin
package com.example.wasm

import java.nio.file.Paths

fun main() {
    var activeSession: InMemoryWasmtimeSession? = null

    // 1. インメモリモックセッションをファクトリに登録
    WasmtimeSession.factory = { _, imports ->
        val hostImports = imports as CalculatorWorld.Imports
        InMemoryWasmtimeSession(imports).apply {
            activeSession = this
            registerFunction("add") { args ->
                val a = args[0] as Int
                val b = args[1] as Int
                hostImports.log("add($a, $b)")
                a + b
            }
            registerFunction("compute") { args ->
                val p = args[0] as CalculatorWorld.Point
                val opt = args[1] as WitOption<String>
                val bytes = args[2] as List<UByte>
                val tup = args[3] as Tuple2<UInt, String>
                if (p.x < 0f) {
                    WitResult.Err("negative x: ${opt.getOrNull()}")
                } else {
                    WitResult.Ok(CalculatorWorld.Status.Code(tup.f0 + bytes.size.toUInt()))
                }
            }
        }
    }

    val imports = object : CalculatorWorld.Imports {
        override fun log(msg: String) = println("[HOST LOG] $msg")
    }

    // 2. 生成されたバインディングの呼び出し
    CalculatorWorld.withSession(Paths.get("mock.wasm"), imports) {
        // Flags のビット演算
        val perms = CalculatorWorld.Permissions.READ or CalculatorWorld.Permissions.EXECUTE
        println("Permissions: read=${perms.read}, write=${perms.write}, execute=${perms.execute}")

        // Record, WitOption, List, Tuple2 を渡して WitResult<Status, String> を受け取る
        val res = CalculatorWorld.compute(
            p = CalculatorWorld.Point(1.5f, 2.5f),
            opt = WitOption.Some("sample"),
            listData = listOf(1u, 2u, 3u),
            tup = Tuple2(100u, "tag"),
        )

        // sealed interface による網羅的なパターンマッチ
        when (res) {
            is WitResult.Ok -> when (val status = res.value) {
                is CalculatorWorld.Status.Ok -> println("Status: OK")
                is CalculatorWorld.Status.Err -> println("Status Err: ${status.value}")
                is CalculatorWorld.Status.Code -> println("Status Code: ${status.value}") // 103
            }
            is WitResult.Err -> println("Compute failed: ${res.error}")
        }
    }

    println("Session closed properly: ${activeSession?.isClosed}")
}
```
