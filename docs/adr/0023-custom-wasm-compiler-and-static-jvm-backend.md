# 0023. R8 と ART 最適化に向けた自作 Core Wasm コンパイラと静的 Kotlin 配線バックエンドの設計

* ステータス: 承認
* 日付: 2026-11-22 提案、2026-11-23 承認
* 関連: [ADR-0001](0001-chicory-backend-for-android.md)、[ADR-0002](0002-wasmtime-environ-decomposer.md)、[ADR-0004](0004-isolated-tooling-classloader.md)、[ADR-0005](0005-async-and-wasi03-chicory-only.md)、[ADR-0007](0007-canonical-abi-validation.md)、[ADR-0008](0008-host-defined-resources.md)、[ADR-0010](0010-wasmtime-lts.md)、[ADR-0011](0011-qualified-interface-keys.md)、[ADR-0013](0013-multi-memory-adapters.md)、[ADR-0014](0014-interface-mappings.md)、[ADR-0015](0015-binary-value-boundary.md)、[ADR-0017](0017-component-model-conformance-suite.md)、[ADR-0018](0018-async-abi-on-chicory.md)、[ADR-0019](0019-suspending-async-boundary.md)、[ADR-0021](0021-split-large-chicory-methods.md)、Issue #180、Issue #181

## 背景

[ADR-0001](0001-chicory-backend-for-android.md) で採用した現行の Chicory バックエンドでは、実行速度と APK サイズの両面で 2 つの層にわたる構造的な制約が生じています。第 1 の層は実行時の動的ディスパッチとメタデータ読み込みであり、第 2 の層は Chicory が生成するバイトコードとランタイム抽象化の構造です。

第 1 の層について、現行実装は実行時の初回 `openSession` 時に `plan.json` と `.meta` ファイルを読み込みます。エクスポート関数の呼び出しは `ComponentBackend.invoke`、`ChicoryComponentInstance`、`ExportFunction`、`Machine.call`、および入口ディスパッチの `call_dispatch_0` を経由します。Chicory が生成する `func_N` メソッド自体はすでにプリミティブ型引数と戻り値を持つ `public static` メソッドであり、`call_indirect_N` も型シグネチャ別に出力されています。しかし、`call_dispatch_0` や `ExportFunction` およびホスト import の境界で `long[]` へのボックス化と配列インデックスによるディスパッチが挟まるため、R8 はアプリケーションから到達可能な Wasm 関数を静的に絞れません。

サイズと起動時間の面でも、この実行時構造が影響しています。[performance.md](../explanation/performance.md) の計測では、R8 適用後の `samples/android` の DEX 333 KB のうち Chicory 関連が 147 KB、`plan.json` 解析用の `kotlinx.serialization` が 59 KB を占めています。`heavy` コンポーネントでは `.meta` も 432 KB になり、初回 `openSession` には ART 上で 57 ms から 239 ms を要しています。なお、この 333 KB の内訳には [ADR-0019](0019-suspending-async-boundary.md) で `:runtime-api` へ追加された `kotlinx-coroutines-core` が含まれておらず、新バックエンドも `:runtime-api` 経由でコルーチンを取り込みます。また、初回 `openSession` の 57 ms から 239 ms はマシンクラスのロードと `.meta`・`plan.json` 解析の合計であり、内訳は未計測です。クラスロードと生リソース読み込みは新バックエンドにも残るため、サイズと起動時間の比較は Phase 1 で同一コミット上の Chicory ベースラインを取り直して確認します。

第 2 の層について、Chicory バックエンドは実行時に `chicory-runtime` の `Instance` や `Memory` インタフェース越しのアクセス、および割り込み確認 `checkInterruption` に結合します。巨大な関数に対しては [ADR-0021](0021-split-large-chicory-methods.md) で ASM による事後分割器 `JvmMethodSplitter` を導入しましたが、次の 3 つの制約が残っています。

* JVM バイトコード 64 KB 上限超過によるインタプリタフォールバック
* `call_dispatch_0` 入口ディスパッチの未分割残存
* 事後分割に伴うスピル配列と行番号消失

`heavy` フィクスチャでは 56 KB の Wasm 関数である `func_366` が出力 JVM バイトコードで 64 KB 上限を超えるためバイトコードが生成されず、インタプリタ実行のまま残ります。`png-glitch-component.wasm` では名前フィルタ外の `call_dispatch_0` が 15,377 JVM バイトおよび 13,440 DEX コード単位のまま残り、HotSpot の 8,000 バイト上限を超過します。さらに `JvmMethodSplitter` は事後抽出の都合上 `long[]` スピル配列の確保と `LineNumberNode` の削除を要します。加えて、実行時は Chicory 1.7.5 のランタイム挙動と Endive 移行に結合した状態です。なお、ビルド時の `Decomposer` と `chicory-wasm` パーサー、および Chicory 1.7.5 のために `strictly` 制約で固定しているビルド時 ASM 9.9.1 は `:tooling` 側に引き続き残るため、本決定で解消するのは実行時の Chicory 依存に限定されます。

また、`roundtrip` のような単一コンポーネントでも、`Decomposer` が内部に用いる [ADR-0002](0002-wasmtime-environ-decomposer.md) の `wasmtime-environ` は複数のコアモジュール、共有 `$imports` テーブル、および `elem` セグメントによる遅延パッチを生成します。したがって同期コンポーネントの段階から、複数モジュール間の配線と共有テーブルを設計に織り込む必要があります。以上の実態を踏まえ、Issue #180 の親計画のもと新しい自作コンパイラおよび静的 Kotlin 配線バックエンドの方針を定めます。

## 検討した選択肢

### 出力ターゲットとコンパイラ調達方法

[ADR-0021](0021-split-large-chicory-methods.md) の時点では巨大メソッドの回避のみが目的であったため、全面自作は維持コストに見合わないとして退けました。今回はそれに加えて、実行時の `chicory-runtime` と `.meta` の排除、出力 JVM バイトコードが 64 KB を超える関数のインタプリタ落ち解消、および Phase 2 における World DSL からの静的直結による R8 最適化の両立を目指すため、次の 6 つの選択肢を再評価しました。

* Core Wasm 関数本体まで含めて Kotlin ソースを生成する案
* 現行 Chicory 出力の `func_N` を静的 Kotlin 配線から直接呼ぶ案
* Apache-2.0 の Chicory コンパイラをフォークし出力先を独自ランタイムへ差し替える案
* 上流 Chicory または Endive へ二段 `tableswitch` と関数分割を貢献する案
* `wasm2c` と Android NDK でネイティブ共有ライブラリ化する案
* 内部型付き IR を介して JVM `.class` を直接生成し配線だけを静的 Kotlin にする案

1 つ目の Kotlin ソース生成案は可読性と Kotlin Multiplatform 展開の余地が長所です。しかし、R8 が最適化対象にするのはコンパイル済みの `.class` であり、Kotlin ソース生成自体に R8 上の優位はありません。むしろ Wasm には任意ブロックへのラベル付き脱出があるため制御構造変換で一時変数が増え、1,000 関数超のモジュールでは `kotlinc` のビルド時間とメモリ負荷が増大します。出力時のバイト長を事前制御しにくい点も短所です。

2 つ目の案は Chicory の `func_N` がすでに `public static` かつプリミティブ型シグネチャであることを活かし、静的配線から直接 `func_N` を呼ぶ案です。これにより `call_dispatch_0`、`long[]` 入口ボックス化、および `plan.json` は回避できます。しかし、`chicory-runtime` の `Instance`・`Memory`・`checkInterruption` への実行時依存、JVM バイトコード 64 KB 超の関数におけるインタプリタ落ちと `JvmMethodSplitter` の `long[]` スピル、および `.meta` の 3 点がそのまま残るため、目的の一部しか達成できません。

3 つ目のフォーク案と 4 つ目の上流貢献案は Wasm 命令翻訳の資産を流用できる点が長所です。しかし、3 つ目のフォークは上流の内部設計に引きずられながら自前保守する点が自作と変わらず、`.meta` 削減や線形メモリの具象化にも広範な改修を要します。4 つ目の上流貢献案は他バックエンドの事情やリリース周期に左右され、R8 向けの独自配線と並行して短期検証を進める用途には合いません。

5 つ目の `wasm2c` と NDK 案は実行速度面で最有力です。ただし ABI ごとのネイティブバイナリ配布と保守が加わり、[ADR-0001](0001-chicory-backend-for-android.md) で採用したネイティブライブラリ不要という前提から外れるため却下しました。

6 つ目の案は、スタックマシンである JVM バイトコードへ Wasm 命令を直接写像し、IR と CFG 段階でバイトコード長を見積もりながら事前分割を行う案です。`kotlinc` 負荷を薄い静的配線のみに抑え、JVM バイトコードが 64 KB を超える関数も事前分割によりコンパイルでき、実行時 Chicory 依存と `.meta` を排除できます。保守コストは増えますが、第 1 マイルストーンで段階導入と計測を行えるため 6 つ目の案を採用します。なお、R8 では間接呼び出しテーブルに登録された関数を削除できないため、将来的には Binaryen の `wasm-metadce` 等による Wasm レベルの不要コード削除を前段で併用する余地も残ります。

### ビルド時の Wasm バイナリパーサー

ビルド時に `coreN.wasm` を読み込むパーサーの調達について、次の 3 案を比較しました。

* Kotlin で Wasm バイナリパーサーをゼロから自作する案
* `decomposer` 側の Rust 製 `wasmparser` から独自 IR を出力させる案
* ビルド時に限り `chicory-wasm` の `Parser` をアダプタ越しに利用する案

1 つ目の案はビルド時依存を減らせますが、LEB128 復号や形式検証の維持が本題から外れます。2 つ目の案は分解成果物の交換形式を拡張して結合度を上げる点が短所です。3 つ目の案は既存の `chicory-wasm` パーサーを使い、変換アダプタ 1 箇所で内部型付き IR へ正規化することで、コンパイラ本体とランタイムを Chicory 非依存に保てます。よって 3 つ目の案を選びました。

### Canonical ABI 実行層の配置と共有

Phase 1 では `:runtime-aot` から実行時の Chicory と `plan.json` への依存を外しつつ、`ComponentBackend` 互換層として動作させます。現在 `CanonicalAbi`、`Transcoder`、`WitType`、および `HandleTable` や `Concurrency` を含む状態管理はすべて `:runtime-chicory` にあり、`HandleTable` は resource、waitable、stream、future で単一の番号空間を共有して `ResourceHandle.borrowScope` に `Task` 型を持ちます。さらに Phase 3 では AOT でも Async ABI を扱う方針であるため、配置方針として次の 3 案を検討しました。

* Chicory 非依存の共通モジュール `:runtime-canonical` を抽出して段階的に共有する案
* Phase 1 と Phase 2 を統合し最初から静的 Lower・Lift のみで置き換える案
* `:runtime-aot` 内に独立した Canonical ABI および Async ABI 実装を新規作成する案

3 つ目の独立実装案は、[ADR-0013](0013-multi-memory-adapters.md) で「仕様の実装が 2 つになる」ことを理由に退けたのと同じ二重保守を招きます。2 つ目の統合案は R8 向けの静的コード生成まで同時に実装が必要となり、まずコア Wasm コンパイラの正確性と基礎性能を切り分けて検証する段階導入の利点が薄れます。1 つ目の共通モジュール `:runtime-canonical` 抽出案は、メモリ・実行器・タスク借用範囲の薄い抽象を介して単一の Canonical ABI および Async 状態管理を共有できるため、1 つ目の案を採用します。

### 移行方式と線形メモリ表現

移行方式は一括置換ではなく、既存バックエンドと並存するオプトインの段階移行を選びます。Phase 1 で `ComponentBackend` 互換層から立ち上げて同期差分テストと実測比較を行い、Phase 2 で静的直結へ進み、Phase 3 で Async と WASI 0.3 を扱います。

[CLAUDE.md](../../CLAUDE.md) により Android 向けランタイムは JVM 17 と Android API 26 互換が必須であるため、API 33 以降で追加された `VarHandle` は使えません。線形メモリ表現として `ByteBuffer` を用いる案はリトルエンディアン補助を持ちますが、抽象クラスによる仮想ディスパッチやバッファ状態管理が伴います。そこで `ByteArray` を直接保持する `final` な具象クラス `LinearMemory` を基本とし、必要に応じて Phase 1 のベンチマーク時に `ByteBuffer` との比較計測を行います。

## 決定

Core Wasm から内部型付き IR を構築して ASM で `.class` を直接出力し、コンポーネント配線を静的な Kotlin コードとして生成する新バックエンドを、既存の Chicory バックエンドと並存するオプトイン機能として導入します。

### モジュール構成と `:runtime-canonical` の境界

実行時モジュールとして次の 2 つを新設し、いずれも JVM ターゲット 17 と `animalsnifferMain` による Android API 26 シグネチャ検査を必須とします。

* `:runtime-canonical` の新設
* `:runtime-aot` の新設

`:runtime-canonical` はパッケージを `wasm2kt.runtime.canonical` とし、`:runtime-api` のみに依存し、`kotlinx.serialization` には依存しません。メモリ読み書きと `realloc` を表す最小の抽象インタフェース越しに `:runtime-chicory` と `:runtime-aot` の双方が利用します。

`:runtime-canonical` が持つ範囲は Phase 1 と Phase 3 で段階的に拡張します。Phase 1 では `CanonicalAbi`、`Transcoder`、`WitType`、検証ヘルパー、poison 状態と境界検査補助、および `HandleTable` と `ResourceHandle` を移管します。`HandleTable` は resource と非同期ハンドルで単一の番号空間を保つため、`ResourceHandle` が参照し借用数 `numBorrows` を管理する `BorrowScope` インタフェースを `:runtime-canonical` に定義します。Phase 1 の AOT バックエンドなど同期呼び出しでは従来通り `borrowScope` を `null` として呼び出し終了時に `checkBorrowsDropped` で検査し、`:runtime-chicory` の `Task` および Phase 3 の非同期タスクが `BorrowScope` を実装する形で単一の番号空間と借用規則を保ちます。また、Phase 1 の段階から `SYNC_ADAPTER_KINDS` と `usesAsync` の純粋な判定規則も `kotlinx.serialization` 非依存の関数として `:runtime-canonical` に配置します。Phase 3 では残る `Concurrency` と `AsyncAbi` も最小の実行器抽象越しに `:runtime-canonical` へ統合し、`:runtime-chicory` と `:runtime-aot` の双方で単一の Async ABI 実装を共有します。また、これまで `internal` であった Canonical ABI 関連型を生成コードからも利用できるよう、opt-in アノテーション `@InternalWasm2KtApi` を設けて公開し、同一バージョンの `wasm2kt` 生成コード間でのみバイナリ互換を保証します。

`:runtime-aot` はパッケージを `wasm2kt.runtime.aot` とし、`:runtime-api` と `:runtime-canonical` のみに依存します。Chicory、`:tooling`、および `kotlinx.serialization` へは依存しません。さらに R8 の keep ルールを不要にする前提として、`:runtime-canonical` および `:runtime-aot` の内部ではリフレクションと `ServiceLoader` を一切使用しません。

ビルド時の内部型付き IR、`chicory-wasm` 変換アダプタ、ASM バイトコード生成器、CFG 事前分割器、および静的配線生成器は `:tooling` に配置し、[ADR-0004](0004-isolated-tooling-classloader.md) の独立クラスローダー分離を維持します。ただし `:tooling` 内では Chicory 1.7.5 が残る間、新しい ASM 生成器も既存の ASM 9.9.1 の `strictly` 制約に従います。ビルド時の `Decomposer` と `:runtime-chicory` は当面維持し、`plan.json` および `wit.json` の形式は変更しません。

### 内部型付き IR と対応 Wasm 仕様

`chicory-wasm` の `Parser` で読み込んだモジュールは、変換アダプタ 1 箇所で直ちにパーサー非依存の内部型付き IR へ正規化します。内部 IR は型、インポート、エクスポート、グローバル、メモリ、テーブル、データと要素セグメント、ならびに各命令のオペランドスタック型とローカル変数型および制御ブロック構造を表現します。

対応する Wasm 仕様は次の機能群とします。

* MVP
* sign-extension
* non-trapping float-to-int
* bulk-memory
* reference-types
* multi-value
* multi-memory
* extended-const
* mutable-globals のインポートとエクスポート

[ADR-0013](0013-multi-memory-adapters.md) のアダプターモジュールが複数メモリを扱うため、multi-memory を対象に含めます。一方、SIMD128、exception-handling、tail-call、および Wasm GC は Phase 1 では対象外とし、`-Cpanic=unwind` や拡張機能を有効にしたコンポーネント等で未対応の命令やセクションを検出した場合は、実行時へ先送りせずビルド時にモジュール番号、関数インデックス、命令名を明示したエラーで停止します。

`exception-handling` を Phase 1 の対象外とする理由は、Wasm 境界におけるエラー表現の役割と `Decomposer` の制約によるものです。Wasm 内の業務エラーを Kotlin 側へ伝える正規の経路は WIT の `result` 型であり、Wasm の `exception-handling` 提案である `tag`、`try_table`、および `throw` はコア Wasm 内部のスタック巻き戻しに使われます。コンポーネント境界まで捕捉されずに到達した Wasm 例外は、仕様上トラップと poison として扱われます。加えて、現行の `Decomposer` は Chicory バックエンドの合成アダプターで `try_table` バリアが出力されないよう `WasmFeatures::EXCEPTIONS` を明示的に無効化しています。したがって Phase 1 は現行 `Decomposer` の仕様に合わせて対象外を維持し、将来必要になった場合はバックエンド別の `Decomposer` フラグ分離と CFG 事前分割における例外テーブル跨ぎ制約を整理した上で別フェーズの拡張として扱います。

`SIMD128` を Phase 1 の対象外とする理由は、実アプリのバイナリ構成と Android API 26 上での実行特性によるものです。`png-glitch` の Rust ソースには SIMD 経路が存在しますが、現在の `png-glitch-component.wasm` は `+simd128` を有効化せずにスカラー版としてビルドされており、SIMD128 命令を含みません。また、Android API 26 の JVM バイトコードには SIMD 命令も Vector API も存在しないため、`v128` を構成する約 240 命令をスカラー演算でエミュレーションしても ART 上での高速化は見込みにくく、コードサイズの増大と Phase 1 工数の増加を招きます。よって Phase 1 では対象外を維持し、手元で再ビルドできない外部の `+simd128` バイナリを動かす互換性要件が生じた際の将来拡張として位置づけます。なお、内部型付き IR の型定義には後から `v128` を追加できる拡張余地を残します。

### JVM バイトコード生成と事前分割および意味論検査

内部 IR から ASM を用いて `.class` ファイルを直接生成します。コンポーネントのエントリやモジュール間境界における `call_dispatch_0` と `long[]` ボックス化を廃し、静的配線からプリミティブ型引数と戻り値を持つ生成メソッドを直接呼び出します。多値返却については戻り値が 0 個または 1 個なら JVM の標準戻り値とし、2 個以上の場合のみ型組み合わせに応じた専用の結果ホルダを用いて割り当てを抑えます。生成するクラスファイルのバージョンは Java 17 相当に固定し、Android D8 および ART との互換性を保つため `invokedynamic` と定数プール動的エントリ `condy` を使用しないことを不変条件とします。出力するすべてのクラスファイルに対して、テストおよび検証経路で `CheckClassAdapter.verify` を必須とします。

クラスとメソッドの分割およびインスタンス状態の配置について、次の 3 つを規定します。1 つ目として、大規模モジュールでは 1 クラスあたりの定数プール上限 65,535 エントリを超えないよう `FuncGroup` 相当のクラス分割を行います。2 つ目として、同一コンポーネントから複数回 `openSession` で独立インスタンスを生成できるよう、線形メモリ、グローバル変数、およびテーブル等のインスタンス状態は生成インスタンス `CoreNInstance` のフィールドに保持し、生成する各 Wasm 関数はその具象インスタンスを引数に取る `static` メソッドとして出力します。これにより仮想呼び出しを避けながら R8 によるインライン化を促します。3 つ目として、IR および CFG 段階で生成バイトコード量を見積もり、7,500 JVM バイト前後を目安に補助メソッドへ事前分割します。

事前分割では `heavy.wasm` の 56 KB の Wasm 関数 `func_366` を含む JVM バイトコード 64 KB 超過の関数もインタプリタへ落とさずコンパイル対象に収めます。分割にあたっては、境界地点でのオペランドスタック深さ 0 と multi-value ブロック結果の受け渡し、ならびに外側ラベルへの `br` や `return` の制御伝達を IR 段階で設計します。ライブイン変数の引数が JVM の 255 スロット上限を超える場合やライブアウト変数が複数ある場合は、呼び出しまたはインスタンス単位で再利用可能なプリミティブ退避ホルダを用いて毎回 `long[]` を確保する方式を避けます。また、メソッド分割により呼び出しフレームが 1 段深くなる影響は設計トレードオフとして考慮し、行番号情報については Wasm バイトコードオフセットを `LineNumberTable` へ写像して診断性を保てる構造にします。データセグメントはクラス名に依存しない固定リソースパスを用いて生バイナリリソースとして出力し、R8 の `-adaptresourcefilenames` の影響を受けずに実行時の Wasm パースをなくします。

Wasm と JVM で意味論が異なる次の 4 つの演算については明示的な検査と写像およびビット列保持を行い、仕様テストの必須検証項目に含めます。

* 整数除算の 0 除算における `ArithmeticException` の `ComponentTrapException` への写像
* `i32.div_s` および `i64.div_s` における最小負値割るマイナス 1 の明示トラップ検査
* 非飽和浮動小数点整数切り捨て命令の NaN および範囲外トラップ検査
* 浮動小数点ロード、ストア、および `reinterpret` における NaN ビット列の保持

Canonical ABI 境界における NaN の正規化は [ADR-0007](0007-canonical-abi-validation.md) に従い実行時境界でのみ行い、Core Wasm 命令内部では元のビットパターンを保存します。

### 線形メモリと共有テーブルおよびトラップ写像

`LinearMemory` は `ByteArray` を直接保持する `final` 具象クラスとして実装します。リトルエンディアン読み書き、符号拡張、`memory.copy`、`memory.fill`、`memory.init`、`data.drop`、および生バイナリリソースからの初期化を備えます。`memory.grow` によって内部配列が差し替えられた際にメモリを共有する全モジュールから最新の配列が見えるよう、呼び出しや `memory.grow` を跨いで配列参照をローカル変数へキャッシュすることを禁止し、フィールドから都度再読込するコードを生成します。Phase 3 において複数スレッドで継続を切り替える際は、[ADR-0018](0018-async-abi-on-chicory.md) の `SessionLoop` によるスケジューリング引き渡しによって配列差し替えのメモリ可視性を保証します。実効アドレスは 32 ビット符号なし整数として 64 ビット長で `addr` と `offset` を加算してオーバーフローを防ぎ、境界外アクセスは `ArrayIndexOutOfBoundsException` に頼らず明示検査でトラップを送出します。なお、`ByteArray` の最大長は 2 の 31 乗マイナス 1 バイトであるため確保上限は最大 32,767 ページとなり、Android 実機上ではアプリヒープ上限が実質的な制約になります。`memory.grow` におけるサイズ上限超過または `OutOfMemoryError` はトラップにせず `-1` を返し、インスタンスを poison しません。

共有テーブルと関数参照 `funcref`・`externref` については、単一モジュールだけでなく複数モジュール間でのテーブル共有やホストトランポリンの登録、`ref.null`、`ref.func`、`table.get`、`table.set`、`table.grow`、複数テーブル、およびインスタンス化順序に沿った `elem` セグメント遅延パッチに対応します。関数参照 `funcref` は、所有者コアインスタンス参照、関数インデックス、および構造的型署名識別子を保持する軽量な不変参照オブジェクトで表し、`ref.null` は `null`、ホストトランポリンは専用の呼び出し参照として表します。これによりインスタンス束縛、参照同一性判定、およびクロスモジュールの構造的型等価性検査を一貫して扱えます。`table.grow` における上限超過および `OutOfMemoryError` もメモリと同様にトラップにせず `-1` を返します。`call_indirect` は型シグネチャ別のディスパッチメソッドを出力しますが、単一の型シグネチャに数千の関数が登録されてディスパッチメソッド自体が 8,000 バイトや 16,383 DEX コード単位を超える場合に備え、ディスパッチメソッドにも二段 `tableswitch` 分割を適用します。

トラップとエラー境界は [ADR-0007](0007-canonical-abi-validation.md) に完全準拠させます。ゲストコード実行箇所は `guest` 境界で囲い込み、poison 状態のインスタンスへの新規呼び出しは入口で `ComponentTrapException` として拒否します。Wasm 実行時トラップ、ゼロ除算による `ArithmeticException`、および `StackOverflowError` は `ComponentTrapException` へ写像して poison します。一方、ゲスト実行中にホスト import が投げた例外は、元の例外型を保持したままインスタンスを poison して再送出します。再入、すなわち `may_enter` 違反、`cabi_realloc` と `post-return` 中の外部呼び出し、すなわち `may_leave` 違反、および同一呼び出し内で `borrow` と `own` を重複させるハンドル移動も仕様通りトラップにします。ホスト側引数の値不正は `IllegalArgumentException` または `IllegalStateException` として扱い、インスタンスを poison しません。

### 3 段階移行計画と既存 ADR との関係

実装と移行は次の 3 つのフェーズに分けて進めます。

* Phase 1 `:runtime-canonical` 抽出と `ComponentBackend` 互換での基礎検証
* Phase 2 World DSL と静的 Lower・Lift の直結による R8 最適化
* Phase 3 Async ABI と WASI 0.3 対応および Chicory の位置づけ見直し

Phase 1 は Issue #182 から Issue #186 に対応します。`:runtime-canonical` の Phase 1 抽出と `:runtime-aot` の構築を行い、ビルド時に `plan.json` を解釈して複数モジュール配線、共有テーブルの遅延 `elem` パッチ、リソーステーブル、マルチメモリ `Transcoder`、ホスト import トランポリン、および静的型記述子を Kotlin コードとして生成します。生成される `<Name>Component` オブジェクトは既存の `ComponentBackendFactory` インタフェースを実装して名前と型の互換を保ちます。この段階で全同期フィクスチャの Wasmtime 差分テスト、`CanonicalAbiStrictnessTest` と `fixtures/abi-edge`、主要命令の Core Wasm 仕様テスト、および [ADR-0017](0017-component-model-conformance-suite.md) の公式適合テストのうち `values` と `resources` の AOT 経路通過を確認し、同一コミット上で Chicory ベースラインを取り直して R8 と ART の実測比較を行います。

Phase 2 は Issue #187 に対応します。Gradle プラグインで `AOT` を選択したコンポーネントに限り、`WitWriter` と `WitReader` のバイナリ境界および `ComponentBackend.invoke` の動的ディスパッチをバイパスし、World DSL からエクスポートおよびコンポーネント固有のローカルなホスト import について静的 Lower と Lift 経由でコンパイル済み Core Wasm メソッドを直接呼び出します。一方で、[ADR-0014](0014-interface-mappings.md) の `with` またはマニフェストで対応付けた外部ライブラリ提供の interface、たとえば `--interfaces-only` でビルド済みの `:runtime-wasi` などは既存の `Linker` バイナリ境界を維持して互換性を保ちます。

Phase 2 で Canonical ABI 実装の二重化を防ぐため、責務の線引きを明確に定めます。検査ヘルパーである `char`・判別子・UTF-8・UTF-16 検証、`Transcoder`、境界とアラインメント検査、`realloc`、ハンドル操作、貸与・移動・ロールバック・借用回収追跡、および poison 管理はすべて `:runtime-canonical` の `@InternalWasm2KtApi` ヘルパーに集約します。Phase 2 の生成コードは、静的に確定した WIT 型の走査とフラット引数およびメモリオフセット配置の展開のみを担い、実処理は `:runtime-canonical` のヘルパーを呼び出します。さらに Phase 1 のバイナリ境界経路と Phase 2 の静的直結経路の結果が一致することを差分テストで保証します。また、Phase 2 で生成される World の `withSession` と `openSession` は `<Name>Component` が実装する AOT 専用ファクトリ型を受け取る形とし、エクスポートおよびローカル import について汎用 `WitWriter`・`WitReader` のフォールバック経路を残しません。利用者の呼び出し構文形である `withSession` は維持しつつ、Wasmtime や Chicory のファクトリー誤混入をコンパイル時に防ぎ、R8 による不要コード削除を確実にします。公開される型マッピング、[ADR-0008](0008-host-defined-resources.md) のホストリソース契約、および [ADR-0011](0011-qualified-interface-keys.md) のキー規則は変更しません。ただし AOT Phase 2 の生成バインディング自体はバックエンド特化となるため、[ADR-0015](0015-binary-value-boundary.md) の「生成コードとバイナリ境界を全バックエンドで共有する」原則に対する例外として位置づけます。

Phase 3 は Issue #188 に対応します。[ADR-0018](0018-async-abi-on-chicory.md) および [ADR-0019](0019-suspending-async-boundary.md) に基づく Async ABI と `:runtime-wasi` 連携を `:runtime-canonical` への統合によって `:runtime-aot` 側へ展開し、`ConformanceTest` の `async` および `wasi-03` 関連テストを通過させます。この決定により、[ADR-0005](0005-async-and-wasi03-chicory-only.md) の「async と WASI 0.3 は Chicory バックエンドだけで進める」という方針は部分的に改訂されます。Wasmtime バックエンドを引き続き同期機能の参照実装として維持する点は変えず、純 JVM 側の対象を Chicory のみから Chicory と AOT の双方へ拡張します。既存 ADR 不変の原則に従い、[ADR-0005](0005-async-and-wasi03-chicory-only.md) および [ADR-0015](0015-binary-value-boundary.md) の本文は書き換えず、本 ADR のステータスを承認へ更新する PR において、`docs/adr/README.md` の両 ADR のステータス欄へ注記を加えます。あわせて `CLAUDE.md` と `CONTRIBUTING.md` には同 PR 時点で将来予定の注記を記載し、実際の不変条件の書き換えは Phase 2 および Phase 3 の実装完了時に行います。既定バックエンドを `AOT` へ切り替えるかどうかと `:runtime-chicory` の処遇は、Phase 3 完了後に別途 ADR を作成して決定します。

### Gradle プラグインとコード生成の入口

`gradle-plugin` には `wasm2kt.gradle.Wasm2KtBackend` 列挙型として `CHICORY` と `AOT` を追加します。`Wasm2KtExtension` に `backend: Property<Wasm2KtBackend>` を設けて規約値を `CHICORY` とし、`Wasm2KtComponent` に設ける `backend` プロパティは `extension.backend` を継承させます。なお、debug と release などのビルドバリアント単位でバックエンドを切り替える構成は対象外とします。また、現行の CLI `wasm2kt generate` には Chicory コンポーネントを組み立てる処理自体が存在しないため、本 ADR の導入対象は Gradle プラグインと `:tooling` の `ComponentBuild` に限定し、CLI への `--backend` や新サブコマンドの追加はスコープ外として必要時に別 Issue で扱います。

[ADR-0004](0004-isolated-tooling-classloader.md) を守るため、設定時に `:tooling` のクラスや `.wasm` ファイルへ触れず、列挙名の文字列のみをタスク入力へ渡します。タスクキャッシュ設計については、`Wasm2KtDecomposeTask` の出力である `plan.json`、`wit.json`、および `coreN.wasm` はバックエンド非依存であるため分解キャッシュを共有させます。そのためバックエンドに依存する機能検査 `PlanCheck` は `Wasm2KtDecomposeTask` から分離してバックエンド別の軽量検査タスクまたは `Wasm2KtCompileTask` 側へ移し、検査タスク、`Wasm2KtCompileTask`、および `Wasm2KtGenerateTask` に `backend` を `@Input` として登録します。検査タスクを分離した後も、`UP-TO-DATE` または `FROM-CACHE` 時に `Wasm2KtWarningsTask` で警告を再出力する従来の仕組みは維持します。

`plan.json` のビルド時解析と `usesAsync` 判定について、`:runtime-chicory` は JVM 17 かつ Android API 26 互換を維持するため JVM 21 の `:ast` や `:tooling` には依存できません。そこで `plan.json` のビルド時 JSON モデル自体は `:tooling` に置く一方で、`SYNC_ADAPTER_KINDS` と `usesAsync` の純粋な判定ロジックは JVM 17 の `:runtime-canonical` に単一定義を置き、`:runtime-chicory` と `:tooling` の双方から呼び出します。あわせて `:tooling` と `:runtime-chicory` の判定がすべての分解済みフィクスチャで一致することをテストで検査します。`PlanCheck` からは「Chicory backend」という固定表記を外して選択バックエンド名を差し込めるようにします。Phase 1 および Phase 2 の間は、`AOT` バックエンドで async 機能を使うコンポーネントが渡された場合、`failOnUnsupportedFeatures` の値にかかわらずビルド時エラーとして即座に失敗させ、`failOnUnsupportedFeatures = false` で回避できる旨の誤った案内を出さない専用メッセージを出力します。同様に Phase 1 および Phase 2 の間で `addWasiDependency` が `true` かつ登録されたすべてのコンポーネントが `AOT` を選んだ場合は、設定時に互換警告を出力します。

`AndroidWiring` と `KotlinJvmWiring` はコンポーネントの選択バックエンドに応じたコンパイル成果物と生成タスクを配線します。`afterEvaluate` の `addRuntimeDependency` では登録されたコンポーネント群が使用するバックエンドを調べ、`:runtime-chicory`、`:runtime-aot`、および必要な `:runtime-canonical` のうち該当するものだけを `implementation` へ追加します。すべてのコンポーネントが `AOT` を選んだ場合は `:runtime-chicory` を依存に含めません。

## 結果とトレードオフ

本決定により、オプトインで実行時の Chicory 依存、`.meta`、および `kotlinx.serialization` 依存を持たない構成を構築でき、Phase 1 から JVM バイトコードが 64 KB を超える関数のコンパイルと `call_dispatch_0` 入口ディスパッチの廃止を実現できます。Phase 2 ではさらにアプリから使われないエクスポート関数と配下の直接呼び出し Wasm 関数を R8 で削除でき、境界レイテンシの低減も期待できます。

一方で、次の 5 つの制約と波及影響が存在します。

* Phase 1 における R8 不要コード削除の範囲制限
* テーブル登録関数に対する R8 不要コード削除の限界
* コンパイラ自作の検証負荷と生成クラス・メソッド増加によるビルド時間への影響
* 単一 `ByteArray` 容量上限と Android ヒープ制約
* 波及するテスト体制、CI、公開成果物、およびドキュメントの更新範囲

1 つ目の点として、Phase 1 では `ComponentBackend.invoke` が整数インデックスで全エクスポートをディスパッチするため、エクスポート単位の R8 不要コード削除はまだ効きません。Phase 1 で得られるサイズ削減は Chicory ランタイム、`.meta`、および `kotlinx.serialization` の除去にほぼ限られ、エクスポート単位の削除は Phase 2 の成果になります。

2 つ目の点として、Phase 2 到達後も `elem` セグメントで関数テーブルへ登録された関数は、型シグネチャ別ディスパッチメソッドから参照が残るため R8 では削除できません。削除できる対象は、未参照エクスポート配下の直接呼び出し関数と一度も呼ばれない型シグネチャのディスパッチャ群に限られます。

3 つ目の点として、Core Wasm 命令の意味論と CFG 事前分割の正確性を自ら保守し続ける必要があります。あわせて分割に伴う補助メソッド数とクラス数の増加は、`kotlinc`、DEX 変換、および R8 の処理時間へ影響を与えるおそれがあります。

4 つ目の点として、単一 `ByteArray` による `LinearMemory` は最大 32,767 ページであり、Wasm 32 ビット理論上限の 4 GiB には達しません。また Android 端末上では各アプリのヒープ上限が実質的な制約となります。

5 つ目の点として、波及する公開成果物、ドキュメント、テスト、および CI の更新が必要です。公開成果物には `io.github.wasm2kt:runtime-canonical` と `io.github.wasm2kt:runtime-aot` が加わります。ドキュメントでは、各フェーズの実装に合わせて `CLAUDE.md`、`CONTRIBUTING.md` の第 3 節、`docs/reference/gradle-plugin.md`、`docs/reference/cli.md`、`docs/reference/feature-support.md`、`docs/reference/compatibility.md`、`docs/explanation/architecture.md`、`docs/explanation/build-pipeline.md`、`docs/explanation/call-path.md`、`docs/STATUS.md`、および `addRuntimeDependency` の KDoc を更新します。なお、既定バックエンドが `CHICORY` である間は [ADR-0001](0001-chicory-backend-for-android.md) および [ADR-0021](0021-split-large-chicory-methods.md) のステータスを承認のまま維持します。

### 検証計画

正確性の検証として、`:runtime-canonical` と `:runtime-aot` への `animalsnifferMain` 適用、生成全クラスの `CheckClassAdapter.verify` 通過、`CanonicalAbiStrictnessTest` と `fixtures/abi-edge` の AOT 実行、および既存の全同期フィクスチャに対する Wasmtime 差分テストを行います。さらに Phase 2 では Phase 1 バイナリ境界経路と Phase 2 静的直結経路の差分テストを追加し、`gradle-plugin` の `functionalTest` には AOT 単独構成と Chicory 混在構成の両ケースを加えます。

Core Wasm 命令そのものの網羅検証には、[ADR-0017](0017-component-model-conformance-suite.md) と同様に公式 Core Wasm 仕様テストスイートを写しとして取り込み、CI で版を固定している `wasm-tools` 1.258.0 の `json-from-wast` 変換を用いて更新スクリプトとスキップリストで管理・実行します。Component Model の公式適合テスト `ConformanceTest` については、現行のインタプリタ経路に加えてビルド時コンパイル経由の AOT 実行ハーネスを整備し、バックエンドごとのスキップリスト管理を含めて通過を確認します。さらに `emulator` ジョブでは、D8 変換・ART 検証器・ARM64 上での NaN ビット列保持を確認するため、ベンチマークに加えて AOT バックエンドを用いた E2E テストを ART 上で実行します。

性能・サイズ・ビルド時間の検証として、`:benchmarks` に AOT バックエンドの計測を追加し、`samples/android`、`heavy.wasm`、および `samples/android-glitch-camera` の `png-glitch-component.wasm` を対象に同一コミット上の Chicory ベースラインと並べて計測します。R8 適用後の APK と DEX 内訳、`dex2oat64` の除外ログ件数、初回 `openSession` とデータリソース読み込み時間、Host JVM と非 `debuggable` の Android ART 実行時間、ならびにビルド時間と R8 処理時間の変化を [performance.md](../explanation/performance.md) へ記録します。なお、CI ランナー間の CPU 個体差による誤検出を避けるため、性能比較は CI の絶対値ゲートにはせず、同一環境での相対比較をベンチマークワークフローで継続的に確認します。
