# Example 05: Gradle プラグインと Chicory バックエンドによる Pure JVM 実行 (`roundtrip`)

`io.github.wasm2kt` Gradle プラグインを使って `.wasm` コンポーネントをビルド時に分解・AOT コンパイルし、ネイティブライブラリ（JNI / FFM）を使わずに Pure JVM（Chicory バックエンド）上で実行するサンプルです。

---

## 1. WIT 定義 ([roundtrip.wit](../../fixtures/roundtrip.wit))

`import env-provider`（ホストインターフェースのインポート）、`export math-api`（インターフェースのエクスポート）、および `flags` / `enum` / `record` / `variant` を含むコンポーネントです。

---

## 2. Gradle ビルド設定 ([build.gradle.kts](build.gradle.kts))

`wasm2kt` ブロックに `.wasm` コンポーネントと出力先パッケージ名を登録すると、ビルド時に以下のタスクが自動実行されます。

1. `wasm2ktDecomposeRoundtrip`: コンポーネントをコアモジュール・`plan.json`・`wit.json` に分解します。
2. `wasm2ktCompileRoundtrip`: コアモジュールを Chicory のビルド時コンパイラで JVM バイトコードへ AOT コンパイルします。
3. `wasm2ktGenerateRoundtrip`: Kotlin ホストバインディングと `RoundtripComponent` エントリポイントを生成します。

```kotlin
plugins {
    kotlin("jvm")
    id("io.github.wasm2kt")
    application
}

wasm2kt {
    components {
        register("roundtrip") {
            source.set(file("roundtrip.wasm"))
            packageName.set("com.example.chicory")
        }
    }
}
```

---

## 3. ホスト側からの呼び出しコード ([Main.kt](Main.kt))

Wasmtime バックエンドと同じ DSL（`withSession` / `imports`）を使い、第 1 引数に生成された `RoundtripComponent` を渡します。

```kotlin
package com.example.chicory

import com.example.chicory.example.roundtrip.env_provider.EnvProvider
import com.example.chicory.exports.example.roundtrip.math_api.scale

fun main() {
    val hostImports = imports(
        exampleRoundtripEnvProvider = {
            object : EnvProvider {
                override fun getMultiplier(): Int = 6
                override fun formatTag(prefix: String, code: UInt): String = "$prefix#$code"
            }
        },
    )

    withSession(RoundtripComponent, hostImports) {
        val scaled = scale(7)
        val tag = describeCode("ERR", 404u)
        val initial = ItemSummary(
            count = 10u,
            perms = Permissions.READ or Permissions.EXECUTE,
            prio = Priority.LOW,
        )
        val updated = transformSummary(initial, Action.AddCount(5u))
        println("scaled = $scaled, tag = $tag, count = ${updated.count}")
    }
}
```
