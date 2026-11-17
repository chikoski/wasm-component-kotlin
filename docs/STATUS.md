# プロジェクトの現状とアクションアイテム

> 最終更新: 2026-11-16。対象ブランチは `feat/phase3-runtime-and-docs`、主な変更点は共有テーブル適合と借用貸与追跡による公式テスト `resources/` 全件通過、`runtime-chicory` の境界呼び出しと `Transcoder` の最適化による #58 完了、大規模メソッド分割後の制約文書化、PR テンプレートの追加です。
>
> このファイルは「今どうなっていて、次に何をやるか」を書く場所です。変わるたびに更新してください。
> 使い方の説明は [README](../README.md) と [docs/](.)、設計判断の理由は [docs/adr/](adr/) に置き、ここでは重複させずにリンクします。

## 1. 全体像

`wasm2kt` は、WebAssembly コンポーネント（WIT）から型安全な Kotlin ホストバインディングを生成するプロジェクトです。
生成されるワールド DSL は共通で、実行バックエンドを 2 つ持ちます。

| バックエンド | 実行方式 | 主な用途 | 入口 |
| :--- | :--- | :--- | :--- |
| Wasmtime | Rust 製ブリッジ（FFM / JNI）でネイティブ実行 | デスクトップ / サーバー JVM | `withSession(path)` |
| Chicory | core モジュールをビルド時に JVM バイトコードへコンパイルし、純 JVM で実行 | **Android**、ネイティブ不可の環境 | `withSession(XxxComponent)` |

```text
component.wasm ──(Gradle プラグイン)──▶ 分解（wasm 版 decomposer を Chicory で実行）
                                        ├─▶ core モジュールの AOT コンパイル（jar）
                                        └─▶ Kotlin バインディング + XxxComponent 生成
アプリ ──▶ 生成 DSL ──▶ バイナリ形式（ADR-0015）──▶ runtime-chicory ──▶ Chicory
                                                    └─▶ Wasmtime ブリッジ（FFM / JNI）
```

詳細: [ドキュメント目次](index.md) / [アーキテクチャ](explanation/architecture.md) / [Gradle プラグイン](reference/gradle-plugin.md)

### モジュール構成

| モジュール | 役割 | JVM | 状態 |
| :--- | :--- | :--- | :--- |
| `ast` | WIT JSON のモデル | 21 | 稼働 |
| `generator` | KotlinPoet によるバインディング生成 | 21 | 稼働 |
| `cli` | `wasm2kt` コマンド | 21 | 稼働 |
| `runtime-wasmtime` | Wasmtime ブリッジの Kotlin 側（FFM / JNI） | 21 | 稼働 |
| `integration-tests` | CLI・生成・両ランタイムの E2E テストと差分テスト | 21 | 稼働 |
| `rust-bridge/` | Wasmtime ブリッジ（Rust のワークスペース。共通部 `core/` と汎用ブリッジの cdylib） | — | 稼働 |
| `decomposer/` | コンポーネント分解ツール（Rust）。`dist/` に wasm 版を同梱 | — | 稼働 |
| `runtime-api` | `ComponentBackend` などバックエンド共通の契約 | 11 | 稼働 |
| `runtime-chicory` | Chicory バックエンド（Canonical ABI 実装） | 11 | 稼働 |
| `runtime-wasi` | WASI 0.3 コアインターフェースのホスト実装 | 11 | 稼働 |
| `tooling` | 分解・AOT コンパイル・コード生成のパイプライン | 21 | 稼働 |
| `gradle-plugin` | `io.github.wasm2kt` プラグイン | 21 | 稼働 |
| `samples/android` | Android サンプルアプリ（composite build） | 11 | ART（エミュレーター）で debug / release とも動作確認済み |
| `samples/android-glitch-camera` | `png-glitch-component` を使った Android カメラグリッチデモアプリ | 11 | ホスト JVM のユニットテストと debug / release APK ビルド、ART エミュレーターでの `nonMinifiedRelease` 実行と `dex2oat64` 警告ゼロを確認済み |

## 2. 現在の状態

### 検証済み

* `./gradlew check`（TestKit の functionalTest と generator のユニットテストを含む）と integration-tests: すべて成功。1 件だけスキップ（wasm-tools が 33 個以上の flags を拒否するため。仕様どおり）。CI ではこれに加えて、decomposer と rust-bridge の `cargo clippy` と `cargo test` を実行
* Component Model の公式テストスイートは [ADR-0017](adr/0017-component-model-conformance-suite.md) と #30 に基づき Chicory で実行。`values/` と `resources/` は 191 コマンド中 190 件、`async/` は 514 件中 482 件が通過し、`resources/` の 36 件はすべて通過。残りは `conformance/skip.txt` に理由付きで記録しており、主な理由は wasmtime-environ 48 が再入をトラップにする項目
* async（#16、[ADR-0018](adr/0018-async-abi-on-chicory.md)）: Chicory で、callback 形式と stackful 形式の async lift、`task.return`、waitable set、subtask、`stream` / `future` / `error-context`、協調スレッドの組み込み関数、合成コンポーネントの async の呼び出しが動く。生成コード（`suspend fun`、`Flow`、`Deferred`）とホストの間の stream / future、ホストの `suspend fun` の import、並行の呼び出しと取り消し（ADR-0019）は、`async-stream` と `async-import` フィクスチャの E2E テストで確認
* 差分テストは、Wasmtime の FFM と JNI の両方で流す。型付きブリッジでは、結果とブリッジ自身のエラーメッセージが汎用ブリッジと同じであることも確かめる（Wasmtime の lift のエラーは、種類だけを比べる）。境界値（空と 256 KiB の list、サロゲートペア、char の端、長い list<option>）も含む
* Chicory と Wasmtime の差分テスト: rich types / host import / resource のライフサイクル / Canonical ABI のトラップ（不正な char・判別子・文字列、範囲外やアラインされていないポインタ、`cabi_realloc` の結果、`may_leave`、poison、borrow 中のハンドルの move）で結果が一致
* 短い名前が同じ interface（`example:http/types` と `example:fs/types`）の import と export を、両バックエンドで区別して扱える。キーは完全修飾名で、短い名前と版なしの名前は一意のときだけ受け付け、曖昧ならエラー（`same-name` フィクスチャ、[ADR-0011](adr/0011-qualified-interface-keys.md)、#32）
* ホスト定義 resource: Wasmtime（FFM / JNI）と Chicory で同じハーネスのイベント列が一致（own / borrow / 入れ子の option・result / static / world 直下の resource / drop 時の `close()`、貸し出し中のハンドルの drop の拒否）
* 参照実装の Wasmtime は 48 LTS（decomposer の wasmtime-environ と同じ版。[ADR-0010](adr/0010-wasmtime-lts.md)）。UTF-16 のアラインメントとホストの整数の範囲検査も、差分テストで一致を確認。Wasmtime と挙動が違う唯一の項目（lift で見つかったトラップの後の poison）と再入は、Chicory 単体のテストで確認（[ADR-0007](adr/0007-canonical-abi-validation.md)）
* コンポーネント定義の resource の所有権: own で返したハンドルはコンポーネントの表から取り除かれ、戻すと新しいハンドルになる（仕様の `lift_own` / `lower_own`）。手書きの WAT フィクスチャ `res-edge` で、返したハンドルの再利用などを Chicory 単体のテストで確認（#31）
* 値の境界: 生成コードとバックエンドの間は、型に沿ったバイナリ形式（`WitWriter` / `WitReader`）で受け渡す。JSON の経路はない（[ADR-0015](adr/0015-binary-value-boundary.md) の進め方の 1、#62。ADR-0003 / ADR-0009 を置き換え）。差分テストは、テスト専用の `test-support` で JSON の値を形式に変換して比べる
* Wasmtime の型付きブリッジ（[ADR-0016](adr/0016-typed-bridge-from-wit-json.md)、#64）: tooling が wit.json から Rust の型と型付きの呼び出しを生成し、`wasm2kt bridge` で 1 つのクレートにする。`typedBridgeTest` で、すべてのフィクスチャの差分テストと型マッピングの E2E が、型付きブリッジでも Chicory と一致する。ライブラリは `wasm2kt bridge --build --resources-output` で jar のリソースにでき、`runtime-wasmtime` がクラスパスから読み込む（#65）。Gradle プラグインでの Wasmtime の対応は、今は行わない（2026-11-04 に決定。プラグインは Chicory 向けのまま）
* Wasmtime の FFM ブリッジ: ホスト import の戻り値とエラーメッセージに大きさの上限がない（#35）。事前コンパイル済みの `.cwasm` は `openPrecompiledSession` / `withPrecompiledSession` からだけ読み込む（#33）
* decomposer の出力は決定的（exports と canonical options の順序）。各フィクスチャを繰り返し分解して同じ plan になることを `cargo test` で確認
* Gradle プラグイン: Kotlin/JVM の実ビルドで実行結果、configuration cache の再利用、UP-TO-DATE、FROM-CACHE を確認
* 性能とサイズ（[docs/performance.md](performance.md)、#19。バイナリ形式のあとは 8 節）: 64 KiB の `list<u8>` の往復は JVM（Chicory AOT）12 µs / Wasmtime FFM 3.5 ms / ART 0.3 ms（JSON 境界のときは 6.6 ms / 16.5 ms / 52 ms）。Wasmtime の型付きブリッジでは 29 µs（同じランナーの汎用ブリッジは 5.7 ms。9 節）。整数だけの呼び出しは JVM 0.19 µs / ART 2〜6 µs。小さな関数の計算は Wasmtime と同等です。大きな wasm 関数に対しては `JvmMethodSplitter` による 7,500 バイト単位の自動分割を導入済みで、詳細と `cyber_sort` の比較は [performance.md](explanation/performance.md) と [ADR-0021](adr/0021-split-large-chicory-methods.md) にあります。初回の `openSession` は ART で 57〜239 ms。APK はランタイムで +181 KB
* Android サンプル: ホスト JVM のユニットテスト、ART（ヘッドレスエミュレーター）での `connectedAndroidTest`、R8 で縮小した release 版の起動がいずれも成功（初回実行 2 ms）。手順は [samples/android/README.md](../samples/android/README.md)

### 未検証・既知の制約

* 実機（物理端末）での確認はまだ。エミュレーターでは確認済み
* Chicory バックエンドの未対応機能: `wasi:http` は [#117](https://github.com/chikoski/wasm-component-kotlin/issues/117)、`wasi:sockets` は [#118](https://github.com/chikoski/wasm-component-kotlin/issues/118) として P2 に分離しており、詳細は [ADR-0022](adr/0022-wasi03-scope-and-host-configuration.md) を参照してください。`map<K, V>` は [#71](https://github.com/chikoski/wasm-component-kotlin/issues/71) で追跡しています。いずれも使用時に例外になります。plan から分かるものは Gradle プラグインがビルド時に警告します。WASI 0.3 の `random`、`clocks`、`cli`、`filesystem`、および [ADR-0020](adr/0020-wasi03-filesystem-sandboxing-and-mounts.md) に沿った設定 API と Gradle `addWasiDependency` は実装済みです
* 同じ package の異なるバージョン（`wasi:http@0.2.0` と `@0.3.0`）を 1 回の生成で使うとエラーになる（Kotlin の package にバージョンを含めないため。[ADR-0011](adr/0011-qualified-interface-keys.md)）
* 1 回の生成で扱う world は 1 つ（world の API を package のトップレベルに置くため。[ADR-0012](adr/0012-world-at-package-top-level.md)）。複数の world は `--world` で選び、別々の package に生成する。import した interface の型は、`--interfaces-only` と `--with` で world の間で共有できる（[ADR-0014](adr/0014-interface-mappings.md)）
* 既存の package への対応付け（`with`）は import した interface だけ。export した interface の型は、world ごとに生成される（ADR-0014）
* ホスト resource の受け渡しは、セッションの呼び出し中（同じスレッド）に限られる。Wasmtime ではホストの borrow を一時的な所有ハンドルで代用している（48 でも必要。[ADR-0008](adr/0008-host-defined-resources.md)、[ADR-0010](adr/0010-wasmtime-lts.md)）
* コンポーネント定義の resource を、コンポーネントがホスト import に own / borrow で渡す経路は未対応（Chicory）
* async と WASI 0.3 は Chicory バックエンドだけで実装する方針。Wasmtime バックエンドは同期の機能の参照実装として維持（[ADR-0005](adr/0005-async-and-wasi03-chicory-only.md)）
* async の制約（[ADR-0018](adr/0018-async-abi-on-chicory.md)、[ADR-0019](adr/0019-suspending-async-boundary.md)）: async の export と import は `suspend fun` だが、ゲストの wasm を実行している間は中断しない（取り消しは、ゲストが次にホストを待つか yield したときに届く）。ホストが受け渡せる stream / future は、要素に resource / stream / future 自体を含まないもの。同じインスタンスや祖先のインスタンスへの再入は、wasmtime-environ 48 がトラップにする
* AGP 8.9 + KGP 2.2 + Gradle 8.12 でのみ検証。AGP 9 / Gradle 9 は未確認
* Gradle デーモンは JDK 21 で動かす必要がある（tooling と plugin が JVM 21 向けで、Gradle 8.12 は JDK 25 上で動かない）。`gradle/gradle-daemon-jvm.properties`（ルートと `samples/android`）で指定済み
* 成果物はまだどこにも公開していない（functionalTest 用のローカル `build/test-repo` のみ）。当面は公開しない方針
* 実行時間とメモリの上限、実行中の中断はない。読み込むコンポーネントは信頼できるものに限る（[README](../README.md) の「安全性の前提」、#77）
* integration-tests には `wasm-tools`（CI は 1.258.0 に固定）と、ビルド済みの `rust-bridge` が必要

## 3. アクションアイテム

アクションアイテムは **GitHub Issues** で管理します。進捗・議論・担当者は Issue 側が正本で、ここには一覧だけを置きます。
優先度はラベル `P0`（すぐ）/ `P1`（公開・運用の前提）/ `P2`（機能拡張）、分野は `area:*` ラベルで表します。
新しい作業は Issue を作り、重要なものだけこの一覧にも追加してください。ID（`A-n`）は過去の記録との対応のために残しています。

* [未完了の Issue 一覧](https://github.com/chikoski/wasm-component-kotlin/issues)
* [P0 の Issue](https://github.com/chikoski/wasm-component-kotlin/issues?q=is%3Aissue+is%3Aopen+label%3AP0)

### P0

なし

### P1

QC レビュー（2026-10-31）で見つかったものは、すべて片付けました（「完了」を参照）。

QC レビュー（2026-11-04）で見つかったものも片付けました（「完了」を参照）。#77 は、ドキュメントで前提を明記し、上限の実装を P2 に残しています。

WASI 0.3 の P1 スコープも #13 と #100〜#102 で完結しました。「完了」を参照してください。

なし

### P2

| Issue | ID | 内容 |
| :--- | :--- | :--- |
| [#117](https://github.com/chikoski/wasm-component-kotlin/issues/117) | — | WASI 0.3 `wasi:http` ホスト実装の追加。[ADR-0022](adr/0022-wasi03-scope-and-host-configuration.md) で P2 へ分離 |
| [#118](https://github.com/chikoski/wasm-component-kotlin/issues/118) | — | WASI 0.3 `wasi:sockets` ホスト実装の追加。[ADR-0022](adr/0022-wasi03-scope-and-host-configuration.md) で P2 へ分離 |
| [#77](https://github.com/chikoski/wasm-component-kotlin/issues/77) | — | 信頼できないコンポーネントの実行時間とメモリの上限・中断。前提は README の「安全性の前提」に明記した（#87）。実装は後で優先度を見直す |
| [#71](https://github.com/chikoski/wasm-component-kotlin/issues/71) | — | `map<K, V>` 型に対応するかを決める（公式テストで発見） |
| [#20](https://github.com/chikoski/wasm-component-kotlin/issues/20) | A-17 | AGP 9 / Gradle 9 対応の確認 |
| [#10](https://github.com/chikoski/wasm-component-kotlin/issues/10) | A-7 | Maven Central / Gradle Plugin Portal への公開準備（当面は公開しない）。LICENSE、Dokka、英語版（#50 の残り）と、rust-bridge の複数プラットフォーム向けのビルド（#59 を統合）も含む |

### 閉じたもの（対応しない）

2026-11-12 の Issue の見直しで閉じたもの:

* [#60](https://github.com/chikoski/wasm-component-kotlin/issues/60) Android で Wasmtime バックエンドを使う — 使えるようにしない。Android は Chicory（[ADR-0001](adr/0001-chicory-backend-for-android.md)）、Wasmtime はデスクトップ / サーバー JVM 向けの同期の参照実装（[ADR-0005](adr/0005-async-and-wasi03-chicory-only.md)）。`JniWasmtimeBridge` の Android の記述は削除
* [#59](https://github.com/chikoski/wasm-component-kotlin/issues/59) rust-bridge の複数プラットフォーム向けのビルド — 公開するときだけ必要なので #10 に統合
* [#68](https://github.com/chikoski/wasm-component-kotlin/issues/68) 数値だけの関数をバイト列を通さずに呼ぶ — 必要な使い方がないため。案は [ADR-0015](adr/0015-binary-value-boundary.md) の進め方の 3 に残っている

### 完了

* Rust クレートに対する差分ミューテーションテストの導入と境界テスト補強
* ランタイムと WASI 0.3 の状態機械・有界並行性・資源寿命テストの追加および書込ストリーム始動前キャンセルの修正
* ツール群と仕様適合テストの境界検査および Android サンプルの録画後始末の修正
* コード生成の名前解決と Rust ブリッジの資源所有権修正
* ランタイムと WASI 0.3 の安定性と境界制御の改善
* 合成コンポーネントの共有テーブルと借用貸与追跡
* ART における文字列と境界呼び出しの最適化と再計測
* 大規模メソッド分割後の制約と回避策の文書化
* プルリクエストテンプレートの整備

[#166](https://github.com/chikoski/wasm-component-kotlin/issues/166) では `rust-bridge` と `decomposer` へ `cargo-mutants` を導入しました。各ワークスペースに `.cargo/mutants.toml` を配置しています。FFI と JNI のエントリポイント群および `main` 関数は検査対象から除外しました。`rust-bridge` の `wire.rs`、`typed.rs`、および `lib.rs` では境界値の単体テストを補強しました。対象の純粋ヘルパーとエラー境界関数について変異体がテストで検出されることも確認しています。`decomposer` では隔離作業ディレクトリからもフィクスチャを参照できるよう `WASM2KT_FIXTURES` の解決を追加しました。あわせて開発手順とレビュー手順へ差分ミューテーション検査の運用を組み込んでいます。

[#159](https://github.com/chikoski/wasm-component-kotlin/issues/159) では外部依存を増やさずに JUnit とコルーチン上で動作する状態機械テストと有界並行性テストを追加しました。[#160](https://github.com/chikoski/wasm-component-kotlin/issues/160) では `HostResourceTable` の借用規則と `SessionLoop` のハンドル回収やポイズン遷移を参照モデルと照合しています。[#161](https://github.com/chikoski/wasm-component-kotlin/issues/161) では単一ルートスレッド排他や逆圧、遅延 `newStream` 解放、デッドロック検知、待機セッション終了を確認しました。[#162](https://github.com/chikoski/wasm-component-kotlin/issues/162) では `WasiPathDescriptor` のサンドボックス検査とストリーム解放を検証しました。あわせて `writeViaStream` と `appendViaStream` が開始前キャンセル時に `Flow` を閉じない不具合を修正しています。

[#136](https://github.com/chikoski/wasm-component-kotlin/issues/136) では `JvmMethodSplitter`、`PlanCheck`、`decomposer`、および `ConformanceTest` の境界処理を修正しました。`JvmMethodSplitter` では非静的メソッドのスロット 0 にオーナー型を設定し、2 スロット変数の重複検査と定数プッシュの `LdcInsnNode` 分岐を加えています。`PlanCheck` では `streamTables`、`futureTables`、および `task-return` の結果型を走査対象へ含めました。`decomposer` では `task.return` の結果型に含まれる `borrow` を拒否するようにし、`conformance/skip.txt` の該当 2 件を削除しています。`ConformanceTest` では実行完了時とタイムアウト時のスレッド中断とインスタンス解放を追加しました。

[#138](https://github.com/chikoski/wasm-component-kotlin/issues/138) では `samples/android-glitch-camera` の録画停止ループ、`GlitchEngine.close` の競合、および一時ファイルの残留を修正しました。`Mp4VideoRecorder` では 0 フレーム終了時の送信省略、EOS 待機回数の上限、初期化失敗時の解放、および未確定出力の削除を実装しています。`GlitchEngine` では `closed` フラグとバックグラウンド解放によりメインスレッドの停止と再生成を防ぎました。`MediaStoreHelper` と `GlitchViewModel` では失敗時の保留レコードと一時ファイルを確実に削除します。

[#134](https://github.com/chikoski/wasm-component-kotlin/issues/134) と [#135](https://github.com/chikoski/wasm-component-kotlin/issues/135) では、コード生成の名前解決と Wasmtime 向け Rust ブリッジの資源管理を修正しました。`WitToKotlinPoetConverter` ではリソースハンドルへの型エイリアスを再帰的に解決し、引数やメンバー名の衝突回避と `equals` の修飾を追加しました。ワールド直下の `use` と推移的依存のホストリソースも、コード生成と `TypedBridge` の双方で契約対象へ含めています。`rust-bridge` では引数検証後に移動済みハンドルを確定し、ゲストトラップ時に消費済み `ResourceAny` を復元しないよう改めました。あわせて 65 ビット以上の `flags` 読み取りと重複ハンドル時のエラー文言も揃えています。

[#130](https://github.com/chikoski/wasm-component-kotlin/issues/130) と [#131](https://github.com/chikoski/wasm-component-kotlin/issues/131) では、ホスト資源表の貸出スコープ管理と非同期引数変換のロールバックを修正しました。`HostResourceTable` ではテーブル単位のインスタンスと参照同一性でスコープを管理します。`witFuture` 取り消し時に起きる読み取り側解除との競合と、`WitWriter` の明示的な初期容量の保持も改善しました。非同期エクスポートで引数変換に失敗した際も、実行中の他の呼び出しへ影響させずその呼び出しのみを失敗させます。ロールバックは `stream` と `future` に加えて `error-context` のハンドルも対象とします。

[#132](https://github.com/chikoski/wasm-component-kotlin/issues/132) と [#133](https://github.com/chikoski/wasm-component-kotlin/issues/133) では、内部型の非公開化と WASI 0.3 の入出力およびファイルシステム境界を修正しました。`WitType` と `ComponentPlan` の内部構文木型を `internal` に制限しています。`WasiStdin.readViaStream` の完了 `Deferred` は読み切りまたはクローズ時に完了するよう改めました。`WasiStdin` では出力先で生じた `IOException` を捕捉せず下流へ伝播します。遅延起動の `writeViaStream` と `newStream` は、開始前の取り消し時にも `AutoCloseable` な `Flow` を閉じます。`WasiFilesystem` では末尾スラッシュ判定とキャンセル時のチャネル解放を改めました。さらに `readlinkAt`、`unlinkFileAt`、および `renameAt` で境界外やルート自身を指定した場合は `ErrorCode.NotPermitted` を返します。

[#70](https://github.com/chikoski/wasm-component-kotlin/issues/70) では、`ChicoryComponent` に共有テーブルビューとアダプターモジュールを導入しました。これにより、異なる型インデックスを持つモジュール間の間接呼び出しとデストラクター呼び出しを解決しています。あわせて `AsyncAbi` と `Task` に借用中の貸与追跡を追加し、同期トラップ時の `StackSwitching` 後始末も入れました。その結果、`resources/` の公式テストは 36 件すべてが通過するようになり、`conformance/skip.txt` から該当 2 件を削除しています。

[#58](https://github.com/chikoski/wasm-component-kotlin/issues/58) では、Android 16 ARM64 エミュレーター上のメソッドトレース解析を踏まえ、`runtime-chicory` の境界処理を最適化しました。`WitType` と `FuncType` のレイアウト情報を遅延キャッシュし、`Lowerer` と `Lifter` の一時確保を減らしています。`WitType` は `sealed interface` から `sealed class` へ変更し、その後の [#132](https://github.com/chikoski/wasm-component-kotlin/issues/132) によって `internal` へ移行しました。あわせて `ChicoryComponentInstance` でのエクスポート解決結果の保持と `Context` コレクションの遅延生成、および `Transcoder` からの `CharsetDecoder` と中間 `String` 確保の削除を行いました。その結果、同じエミュレーター上で `echoName5_aot_dsl` は最小値 8,309 ns と 113.0 回のアロケーションから、最小値 770.71 ns と 26.0 回のアロケーションへ変化しました。`echoBytes16_aot_dsl` との差は最小値で 49.13 ns に縮まり、`#58` の課題は完了しています。変更後は Apple Silicon の Host JVM 上で `echo-name` が 355 ns となることも確認しました。ローカル機には変更前の Host JVM 計測値がないため単独の参照値とし、セクション 3.1 の CI 表全体の更新や実機計測は通常運用の課題として分けています。詳細は [performance.md](explanation/performance.md) に記載しています。

[#57](https://github.com/chikoski/wasm-component-kotlin/issues/57) では、`JvmMethodSplitter` による分割後も残る制約と回避策を [architecture.md](explanation/architecture.md) と [gradle-plugin.md](reference/gradle-plugin.md) へ明文化しました。対象は未分割の `call_dispatch_0` と Chicory の 64 KB 上限および `-XX:-DontCompileHugeMethods` です。これにより [#106](https://github.com/chikoski/wasm-component-kotlin/issues/106) と [ADR-0021](adr/0021-split-large-chicory-methods.md) の実装とあわせて #57 の全項目を完了しました。

[#95](https://github.com/chikoski/wasm-component-kotlin/issues/95) では、PR テンプレートを `.github/pull_request_template.md` に追加し、`CONTRIBUTING.md` から参照しました。

* 設計・実装レビュー改善と E2E 検証の拡充

[#124](https://github.com/chikoski/wasm-component-kotlin/issues/124) では `runtime-api` に共通インタフェース `WitResultValue` を導入しました。生成される `WitResult` がこの契約を実装し、`runWasiCommand` で直接受け取れるようにしています。[#125](https://github.com/chikoski/wasm-component-kotlin/issues/125) では同期呼び出し向けに非インラインの `withSessionBlocking` ヘルパーを生成しました。これに合わせて `examples/06-async-streams` と `examples/07-wasi-cli` のコードも簡潔に更新しています。

[#126](https://github.com/chikoski/wasm-component-kotlin/issues/126) では `ComponentExitException` を導入し、Chicory ランタイムで終了後再利用とトラップ後再利用の診断を分けました。[#127](https://github.com/chikoski/wasm-component-kotlin/issues/127) では `wasi-cli` ゲストフィクスチャへ最長一致マウント解決と終了フラグを追加し、異常系と終了系および部分ストリーム消費の E2E テストを整備しました。[#128](https://github.com/chikoski/wasm-component-kotlin/issues/128) では重複していたフィクスチャ WIT 定義を `runtime-wasi/wit` へ統合しました。さらに `ComponentBuildTest` へ事前分解コンポーネントのドリフト検証を追加しています。

* [#13](https://github.com/chikoski/wasm-component-kotlin/issues/13) WASI 0.3 ホスト実装と設定 API の完結。[#98](https://github.com/chikoski/wasm-component-kotlin/issues/98) と [#99](https://github.com/chikoski/wasm-component-kotlin/issues/99) の各パッケージ実装に加え、[#100](https://github.com/chikoski/wasm-component-kotlin/issues/100) の `wasi:http` と `wasi:sockets` の対応方針決定、[#101](https://github.com/chikoski/wasm-component-kotlin/issues/101) のホスト設定・選択 API と Gradle `addWasiDependency` 連携、[#102](https://github.com/chikoski/wasm-component-kotlin/issues/102) の Rust 製フィクスチャ `wasi-03` と結合テストを完了しました。設計詳細は [ADR-0020](adr/0020-wasi03-filesystem-sandboxing-and-mounts.md) と [ADR-0022](adr/0022-wasi03-scope-and-host-configuration.md) に記録しています。`wasi:http` と `wasi:sockets` はそれぞれ [#117](https://github.com/chikoski/wasm-component-kotlin/issues/117) と [#118](https://github.com/chikoski/wasm-component-kotlin/issues/118) として P2 に分離しました。

* [#106](https://github.com/chikoski/wasm-component-kotlin/issues/106) ART でコンパイル上限を超える大きな wasm 関数の対策 — [ADR-0021](adr/0021-split-large-chicory-methods.md)。`JvmMethodSplitter` による 7,500 バイト単位のバイトコード自動分割と、HotSpot 8,000 バイト / ART 16,383 DEX コード単位 / 64 KB インタプリタのビルド時警告を実装。`samples/android-glitch-camera` で `dex2oat64` 上限超過を 4 件から 0 件へ解消

* [#16](https://github.com/chikoski/wasm-component-kotlin/issues/16) Component Model Async（Chicory のみ）— [ADR-0018](adr/0018-async-abi-on-chicory.md)（参照定義の移植、継続をスレッドで表す。plan v5。生成コードの `suspend fun` / `Flow` / `Deferred`。公式テストの `async/`。fused adapter の resource の受け渡しも実装し、共有テーブル経由のデストラクターは別項の #70 で完了）
* [#50](https://github.com/chikoski/wasm-component-kotlin/issues/50) ドキュメントの組み直し（読者別の構成、古い記述の修正、`examples/05-chicory-gradle-plugin` の追加、`examples/*/Main.kt` のコンパイル・実行検査と Markdown 相対リンク検査の CI 組み込み）— #89、#90（KDoc / Dokka、LICENSE、英語版は #10、PR テンプレートは #95 へ移した）
* [#30](https://github.com/chikoski/wasm-component-kotlin/issues/30) Component Model の公式テストスイートの取り込み — #72（[ADR-0017](adr/0017-component-model-conformance-suite.md)）
* [#63](https://github.com/chikoski/wasm-component-kotlin/issues/63) 分解タスクがまれに `Stream closed` で失敗する — #73（jar のキャッシュを使わずに読む。原因は推定）
* [#15](https://github.com/chikoski/wasm-component-kotlin/issues/15) JSON 境界をやめ、型に沿ったバイナリ形式と Wasmtime の型付きブリッジにする — #62（[ADR-0015](adr/0015-binary-value-boundary.md)）、#64（[ADR-0016](adr/0016-typed-bridge-from-wit-json.md)）、#67（#65。ライブラリをクラスパスから読み込む）。Gradle プラグインでの Wasmtime の対応は見送り
* [#19](https://github.com/chikoski/wasm-component-kotlin/issues/19) 性能・サイズの計測 — [docs/performance.md](performance.md)（`benchmarks/`、`samples/android/benchmark`、`Bench` ワークフロー、`heavy` フィクスチャ）

QC レビュー（2026-11-04）で見つかったもの:

* [#75](https://github.com/chikoski/wasm-component-kotlin/issues/75) 同じセッションを複数のスレッドから使うと、ホスト resource の貸し出しが混ざる — #82（`HostResourceTable` の束縛も直列化の中で行う）
* [#76](https://github.com/chikoski/wasm-component-kotlin/issues/76) ゲスト resource のハンドルを検証する（閉じたもの・別セッションのもの）。own は呼び出しが成功してから手放す — #83
* [#79](https://github.com/chikoski/wasm-component-kotlin/issues/79) 型付きブリッジの `cargo build` に `--locked` とタイムアウト — #84
* [#80](https://github.com/chikoski/wasm-component-kotlin/issues/80) `runtime-api` / `runtime-chicory` を Android API 26 のシグネチャで検査する（animal-sniffer）— #85
* [#78](https://github.com/chikoski/wasm-component-kotlin/issues/78) ビルド時の警告を UP-TO-DATE / FROM-CACHE のときも出す — #86（警告をファイルに出力し、キャッシュしないタスクで表示）
* [#77](https://github.com/chikoski/wasm-component-kotlin/issues/77) のうちドキュメント（コンポーネントは信頼できるものに限る）— #87。上限の実装は P2 に残す

QC レビュー（2026-10-31）で見つかったもの:

* [#31](https://github.com/chikoski/wasm-component-kotlin/issues/31) Chicory の own ハンドルの受け渡しを仕様どおりにする — #40（decomposer の出力の決定性も修正）
* [#33](https://github.com/chikoski/wasm-component-kotlin/issues/33) `.cwasm` を明示的な入口からだけ読み込む — #41
* [#37](https://github.com/chikoski/wasm-component-kotlin/issues/37) 所有権を移したホスト resource の再度の own / borrow を拒否する — #42
* [#35](https://github.com/chikoski/wasm-component-kotlin/issues/35) FFM のホスト import の戻り値の 64 KiB 制限をなくす — #43
* [#36](https://github.com/chikoski/wasm-component-kotlin/issues/36) JSON 境界のスカラーの型の検査と option のタグの判定 — #44、[ADR-0009](adr/0009-json-boundary-option-tag-and-scalar-types.md)
* [#38](https://github.com/chikoski/wasm-component-kotlin/issues/38) wasm-tools と Rust の版の固定、Rust の clippy / test、generator のユニットテスト — #45
* [#39](https://github.com/chikoski/wasm-component-kotlin/issues/39) Chicory 未対応の機能をビルド時に報告する — #46
* [#54](https://github.com/chikoski/wasm-component-kotlin/issues/54) 既存の package の interface を使う対応表（wit-bindgen の `with`）— [ADR-0014](adr/0014-interface-mappings.md)（`--with`、`--interfaces-only` とマニフェスト、Gradle プラグインの `with(...)` と依存関係からの自動設定）
* [#47](https://github.com/chikoski/wasm-component-kotlin/issues/47) メモリを持つ子コンポーネント同士の合成（decomposer の panic）— [ADR-0013](adr/0013-multi-memory-adapters.md)（multi-memory、plan v4 の `trap` / `transcode` トランポリン、Chicory の transcoder）
* world を package のトップレベルに生成し、export した interface はマーカーを context に取る — [ADR-0012](adr/0012-world-at-package-top-level.md)（ADR-0011 で残った、export していない interface を呼べる制約を解消）
* [#32](https://github.com/chikoski/wasm-component-kotlin/issues/32) interface を完全修飾名で識別し、Kotlin では WIT の構造を package に写す — [ADR-0011](adr/0011-qualified-interface-keys.md)（`Linker` と型付きの `imports(...)`、`WitSession`）
* [#34](https://github.com/chikoski/wasm-component-kotlin/issues/34) decomposer と rust-bridge を Wasmtime 48 LTS に揃える — [ADR-0010](adr/0010-wasmtime-lts.md)（plan v3、import した resource 型ごとの動的なホスト型、ブリッジの整数の範囲検査）

それ以前:

* [#14](https://github.com/chikoski/wasm-component-kotlin/issues/14) ホスト定義 resource — [ADR-0008](adr/0008-host-defined-resources.md)（`WitHostResource` の interface と `Imports` の constructor / static、`HostResourceTable`、Chicory と Wasmtime ブリッジの両方、plan v2。現在は v4）
* [#18](https://github.com/chikoski/wasm-component-kotlin/issues/18) Canonical ABI の検証の厳密化 — [ADR-0007](adr/0007-canonical-abi-validation.md)（`ComponentTrapException` と poison、`may_enter` / `may_leave`、ポインタ・長さ・エンコーディング・判別子・`char` の検査、ハンドルの貸し出し数、ホストの値の範囲検査、NaN の正規化）
* [#17](https://github.com/chikoski/wasm-component-kotlin/issues/17) 型マッピングの見直し — [ADR-0006](adr/0006-type-mapping.md)（option → `T?`、`list<u8>` → `ByteArray`、char → `CodePoint`、world 名、`WitResult` の補助関数、WIT シグネチャの KDoc）。async の型は #16 で実装
* [#11](https://github.com/chikoski/wasm-component-kotlin/issues/11) ルートのモジュール分割 — ast / generator / cli / runtime-wasmtime / integration-tests、fixtures を共有ディレクトリに
* [#8](https://github.com/chikoski/wasm-component-kotlin/issues/8) GitHub Actions で CI — #23
* [#12](https://github.com/chikoski/wasm-component-kotlin/issues/12) `decomposer/dist` の検証 — 再現可能ビルド + CI の decomposer ジョブ
* [#5](https://github.com/chikoski/wasm-component-kotlin/issues/5) ART での動作確認 — ヘッドレスエミュレーターで debug / release とも確認（2026-10-31）
* [#6](https://github.com/chikoski/wasm-component-kotlin/issues/6) `feat/gradle-plugin` を `main` にマージ — #21
* [#7](https://github.com/chikoski/wasm-component-kotlin/issues/7) 計画メモ 2 件の扱い — `docs/notes/` にコミット
* [#9](https://github.com/chikoski/wasm-component-kotlin/issues/9) `org.gradle.java.home` の固定を外す — デーモン JVM 指定に置き換え
* A-3 ローカルの `main` を `origin/main` に同期する — `feat/chicory-backend` は PR #4 でマージ済み。手元で `git switch main && git pull` するだけなので、Issue にはしていません

## 4. 既存ドキュメントの位置づけ

全体の目次は [`docs/index.md`](index.md) にあります（#50 で読者別の構成に再編）。

| ファイル / ディレクトリ | 位置づけ |
| :--- | :--- |
| `README.md`、`docs/index.md` | 概要・読者別の入口 |
| `docs/tutorials/`（`android-quickstart.md`、`jvm-quickstart.md`、`wasmtime-quickstart.md`） | チュートリアル（バックエンド・環境別の導入手順） |
| `docs/guides/`（`choosing-a-backend.md`、`host-imports.md`、`resources-and-ownership.md`、`errors-and-traps.md`、`testing.md`、`authoring-components.md`、`troubleshooting.md`） | 目的別のハウツーガイド |
| `docs/reference/`（`type-mapping.md`、`gradle-plugin.md`、`cli.md`、`feature-support.md`、`compatibility.md`） | リファレンス（型対応表、DSL、CLI、対応機能、バージョン要件） |
| `docs/explanation/`（`architecture.md`、`build-pipeline.md`、`call-path.md`、`performance.md`） | 内部構造・ビルド順序・呼び出し経路・性能とサイズの計測結果 |
| `CONTRIBUTING.md`、`CHANGELOG.md` | 開発手順・不変条件（人間向け）、および破壊的変更・主要変更の記録 |
| `docs/adr/` | 設計判断の記録（追記のみ） |
| `docs/notes/`（[`phase1-implementation-tasks.md`](notes/phase1-implementation-tasks.md)、[`wit-async-guide.md`](notes/wit-async-guide.md)、[`android-glitch-camera-demo.md`](notes/android-glitch-camera-demo.md)） | 作業計画・async 調査メモ（#16 の前提資料）・Android カメラグリッチデモ設計メモ |
| `docs/archive/`（`SPEC.md`、`SPEC_REVIEW.md`、`ANDROID_INTEGRATION_PLAN.md`、`CODE_REVIEW.md`、`TEST_PROPOSALS.md`） | 過去の記録（プロジェクト初期の仕様書・レビュー記録） |
