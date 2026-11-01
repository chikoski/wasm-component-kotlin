# プロジェクトの現状とアクションアイテム

> 最終更新: 2026-11-01 / 対象ブランチ: `main`
>
> このファイルは「今どうなっていて、次に何をやるか」を書く場所です。変わるたびに更新してください。
> 使い方の説明は [README](../README.md) と [docs/](.)、設計判断の理由は [docs/adr/](adr/) に置き、ここでは重複させずにリンクします。

## 1. 全体像

`wasm2kt` は、WebAssembly コンポーネント（WIT）から型安全な Kotlin ホストバインディングを生成するプロジェクトです。
生成されるワールド DSL は共通で、実行バックエンドを 2 つ持ちます。

| バックエンド | 実行方式 | 主な用途 | 入口 |
| :--- | :--- | :--- | :--- |
| Wasmtime | Rust 製ブリッジ（FFM / JNI）でネイティブ実行 | デスクトップ / サーバー JVM | `World.withSession(path)` |
| Chicory | core モジュールをビルド時に JVM バイトコードへコンパイルし、純 JVM で実行 | **Android**、ネイティブ不可の環境 | `World.withSession(XxxComponent)` |

```text
component.wasm ──(Gradle プラグイン)──▶ 分解（wasm 版 decomposer を Chicory で実行）
                                        ├─▶ core モジュールの AOT コンパイル（jar）
                                        └─▶ Kotlin バインディング + XxxComponent 生成
アプリ ──▶ 生成 DSL ──▶ ComponentBackend（JSON 境界）──▶ runtime-chicory ──▶ Chicory
                                                    └─▶ Wasmtime ブリッジ（FFM / JNI）
```

詳細: [Chicory バックエンドと Gradle プラグイン](chicory-backend.md) / [アーキテクチャ（Wasmtime 側）](architecture.md)

### モジュール構成

| モジュール | 役割 | JVM | 状態 |
| :--- | :--- | :--- | :--- |
| `ast` | WIT JSON のモデル | 21 | 稼働 |
| `generator` | KotlinPoet によるバインディング生成 | 21 | 稼働 |
| `cli` | `wasm2kt` コマンド | 21 | 稼働 |
| `runtime-wasmtime` | Wasmtime ブリッジの Kotlin 側（FFM / JNI） | 21 | 稼働 |
| `integration-tests` | CLI・生成・両ランタイムの E2E テストと差分テスト | 21 | 稼働 |
| `rust-bridge/` | Wasmtime ブリッジ（Rust, cdylib） | — | 稼働 |
| `decomposer/` | コンポーネント分解ツール（Rust）。`dist/` に wasm 版を同梱 | — | 稼働 |
| `runtime-api` | `ComponentBackend` などバックエンド共通の契約 | 11 | 稼働 |
| `runtime-chicory` | Chicory バックエンド（Canonical ABI 実装） | 11 | 稼働 |
| `tooling` | 分解・AOT コンパイル・コード生成のパイプライン | 21 | 稼働 |
| `gradle-plugin` | `io.github.wasm2kt` プラグイン | 21 | 稼働 |
| `samples/android` | Android サンプルアプリ（composite build） | 11 | ART（エミュレーター）で debug / release とも動作確認済み |

## 2. 現在の状態

### 検証済み

* `./gradlew check`（TestKit の functionalTest と generator のユニットテストを含む）と integration-tests: すべて成功。1 件だけスキップ（wasm-tools が 33 個以上の flags を拒否するため。仕様どおり）。CI ではこれに加えて、decomposer と rust-bridge の `cargo clippy` と `cargo test` を実行
* Chicory と Wasmtime の差分テスト: rich types / host import / resource のライフサイクル / Canonical ABI のトラップ（不正な char・判別子・文字列、範囲外やアラインされていないポインタ、`cabi_realloc` の結果、`may_leave`、poison、borrow 中のハンドルの move）で結果が一致
* ホスト定義 resource: Wasmtime（FFM / JNI）と Chicory で同じハーネスのイベント列が一致（own / borrow / 入れ子の option・result / static / world 直下の resource / drop 時の `close()`、貸し出し中のハンドルの drop の拒否）
* 参照実装の Wasmtime は 48 LTS（decomposer の wasmtime-environ と同じ版。[ADR-0010](adr/0010-wasmtime-lts.md)）。UTF-16 のアラインメントとホストの整数の範囲検査も、差分テストで一致を確認。Wasmtime と挙動が違う唯一の項目（lift で見つかったトラップの後の poison）と再入は、Chicory 単体のテストで確認（[ADR-0007](adr/0007-canonical-abi-validation.md)）
* コンポーネント定義の resource の所有権: own で返したハンドルはコンポーネントの表から取り除かれ、戻すと新しいハンドルになる（仕様の `lift_own` / `lower_own`）。手書きの WAT フィクスチャ `res-edge` で、返したハンドルの再利用などを Chicory 単体のテストで確認（#31）
* JSON 境界: スカラーの JSON 型の検査と、option のタグの判定規則を両バックエンドで揃えた（[ADR-0009](adr/0009-json-boundary-option-tag-and-scalar-types.md)、#36）
* Wasmtime の FFM ブリッジ: ホスト import の戻り値とエラーメッセージに大きさの上限がない（#35）。事前コンパイル済みの `.cwasm` は `openPrecompiledSession` / `withPrecompiledSession` からだけ読み込む（#33）
* decomposer の出力は決定的（exports と canonical options の順序）。各フィクスチャを繰り返し分解して同じ plan になることを `cargo test` で確認
* Gradle プラグイン: Kotlin/JVM の実ビルドで実行結果、configuration cache の再利用、UP-TO-DATE、FROM-CACHE を確認
* Android サンプル: ホスト JVM のユニットテスト、ART（ヘッドレスエミュレーター）での `connectedAndroidTest`、R8 で縮小した release 版の起動がいずれも成功（初回実行 2 ms）。手順は [samples/android/README.md](../samples/android/README.md)

### 未検証・既知の制約

* 実機（物理端末）での確認はまだ。エミュレーターでは確認済み
* Chicory バックエンドの未対応機能: async（WASI P3）、WASI のホスト実装、子コンポーネントの間で resource を受け渡す合成コンポーネント。いずれも使用時に例外になる。plan から分かるものは、Gradle プラグインがビルド時に警告する（`failOnUnsupportedFeatures` でエラーにもできる。#39）
* メモリを持つ子コンポーネント同士の合成は、decomposer が panic する（[#47](https://github.com/chikoski/wasm-component-kotlin/issues/47)）
* 短い名前が同じ interface（`wasi:filesystem/types` と `wasi:http/types` など）を同時に扱えない（[#32](https://github.com/chikoski/wasm-component-kotlin/issues/32)。方針を検討中）
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

QC レビュー（2026-10-31）で見つかった残りの 1 件を、async より先に片付けます。#13 の前提です。

| Issue | 内容 |
| :--- | :--- |
| [#32](https://github.com/chikoski/wasm-component-kotlin/issues/32) | interface を完全修飾名で識別する（方針案を Issue で検討中） |

async と WASI 0.3 に向けて、上から順に進めます（[ADR-0005](adr/0005-async-and-wasi03-chicory-only.md)）。

| 順 | Issue | ID | 内容 |
| :--- | :--- | :--- | :--- |
| 1 | [#16](https://github.com/chikoski/wasm-component-kotlin/issues/16) | A-13 | Component Model Async（Chicory のみ） |
| 2 | [#13](https://github.com/chikoski/wasm-component-kotlin/issues/13) | A-10 | WASI 0.3 ホスト実装（Chicory のみ。0.2 の poll は実装しない） |

### P2

| Issue | ID | 内容 |
| :--- | :--- | :--- |
| [#15](https://github.com/chikoski/wasm-component-kotlin/issues/15) | A-12 | 静的な lift / lower 生成（JSON 境界は当面継続。#19 の結果で判断） |
| [#19](https://github.com/chikoski/wasm-component-kotlin/issues/19) | A-16 | 性能・サイズの計測 |
| [#20](https://github.com/chikoski/wasm-component-kotlin/issues/20) | A-17 | AGP 9 / Gradle 9 対応の確認 |
| [#10](https://github.com/chikoski/wasm-component-kotlin/issues/10) | A-7 | Maven Central / Gradle Plugin Portal への公開準備（当面は公開しない） |
| [#30](https://github.com/chikoski/wasm-component-kotlin/issues/30) | — | Component Model 仕様の公式テストスイートの取り込み（#16 の受け入れ基準にも使う） |
| [#47](https://github.com/chikoski/wasm-component-kotlin/issues/47) | — | メモリを持つ子コンポーネント同士の合成で decomposer が panic する |

### 完了

QC レビュー（2026-10-31）で見つかったもの:

* [#31](https://github.com/chikoski/wasm-component-kotlin/issues/31) Chicory の own ハンドルの受け渡しを仕様どおりにする — #40（decomposer の出力の決定性も修正）
* [#33](https://github.com/chikoski/wasm-component-kotlin/issues/33) `.cwasm` を明示的な入口からだけ読み込む — #41
* [#37](https://github.com/chikoski/wasm-component-kotlin/issues/37) 所有権を移したホスト resource の再度の own / borrow を拒否する — #42
* [#35](https://github.com/chikoski/wasm-component-kotlin/issues/35) FFM のホスト import の戻り値の 64 KiB 制限をなくす — #43
* [#36](https://github.com/chikoski/wasm-component-kotlin/issues/36) JSON 境界のスカラーの型の検査と option のタグの判定 — #44、[ADR-0009](adr/0009-json-boundary-option-tag-and-scalar-types.md)
* [#38](https://github.com/chikoski/wasm-component-kotlin/issues/38) wasm-tools と Rust の版の固定、Rust の clippy / test、generator のユニットテスト — #45
* [#39](https://github.com/chikoski/wasm-component-kotlin/issues/39) Chicory 未対応の機能をビルド時に報告する — #46
* [#34](https://github.com/chikoski/wasm-component-kotlin/issues/34) decomposer と rust-bridge を Wasmtime 48 LTS に揃える — [ADR-0010](adr/0010-wasmtime-lts.md)（plan v3、import した resource 型ごとの動的なホスト型、ブリッジの整数の範囲検査）

それ以前:

* [#14](https://github.com/chikoski/wasm-component-kotlin/issues/14) ホスト定義 resource — [ADR-0008](adr/0008-host-defined-resources.md)（`WitHostResource` の interface と `Imports` の constructor / static、`HostResourceTable`、Chicory と Wasmtime ブリッジの両方、plan v2。現在は v3）
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

| ファイル | 位置づけ |
| :--- | :--- |
| `README.md`、`docs/getting-started.md`、`docs/type-mapping-and-dsl.md`、`docs/architecture.md` | 現行（Wasmtime 側の利用者向け） |
| `docs/chicory-backend.md` | 現行（Chicory バックエンド、プラグイン、Android） |
| `docs/adr/` | 設計判断の記録（追記のみ） |
| `SPEC.md`、`SPEC_REVIEW.md` | 当初の仕様と決定事項 |
| `ANDROID_INTEGRATION_PLAN.md` | Wasmtime を Android で動かす計画。Android の主路線は Chicory に移った（→ [ADR-0001](adr/0001-chicory-backend-for-android.md)） |
| `CODE_REVIEW.md`、`TEST_PROPOSALS.md` | 過去のレビュー記録 |
| [`docs/notes/phase1-implementation-tasks.md`](notes/phase1-implementation-tasks.md) | フェーズ 1 の作業計画。Step 3（モジュール分割の一部、Gradle プラグイン、AGP、キャッシュ、TestKit）は実施済み。Step 1 / 2 / 4 は #16 / #17 などに引き継ぎ |
| [`docs/notes/wit-async-guide.md`](notes/wit-async-guide.md) | async 対応の調査メモ（#16 の前提資料） |
