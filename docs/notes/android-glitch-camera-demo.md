# Android カメラグリッチデモの作成計画と機能改善設計

このドキュメントでは、`samples/android-glitch-camera` に作成するデモアプリの実装計画をまとめます。あわせて、最新の `png-glitch` リポジトリの実装状況を踏まえ、`png-glitch` と `wasm-component-kotlin` の双方で必要となる機能改善を整理します。

## 1. `png-glitch` の最新実装状況の調査結果

最新の `png-glitch` リポジトリでは、ライブフレーム処理やピクセル単位のマスク処理、WebAssembly コンポーネント化の基盤がすでに実装されています。

実装済みの主な機能は以下の4つです。

* `png-glitch` クレートの `LivePipeline` と `ScanLines`
* `png-glitch` クレートの `Mask` と `Masked` ラッパー
* `png-glitch-recipe` クレートの `LiveRecipe` と `resolve_into`
* `png-glitch-component` クレートと WIT 定義 `chikoski:png-glitch@0.1.0`

1つ目は、DEFLATE 圧縮やチェックサム計算を行わずに RGBA ピクセル列とスキャンラインを往復する `LivePipeline` です。初回に `PngGlitch::from_pixels` と `Renderer::new` でバッファを確保したあとは、毎フレーム `LivePipeline::encode` でフィルタ処理を適用し、`ScanLines` ビュー上でスキャンラインを直接編集し、`LivePipeline::decode_into` で RGBA ピクセル列へ展開します。これら一連の操作はヒープ割り当てを一切行わずに動作します。静止画として PNG バイト列が必要なときだけ `LivePipeline::snapshot` を呼んで DEFLATE 圧縮を実行します。

2つ目は、ピクセル単位でエフェクトの適用強度を制御する `Mask` と `Masked` です。ADR 0006 に基づき、`Masked` は任意の `ScanLinesEffect` をラップし、内部の `Mutex<Vec<u8>>` にスキャンラインの元データを退避したうえで、エフェクト適用後のバイト列と元のバイト列をマスク値に応じてブレンドします。スキャンラインのフィルタ済みバイト列に対してそのままマスクを適用するため、`Up` や `Average` や `Paeth` フィルタの行ではマスク内の変更がデコード時に下方向へ伝播します。エラー伝播をマスク領域の内部だけに閉じ込めたい場合は、マスク付きステップの前に `remove-filter` ステップを置くことで制御できます。

3つ目は、フレームごとのレシピ解決をゼロアロケーションで行う `LiveRecipe` です。ADR 0005 に基づき、全フレームで同じ乱数を使う `SeedMode::Static` と、SplitMix64 による `frame_seed` で毎フレーム異なる乱数を引く `SeedMode::PerFrame` の2種類を備えています。`Recipe::resolve_into` が前フレームの `ResolvedRecipe` 内のベクタ容量を再利用するため、組み込みフィルタであれば `PerFrame` モードでも2フレーム目以降のヒープ割り当てが発生しません。

4つ目は、WebAssembly コンポーネントを提供する `png-glitch-component` クレートです。`crates/png-glitch-component/wit/world.wit` において、`glitch` インターフェースと `live` インターフェースが定義されています。`live` インターフェースの `session` リソースは、`from-pixels`、`reload-pixels`、`set-recipe`、`apply`、`reset`、`render-rgba`、`snapshot` の各メソッドを公開しています。また、`glitch` インターフェースの `list-filters` 関数を使えば、利用可能な全フィルタの名前やパラメータ範囲、説明文のメタデータをホスト側から動的に取得できます。

## 2. デモアプリ作成に向けて追加で必要な機能改善

最新の `png-glitch` によってライブ処理の大部分は揃っていますが、Android 上で MediaPipe のセグメンテーションマスクを毎フレーム流し込み、Chicory バックエンドで動作させるためには、`png-glitch` 側と `wasm-component-kotlin` 側にそれぞれ改善が必要です。

### 2.1 `png-glitch` 側の改善項目

`png-glitch` リポジトリ側で対応する項目は以下の3点です。

* `png-glitch-component` の `session` におけるマスク受け渡し API の追加
* `png-glitch-recipe` における毎フレームのマスク更新時のバッファ再利用
* Chicory バックエンド向けのビルドターゲット構成

1つ目は、コンポーネントへのマスク供給 API の追加です。現在の `crates/png-glitch-component/src/lib.rs` にある `parse_recipe` は、レシピ内に `mask:` の指定があるとエラーとして拒否します。これはファイルシステムを持たないコンポーネント環境でマスク画像を読み込む手段がまだないためです。MediaPipe が生成したセグメンテーションマスクをホストから渡せるよう、WIT の `live.session` リソースに `set-mask: func(name: string, values: list<u8>) -> result<_, glitch-error>` を追加します。これにより、レシピ側で `mask: { path: selfie }` や `invert-mask: true` を記述して人物領域や背景領域をターゲットに指定できるようになります。

2つ目は、マスク更新時のゼロアロケーション化です。現在の `crates/png-glitch-recipe/src/resolve.rs` にある `resolve_masked_into` は、前フレームのマスクと現在のマスクが一致する場合のみ `MaskedEffect` とその内部のスナップショットバッファを再利用します。カメラ映像から毎フレーム新しいセグメンテーションマスクが渡されると、マスクの内容が毎フレーム変化するため、現在の実装では `Masked::new` が毎回呼ばれて数 MB のスナップショットバッファが再確保されてしまいます。また、`SeedMode::Static` の場合はフレームごとに `resolve_into` が呼ばれないため、解決済みステップ内のマスク自体が更新されません。これを解決するため、`Mask` と `Masked` に既存のバイトスライスからマスク値だけをインプレースで書き換えるメソッドを追加し、スナップショットバッファを再確保せずに最新フレームのマスクを反映できるようにします。

3つ目は、Chicory 向けの WebAssembly バイナリ生成手順の整備です。ブラウザベンチマーク用の `bench/browser/build.mjs` は `wasm32-wasip2` ターゲットと `+simd128` を使ってビルドしています。一方、`wasm-component-kotlin` の Chicory バックエンドは WASI 0.2 のホスト実装を同梱しておらず、SIMD 命令ではなく標準命令セットを AOT コンパイル対象とします。`png-glitch-component` は乱数やファイル I/O を一切使わない設計になっているため、`wasm32-unknown-unknown` ターゲットかつ `simd128` なしでビルドし、`wasm-tools component new` でコンポーネント化することで、WASI インポートを持たない純粋なコンポーネントバイナリを生成します。

### 2.2 `wasm-component-kotlin` 側の改善項目

`wasm-component-kotlin` リポジトリ側で対応する項目は以下の4点です。

* セッション単位での `WitWriter` バッファの再利用
* `Lifter.loadList` における中間 `ByteArray` 割り当ての排除
* ホスト側で出力用 `ByteArray` を再利用するデコード経路の追加
* 生成セッションの `invoke` における引数ボックス配列の削減

1つ目は、`WitWriter` の再利用です。解像度 1280×720 の場合、`reload-pixels` で約 3.68 MB の RGBA 配列を送り、`set-mask` で約 0.92 MB のマスク配列を送り、`render-rgba` で約 3.68 MB の RGBA 配列を受け取ります。現在の `BackendWasmtimeSession.invoke` と `ChicoryComponentInstance.invoke` は呼び出しのたびに初期容量 64 バイトの `WitWriter` を新規作成するため、倍々拡張によって毎フレーム 20 MB 以上の短期オブジェクトがヒープに割り当てられます。`WitWriter` に書き込み位置だけを 0 に戻す `reset` メソッドを追加し、セッション内で入出力用のインスタンスを使い回すように変更します。

2つ目は、`runtime-chicory` の `Lifter.loadList` における二重コピーの解消です。現在の実装は `cx.memory.readBytes` で 3.68 MB の一時的な `ByteArray` を新規確保してから `WitWriter.raw` にコピーしています。`WitWriter` の内部バッファへ Chicory の `Memory` から直接コピーする経路を設けることで、毎フレーム 3.68 MB の不要な配列確保とコピーを削減します。

3つ目は、ホスト側での出力用配列の再利用です。`render-rgba` の戻り値を毎回新しい `ByteArray` として生成すると、それだけで毎秒約 110 MB のヒープ割り当てが発生します。ホスト側であらかじめ確保した `ByteArray` または `ByteBuffer` に結果を書き出せる API を用意し、Android の `Bitmap.copyPixelsFromBuffer` へゼロアロケーションで渡せるようにします。

4つ目は、生成コードの `WasmtimeSession.invoke` および `invokeMethod` で毎回生成される `args: Array<out Any?>` の削除または分離です。テスト用の `InMemoryWasmtimeSession` 以外では使われない配列生成を取り除き、フレームごとの細かなオブジェクト生成を抑えます。

## 3. デモアプリ `samples/android-glitch-camera` の作成計画

デモアプリは `samples/android-glitch-camera` ディレクトリに配置し、既存の最小サンプルである `samples/android` とは独立した Gradle プロジェクトとして構成します。

### 3.1 ディレクトリ構成

プロジェクトのファイル構成は以下のとおりです。

```text
samples/android-glitch-camera/
├── README.md
├── settings.gradle.kts
├── build.gradle.kts
├── gradle.properties
├── gradle/
│   └── gradle-daemon-jvm.properties
└── app/
    ├── build.gradle.kts
    ├── proguard-rules.pro
    └── src/main/
        ├── AndroidManifest.xml
        ├── assets/
        │   └── selfie_segmenter.tflite
        ├── wasm/
        │   └── png-glitch-component.wasm
        └── kotlin/com/example/wasm2kt/glitchcamera/
            ├── MainActivity.kt
            ├── CameraPipeline.kt
            ├── SegmentationProcessor.kt
            ├── GlitchSessionManager.kt
            ├── RecipeBuilder.kt
            ├── VideoRecorder.kt
            └── MediaStoreSaver.kt
```

`settings.gradle.kts` では `includeBuild("../..")` を指定し、このリポジトリの `io.github.wasm2kt` プラグインと `runtime-chicory` を Composite Build で直接参照します。`app/build.gradle.kts` の `wasm2kt` ブロックで `src/main/wasm/png-glitch-component.wasm` を登録し、ビルド時に分解・AOT コンパイル・Kotlin バインディング生成を実行します。

### 3.2 カメラと MediaPipe の処理パイプライン

カメラ入力から画面表示および録画までのデータフローは専用のワーカースレッド上で直列に実行します。

各コンポーネントの役割は以下のとおりです。

* `CameraPipeline`
* `SegmentationProcessor`
* `GlitchSessionManager`
* `RecipeBuilder`

`CameraPipeline` は CameraX の `ImageAnalysis` ユースケースを構成します。出力形式には `ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888` を指定し、バックプレッシャー戦略には `STRATEGY_KEEP_ONLY_LATEST` を設定します。これにより、Wasm 側の処理中に到着した中間フレームを自動的にスキップし、常に最新のフレームだけを処理します。カメラの解像度はリアルタイム性を重視して 640×480 または 1280×720 を選択可能にします。

`SegmentationProcessor` は MediaPipe Tasks Vision の `ImageSegmenter` を初期化し、`selfie_segmenter.tflite` モデルを読み込みます。CameraX から受け取ったフレームを `MPImage` として入力し、出力された信頼度マスクを 0 から 255 の `u8` バイト配列に変換して再利用バッファへ格納します。

`GlitchSessionManager` は `wasm-component-kotlin` が生成した `PngGlitch` ワールドのセッションと、`live.Session` リソースのライフサイクルを管理します。最初のフレームが到着した時点で `Session.fromPixels` を呼び出し、カラータイプ `Rgba`、ビット深度 8、フィルタ戦略 `AdaptiveFast` または指定の固定フィルタで初期化します。2フレーム目以降は、`reloadPixels` でカメラの RGBA 配列を渡し、`setMask` でセグメンテーションマスクを渡し、`apply` を実行して `renderRgba` でグリッチ後の RGBA 配列を取得します。

`RecipeBuilder` は UI の操作状態から `png-glitch` の YAML レシピ文字列を組み立て、変更があったときだけ `session.setRecipe` を呼び出します。

### 3.3 UI 機能とグリッチ制御

UI では `png-glitch-component` の機能を活かして以下の操作を提供します。

* フィルタ種類と強度の選択
* マスク対象領域の切り替え
* エラー伝播の閉じ込め設定
* シードモードと PNG フィルタ戦略の切り替え

フィルタの選択肢は、`glitch.listFilters` から取得したメタデータ、または代表的なレシピプリセットから選べるようにします。具体的には、`transpose`、`replace`、`change-filter-type`、`pixel-sort`、`chromatic-aberration`、`channel-swap`、`block-scramble` などをスライダーで調整できます。

マスク対象領域は、全体、人物のみ、背景のみの3通りを切り替えられます。人物のみの場合はレシピの各ステップに `mask: { path: selfie }` を付与し、背景のみの場合はそれに加えて `invert-mask: true` を付与します。

エラー伝播の閉じ込め設定では、マスク外へのスキャンライン破損のあふれを制御します。あふれを許可する場合は `AdaptiveFast` や `Paeth` などの予測フィルタがかかった状態のままマスク付きステップを実行します。マスク内に厳密に閉じ込める場合は、レシピの先頭に `remove-filter` ステップを挿入してフィルタ種別を `None` に揃えてからマスク付きステップを実行します。

シードモードは、毎フレーム同じ破損パターンを維持する `Fixed` と、フレームごとに破損位置が変化する `PerFrame` を切り替えられます。

### 3.4 録画と静止画保存の実装

グリッチ適用後の出力を保存するため、動画録画と2種類の静止画保存を実装します。

保存機能は以下の3つです。

* `VideoRecorder` による MP4 録画
* `session.snapshot` による本来のグリッチ PNG 保存
* プレビュー RGBA バッファからの標準画像保存

`VideoRecorder` は Android の `MediaCodec` と `MediaMuxer` を用いて H.264 形式の MP4 動画を生成します。CameraX 標準の `VideoCapture` はカメラの生映像を直接録画してしまうため使用しません。代わりに、`GlitchSessionManager` が毎フレーム出力するグリッチ適用後の RGBA ピクセル列を `MediaCodec` の入力 Surface または入力バッファへ描画し、フレームタイムスタンプを付与してエンコードします。録画完了後、`MediaStoreSaver` を通じて端末の `Movies/GlitchCamera` フォルダへ保存します。

静止画保存では、`live.Session` が持つ `snapshot` メソッドを活用します。シャッターボタンが押された際、ワーカースレッド上で `session.snapshot` を呼び出すと、その瞬間の破損したスキャンラインデータから DEFLATE 圧縮とチャンク生成が行われ、`png-glitch` 本来の構造を持った PNG バイナリが得られます。これを `Pictures/GlitchCamera` へ保存します。あわせて、表示中の RGBA `Bitmap` をそのまま PNG または JPEG として保存する経路も用意します。

## 4. 実装ステップとフェーズ分け

開発は以下の4つのフェーズに分けて順番に進めます。

### フェーズ 1: `png-glitch` 側のコンポーネントマスク対応

`png-glitch` リポジトリにて、`Mask` と `Masked` のインプレース更新メソッドを追加し、`png-glitch-component` の WIT 定義と `Session` 実装に `set-mask` を追加します。あわせて、`wasm32-unknown-unknown` ターゲットから `wasm-tools component new` で `.wasm` コンポーネントを生成するスクリプトを整備し、単体テストでマスク付きライブセッションがゼロアロケーションで動作することを確認します。

### フェーズ 2: `wasm-component-kotlin` 側のバッファ再利用と高速化

`wasm-component-kotlin` リポジトリにて、`WitWriter` のセッション内再利用、`Lifter.loadList` の中間配列削除、不要な引数配列生成の削除、および出力バッファ再利用 API を実装します。`benchmarks` モジュールに数 MB の `list<u8>` を往復させるベンチマークを追加し、アロケーション量と実行時間の改善を検証します。

### フェーズ 3: `samples/android-glitch-camera` の基本パイプライン構築

`samples/android-glitch-camera` プロジェクトを作成し、フェーズ 1 でビルドした `png-glitch-component.wasm` を組み込みます。CameraX によるフレーム取得、MediaPipe `ImageSegmenter` によるマスク生成、`GlitchSessionManager` による Wasm 呼び出し、およびプレビュー表示までを実装し、エミュレーターと実機でフレームレートを計測します。

### フェーズ 4: UI コントロールと録画・静止画保存の実装

レシピパラメータ操作 UI、`VideoRecorder` による MP4 録画、`session.snapshot` による PNG 保存、`MediaStore` への書き出しを実装します。最後に R8 を有効にした release ビルドでの動作確認とドキュメント整備を行います。
