# 0007. Canonical ABI の検証は仕様に従い、トラップとホストのエラーを分ける

* ステータス: 承認
* 日付: 2026-10-31

## 背景

Chicory バックエンドの Canonical ABI 実装（`runtime-chicory`）は、正しいゲストを動かすことだけを考えていた。
不正な `char`、範囲外の判別子、壊れた UTF-8、範囲外やアラインされていないポインタ、`cabi_realloc` 中の import 呼び出し、
インスタンスへの再入などを検出できず、黙って変な値を返すか、`IllegalArgumentException` などで落ちていた。
async（#16）ではタスクとインスタンスの状態がさらに増えるため、その前に検証を仕様どおりにしておく（#18）。

参照実装の Wasmtime（`rust-bridge`、Wasmtime 49）とは、次の 2 点で仕様と挙動が違う。

* UTF-16 文字列のポインタのアラインメント（2 バイト）を検査しない
* ホストから渡された整数が WIT の型の範囲を超えると、ブリッジが切り捨てる

## 検討した選択肢

1. **Wasmtime の挙動に完全に揃える** — 差分テストがすべて一致する / 仕様違反を受け入れることになる
2. **仕様（`definitions.py` の `trap_if`）に従い、Wasmtime と違う点は Chicory 単体のテストで確認する** — 仕様どおり / 差分テストから外す項目が出る

## 決定

選択肢 2。

* ゲストが ABI の規則を破ったら **トラップ**（`wasm2kt.runtime.ComponentTrapException`）。トラップしたインスタンスは使えなくなり
  （poison）、以降の呼び出しもすべて `ComponentTrapException` になる。core wasm のトラップや、ゲスト実行中にホスト import が投げた
  例外も同じ扱い（例外の型はそのまま）
* ホストが渡した値が WIT の型に合わないとき（範囲外の整数、1 文字でない `char`、孤立サロゲートを含む文字列、未知のハンドル）は
  **ホストのエラー**（`IllegalArgumentException` / `IllegalStateException`）。インスタンスは使い続けられる。
  ただし、同じ呼び出しで `borrow` として渡したハンドルを `own` としても渡すのは、仕様（`lift_own` の `num_lends` 検査）と
  Wasmtime に合わせてトラップにする
* `ComponentTrapException` は `IllegalStateException` を継承し、既存の呼び出し側のコードを壊さない
* Wasmtime と挙動が一致する項目は差分テスト（`ChicoryDifferentialTest`）、一致しない項目と Wasmtime では試せない項目
  （再入など）は `runtime-chicory` の `CanonicalAbiStrictnessTest` で確認する。テスト用に、わざと不正な値を返す
  コンポーネント `fixtures/abi-edge`（手書きの WAT）を追加する

## 結果

* Chicory バックエンドは不正なゲストに対して仕様どおりに振る舞う。async の実装でも同じ枠組み（poison、`may_enter` / `may_leave`、
  ハンドルの貸し出し数）を使える
* ホストの値の範囲検査は Wasmtime より厳しい。範囲外の値を渡すコードは Chicory でだけ失敗する（生成コードは型で範囲を保証するので、
  通常は起きない）
* Wasmtime バックエンドはトラップを `IllegalStateException` として報告し、poison の後は「cannot enter component instance」になる。
  例外の型は揃えていない
* ゲストがホストに `borrow` を渡す方向の貸し出し数は、ホスト定義 resource（#14）で扱う
