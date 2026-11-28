# 0025. AOT バックエンドの非同期および WASI 0.3 コンポーネントにおけるワイヤ層 R8 除去の方針

* ステータス: 提案
* 日付: 2026-11-28
* 関連: [ADR-0005](0005-async-and-wasi03-chicory-only.md)、[ADR-0007](0007-canonical-abi-validation.md)、[ADR-0008](0008-host-defined-resources.md)、[ADR-0014](0014-interface-mappings.md)、[ADR-0015](0015-binary-value-boundary.md)、[ADR-0018](0018-async-abi-on-chicory.md)、[ADR-0019](0019-suspending-async-boundary.md)、[ADR-0022](0022-wasi03-scope-and-host-configuration.md)、[ADR-0023](0023-custom-wasm-compiler-and-static-jvm-backend.md)、[ADR-0024](0024-default-backend-and-chicory-role.md)、Issue #180、Issue #188、Issue #203、Issue #204、Issue #206

## 背景

[ADR-0023](0023-custom-wasm-compiler-and-static-jvm-backend.md) の Phase 2 では、同期 `AOT` コンポーネント向けに `DirectCanonicalAbi` を用いた静的直接結合を導入しました。外部ライブラリ対応付け `with` と `:runtime-wasi` を伴わない同期構成では、`WitWriter`、`WitReader`、`ComponentBackend`、`Linker`、`CanonicalAbi` といったワイヤ層クラスを R8 で除去できます。たとえば `heavy` コンポーネントで 1 つの関数のみを呼び出す Android リリース APK では、非圧縮 DEX が `26,224 B` まで縮小し、残存するワイヤ系クラスは `0` 件です。

一方、Phase 3 の非同期コンポーネントと WASI 0.3 コンポーネントは `AotComponentBackend` を経由します。生成された `<Name>Component` が `ComponentBackendFactory` を実装し、`WitWriter`、`WitReader`、および `:runtime-canonical` の `AsyncAbi` を通過する構造です。[ADR-0005](0005-async-and-wasi03-chicory-only.md) の段階では Chicory のみが非同期対象でしたが、ADR-0023 と [ADR-0024](0024-default-backend-and-chicory-role.md) で既定バックエンドが `AOT` へ広がった現在も、非同期および WASI 0.3 構成では R8 によるワイヤ層除去が働きません。この実現性と費用対効果を [Issue #206](https://github.com/chikoski/wasm-component-kotlin/issues/206) で検討することになりました。

検討対象は次の 4 項目です。

* 非同期境界とストリームおよび将来値の静的 Lower と Lift 化の実現性
* 既存外部ライブラリ境界と WASI 0.3 ランタイムとの整合性
* R8 適用後の DEX 内訳に対するコード生成と保守コストの評価
* 採用可否および設計方針の整理

## 調査結果

### 非同期境界と `stream` および `future` の静的 Lower と Lift 化の実現性

非同期エクスポートとコンポーネントローカルなホストインポートの引数・結果自体は、型付きコールバックによる静的 Lower と Lift 化が可能です。`AsyncAbi` の `startExport` や `invokeSuspending` が受け取る `WitReader` 境界は、Task 生成時の `onStart` クロージャと解決時の `onResolve` クロージャへ静的エンコード・デコード処理を埋め込むことでバイナリ列を省けます。同様にローカルなホストインポートを呼び出す `callHostAsync` と `callHostSync` も、引数読み出しと結果書き込みを静的コードへ置き換えられます。`error-context-new` と `error-context-debug-message` が `WitType.Str` のために使う `Lifter` と `Lowerer` も、`DirectCanonicalAbi` へ容易に置換できます。

しかし、`stream` と `future` のデータ転送経路が純ローカル構成におけるワイヤ層除去の構造的な障壁になります。`AsyncAbi` の `GuestBuffer`、`HostReadableBuffer`、および `HostWritableBuffer` は、`stream` や `future` のコピー時に線形メモリとバッファの間で値を `WitWriter` と `WitReader` のバイナリ形式にシリアライズします。特にゲスト間やホスト間でやり取りされる要素には、`TransferContext` を介して所有資源、`stream`、`future`、`error-context` などのハンドルが含まれうる契約です。さらに、合成コンポーネントの内部で使われる `stream-transfer` や `future-transfer` といったテーブルは、ワールドの公開 WIT 表面ではなく分解後の `BuildPlan` 上のテーブル単位で定義されます。

加えて、型特化型の転送コーデックは多様な異常系と非同期境界条件を忠実に再現する必要があります。特に注意が必要な境界条件は次のとおりです。

* 部分コピーと長さ 0 およびクローズ・ドロップ時の状態遷移
* `lowerErrorContextFromHost` 相当のロールバック処理
* 転送経路における `borrow` ハンドル拒否トラップ
* 所有資源の表現整数 `rep` 移送契約
* 同一コンポーネントまたは別タスク間の `stream-transfer` とキャンセル時点
* トラップ発生時のインスタンス poison 境界

また、実行モデルの不変条件との整合性も課題になります。[ADR-0019](0019-suspending-async-boundary.md) により、非同期状態は単一の `SessionLoop` スレッド上でのみ操作され、コルーチン境界を介して `HostResourceTable` を引き継ぎます。型付き経路を `:runtime-canonical` へ置けば `:runtime-chicory` と共有する契約を肥大化させ、逆に `:runtime-aot` と生成コードへ分離すれば AOT と Chicory で仕様挙動が乖離する危険が生じます。

この構造をバイナリ境界なしで実現するには、大きな生成系とランタイムの拡張が必要です。すべての `stream` テーブルと `future` テーブルについて上記の状態遷移・ロールバック・トラップを再現した型特化型 `GuestTransferCodec` クラス群を生成し、さらに `witStream`、`witFuture`、および `ComponentBackend.current` に依存しない AOT 専用の受け渡し層を `WitToKotlinPoetConverter` と `:runtime-aot` へ新設することになります。加えて、`:runtime-chicory` と共有する `1,737` 行の `AsyncAbi` 内部でバイナリ経路と型付き経路の二重実装を保守する負荷が発生します。

### `:runtime-wasi` および `with` 外部ライブラリ境界との整合性

[ADR-0014](0014-interface-mappings.md) と [ADR-0022](0022-wasi03-scope-and-host-configuration.md) の契約上、`:runtime-wasi` および `with` 対応付けを使うコンポーネントはワイヤ層を除去できません。ADR-0014 に基づく事前コンパイル済みライブラリは `--interfaces-only` でバックエンド非依存に生成され、`Linker` へ登録された `WitWriter` と `WitReader` の境界を通じてホスト実装と通信します。共通補助型である `WitResult` や `WitOption` もライブラリ側と利用者側で別パッケージの別クラスとなるため、Kotlin のオブジェクト参照をそのまま共有する方式へは移行できません。

現行の同期 Phase 2 経路でも、`with` されたインポートが存在する場合は `AotMappedImportBridge` を経由して `WitWriter`、`WitReader`、`Lowerer`、`Lifter` へフォールバックします。WASI 0.3 コンポーネントの大部分は `wasi03Imports` をはじめとする `:runtime-wasi` のマップ済みインポートに依存するため、R8 除去を WASI 0.3 まで広げるには ADR-0014 と ADR-0022 のライブラリバイナリ契約自体を破壊的に再設計する必要があります。

### R8 適用後の DEX 内訳と性能および保守コストの評価

実測による定量的根拠を得るため、コミット `e4180bd`、AGP `9.5.2`、Kotlin `2.4.21`、`compileSdk 35`、`minSdk 26`、および R8 最適化有効の条件で最小の Android アプリを構築し、Release APK の `classes.dex` と `mapping.txt` を解析しました。対象は純ローカルの非同期コンポーネントである `fixtures/async-pipeline.wasm` の全 3 エクスポート呼び出し構成と、`addWasiDependency` を有効にして `wasi03Imports` 経由で `runSummary` を呼び出す `fixtures/wasi-03.wasm` 構成です。なお、[performance.md](../explanation/performance.md) §9.3 の「DEX クラス数」と本 ADR の「定義済みクラス数」はいずれも DEX ヘッダの `class_defs_size` を指しています。今回の計測値が §9.3 の `bd002af` 時点の参考値である `async-pipeline` の APK `184,337 B`・DEX `329,308 B`・`375` クラスおよび `wasi-03` の APK `218,026 B`・DEX `398,052 B`・`462` クラスより大きいのは、`async-pipeline` 側で全 3 エクスポートと `kotlinx.coroutines.async` を呼び出した点に加え、双方のアプリが `MainActivity` 上の `TextView` 表示や `ByteArrayOutputStream` を保持し、さらに `e4180bd` までのランタイム変更と AGP `9.5.2` 環境で再構築したためです。

層の分類は難読化前のクラス名に基づき、パッケージおよび役割単位で行いました。ワイヤ層の上限把握のため、`WitWriter`・`WitReader`・`WitStreamChunk`・`ComponentBackend`・`Linker` 群、`CanonicalAbi`・`Lowerer`・`Lifter`・`WitType`・`FuncType` 群、ならびに `AotComponentBackend`・各種記述子・`AotCanonOptions`・`AotCoreFunction`・`AotPostReturn` の計 76 個の未縮約クラスファイルを抽出し、`d8 --release` で単独 DEX 化したサイズも計測しました。

未縮約状態のワイヤ層 76 クラスファイルを `d8 --release` で DEX 化したサイズは `157,024 B` でした。これに対し、実際の R8 適用後における `async-pipeline` アプリの Release APK は `192,512 B`、非圧縮 DEX は `344,280 B`、定義済みクラス数は `396`、DEX メソッド ID 数は `1,942` です。定義済みメソッド総数は `1,556` 件、Dalvik 命令バイト総数は `207,446 B` であり、層別の内訳は次の結果となりました。

| `async-pipeline` の層別内訳 | クラス数 | 定義メソッド数 | Dalvik 命令バイト数 | 命令全体比 |
| :--- | ---: | ---: | ---: | ---: |
| `kotlinx.coroutines` | 116 | 556 | 90,318 | 43.54% |
| `kotlin-stdlib` | 93 | 371 | 44,214 | 21.31% |
| `AsyncAbi` と `Concurrency` 中核層 | 79 | 218 | 29,016 | 13.99% |
| 生成 DSL とコンポーネント層 | 19 | 54 | 10,370 | 5.00% |
| `AotComponentBackend` 群 | 16 | 50 | 9,812 | 4.73% |
| 生成 Core Wasm クラス群 | 3 | 7 | 9,188 | 4.43% |
| `runtime-api` 非同期ホスト補助層 | 10 | 29 | 4,626 | 2.23% |
| `runtime-aot` 実行基盤層 | 13 | 65 | 3,666 | 1.77% |
| `CanonicalAbi`・`Lowerer`・`Lifter`・`WitType`・`FuncType` | 23 | 134 | 2,640 | 1.27% |
| `WitWriter`・`WitReader`・`ComponentBackend`・`Linker` | 9 | 38 | 2,222 | 1.07% |
| `MainActivity` と `DirectCanonicalAbi` 共通補助 | 15 | 34 | 1,374 | 0.66% |
| 合計 | 396 | 1,556 | 207,446 | 100.00% |

`async-pipeline` のうち、純粋なバイナリ直列化と `ComponentBackend`・`Linker` インタフェース層は `32` クラスかつ `4,862 B` であり、Dalvik 命令バイト全体の `2.34%` にとどまります。`AotComponentBackend` 群 `9,812 B` をすべて合算した上限でも `48` クラス、`222` 定義メソッド、命令 `14,674 B` で全体の `7.07%` です。`AotComponentBackend` 群の内部にはワイヤ除去後も `AotAsyncHost` やテーブル・メモリ・コールバック登録として必須となるコードが含まれます。なお、クラスを除去すると命令バイト以外にも文字列・型・メソッド ID のテーブル領域が減るため非圧縮 DEX 全体への比率は命令比より大きく出ますが、未縮約 76 クラス全体の `157,024 B` に対して R8 がすでに使用型と使用メソッドへ強く絞り込んでいるため、代替の型付き `stream`・`future` コーデック生成による増加と差し引きすると純減幅は限定的です。一方、DEX 命令の大半は非同期実行に必須の `kotlinx.coroutines`、`kotlin-stdlib`、および `AsyncAbi`・`Concurrency` が合計 `78.84%` を占めています。

`wasi-03` に `runtime-wasi` を組み合わせた構成でも同様の傾向を確認しました。`wasi-03` の Release APK は `238,907 B`、非圧縮 DEX は `444,348 B`、定義済みクラス数は `529`、定義済みメソッド数は `2,076`、DEX メソッド ID 数は `2,558`、Dalvik 命令バイト合計は `261,070 B` です。主要な占有層は `kotlinx.coroutines` が `115` クラスかつ `86,694 B` で `33.21%`、`kotlin-stdlib` が `111` クラスかつ `59,058 B` で `22.62%`、`AsyncAbi` と `Concurrency` 中核層が `81` クラスかつ `30,074 B` で `11.52%`、`WasiFilesystem` 関連が `82` クラスかつ `21,164 B` で `8.11%` でした。ワイヤ層全体の上限は `AotComponentBackend` 群を含めて `56` クラス、定義メソッド `304` 件、Dalvik 命令 `18,534 B` の `7.10%` であり、純粋なバイナリワイヤ層のみでは `39` クラス、Dalvik 命令 `7,326 B` の `2.81%` です。

実行性能への寄与も同期経路とは異なると考えられます。[performance.md](../explanation/performance.md) の実測では、同期 `add` のように数ナノ秒で完了する呼び出しは Phase 2 の直結化により `183.5 ns` から `14.8 ns` へ短縮し、`echo-bytes` 1 KiB でも `342.5 ns` から `73.6 ns` へ短縮しました。対して非同期定常呼び出しの所要時間は `async-stream` の `double` で `18.5 µs`、`sum` で `195.3 µs`、`async-pipeline` の `process-items` で `114.4 µs`、`wasi-03` の `run-summary` で `1.98 ms` です。`wasi-03` の `run-summary` には `1.0 ms` の `monotonic_clock::wait_for` 待機が含まれタイマー待機と入出力が支配的である一方、メモリ内で完結する `async-stream` と `async-pipeline` でも実行時間は数十から数百マイクロ秒に達します。今回非同期呼び出し内の段階別プロファイルは取得していないものの、同期 Phase 1 と Phase 2 の差分から読み取れる数百ナノ秒規模のワイヤ層オーバーヘッドは非同期実行時間全体に対して小さく、`async-stream` や `async-pipeline` では `SessionLoop` によるコルーチンディスパッチや `GuestThread` のセマフォ切り替えが支配的と見積もれます。

保守コストの面では変更対象が多層に広がります。`WitToKotlinPoetConverter.kt` は `4,297` 行、`DirectAotComponentEmitter.kt` は `1,954` 行、`AsyncAbi.kt` は `1,737` 行、`Concurrency.kt` は `785` 行に達しています。公式適合テストスイートは `conformance/component-model/test/async/` 配下の `validate-*` 3 件を含む `47` ファイルを対象として `:runtime-chicory` と共有の `AsyncAbi` 上で維持しており、非同期専用の直結コード生成と二重経路の維持は今後の仕様追従とバグ修正の負荷を大きく引き上げます。

## 検討した選択肢

* 現行のワイヤ層と共有非同期 ABI の維持
* 純ローカル非同期コンポーネントのみ静的直結化
* 外部ライブラリ契約を再設計した全体直結化
* ワイヤ層を残したままの未使用エクスポート除去

第 1 の選択肢は、Phase 3 の非同期および WASI 0.3 コンポーネントにおいて現行の `AotComponentBackend` と共有 `AsyncAbi` を維持し、ワイヤ層 R8 除去を導入しない方針です。既存テストと外部ライブラリ互換をそのまま保てますが、Release APK の DEX から `WitWriter`・`WitReader`・`CanonicalAbi` などの命令比約 `7%` 以下のワイヤ層は除去されず、`AotComponentBackend.setExports` 配列経由の全参照によって未使用エクスポート単位の関数除去も効かないまま残ります。

第 2 の選択肢は、`with` と `:runtime-wasi` を伴わない純ローカル非同期コンポーネントに対してのみ静的直結化を導入する方針です。純ローカル構成向けの実装の中でも、完全直結案・同期関数混在案・段階直結案の 3 つの変種がありえます。完全直結案は `stream` と `future` を含め型特化コーデックを生成してワイヤ層を完全に除去しますが、前述の境界条件や異常系を二重実装する保守コストに対し、削減対象が R8 後の `async-pipeline` で命令比 `7.07%` 未満にとどまり生成コーデックの増加で一部相殺されます。非同期コンポーネント内の同期関数だけを Phase 2 経路へ分岐する混在案や、`stream`・`future` 転送を既存の `GuestBuffer` へ残して引数と結果だけを静的に Lower・Lift する段階案は、`Lifter`・`Lowerer`・`WitWriter`・`WitReader`・`WitType` が結局 APK へ残存するためワイヤ層除去の主目的を達成できません。

第 3 の選択肢は、[ADR-0014](0014-interface-mappings.md) と [ADR-0022](0022-wasi03-scope-and-host-configuration.md) を再設計し、`:runtime-wasi` を含むすべての非同期コンポーネントで直接結合とワイヤ層除去を行う方針です。理論上は WASI 0.3 構成でもバイナリワイヤ境界を解消できますが、事前コンパイル済みライブラリを特定の内部 ABI と共通型パッケージへ強く結合させることになり、設計上の独立性と後方互換性を損ないます。

第 4 の選択肢は、`WitWriter`・`AsyncAbi`・`AotComponentBackend` のワイヤ境界を残したまま、未使用エクスポートの到達不能コード除去だけを R8 で有効にする部分適用方針です。現在は `AotComponentBackend.setExports` が全 `AotExportDesc` を配列で保持しているため、一部関数しか呼ばれない巨大コンポーネントでは使われない `CoreModule` 関数が保持されます。生成コードの各エクスポートメソッドが個別の `AotExportDesc` を直接渡す形へ改めれば、`heavy` の同期版で見られたような巨大 Wasm 関数の削減を二重 ABI 実装なしで得られる見込みがあります。ただし、すべてのエクスポートを呼ぶ構成や小規模コンポーネントでは効果がなく、ワイヤ層クラス自体は残るため、独立した最適化課題として本案とは切り離して扱えます。

## 決定

第 1 の選択肢を採用し、Phase 3 の非同期および WASI 0.3 の `AOT` コンポーネントにはワイヤ層 R8 除去を導入しません。非同期コンポーネントは引き続き `AotComponentBackend` と `:runtime-canonical` の共有 `AsyncAbi` 経由で実行します。

この判断の主な理由は費用対効果の小ささにあります。非同期アプリでは `kotlinx.coroutines` と `kotlin-stdlib` および `AsyncAbi`・`Concurrency` がすでに命令バイト数の `78.84%` を占めており、R8 縮小後のワイヤ層全体の上限は `7.1%` 程度、純粋な直列化境界に限れば `2.3%`〜`2.8%` です。`stream` や `future` の型特化転送コード生成と `AsyncAbi` 二重化の保守コスト、および異常系・スレッド不変条件へのリスクに見合うサイズ削減および速度改善は得られません。さらに WASI 0.3 では ADR-0014 のライブラリ境界が必須となるため、純ローカル向け実装の効果範囲も限定的です。

一方で、非同期および WASI 0.3 アプリの APK サイズを抑える実務的な改善策は別の非破壊的手段で確保できます。具体的には次の 2 点を推奨および将来の個別課題として整理します。

* 必要な WASI インタフェースの選択的登録
* 第 4 の選択肢としての未使用エクスポート R8 除去の個別検討

`wasi-03` のように `wasi:filesystem` を import するコンポーネントでは `WasiFilesystem` が必須となるため除去できませんが、そもそもコンポーネントが `wasi:filesystem` を import しない構成であれば、`wasi03Imports` は使わずにセッションごとに新しい `Linker` を返す `ImportsFactory` で包んだ上で `addWasiClocks` や `addWasiRandom` など必要なインタフェースだけを個別登録する方が有利です。なお、`Wasi03.Builder.includeFilesystem` に `false` を指定しても、`Wasi03.Builder.build` が `WasiFilesystemTypes` と `WasiPreopens` を無条件に生成するため、R8 は `WasiFilesystem` 関連クラスを除去できません。また、第 4 の選択肢である `AotComponentBackend` のエクスポート配列参照の解消は、将来数 MB 級の巨大非同期コンポーネントで未使用エクスポートが APK サイズを圧迫する事例が現れた際に独立した Issue として起票し検討します。

この決定は前提条件が変化した場合に再検討します。具体的な再検討トリガーは、外部ライブラリを伴わない純ローカル非同期の Android アプリで Dalvik 命令バイト比または非圧縮 DEX 占有比の `20%` を超える規模のワイヤ関連クラス残存や APK 上限超過といった実害が確認された場合、または将来 [ADR-0014](0014-interface-mappings.md) のライブラリ ABI 自体を刷新する計画を立てる場合です。

## 結果

本決定により、`:runtime-aot` と `:runtime-chicory` の双方が単一の `AsyncAbi` と `Concurrency` 実装を共有し続ける体制が維持されます。[ADR-0014](0014-interface-mappings.md) の `with` 契約と [ADR-0022](0022-wasi03-scope-and-host-configuration.md) の `:runtime-wasi` API、および JVM 17 と Android API 26 互換の不変条件は変更不要となり、公式適合テスト `async/` の一致性を保ったまま保守範囲を抑えられます。同期 `AOT` コンポーネントにおける Phase 2 静的直結と R8 ワイヤ層除去の動作にも影響はありません。

なお、本 ADR と [performance.md](../explanation/performance.md) §9 の計測によって、承認済み [ADR-0024](0024-default-backend-and-chicory-role.md) の背景節にあった「非同期 `AOT` のサイズと実行速度の実測値はまだ取得されていません」という前提はすでに解消されたものとして扱います。同様に [ADR-0005](0005-async-and-wasi03-chicory-only.md) は既に ADR-0023 と ADR-0024 により補足・更新済みです。

一方で、非同期および WASI 0.3 の `AOT` コンポーネントをビルドした Android アプリでは、引き続き `WitWriter`、`WitReader`、`ComponentBackend`、`Linker`、`CanonicalAbi`、および `WitType` が Release DEX に残ります。また、`AotComponentBackend` のエクスポート配列経由で全エクスポートが参照されるため、非同期コンポーネントにおける未使用エクスポート関数の R8 除去も効かない状態を許容します。本 ADR の承認とマージに合わせて、`docs/STATUS.md` の Issue `#206` 項目および既知の制約欄へこの結論を反映します。
