# プロジェクトの現状とアクションアイテム

> 最終更新: 2026-10-30 / 対象ブランチ: `feat/gradle-plugin`（`main` へのマージ待ち）
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
| `:`（ルート） | AST・ジェネレータ・CLI・Wasmtime ブリッジ（Kotlin 側） | 21 | 稼働。分割は未完（→ A-8） |
| `rust-bridge/` | Wasmtime ブリッジ（Rust, cdylib） | — | 稼働 |
| `decomposer/` | コンポーネント分解ツール（Rust）。`dist/` に wasm 版を同梱 | — | 稼働 |
| `runtime-api` | `ComponentBackend` などバックエンド共通の契約 | 11 | 稼働 |
| `runtime-chicory` | Chicory バックエンド（Canonical ABI 実装） | 11 | 稼働 |
| `tooling` | 分解・AOT コンパイル・コード生成のパイプライン | 21 | 稼働 |
| `gradle-plugin` | `io.github.wasm2kt` プラグイン | 21 | 稼働 |
| `samples/android` | Android サンプルアプリ（composite build） | 11 | ART 実機は未確認（→ A-1） |

## 2. 現在の状態

### 検証済み

* `./gradlew check`（TestKit の functionalTest を含む）: 36 件中 35 件成功、1 件スキップ（新しい wasm-tools が 33 個以上の flags を拒否するため。仕様どおり）
* Chicory と Wasmtime の差分テスト: rich types / host import / resource のライフサイクルで結果が一致
* Gradle プラグイン: Kotlin/JVM の実ビルドで実行結果、configuration cache の再利用、UP-TO-DATE、FROM-CACHE を確認
* Android サンプル: ホスト JVM のユニットテストが成功、R8 で縮小した release APK がビルド可能

### 未検証・既知の制約

* **ART（実機 / エミュレータ）での実行は未確認**。R8 縮小後の release ビルドで実行時に問題が出ないかも未確認
* Chicory バックエンドの未対応機能: ホスト定義 resource、async（WASI P3）、WASI のホスト実装。いずれも使用時に例外になる
* AGP 8.9 + KGP 2.2 + Gradle 8.12 でのみ検証。AGP 9 / Gradle 9 は未確認
* Gradle を JDK 21 以上で動かす必要がある（tooling と plugin が JVM 21 向け）
* `gradle.properties` に macOS 固有の `org.gradle.java.home` が固定されている（CI や他の環境で問題になる）
* 成果物はまだどこにも公開していない（functionalTest 用のローカル `build/test-repo` のみ）
* ルートのテストには `wasm-tools`（1.220 以降推奨）と、ビルド済みの `rust-bridge` が必要

## 3. アクションアイテム

優先度: **P0** = すぐ、**P1** = 公開・運用の前提、**P2** = 機能拡張。完了したらチェックを付け、PR や commit へのリンクを添えてください。
可能なら GitHub Issues に移し、ここには Issue へのリンクだけを残す運用を推奨します。

### P0

- [ ] **A-1** ART での動作確認: `./gradlew -p samples/android :app:connectedAndroidTest`（debug）と、release APK を端末にインストールしての確認（R8）
- [ ] **A-2** `feat/gradle-plugin` を push → PR → `main` にマージ
- [ ] **A-3** ローカルの `main` を `origin/main` に同期する（`feat/chicory-backend` のマージ分）
- [ ] **A-4** 未コミットの `PHASE1_IMPLEMENTATION_TASKS.md`、`WIT_ASYNC_GUIDE.md` を `docs/` 配下にコミットするか判断する

### P1

- [ ] **A-5** CI（GitHub Actions）: `check`、functionalTest、`samples/android` の assemble。wasm-tools と rust-bridge が必要なテストは別ジョブに分ける
- [ ] **A-6** `gradle.properties` から macOS 固有の `org.gradle.java.home` を外す（toolchain 指定などに置き換える）
- [ ] **A-7** 公開設定: Maven Central（runtime-api / runtime-chicory / tooling / ジェネレータ）と Gradle Plugin Portal（plugin）。署名と POM 情報。ルートの成果物名 `wasm-component-kotlin` の見直し
- [ ] **A-8** モジュール分割の残り: ルートから `ast` / `generator` / `cli` / `runtime-wasmtime` を切り出す（現状はルート成果物全体が tooling 経由でプラグインの依存に入っている）
- [ ] **A-9** `decomposer/dist/wasm2kt-decompose.wasm` が最新か CI で検証する（`build-wasm.sh` を実行して差分を検出）

### P2

- [ ] **A-10** Chicory 向け WASI ホスト実装（`wasi:cli` / `clocks` / `random` / `io` から）。普通にビルドした Rust コンポーネントを動かすのに必要
- [ ] **A-11** ホスト定義 resource（import された resource の own / borrow の受け渡し）
- [ ] **A-12** フェーズ B: JSON 境界をやめ、線形メモリを直接読み書きする静的 lift / lower を生成する（性能）。→ [ADR-0003](adr/0003-json-value-boundary.md)
- [ ] **A-13** async（WASI P3）: `async func` → `suspend`、`stream` → `Flow`、`future`。→ `WIT_ASYNC_GUIDE.md`、PHASE1 Step 2
- [ ] **A-14** PHASE1 Step 1 の型マッピング変更（`option` → `T?`、`result` → `Result`、resource の value class 化、`list<u8>` → `ByteArray`）。現行設計（`WitOption` / `WitResult`）と相反するため、**採否を決めて ADR に残す**
- [ ] **A-15** Canonical ABI の厳密化（borrow の lend count、範囲外値の trap 条件、NaN の正規化）
- [ ] **A-16** 計測: 呼び出しのオーバーヘッド、初回インスタンス化の時間、APK サイズ、大きなコンポーネントでの dex 制限とインタプリタへのフォールバック
- [ ] **A-17** AGP 9 / Gradle 9 対応の確認（`variant.sources.kotlin` の扱いを含む）

## 4. 既存ドキュメントの位置づけ

| ファイル | 位置づけ |
| :--- | :--- |
| `README.md`、`docs/getting-started.md`、`docs/type-mapping-and-dsl.md`、`docs/architecture.md` | 現行（Wasmtime 側の利用者向け） |
| `docs/chicory-backend.md` | 現行（Chicory バックエンド、プラグイン、Android） |
| `docs/adr/` | 設計判断の記録（追記のみ） |
| `SPEC.md`、`SPEC_REVIEW.md` | 当初の仕様と決定事項 |
| `ANDROID_INTEGRATION_PLAN.md` | Wasmtime を Android で動かす計画。Android の主路線は Chicory に移った（→ [ADR-0001](adr/0001-chicory-backend-for-android.md)） |
| `CODE_REVIEW.md`、`TEST_PROPOSALS.md` | 過去のレビュー記録 |
| `PHASE1_IMPLEMENTATION_TASKS.md`（未コミット） | フェーズ 1 の作業計画。Step 3（モジュール分割の一部、Gradle プラグイン、AGP、キャッシュ、TestKit）は実施済み。Step 1 / 2 / 4 は A-13 / A-14 などに引き継ぎ |
| `WIT_ASYNC_GUIDE.md`（未コミット） | async 対応の調査メモ（A-13 の前提資料） |
