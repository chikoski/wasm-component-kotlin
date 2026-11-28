# テストの書き方

このガイドでは、`wasm2kt` で生成したバインディングを使うコードをテストする 3 つの方法を説明します。

## 1. Gradle プラグインの生成物をそのまま単体テストで動かす（推奨）

Gradle プラグインの生成物は既定で `AOT` バックエンドを使用する純 JVM コードであり、Android プロジェクトのローカルユニットテスト（`testDebugUnitTest`）でも、エミュレーターや実機なしで実際のコンポーネントをそのまま実行できます。

```kotlin
// app/src/test/kotlin/com/example/app/CounterTest.kt
import com.example.app.counter.CounterComponent
import com.example.app.counter.exports.example.counter.counter_api.Counter
import com.example.app.counter.withSession
import org.junit.Assert.assertEquals
import org.junit.Test

class CounterTest {
    @Test
    fun mergeCounters() {
        val result = withSession(CounterComponent) {
            Counter(10).use { a ->
                a.increment()
                val b = Counter(31)
                Counter.merge(a, b).use { it.getValue() }
            }
        }
        assertEquals(42, result)
    }
}
```

## 2. `InMemoryWasmtimeSession` でコンポーネントをモックする

`.wasm` バイナリがまだ手元にない段階や、ホスト側のロジック・リソース解放漏れ（`close()` の呼び忘れ）だけを単体テストしたい場合は、生成される `InMemoryWasmtimeSession` を使えます（`--no-wasmtime` を付けずに生成した場合に含まれます）。

`WasmtimeSession.factory` を差し替えることで、`withSession(Paths.get("mock.wasm"))` がネイティブライブラリの代わりにインメモリのモックセッションを返すようになります。

```kotlin
import com.example.arithmetic.InMemoryWasmtimeSession
import com.example.arithmetic.WasmtimeSession
import com.example.arithmetic.add
import com.example.arithmetic.withSession
import java.nio.file.Paths
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MockArithmeticTest {
    @AfterTest
    fun resetFactory() {
        WasmtimeSession.resetFactory()
    }

    @Test
    fun mockAdd() {
        var session: InMemoryWasmtimeSession? = null
        WasmtimeSession.factory = { _, imports ->
            InMemoryWasmtimeSession(imports).apply {
                session = this
                registerFunction("add") { args ->
                    (args[0] as Int) + (args[1] as Int)
                }
            }
        }

        val sum = withSession(Paths.get("unused.wasm")) {
            add(20, 22)
        }

        assertEquals(42, sum)
        assertTrue(session!!.isClosed)
    }
}
```

リソースのコンストラクタ（`[constructor]counter`）やメソッド（`[method]counter.increment`）、ホスト import の呼び出しを含む例は [`examples/04-algebraic-types-and-mock`](../../examples/04-algebraic-types-and-mock/README.md) を参照してください。

## 3. `ChicoryComponent.fromDirectory` で分解結果をインタプリタ実行する

AOT コンパイルを経ずに、`Decomposer` が出力したディレクトリ（`plan.json` と `core*.wasm`）を Chicory のインタプリタで直接読み込んで実行できます。`wasm2kt` 自身の統合テストや、分解結果の検証に使います。なお、同期コンポーネントの生成エントリーポイントを既定の `AOT` のまま生成している場合、`<Name>Component` 向けヘルパーは `AotComponentFactory` を受け取るため、`ChicoryComponent.fromDirectory` を渡すコンポーネントでは `backend.set(Wasm2KtBackend.CHICORY)` を指定してください。

```kotlin
import wasm2kt.runtime.chicory.ChicoryComponent
import java.nio.file.Paths

val component = ChicoryComponent.fromDirectory(Paths.get("build/wasm2kt/counter/decomposed"))
withSession(component) {
    // ...
}
```
