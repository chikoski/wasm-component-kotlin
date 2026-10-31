# プロジェクトの現状とアクションアイテム

> 最終更新: 2026-10-31 / 対象ブランチ: `main`
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

* `./gradlew check`（TestKit の functionalTest を含む）: 36 件中 35 件成功、1 件スキップ（新しい wasm-tools が 33 個以上の flags を拒否するため。仕様どおり）
* Chicory と Wasmtime の差分テスト: rich types / host import / resource のライフサイクルで結果が一致
* Gradle プラグイン: Kotlin/JVM の実ビルドで実行結果、configuration cache の再利用、UP-TO-DATE、FROM-CACHE を確認
* Android サンプル: ホスト JVM のユニットテスト、ART（ヘッドレスエミュレーター）での `connectedAndroidTest`、R8 で縮小した release 版の起動がいずれも成功（初回実行 2 ms）。手順は [samples/android/README.md](../samples/android/README.md)

### 未検証・既知の制約

* 実機（物理端末）での確認はまだ。エミュレーターでは確認済み
* Chicory バックエンドの未対応機能: ホスト定義 resource、async（WASI P3）、WASI のホスト実装。いずれも使用時に例外になる
* async と WASI 0.3 は Chicory バックエンドだけで実装する方針。Wasmtime バックエンドは同期の機能の参照実装として維持（[ADR-0005](adr/0005-async-and-wasi03-chicory-only.md)）
* AGP 8.9 + KGP 2.2 + Gradle 8.12 でのみ検証。AGP 9 / Gradle 9 は未確認
* Gradle デーモンは JDK 21 で動かす必要がある（tooling と plugin が JVM 21 向けで、Gradle 8.12 は JDK 25 上で動かない）。`gradle/gradle-daemon-jvm.properties`（ルートと `samples/android`）で指定済み
* 成果物はまだどこにも公開していない（functionalTest 用のローカル `build/test-repo` のみ）。当面は公開しない方針
* integration-tests には `wasm-tools`（1.220 以降推奨）と、ビルド済みの `rust-bridge` が必要

## 3. アクションアイテム

アクションアイテムは **GitHub Issues** で管理します。進捗・議論・担当者は Issue 側が正本で、ここには一覧だけを置きます。
優先度はラベル `P0`（すぐ）/ `P1`（公開・運用の前提）/ `P2`（機能拡張）、分野は `area:*` ラベルで表します。
新しい作業は Issue を作り、重要なものだけこの一覧にも追加してください。ID（`A-n`）は過去の記録との対応のために残しています。

* [未完了の Issue 一覧](https://github.com/chikoski/wasm-component-kotlin/issues)
* [P0 の Issue](https://github.com/chikoski/wasm-component-kotlin/issues?q=is%3Aissue+is%3Aopen+label%3AP0)

### P0

なし

### P1

async と WASI 0.3 に向けて、上から順に進めます（[ADR-0005](adr/0005-async-and-wasi03-chicory-only.md)）。

| 順 | Issue | ID | 内容 |
| :--- | :--- | :--- | :--- |
| 1 | [#17](https://github.com/chikoski/wasm-component-kotlin/issues/17) | A-14 | 型マッピングの方針決定（async の型 `Flow` / `suspend` も含む） |
| 2 | [#18](https://github.com/chikoski/wasm-component-kotlin/issues/18) | A-15 | Canonical ABI の検証の厳密化（async の土台） |
| 3 | [#14](https://github.com/chikoski/wasm-component-kotlin/issues/14) | A-11 | ホスト定義 resource（WASI 0.3 の前提） |
| 4 | [#16](https://github.com/chikoski/wasm-component-kotlin/issues/16) | A-13 | Component Model Async（Chicory のみ） |
| 5 | [#13](https://github.com/chikoski/wasm-component-kotlin/issues/13) | A-10 | WASI 0.3 ホスト実装（Chicory のみ。0.2 の poll は実装しない） |

### P2

| Issue | ID | 内容 |
| :--- | :--- | :--- |
| [#15](https://github.com/chikoski/wasm-component-kotlin/issues/15) | A-12 | 静的な lift / lower 生成（JSON 境界は当面継続。#19 の結果で判断） |
| [#19](https://github.com/chikoski/wasm-component-kotlin/issues/19) | A-16 | 性能・サイズの計測 |
| [#20](https://github.com/chikoski/wasm-component-kotlin/issues/20) | A-17 | AGP 9 / Gradle 9 対応の確認 |
| [#10](https://github.com/chikoski/wasm-component-kotlin/issues/10) | A-7 | Maven Central / Gradle Plugin Portal への公開準備（当面は公開しない） |

### 完了

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
