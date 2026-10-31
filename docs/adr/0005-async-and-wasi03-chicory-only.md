# 0005. async と WASI 0.3 は Chicory バックエンドだけで進める

* ステータス: 承認
* 日付: 2026-10-31

## 背景

WASI 0.3 が 2026-06 に承認された。`wasi:io`（pollable / input-stream / output-stream）は独立したインターフェースではなくなり、
Canonical ABI の `stream<T>` / `future<T>` / `async` に吸収された（[WASI 0.3](https://bytecodealliance.org/articles/WASI-0.3)）。

このプロジェクトは実験的に進めたい。一方で、新しい機能を 2 つのバックエンドの両方に入れると作業量が大きく増える。
Wasmtime バックエンドで async に対応するには、`rust-bridge` の Wasmtime を 29 から 46 以降に上げ、FFI 越しに
async を扱う仕組み（ブリッジ内部の `block_on` など）が必要になる。

## 検討した選択肢

1. **両方のバックエンドに新機能を入れる** — 機能が揃う / 作業量が約 2 倍。Wasmtime 側は FFI 越しの async が難しい
2. **async と WASI 0.3 は Chicory だけで進め、Wasmtime は維持する** — 実験の速度を保てる / バックエンド間で機能差が出る
3. **Wasmtime バックエンドをやめる** — 単純になる / 差分テストの基準（正しさの参照実装）を失う

## 決定

選択肢 2。

* Wasmtime バックエンドは維持する。同期の機能について、差分テストの基準（参照実装）として使い続ける
* async（Component Model Async）と WASI 0.3 のホスト実装は Chicory バックエンドだけで実装する
* WASI 0.2 の pollable / `wasi:io/poll` は実装しない
* 値の受け渡しは JSON 境界を継続する（[ADR-0003](0003-json-value-boundary.md) を維持）

## 結果

* async の正しさは、Wasmtime との差分テストでは確かめられない。仕様（Canonical ABI の定義）に基づくテストと、
  WASI 0.3 対応の実際のコンポーネントを使った E2E テストで担保する必要がある
* 生成コードは、async な関数を含む world をバックエンドに応じて扱い分ける必要がある
  （Wasmtime セッションでは未対応として明確に失敗させる）
* 作業順: #17 型マッピングの方針 → #18 Canonical ABI の厳密化 → #14 ホスト定義 resource → #16 async → #13 WASI 0.3
