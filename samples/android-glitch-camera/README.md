# png-glitch Android カメラデモ

`png-glitch` の WebAssembly コンポーネントである `png-glitch-component.wasm` と `wasm-component-kotlin` の Chicory バックエンドを組み合わせた Android カメラデモアプリです。カメラのライブ入力フレームに対して PNG 予測フィルタ破壊を含むグリッチエフェクトを適用し、プレビュー表示と PNG 静止画保存および MP4 動画録画を行います。

## 構成

このプロジェクトはリポジトリルートのプラグインとランタイムを `includeBuild` で参照する独立した Gradle プロジェクトです。`app/src/main/wasm/png-glitch-component.wasm` をビルド時に分解して JVM バイトコードへ AOT コンパイルし、型安全な Kotlin バインディングを生成します。

主なソースコードと役割は以下の通りです。

- `GlitchEngine.kt`
- `RecipePresets.kt`
- `FrameConverter.kt`
- `RgbaFrameTransform.kt`
- `PreviewRenderer.kt`
- `Mp4VideoRecorder.kt`
- `MediaStoreHelper.kt`
- `GlitchViewModel.kt`
- `GlitchCameraScreen.kt`
- `MainActivity.kt`

`GlitchEngine.kt` は生成された `PngglitchComponent` のセッションと `chikoski:png-glitch/live` の `Session` リソースを管理します。WIT のエクスポート関数は `async func` として定義されており、生成された `suspend fun` バインディングと `Mutex` を組み合わせてコルーチンから非同期に呼び出します。初回フレームで `Session.fromPixels` を呼び出し、2フレーム目以降は `reloadPixels` と `apply` と `renderRgba` を繰り返すことで PNG チャンク構築のオーバーヘッドを抑えます。静止画シャッター押下時には `snapshot` を呼び出し、グリッチ状態の真正な PNG バイト列を取得します。

カメラの各フレームは `FrameConverter.kt`（実体は Android に依存しない `RgbaFrameTransform.kt`）で、回転、前面カメラのミラー、縮小を 1 回の走査でまとめて行ってから `GlitchEngine` に渡します。解像度の切り替え（360p、540p、720p。既定は 540p）は、グリッチ処理を行うサイズを選ぶものです。カメラには 16:9 で、それ以上のサイズを要求し（アスペクト比を指定しないと、CameraX は `ImageAnalysis` に 4:3 を優先し、必要より大きなフレームを渡すことがあります）、各フレームを長辺と短辺がそれぞれ 640×360、960×540、1280×720 に収まるように縮小します（拡大はしません）。グリッチ処理と境界のコピーは画素数に比例するので、解像度を下げると FPS が上がります。

フレームは 3 つの段を順に通ります。変換の段は CameraX の analyzer（`Dispatchers.Default`）で、届いたフレームをすぐ変換して `ImageProxy` を閉じ、結果を「最新の 1 枚」の受け渡し場所（`FrameExchange.kt` の `LatestFrameSlot`）に置きます。エンジンがまだ取り出していない古いフレームは、新しいフレームで置き換えられます。エンジンの段は、受け渡し場所から最新のフレームを取ってグリッチ処理の `suspend fun` を待つ 1 本のコルーチンです（wasm 自体は、コンポーネントのインスタンスのスケジューラーのスレッドで動きます）。エンジンが 1 フレームを終えた時点で次のフレームはもう変換済みなので、カメラの次のフレームが届くのを待つ時間と変換の時間が、エンジンの処理と重なります。変換先の配列は `FrameBufferPool` から取り（同時に使うのは最大 3 枚）、エンジンが読んでいる配列を上書きしません。表示の段は専用のスレッド（`glitch-present`）で、結果を Bitmap にコピーしてプレビューに描き、録画中はエンコーダーに渡します。3 つの段が並行して進むので、1 フレームにかかる時間は各段の合計ではなく、いちばん遅い段の時間になります。`renderRgba` は毎フレーム新しい配列を返すので、エンジンの段はそれをコピーせずに表示の段へ渡します。表示の段が前のフレームを処理している間に新しい結果が来ると、まだ取り出されていない古い結果は新しい結果で置き換えられます（表示と録画は、表示の段が処理できる頻度になります）。

プレビューは `PreviewRenderer.kt` が、表示のスレッドから Compose の `AndroidExternalSurface` の `Surface` に `lockHardwareCanvas` で直接描きます。新しいフレームが来ても、Compose の再コンポーズも描画も起きません。またプレビューは別のレイヤーとしてシステムが合成するので、アプリ側の GPU での合成も要りません。Bitmap への書き込みと描画を同じスレッドで順に行うので、プレビュー用の Bitmap は 1 枚を使い回しても、書き込み途中の画素が表示されることはありません（`unlockCanvasAndPost` は Bitmap の転送を終えてから戻ります）。Surface が破棄されるとき（`onDestroyed`）は、描画中のフレームが終わるのを待ってから戻ります。フレームの統計（サイズ、処理時間、FPS）は `GlitchUiState` とは別の `PreviewFrame` の `StateFlow` で渡し、FPS 表示と設定シートの統計だけがこれを読みます。FPS 表示は `derivedStateOf` で表示する文字列だけを読むので、再コンポーズは毎フレームではなく、表示する FPS の整数値が変わったときだけ起きます。カメラ側の画面（`LiveCameraSection`）と設定シートは `GlitchUiState` 全体ではなく表示に使う値だけを受け取るので、フィルターのスライダーを動かしても再コンポーズされません。

`RecipePresets.kt` は複数のフィルタを組み合わせたプリセットレシピと、`listFilters` のメタデータから単一フィルタの YAML レシピを組み立てるヘルパーを提供します。`GlitchCameraScreen.kt` は Jetpack Compose で Pixelfray のモバイル向け縦並びレイアウトとフォルダブル向け2ペインレイアウトを画面幅に応じて切り替え、カメラプレビュー、解像度切り替え、ズーム切り替え、前後カメラ切り替え、プリセットとシングルのサムネイル一覧、単一フィルタのパラメータスライダー、設定ボトムシートでの PNG 予測フィルタ戦略および `SeedMode` 切り替え、静止画保存、MP4 録画ボタンを描画します。

## WebAssembly コンポーネントの更新手順

ローカルの `png-glitch` リポジトリから `png-glitch-component.wasm` を再ビルドする場合は、付属のスクリプトを実行します。

```bash
./samples/android-glitch-camera/build-wasm.sh
```

環境変数 `PNG_GLITCH_DIR` で `png-glitch` のクローン先パスを上書きできます。既定値は `/Users/chikoski/png-glitch` です。

## ビルドとテスト

リポジトリのルートから以下のコマンドを実行します。

```bash
./gradlew -p samples/android-glitch-camera :app:testDebugUnitTest
./gradlew -p samples/android-glitch-camera :app:assembleDebug
./gradlew -p samples/android-glitch-camera :app:assembleRelease
```

`testDebugUnitTest` はホスト JVM 上で `PngglitchComponent` を起動し、`listFilters` のメタデータ検証、全プリセットと全フィルタの `validateRecipe` 検証、複数フレームにわたる `processFrame` と `captureSnapshotPng` の動作を検証します。

## フレーム処理の計測

各段の処理は、`FrameTrace.kt` の名前でトレース区間（`android.os.Trace`）に囲んでいます。エンジンの段の区間は、`suspend fun` の呼び出しをまたいでスレッドが変わることがあるので、非同期の区間（`Trace.beginAsyncSection`。API 29 以降で記録されます）にしています。Perfetto や Android Studio のプロファイラーで、`glitch.convert`（変換の段の 1 フレーム）、`glitch.analyze`（エンジンの段の 1 フレーム。その中に `glitch.reloadPixels`、`glitch.setRecipe`、`glitch.apply`、`glitch.renderRgba`）と `glitch.present`（表示の段の 1 フレーム。その中に `glitch.copyToBitmap`、`glitch.draw`、`glitch.encode`）の内訳を見られます。

`:baselineprofile` モジュールの `FramePipelineBenchmark` は、カメラの代わりに合成フレーム（1280×720 の RGBA を 90 度回転する、縦持ちの背面カメラ相当）を 120 フレーム流し、処理解像度（360p、540p、720p）ごとに各区間の平均時間を計測します。カメラのないエミュレーターでも動きます。合成フレームはアプリを `com.example.wasm2kt.glitchcamera.SYNTHETIC_FRAMES`（フレーム数）と `com.example.wasm2kt.glitchcamera.SYNTHETIC_RESOLUTION`（`CameraResolutionOption` の名前）の extra 付きで起動すると流れ、終わるとステータスに `Synthetic frames done` を表示します。

```bash
./gradlew -p samples/android-glitch-camera :baselineprofile:connectedBenchmarkReleaseAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.example.wasm2kt.glitchcamera.baselineprofile.FramePipelineBenchmark
```

合成フレームでも、変換はエンジンの処理と重ねて行います（ただしフレームは捨てず、120 フレームすべてをエンジンに渡します）。`glitch.syntheticRun` はエンジンの段で 120 フレームを処理し終えるまでの時間で、120 をこれで割るとエンジンのスループット（FPS）になります。`glitch.presentedFrames` は実際に表示したフレーム数で、120 より少なければ表示の段のほうが遅いことを示します。

## ベースラインプロファイルの生成

`:baselineprofile` モジュールと `androidx.baselineprofile` プラグインを使って、アプリの起動と `png-glitch-component` のフレーム処理ホットパスを含むベースラインプロファイルを生成します。`automaticGenerationDuringBuild = true` を設定しているため、エミュレーターまたは実機を接続した状態で `:app:assembleRelease` を実行すると、リリースビルドの作成時にベースラインプロファイルの収集が自動的に走り、生成されたプロファイルと DEX レイアウト最適化が APK に組み込まれます。単体でプロファイル生成のみを実行する場合は以下のコマンドを使用します。

```bash
./gradlew -p samples/android-glitch-camera :app:generateBaselineProfile
```

プロファイル収集時は `MainActivity` にウォームアップ用のフラグが渡されます。物理カメラの入力がないエミュレーター環境でも、合成 RGBA フレームに対する `fromPixels`、`reloadPixels`、`apply`、`renderRgba`、`snapshot` の一連の呼び出しがワーカースレッドで実行され、AOT コンパイルされた WebAssembly コンポーネントのホットパスがプロファイルに記録されます。生成されたプロファイルは `app/src/release/generated/baselineProfiles/` に出力されます。

