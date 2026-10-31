package com.example.wasm

import java.nio.file.Paths

fun main() {
    var activeSession: InMemoryWasmtimeSession? = null

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
                val opt = args[1] as String?
                val bytes = args[2] as ByteArray
                val tup = args[3] as Tuple2<UInt, String>
                if (p.x < 0f) {
                    WitResult.Err("negative x: $opt")
                } else {
                    WitResult.Ok(CalculatorWorld.Status.Code(tup.f0 + bytes.size.toUInt()))
                }
            }
        }
    }

    val imports = object : CalculatorWorld.Imports {
        override fun log(msg: String) = println("[HOST LOG] $msg")
    }

    CalculatorWorld.withSession(Paths.get("mock.wasm"), imports) {
        val perms = CalculatorWorld.Permissions.READ or CalculatorWorld.Permissions.EXECUTE
        println("Permissions: read=${perms.read}, write=${perms.write}, execute=${perms.execute}")

        val res = CalculatorWorld.compute(
            p = CalculatorWorld.Point(1.5f, 2.5f),
            opt = "sample",
            listData = byteArrayOf(1, 2, 3),
            tup = Tuple2(100u, "tag"),
        )

        when (res) {
            is WitResult.Ok -> when (val status = res.value) {
                is CalculatorWorld.Status.Ok -> println("Status: OK")
                is CalculatorWorld.Status.Err -> println("Status Err: ${status.value}")
                is CalculatorWorld.Status.Code -> println("Status Code: ${status.value}")
            }
            is WitResult.Err -> println("Compute failed: ${res.error}")
        }
    }

    println("Session closed properly: ${activeSession?.isClosed}")
}
