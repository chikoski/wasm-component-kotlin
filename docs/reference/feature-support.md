# 機能の対応表

WIT・Canonical ABI・Component Model の各機能について、バックエンド別の対応状況を示します。

## 1. WIT の型と関数

| 分類 | 機能 | Chicory | Wasmtime（汎用 / 型付き） | 備考 |
| :--- | :--- | :--- | :--- | :--- |
| プリミティブ | `bool`, `s8`〜`s64`, `u8`〜`u64`, `f32`, `f64`, `char`, `string` | 対応 | 対応 | 文字列は UTF-8 / UTF-16 / Latin1+UTF-16 に対応。NaN は正規化（[ADR-0007](../adr/0007-canonical-abi-validation.md)） |
| 複合型 | `record`, `variant`, `enum`, `flags`（32 個以下）, `tuple`, `list<T>`, `option<T>`, `result<T, E>` | 対応 | 対応 | ネストした `option<option<T>>` も対応 |
| フラグ | 33 個以上の `flags` | — | — | `wasm-tools` 1.258 自体が仕様により拒否 |
| マップ | `map<K, V>` | 未対応 | 未対応 | 対応可否を [#71](https://github.com/chikoski/wasm-component-kotlin/issues/71) で検討中 |
| 関数・interface | トップレベル関数、import / export interface、同一短名 interface（`example:http/types` と `example:fs/types`） | 対応 | 対応 | 完全修飾名で識別（[ADR-0011](../adr/0011-qualified-interface-keys.md)） |
| 関数・interface | 同一パッケージの複数バージョン（`@0.2.0` と `@0.3.0`）を 1 回の生成で混在 | 未対応 | 未対応 | パッケージ名にバージョンを含めないためエラーになる |
| ABI | 16 個を超える flat 引数 / 2 個以上の flat 戻り値（メモリ経由）、`cabi_realloc`、`post-return` | 対応 | 対応 | |

## 2. リソース (`resource`)

| 機能 | Chicory | Wasmtime（汎用 / 型付き） | 備考 |
| :--- | :--- | :--- | :--- |
| コンポーネント定義 `resource`（export された resource の `constructor` / `method` / `static` / `drop`、`own` / `borrow`） | 対応 | 対応 | 返された `own` ハンドルはコンポーネントのテーブルから除去される（[#31](https://github.com/chikoski/wasm-component-kotlin/issues/31)） |
| ホスト定義 `resource`（import された resource の `own` / `borrow`、drop 時の `close()`） | 対応 | 対応 | [ADR-0008](../adr/0008-host-defined-resources.md)。Wasmtime ではホストの `borrow` を一時的な所有ハンドルで代用 |
| コンポーネント定義 `resource` を、ゲストからホスト import の引数に `own` / `borrow` で渡す経路 | 未対応 | 対応 | |

## 3. 合成コンポーネント・Async・WASI・制限

| 機能 | Chicory | Wasmtime | 備考 |
| :--- | :--- | :--- | :--- |
| メモリを持つ子コンポーネント同士の合成（文字列・`list` の transcode、`trap`） | 対応 | 対応 | 12 種類の文字列 transcoder に対応（[#47](https://github.com/chikoski/wasm-component-kotlin/issues/47)、[ADR-0013](../adr/0013-multi-memory-adapters.md)） |
| 合成した子コンポーネント間での `resource` の受け渡し（`resource.transfer-own` 等） | 一部対応 | 対応 | fused adapter の `resource-transfer-own` / `-borrow` と `enter-sync-call` / `exit-sync-call` に対応（[#16](https://github.com/chikoski/wasm-component-kotlin/issues/16)）。公式テストの 2 コマンドは、まだ通らない（[#70](https://github.com/chikoski/wasm-component-kotlin/issues/70)） |
| Component Model Async（`async func`, `future<T>`, `stream<T>`, `error-context`、callback 形式と stackful 形式、協調スレッドの組み込み関数） | 対応 | 対応予定なし（[ADR-0005](../adr/0005-async-and-wasi03-chicory-only.md)） | [ADR-0018](../adr/0018-async-abi-on-chicory.md)、[ADR-0019](../adr/0019-suspending-async-boundary.md)。async の export と import は `suspend fun`（並行の呼び出しと取り消しに対応）。stream / future は export・host import の引数や結果、およびレコード・タプル等の入れ子型に対応（要素に resource / stream / future 自体を含まないもの） |
| WASI 0.3 ホスト実装 | 一部対応（[#13](https://github.com/chikoski/wasm-component-kotlin/issues/13)、[#98](https://github.com/chikoski/wasm-component-kotlin/issues/98)） | 対応予定なし（[ADR-0005](../adr/0005-async-and-wasi03-chicory-only.md)） | `runtime-wasi` が `wasi:random@0.3.0`、`wasi:clocks@0.3.0`、`wasi:cli@0.3.0` を提供（`filesystem` / `sockets` / `http` は [#99](https://github.com/chikoski/wasm-component-kotlin/issues/99)〜[#101](https://github.com/chikoski/wasm-component-kotlin/issues/101) で追跡） |
| 実行時間・メモリの上限と強制中断 | 未対応 | 未対応 | [#77](https://github.com/chikoski/wasm-component-kotlin/issues/77)。信頼できるコンポーネントのみを実行する前提 |

## 4. 公式テストスイート（Conformance）

Component Model の公式リファレンステストスイート（`values/`、`resources/`、`async/`）を `conformance/component-model/` に取り込み、`ConformanceTest` で Chicory バックエンドに対して実行しています（[ADR-0017](../adr/0017-component-model-conformance-suite.md)、[#30](https://github.com/chikoski/wasm-component-kotlin/issues/30)）。

* `values/` と `resources/` は 191 コマンド中 **188**、`async/` は 514 コマンド中 **480** が成功しています（仕様の `nyi.txt` に載ったファイルは、1 つと数えています）
* 残りは、Wasmtime 48 LTS の制約（同じインスタンスや祖先のインスタンスへの再入がアダプターでトラップになる、wasmparser 0.254 が `task.return` の `borrow` を拒否しない）と、上記の未対応機能（[#70](https://github.com/chikoski/wasm-component-kotlin/issues/70) 合成コンポーネント間の resource、[#71](https://github.com/chikoski/wasm-component-kotlin/issues/71) `map`）に該当し、`conformance/skip.txt` に理由付きで記録しています
