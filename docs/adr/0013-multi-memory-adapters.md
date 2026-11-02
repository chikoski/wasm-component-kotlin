# 0013. 合成コンポーネントのアダプターを multi-memory で検証し、async の組み込み関数を使わない形で生成する

* ステータス: 承認
* 日付: 2026-11-02

## 背景

子コンポーネントがそれぞれ線形メモリを持つ合成コンポーネントを分解すると、decomposer が `multiple memories` で panic していた（#47）。

`wasmtime-environ` は、子コンポーネントをつなぐ fused adapter を core モジュールとして生成し、呼び出し側が渡した `Validator` で検証する。
アダプターは呼び出し元と呼び出し先の 2 つのメモリを import するため、`MULTI_MEMORY` が必要になる。decomposer はこれを有効にしていなかった。

`MULTI_MEMORY` を有効にすると、アダプターが次のトランポリンを使うようになる。

* `trap`（エラーコードを 1 つ取る）
* 文字列の transcoder（12 種類。メモリ間のコピー、エンコーディングの変換、部分的な変換）
* `Tunables::concurrency_support` が有効なとき、`context.get` / `context.set` と task-may-block の組み込み関数

Chicory バックエンドは、すべての呼び出しを同期で実行する（async は #16）。

## 検討した選択肢

1. `MULTI_MEMORY` だけを有効にし、生成されるトランポリンをすべて Chicory で実装する — 長所: Wasmtime のアダプターと同じ形。短所: async の組み込み関数は、同期の実行では意味がない。#16 の前に中途半端な実装を持つことになる
2. `MULTI_MEMORY` を有効にし、`concurrency_support = false` でアダプターを生成する。`trap` と transcoder だけを実装する — 長所: 同期の合成に必要なものだけで済む。短所: #16 で async を入れるときに、この設定を見直す必要がある
3. アダプターを decomposer で生成せず、ランタイムで Canonical ABI を直接実装する — 長所: アダプターに依存しない。短所: 仕様の実装が 2 つになる。ADR-0002 の方針に反する

## 決定

選択肢 2 を選ぶ。

* decomposer の `WasmFeatures` に `MULTI_MEMORY` を加え、`Tunables::concurrency_support = false` にする（#16 で見直す）
* plan を v4 にする。トランポリンに次を加える
  * `{"kind": "trap", "messages": [...]}`: コードから `wasmtime_environ::Trap` の表示文字列への表（Chicory はトラップの文言を Wasmtime と揃えられる）
  * `{"kind": "transcode", "op": "<wasmtime の desc>", "from": <memory>, "to": <memory>}`（32 ビットのメモリのみ）
* Chicory の `Transcoder` は、Wasmtime 48 の libcall（`vm/component/libcalls.rs`）と同じ引数・戻り値・部分的な変換を実装する。不正なエンコーディングはゲスト起因の違反として `ComponentTrapException` にする（ADR-0007）

## 結果

* 文字列（utf8 / utf16 / latin1+utf16 のすべての組み合わせ）と list を受け渡す合成コンポーネントが、Chicory のインタプリタと AOT の両方で動く。`composed-str` フィクスチャで、Wasmtime との差分テストを行う
* 子コンポーネントの間で resource を受け渡す合成は、引き続き未対応（`ResourceTransferOwn` などのトランポリン。PlanCheck がビルド時に報告する。#39）
* decomposer が panic したときは、tooling の `Decomposer` が stderr を含むエラーにする（wasm2kt の不具合として報告を促す）
* #16 で async を実装するときは、`concurrency_support` を有効にし、`context.get` / `context.set` などを実装する
