# Changelog

このファイルには、`wasm2kt` の破壊的変更（`!` 付きコミット・PR）と主要な機能変更を記録します。

## Unreleased (`main`)

### 破壊的変更 (Breaking Changes)

* `runtime-api`、`runtime-chicory`、`runtime-wasi` のターゲット JVM を 17 へ引き上げ

[#172](https://github.com/chikoski/wasm-component-kotlin/pull/172) に伴い、ランタイムモジュール群の JVM バイトコードターゲットを 11 から 17 へ引き上げました。Android 向けの利用プロジェクトでも `compileOptions` と `jvmTarget` に Java 17 以上を指定する必要があります。なお、`animalsniffer` による Android API 26 シグネチャ検査は引き続き維持しています。

* `WitType` と `ComponentPlan` 内部構文木型の `internal` 化

[#132](https://github.com/chikoski/wasm-component-kotlin/issues/132) により、`runtime-chicory` の `WitType` と `ComponentPlan` の内部構文木型、`fromJson` を `internal` へ変更しました。`ComponentPlan` の公開メンバーは `parse`、`usesAsync`、および `SUPPORTED_VERSION` に絞られます。`WitType` や解析済みプランの内部構文木を直接参照していたコードはコンパイルできなくなります。コンポーネントの呼び出しには生成コードまたは `ComponentBackend` を使用してください。

* `WitResult` の共通契約化と `runWasiCommand` の契約統一

[#124](https://github.com/chikoski/wasm-component-kotlin/issues/124) により、生成される `WitResult` インタフェースは `wasm2kt.runtime.WitResultValue` を実装する形へ変更しました。これに伴い `isOk`、`isErr`、`getOrNull`、`errorOrNull`、`getOrThrow` はパッケージトップレベルの拡張宣言からメンバー宣言へ移動しています。通常のメンバー呼び出し構文はそのまま記述できます。ただし、この変更にはバイナリ互換性がありません。バインディングを再生成してください。あわせて既存の呼び出し側コードも再コンパイルが必要です。パッケージ直下の拡張宣言を明示的に `import` していた箇所は削除してください。また、`runWasiCommand` は `Boolean` ではなく `WitResultValue` を返すラムダを受け取る単一の署名へ統一しました。

* **Component Model の async に対応し、`plan.json` を v5 へ更新** ([#16](https://github.com/chikoski/wasm-component-kotlin/issues/16), [ADR-0018](docs/adr/0018-async-abi-on-chicory.md))
  * Chicory バックエンドで、async lift（callback 形式と stackful 形式）、`task.return`、waitable set、subtask、`stream` / `future` / `error-context`、協調スレッドの組み込み関数、合成コンポーネントの async の呼び出しを実行できるようにしました
  * 生成コードでは、export の `async func` を `suspend fun`、`future<T>` を `Deferred<T>`、`stream<T>` を `Flow<T>`（`stream<u8>` は `Flow<ByteArray>`）にしました（ADR-0006）。これらを使う world の生成コードは `kotlinx-coroutines-core` に依存します
  * 子コンポーネントの間で resource を受け渡す合成コンポーネントも動くようになりました（#70 の一部）
  * 古い `plan.json`（v4 以前）は読み込めません。再分解が必要です。`ComponentBackend` には stream / future のメソッドが加わりました（既定の実装は `UnsupportedOperationException`）
* **async の境界をコルーチンに** ([ADR-0019](docs/adr/0019-suspending-async-boundary.md))
  * export の `async func` は、呼び出し元のスレッドをブロックせずに中断する `suspend fun` になりました。同じセッションへの呼び出しを並行に進められ、コルーチンを取り消すとコンポーネントの task が取り消されます
  * ホストが実装する import の `async func` は `suspend fun` になりました（ホストの実装の変更が必要です）
  * `runtime-api` が `kotlinx-coroutines-core` に依存するようになりました。`ComponentBackend` に `invokeSuspending` などの suspending なメソッド、`WitHostImportDispatcher` に `asyncHostFunction`、`Linker` に `defineAsync` が加わりました

* **値の境界を JSON からバイナリ形式（`WitWriter` / `WitReader`）へ変更** ([#62](https://github.com/chikoski/wasm-component-kotlin/pull/62), [ADR-0015](docs/adr/0015-binary-value-boundary.md))
  * 生成コードとバックエンド（`runtime-chicory` / `runtime-wasmtime`）の間の値の受け渡しを、`kotlinx.serialization` の `JsonElement` から型に沿ったバイナリ形式に置き換えました
  * `runtime-api` から `FfmJsonCodec` と `kotlinx-serialization-json` への依存を削除しました。古いバージョンの生成コードやネイティブブリッジとは互換性がありません
* **world の API をパッケージのトップレベルに生成し、export された interface はマーカーを context に取る形に変更** ([#52](https://github.com/chikoski/wasm-component-kotlin/pull/52), [ADR-0012](docs/adr/0012-world-at-package-top-level.md))
  * `<WorldName>World` シングルトン `object` を廃止し、`Session`、`withSession`、`openSession`、`imports(...)`、および world 直下の関数・型を指定パッケージのトップレベルに生成するようにしました
  * 1 回の生成で扱う world を 1 つにしました
  * export された interface の関数は `object` のメンバーではなく、マーカー interface（`<Iface>Exports`）を context に取るトップレベル関数になりました
* **interface を完全修飾名で識別し、WIT のパッケージ階層を Kotlin パッケージに対応付け** ([#51](https://github.com/chikoski/wasm-component-kotlin/pull/51), [#32](https://github.com/chikoski/wasm-component-kotlin/issues/32), [ADR-0011](docs/adr/0011-qualified-interface-keys.md))
  * import した interface は `<base>.<ns>.<pkg>.<iface>`、export した interface は `<base>.exports.<ns>.<pkg>.<iface>` に生成されるようになりました
  * ホスト import の受け渡しを、すべての必須 import をラムダで受け取る `imports(...)` 関数と `Linker.add(impl)` に変更しました
* **`decomposer` と `rust-bridge` を Wasmtime 48 LTS（`=48.0.8`）に統一し、`plan.json` を v3（のち #53 で v4）へ更新** ([#49](https://github.com/chikoski/wasm-component-kotlin/pull/49), [#34](https://github.com/chikoski/wasm-component-kotlin/issues/34), [ADR-0010](docs/adr/0010-wasmtime-lts.md))
  * 古い `plan.json`（v1 / v2 / v3）は `runtime-chicory` で読み込めません。再分解が必要です
* **事前コンパイル済み `.cwasm` の読み込みを専用エントリポイントに分離** ([#41](https://github.com/chikoski/wasm-component-kotlin/pull/41), [#33](https://github.com/chikoski/wasm-component-kotlin/issues/33))
  * `openSession` / `withSession` は `.wasm` コンポーネントのみを受け付け、`.cwasm` を渡すとエラーになります。`.cwasm` の読み込みには `openPrecompiledSession` / `withPrecompiledSession` を使用します
* **ホスト定義 `resource` の表現を `WitHostResource` とホスト用 interface に変更** ([#29](https://github.com/chikoski/wasm-component-kotlin/pull/29), [#14](https://github.com/chikoski/wasm-component-kotlin/issues/14), [ADR-0008](docs/adr/0008-host-defined-resources.md))
  * world 直下または import した interface の `resource` は、ハンドルクラスではなくホストが実装する `interface`（`WitHostResource`）として生成されます
* **WIT → Kotlin の型マッピングを改訂** ([#27](https://github.com/chikoski/wasm-component-kotlin/pull/27), [#17](https://github.com/chikoski/wasm-component-kotlin/issues/17), [ADR-0006](docs/adr/0006-type-mapping.md))
  * `option<T>` を `T?`（ネスト時のみ `WitOption<T>`）、`list<u8>` を `ByteArray`、`char` を `CodePoint` に変更しました

### 主な追加・改善

* 共通 Canonical ABI 層と AOT 実行時基盤モジュールの追加
* AGP 9 と Gradle 9 の動作確認と Android 向け機能テストの追加
* ツール群と仕様適合テストの境界検査および Android サンプルの録画後始末の修正
* コード生成の名前解決と Rust ブリッジの資源所有権修正
* ランタイムと WASI 0.3 の安定性と境界制御の改善
* 合成コンポーネントの共有テーブルと借用貸与追跡
* ART における文字列と境界呼び出しの最適化
* 大規模メソッド分割後の制約と回避策の文書化
* プルリクエストテンプレートの整備

[#182](https://github.com/chikoski/wasm-component-kotlin/issues/182) に対応し、Chicory に依存しない `:runtime-canonical` と `:runtime-aot` モジュールを新設しました。`CanonicalAbi`、`Transcoder`、`WitType`、`HandleTable`、`BorrowScope`、および `ComponentGuard` は `:runtime-canonical` へ移し、`@InternalWasm2KtApi` で境界を区切っています。`SYNC_ADAPTER_KINDS` と `usesAsync` の分類規則も `:runtime-canonical` へ単一定義化しました。`:runtime-aot` には `LinearMemory`、`FuncRef` と共有 `WasmTable`、`WasmNumerics`、および `AotTrapSupport` を実装し、JVM 17 と Android API 26 互換検査を通過させています。

[#20](https://github.com/chikoski/wasm-component-kotlin/issues/20) と [#150](https://github.com/chikoski/wasm-component-kotlin/pull/150) に対応し、Gradle 9.9.1 と Kotlin 2.4.21 および AGP 9.5.1 の検証と機能テストの整備を完了しました。テスト環境向けに `gradle-plugin` をローカルのテストリポジトリへ公開する構成へ改めています。さらに `Wasm2KtPluginFunctionalTest` へ Android ライブラリプロジェクトの検証ケースを追加しました。これにより `com.android.library` 構成でのコンポーネント生成、ホスト JVM 単体テストの実行、設定キャッシュの再利用、および `runtime-wasi` インタフェースの再利用を検証します。

[#136](https://github.com/chikoski/wasm-component-kotlin/issues/136) に対応し、`JvmMethodSplitter`、`PlanCheck`、`decomposer`、および `ConformanceTest` の境界処理を修正しました。`inferLocalTypes` では非静的メソッドのスロット 0 にオーナー型を設定し、2 スロット変数の重複検査と定数プッシュ時の `LdcInsnNode` 分岐を追加しています。`PlanCheck` では `streamTables`、`futureTables`、および `task-return` の `results` も検査対象に加えました。`decomposer` では `task.return` の結果型に含まれる `borrow` を検証段階で拒否するようにし、`conformance/skip.txt` から該当 2 件を削除しています。`ConformanceTest` では実行終了時とタイムアウト時にスレッド中断およびインスタンスのクローズを実行します。

[#138](https://github.com/chikoski/wasm-component-kotlin/issues/138) に対応し、`samples/android-glitch-camera` の録画停止時の無限ループ、`GlitchEngine.close` の競合、および一時ファイルの削除漏れを修正しました。`Mp4VideoRecorder` では 0 フレーム時の `signalEndOfInputStream` 回避、EOS 待機の上限設定、初期化失敗時の解放、および未確定ファイルの削除を実装しています。`GlitchEngine` では `closed` フラグとバックグラウンド解放によりメインスレッドの停止と再生成を防ぎました。`MediaStoreHelper` と `GlitchViewModel` では失敗時の保留レコードと一時ファイルを確実に削除します。

[#134](https://github.com/chikoski/wasm-component-kotlin/issues/134) に対応し、`generator` と `TypedBridge` の名前解決と契約生成を修正しました。リソースハンドルへの型エイリアスを再帰的に辿り、`handleFor`、`markMoved`、および `WitBorrowed` の生成が欠落しないようにしています。生成コード内の補助変数と `equals` や `hashCode` のフィールド参照も衝突しない形へ改めました。`variant` と `flags` のメンバー名衝突を回避する規則も加えています。さらにワールド直下の `use` や推移的依存で導入されるホストリソースを、両生成器で漏れなくインポート契約へ登録します。

[#135](https://github.com/chikoski/wasm-component-kotlin/issues/135) に対応し、`rust-bridge` のリソース所有権管理と `flags` 読み取りを修正しました。引数のデコード完了後に `moved_handles` を確定し、ゲスト関数呼び出し中のトラップで消費済み `ResourceAny` がテーブルへ戻らないようにしています。同じ仕様を型付きブリッジのエクスポート生成側にも反映しました。`wire.rs` の `Type::Flags` 読み取りは `u64` 固定を廃し、ワード配列ベースで 65 ビット以上のフラグに対応します。同一呼び出し内での重複ハンドル検出エラーも Chicory バックエンドの文言へ揃えました。

[#130](https://github.com/chikoski/wasm-component-kotlin/issues/130) と [#131](https://github.com/chikoski/wasm-component-kotlin/issues/131) に対応し、資源管理と非同期境界のロールバックを修正しました。`HostResourceTable` では貸出スコープをテーブル単位と参照同一性で管理します。これにより異なるテーブルや並行呼び出しの間で貸出状態が混ざりません。`witFuture` 取り消し時の読み取り側解除との競合と、`WitWriter` の明示的な初期容量の保持も修正しました。`runtime-chicory` では非同期引数の変換失敗時にロールバックとエラー隔離を行います。このロールバックは `stream` と `future` に加えて `error-context` のハンドルも含みます。

[#133](https://github.com/chikoski/wasm-component-kotlin/issues/133) に対応し、`runtime-wasi` とストリーム終了時の後処理を修正しました。`WasiStdin.readViaStream` の完了 `Deferred` は、ストリームの読み切りまたはクローズ時に完了します。`WasiStdin` は下流の出力先で起きた `IOException` を捕捉せず出力側へ伝播します。遅延起動の `writeViaStream` と `newStream` は、開始前の取り消しや早期終了時にも `AutoCloseable` な `Flow` を閉じます。さらに `WasiFilesystem` では末尾スラッシュの扱いとキャンセル時のチャネル解放を改めました。`readlinkAt`、`unlinkFileAt`、および `renameAt` で境界外やルート自身を対象とした場合は `ErrorCode.NotPermitted` を返します。

[#70](https://github.com/chikoski/wasm-component-kotlin/issues/70) では、`ChicoryComponent` に共有テーブルビューとアダプターモジュールを追加し、異なる型インデックスを持つモジュール間の間接呼び出しとデストラクター呼び出しを修正しました。あわせて `AsyncAbi` と `Task` に借用中の貸与追跡を追加し、同期トラップ時の `StackSwitching` 後始末も実装しています。これにより Component Model 公式テストの `resources/` は全 36 件が通過します。

[#58](https://github.com/chikoski/wasm-component-kotlin/issues/58) では、Android ART 上のメソッドトレース解析を基に `runtime-chicory` の境界処理を最適化しました。`WitType` と `FuncType` のレイアウトキャッシュ、`Lowerer` と `Lifter` の一時確保の削減、`ChicoryComponentInstance` でのエクスポート解決結果の保持、および `Context` コレクションの遅延初期化を実装しています。あわせて `WitType` を `sealed interface` から `sealed class` へ変更し、その後の [#132](https://github.com/chikoski/wasm-component-kotlin/issues/132) で `WitType` と `ComponentPlan` の内部構文木型を `internal` に制限しました。また `Transcoder` から `CharsetDecoder` と中間 `String` の確保を取り除きました。Android 16 ARM64 エミュレーター上で `echoName5_aot_dsl` の最小値は 8,309 ns から 770.71 ns へ短縮し、詳細な計測値は [performance.md](docs/explanation/performance.md) に記録しています。

[#57](https://github.com/chikoski/wasm-component-kotlin/issues/57) では、`JvmMethodSplitter` の分割後に残る制約と回避策を [architecture.md](docs/explanation/architecture.md) と [gradle-plugin.md](docs/reference/gradle-plugin.md) へ明文化しました。

[#95](https://github.com/chikoski/wasm-component-kotlin/issues/95) では、`.github/pull_request_template.md` を追加し、`CONTRIBUTING.md` へ案内を記載しました。

* WASI 0.3 ホスト設定 API、Gradle 連携、および結合フィクスチャの追加 [#13](https://github.com/chikoski/wasm-component-kotlin/issues/13) [#98](https://github.com/chikoski/wasm-component-kotlin/issues/98) [#99](https://github.com/chikoski/wasm-component-kotlin/issues/99) [#100](https://github.com/chikoski/wasm-component-kotlin/issues/100) [#101](https://github.com/chikoski/wasm-component-kotlin/issues/101) [#102](https://github.com/chikoski/wasm-component-kotlin/issues/102) [#124](https://github.com/chikoski/wasm-component-kotlin/issues/124) [#125](https://github.com/chikoski/wasm-component-kotlin/issues/125) [#126](https://github.com/chikoski/wasm-component-kotlin/issues/126) [#127](https://github.com/chikoski/wasm-component-kotlin/issues/127) [#128](https://github.com/chikoski/wasm-component-kotlin/issues/128) [ADR-0020](docs/adr/0020-wasi03-filesystem-sandboxing-and-mounts.md) [ADR-0022](docs/adr/0022-wasi03-scope-and-host-configuration.md)

`runtime-wasi` に `wasi03Imports`、パッケージ群ごとの有効化フラグ、セッション単位の入出力ストリームプロバイダ、文字列ベースの `stdin` オーバーロード、`runWasiCommand` を追加しました。`runtime-api` には `WitResultValue`、`ComponentExitException`、`Linker.addAll`、`ImportsFactory.plus` を導入しました。生成コードには `withSessionBlocking` とマッピング済み interface 向けの `imports` `base` オーバーロードを追加しています。`ComponentBuild.run` もライブラリ参照によるマッピング指定を受け付けます。Gradle プラグインには `addWasiDependency` プロパティを追加しました。結合テストに加えて非同期ストリームの `examples/06-async-streams` と WASI 0.3 CLI の `examples/07-wasi-cli` も整備しています。あわせて Chicory での終了後再利用診断を改善しました。また、`wasi-cli` ゲストフィクスチャのパス解決検証や終了系 E2E テストの拡充も行いました。さらに共有 WIT 定義を統合し、事前分解コンポーネントのドリフト検証を追加しています。`wasi:http` は [#117](https://github.com/chikoski/wasm-component-kotlin/issues/117) へ、`wasi:sockets` は [#118](https://github.com/chikoski/wasm-component-kotlin/issues/118) の P2 課題へ分離しました。

* **Wasmtime の型付きブリッジ (`wasm2kt bridge`) とクラスパスからの読み込み** ([#64](https://github.com/chikoski/wasm-component-kotlin/pull/64), [#67](https://github.com/chikoski/wasm-component-kotlin/pull/67), [ADR-0016](docs/adr/0016-typed-bridge-from-wit-json.md))
* **既存のパッケージへの interface 対応付け（`with` / `--interfaces-only` / マニフェスト自動検出）** ([#55](https://github.com/chikoski/wasm-component-kotlin/pull/55), [#54](https://github.com/chikoski/wasm-component-kotlin/issues/54), [ADR-0014](docs/adr/0014-interface-mappings.md))
* **メモリを持つ子コンポーネント同士の合成（multi-memory アダプターと文字列 transcoder）** ([#53](https://github.com/chikoski/wasm-component-kotlin/pull/53), [#47](https://github.com/chikoski/wasm-component-kotlin/issues/47), [ADR-0013](docs/adr/0013-multi-memory-adapters.md))
* **Component Model 公式テストスイートの取り込み** ([#72](https://github.com/chikoski/wasm-component-kotlin/pull/72), [#30](https://github.com/chikoski/wasm-component-kotlin/issues/30), [ADR-0017](docs/adr/0017-component-model-conformance-suite.md))
* **ゲスト `resource` 引数のハンドル所属検査と、呼び出し成功後の `own` 消費** ([#83](https://github.com/chikoski/wasm-component-kotlin/pull/83), [#76](https://github.com/chikoski/wasm-component-kotlin/issues/76))
* **複数スレッドからの同一セッション利用時の `HostResourceTable` 束縛の直列化** ([#82](https://github.com/chikoski/wasm-component-kotlin/pull/82), [#75](https://github.com/chikoski/wasm-component-kotlin/issues/75))
* **Chicory 未対応機能のビルド時検査と、キャッシュヒット時の警告再表示** ([#46](https://github.com/chikoski/wasm-component-kotlin/pull/46), [#86](https://github.com/chikoski/wasm-component-kotlin/pull/86), [#39](https://github.com/chikoski/wasm-component-kotlin/issues/39), [#78](https://github.com/chikoski/wasm-component-kotlin/issues/78))
* **Chicory バックエンドと Gradle プラグイン（`io.github.wasm2kt`）の追加** ([#4](https://github.com/chikoski/wasm-component-kotlin/pull/4), [#21](https://github.com/chikoski/wasm-component-kotlin/pull/21), [ADR-0001](docs/adr/0001-chicory-backend-for-android.md))
