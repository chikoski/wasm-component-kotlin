# 機能の対応表

WIT・Canonical ABI・Component Model の各機能について、バックエンド別の対応状況を示します。

## 1. WIT の型と関数

| 分類 | 機能 | AOT（既定） / CHICORY | Wasmtime（汎用 / 型付き） | 備考 |
| :--- | :--- | :--- | :--- | :--- |
| プリミティブ | `bool`, `s8`〜`s64`, `u8`〜`u64`, `f32`, `f64`, `char`, `string` | 対応 | 対応 | 文字列は UTF-8 / UTF-16 / Latin1+UTF-16 に対応。NaN は正規化（[ADR-0007](../adr/0007-canonical-abi-validation.md)） |
| 複合型 | `record`, `variant`, `enum`, `flags`（32 個以下）, `tuple`, `list<T>`, `option<T>`, `result<T, E>` | 対応 | 対応 | ネストした `option<option<T>>` も対応 |
| フラグ | 33 個以上の `flags` | — | — | `wasm-tools` 1.258 自体が仕様により拒否 |
| マップ | `map<K, V>` | 未対応 | 未対応 | 対応可否を [#71](https://github.com/chikoski/wasm-component-kotlin/issues/71) で検討中 |
| 関数・interface | トップレベル関数、import / export interface、同一短名 interface（`example:http/types` と `example:fs/types`） | 対応 | 対応 | 完全修飾名で識別（[ADR-0011](../adr/0011-qualified-interface-keys.md)） |
| 関数・interface | 同一パッケージの複数バージョン（`@0.2.0` と `@0.3.0`）を 1 回の生成で混在 | 未対応 | 未対応 | パッケージ名にバージョンを含めないためエラーになる |
| ABI | 16 個を超える flat 引数 / 2 個以上の flat 戻り値（メモリ経由）、`cabi_realloc`、`post-return` | 対応 | 対応 | |

## 2. リソース (`resource`)

| 機能 | AOT（既定） / CHICORY | Wasmtime（汎用 / 型付き） | 備考 |
| :--- | :--- | :--- | :--- |
| コンポーネント定義 `resource`（export された resource の `constructor` / `method` / `static` / `drop`、`own` / `borrow`） | 対応 | 対応 | 返された `own` ハンドルはコンポーネントのテーブルから除去される（[#31](https://github.com/chikoski/wasm-component-kotlin/issues/31)） |
| ホスト定義 `resource`（import された resource の `own` / `borrow`、drop 時の `close()`） | 対応 | 対応 | [ADR-0008](../adr/0008-host-defined-resources.md)。Wasmtime ではホストの `borrow` を一時的な所有ハンドルで代用 |
| コンポーネント定義 `resource` を、ゲストからホスト import の引数に `own` / `borrow` で渡す経路 | 未対応 | 対応 | |

## 3. 合成コンポーネント・Async・WASI・制限

| 機能 | AOT（既定） / CHICORY | Wasmtime | 備考 |
| :--- | :--- | :--- | :--- |
| メモリを持つ子コンポーネント同士の合成（文字列・`list` の transcode、`trap`） | 対応 | 対応 | 12 種類の文字列 transcoder に対応（[#47](https://github.com/chikoski/wasm-component-kotlin/issues/47)、[ADR-0013](../adr/0013-multi-memory-adapters.md)） |
| 合成した子コンポーネント間での `resource` の受け渡し（`resource.transfer-own` 等） | 対応 | 対応 | fused adapter の `resource-transfer-own`、`resource-transfer-borrow`、`enter-sync-call`、`exit-sync-call` および共有テーブルの適合に対応。[#16](https://github.com/chikoski/wasm-component-kotlin/issues/16) と [#70](https://github.com/chikoski/wasm-component-kotlin/issues/70) で実装 |
| Component Model Async（`async func`, `future<T>`, `stream<T>`, `error-context`、callback 形式と stackful 形式、協調スレッドの組み込み関数） | 対応 | 対応予定なし（[ADR-0005](../adr/0005-async-and-wasi03-chicory-only.md)） | [ADR-0018](../adr/0018-async-abi-on-chicory.md)、[ADR-0019](../adr/0019-suspending-async-boundary.md)、[ADR-0023](../adr/0023-custom-wasm-compiler-and-static-jvm-backend.md) Phase 3 により Chicory と AOT が `:runtime-canonical` の `AsyncAbi` を共有して動作します。async の export と import は `suspend fun` となり、並行呼び出しと取り消しに対応します。`stream` と `future` は export やホスト import の引数と結果に加えて、レコードやタプルの入れ子型でも要素に resource・stream・future 自体を含まない範囲で扱えます |
| WASI 0.3 ホスト実装 | 対応 | 対応予定なし。[ADR-0005](../adr/0005-async-and-wasi03-chicory-only.md) | `runtime-wasi` が `wasi:random@0.3.0`、`wasi:clocks@0.3.0`、`wasi:cli@0.3.0`、`wasi:filesystem@0.3.0` を提供し、Chicory と AOT の双方で動作します。`wasi:http` は [#117](https://github.com/chikoski/wasm-component-kotlin/issues/117)、`wasi:sockets` は [#118](https://github.com/chikoski/wasm-component-kotlin/issues/118) として P2 に分離しており、設計の詳細は [ADR-0022](../adr/0022-wasi03-scope-and-host-configuration.md) を参照してください |
| 実行時間・メモリの上限と強制中断 | 未対応 | 未対応 | [#77](https://github.com/chikoski/wasm-component-kotlin/issues/77)。信頼できるコンポーネントのみを実行する前提 |

## 4. 公式テストスイート

Component Model の公式テストスイートである `values/`、`resources/`、`async/` を `conformance/component-model/` に取り込み、Chicory 側は `ConformanceTest` で、AOT 側は `AotConformanceTest` で実行しています（[ADR-0017](../adr/0017-component-model-conformance-suite.md)、[#30](https://github.com/chikoski/wasm-component-kotlin/issues/30)、[#188](https://github.com/chikoski/wasm-component-kotlin/issues/188)）。

`ConformanceTest` と `AotConformanceTest` の双方で、`values/` と `resources/` は 191 コマンド中 190 コマンド、`async/` は 514 コマンド中 482 コマンドが通過しています。仕様の `nyi.txt` に記載されたファイルは 1 件として数えています。残りの項目は `wasmtime-environ` 48 が同一インスタンスや祖先インスタンスへの再入をトラップにする制約に該当し、`conformance/skip.txt` と `conformance/skip-aot.txt` に同じ基準で記録しています。未対応機能としては `map` 型を扱う [#71](https://github.com/chikoski/wasm-component-kotlin/issues/71) が残っています。
