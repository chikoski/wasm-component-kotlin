# Example 03: Resource のライフサイクルと `borrow` / `own` 所有権 (`counter`)

WebAssembly Component Model の中核機能である **`resource`（ステートフルなハンドル参照）** を Kotlin の `AutoCloseable` と Context Parameters で扱うサンプルです。

---

## 1. WIT 定義 ([counter.wit](../../fixtures/counter.wit))

```wit
package example:counter@0.1.0;

interface counter-api {
    /// Stateful counter resource backed by linear memory inside the Wasm component
    resource counter {
        constructor(init: s32);
        increment: func();
        add: func(delta: s32);
        get-value: func() -> s32;
        merge: static func(a: borrow<counter>, b: own<counter>) -> counter;
    }
}

world counter-world {
    export counter-api;
}
```

---

## 2. バインディングの生成コマンド

```bash
./gradlew run --args="fixtures/counter.wit -o build/examples/03-counter-resource -p com.example.counter"
```

---

## 3. ホスト側からの呼び出しコード ([Main.kt](Main.kt))

```kotlin
package com.example.counter

import com.example.counter.exports.example.counter.counter_api.Counter
import java.nio.file.Paths

fun main() {
    val wasmPath = Paths.get("fixtures/counter.wasm")

    CounterWorld.withSession(wasmPath) {
        // 1. リソースの生成: companion object の invoke が Context Parameter から Session を受け取る
        Counter(10).use { c1 ->
            c1.increment()
            c1.increment()
            c1.add(5)
            println("c1 initial value = ${c1.getValue()}") // 17

            val c2 = Counter(25)
            c2.increment() // 26

            // 2. static 関数 merge(a: borrow<counter>, b: own<counter>) -> counter
            //    - c1 は borrow なので呼び出し後も引き続き有効
            //    - c2 は own なので所有権がゲストへ移動し、ホスト側では自動的に closed 扱いになる
            Counter.merge(c1, c2).use { merged ->
                println("c1 after merge   = ${c1.getValue()}") // 17 (借用なので生存)
                println("c2 isClosed      = ${c2.isClosed}")   // true (所有権移動済み)
                println("merged value     = ${merged.getValue()}") // 43
            }
            // merged の .use ブロック終了時に merged のハンドルが drop される
        }
        // c1 の .use ブロック終了時に c1 のハンドルが drop される
    }
}
```
