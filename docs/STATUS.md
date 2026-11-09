# プロジェクトの現状とアクションアイテム

> 最終更新: 2026-11-04 / 対象ブランチ: `main`
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
| `tooling` | 分解・AOT コンパイル・コード生成のパイプライン | 21 | 稼働 |
| `gradle-plugin` | `io.github.wasm2kt` プラグイン | 21 | 稼働 |
| `samples/android` | Android サンプルアプリ（composite build） | 11 | ART（エミュレーター）で debug / release とも動作確認済み |

## 2. 現在の状態

### 検証済み

* `./gradlew check`（TestKit の functionalTest と generator のユニットテストを含む）と integration-tests: すべて成功。1 件だけスキップ（wasm-tools が 33 個以上の flags を拒否するため。仕様どおり）。CI ではこれに加えて、decomposer と rust-bridge の `cargo clippy` と `cargo test` を実行
* Component Model の公式テストスイート（`values/`、`resources/`。[ADR-0017](adr/0017-component-model-conformance-suite.md)、#30）: Chicory で 191 のコマンドのうち 168 が通る。残りは `conformance/skip.txt` に理由つきで載せている（#70 合成したコンポーネントの間の resource、#71 `map`、#16 async）
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
* 性能とサイズ（[docs/performance.md](performance.md)、#19。バイナリ形式のあとは 8 節）: 64 KiB の `list<u8>` の往復は JVM（Chicory AOT）12 µs / Wasmtime FFM 3.5 ms / ART 0.3 ms（JSON 境界のときは 6.6 ms / 16.5 ms / 52 ms）。Wasmtime の型付きブリッジでは 29 µs（同じランナーの汎用ブリッジは 5.7 ms。9 節）。整数だけの呼び出しは JVM 0.19 µs / ART 2〜6 µs。小さな関数の計算は Wasmtime と同等、大きな wasm 関数を含むコード（regex）は HotSpot の JIT の上限とインタプリタへのフォールバックのため最大 53 倍遅い。初回の `openSession` は ART で 57〜239 ms。APK はランタイムで +181 KB
* Android サンプル: ホスト JVM のユニットテスト、ART（ヘッドレスエミュレーター）での `connectedAndroidTest`、R8 で縮小した release 版の起動がいずれも成功（初回実行 2 ms）。手順は [samples/android/README.md](../samples/android/README.md)

### 未検証・既知の制約

* 実機（物理端末）での確認はまだ。エミュレーターでは確認済み
* Chicory バックエンドの未対応機能: async（WASI P3）、WASI のホスト実装、子コンポーネントの間で resource を受け渡す合成コンポーネント。いずれも使用時に例外になる。plan から分かるものは、Gradle プラグインがビルド時に警告する（`failOnUnsupportedFeatures` でエラーにもできる。#39）
* 同じ package の異なるバージョン（`wasi:http@0.2.0` と `@0.3.0`）を 1 回の生成で使うとエラーになる（Kotlin の package にバージョンを含めないため。[ADR-0011](adr/0011-qualified-interface-keys.md)）
* 1 回の生成で扱う world は 1 つ（world の API を package のトップレベルに置くため。[ADR-0012](adr/0012-world-at-package-top-level.md)）。複数の world は `--world` で選び、別々の package に生成する。import した interface の型は、`--interfaces-only` と `--with` で world の間で共有できる（[ADR-0014](adr/0014-interface-mappings.md)）
* 既存の package への対応付け（`with`）は import した interface だけ。export した interface の型は、world ごとに生成される（ADR-0014）
* ホスト resource の受け渡しは、セッションの呼び出し中（同じスレッド）に限られる。Wasmtime ではホストの borrow を一時的な所有ハンドルで代用している（48 でも必要。[ADR-0008](adr/0008-host-defined-resources.md)、[ADR-0010](adr/0010-wasmtime-lts.md)）
* コンポーネント定義の resource を、コンポーネントがホスト import に own / borrow で渡す経路は未対応（Chicory）
* async と WASI 0.3 は Chicory バックエンドだけで実装する方針。Wasmtime バックエンドは同期の機能の参照実装として維持（[ADR-0005](adr/0005-async-and-wasi03-chicory-only.md)）
* AGP 8.9 + KGP 2.2 + Gradle 8.12 でのみ検証。AGP 9 / Gradle 9 は未確認
* Gradle デーモンは JDK 21 で動かす必要がある（tooling と plugin が JVM 21 向けで、Gradle 8.12 は JDK 25 上で動かない）。`gradle/gradle-daemon-jvm.properties`（ルートと `samples/android`）で指定済み
* 成果物はまだどこにも公開していない（functionalTest 用のローカル `build/test-repo` のみ）。当面は公開しない方針
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

**QC レビュー（2026-11-04）で見つかったもの**を先に片付けます。async（#16）は、調査が済むまで止めています（2026-11-04 に決定）。優先度の見直しの理由は、各 Issue のコメントにあります。

| 順 | Issue | 優先度 | 内容 |
| :--- | :--- | :--- | :--- |
| 1 | [#75](https://github.com/chikoski/wasm-component-kotlin/issues/75) | P1 | 同じセッションを複数のスレッドから使うと、ホスト resource の貸し出しが別の呼び出しのものとして扱われる（`bind` が直列化の外にある） |
| 2 | [#76](https://github.com/chikoski/wasm-component-kotlin/issues/76) | P1（P2 から上げた） | 生成コードがゲスト resource のハンドルを検証しない。別のセッションの resource が、黙って別のものとして使われる。own は呼び出しの前に手放される |
| 3 | [#79](https://github.com/chikoski/wasm-component-kotlin/issues/79) | P2 | 型付きブリッジの `cargo build` が `--locked` を使わず、タイムアウトも効かない（修正が小さいので早めに） |
| 4 | [#80](https://github.com/chikoski/wasm-component-kotlin/issues/80) | P2 | `runtime-api` / `runtime-chicory` を Android の API レベル（minSdk 26）で検査していない |
| 5 | [#78](https://github.com/chikoski/wasm-component-kotlin/issues/78) | P2 | ビルド時の警告が、2 回目以降のビルド（UP-TO-DATE、FROM-CACHE）で出ない |
| 6 | [#77](https://github.com/chikoski/wasm-component-kotlin/issues/77) | P2 | 実行時間とメモリの制限がない。まずドキュメントに明記し、実装は後で優先度を見直す |

そのあと、async と WASI 0.3 に向けて、上から順に進めます（[ADR-0005](adr/0005-async-and-wasi03-chicory-only.md)）。公式テストスイート（#30）は取り込み済みで、#16 の受け入れ基準に使います。

| 順 | Issue | ID | 内容 |
| :--- | :--- | :--- | :--- |
| 1 | [#16](https://github.com/chikoski/wasm-component-kotlin/issues/16) | A-13 | Component Model Async（Chicory のみ）。調査が済むまで止めている |
| 2 | [#13](https://github.com/chikoski/wasm-component-kotlin/issues/13) | A-10 | WASI 0.3 ホスト実装（Chicory のみ。0.2 の poll は実装しない。ライブラリとして配る仕組みは #54 で用意済み） |

並行して進めるもの:

| Issue | 内容 |
| :--- | :--- |
| [#50](https://github.com/chikoski/wasm-component-kotlin/issues/50) | ドキュメントの組み直し（読者別の構成。古い記述の修正） |

### P2

| Issue | ID | 内容 |
| :--- | :--- | :--- |
| [#68](https://github.com/chikoski/wasm-component-kotlin/issues/68) | — | 数値だけの関数をバイト列を通さずに呼ぶ（ADR-0015 の進め方の 3。必要になってから） |
| [#57](https://github.com/chikoski/wasm-component-kotlin/issues/57) | — | 大きな wasm 関数が JIT されない・インタプリタに回る問題への対策（#19 で発見） |
| [#58](https://github.com/chikoski/wasm-component-kotlin/issues/58) | — | ART で文字列の受け渡しが遅い。#62 で 154 µs → 8〜29 µs になったが、まだ `list<u8>` より遅い |
| [#70](https://github.com/chikoski/wasm-component-kotlin/issues/70) | — | Chicory: 合成したコンポーネントの間で resource を受け渡す組み込み関数（`ResourceTransferOwn` など。公式テストで発見） |
| [#71](https://github.com/chikoski/wasm-component-kotlin/issues/71) | — | `map<K, V>` 型に対応するかを決める（公式テストで発見） |
| [#20](https://github.com/chikoski/wasm-component-kotlin/issues/20) | A-17 | AGP 9 / Gradle 9 対応の確認 |
| [#10](https://github.com/chikoski/wasm-component-kotlin/issues/10) | A-7 | Maven Central / Gradle Plugin Portal への公開準備（当面は公開しない） |

### 完了

* [#30](https://github.com/chikoski/wasm-component-kotlin/issues/30) Component Model の公式テストスイートの取り込み — #72（[ADR-0017](adr/0017-component-model-conformance-suite.md)）
* [#63](https://github.com/chikoski/wasm-component-kotlin/issues/63) 分解タスクがまれに `Stream closed` で失敗する — #73（jar のキャッシュを使わずに読む。原因は推定）
* [#15](https://github.com/chikoski/wasm-component-kotlin/issues/15) JSON 境界をやめ、型に沿ったバイナリ形式と Wasmtime の型付きブリッジにする — #62（[ADR-0015](adr/0015-binary-value-boundary.md)）、#64（[ADR-0016](adr/0016-typed-bridge-from-wit-json.md)）、#67（#65。ライブラリをクラスパスから読み込む）。Gradle プラグインでの Wasmtime の対応は見送り
* [#19](https://github.com/chikoski/wasm-component-kotlin/issues/19) 性能・サイズの計測 — [docs/performance.md](performance.md)（`benchmarks/`、`samples/android/benchmark`、`Bench` ワークフロー、`heavy` フィクスチャ）

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
