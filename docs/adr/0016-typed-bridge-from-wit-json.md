# 0016. 型付きブリッジの Rust の型を wit.json から生成し、bindgen! を使わない

* ステータス: 承認
* 日付: 2026-11-04
* 関連: ADR-0010、ADR-0011、ADR-0015（型付きブリッジの作り方と、決めた点 A を置き換える）、#15、#59、#60

## 背景

ADR-0015 では、Wasmtime の型付きブリッジを次の手順で作ることにしていた。

* `wasmtime::component::bindgen!` に WIT を渡して、Rust の型と関数を作る
* `bindgen!` はコンポーネントの `.wasm` を直接は読めないので、decomposer が `wit-component` で WIT を取り出す（決めた点 A）

実装を始める前に、もっと単純な方法があるとわかった。wasmtime 48 には、次の 2 つがある。

* derive マクロ `ComponentType` / `Lift` / `Lower`（record、variant、enum）と、`flags!` マクロ
* `Func::typed` と型付きの `LinkerInstance::func_wrap`

これらを使えば、`bindgen!` を通さずに型付きの呼び出しを作れる。

`bindgen!` の方式には、次の負担がある。

* `bindgen!` が付ける名前（モジュール、型、フィールド、関数の名前を heck で変換したもの）に合わせて、形式との変換コードを生成しなければならない
* WIT を取り出すために、decomposer に `wit-component` を加えて出力を変える必要がある（`build-wasm.sh` と `regenerate-fixtures.sh` の再実行も要る）

## 検討した選択肢

1. **`bindgen!` に WIT を渡す（ADR-0015 のまま）**
   * 長所: Wasmtime の推奨の方法
   * 短所: `bindgen!` の名前づけに合わせる必要がある。decomposer の出力に WIT を加える必要がある
2. **tooling が wit.json から Rust の型と呼び出しを生成し、derive と `Func::typed` / `func_wrap` を使う**
   * 長所
     * 名前を自分で決められる（`T<型の番号>`、フィールドは `f<番号>`。WIT の名前は `#[component(name = "...")]` で渡す）
     * 入力は Kotlin の生成と同じ wit.json なので、decomposer を変えずに済む
   * 短所: WIT の型から Rust の型への対応を、tooling で保守する

## 決定

選択肢 2 を採る。

**クレートの構成**

* rust-bridge をワークスペースにし、共通部 `wasm2kt-bridge-core`（rlib）を切り出す
  * 共通部が持つもの: セッション、形式、ホスト import、resource の規則、C ABI / JNI の関数
  * C ABI / JNI の関数は、マクロ `export_bridge!` が公開する
* 汎用ブリッジ（`rust-bridge/`）は、`export_bridge!(&[])` を呼ぶだけの cdylib にする
* 型付きブリッジは、tooling が生成する 1 つのクレートにする
  * 共通部のソースと汎用ブリッジの `Cargo.lock` は tooling のリソースとして同梱し、生成したクレートの `core/` に書く
  * パッケージ名とライブラリ名は汎用ブリッジと同じにする（`libwasm2kt_wasmtime_bridge`）。こうすると、型付きブリッジのライブラリで汎用ブリッジをそのまま置き換えられ、`Cargo.lock` もそのまま使える
  * テストでは別のライブラリ名にして、Cargo の target ディレクトリを汎用ブリッジと共有する

**型付きの呼び出し**

* コンポーネントごとに、`TypedComponent` を実装したモジュールを生成する
  * `export`: 関数名から、`Func::typed` で作った型付きの呼び出しを返す
  * `link_import`: ホスト import を `func_wrap` で型付きに登録する
* 関数名の解決（ADR-0011）は汎用の経路と同じ処理で行い、正規の名前（`<interface>#<function>`）で型付きの呼び出しを引く
* 生成できない関数は、関数ごとに汎用の経路（`Val`）で呼ぶ。生成したモジュールの先頭のコメントに、その一覧を書く
  * 例: 引数が 17 個以上の関数、async の型、world が import と export の両方に持つ interface の resource を含む型
* 型が合わないとき（コンポーネントを変えたのにブリッジを作り直していないなど）は、`resolve` か、インスタンス化のときにエラーにする。汎用の経路には落とさない
* resource の規則（ADR-0008、ADR-0007 の move と borrow の検査）は、汎用の経路と同じ関数を共通部から呼ぶ
  * ホスト定義 resource: rep（`host_payloads` で import のキーから payload を引く）
  * ゲストの resource: ブリッジのハンドル

**セッション**

* セッションを開くときに、コンポーネントの ID（`wasm2kt generate --bridge-id`）を渡す
* ID のない（null の）セッションは汎用の経路で動く
* ブリッジのライブラリにない ID はエラーにする

**ビルド**

* CLI
  * `wasm2kt bridge`: クレートを生成する。`--build` を付けると、続けて `cargo build --release` も実行する
  * `wasm2kt generate`: 今までの生成。サブコマンドなしの呼び出しは `generate` と同じに扱う
* Gradle プラグインのタスク（`wasm2ktGenerateBridge`、`wasm2ktCargoBuild`）は別の PR で扱う。今のプラグインは Chicory 向けの生成だけで、Wasmtime のセッションを出していないため

## 結果

* decomposer の変更（ADR-0015 の決めた点 A）が要らなくなった。WIT のテキストも、エンコードした WIT パッケージも作らない
* 型付きブリッジは、汎用ブリッジと同じ C ABI と JNI の関数を公開する。Kotlin 側は、セッションを開くときに ID を渡すかどうかだけが違う
* WIT の型から Rust の型への対応を、tooling（`TypedBridge.kt`）で保守する
  * 型が合っているかは、Wasmtime がインスタンス化と `Func::typed` のときに検査する
  * 差分テスト（`typedBridgeTest`）は、すべてのフィクスチャで、型付きの呼び出しの結果が Chicory と一致することを確かめる
* 共通部を変えたら、型付きブリッジにも同梱される。tooling のリソースは、ビルドのときに `rust-bridge/core` からコピーする
* ターゲットの指定（`--target`）は CLI にあるが、複数のプラットフォーム向けのビルドと配布は #59、Android は #60 で扱う
