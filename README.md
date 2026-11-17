# wasm2kt (`wasm-component-kotlin`)

[![CI](https://github.com/chikoski/wasm-component-kotlin/actions/workflows/ci.yml/badge.svg)](https://github.com/chikoski/wasm-component-kotlin/actions/workflows/ci.yml)

WebAssembly コンポーネント（`.wasm` / `.wit`）から、Kotlin のホストバインディングを生成するツールとランタイムです。
生成される Kotlin の呼び出しコード（ワールド DSL）は共通で、用途に応じて 2 つの実行バックエンドを使い分けられます。

| バックエンド | 実行方式 | 主な対象環境 | 入口 |
| :--- | :--- | :--- | :--- |
| **Chicory** | Gradle プラグインでビルド時に core モジュールを JVM バイトコードへコンパイルし、純 JVM で実行 | **Android（minSdk 26+）**、ネイティブライブラリを使わない JVM | `withSession(XxxComponent)` |
| **Wasmtime** | CLI でバインディングを生成し、Rust 製ブリッジ（Java 21 FFM / JNI）でネイティブ実行 | デスクトップ / サーバー JVM（JDK 21+） | `withSession(wasmPath)` |

選択の目安は [バックエンドの選び方](docs/guides/choosing-a-backend.md) を参照してください。

---

## クイックスタート

* **Android アプリから使う**: [Android クイックスタート](docs/tutorials/android-quickstart.md)（実例: [`samples/android`](samples/android/README.md)）
* **Kotlin/JVM から Gradle プラグインで使う（純 JVM）**: [Kotlin/JVM クイックスタート](docs/tutorials/jvm-quickstart.md)
* **Wasmtime と CLI で使う（ネイティブ実行）**: [Wasmtime クイックスタート](docs/tutorials/wasmtime-quickstart.md)（実例: [`examples/`](examples/)）

---

## コード例

### 関数の呼び出し (`arithmetic.wit`)

```wit
package example:arithmetic@0.1.0;

world arithmetic {
    export add: func(a: s32, b: s32) -> s32;
    export sub: func(a: s32, b: s32) -> s32;
}
```

生成されたパッケージのトップレベルに `withSession` とエクスポート関数（`context(session: Session)` を取る関数）が出力されます（[ADR-0012](docs/adr/0012-world-at-package-top-level.md)）。

```kotlin
import com.example.arithmetic.*

// Chicory バックエンド（Gradle プラグインで生成された ArithmeticComponent を渡す）
withSession(ArithmeticComponent) {
    val sum = add(20, 7)   // 27
    val diff = sub(50, 8)  // 42
}

// Wasmtime バックエンド（.wasm のパスを渡す）
withSession(Paths.get("arithmetic.wasm")) {
    val sum = add(20, 7)
}
```

### Resource と `borrow` / `own` (`counter.wit`)

```wit
package example:counter@0.1.0;

interface counter-api {
    resource counter {
        constructor(init: s32);
        increment: func();
        get-value: func() -> s32;
        merge: static func(a: borrow<counter>, b: own<counter>) -> counter;
    }
}

world counter-world {
    export counter-api;
}
```

エクスポートされた interface の型と関数は `<base>.exports.<namespace>.<package>.<interface>` パッケージに生成されます（[ADR-0011](docs/adr/0011-qualified-interface-keys.md)）。`resource` は `AutoCloseable` を実装します。

```kotlin
import com.example.counter.*
import com.example.counter.exports.example.counter.counter_api.Counter

withSession(CounterComponent) {
    Counter(10).use { c1 ->
        c1.increment()
        val c2 = Counter(31)

        // c1 は borrow のため呼び出し後も有効、c2 は own のため呼び出し成功後に closed になる
        Counter.merge(c1, c2).use { merged ->
            println(c1.getValue())     // 11
            println(c2.isClosed)       // true
            println(merged.getValue()) // 42
        }
    }
}
```

---

## 要件

要件は使う経路によって異なります。詳細は [互換性とバージョン要件](docs/reference/compatibility.md) にまとめています。

| 項目 | Chicory + Gradle プラグイン | Wasmtime + CLI |
| :--- | :--- | :--- |
| ビルド時の JDK | JDK 21 以上（Gradle デーモン） | JDK 21 以上 |
| 実行時の環境 | JVM 11 以上 / Android `minSdk 26` 以上 | JVM 21 以上（FFM は `--enable-preview --enable-native-access=ALL-UNNAMED`） |
| Kotlin | 2.4.21 で検証 | 2.4.21 で検証 |
| Rust / `cargo` | 不要 | 必要（`rust-bridge` または型付きブリッジのビルド） |
| `wasm-tools` | 不要（分解ツールを wasm として同梱） | 必要（`.wit` / `.wasm` から JSON AST を抽出） |

---

## 安全性の前提 (Trust model)

現在の `wasm2kt` は、**信頼できるコンポーネントを動かすこと**を前提としています。外部から受け取ったコンポーネント（ユーザーが追加するプラグインなど）を実行する場合は、以下の制約に注意してください（[#77](https://github.com/chikoski/wasm-component-kotlin/issues/77)）。

* **実行時間を制限しません。** 無限ループするゲストは、呼び出したスレッドをブロックし続けます。別スレッドからセッションを `close()` しても実行中の呼び出しは中断されません（`close()` は呼び出しの終了を待ちます）。Android のメインスレッドで起きると ANR になります
* **メモリを制限しません。** ゲストはコンポーネントが宣言した最大値（未宣言なら最大 4 GiB）まで線形メモリを拡張できるため、JVM や Android プロセスのメモリが枯渇することがあります
* WebAssembly のサンドボックスは維持され、import で渡したもの以外のホスト資源には触れられません。ただし Wasmtime の事前コンパイル済みファイル（`.cwasm`）は未検証の機械語を含むため、自分のビルドで生成したものだけを読み込んでください（[Wasmtime クイックスタート](docs/tutorials/wasmtime-quickstart.md#6-事前コンパイルcwasm)）

---

## ドキュメント

全体の目次は **[docs/index.md](docs/index.md)** にあります。

* **ガイド**: [バックエンドの選び方](docs/guides/choosing-a-backend.md) / [ホスト import と interface の共有](docs/guides/host-imports.md) / [リソースと所有権](docs/guides/resources-and-ownership.md) / [エラーとトラップ](docs/guides/errors-and-traps.md) / [テストの書き方](docs/guides/testing.md) / [コンポーネント作者向け](docs/guides/authoring-components.md) / [トラブルシューティング](docs/guides/troubleshooting.md)
* **リファレンス**: [WIT 型マッピングと DSL](docs/reference/type-mapping.md) / [Gradle プラグイン](docs/reference/gradle-plugin.md) / [CLI](docs/reference/cli.md) / [機能の対応表](docs/reference/feature-support.md) / [互換性とバージョン要件](docs/reference/compatibility.md)
* **仕組みと計測**: [アーキテクチャ](docs/explanation/architecture.md) / [ビルドの順序](docs/explanation/build-pipeline.md) / [呼び出しの経路](docs/explanation/call-path.md) / [性能とサイズ](docs/explanation/performance.md)
* **開発・現状**: [サンプル集 (`examples/`)](examples/README.md) / [CONTRIBUTING.md](CONTRIBUTING.md) / [プロジェクトの現状 (`STATUS.md`)](docs/STATUS.md) / [設計判断 (`adr/`)](docs/adr/README.md) / [変更履歴 (`CHANGELOG.md`)](CHANGELOG.md)
