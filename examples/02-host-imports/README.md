# Example 02: ホスト関数のインポートと FFM Upcall コールバック (`live-calc`)

Wasm コンポーネントがホスト側の関数（`import record-calc: func(a: s32, b: s32)`）を呼び出すケースのサンプルです。
ホスト側で `<WorldName>.Imports` インターフェースを実装し、`<WorldName>.imports(...)` で `withSession` に渡すと、Wasm 実行中に JVM Foreign Function & Memory (FFM) API の Upcall Stub を経由して Kotlin のメソッドがリアルタイムにコールバックされます。

---

## 1. WIT 定義 ([live_calc.wit](../../fixtures/live_calc.wit))

```wit
package example:livecalc@0.1.0;

world live-calc {
    import record-calc: func(a: s32, b: s32);
    export add: func(a: s32, b: s32) -> s32;
    export multiply: func(a: s32, b: s32) -> s32;
}
```

---

## 2. バインディングの生成コマンド

```bash
./gradlew run --args="fixtures/live_calc.wit -o build/examples/02-host-imports -p com.example.live"
```

---

## 3. ホスト側からの呼び出しコード ([Main.kt](Main.kt))

```kotlin
package com.example.live

import java.nio.file.Paths

fun main() {
    val wasmPath = Paths.get("fixtures/live_calc.wasm")
    val auditLog = mutableListOf<String>()

    // 1. ホストが提供する Imports インターフェースを実装し、imports(...) に渡す
    //    （import した interface は、それぞれの package の Kotlin interface を実装して引数に渡す。ADR-0011）
    val hostImports = LiveCalcWorld.imports(
        world = {
            object : LiveCalcWorld.Imports {
                override fun recordCalc(a: Int, b: Int) {
                    auditLog += "Guest requested calculation with ($a, $b)"
                }
            }
        },
    )

    // 2. withSession に wasmPath と hostImports を渡して実行
    LiveCalcWorld.withSession(wasmPath, hostImports) {
        val sum = LiveCalcWorld.add(15, 27)
        val product = LiveCalcWorld.multiply(6, 7)

        println("sum = $sum, product = $product")
    }

    // 3. Wasm コンポーネント内からコールバックされた履歴を確認
    auditLog.forEach(::println)
}
```
