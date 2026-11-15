# 0022. WASI 0.3 の対応範囲とホスト側設定 API の設計

* ステータス: 承認
* 日付: 2026-11-15

## 背景

[ADR-0005](0005-async-and-wasi03-chicory-only.md) の方針に基づき、Chicory バックエンド向けに `:runtime-wasi` モジュールを追加しました。これまでに `wasi:random@0.3.0`、`wasi:clocks@0.3.0`、`wasi:cli@0.3.0`、および [ADR-0020](0020-wasi03-filesystem-sandboxing-and-mounts.md) に沿った `wasi:filesystem@0.3.0` のホスト実装が揃っています。一方で、残るネットワーク系パッケージの扱いと、ホスト側からの提供範囲および設定方法の API が未確定でした。

1 つ目の論点は、`wasi:http@0.3.0` と `wasi:sockets@0.3.0` を P1 の `:runtime-wasi` に含めるかどうかの判断であり、Issue #100 の論点に対応します。`:runtime-wasi` は `:runtime-api` および `:runtime-chicory` とともに Android アプリへ同梱するため、JVM 11 および Android API 26 の互換性を保つ必要があります。`runtime-wasi` のビルドでは `animalsniffer` で Android API 26 のシグネチャを検査しています。Java 11 標準ライブラリの `java.net.http.HttpClient` は Android API 26 に存在しないため利用できません。また `wasi:sockets` は名前解決、TCP、UDP を含む広い API を持ち、ホスト側の通信許可範囲を制御する仕組みを要します。

2 つ目の論点は、WASI 0.3 インターフェースをホストへ登録して設定する API の設計であり、Issue #101 の論点に対応します。[ADR-0014](0014-interface-mappings.md) では、`imports` 関数における外部ライブラリの既定実装の受け渡し方法と Gradle プラグイン連携を本判断へ委ねていました。[ADR-0014](0014-interface-mappings.md) では既定値を付けない方針でしたが、本決定でマップ済みライブラリを合成しやすい形へ拡張します。既存の `Wasi03` クラスと `Linker.addWasi03` 拡張関数では環境変数、引数、標準入出力、マウント先を設定できます。しかし、提供するパッケージ群の選択や、セッションごとに独立した `ImportsFactory` としての合成には未対応でした。さらに、Gradle プラグインは [ADR-0004](0004-isolated-tooling-classloader.md) により設定時に `tooling` のクラスや `.wasm` の import 一覧へアクセスできないため、ビルド設定の自動判定ではなく明示的な依存追加の方法も決める必要があります。

## 検討した選択肢

WASI 0.3 のネットワーク系パッケージの扱いについては、次の 3 つの選択肢を検討しました。

* P1 での一括実装
* 対象外としての切り捨て
* P2 への切り出しと P1 対象 4 パッケージの確定

P1 でまとめて実装する案は WASI 0.3 の仕様を網羅できる点が長所です。しかし、Android API 26 互換の HTTP クライアント抽象とソケット権限制御の設計を終えるまで他の P1 課題へ進めなくなる点が短所です。対象外として切り捨てる案は実装負担がない反面、将来 HTTP やネットワーク機能が必要になった際の拡張経路が閉ざされます。P2 へ切り出す案は、外部依存やネットワーク権限なしに完結する 4 パッケージで P1 を実用化しつつ、HTTP とソケットをそれぞれ別 Issue として後から設計できます。

あわせて、P1 で未対応の `wasi:http` と `wasi:sockets` を import するコンポーネントの扱いについて、次の 2 案を比較しました。

* セッション開始時の `requireDefined` による拒否
* 呼び出し時にトラップを送出するスタブの自動登録

呼び出し時トラップのスタブを自動登録する案は、import するだけで関数を呼ばないコンポーネントでもセッションを開ける点が長所です。一方で、利用者が許可していないネットワーク能力をバックエンドが備えているかのように見せてしまいます。セッション開始時の `requireDefined` で拒否する案は [ADR-0011](0011-qualified-interface-keys.md) と [ADR-0020](0020-wasi03-filesystem-sandboxing-and-mounts.md) の明示的な能力境界と一致します。そのため、未実装パッケージに対してはスタブを置かず `requireDefined` で即座に失敗させる方針を選びました。

ホスト側で提供する WASI 0.3 パッケージ群の選択と構成 API については、次の 2 つの選択肢を比較しました。

* 既存の個別登録関数 `addWasiRandom` 等だけを用いる案
* `Wasi03.Builder` へ選択フラグを設け `wasi03Imports` ファクトリー関数を提供する案

既存の個別登録関数だけを用いる案は API を増やさずに済みます。しかし、環境変数やマウント先の DSL 設定とパッケージ選択を一元化できません。また `Linker` は単一インスタンスを返すため、セッションごとにストリームや記述子表を分離する `ImportsFactory` を利用者が毎回手で書く必要があります。`Wasi03.Builder` へ選択フラグを設け `wasi03Imports` を提供する案は、従来の `Linker.addWasi03` と共通の設定 DSL で能力選択とセッション分離を両立できます。

生成される `imports` 関数で WASI 0.3 と独自インターフェースを併用する方法については、次の 3 つの選択肢を比較しました。

* 既存の `imports` 関数自体のマップ済み引数をすべて `null` 許容にする案
* マニフェストへ既定ファクトリーを記述し無引数で自動供給する案
* 既存の `imports` 関数は変えず `base` 引数を取るオーバーロードを追加する案

既存の `imports` 関数自体でマップ済み引数を `null` 許容にする案は、呼び出し形式を 1 つに保てます。しかし、WASI 以外の通常ライブラリを [ADR-0014](0014-interface-mappings.md) でマップした場合にもコンパイル時の渡し忘れ検出が失われます。さらに単一引数の後方ラムダ構文へ影響する懸念もあります。マニフェストへ既定ファクトリーを記述して自動供給する案は、ホストが権限を意識しないまま WASI 実装が注入される点と、セッションごとの出力先設定を差し替えにくい点が短所です。

3 つ目の案では、既存の `imports` 関数をすべての引数が非 Null 必須のまま維持します。その上で、マップ済みインターフェースを含む world に限り `base: ImportsFactory` を先頭引数に取るオーバーロードを追加します。このオーバーロードではマップ済みインターフェースの引数のみ省略可能とし、コンポーネント固有のローカルなインターフェースは引き続き非 Null の必須引数とします。これにより既存のコンパイル時検査を損なわず、WASI 以外のライブラリ対応付けにも影響を与えずにボイラープレートを解消できます。

## 決定

### P1 の対象範囲と P2 への切り出し

`:runtime-wasi` における P1 の対応範囲は `wasi:random@0.3.0`、`wasi:clocks@0.3.0`、`wasi:cli@0.3.0`、`wasi:filesystem@0.3.0` の 4 パッケージに確定します。`wasi:http@0.3.0` と `wasi:sockets@0.3.0` は P1 の実装対象から外し、それぞれ P2 の Issue #117 と Issue #118 として追跡します。両パッケージの関数は `:runtime-wasi` へスタブ登録しないため、これらを import するコンポーネントは実際に呼び出さなくてもセッション開始時の `requireDefined` で失敗します。

### WASI 0.3 設定ビルダーとセッション分離

既存の `Wasi03` クラスは事前構築済みの実装インスタンス群を保持するため、`ImportsFactory` は実装しません。同じ `Wasi03` インスタンスを複数セッションで共有する使い方は `ImportsFactory` の非共有契約に反するためです。単一の `Linker` を明示的に共有する場合にのみ、従来どおり `Wasi03.registerOn` および `Linker.addWasi03` を使用します。

セッション単位で分離した構成を簡単に渡せるよう、`wasm2kt.wasi` パッケージのトップレベルに `wasi03Imports` 関数を追加します。`wasi03Imports` は生成時ではなくセッションを開く `newLinker` 呼び出しごとに設定ブロックと `Wasi03.Builder.build` の検証を実行します。これにより新しい `Linker` が生成され、`Wasi03` のインスタンス群もセッションごとに新規構築されます。

ストリームの分離も強化します。`WasiStdout` と `WasiStderr` は従来の `OutputStream` 直接受け取りに加えて `() -> OutputStream` の供給関数を受け付けるよう拡張し、直接生成した場合には初回の `writeViaStream` 呼び出し時に 1 回だけ供給関数を遅延評価して出力先を確定します。`Wasi03.Builder` には関数形式の `stdin`、`stdout`、`stderr` ヘルパーを設けます。`stdout` と `stderr` の供給関数は `includeCli` が `true` の場合に `Wasi03.Builder.build` 時に 1 回評価してその戻り値を `WasiStdout` と `WasiStderr` へ渡します。`includeCli` が `false` の場合は供給関数を呼び出さず `NullOutputStream` を使います。そのため同じセッション内で複数回ストリーム書き込みが行われても同一のストリームへ追記され、別セッションとは分離されます。また `stdout` や `stderr` のプロパティ代入と関数形式ヘルパーは後から設定した側が優先されます。`WasiStdout` と `WasiStderr` の `writeStreamToOutput` は書き込みごとに `flush` を行いますが、ホストから渡されたストリームの `close` は行いません。セッション終了後または遅延タスク完了後に外部ストリームを閉じる責務はホスト側にあります。また、`stdin` へ単一の `InputStream` インスタンスを直接渡す設定は 1 回目のセッションで読み切られるため、複数セッションで再利用する場合は供給関数または `ByteArray` 版を使用します。

### パッケージ群の選択フラグと境界条件

`Wasi03.Builder` に `includeRandom`、`includeClocks`、`includeCli`、`includeFilesystem` の 4 つの真偽値プロパティを追加します。既存の `Linker.addWasi03` との後方互換性を保つため、既定値はいずれも `true` とします。`wasi:filesystem` については [ADR-0020](0020-wasi03-filesystem-sandboxing-and-mounts.md) に従い `includeFilesystem` が `true` でも既定のマウント一覧は空であるため、明示的に `mounts` を書かない限りファイルシステム権限は与えられません。環境変数とコマンド引数も既定は空であり、標準入出力も空または破棄ストリームです。

`false` を指定したパッケージ群の関数は `Linker` へ登録しません。その結果、許可しないパッケージを要求するコンポーネントはセッション開始時の `requireDefined` で拒否されます。整合性の検査として、`includeFilesystem` が `false` の状態で `mounts`、`mount`、`filesystemTypes`、`preopens` のいずれかが設定されている場合は、`Wasi03.Builder.build` が `IllegalArgumentException` を投げます。`wasi03Imports` 経由ではセッション開始時の `newLinker` 呼び出し内で `Wasi03.Builder.build` が実行されるため、不正な設定の組み合わせはその時点で失敗します。`wasi:filesystem/types` が `wasi:clocks/system-clock` の時刻型を参照する点については、型参照自体にはホスト関数登録を要しないため、`includeClocks` が `false` でも `system-clock` の関数を呼ばない限り `wasi:filesystem` は動作します。

### Linker.addAll と ImportsFactory.plus の仕様

`:runtime-api` の既存の `Linker` と `ImportsFactory` を拡張し、JVM 11 と Android API 26 の範囲内で合成機能を提供します。既存の `ImportsFactory.EMPTY` 定数はそのまま活用します。`Linker` には `addAll` メソッドを追加し、別の `Linker` から同期ハンドラーと非同期ハンドラーの両方をコピーします。`overwrite` 引数の既定値は `false` とし、[ADR-0011](0011-qualified-interface-keys.md) に沿って重複キーが存在する場合は `IllegalArgumentException` で拒否します。`overwrite` に `true` を指定した場合のみ既存キーを上書きします。その際は上書き先の `asyncHandlers` も同時に更新または削除し、同期版で非同期版を上書きした後に古い非同期ハンドラーが残る不整合を防ぎます。

`ImportsFactory` には `+` 演算子を追加します。`left + right` は `left` を直接変更せず、呼び出しのたびに新しい `Linker` インスタンスを空から生成した上で `left.newLinker()` と `right.newLinker()` を既定の非上書きモード `overwrite = false` で順に `addAll` します。`Linker` 自身が `ImportsFactory` を実装して自身を返す仕様であっても、合成演算子によって元の `Linker` が書き換わることはありません。非上書きモードであるため `+` の結合順序に依存せず、`ImportsFactory.EMPTY` との結合は単位元として振る舞い、意図しないキー衝突は常にエラーとして検出されます。

### 生成される imports 関数のオーバーロード設計

`WitToKotlinPoetConverter` が生成する既存の `imports` 関数は、引数型および必須制約を変更せずそのまま維持します。既存のすべてのテストおよび単一引数の後方ラムダ構文は影響を受けません。

world が [ADR-0014](0014-interface-mappings.md) で外部ライブラリや `with` へ対応付けられたインターフェースを 1 つ以上 import している場合に限り、先頭に `base: ImportsFactory` を必須引数として受け取る 2 つ目の `imports` オーバーロードを追加生成します。world 内にすでに `base` という名前の import 引数がある場合は、衝突を避けるため先頭引数名を `baseImports` に切り替えます。なおこのオーバーロードは Kotlin のデフォルト引数を活用するため、`@JvmOverloads` は付与しません。

この `base` オーバーロードでは、マップ済みインターフェースの引数のみを `null` 許容の既定値 `null` とし、コンポーネント固有のローカルなインターフェースおよび world 直下の import は非 Null の必須引数として維持します。呼び出し時には新しい空の `Linker` へまず `base.newLinker()` の定義を `overwrite = false` でコピーします。続いてこの呼び出しで非 Null として渡された各インターフェース実装を一時 `Linker` へ登録し、その結果を最初の `Linker` へ `overwrite = true` で合流させます。これにより `wasi03Imports` を基盤にしつつ特定の WASI インターフェースだけを明示的に差し替える用法と、独自のホスト import をコンパイル時に必須検査する仕組みを両立できます。

### Gradle プラグインでの WASI 0.3 連携

`Wasm2KtExtension` に `addWasiDependency: Property<Boolean>` を追加し、既定値は `false` とします。[ADR-0004](0004-isolated-tooling-classloader.md) によりプラグインは設定時に `tooling` へアクセスして `.wasm` の import 一覧を調べられないため、すべての利用プロジェクトへ既定で `:runtime-wasi` を追加するのではなく利用者が明示的に有効化する方式を採りました。

`afterEvaluate` において、`addRuntimeDependency` と同じ条件である「`components` が空でなく `implementation` 構成が存在する」場合に、`addWasiDependency` が `true` であれば同一バージョンの `io.github.wasm2kt:runtime-wasi` を `implementation` へ追加します。この動作は `addRuntimeDependency` の設定値とは独立しています。`addWasiDependency` が `true` なのに `useLibraryInterfaces` が `false` の場合は、型が重複生成されて `wasi03Imports` と噛み合わなくなるため設定時に警告ログを出力します。また、コンポーネントが `wasi:*@0.2.0` など別版を import している場合は [ADR-0014](0014-interface-mappings.md) のバージョン不一致検査によりビルド時エラーとなります。なお、依存関係の追加はプロジェクト単位の設定であり、コンポーネント個別の細かな制御には引き続き `with` を用います。

## 結果とトレードオフ

この決定により、P1 の `:runtime-wasi` は外部依存を持たず JVM 11 と Android API 26 の両方で動作する範囲に保たれます。WASI 0.3 のみを使うコンポーネントは `wasi03Imports` だけで起動でき、独自インターフェースを併用するコンポーネントでも `imports` の `base` オーバーロードにより型安全性を損なわず簡潔に配線を記述できます。

検証計画としては、`runtime-api` と `runtime-wasi` の `animalsnifferMain` による Android API 26 適合検査、`generator` のユニットテスト、`Wasi03E2ETest` への `fixtures/wasi-03.wasm` 追加、`gradle-plugin` の `functionalTest` での `addWasiDependency` 実ビルド検証に加え、生成コードへの影響がないことを `ChicoryDifferentialTest` と `ConformanceTest` で確認します。判断結果と現状の更新は `docs/STATUS.md` と `docs/reference/feature-support.md` に反映し、公開 API 追加の概要は `CHANGELOG.md` の確認対象とします。

残る制約とトレードオフは次のとおりです。

* `wasi:http` と `wasi:sockets` を import するコンポーネントの起動不可
* `base` オーバーロード利用時における未登録 WASI キーの実行時検出
* `addWasiDependency` のプロジェクト単位適用と明示有効化

`wasi:http` と `wasi:sockets` については P2 の Issue #117 と Issue #118 が完了するまで利用できません。`imports` の `base` オーバーロードを使う場合は、マップ済みインターフェースが `base` 側で無効化されていないかの検証がセッション開始時の `requireDefined` で行われます。
