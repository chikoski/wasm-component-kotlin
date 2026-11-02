# 0012. world を package のトップレベルに生成し、export した interface はマーカーを context に取る

* ステータス: 承認
* 日付: 2026-11-02
* 関連: ADR-0006（world の形を置き換える）、ADR-0011

## 背景

ADR-0006 までの生成コードは、world を `object <Name>World` にし、その中に `Session`、`withSession`、world の型を入れ子にしていました。
world の関数は `fun <Name>World.add(...)` という拡張関数で、呼び出しは `CalculatorWorld.add(1, 2)` でした。

ADR-0011 で interface の型をそれぞれの package に出したことで、world オブジェクトに残るのは world 自身の API だけになりました。
一方で、次の問題が残っていました。

* 呼び出しのたびに `CalculatorWorld.` を書く必要があり、Kotlin のコードとして冗長
* export した interface の関数（`MathApi.scale(7)`）とコンポーネント定義の resource は、どの world の `Session` でも受け付けていた（`WitSession`）。
  world がその interface を export しているかを、コンパイラが確かめられない

## 検討した選択肢

world を置く場所:

1. **生成時に指定した package（`-p`）の直下** — 利用側は `import com.example.calc.*` で済む / 1 回の生成で world は 1 つになる。複数の world は `--world` で選び、別々の package に生成する
2. **`<base>.<ns>.<pkg>.<world>`（interface と同じ規則）** — 複数の world を一度に生成できる / `.wasm` から生成すると world は常に `root:component/root` なので、`com.example.rt.root.component.root` のような package になる
3. **world オブジェクトを残す** — 変更がない / 冗長さが残る

export した interface の呼び出し方:

1. **interface ごとのマーカー `<Iface>Exports` を context に取るトップレベルの関数** — world の `Session` は自分が export する interface のマーカーだけを実装するので、export していない world からはコンパイルエラーになる。呼び出しは `scale(7)` / 2 つの interface に同じ名前の関数があると、利用側で別名が要る
2. **`session.mathApi.scale(7)`（`Session` に interface ごとのプロパティ）** — 名前が重なっても読みやすい / プロパティ名の規則が要り、トップレベル化の利点が小さい

## 決定

world は選択肢 1、export の呼び出しも選択肢 1 を採ります。

**world**

* world の API を、指定した package のトップレベルに生成する: `Session`、`openSession` / `withSession` / `openPrecompiledSession` / `withPrecompiledSession`、
  `imports(...)`、world 直下の import の `interface Imports` と `fun Linker.add(impl: Imports)`、world の型、world の export 関数（`context(session: Session) fun add(...)`）
* ファイル名は従来どおり `<Name>World.kt`（ADR-0006 の名前の規則を使う）
* 1 回の生成で world は 1 つ。WIT に複数の world があり `--world` がなければ、生成時にエラーにする（`--world` で選ぶよう案内する）。指定した world がなければエラーにする
* world の型や export 関数の名前が、生成する API の名前（`Session`、`Imports`、`WitSession`、`WitOption`、`TupleN`、`imports`、`withSession` など）と重なるときは、生成時にエラーにする

**export した interface**

* export した interface の package に、マーカー `interface <Iface>Exports : WitExports` を生成する（`<Iface>` は ADR-0011 の interface 名の規則。`Interface` 接尾辞も同じ）
* export 関数は、その package のトップレベルの `context(exports: <Iface>Exports) fun f(...)` にする（ADR-0011 の `object <Iface>` はなくす）
* コンポーネント定義の resource の constructor と static も、その interface のマーカーを context に取る。resource のオブジェクトは、作ったセッションを `WitSession` として持つ
* world の `Session` は、world が export する interface のマーカーをすべて実装する
* マーカーからセッションへは、`WitSupport.kt` の `internal val WitExports.witSession` で戻る（マーカーを実装するのは生成した `Session` だけ）

## 結果

* 生成コードの公開 API が変わる（ADR-0006 の world の形を置き換える）。`CalculatorWorld.add(1, 2)` は `add(1, 2)`、`CalculatorWorld.withSession` は `withSession`、
  `MathApi.scale(7)` は `scale(7)` になる。成果物は未公開なので、影響はリポジトリ内（テスト、examples、`samples/android`、ドキュメント）だけ
* export していない interface の関数やその resource を、別の world のセッションから呼ぶとコンパイルエラーになる（ADR-0011 で残った制約を解消）
* 2 つのコンポーネントを 1 つのファイルで使うときは、`withSession` などが重なるので、`import ... as` で別名を付けるか package 名で修飾する
* 同じ WIT の複数の world を使うときは、world ごとに `--world` と別の package を指定して生成する。interface は world ごとの package の下に別々に生成される
  （world をまたいで型を共有するには、#13 の `with` に当たる仕組みが要る）
