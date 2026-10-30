# wasm2kt (`wasm-component-kotlin`)

**WebAssembly Component Model (WIT) から型安全でイディオマティックな Kotlin ホスト側バインディングを生成するコードジェネレータ**

`wasm2kt` は、`.wit` 定義ファイルまたは `.wasm` コンポーネントバイナリを入力として受け取り、**Kotlin 2.2+ の Context Parameters (`context(session: Session)`)** と **Wasmtime (JVM Foreign Function & Memory API)** を活用した Kotlin ホストバインディングを自動生成します。

---

## 主な特徴 (Highlights)

* **特異オブジェクト (`object`) + Context Parameters による直感的な DSL**
  * WIT の各 `world` を Kotlin のシングルトン `object` として生成します。
  * エクスポート関数は `context(session: <World>.Session)` を持つ拡張関数として生成されるため、`withSession` スコープ内で自然に呼び出せます。
* **安全なリソース (`resource`) ライフサイクルと所有権管理**
  * WIT の `resource` は `java.lang.AutoCloseable` を実装した内部クラスとして生成されます。
  * コンストラクタ（`companion object operator fun invoke`）が暗黙の `Session` をキャプチャするため、Kotlin 標準の `.use { ... }` ブロックで Wasmtime 側のリソース解放（`resource_drop`）を確実に自動化できます。
  * `borrow<T>`（借用）と `own<T>`（所有権移動・`consumeOwnership()`）を区別し、所有権移動後の誤使用や二重解放を防止します。
* **代数的データ型とビットフラグの完全な型安全性**
  * `variant`、`option<T>` (`WitOption<out T>`)、`result<T, E>` (`WitResult<out T, out E>`) をすべて `sealed interface` で表現し、`when` 式による網羅性チェックをサポートします（`option<option<T>>` のネスト問題も発生しません）。
  * `flags` は `@JvmInline value class` + ビット演算メソッド（`or`, `and`）としてゼロオーバーヘッドに生成します。
* **Chicory バックエンドと Gradle プラグイン（Android 対応）**
  * Gradle プラグイン `io.github.wasm2kt` で、コンポーネントの core モジュールをビルド時に JVM バイトコードへコンパイルし、純 JVM のランタイム [Chicory](https://github.com/dylibso/chicory) 上で実行します。ネイティブライブラリ・JNI・`wasm-tools` は不要で、Android でもそのまま動きます。
  * ワールド DSL は共通で、`CalculatorWorld.withSession(CalculatorComponent) { ... }` のように渡すものを変えるだけです。詳しくは [Chicory バックエンドと Gradle プラグイン](docs/chicory-backend.md) を参照してください。
* **Wasmtime ネイティブ実行 (JVM FFM API) & インメモリモックの両対応**
  * デフォルトでは Java 21+ の Foreign Function & Memory (FFM) API (`java.lang.foreign`) 経由で Rust 製の `wasmtime` ブリッジ (`cdylib`) を呼び出し、実際の `.wasm` コンポーネントをネイティブ実行します。
  * 単体テスト向けに、`.wasm` バイナリやネイティブライブラリなしで動作を検証できる `InMemoryWasmtimeSession` も同梱しています。

---

## ひと目でわかるコード例 (Quick Examples)

### Example 1: 四則演算コンポーネントの呼び出し (`arithmetic.wit`)

**入力 WIT ([arithmetic.wit](src/test/resources/fixtures/arithmetic.wit)):**
```wit
package example:arithmetic@0.1.0;

world arithmetic {
    export add: func(a: s32, b: s32) -> s32;
    export sub: func(a: s32, b: s32) -> s32;
    export mul: func(a: s32, b: s32) -> s32;
    export div: func(a: s32, b: s32) -> s32;
}
```

**ホスト側 Kotlin コード:**
```kotlin
import com.example.arithmetic.*
import java.nio.file.Paths

fun main() {
    val wasmPath = Paths.get("arithmetic.wasm")

    // withSession ブロックを抜けると Wasmtime の Store / Instance が自動的に close されます
    ArithmeticWorld.withSession(wasmPath) {
        val sum = ArithmeticWorld.add(20, 7)      // 27
        val diff = ArithmeticWorld.sub(50, 8)     // 42
        val prod = ArithmeticWorld.mul(-6, -7)    // 42
        val quot = ArithmeticWorld.div(84, 2)     // 42
        println("sum=$sum, diff=$diff, prod=$prod, quot=$quot")
    }
}
```

---

### Example 2: ステートフルな Resource と `borrow` / `own` 所有権 (`counter.wit`)

**入力 WIT ([counter.wit](src/test/resources/fixtures/counter.wit)):**
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

**ホスト側 Kotlin コード:**
```kotlin
import com.example.counter.*
import java.nio.file.Paths

fun main() {
    val wasmPath = Paths.get("counter.wasm")

    CounterWorldWorld.withSession(wasmPath) {
        // Counter(...) の生成時に Context Parameter から Session を自動キャプチャし、.use で自動解放
        CounterWorldWorld.Counter(10).use { c1 ->
            c1.increment()
            c1.add(5)
            println("c1 = ${c1.getValue()}") // 16

            val c2 = CounterWorldWorld.Counter(26)

            // merge(a: borrow<counter>, b: own<counter>) -> counter
            // c1 は借用(borrow)なので引き続き利用可能、c2 は所有権移動(own)により消費される
            CounterWorldWorld.Counter.merge(c1, c2).use { merged ->
                println("c1 after merge = ${c1.getValue()}") // 16 (引き続き有効)
                println("c2 isClosed = ${c2.isClosed}")      // true (所有権が移動済み)
                println("merged = ${merged.getValue()}")     // 42
            }
        }
    }
}
```

---

## 必要要件 (Requirements)

* **JDK 21 以上**（Foreign Function & Memory API のため `--enable-preview --enable-native-access=ALL-UNNAMED` を使用）
* **Kotlin 2.2.0 以上**（Context Parameters のため `-Xcontext-parameters` を使用）
* **`wasm-tools` CLI**（`1.200.0+`、`.wit` や `.wasm` から JSON AST を抽出するために使用）
* **Rust ツールチェーン (`cargo`)**（ネイティブ Wasmtime ブリッジ `rust-bridge` をビルドするために使用）

---

## クイックスタート (Quick Start)

### 1. ネイティブ Wasmtime ブリッジのビルド

最初に Rust 製の共有ライブラリ（macOS: `libwasm2kt_wasmtime_bridge.dylib` / Linux: `.so` / Windows: `.dll`）をビルドします。

```bash
cargo build --release --manifest-path rust-bridge/Cargo.toml
```

### 2. `wasm2kt` CLI でバインディングを生成

`.wit` ファイル、WIT ディレクトリ、または `.wasm` コンポーネントバイナリを指定して Kotlin コードを生成します。

```bash
# .wit ファイルから生成する場合
./gradlew run --args="src/test/resources/fixtures/counter.wit -o build/generated -p com.example.counter"

# .wasm コンポーネントバイナリから直接生成する場合
./gradlew run --args="src/test/resources/fixtures/counter.wasm -o build/generated -p com.example.counter"

# wasm-tools の JSON AST をパイプで渡す場合
wasm-tools component wit --json component.wasm | ./gradlew run --args="--from-json -o build/generated -p com.example.wasm"
```

### 3. テストの実行（実 `.wasm` コンポーネントの E2E 検証を含む）

```bash
./gradlew test
```

---

## CLI オプション一覧

```text
Usage: wasm2kt [<options>] [<input-path>]

Arguments:
  <input-path>               .wit ファイル、WIT パッケージディレクトリ、.wasm コンポーネントバイナリ、
                             または JSON AST ファイルのパス（--from-json 時は省略で stdin から読込）

Options:
  -o, --output=<path>        生成された .kt ファイルの出力先ディレクトリ (必須)
  -p, --package=<text>       生成される Kotlin コードのパッケージ名 (必須)
  -w, --world=<text>         対象の WIT world 名を指定（省略時はすべての world を生成）
  --from-json                入力を wasm-tools の JSON AST として直接読み込むフラグ
  --no-wasmtime              Wasmtime (FFM/JNI) セッション実装を生成しない（Chicory のみ・Android 向け）
  --wasm-tools-path=<text>   wasm-tools 実行バイナリのパス（デフォルト: "wasm-tools"）
  -h, --help                 ヘルプを表示
```

---

## ドキュメント & 使用例 (Documentation & Examples)

### 詳細ドキュメント (`docs/`)
* **[プロジェクトの現状とアクションアイテム](docs/STATUS.md)** / **[設計判断の記録 (ADR)](docs/adr/README.md)**
* **[Getting Started (環境構築・チュートリアル)](docs/getting-started.md)**: ゼロから Wasm コンポーネントを用意し、Kotlin プロジェクトに組み込んで実行するまでの手順。
* **[WIT 型マッピング & DSL ガイド](docs/type-mapping-and-dsl.md)**: プリミティブ型・複合型（`record`, `variant`, `enum`, `flags`, `tuple`, `WitOption`, `WitResult`）および `resource`・Context Parameters の詳細リファレンス。
* **[Chicory バックエンドと Gradle プラグイン](docs/chicory-backend.md)**: Android を含む純 JVM 実行。ビルド時の分解・AOT コンパイルのパイプラインと、プラグインの使い方。
* **[アーキテクチャ解説](docs/architecture.md)**: `wasm-tools` JSON AST から KotlinPoet への変換パイプラインと、JVM FFM API ⇄ Rust `wasmtime` (`cdylib`) 間の C ABI 設計。
* **[技術仕様書 (SPEC.md)](SPEC.md)** / **[仕様レビューと決定事項 (SPEC_REVIEW.md)](SPEC_REVIEW.md)**

### サンプルアプリ (`samples/`)
* **[android](samples/android)**: Gradle プラグインを使い、ビルド時にコンパイルしたコンポーネントを Android アプリから呼び出すサンプル。

### 使用例 (`examples/`)
* **[01-arithmetic](examples/01-arithmetic/README.md)**: 最小構成の四則演算コンポーネントのネイティブ実行。
* **[02-host-imports](examples/02-host-imports/README.md)**: ホスト関数 (`Imports`) の実装と Wasm からの FFM Upcall コールバック。
* **[03-counter-resource](examples/03-counter-resource/README.md)**: ステートフルな `resource` のライフサイクル（`.use` 自動解放、`borrow` と `own` による `merge`）。
* **[04-algebraic-types-and-mock](examples/04-algebraic-types-and-mock/README.md)**: `record`, `variant`, `flags`, `WitOption`, `WitResult` の操作と、`InMemoryWasmtimeSession` を使った単体テスト。
