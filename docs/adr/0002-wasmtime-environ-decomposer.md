# 0002. コンポーネントの分解に wasmtime-environ を使い、分解ツールを wasm で同梱する

* ステータス: 承認
* 日付: 2026-10-30

> 補足（2026-11-01）: `wasmtime-environ` の版は [ADR-0010](0010-wasmtime-lts.md) で Wasmtime 48 LTS（`=48.0.8`、wit-component / wit-parser `=0.254.2`）に変わった。

## 背景

コンポーネントを core モジュールとインスタンス化手順に分解するには、ネストしたインスタンス化、アダプタ（shim / fixup）、
FACT が生成するアダプタモジュールなどの処理が必要になる。これを自作すると大きく、誤りやすい。

## 検討した選択肢

1. **分解処理を Kotlin で自作する** — 依存なし / 作業量が大きく、仕様追従が重い
2. **`wasmtime-environ` の Translator を使う（jco と同じ方式）** — 実績がある / 公開 API ではないため、バージョン固定が必要
3. 分解ツールの配布方法: **ネイティブバイナリ** / **wasm32-wasip1 にビルドして Chicory で実行**

## 決定

`wasmtime-environ`（`=49.0.0`、wit-component / wit-parser `=0.258.3`）を使う Rust 製の分解ツール `decomposer/` を作る。
出力は内部表現の serde 形式ではなく、明示的に定義した `plan.json`（バージョン付き）とする。あわせて `wit.json`
（`wasm-tools component wit --json` と同一形式）も出力する。分解ツールは wasm32-wasip1 にビルドして `decomposer/dist/` に置き、
Gradle の中で Chicory（WASI）上で実行する。

## 結果

* プラグインの利用者に Rust、wasm-tools、ネイティブバイナリは不要
* `plan.json` の形式を変えたら `PLAN_VERSION`（Rust 側）と `ComponentPlan.SUPPORTED_VERSION`（Kotlin 側）を同時に上げる
* `wasmtime-environ` を更新するときは `decomposer/build-wasm.sh` で wasm を再生成し、差分テストで確認する
* ビルド済みの wasm バイナリ（約 1.7MB）をリポジトリに含める。最新かどうかを CI で検証する必要がある（#12）。
  → 2026-10-31: ツールチェーンの固定とパスのリマップで再現可能ビルドにし、CI の decomposer ジョブで検証するようにした
