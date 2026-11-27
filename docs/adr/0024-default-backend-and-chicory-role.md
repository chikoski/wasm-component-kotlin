# 0024. Gradle プラグインの既定バックエンドの切り替えと Chicory バックエンドの位置づけ

* ステータス: 提案
* 日付: 2026-11-27
* 関連: [ADR-0001](0001-chicory-backend-for-android.md)、[ADR-0005](0005-async-and-wasi03-chicory-only.md)、[ADR-0014](0014-interface-mappings.md)、[ADR-0015](0015-binary-value-boundary.md)、[ADR-0018](0018-async-abi-on-chicory.md)、[ADR-0021](0021-split-large-chicory-methods.md)、[ADR-0023](0023-custom-wasm-compiler-and-static-jvm-backend.md)、Issue #180、Issue #188

## 背景

[ADR-0023](0023-custom-wasm-compiler-and-static-jvm-backend.md) で定めた 3 段階計画の Phase 1 から Phase 3 までの実装が完了しました。同期コンポーネントのコンパイルと実行に加えて、Phase 2 ではエクスポートとコンポーネント固有のホストインポートに対する静的直接 Lower・Lift を導入しました。さらに Phase 3 では `Concurrency` と `AsyncAbi` を `:runtime-canonical` へ統合し、`AOT` バックエンドでも Component Model Async と WASI 0.3 が動作するようになっています。

公式 Component Model 適合テスト `AotConformanceTest` では、`AOT` バックエンドが `CHICORY` バックエンドと同一の通過状況に達しました。`conformance/skip-aot.txt` のスキップ項目は `conformance/skip.txt` と同一の 30 件のみです。これらはいずれも `wasmtime-environ` 48 が同一インスタンスまたは祖先インスタンスへの再入を無条件トラップへコンパイルする仕様に起因します。ただし、`AotConformanceTest` および厳密境界とリソース寿命を検査する `AotCanonicalAbiStrictnessTest` が呼び出しているのは、いずれも `AotComponentCodegen.generateComponentOnly` の Phase 1 互換経路です。既定切り替え時に同期コンポーネントで使われる Phase 2 の `DirectCanonicalAbi` 直結経路を検証しているのは、現状 8 つの同期フィクスチャに対する `AotPhase2DifferentialTest` のみであり、Phase 2 経路での公式適合テストおよび厳密境界・リソース寿命テストの直接通過は未実施です。

サイズと起動時間および実行速度の計測データは、[performance.md](../explanation/performance.md) にある同期コンポーネントの実測に基づいています。R8 適用後のリリース APK は、Phase 1 の段階で `samples` が `243,797 B` から `55,847 B`、`png-glitch` が `1,524,654 B` から `1,290,954 B` に縮小しました。`heavy` のリリース APK は `CHICORY` の `990,410 B` から Phase 1 `AOT` の `805,797 B` へ約 19% 減っています。Phase 2 で `heavy` の呼び出し対象を全 4 エクスポートから `lcgSum` のみへ絞った比較は `AOT` 同士の比較であり、`classes.dex` が `1,324,716 B` から `26,224 B` へ、DEX メソッド ID 数が `1,039` 件から `54` 件へ減少しました。`with` や `:runtime-wasi` を使わない同期コンポーネントでは `WitWriter` や `ComponentBackend` などのワイヤ層クラスも 0 件になりました。

ARM64 Android 16 エミュレーター `Medium_Phone_API_36.1` 上の初回起動比較では、`arithmetic` の初回合計時間が `CHICORY` の `3.836 ms` から Phase 2 `AOT` の `0.238 ms` へ、`heavy` が `84.646 ms` から `5.607 ms` へ短縮しました。Apple Silicon 上の Host JVM における定常状態では、Phase 2 `AOT` の `add` が `chicory-aot/dsl` の `196.7 ns` から `14.8 ns` へ、`echo-bytes` 64 KiB が `12.08 µs` から `3.01 µs` へ、`sort-bytes` 64 KiB が `4.64 ms` から `579.4 µs` へ短縮しています。同じ ARM64 エミュレーター上の `CallBenchmark` でも、`add` 中央値が `864.3 ns` から `11.6 ns`、`echoName5` 中央値が `7,124.8 ns` から `500.2 ns`、`echoBlob1024` 中央値が `2,462.2 ns` から `655.9 ns` へ短縮しました。

一方で、未計測の領域と性能・機能上の制限も残っています。まず計算中心の処理では、`lcg-sum` が Host JVM で `311.2 µs` と `314.2 µs` でほぼ同等であり、`regex-count` や `json-roundtrip` は `CHICORY` より速いものの Wasmtime より低速です。ARM64 エミュレーター上の `echoBytes1024` は最小値が `748.4 ns` と `1,184.4 ns` で `AOT` が短い一方、中央値では `CHICORY` の `1,325.2 ns` に対し `AOT` が `2,193.5 ns` となりました。さらに ARM64 ART における `lcgSum1e6` 以降の計算ベンチマークは ADB 切断により未取得のままです。

非同期コンポーネントと WASI 0.3 の扱いにも注意が必要です。Phase 3 の `AOT` 非同期コンポーネントは `AotComponentBackend` 経由で `ComponentBackendFactory` と `WitWriter`・`AsyncAbi` の境界を通るため、Phase 2 同期コンポーネントのような R8 によるワイヤ層除去は働きません。加えて、非同期 `AOT` のサイズと実行速度の実測値はまだ取得されていません。

また、`AOT` バックエンドには Wasm 仕様と API 互換性の制約があります。SIMD128、`exception-handling`、`tail-call`、および Wasm GC は対象外であり、該当命令を含むコンポーネントはビルド時にエラーとなります。`LinearMemory` は単一の `ByteArray` を用いるため最大 32,767 ページの上限があります。`ChicoryComponent.fromDirectory` に相当する実行時動的ロードも備えていません。さらに Phase 2 の同期コンポーネントでは `withSession` と `openSession` が `AotComponentFactory` 専用になり、`ComponentBackendFactory` や `Path` を渡すコードはコンパイルできなくなります。

[ADR-0023](0023-custom-wasm-compiler-and-static-jvm-backend.md) では、Phase 3 完了後に別途 ADR を作成して 2 つの事項を判断すると定めていました。1 つ目は `Wasm2KtExtension.backend` の既定値を `CHICORY` から `AOT` へ切り替えるかどうかであり、2 つ目は `:runtime-chicory` を含む Chicory バックエンドの今後の位置づけです。なお、ビルド時の `:tooling` では、`Decomposer` が同梱の `wasm2kt-decompose.wasm` を実行するために Chicory の `Parser`、`MachineFactoryCompiler`、`Instance`、および `WasiPreview1` を使用しており、`AOT` コンパイラ自身も `ChicoryWasmAdapter` を通じて `chicory-wasm` の `Parser` に依存しています。したがって実行時バックエンドとしての Chicory とビルド時ツール側の Chicory 依存は分けて整理します。

## 検討した選択肢

### 既定バックエンドの選択

`Wasm2KtExtension.backend` の既定値について、6 つの選択肢を比較しました。

* `CHICORY` を既定のまま維持する案
* 既定は `CHICORY` のまま無指定時に非推奨警告を出す予告期間を置く案
* 同期と非同期を一括で `AOT` 既定へ切り替える案
* 同期コンポーネントのみ `AOT` 既定へ切り替える段階的移行案
* 未対応命令を検出したら自動で `CHICORY` へ戻す案
* `AOT` へ切り替えると同時に `CHICORY` を禁止する案

`CHICORY` 維持案と予告期間案は既存利用者のビルドを壊さない点が長所です。しかし、新規利用者が R8 縮小や呼び出し速度の恩恵を受けるために毎回明示設定を要します。

同期・非同期の一括切り替え案は設定モデルが単純で、バックエンドの規約値を 1 箇所で説明できる点が長所です。一方で、非同期 `AOT` には性能計測がない点と、同期 `AOT` では `withSession` の型変更や SIMD128 等でのビルドエラーという破壊的変更が生じる点が短所です。

同期のみ先行して `AOT` 既定にする段階的移行案は、計測済みの同期コンポーネントだけを先に切り替えて非同期の計測リスクを切り離せる点が長所です。しかし、コンポーネントが非同期か同期かによって既定バックエンドが暗黙に変わるため利用者に分かりにくく、混在アプリで `:runtime-aot` と `:runtime-chicory` の両方が入りやすくなる点が短所です。

未対応命令の検出時に自動で `CHICORY` へ戻す案は既存ビルドが失敗しない点が長所です。ただし [ADR-0023](0023-custom-wasm-compiler-and-static-jvm-backend.md) で「未対応命令は実行時へ先送りせずビルド時エラーで停止する」と定めた方針に反し、意図せずアプリサイズが増大する原因にもなります。即時禁止案も、ARM64 ART の計算系ベンチマーク未完走や `echoBytes1024` 調査が残る現状では逃げ道を断つリスクが高いため退けました。

以上を踏まえ、本 ADR では一括での `AOT` 既定化と `CHICORY` オプトアウトの維持を主案としつつ、段階的移行案と非推奨予告期間案を人間判断の選択肢として残します。

### Chicory バックエンドと `:runtime-chicory` の位置づけ

実行時バックエンドである `:runtime-chicory` および `:tooling` の `CoreModuleCompiler`・`JvmMethodSplitter` の今後の位置づけについて、3 つの選択肢を比較しました。ビルド時の `Decomposer` と `ChicoryWasmAdapter` が依存する Chicory の実行基盤およびパーサーはこの比較対象から切り離して別途扱います。

* 主要バックエンドとして `AOT` と対等に開発を継続する案
* 副次バックエンドとして維持し、撤退条件を定めた上で段階的に縮小する案
* `:runtime-chicory` と Chicory 生成経路を即時に削除する案

対等維持案は保守と最適化の二重投資が続きます。即時削除案は、非同期経路の別実装による比較参照、`ChicoryComponent.fromDirectory` による動的ロード、および未対応命令や `echoBytes1024` 等の回帰時の回避手段を失います。よって副次バックエンドへ位置づけを改め、撤退条件を満たすまで互換維持のみを行う案が妥当です。

## 決定

既定バックエンドについては、Gradle プラグインの規約値を `Wasm2KtBackend.AOT` へ切り替える方針を提案します。Chicory バックエンドについては、副次バックエンドとして位置づけを改め、撤退条件を満たすまで互換維持のみを行う方針を提案します。なお、`wasm2kt generate` CLI には [ADR-0023](0023-custom-wasm-compiler-and-static-jvm-backend.md) の時点でバックエンド選択機能がないため、本決定の対象は Gradle プラグインのみです。

### 既定値の切り替えと互換性・境界条件の扱い

第 1 に、`Wasm2KtExtension.backend` の規約値を `Wasm2KtBackend.CHICORY` から `Wasm2KtBackend.AOT` へ変更し、`Wasm2KtBackend.CHICORY` は明示指定によって引き続き選択可能とします。`Wasm2KtComponent.backend` が `extension.backend` を規約として継承する優先規則は現行のまま変更しません。

この既定変更は一部の既存利用者にソース非互換およびビルドエラーを伴う破壊的変更となります。同期コンポーネントで `AOT` が選ばれると `withSession` と `openSession` の引数は `AotComponentFactory` に限定されるため、`ComponentBackendFactory` 型変数や `ChicoryComponent.fromDirectory` の戻り値や `Path` を渡すコードはコンパイルエラーになります。また、SIMD128、`exception-handling`、`tail-call` を含むコンポーネントは `AOT` ではビルド時エラーになり、`addRuntimeDependency = false` で `:runtime-chicory` のみを手動追加している構成も不足エラーになります。したがって実装 PR では、ビルドエラーメッセージで `backend.set` に `Wasm2KtBackend.CHICORY` を指定できる旨を案内する改修と、壊れる呼び出し一覧を含む移行ガイドの整備を必須とします。

混在構成と外部ライブラリ連携の扱いも明確にします。一部コンポーネントのみ `CHICORY` へ戻す混在構成では `:runtime-aot` と `:runtime-chicory` の双方が `implementation` へ追加されます。この場合、ランタイムクラスや `WitWriter`・`Linker` 等のワイヤ層は APK に残りますが、`AOT` 側コンポーネントの未使用エクスポートに対する R8 の静的除去は引き続き機能します。同様に、`AOT` 選択時でも [ADR-0014](0014-interface-mappings.md) の `with` による外部ライブラリ提供インタフェースと `:runtime-wasi` は `AotMappedImportBridge` と `Linker` のバイナリ境界を通るため、これらのワイヤ層クラスは残ります。実装 PR では、Chicory 既定または `CHICORY` 明示指定でビルドされたインタフェースライブラリを `AOT` 側アプリから利用できることも互換性確認に含めます。あわせて Phase 2 経路の同期差分検証は `AotPhase2DifferentialTest` を基準としつつ、Phase 2 経路での [ADR-0007](0007-canonical-abi-validation.md) と [ADR-0008](0008-host-defined-resources.md) の厳密契約検査は後続の撤退条件または切り替え前検証課題として明示します。

### 副次バックエンドへの再定義と撤退条件

第 2 に、`:runtime-chicory` および `:tooling` の `CoreModuleCompiler`・`JvmMethodSplitter` を次の役割を持つ副次バックエンドへ再定義します。

* 既定移行期間のフォールバック実行基盤
* 実行時の動的 `.wasm` 読み込みを行うインタプリタ経路
* 非同期および WASI 0.3 機能の比較用参照バックエンド

役割の再定義に伴い、Chicory 固有の性能最適化開発は凍結し、保守範囲を不具合修正、互換維持、および `:runtime-canonical` との共通契約追従に限定します。一方で、ビルド時の `Decomposer` 実行基盤および `AOT` コンパイラの `ChicoryWasmAdapter` が使用する Chicory 依存は `:tooling` 内にそのまま残します。

第 3 に、将来別の ADR で `:runtime-chicory` の非推奨化または削除を検討する前に満たすべき撤退条件を次のとおり定めます。

* Phase 2 静的直結経路での公式適合テストおよび厳密境界・リソース寿命テストの通過
* 非同期および WASI 0.3 における `AOT` バックエンドのサイズと実行時間計測の完了
* ARM64 ART における計算ベンチマーク完走と `echoBytes1024` 逆転要因の調査完了
* `AOT` 既定リリース後における少なくとも 1 回のマイナーバージョン運用期間での重大回帰ゼロの確認
* `ChicoryComponent.fromDirectory` に相当する動的ロード要件の採否判断
* Wasmtime の非同期対応または `definitions.py` による単一仕様検証への移行合意
* ビルド時における `Decomposer` 実行基盤および `ChicoryWasmAdapter` 用パーサーの Chicory 依存を残すか置き換えるかの判断

重大回帰の定義は、既存テストまたは利用者報告で再現するクラッシュ、誤結果、およびビルド不能のうち `AOT` 固有かつ既知制約外の事象とし、GitHub Issue で追跡して解消状況を確認します。なお、非同期の参照バックエンドとして Wasmtime を使う選択肢を採る場合は、[ADR-0005](0005-async-and-wasi03-chicory-only.md) の方針を改訂する新規 ADR が必要になります。

### 既存 ADR とドキュメントの更新範囲

既存の [ADR-0001](0001-chicory-backend-for-android.md)、[ADR-0005](0005-async-and-wasi03-chicory-only.md)、[ADR-0015](0015-binary-value-boundary.md)、および [ADR-0021](0021-split-large-chicory-methods.md) の本文は書き換えません。本 ADR が承認され、既定切り替えの実装 PR をマージする段階で、`docs/adr/README.md` のステータス欄へ注記を加えます。具体的には ADR-0001 と ADR-0021 の位置づけ変更注記に加えて、Phase 2 例外が既定になる点を示す注記を ADR-0015 に追加します。さらに `:runtime-api`・`:runtime-canonical`・`:runtime-aot`・`:runtime-chicory`・`:runtime-wasi` の全ランタイムモジュールで JVM 17 ターゲットと `animalsnifferMain` による Android API 26 互換検査を継続します。

実装 PR では、すでに現状と乖離している記述や既定変更に伴う対象を一括更新します。主な更新対象は `Wasm2KtPlugin.kt` の KDoc における `CHICORY` の既定表記・`failOnUnsupportedFeatures` の警告のみおよび非同期不可記述、`docs/explanation/performance.md` の 7.6 節と 8.3 節の同期限定記述、`docs/reference/gradle-plugin.md`、`docs/STATUS.md`、`CLAUDE.md` の構成と必須テスト記述、`CONTRIBUTING.md`、ならびに ADR-0023 の結果節で指定されたドキュメント群です。

## 結果とトレードオフ

本提案が承認されて実装された場合、`with` と `:runtime-wasi` を伴わない同期コンポーネントを利用する Gradle プラグインの既定利用者は、追加設定なしで R8 による未使用コードおよびワイヤ層の除去と初回起動短縮を受けられます。開発側は Chicory 固有の最適化保守から離れ、`:runtime-aot` と `:runtime-canonical` へ改善投資を集中できます。

一方で、同期コンポーネントでは `withSession` 等の引数型変更や未対応命令のビルド停止によって既存のソース互換が壊れる場面が生じます。非同期コンポーネントおよび `with` や `:runtime-wasi` を併用するコンポーネントではワイヤ層の R8 除去が効かず、非同期経路については性能改善の定量的根拠も今後の計測課題として残ります。また、並存期間中は CI における両バックエンドの適合テスト維持コストと、ビルド時 `:tooling` 側の Chicory 依存が継続します。

本提案は現在ステータスを提案として人間のレビューに付し、既定切り替えの実装 PR へ進む前に次の 5 点について判断を求めます。

* 既定切り替えの前提条件と実装タイミング
* 同期のみ先行するか非同期も同時に既定へ切り替えるか
* 破壊的変更としてのバージョニングと `@Deprecated` 付与時期
* `samples/android` とベンチマークおよび別リポジトリ化したサンプルの追従範囲
* CI とローカル開発における必須テスト対象および E2E 検証の切り替え範囲

1 点目は、未実施の ARM64 ART 計算系ベンチマーク、非同期 `AOT` 計測、および Phase 2 経路の公式適合テストと厳密契約テストの確認を、既定切り替え PR の前に完了させるか後続とするかという判断です。2 点目は、既定切り替えを全コンポーネント一括で行うか、未計測の非同期コンポーネントを `CHICORY` 既定のまま残すかという判断です。3 点目は、ソース非互換を伴うため次のメジャーバージョンで切り替えるかマイナーバージョンで扱うか、および `:runtime-chicory` への `@Deprecated` 付与時期です。4 点目は、`samples/android` がすでに `roundtrip-aot` 等へ `AOT` を明示している現状を踏まえ、`samples/android` の無指定コンポーネントやベンチマーク対象および独立リポジトリへ切り出された `android-glitch-camera` を既定変更に合わせてどう更新するかという判断です。5 点目は、`ConformanceTest` や `ChicoryDifferentialTest`・`TypedBridgeDifferentialTest` を含む CI の必須テスト範囲と `CLAUDE.md` の必須テスト記述をどう見直すかという判断です。
