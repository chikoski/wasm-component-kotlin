# Android カメラグリッチデモの作成計画と機能改善設計

このドキュメントでは、`samples/android-glitch-camera` に作成するデモアプリの実装計画をまとめます。最初の段階では MediaPipe によるセグメンテーションを行わず、カメラ入力全体に `png-glitch` でリアルタイムのグリッチエフェクトをかけ、静止画保存と動画録画を行うアプリを構築します。あわせて、最新の `png-glitch` リポジトリの実装状況と、`wasm-component-kotlin` 側で行うランタイム改善を整理します。

## 1. `png-glitch` の最新実装状況の調査結果

最新の `png-glitch` リポジトリでは、ライブフレーム処理やレシピ解決、および WebAssembly コンポーネント化の基盤がすでに実装されています。

実装済みの主な機能は以下の4つです。

* `png-glitch` クレートの `LivePipeline` と `ScanLines`
* `png-glitch` クレートの `Mask` と `Masked` ラッパー
* `png-glitch-recipe` クレートの `LiveRecipe` と `resolve_into`
* `png-glitch-component` クレートと WIT 定義 `chikoski:png-glitch@0.1.0`

1つ目は、DEFLATE 圧縮やチェックサム計算を行わずに RGBA ピクセル列とスキャンラインを往復する `LivePipeline` です。初回に `PngGlitch::from_pixels` と `Renderer::new` でバッファを確保したあとは、毎フレーム `LivePipeline::encode` でフィルタ処理を適用し、`ScanLines` ビュー上でスキャンラインを直接編集し、`LivePipeline::decode_into` で RGBA ピクセル列へ展開します。これら一連の操作はヒープ割り当てを一切行わずに動作します。静止画として PNG バイト列が必要なときだけ `LivePipeline::snapshot` を呼んで DEFLATE 圧縮を実行します。

2つ目は、ピクセル単位でエフェクトの適用強度を制御する `Mask` と `Masked` です。ADR 0006 に基づき、`Masked` は任意の `ScanLinesEffect` をラップし、内部の `Mutex<Vec<u8>>` にスキャンラインの元データを退避したうえで、エフェクト適用後のバイト列と元のバイト列をマスク値に応じてブレンドします。これらは次の段階で MediaPipe セグメンテーションを導入する際の基盤になります。

3つ目は、フレームごとのレシピ解決をゼロアロケーションで行う `LiveRecipe` です。ADR 0005 に基づき、全フレームで同じ乱数を使う `SeedMode::Static` と、SplitMix64 による `frame_seed` で毎フレーム異なる乱数を引く `SeedMode::PerFrame` の2種類を備えています。`Recipe::resolve_into` が前フレームの `ResolvedRecipe` 内のベクタ容量を再利用するため、組み込みフィルタであれば `PerFrame` モードでも2フレーム目以降のヒープ割り当てが発生しません。

4つ目は、WebAssembly コンポーネントを提供する `png-glitch-component` クレートです。`crates/png-glitch-component/wit/world.wit` において、`glitch` インターフェースと `live` インターフェースが定義されています。`live` インターフェースの `session` リソースは、`from-pixels`、`reload-pixels`、`set-recipe`、`apply`、`reset`、`render-rgba`、`snapshot` の各メソッドを公開しています。また、`glitch` インターフェースの `list-filters` 関数を使えば、利用可能な全フィルタの名前やパラメータ範囲、説明文のメタデータをホスト側から動的に取得できます。セグメンテーションなしのカメラグリッチアプリに必要な WIT 関数は、すべて現行の `png-glitch-component` に揃っています。

## 2. `wasm-component-kotlin` 側の機能改善

セグメンテーションなしのカメラグリッチアプリでは、解像度 640×480 で毎フレーム約 1.22 MB、解像度 1280×720 で毎フレーム約 3.68 MB の RGBA 配列を `reload-pixels` と `render-rgba` で往復させます。GC 負荷を抑えて滑らかなフレームレートを実現するため、デモアプリ作成に先立って以下の2つのランタイム改善を実装します。

* セッション単位での `WitWriter` バッファの再利用
* `Lifter` における `list` および `string` 読み出し時の中間 `ByteArray` 排除

1つ目は、`WitWriter` の再利用です。これまでの `BackendWasmtimeSession.invoke`、`FfmWasmtimeSession.invoke`、`JniWasmtimeSession.invoke`、および `ChicoryComponentInstance.invoke` は、呼び出しのたびに初期容量 64 バイトの `WitWriter` を新規作成していました。数 MB の RGBA 配列を書き込むたびに配列の倍々拡張が走るため、1 フレームあたり十数 MB の一時配列がヒープに割り当てられていました。`WitWriter` に書き込み位置だけを 0 に戻す `reset` メソッドを追加し、各セッションインスタンスおよび `ChicoryComponentInstance` のフィールドとして `WitWriter` を保持して使い回すことで、2 フレーム目以降のバッファ再確保をなくします。

2つ目は、`runtime-chicory` の `Lifter.loadList` および `Lifter.loadString` における中間配列の排除です。これまでの実装は `cx.memory.readBytes` を呼んで線形メモリから新しい `ByteArray` を確保し、その直後に `WitWriter.raw` へ再度コピーしていました。`WitWriter` に書き込み領域を予約する `reserve` メソッドを追加し、Chicory の `Memory.readLong` と `Memory.read` を使って `WitWriter` の内部バッファへ直接読み出すように変更します。これにより、`render-rgba` 呼び出し時に `Lifter` 内部で発生していた数 MB の一時配列確保をゼロにします。

なお、将来 MediaPipe セグメンテーションを追加するフェーズでは、`png-glitch-component` の `live.session` への `set-mask` メソッド追加と、ホスト側での出力用 `ByteArray` の再利用 API も段階的に導入します。

## 3. デモアプリ `samples/android-glitch-camera` の作成計画

デモアプリは `samples/android-glitch-camera` ディレクトリに配置し、既存の最小サンプルである `samples/android` とは独立した Gradle プロジェクトとして構成します。UI フレームワークには Jetpack Compose を採用します。

### 3.1 ディレクトリ構成

プロジェクトのファイル構成は以下のとおりです。

```text
samples/android-glitch-camera/
├── README.md
├── build-wasm.sh
├── settings.gradle.kts
├── build.gradle.kts
├── gradle.properties
├── gradle/
│   └── gradle-daemon-jvm.properties
└── app/
    ├── build.gradle.kts
    ├── proguard-rules.pro
    └── src/
        ├── main/
        │   ├── AndroidManifest.xml
        │   ├── wasm/
        │   │   └── png-glitch-component.wasm
        │   └── kotlin/com/example/wasm2kt/glitchcamera/
        │       ├── MainActivity.kt
        │       ├── GlitchCameraScreen.kt
        │       ├── GlitchViewModel.kt
        │       ├── GlitchEngine.kt
        │       ├── RecipePresets.kt
        │       ├── Mp4VideoRecorder.kt
        │       └── MediaStoreHelper.kt
        └── test/
            └── kotlin/com/example/wasm2kt/glitchcamera/
                └── GlitchEngineTest.kt
```

`settings.gradle.kts` では `includeBuild("../..")` を指定し、このリポジトリの `io.github.wasm2kt` プラグインと `runtime-chicory` を Composite Build で直接参照します。`build-wasm.sh` は `png-glitch` リポジトリの `png-glitch-component` クレートを `wasm32-unknown-unknown` ターゲットでビルドし、`wasm-tools component new` で `app/src/main/wasm/png-glitch-component.wasm` を生成します。

### 3.2 カメラパイプラインと Wasm セッション管理

カメラ入力から Wasm 処理、プレビュー表示、録画までの流れは以下の3つのクラスで分担します。

* `GlitchEngine`
* `GlitchViewModel`
* `GlitchCameraScreen`

`GlitchEngine` は `wasm-component-kotlin` が生成した `PngGlitch` ワールドのセッションと `live.Session` リソースをラップし、専用の単一スレッドディスパッチャー上で直列に呼び出します。起動時に `glitch.listFilters` を呼び出して PNG 対応フィルタのメタデータ一覧を取得します。最初のカメラフレームが到着した際、または解像度や PNG エンコード戦略が変更された際に `Session.fromPixels` を呼び出してセッションを初期化します。以降のフレームでは `reloadPixels`、`apply`、`renderRgba` を順に実行してグリッチ適用後の RGBA バイト列を返します。

`GlitchViewModel` は UI 状態を保持します。管理する状態には、カメラの前後切り替え、解像度の 640×480 と 1280×720 の切り替え、プリセットモードとカスタムフィルタモードの切り替え、`listFilters` から取得した各フィルタのパラメータ値、PNG フィルタ戦略である `AdaptiveFast` や `Paeth` や `Sub` や `Up` や `Average`、およびシードモードの `PerFrame` と `Fixed` が含まれます。パラメータが変更された際は YAML 形式のレシピ文字列を組み立て、次のフレーム処理の前に `session.setRecipe` で反映します。

`GlitchCameraScreen` は Jetpack Compose でカメラプレビューと操作パネルを描画します。CameraX の `ImageAnalysis` を `OUTPUT_IMAGE_FORMAT_RGBA_8888` および `STRATEGY_KEEP_ONLY_LATEST` で構成し、到着したフレームから RGBA バイト列を取り出して `GlitchEngine` に渡します。返された RGBA バイト列を `Bitmap` に書き戻して画面上に表示します。

### 3.3 静止画保存と MP4 動画録画

グリッチ適用後の映像を保存するため、以下の2つの保存機能を実装します。

* `session.snapshot` による本来のグリッチ PNG 静止画保存
* `Mp4VideoRecorder` による H.264 MP4 動画録画

静止画保存では、シャッターボタンが押されたタイミングでワーカースレッドから `session.snapshot` を呼び出します。これにより、現在の破損したスキャンラインがそのまま DEFLATE 圧縮された正規の PNG バイナリとして出力されます。得られたバイト列を `MediaStoreHelper` 経由で端末の `Pictures/GlitchCamera` に保存します。

動画録画では、`Mp4VideoRecorder` が Android の `MediaCodec` と `MediaMuxer` を使って H.264 形式の MP4 ファイルを生成します。`MediaCodec.createInputSurface` で作成した入力 `Surface` に対し、毎フレーム `GlitchEngine` が出力したグリッチ済み `Bitmap` を描画してエンコーダへ送り、出力バッファを `MediaMuxer` で書き出します。録画停止後に `MediaStoreHelper` 経由で `Movies/GlitchCamera` へ登録します。

## 4. 実装手順

以下の順序で実装と検証を進めます。

1. `runtime-api`、`runtime-chicory`、`generator` における `WitWriter` 再利用と `Lifter` の中間配列削除の実装および既存テストの実行
2. `png-glitch-component` の `wasm32-unknown-unknown` ビルドと `png-glitch-component.wasm` の生成
3. `samples/android-glitch-camera` の Gradle プロジェクトと Jetpack Compose アプリ一式の実装
4. `GlitchEngineTest` によるホスト JVM 上でのユニットテストと、`assembleDebug` および `assembleRelease` によるビルド検証
