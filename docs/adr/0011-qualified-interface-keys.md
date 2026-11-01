# 0011. interface を完全修飾名で識別し、Kotlin では WIT の構造を package に写す

* ステータス: 承認
* 日付: 2026-11-02

## 背景

ADR-0003 は、関数名とホスト import のキーを Wasmtime ブリッジに揃えると決めました。その形式は `<短い interface 名>#<関数名>` で、package とバージョンを落としています（`wasi:http/types@0.2.0` → `types`）。生成コードも同じ短い名前から、world オブジェクトの入れ子の型（`Types`）、`Imports` のプロパティ（`types`）、ディスパッチの分岐を作っています。

WIT には名前の空間が 2 つあります（wasm-tools 1.258.0 で確認）。

| 空間 | 例 | 一意性 |
| :--- | :--- | :--- |
| world 内の素の名前 | インライン interface、world 直下の関数、`use` した型 | WIT が保証する。重複はエラーで、`use` は `as` で別名にする |
| 完全修飾名 | `wasi:http/types@0.2.0` | 完全修飾名としては一意。ただし短い名前（`types`）は重なりうる |

`wasi:filesystem/types` と `wasi:http/types` を両方 import する world は WIT として正しく、コンポーネントのバイナリでも完全修飾名で区別されています。ところが生成器は短い名前で識別するので、次の問題が起きます。#13（WASI 0.3）で必ず当たる組み合わせです。

* 実行時: 両バックエンドと `rust-bridge` が、最初に見つかったものを黙って選ぶ（別の interface の実装に届く）
* 生成コード: 型・プロパティ・分岐が衝突してコンパイルできない
* 型名: `allocateWorldTypeNames` は同名の型を短い interface 名で修飾するので、両方の `error-code` が `TypesErrorCode` になり、型インデックス付きの不安定な名前（`TypesErrorCode_123`）に落ちる

また、#13 で WASI のホスト実装をビルド済みライブラリとして配るには、別々に生成したバインディングの名前が一致する必要があります（「今後」を参照）。名前は決定的でなければなりません。

wit-bindgen（2026-10-30 の main）と Wasmtime の `bindgen!`（2026-10-31 の main）は、どれも短い名前を識別子に使っていません。

* 名前空間を入れ子にできる言語（Rust、C#）は、`wasi::http::types` のように namespace / package / interface を写して区別する。export は `exports::` の下に置く
* 入れ子にできない場面（C の関数名、Go の package 名）は、常に完全修飾名を連結する（`wasi_http_types_...`）
* ホスト側の Wasmtime は、interface ごとの `Host` trait を `add_to_linker` で 1 つずつ登録し、集約する型を持たない

## 検討した選択肢

内部のキー:

1. **完全修飾名にする**（`wasi:http/types@0.2.0#[method]fields.get`） — 一意で、WIT の名前そのもの / 生成コード、Chicory、ブリッジ、手書きのディスパッチャーのキーがすべて変わる
2. **短い名前を保ち、衝突したものだけ修飾する** — 既存のキーが変わらない / import を足しただけで既存のキーが変わる。規則が複雑
3. **生成コードの名前だけ変える** — 実行時の衝突が残るので解決にならない

Kotlin の型名:

1. **衝突するときだけ package で修飾する**（`Types` / `HttpTypes`） — 普段は短い / 依存を足すと既存の名前が変わる
2. **常に package 名で修飾する**（`HttpTypes`） — 決定的 / world オブジェクトに全部入る構成のままで、型を world 間で共有できない
3. **WIT の namespace / package / interface を Kotlin の package に写し、interface ごとにファイルを分ける** — 修飾の規則が要らず決定的。world 間で型を共有でき、ビルド済みライブラリの型を参照する仕組みにつなげられる。wit-bindgen と同じ考え方 / 型が world オブジェクトの外に出るので、公開 API が変わる

package のセグメントの変換:

1. **スネークケース**（`outgoing-handler` → `outgoing_handler`） — WIT の識別子に `_` は現れないので一対一。JLS の命名規約（ハイフンはアンダースコアに）と wit-bindgen の Rust 版に一致。大文字小文字を区別しないファイルシステムでも衝突しない / Kotlin の規約（アンダースコアなし）から外れる
2. **キャメルケース**（`outgoingHandler`） — Kotlin の規約で許容 / `fooBar/` と `foobar/` が macOS や Windows で衝突する
3. **ハイフンを `.` に**（`outgoing.handler`） — Kotlin の規約に沿う / `foo:bar-baz/x` と `foo-bar:baz/x` が同じ package になる。`wasi:sockets/tcp-create-socket` が `tcp` の下位に見えるなど、存在しない階層ができる

ホスト import の渡し方:

1. **`Imports` interface に interface ごとのプロパティを並べる**（現状） — 実装し忘れはコンパイルエラー / プロパティ名を完全修飾名から作る規則が要り、利用者が `override val wasiHttpTypes` と書く名前が公開 API になる。ライブラリの実装を渡すにも、world の `Imports` にライブラリの型を埋め込む必要がある
2. **interface ごとに `Linker` に登録する**（Wasmtime の `add_to_linker` と同じ） — 名前の規則が要らない。world をまたいで再利用でき、ライブラリは interface 単位で提供できる / 実装し忘れが実行時まで分からない
3. **2 + world ごとの型付きのファクトリー関数** — 登録の仕組みは 2 のまま、world が import する interface を必須の引数にして、実装し忘れをコンパイル時に検出する / 引数名の規則は要る（ただし名前付き引数でしか使わない）

## 決定

キーは選択肢 1、型名は選択肢 3、セグメントの変換は選択肢 1、ホスト import の渡し方は選択肢 3 を採ります。細部は wit-bindgen（Rust 版）の振る舞いに揃えます。

**内部のキー**（ディスパッチキー、`invoke` の関数名、`[resource-drop]` のキー）

* `<namespace>:<package>/<interface>@<version>#<関数名>` にする（例: `wasi:http/types@0.2.0#[resource-drop]fields`）
* world 直下の関数とインライン interface（`import foo: interface { ... }`）は、world 内の素の名前を使う（`fn`、`foo#fn`）。WIT が一意性を保証している

**`invoke` の名前の解決**（両バックエンド）

* 完全修飾名を正とする
* 短い名前と修飾なしの名前は、一意に決まるときだけ受け付ける（手書きの呼び出しとの互換のため）
* 曖昧なときは `IllegalArgumentException` にし、黙って最初の候補を選ばない

**Kotlin の package とファイル**

* import した interface `ns:pkg/iface` は、package `<base>.<ns>.<pkg>.<iface>` に置く。ファイルは `<Iface>.kt`（`<base>` は利用者が指定するパッケージ）
* export した interface は、先頭に `exports` を付けた `<base>.exports.<ns>.<pkg>.<iface>` に置く。同じ interface を import と export の両方で使っても、Component Model では別の実体なので別に生成する
* インライン interface は `<base>.<name>` に置く
* interface 内の型（record、variant、resource など）とホストが実装する Kotlin interface は、その package のトップレベルに PascalCase で置き、修飾しない（`<base>.wasi.http.types.ErrorCode`）。型名の衝突は package で解消するので、world 側で別名を付ける必要はない
* 共通の型（`WitOption`、`WitResult`、`CodePoint` など）は従来どおり `<base>/WitSupport.kt`

**セグメントの変換**

* すべて小文字にし、`-` を `_` に置き換える
* Kotlin のハードキーワード（`is`、`in`、`as`、`fun`、`object`、`interface` など）になるセグメントは、末尾に `_` を付ける（`is_`）。バッククォートは Java から参照できないので使わない。`_` は WIT の名前に現れないので、ほかの名前と衝突しない

**バージョン**

* package 名にバージョンは含めない
* 同じ `ns:pkg` の異なるバージョンが 1 つの world に同居したら、生成時にエラーにする。wit-bindgen は同居したものにだけ版を付けるので、将来同居を許す場合も、今エラーになる組み合わせにだけ版を付ければ既存の名前は変わらない

**ホスト import の渡し方: `Linker` と型付きのファクトリー関数**

下の層（interface 単位）:

* `runtime-api` に `Linker` を追加する。完全修飾名のキーからハンドラーへの表で、`WitHostImportDispatcher` を実装する。バックエンドは従来どおり `dispatchHostImport` を呼ぶだけで、変更は要らない
* 生成器は、import した interface の package に、実装をその interface のキーで登録する拡張関数を生成する

  ```kotlin
  package com.example.app.wasi.http.types

  interface Types { fun add(p0: Int): Int }

  fun Linker.add(impl: Types) {
      define("wasi:http/types@0.2.0#add") { args -> /* デコードして impl.add(...) */ }
      // ホスト resource があれば "[resource-drop]..." も同じキーの形式で登録する
  }
  ```

* 関数名はどの interface でも `add` で、引数の型でオーバーロードとして解決する。1 つのオブジェクトが複数の interface を実装して曖昧になるときは、import の別名で区別する
* world 直下の import 関数は、world の package に、それだけを持つ interface と `Linker.add` を生成する

上の層（world 単位）:

* world が import する interface ごとに、`() -> 実装` を必須の引数に取る関数を生成し、`Linker` を組み立てるファクトリーを返す

  ```kotlin
  fun imports(
      wasiHttpTypes: () -> com.example.app.wasi.http.types.Types,
      wasiFilesystemTypes: () -> com.example.app.wasi.filesystem.types.Types,
  ): ImportsFactory = ImportsFactory {
      Linker().apply {
          add(wasiHttpTypes())
          add(wasiFilesystemTypes())
      }
  }

  // 利用側
  withSession(AComponent, imports(
      wasiHttpTypes = { MyHttpTypes() },
      wasiFilesystemTypes = { MyFilesystemTypes() },
  )) { ... }
  ```

* ファクトリーはセッションを開くたびに呼ぶ。ホストの実装が状態を持っても、セッション間で混ざらない（`HostResourceTable` がセッションごとなのと揃う）。共有したいときは同じオブジェクトを返せばよい
* 引数名は、完全修飾名から作る: namespace + package + interface のキャメルケース（`wasiHttpTypes`）。インライン interface は素の名前（`foo`）。両者が一致したら（インライン interface の名前が `wasi-http-types` など）生成時にエラーにする
* 引数の順序は WIT の import の順にする。WIT の変更で順序が変わりうるので、ドキュメントでは名前付き引数を勧める
* `withSession` / `openSession` は `Linker` を直接受け取る形も持つ。その場合は、セッションを開くときに world が import するキーがすべて登録されているかを確かめ、足りなければ不足している interface の一覧を付けて `IllegalArgumentException` にする（コンポーネントが import を呼んだときに初めて `Unknown host import` で落ちる、ということを避ける）

## 結果

* ADR-0003 のキーの形式（短い interface 名）と、ADR-0008 で `Imports` に置いたホスト resource の constructor / static の置き場所を、この ADR で置き換える。ホスト resource の constructor / static は、その resource を持つ interface の Kotlin interface（world 直下の resource なら world 直下の import の interface）に移る。rep の表と `[resource-drop]` の仕組みは ADR-0008 のまま
* 生成コードの公開 API が変わる。interface の型は world オブジェクトの入れ子からトップレベルの package に移り、`Imports` interface は `imports(...)` と `Linker` に置き換わる（ADR-0006 の DSL の形に影響）。成果物は未公開なので、影響はリポジトリ内（テスト、`samples/android`、ドキュメント）だけ
* ディスパッチキーが変わる。手書きで `WitHostImportDispatcher` を実装しているコードと、`ComponentBackend.invoke` を直接呼ぶコードは、キーの更新が必要
* 同じ PR でそろえて変えるもの: 生成器のキーと名前の作り方（`WitToKotlinPoetConverter` の import / export の両側、`allocateWorldTypeNames`）、`PlanImport.dispatchKey` と `resolveExport`（Chicory）、`rust-bridge` の関数の解決とホスト import の登録
* `runtime-api` に `Linker` と `ImportsFactory` が加わる（JVM 11 の範囲で実装する）
* 生成されるファイルが増える。Gradle プラグインの出力先ディレクトリは変わらない。インクリメンタルコンパイルには有利
* テスト
  * `wasi:filesystem/types` と `wasi:http/types` を両方 import / export するフィクスチャで、両バックエンドの E2E と差分テスト
  * 同じ interface を import と export の両方で使う world
  * 異なるバージョンの同居、インライン interface の名前と引数名の一致がエラーになること
  * キーワードと複数語のセグメントの変換（生成器のユニットテスト）
  * 曖昧な短い名前の `invoke` が `IllegalArgumentException` になること
  * `Linker` を直接渡したとき、登録の不足がセッションを開く時点でエラーになること

## 今後

**ADR-0012（world の形）**: world をオブジェクトではなく `<base>.<ns>.<pkg>.<world>` の package にし、`withSession`、world 直下の export 関数、`imports(...)` をトップレベルに置く変更を、ADR-0006 を置き換える形で決める。export した interface の関数の呼び出し方（interface ごとのマーカーを context に取ってトップレベルに置くか、`session.foo.f()` の形を残すか）もそこで決める。`imports(...)` の置き場所はそれに従う。

**#13（WASI のライブラリ）**: WASI 0.3 のホスト実装をビルド済みライブラリとして配るには、ライブラリが使う WASI の型（例: `wasm2kt.wasi.filesystem.types.Descriptor`）と、利用者の生成コードの型が同じクラスでなければならない。wit-bindgen と Wasmtime の `bindgen!` は、`with` オプション（「この interface は生成せず、既存のモジュールを使う」という対応表）でこれを解決している。

こちらでも Gradle プラグインに同様の対応表（とプリセット、ライブラリの jar のマニフェストからの自動設定）を持たせる想定。ライブラリは interface ごとの型と `Linker.add` を提供し、対応付けた interface の `imports(...)` の引数にはライブラリの実装を既定値として付けられる。それが成り立つ前提が、この ADR の「名前が決定的であること」「キーが完全修飾名であること」「interface 単位で登録できること」「バージョンが違えば対応付けないこと」。`with` 自体の設計は #13 の ADR で決める。
