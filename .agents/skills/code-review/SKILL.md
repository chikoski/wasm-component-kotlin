---
name: code-review
description: テストケースの変更時や一定規模以上のコード変更時に、Claudeによるコードレビューを実行する手順です。ローカルのビルドとテストが成功した直後、コミットおよびPR作成の前に使用します。
---

# コードとテストのレビュー手順

テストケースの変更時や一定規模以上のコード変更時に、Claudeに差分を検査させます。実装上の不備や設計不変条件からの逸脱、不適切なテスト修正をコミット前に検出することが目的です。

## 発火条件と実行タイミング

次のいずれかに該当する場合は、このスキルに従ってレビューを実施します。

* テストファイルの追加または修正
* 約50行以上の本番コード変更
* 複数ファイルにまたがる本番コード変更
* 重要境界に関わる本番コード変更

重要境界にはCanonical ABI、値のバイナリ形式、JNIやFFMのブリッジ、asyncスケジューラ、Gradleプラグインのクラスローダー分離、JVM 17互換性が含まれます。実行タイミングはローカルでのビルドおよびテストがすべて成功した直後とします。コミットおよびPRの作成は、このレビューと指摘事項の修正を完了させてから行います。

## ステップ1 レビューの実行手段の選択と起動

状況に応じて、次の3つの手段のいずれかを選択してレビューを実行します。

* ローカルの `claude` コマンド
* サブエージェントの起動
* `swarm` ワーカーの起動

ローカルの `claude` コマンドを使う場合は、認証済みの `/Users/chikoski/.local/bin/claude` を利用します。サンドボックス外のバイナリとネットワーク通信を使用するため、`run_command` で `BypassSandbox: true` を指定して実行してください。`-p` と `--permission-mode plan` などを指定して非対話かつ読み取り専用で実行し、プロンプトにレビュー依頼内容を渡します。

サブエージェントを使う場合は、`invoke_subagent` ツールで `research` などの読み取り専用サブエージェントを起動し、プロンプトに検査対象と観点を渡します。

`swarm` ワーカーを使う場合は、`swarm` ツールの `add` アクションを呼び出して起動します。`name` には `code-reviewer`、`model` には `claude-opus-5-5-high`、`workspace` には `inherit` を指定します。`claude-opus-5-5-high` のクォータが枯渇している場合は、`claude-sonnet-5-5-high` を指定します。実行中にクォータ上限へ達した場合も、同じ代替モデルへ切り替えます。

## ステップ2 レビュー指示と検査観点

レビュー担当者への依頼内容には、変更の目的、関連するIssueやADRの番号、`git status` と `git diff` を用いてワークツリーの差分を確認する指示を含めます。あわせて、[CLAUDE.md](../../../CLAUDE.md) と [CONTRIBUTING.md](../../../CONTRIBUTING.md) の不変条件を参照するよう伝えます。レビュー担当者は読み取り専用で検査のみを行い、ファイルを直接編集せずに指摘事項を出力または `@coordinator` 宛てにボードへ投稿させます。

レビュー担当者には次の5つの観点で差分を検査させます。

* 不変条件の遵守
* 例外とトラップの境界
* リソースと並行処理の安全性
* テストの妥当性と網羅性
* 差分ミューテーション検査の生き残り確認

不変条件の遵守では、`runtime-api` と `runtime-chicory` におけるJVM 17およびAndroid API 26互換性の維持を確認させます。さらに、WasmtimeとChicoryの両バックエンド間での値形式や関数解決の整合性、`plan.json` バージョンや `decomposer` 成果物の更新漏れ、Gradleプラグインにおけるクラスローダー分離も点検させます。

例外とトラップの境界では、Canonical ABIの検証が仕様の `trap_if` に沿っているかを確認させます。ゲスト起因の違反がインスタンスをpoisonする `ComponentTrapException` になり、ホスト側の引数誤りがpoisonしない `IllegalArgumentException` または `IllegalStateException` になっているかを検査させます。

リソースと並行処理の安全性では、ホスト定義リソースの `rep` 管理と `HostResourceTable` への束縛、破棄時の通知漏れがないかを確認させます。async機能では `SessionLoop` スレッド制約が守られ、同期パスに不要な変更が入っていないかを点検させます。

テストの妥当性と網羅性では、テストのスキップや無効化、`conformance/skip.txt` への安易な追加、アサーションの緩和が行われていないかを入念に確認させます。実装のバグに合わせて既存テストの期待値を書き換えていないかも検査させ、境界値や異常系のテストケースが十分かを評価させます。

`rust-bridge` または `decomposer` の Rust コードを変更した場合は、コミット前のレビュー依頼に先立ち `cargo-mutants` の差分実行を行います。`cargo-mutants` が未インストールの環境では差分検査を省略し、通常のテストとレビューへ進みます。新規追加した未追跡ファイルがある場合は `git add -N` で追跡予定に加えてから、`origin/main` との分岐点を基準にワークスペースごとの相対パスで差分を生成します。`rust-bridge` は `wasm2kt-bridge-core` を対象に含めるため `--workspace` を付けて実行します。`decomposer` は隔離作業ディレクトリからもフィクスチャを参照できるよう `WASM2KT_FIXTURES` を設定して実行します。

```bash
mkdir -p .tmp
git diff --no-ext-diff --no-color --relative=rust-bridge "$(git merge-base origin/main HEAD)" > .tmp/mutants-rust-bridge.diff
cargo mutants --manifest-path rust-bridge/Cargo.toml --workspace --in-diff .tmp/mutants-rust-bridge.diff -o .tmp/mutants-rust-bridge

git diff --no-ext-diff --no-color --relative=decomposer "$(git merge-base origin/main HEAD)" > .tmp/mutants-decomposer.diff
WASM2KT_FIXTURES="$(pwd)/fixtures" cargo mutants --manifest-path decomposer/Cargo.toml --in-diff .tmp/mutants-decomposer.diff -o .tmp/mutants-decomposer
```

固定スコアによる一律の失敗判定は行いません。生き残った変異体の一覧をレビュー担当者へ共有します。境界値テストの抜け漏れ判断にその一覧を活用します。

## ステップ3 修正とエスカレーション

選択した実行手段の出力、サブエージェントからの返答、または `swarm_wait` と `read_board` でレビュー報告を受け取ります。指摘された実装上のバグやエッジケースのテスト不足は、メインエージェントが自律的に修正します。修正後はローカルのビルドとテストを再度実行し、すべて通ったことを確認した上で必要に応じて再確認を依頼します。`swarm` ワーカーの場合は `post_message` で `@code-reviewer` に依頼します。サブエージェントの場合は `send_message` で、ローカルコマンドの場合は修正後の差分を添えた再実行で依頼します。再確認のやり取りは最大2往復までとします。

既存テストケースの期待値を変更する必要があり、かつその変更が仕様変更や公開APIの挙動変更を伴う場合は、自律的に修正を確定してはなりません。また、設計上のトレードオフ判断が必要な指摘を受けた場合も同様です。該当する論点と変更影響を整理し、ユーザーへエスカレーションして確認を求めます。

## ステップ4 後処理

すべての指摘への対応とローカルテストの再確認が完了したら後処理を行います。`swarm` ワーカーを起動した場合は、`swarm` ツールの `kill` アクションで `code-reviewer` を終了します。その後、変更のコミットとPR作成へ進みます。
