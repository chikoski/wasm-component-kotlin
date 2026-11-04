package com.example.wasm

import com.example.wasm.example.calc.types.Permissions
import com.example.wasm.example.calc.types.Point
import com.example.wasm.example.calc.types.Status
import wasm2kt.runtime.Linker
import wasm2kt.runtime.WitWriter
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
                hostImports.call("log", WitWriter().apply { string("add($a, $b)") })
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

    val imports = imports(
        world = {
            object : Imports {
                override fun log(msg: String) = println("[HOST LOG] $msg")
            }
        },
    )

    withSession(Paths.get("mock.wasm"), imports) {
        val perms = Permissions.READ or Permissions.EXECUTE
        println("Permissions: read=${perms.read}, write=${perms.write}, execute=${perms.execute}")

        val res = compute(
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
