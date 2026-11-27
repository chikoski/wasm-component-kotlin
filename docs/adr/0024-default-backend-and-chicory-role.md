# 0024. Gradle プラグインの既定バックエンドの切り替えと Chicory バックエンドの位置づけ

* ステータス: 提案
* 日付: 2026-11-27 提案、2026-11-28 改訂
* 関連: [ADR-0001](0001-chicory-backend-for-android.md)、[ADR-0005](0005-async-and-wasi03-chicory-only.md)、[ADR-0014](0014-interface-mappings.md)、[ADR-0015](0015-binary-value-boundary.md)、[ADR-0018](0018-async-abi-on-chicory.md)、[ADR-0021](0021-split-large-chicory-methods.md)、[ADR-0023](0023-custom-wasm-compiler-and-static-jvm-backend.md)、Issue #180、Issue #188、Issue #205、Issue #206

## 背景

[ADR-0023](0023-custom-wasm-compiler-and-static-jvm-backend.md) で定めた 3 段階計画の Phase 1 から Phase 3 までの実装が完了しました。同期コンポーネントのコンパイルと実行に加えて、Phase 2 ではエクスポートとコンポーネント固有のホストインポートに対する静的直接 Lower・Lift を導入しました。さらに Phase 3 では `Concurrency` と `AsyncAbi` を `:runtime-canonical` へ統合し、`AOT` バックエンドでも Component Model Async と WASI 0.3 が動作するようになっています。

公式 Component Model 適合テスト `AotConformanceTest` では、`AOT` バックエンドが `CHICORY` バックエンドと同一の通過状況に達しました。`conformance/skip-aot.txt` のスキップ項目は `conformance/skip.txt` と同一の 30 件のみです。これらはいずれも `wasmtime-environ` 48 が同一インスタンスまたは祖先インスタンスへの再入を無条件トラップへコンパイルする仕様に起因します。ただし、`AotConformanceTest` および厳密境界とリソース寿命を検査する `AotCanonicalAbiStrictnessTest` が呼び出しているのは、いずれも `AotComponentCodegen.generateComponentOnly` の Phase 1 互換経路です。既定切り替え時に同期コンポーネントで使われる Phase 2 の `DirectCanonicalAbi` 直結経路を検証しているのは、現状 8 つの同期フィクスチャに対する `AotPhase2DifferentialTest` のみであり、同期スイートを対象とした Phase 2 経路での公式適合テストおよび厳密境界・リソース寿命テストの直接通過は未実施です。

サイズと起動時間および実行速度の計測データは、[performance.md](../explanation/performance.md) にある同期コンポーネントの実測に基づいています。R8 適用後のリリース APK は、Phase 1 の段階で `samples` が `243,797 B` から `55,847 B`、`png-glitch` が `1,524,654 B` から `1,290,954 B` に縮小しました。`heavy` のリリース APK は `CHICORY` の `990,410 B` から Phase 1 `AOT` の `805,797 B` へ約 19% 減っています。Phase 2 で `heavy` の呼び出し対象を全 4 エクスポートから `lcgSum` のみへ絞った比較は `AOT` 同士の比較であり、`classes.dex` が `1,324,716 B` から `26,224 B` へ、DEX メソッド ID 数が `1,039` 件から `54` 件へ減少しました。`with` や `:runtime-wasi` を使わない同期コンポーネントでは `WitWriter` や `ComponentBackend` などのワイヤ層クラスも 0 件になりました。

ARM64 Android 16 エミュレーター `Medium_Phone_API_36.1` 上の初回起動比較では、`arithmetic` の初回合計時間が `CHICORY` の `3.836 ms` から Phase 2 `AOT` の `0.238 ms` へ、`heavy` が `84.646 ms` から `5.607 ms` へ短縮しました。Apple Silicon 上の Host JVM における定常状態では、Phase 2 `AOT` の `add` が `chicory-aot/dsl` の `196.7 ns` から `14.8 ns` へ、`echo-bytes` 64 KiB が `12.08 µs` から `3.01 µs` へ、`sort-bytes` 64 KiB が `4.64 ms` から `579.4 µs` へ短縮しています。同じ ARM64 エミュレーター上の `CallBenchmark` でも、`add` 中央値が `864.3 ns` から `11.6 ns`、`echoName5` 中央値が `7,124.8 ns` から `500.2 ns`、`echoBlob1024` 中央値が `2,462.2 ns` から `655.9 ns` へ短縮しました。

一方で、未計測の領域と性能・機能上の制限も残っています。まず計算中心の処理では、`lcg-sum` が Host JVM で `311.2 µs` と `314.2 µs` でほぼ同等であり、`regex-count` や `json-roundtrip` は `CHICORY` より速いものの Wasmtime より低速です。ARM64 エミュレーター上の `echoBytes1024` は最小値が `748.4 ns` と `1,184.4 ns` で `AOT` が短い一方、中央値では `CHICORY` の `1,325.2 ns` に対し `AOT` が `2,193.5 ns` となりました。この逆転要因の調査は Issue #205 として切り出しています。さらに ARM64 ART における `lcgSum1e6` 以降の計算ベンチマークは ADB 切断により未取得のままです。

非同期コンポーネントと WASI 0.3 の扱いにも注意が必要です。Phase 3 の `AOT` 非同期コンポーネントは `AotComponentBackend` 経由で `ComponentBackendFactory` と `WitWriter`・`AsyncAbi` の境界を通るため、Phase 2 同期コンポーネントのような R8 によるワイヤ層除去は働きません。加えて、非同期 `AOT` のサイズと実行速度の実測値はまだ取得されていません。なお、非同期および WASI 0.3 におけるワイヤ層除去の技術的実現性は別途 Issue #206 で検討します。

また、`AOT` バックエンドには Wasm 仕様と API 互換性の制約があります。SIMD128、`exception-handling`、`tail-call`、および Wasm GC は対象外であり、該当命令を含むコンポーネントはビルド時にエラーとなります。`LinearMemory` は単一の `ByteArray` を用いるため最大 32,767 ページの上限があります。`ChicoryComponent.fromDirectory` に相当する実行時動的ロードも備えていません。さらに Phase 2 の同期コンポーネントでは `withSession` と `openSession` が `AotComponentFactory` 専用になり、`ComponentBackendFactory` や `Path` を渡すコードはコンパイルできなくなります。

[ADR-0023](0023-custom-wasm-compiler-and-static-jvm-backend.md) では、Phase 3 完了後に別途 ADR を作成して 2 つの事項を判断すると定めていました。1 つ目は `Wasm2KtExtension.backend` の既定値を `CHICORY` から `AOT` へ切り替えるかどうかであり、2 つ目は `:runtime-chicory` を含む Chicory バックエンドの今後の位置づけです。なお、ビルド時の `:tooling` では、`Decomposer` が同梱の `wasm2kt-decompose.wasm` を実行するために Chicory の `Parser`、`MachineFactoryCompiler`、`Instance`、および `WasiPreview1` を使用しており、`AOT` コンパイラ自身も `ChicoryWasmAdapter` を通じて `chicory-wasm` の `Parser` に依存しています。したがって実行時バックエンドとしての Chicory とビルド時ツール側の Chicory 依存は分けて整理します。

## 検討した選択肢

### 既定バックエンドの選択

`Wasm2KtExtension.backend` の既定値と切り替え時期について、6 つの選択肢を比較しました。

* `CHICORY` を既定のまま維持する案
* 既定は `CHICORY` のまま無指定時に非推奨警告を出す予告期間を置く案
* 前提計測と検証を完了した直後に同期と非同期を一括で `AOT` 既定へ切り替える案
* 同期コンポーネントのみ `AOT` 既定へ切り替える段階的移行案
* 未対応命令を検出したら自動で `CHICORY` へ戻す案
* `AOT` へ切り替えると同時に `CHICORY` を禁止する案

`CHICORY` 維持案は既存利用者のビルドを壊さない点が長所です。しかし、新規利用者が R8 縮小や呼び出し速度の恩恵を受けるために毎回明示設定を要します。予告期間を置く案も互換性配慮としては丁寧ですが、本プロジェクトはまだ初回公開前かつ全体として実験的な段階にあるため、非推奨警告期間を挟まずに仕様を前へ進めるほうが適切です。

同期と非同期を一括で `AOT` 既定へ切り替える案は、事前計測と Phase 2 経路の適合・厳密境界テストを切り替え前に済ませることで、定量的な裏付けと正確性の根拠を揃えた上で設定モデルを単純化できる点が長所です。非同期経路と同期経路で規約値が分かれず、利用者の混乱も防げます。

同期のみ先行して `AOT` 既定にする段階的移行案は、非同期の計測リスクを切り離せる点が長所です。しかし、コンポーネントが非同期か同期かによって既定バックエンドが暗黙に変わるため振る舞いが予測しづらく、混在アプリで `:runtime-aot` と `:runtime-chicory` の両方が入りやすくなります。

未対応命令の検出時に自動で `CHICORY` へ戻す案は [ADR-0023](0023-custom-wasm-compiler-and-static-jvm-backend.md) の「未対応命令はビルド時エラーで停止する」方針に反します。即時禁止案も、ARM64 ART の `echoBytes1024` 調査や未対応命令に対する逃げ道を断つ点が不利益になります。よって前提計測と検証の完了直後に、同期・非同期を一括で `AOT` 既定へ切り替え、かつ `CHICORY` のオプトアウトを残す案を採用します。

### Chicory バックエンドと `:runtime-chicory` の位置づけ

実行時バックエンドである `:runtime-chicory` および `:tooling` の `CoreModuleCompiler`・`JvmMethodSplitter` の今後の位置づけについて、3 つの選択肢を比較しました。ビルド時の `Decomposer` と `ChicoryWasmAdapter` が依存する Chicory の実行基盤およびパーサーはこの比較対象から切り離して別途扱います。

* 主要バックエンドとして `AOT` と対等に開発を継続する案
* 副次バックエンドとして維持し、撤退条件を定めた上で段階的に縮小する案
* `:runtime-chicory` と Chicory 生成経路を即時に削除する案

対等維持案は保守と最適化の二重投資が続きます。即時削除案は、非同期経路の別実装による比較参照、`ChicoryComponent.fromDirectory` による動的ロード、および未対応命令や Issue #205 等の調査中の回避手段を失います。よって副次バックエンドへ位置づけを改め、撤退条件を満たすまで互換維持のみを行う案を採用します。

### CI とローカル開発における必須テスト範囲

既定切り替え後に `.github/workflows/ci.yml` の各ジョブおよび `CLAUDE.md` で求める必須テスト範囲について、3 つの選択肢を比較しました。

* 全スイートの並走維持
* AOT 主体と Chicory 最小回帰ゲートの併用
* PR 必須ゲートの AOT と Wasmtime 一本化

全スイートの並走維持案は回帰の見落としを防げる点が長所ですが、テスト実行時間と二重保守負荷が残ります。一本化案は PR の負荷が最小になる一方で、`:runtime-canonical` を変更した際に副次バックエンドである `:runtime-chicory` や非同期比較の基準が壊れても PR 時点で検知できません。

併用案は、`AOT` と参照実装 Wasmtime のテスト群を PR の必須ゲートへ据えつつ、Chicory 側は非同期参照比較用のテストと動的ロードのスモーク確認を PR 必須に残します。同期の網羅的な差分テスト等は関連モジュールの変更時起動と定期実行へ移すため、保守負荷を抑えながら非同期比較とフォールバックの安全網を保てます。よって併用案を採用します。

## 決定

既定バックエンドについては、前提データの取得と Phase 2 経路検証を先行して実施した直後に、Gradle プラグインの規約値を同期と非同期の双方で一括して `Wasm2KtBackend.AOT` へ切り替えます。Chicory バックエンドは副次バックエンドへ再定義し、撤退条件を満たすまで互換維持のみを行います。また、CI とローカルの必須テスト体系は `AOT` 主体と Chicory 最小回帰ゲートの併用へ移行します。なお、`wasm2kt generate` CLI には [ADR-0023](0023-custom-wasm-compiler-and-static-jvm-backend.md) の時点でバックエンド選択機能がないため、既定値切り替えの対象は Gradle プラグインのみです。

### 既定切り替えの前提確認と適用方針

既定値切り替えの実装 PR へ進む前に、次の 3 つの前提確認を完了させます。

* ARM64 ART における `lcgSum1e6` 以降の計算系ベンチマークの取得
* 非同期および WASI 0.3 コンポーネントにおける `AOT` バックエンドのサイズと実行時間の計測
* 同期コンポーネントを対象とした Phase 2 静的直結経路での公式適合テストおよび厳密境界・リソース寿命テストの通過確認

3 点目の対象範囲は、Phase 2 の適用対象である同期コンポーネントの公式適合テスト `values/` および `resources/` です。`async/` は Phase 3 の `AotComponentBackend` 互換経路に留まるため対象から外し、`with` を使う項目および `conformance/skip-aot.txt` を Phase 1 と共有するか分離するかもこの検証時に確定します。

これらの前提確認が完了し次第、`Wasm2KtExtension.backend` の規約値を `Wasm2KtBackend.CHICORY` から `Wasm2KtBackend.AOT` へ変更します。同期コンポーネントと非同期コンポーネントを一括で `AOT` 既定としつつ、`Wasm2KtBackend.CHICORY` は明示指定によるオプトアウトとして維持します。`Wasm2KtComponent.backend` が `extension.backend` を規約として継承する優先規則は現行のまま変更しません。

この切り替えは、本プロジェクトがまだ初回公開前かつ実験的段階にあるため、初回公開に向けた作業線上のマイナーバージョン相当の変更として実施し、`CHANGELOG.md` の `Unreleased` 破壊的変更欄に記録します。ソース非互換となる変更や不要になった公開 API に対して `@Deprecated` 注釈を付けて維持する運用は行わず、不要と判断したものは可視性を狭めるか削除します。あわせて `samples/android`、`:benchmarks`、および独立リポジトリへ切り出された `android-glitch-camera` も既定変更に合わせて `AOT` 前提へ追従させます。

利用者の移行と境界条件への配慮として、次の 3 点を明確にします。第 1 に、同期コンポーネントで `withSession` や `openSession` が `AotComponentFactory` 専用になる点です。`ComponentBackendFactory` 型変数、`ChicoryComponent.fromDirectory` の戻り値、または `Path` を渡すコードはコンパイルエラーになります。第 2 に、`failOnUnsupportedFeatures` の既定挙動の変化です。従来 `CHICORY` 既定では未対応機能があってもビルド時は警告のみで到達時の実行時例外となっていましたが、`AOT` では SIMD128・`exception-handling`・`tail-call` 等をビルド時エラーとして即座に停止します。第 3 に、`addRuntimeDependency = false` で `:runtime-chicory` のみを手動追加している構成は不足エラーになります。したがって実装 PR では、ビルドエラーメッセージで `backend.set` に `Wasm2KtBackend.CHICORY` を指定できる案内を出し、壊れる呼び出し例と挙動変化をまとめた移行ガイドを整えます。

一部コンポーネントのみ `CHICORY` を指定する混在構成や、[ADR-0014](0014-interface-mappings.md) の `with` による外部ライブラリ連携、および `:runtime-wasi` を併用する構成では、`WitWriter`・`Linker`・`AotMappedImportBridge` などのワイヤ層クラスが APK に残る一方、`AOT` 側コンポーネントの未使用エクスポート除去は有効です。Chicory でビルドされたインタフェースライブラリを `AOT` 既定アプリから利用できることも互換性検証に含めます。

### 副次バックエンドへの再定義と撤退条件

`:runtime-chicory` および `:tooling` の `CoreModuleCompiler`・`JvmMethodSplitter` を次の役割を持つ副次バックエンドへ再定義します。

* 既定移行期間のフォールバック実行基盤
* 実行時の動的 `.wasm` 読み込みを行うインタプリタ経路
* 非同期および WASI 0.3 機能の比較用参照バックエンド

役割の再定義に伴い、Chicory 固有の性能最適化開発は凍結し、保守範囲を不具合修正、互換維持、および `:runtime-canonical` との共通契約追従に限定します。一方で、ビルド時の `Decomposer` 実行基盤および `AOT` コンパイラの `ChicoryWasmAdapter` が使用する Chicory 依存は `:tooling` 内にそのまま残します。また、非同期および WASI 0.3 のワイヤ層 R8 除去に関する研究は Issue #206 として別枠で進め、既定切り替え自体の前提にはしません。

将来別の ADR で `:runtime-chicory` の縮小や削除を検討する前に満たすべき撤退条件を次のとおり定めます。

* Issue #205 による ARM64 ART `echoBytes1024` 逆転要因の調査完了と必要な対策判断
* `AOT` 既定リリース後における少なくとも 1 回のマイナーバージョン運用期間での重大回帰ゼロの確認
* `ChicoryComponent.fromDirectory` に相当する動的ロード要件の採否判断
* Wasmtime の非同期対応または `definitions.py` による単一仕様検証への移行合意
* ビルド時における `Decomposer` 実行基盤および `ChicoryWasmAdapter` 用パーサーの Chicory 依存を残すか置き換えるかの判断

重大回帰の定義は、既存テストまたは利用者報告で再現するクラッシュ、誤結果、およびビルド不能のうち `AOT` 固有かつ既知制約外の事象とし、GitHub Issue で追跡します。非同期の参照バックエンドとして Wasmtime を使う選択肢を採る場合は、[ADR-0005](0005-async-and-wasi03-chicory-only.md) の方針を改訂する新規 ADR が必要になります。

### CI とローカル開発の必須テスト範囲

PR ごとの CI 必須ゲートおよび `CLAUDE.md` の必須テスト手順は、`AOT` 主体と Chicory 最小回帰ゲートの併用構成へ切り替えます。

* `AOT` 主体側の PR 必須スイート
* Wasmtime 参照実装側の PR 必須スイート
* Chicory 最小回帰側の PR 必須スイート
* 条件起動および定期実行へ移行するスイート

`AOT` 主体側では、Phase 1・Phase 2 を含む `AotConformanceTest`、Phase 2 経路の厳密境界・リソース寿命テスト、`AotDifferentialTest`、`AotPhase2DifferentialTest`、`AotGeneratedBindingsTest`、`AotAsyncE2ETest`、`Wasi03E2ETest`、および `samples/android` を `AOT` 中心へ切り替えた上での `emulator` ジョブ `connectedAndroidTest` を PR 必須とします。Wasmtime 参照実装側では、`rust-bridge` のテストおよび型付きブリッジの `TypedBridgeDifferentialTest` である `typedBridgeTest` を PR 必須として維持します。

Chicory 最小回帰側では、`ConformanceTest` の `async` 群と、`:runtime-chicory:test` 内の `AsyncAbiTest`・`SuspendingCallsTest`、ならびに `ChicoryComponent.fromDirectory` とフォールバック経路を確認するスモークテストを PR 必須に残します。これにより [ADR-0018](0018-async-abi-on-chicory.md) および [ADR-0019](0019-suspending-async-boundary.md) の非同期不変条件を PR 時点で継続検証します。

一方、網羅的な `ChicoryDifferentialTest` など Chicory の厚い同期スイートは PR ごとの常時実行から外し、条件起動と `schedule` トリガーの定期実行へ移行します。現在の `.github/workflows/ci.yml` は `:integration-tests:test` を一括実行しておりパスフィルタや `schedule` 設定を持たないため、実装 PR においてテストフィルタの分割、`runtime-chicory/`・`runtime-canonical/`・`runtime-api/`・`CoreModuleCompiler`・`JvmMethodSplitter`・`generator/` へのパス判定導入、および定期実行ジョブを追加します。あわせて、定期実行が失敗した際の通知および GitHub Issue 起票などのトリアージ手順も運用ルールとして定めます。

### 既存 ADR とドキュメントの更新範囲

既存の [ADR-0001](0001-chicory-backend-for-android.md)、[ADR-0005](0005-async-and-wasi03-chicory-only.md)、[ADR-0015](0015-binary-value-boundary.md)、および [ADR-0021](0021-split-large-chicory-methods.md) の本文は書き換えません。本 ADR が承認され、既定切り替えの実装 PR をマージする段階で、`docs/adr/README.md` のステータス欄へ注記を加えます。具体的には ADR-0001 と ADR-0021 の位置づけ変更注記に加えて、Phase 2 例外が既定になる点を示す注記を ADR-0015 に、非同期の既定対象も `AOT` へ広がる点を示す注記を ADR-0005 に追加します。さらに `:runtime-wasmtime` が JVM 21 と preview を用いるのに対し、純 JVM 系および WASI の `:runtime-api`・`:runtime-canonical`・`:runtime-aot`・`:runtime-chicory`・`:runtime-wasi` の 5 モジュールでは JVM 17 ターゲットと `animalsnifferMain` による Android API 26 互換検査を継続します。

実装 PR では、すでに現状と乖離している記述や既定変更に伴う対象を一括更新します。主な更新対象は `Wasm2KtPlugin.kt` の KDoc における `CHICORY` の既定表記・`failOnUnsupportedFeatures` の警告のみおよび非同期不可記述、`docs/explanation/performance.md` の 7.6 節と 8.3 節の同期限定記述、`docs/reference/gradle-plugin.md`、`docs/STATUS.md`、`CLAUDE.md` のモジュール説明・`runtime-wasi` を含む JVM 17 不変条件・必須テスト手順、`CONTRIBUTING.md`、`.github/workflows/ci.yml`、`CHANGELOG.md` の `Unreleased` 欄、および ADR-0023 の結果節で指定されたドキュメント群です。

## 結果とトレードオフ

本決定により、既定切り替え前に欠けていた ARM64 ART 計算系ベンチマーク、非同期計測、および同期 Phase 2 経路の公式適合と厳密境界の根拠を揃えた上で、同期・非同期の両面で `AOT` 既定へ移行できます。`with` と `:runtime-wasi` を伴わない同期コンポーネントの利用者は追加設定なしで R8 除去と起動時間短縮を得られ、開発と CI のリソースも `AOT` と `:runtime-canonical` に集中できます。

一方で、次のトレードオフが存在します。第 1 に、`withSession` の型変化、未対応命令のビルド停止、および不要 API の削除・可視性縮小によって既存のソース互換が壊れる場面が生じます。第 2 に、非同期コンポーネントおよび `with` や `:runtime-wasi` 併用構成では、Issue #206 の検討結果が出るまでワイヤ層の R8 除去が効きません。第 3 に、Chicory 側の網羅的同期テストを関連パス変更時の条件起動と定期実行へ移すため、関連外の共有変更が Chicory の同期経路のみへ与える回帰の発見が PR 時より遅れる余地があります。非同期経路と `ChicoryComponent.fromDirectory` 経路および関連パス変更時ゲートを残すことで、主要な副次役割への検知性は保ちます。

本 ADR の承認後の実施順序は、第 1 段階として前提データ取得と同期 Phase 2 経路の適合・厳密境界検証を完了させ、第 2 段階として既定値切り替え、`CHANGELOG.md` 更新、`samples/android`・ベンチマーク・CI・ドキュメントの一括更新を行います。これと並行し、別リポジトリの `android-glitch-camera` の追従、Issue #205 の `echoBytes1024` 調査、および Issue #206 の非同期ワイヤ層 R8 除去検討を進めます。
