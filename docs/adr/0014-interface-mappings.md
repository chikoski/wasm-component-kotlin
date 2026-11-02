# 0014. 既存の package の interface を対応表（with）で使い、ライブラリはマニフェストで提供する interface を示す

* ステータス: 承認
* 日付: 2026-11-02
* 関連: ADR-0011（今後: #13 の項）、ADR-0012（結果: world をまたいだ型の共有）、#54、#13

## 背景

world からバインディングを生成すると、world が import する interface（WASI など）も、利用者の package に生成される。

```text
ライブラリがコンパイル時に見た型:  wasm2kt.wasi.wasi.filesystem.types.Descriptor
利用者の生成コードの型:            com.example.app.wasi.filesystem.types.Descriptor
```

名前と中身が同じでも JVM では別のクラスなので、ライブラリのホスト実装を利用者の `imports(...)` に渡せない。#13（WASI 0.3 のホスト実装をビルド済みライブラリとして配る）の前提になる。

wit-bindgen の `generate!` と Wasmtime の `bindgen!` は、`with` オプション（「この interface は生成せず、既存のモジュールを使う」）でこれを解決している。
ADR-0011 で、名前が決定的、キーが完全修飾名、interface 単位の `Linker.add`、同じ package の異なるバージョンは同居させない、という前提はそろっている。

残る問題:

* 生成コードの共通の型（`WitOption`、`WitResult`、`CodePoint`、`TupleN`）は `<base>/WitSupport.kt` にあり、生成ごとに別のクラスになる
* export した interface のマーカー（`<Iface>Exports`）は、`<base>` の `WitExports` と `internal` な `witSession` に依存する
* 生成時に、ライブラリがどの版の interface から生成されたかが分からない

## 検討した選択肢

対応付ける範囲:

1. **import した interface だけ** — ホストの実装を配るのに必要なのはこれだけ / export の型は共有できない
2. **export も含める** — world をまたいで export の型も共有できる / `WitSession` と `WitExports` を runtime-api に移す必要があり、生成された `WasmtimeSession` にも波及する

共通の型（`WitResult` など）の扱い:

1. **生成ごとのまま。対応付けた interface の型の別名は展開する** — 変更が小さい / ライブラリと利用者で `WitResult` が別のクラスになる（利用者のコードが値を受け渡すのは名前付きの型と resource だけなので、困らない）
2. **runtime-api に移す** — 1 つのクラスになる / 公開 API と生成器の広い範囲が変わる

バージョンの照合:

1. **対応表のキーと WIT のキーの比較だけ** — 単純 / ライブラリが別の版から生成されていても分からない
2. **ライブラリの jar にマニフェスト（提供する interface とバージョンと package）を入れ、生成時に照合する** — 版の違いを生成時に検出でき、依存関係から対応表を自動で作れる / マニフェストの形式を保守する必要がある

## 決定

対応付ける範囲は選択肢 1、共通の型は選択肢 1、バージョンの照合は選択肢 2 を採る。

**対応表**

* キーは interface の完全修飾名（バージョン込み。`wasi:filesystem/types@0.3.0`。バージョンのない package では `@` 以降なし）、値はその interface のバインディングを生成した Kotlin の package（`wasm2kt.wasi.wasi.filesystem.types`）
* 対応先の package にある型とホストが実装する interface の名前は、ADR-0011 の規則（PascalCase、`Interface` 接尾辞）で決まるものとみなす
* CLI: `--with <key>=<package>`（繰り返し可）。Gradle プラグイン: コンポーネントごとの `with("<key>", "<package>")`

**対応付けた interface について生成するもの・しないもの**

* 生成しない: その interface の型、ホストが実装する Kotlin interface、`Linker.add`
* 対応先を参照する: 型の参照（record、variant、enum、flags、resource）、`imports(...)` の引数の型、`imports(...)` の中の `Linker.add`
* 型の別名（`type x = ...`）は、対応先の typealias を参照せずに中身を展開する（対応先の別名が対応先の `WitResult` などを指すことがあるため）
* セッションを開くときの登録の確認（`requireDefined`）は、WIT から求めたキーで行う。ライブラリが別の版から生成されていた場合の最後の安全網になる
* export した interface は対応付けられない。world が export するだけの interface を対応表に書いたら、生成時にエラーにする。import と export の両方に使う interface は、import 側だけを対応付ける（export 側は従来どおり `<base>.exports...` に生成する）
* 対応付けた interface の型が、ほかの interface の型を使う（`use`）なら、その interface も対応付ける必要がある。足りなければ、生成時にエラーにする（対応先のライブラリは、依存する interface を自分の package の型で参照しているため）

**バージョンの照合とエラー**（いずれも生成時の `IllegalArgumentException`）

* 明示した対応表のキーが world の import にない。同じ interface が別の版で import されていれば、その版をメッセージに含める
* 明示した対応先の package が、ライブラリのマニフェストでは別の版の interface として登録されている
* ライブラリのマニフェストが、world が import する interface を別の版で提供している（黙って利用者の package に生成すると、同じ名前の別のクラスができるため）。依存関係を見直すか、マニフェストからの自動設定を止める（CLI は `--no-library-interfaces`、Gradle は `useLibraryInterfaces = false`）

**ライブラリ側: `--interfaces-only` とマニフェスト**

* `--interfaces-only` は、world が import する interface（`use` で参照される interface を含む）と、値の型だけの `WitSupport.kt`（`WitOption`、`WitResult`、`CodePoint`、`TupleN`）を生成する。world のファイル、セッション、export は生成しない。WIT に world がなければ、すべての interface を import する world として扱う
* 同時に、マニフェスト `META-INF/wasm2kt/interfaces/<base package>.json` を出力する。ファイル名に base package を入れるのは、複数のライブラリの jar を 1 つにまとめる（Android の APK など）ときに衝突させないため

  ```json
  {
    "format": 1,
    "package": "wasm2kt.wasi",
    "interfaces": {
      "wasi:filesystem/types@0.3.0": "wasm2kt.wasi.wasi.filesystem.types",
      "wasi:io/streams@0.3.0": "wasm2kt.wasi.wasi.io.streams"
    }
  }
  ```

* `--interfaces-only` と `--with` は組み合わせられる（ほかのライブラリの interface を使うライブラリ）。マニフェストには、そのライブラリが生成した interface だけを書く
* ライブラリごとに別の base package を使う（`WitSupport.kt` のクラスとマニフェストのファイル名が衝突するため）

**マニフェストからの自動設定**

* CLI: `--library <jar またはディレクトリ>`（繰り返し可）のマニフェストを読む
* Gradle プラグイン: コンパイル時のクラスパスからマニフェストを読む。Kotlin/JVM は `compileClasspath` のうちモジュールとプロジェクトの依存（ファイルの依存、つまりプラグイン自身が加える AOT コンパイル結果の jar は除く）、Android は variant ごとの `compileClasspath`。読むのは生成タスクの実行時で、設定時に tooling のクラスを参照しない（ADR-0004）
* world が import する interface と同じキーのものだけを使う。明示した対応表が優先する

**`imports(...)` の既定値**

* 今回は付けない。対応付けた interface も、`imports(...)` の必須の引数のまま（引数の型が対応先の型になる）。ライブラリが既定の実装を提供する方法（マニフェストに実装のファクトリーを書くなど）は、#13 で決める

## 結果

* ビルド済みのライブラリが、interface ごとの型と `Linker.add` を提供できる。利用者は、ライブラリの実装をそのまま `imports(...)` に渡せる
* 同じ WIT の複数の world は、1 つ目の world の import の interface を `--interfaces-only` で生成し、2 つ目以降で `with` を使えば、型を共有できる（import の interface に限る）
* ライブラリと利用者の `WitResult` などは別のクラスのまま。対応付けた interface の型の別名は、利用者の側では展開された型になる
* export の型の共有が必要になったら、`WitSession` / `WitExports` を runtime-api に移す ADR を別に書く
* テスト: interface を `--interfaces-only` で別の package に生成し、それを対応付けて world を生成して、両バックエンドの E2E を通す。各エラーの生成器のテスト。Gradle プラグインで、ライブラリのプロジェクトのマニフェストから自動で対応付けること
