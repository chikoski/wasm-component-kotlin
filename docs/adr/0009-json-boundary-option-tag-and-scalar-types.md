# 0009. JSON 境界で option のタグを型に基づいて判定し、スカラーの JSON 型を検査する

* ステータス: 承認
* 日付: 2026-10-31

## 背景

ADR-0003 の JSON 境界では、option の `some` を次の 2 つの形で受け付けている。

- タグ付き: `{"option": "some", "value": ...}`
- 値そのまま

ADR-0006 で `option<T>` を `T?` にしてからは、生成コードは `some` を値そのまま送る。受け取る側（Chicory の `Lowerer`、rust-bridge の `json_to_val`）は、`"option"` キーを持つオブジェクトを常にタグと見なしていた。そのため、`%option` フィールドを持つ record が `some` の中身だと、エラーになるか、黙って `none` になっていた（#36）。

また Chicory は、スカラーを `JsonPrimitive.content` で読んでいた。そのため `string` に `null` を渡すと、文字列 `"null"` として格納されていた。数値や真偽値も文字列として通っていた。

## 検討した選択肢

1. **生成コードが常にタグ付きで送る** — 曖昧さが原理的になくなる / 生成コードの値の符号化は型を持たない（`FfmJsonCodec.toJsonElement`）ので、record のフィールド、引数、リストの要素などのすべての箇所で型に応じた包み直しが必要になり、変更が大きい
2. **受け取る側が WIT の型を見てタグを判定する** — 両バックエンドの判定関数を 1 つずつ直すだけで済む / 中身が `option` と `value` だけからなる record では、タグ付きの形を使えない

## 決定

選択肢 2 を採る。オブジェクトは、次の条件をすべて満たすときだけ option のタグと見なす。両バックエンドで同じ規則にする（Chicory の `isTaggedOption`、rust-bridge の `is_tagged_option`）。

- `"option"` の値が文字列の `"some"` か `"none"`
- キーがすべて `option` と `value` のどちらか
- `some` の中身が record なら、その record のフィールドがすべて `option` / `value` に収まる形ではない

最後の条件に当たる record（フィールドが `option` だけ、または `option` と `value` だけ）は、常に値そのまま（`some`）として扱う。生成コードは `some` を値そのまま送るので、生成コード経由の呼び出しは常に正しく解釈される。

スカラーは、JSON の型が WIT の型と合わない値を `IllegalArgumentException` で拒否する（rust-bridge の `as_str()` / `as_bool()` などと同じ）。

- `null` はどのスカラーの値でもない
- `string` / `char` / enum の名前 / flags の名前は JSON の文字列だけ
- 数値と bool は文字列を受け付けない。例外は u64 で、JSON の数値で正確に表せない値のために 10 進の文字列も受け付ける
- tuple は要素数も検査する

## 結果

- `%option` フィールドを持つ record が `option<T>` の中身でも、正しく受け渡せる（`types` フィクスチャの `echo-flagged` で、両バックエンドを確認）
- フィールドが `option` / `value` だけの record を中身に持つ option を、生成コードを使わずに手で JSON を組んで呼ぶ場合は、`some` を値そのままで送る必要がある
- Chicory でホストが不正な値を渡すと、黙って変換されずに失敗する。生成コードは型で正しい JSON を作るので、影響は手で JSON を組む呼び出しに限られる
