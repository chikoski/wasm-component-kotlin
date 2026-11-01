# 0008. ホスト定義 resource を interface + Imports で表し、rep の表で受け渡す

* ステータス: 承認
* 日付: 2026-10-31

> 補足（2026-11-01）: [ADR-0010](0010-wasmtime-lts.md) で Wasmtime 48 に上げ、import した resource 型はそれぞれ別の動的なホスト型で登録するようになった（下の `HostRes` 1 型の回避策は不要）。ホストの borrow を所有ハンドルで代用する回避策は、48 でも必要なので残っている。plan は v3。

## 背景

WIT では、world 直下の `resource` と、world が import する interface の `resource` は、**ホストが定義する**
resource です（コンポーネントから見ると import）。WASI 0.3 のハンドル（ファイル記述子、HTTP のリクエストなど）は
すべてこの種類なので、WASI 0.3 のホスト実装（#13）の前提になります（#14）。

これまでの実装は次の状態でした。

* ジェネレーターはすべての resource を、コンポーネントが定義する resource として扱っていた（ホストが実装する手段がない）
* Wasmtime ブリッジは resource 型を linker に登録しておらず、resource を import するコンポーネントをインスタンス化できなかった
* Chicory バックエンドは、import された resource の受け渡しを未対応の例外にしていた

## 検討した選択肢

Kotlin の API:

1. **resource ごとの interface + `Imports` のメンバー** — resource はメソッドだけを持つ interface（`WitHostResource`）。
   constructor は `newBlob(...)`、static は `blobMerge(...)` として `Imports` に置く / resource が多いと `Imports` が大きくなる
2. **resource ごとの Factory interface** — `Imports` には `val blob: Blob.Factory` だけを置く / 1 段深くなり、書く量が増える

バックエンドの範囲:

1. **両方のバックエンドに実装する** — 同期の機能は Wasmtime を参照実装にする方針（ADR-0005）どおり。差分テストができる
2. **Chicory だけ** — 作業は減る / Wasmtime では動かず、差分テストもできない

## 決定

API は選択肢 1、範囲も選択肢 1（両方に実装する）。

* **ホスト resource の判定**: 型の持ち主が world か、world が import して export しない interface である `resource`
* **値の表し方**: バックエンドとの JSON 境界では、ホスト resource は整数の rep で表す。オブジェクトと rep の対応は、
  セッションごとの `HostResourceTable`（`runtime-api`）が持つ。生成されたセッションは、インスタンス化とすべての呼び出しの間、
  この表を現在のスレッドに束縛する。同期呼び出しの間にコンポーネントが呼ぶホスト import も同じスレッドで動くので、
  同じ表を使える
* **所有権**
  * own で渡すと所有権がコンポーネントに移る。コンポーネントが最後の own ハンドルを drop すると、バックエンドが
    `[resource-drop]<name>`（interface なら `<iface>#[resource-drop]<name>`）を dispatch し、表から外して `close()` を呼ぶ
  * コンポーネントが own で返したオブジェクトは、表から外れてホストの手元に戻る
  * borrow は `WitBorrowed` で包み、その呼び出しの間だけ表に載せる
  * セッションを閉じたとき、コンポーネントがまだ所有しているオブジェクトを `close()` する
* **Chicory**
  * plan に import された resource と、その名前を示す import を追加する（`PLAN_VERSION` 2）
  * own は guest のテーブルに own ハンドルとして入れ、borrow は borrow ハンドルとして入れる。borrow ハンドルが
    呼び出しの終わりまでに drop されていなければ、仕様（`num_borrows`）どおりトラップにする
* **Wasmtime（rust-bridge）**
  * import された resource 型を、すべて 1 つのホスト型 `HostRes` として linker に登録する（Wasmtime 29 には動的なホスト型がないため）
  * ホストからの borrow は、Wasmtime 29 がホスト呼び出しの外で作った borrow を lower できないので、所有ハンドルを一時的に作って
    貸し、呼び出しの後に解放する

## 結果

* WIT の意味どおり、import された resource はホストが実装する。これまでコンポーネント定義として生成されていた world 直下や
  import した interface の `resource` は、生成コードが変わる（破壊的変更）。テスト用の `calculator.wit` は、
  コンポーネント定義の resource を exported interface に移した
* Wasmtime では、別々の import resource 型が同じホスト型になるため、Wasmtime 側では型を取り違えても検出されない。
  ホスト側の表は rep から得たオブジェクトの型を検査するので、取り違えは Kotlin 側で `IllegalStateException` になる
* 表をスレッドに束縛する方式は、同期呼び出しが前提。async（#16）では、コルーチンのコンテキストに表を載せる形に見直す
* ホスト import の結果が JSON の null（`option` の none など）のとき、Wasmtime ブリッジが結果を設定していなかった既存の不具合を直した
