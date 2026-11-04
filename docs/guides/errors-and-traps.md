# エラーとトラップ

このガイドでは、コンポーネント呼び出し時に発生する例外の種類と、発生後のセッション（インスタンス）の扱いについて説明します。
設計の詳細は [ADR-0007](../adr/0007-canonical-abi-validation.md) に記録しています。

## 1. 例外の分類とセッションの状態

`wasm2kt` は、**ゲスト（コンポーネント）起因の失敗**と、**ホスト（Kotlin 側）の引数・状態の誤り**を区別します。

| 状況 | 投げられる例外 | セッション（インスタンス）の状態 |
| :--- | :--- | :--- |
| **WIT の `result<T, E>` の `err`** | 例外は投げない（`WitResult.Err(error)` が返る。`getOrThrow()` を呼んだ場合のみ `WitResultException`） | そのまま使い続けられる |
| **ホスト側の引数・状態の誤り**<br>• 既に close / move されたリソースや別セッションのリソースを渡した（[#76](https://github.com/chikoski/wasm-component-kotlin/issues/76)）<br>• 孤立サロゲートを含む文字列や不正な `CodePoint`<br>• 必要なホスト import が `Linker` に未登録<br>• 存在しないエクスポート名 | `IllegalArgumentException` / `IllegalStateException` | そのまま使い続けられる（コンポーネントに入る前に失敗するため、poison しない） |
| **ゲストのトラップ・Canonical ABI 違反**<br>• core wasm の `unreachable`、ゼロ除算、スタックオーバーフロー、メモリ範囲外アクセス<br>• ゲストが返した不正な `char`・判別子・UTF-8 / UTF-16、アラインされていないポインタ<br>• `cabi_realloc` や `post-return` 中の import 呼び出し（`may_leave` 違反）<br>• ホスト import の中から同じセッションを再入呼び出しした（`may_enter` 違反） | **Chicory**: `ComponentTrapException`（`IllegalStateException` のサブクラス）<br>**Wasmtime**: `IllegalStateException` | **使えなくなる（poison）**。以降の呼び出しも失敗するため、セッションを閉じて新しく開き直す必要がある |
| **ゲストの実行中にホスト import が例外を投げた** | **Chicory**: ホストが投げたその例外<br>**Wasmtime**: メッセージを含む `IllegalStateException` | **使えなくなる（poison）**。セッションを閉じて新しく開き直す必要がある |

## 2. トラップ発生時の対処

Component Model の仕様により、ゲストの実行中にトラップまたは未捕捉例外が発生すると、そのコンポーネントインスタンスの内部状態は不整合を起こしている可能性があるため **poison（使用不能）** になります。

一度 poison されたセッションに対して再度エクスポート関数や `dropResource` を呼ぶと、直ちに例外が投げられます。復帰するには、現在のセッションを閉じて新しいセッションを開いてください。

```kotlin
fun runSafely(input: Int): Int? =
    try {
        // 呼び出しごとに withSession で新しいセッションを開くか、
        // 長命な openSession を使っている場合は close() して openSession し直す
        withSession(CalculatorComponent) {
            divide(100, input)
        }
    } catch (e: ComponentTrapException) {
        // ゲスト側でトラップが発生した
        null
    }
```

## 3. Chicory と Wasmtime の細かな違い

通常の利用では両バックエンドとも同じ条件で失敗しますが、以下の 2 点だけ挙動が異なります（[ADR-0007](../adr/0007-canonical-abi-validation.md)）。

1. **例外の型**: Chicory バックエンドはトラップ時に `wasm2kt.runtime.ComponentTrapException`（`IllegalStateException` を継承）を投げます。Wasmtime ブリッジは C ABI 境界を文字列エラーで越えるため `IllegalStateException` を投げます。両バックエンド共通で捕捉する場合は `IllegalStateException` を catch してください
2. **戻り値の lift 中に検出された違反**: ゲスト関数本体は正常に戻ったものの、戻り値の読み出し（lift）で不正な判別子や不正な文字列が見つかった場合、Chicory は仕様どおりインスタンスを poison しますが、Wasmtime 48 は `may_enter` を戻した後に lift するため poison しません
