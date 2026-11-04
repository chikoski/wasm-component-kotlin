# リソースと所有権

このガイドでは、コンポーネントがエクスポートする `resource`（ゲスト定義リソース）のライフサイクルと、`borrow<T>` / `own<T>` の所有権管理について説明します。
コンポーネントが import する `resource`（ホスト定義リソース）については、[ホスト import と interface の共有](host-imports.md#3-ホスト定義-resourceimport-された-resource)を参照してください。

## 1. 生成されるクラス

エクスポートされた interface 内の `resource` は、その interface のパッケージ（`<base>.exports.<ns>.<pkg>.<iface>`）に `AutoCloseable` を実装したクラスとして生成されます。

```wit
package example:counter@0.1.0;

interface counter-api {
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

生成される `Counter` クラスの概要は次のとおりです。

```kotlin
package com.example.counter.exports.example.counter.counter_api

class Counter internal constructor(
    private val session: WitSession?,
    val handle: Int,
) : AutoCloseable {
    var isClosed: Boolean = false
        internal set

    fun increment() { /* ... */ }
    fun add(delta: Int) { /* ... */ }
    fun getValue(): Int { /* ... */ }

    override fun close() {
        if (!isClosed) {
            isClosed = true
            session?.runtimeSession?.dropResource(handle)
        }
    }

    companion object {
        context(exports: CounterApiExports)
        operator fun invoke(init: Int): Counter { /* ... */ }

        context(exports: CounterApiExports)
        fun merge(a: Counter, b: Counter): Counter { /* ... */ }
    }
}
```

## 2. 生成と解放（`.use { ... }`）

* `companion object` の `operator fun invoke` が `context(exports: CounterApiExports)`（world の `Session` が実装するマーカー）を取るため、`withSession` ブロック内で `Counter(10)` のようにコンストラクタ形式で生成できます
* 生成時に `session` を内部に保持するため、`close()` の呼び出しにはコンテキストパラメータが不要です。Kotlin 標準の `.use { ... }` でスコープを抜けたときに `dropResource` が呼ばれます

```kotlin
withSession(CounterComponent) {
    Counter(10).use { c ->
        c.increment()
        println(c.getValue()) // 11
    } // ここで resource.drop が実行される
}
```

## 3. `borrow<T>` と `own<T>` の違い

WIT の関数引数には、リソースの借用（`borrow<T>`）と所有権の移動（`own<T>`）があります。Kotlin の型としてはどちらも同じ `Counter` ですが、呼び出し後の状態が異なります。

* **`borrow<T>`**: ハンドル番号だけを渡します。呼び出しが終わったあとも `isClosed` は `false` のままで、引き続きメソッドを呼べます
* **`own<T>`**: コンポーネントへ所有権を移動します。呼び出しが成功して戻った時点で `isClosed = true` になります。外側の `.use` ブロックで囲んでいても、`isClosed` が `true` になっているため二重に drop されることはありません

```kotlin
withSession(CounterComponent) {
    Counter(10).use { a ->
        val b = Counter(32)
        // merge(a: borrow<counter>, b: own<counter>) -> counter
        Counter.merge(a, b).use { merged ->
            println(a.getValue())      // 10（borrow なので引き続き使える）
            println(b.isClosed)        // true（own で消費されたため closed）
            println(merged.getValue()) // 42
        }
    }
}
```

## 4. ハンドルの検査（[#76](https://github.com/chikoski/wasm-component-kotlin/issues/76)）

生成コードは、リソースを引数として渡す際やメソッドを呼ぶ際に、コンポーネントへ到達する前に次の検査を行います。

* **すでに close または move されたリソース**: `IllegalStateException`（`"Resource Counter(handle=...) is already closed or moved"`）を投げます
* **別のセッションで作られたリソース**: ハンドル番号はセッションごとに採番されるため、別のセッションに属するリソースを渡すと `IllegalStateException`（`"Resource Counter(handle=...) belongs to another session"`）を投げます
* **呼び出し前の引数エラー時**: 同じ呼び出しの別の引数が不正でコンポーネント呼び出し前に失敗した場合、`own` 引数は消費されず有効なまま残ります

これらはホスト側の使い方エラーとして扱われるため、セッション自体は poison されず、そのまま使い続けられます（[エラーとトラップ](errors-and-traps.md)）。
