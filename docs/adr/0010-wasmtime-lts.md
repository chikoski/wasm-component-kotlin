# 0010. decomposer と rust-bridge を同じ Wasmtime の LTS に揃える

* ステータス: 承認
* 日付: 2026-11-01

## 背景

Wasmtime は 2 か所で使っている。

* decomposer: `wasmtime-environ` で、コンポーネントを分解する（ADR-0002）
* rust-bridge: `wasmtime` で、Wasmtime バックエンドを動かす。この Wasmtime バックエンドが、Chicory との差分テストの参照実装になる（ADR-0007）

2 つの版はばらばらに決まっていて、decomposer は 49、rust-bridge は 29 だった（#34）。29 はすでにサポートが終わっていた。

さらに 29 から 49 の間には、Component Model の意味の変更が入っている。そのため差分テストは、古い Wasmtime と一致することを確かめていた可能性がある。

Wasmtime のリリースの方針は次のとおり（[Release Process](https://docs.wasmtime.dev/stability-release.html)）。

* 新しいメジャー版は毎月出て、サポート期間は 2 か月
* 12 の倍数の版（24 / 36 / 48 …）は LTS で、24 か月間セキュリティ修正が backport される

## 検討した選択肢

1. **両方を最新の版に追従する**（当時は 49） — 参照実装が最新の仕様に近い / 2 か月ごとに更新が必要。decomposer は wasmtime-environ の内部表現に依存しているので、更新のたびに plan の確認と同梱 wasm の再ビルドが要る
2. **両方を同じ LTS に揃える**（48） — 2 年間修正を受けられ、更新の頻度が下がる / 最新の仕様の変更が入るのは、次の LTS への更新か、意図して上げたとき
3. **rust-bridge だけ LTS にする** — 変更が小さい / 参照実装と分解の版がずれ続ける

## 決定

選択肢 2 を採る。

* decomposer の `wasmtime-environ` と rust-bridge の `wasmtime` は、同じ Wasmtime の LTS の、同じパッチ版に固定する（今は `=48.0.8`）
* decomposer の `wit-component` / `wit-parser` は、その Wasmtime が使う wasm-tools の系列（48 は wasmparser 0.254）に合わせる
* LTS の間はパッチ版だけを上げる。次の LTS（60）か、仕様の変更で必要になったときにメジャー版を上げる。上げるときは、両方を同じ PR で上げる
* wasm-tools の CLI（フィクスチャの作成と integration-tests が使う）は、この組み合わせとは別に CI で固定する（今は 1.258.0）

## 結果

48 に上げたときに、次のことが分かった。決定の前提になった事実の記録として残す。

* **plan の形式は v3 になった。** 48 のアダプター（子コンポーネント同士の同期呼び出し）は、`task-may-block` という global を import する。plan ではこれを新しい core 定義として表し、Chicory はインスタンスごとに値 0 の global を渡す
* **ADR-0007 の、Wasmtime との違いは 1 点になった。**
  * UTF-16 の文字列のアラインメントは、48 では Wasmtime も検査する
  * ホストの整数の範囲検査は、ブリッジ自身の切り捨て（`as u8` など）が原因だった。ブリッジを直したので、両バックエンドで一致する
  * 残る違いは、lift のときに見つかったトラップの後の扱いだけ。48 は core の wasm のトラップの後しかインスタンスを poison しない。Chicory は、仕様の `trap_if` に従ってどちらも poison する。この項目は、Chicory 単体のテストで確かめる
* **同じ呼び出しで borrow しているハンドルを own でも渡すことは、ホストの誤りとして扱う。**
  * Chicory は、これまで poison していた（29 がそうだったため）。ADR-0007 の規則どおり `IllegalStateException` にし、poison しない
  * ブリッジは、Wasmtime に渡す前に自分で拒否する。48 はこれを拒否するが、ハンドルの貸し出し数を戻さないので、ホストが後でそのハンドルを drop できなくなるため
* **ADR-0008 の回避策のうち、1 つは外せた。**
  * import した resource 型は、それぞれ別の動的なホスト型（`ResourceType::host_dynamic`）で登録する。1 つの `HostRes` に揃える必要はなくなった。別の resource の別名の import（`(eq ...)`）は、元の型と同じ型で登録する
  * ホストの borrow を所有ハンドルで代用する回避策は、48 でも必要。ホスト呼び出しの外で作った borrow を lower すると、Wasmtime の内部エラー（`BUG: no current scope`）になる
* LTS に揃えても、decomposer の更新では、分解結果が変わらないか（`regenerate-fixtures.sh` で差分が出ないか）と、同梱 wasm の再ビルドを毎回確かめる必要は残る
