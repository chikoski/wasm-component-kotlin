# 0006. WIT → Kotlin の型マッピングを見直す

* ステータス: 承認（world の形は ADR-0012 で置き換え済み。型マッピングと world 名の規則はこの ADR のまま）
* 日付: 2026-10-31
* 関連: #17

## 背景

生成コードの公開 API は、後から変えるほど影響が大きくなる。async（#16）に入る前に、型マッピングをまとめて見直す。
前提として、値の受け渡しは JSON 境界を継続し（[ADR-0003](0003-json-value-boundary.md)）、async は Chicory のみで実装する
（[ADR-0005](0005-async-and-wasi03-chicory-only.md)）。生成コードは AI が書くことも前提にして判断した。

## 決定

| WIT | 以前 | 今後 |
| :--- | :--- | :--- |
| `option<T>` | `WitOption<T>` | `T?`。ただし `T` の Kotlin 型が null を取りうるとき（`option<option<U>>` など）は `WitOption<T>` |
| `result<T, E>` | `WitResult<T, E>` | 変更なし。`getOrNull()` / `errorOrNull()` / `getOrThrow()` / `getOrElse` / `map` / `mapError` / `fold` を追加 |
| `resource` | クラス（`AutoCloseable`） | 変更なし（value class にはしない） |
| `list<u8>` | `List<UByte>` | `ByteArray`（record や variant の `equals` / `hashCode` は内容で比較） |
| `char` | `Char` | `CodePoint`（`@JvmInline value class CodePoint(val value: Int)`） |
| world 名 | `<名前>World` | 名前がすでに `World` で終わる場合は付け足さない（`counter-world` → `CounterWorld`） |
| `async func` | — | `suspend fun`（#16 で実装） |
| `future<T>` | — | `Deferred<T>`（#16 で実装） |
| `stream<T>` | — | 1 回だけ collect できる `Flow<T>`（`stream<u8>` は `Flow<ByteArray>`）（#16 で実装） |

あわせて、生成する関数と型の KDoc に元の WIT の定義を書き出す。生成コードは `build/` 以下にあり、AI のコンテキストに
入らないことがあるため、IDE やコンパイラ経由で WIT との対応がわかるようにする。

### 理由

* **option**: Kotlin では値の有無を `T?` で表すのが標準で、AI が最初に書くコード（`?.` / `?:`）がそのまま正解になる。
  `WitOption` を使う理由だった「`option<option<T>>` が潰れる」問題は、中身が null を取りうるときだけ `WitOption` にすれば
  防げる。文脈で型が変わる不一致は、シグネチャと型チェックで気づける
* **result**: `kotlin.Result` はエラー型が `Throwable` に固定され、WIT の `E` の型情報が失われる。例外で投げる方式は、
  エラーを値として扱う WIT の意図とずれる。型付きのまま補助関数で使いやすくする
* **resource**: value class では「移動済み」「解放済み」の状態を持てない。オブジェクト 1 個の割り当ては JSON 境界の
  コストに比べて無視できる
* **`list<u8>`**: バイト列は最もよく使う型の 1 つで、`List<UByte>` は要素ごとにボックス化される
* **char**: WIT の `char` は Unicode スカラー値（U+10FFFF まで）で、Kotlin の `Char`（UTF-16 の 1 単位）では絵文字などを
  表せない（以前は受け取った時点で例外になっていた）
* **world 名**: `counter-world` から `CounterWorldWorld` が生成されるような重複をなくす

## 結果

* 生成コードの公開 API の破壊的変更（option、`list<u8>`、char、world 名）。examples、サンプル、テストを更新した
* JSON の形式は変えていない。`T?` の `null` は `none`、値はそのまま `some` として送る（Wasmtime ブリッジも Chicory も
  両方の形を受け付ける）。そのため Wasmtime ブリッジの変更は不要
* 既知の制約: `option<T>` の `T` が `option` という名前のフィールドを持つ record のとき、値をそのまま送ると
  タグ付きの option と区別できない（WIT では `option` はキーワードで `%option` と書く必要があり、まれ）
* `TupleN` に `ByteArray` を入れた場合の `equals` は参照比較のまま
