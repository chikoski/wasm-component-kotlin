# 0003. バックエンド間の値の受け渡しに JSON 境界を共有する（フェーズ A）

* ステータス: 置き換え済み（→ ADR-0015）
* 日付: 2026-10-30

## 背景

既存の生成コードは `WasmtimeSession` を通じて、Wasmtime ブリッジと JSON 形式（`FfmJsonCodec`）で値をやり取りしている。
Chicory バックエンドを追加するにあたり、生成コードを作り直すか、既存の境界に合わせるかを決める必要があった。

## 検討した選択肢

1. **既存の JSON 境界に合わせる** — 生成 DSL・テスト・差分検証をそのまま流用できる / 純 JVM では JSON 化が余分なコストになる
2. **線形メモリを直接読み書きする静的な lift / lower を生成する** — 速い / ジェネレータの大改修が必要で、検証の基準がない

## 決定

フェーズ A として選択肢 1 を採る。`runtime-api` に `ComponentBackend`（`invoke(name, JsonArray): JsonElement`、`dropResource`）を定義し、
生成コードの `BackendWasmtimeSession` がこれに橋渡しする。値の形式、関数名の解決規則、ホスト import のキーは Wasmtime ブリッジに揃える。

## 結果

* Wasmtime を基準にした差分テスト（`ChicoryDifferentialTest`）で正しさを担保できる
* 呼び出しごとに JSON 値を組み立てるオーバーヘッドが残る。計測（#19）の結果を見て、フェーズ B（選択肢 2、#15）に進むか判断する
