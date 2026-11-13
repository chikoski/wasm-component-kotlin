# png-glitch Android カメラデモ

`png-glitch` の WebAssembly コンポーネントである `png-glitch-component.wasm` と `wasm-component-kotlin` の Chicory バックエンドを組み合わせた Android カメラデモアプリです。カメラのライブ入力フレームに対して PNG 予測フィルタ破壊を含むグリッチエフェクトを適用し、プレビュー表示と PNG 静止画保存および MP4 動画録画を行います。

## 構成

このプロジェクトはリポジトリルートのプラグインとランタイムを `includeBuild` で参照する独立した Gradle プロジェクトです。`app/src/main/wasm/png-glitch-component.wasm` をビルド時に分解して JVM バイトコードへ AOT コンパイルし、型安全な Kotlin バインディングを生成します。

主なソースコードと役割は以下の通りです。

- `GlitchEngine.kt`
- `RecipePresets.kt`
- `FrameConverter.kt`
- `Mp4VideoRecorder.kt`
- `MediaStoreHelper.kt`
- `GlitchViewModel.kt`
- `GlitchCameraScreen.kt`
- `MainActivity.kt`

`GlitchEngine.kt` は生成された `PngglitchComponent` のセッションと `chikoski:png-glitch/live` の `Session` リソースを管理します。WIT のエクスポート関数は `async func` として定義されており、生成された `suspend fun` バインディングと `Mutex` を組み合わせてコルーチンから非同期に呼び出します。初回フレームで `Session.fromPixels` を呼び出し、2フレーム目以降は `reloadPixels` と `apply` と `renderRgba` を繰り返すことで PNG チャンク構築のオーバーヘッドを抑えます。静止画シャッター押下時には `snapshot` を呼び出し、グリッチ状態の真正な PNG バイト列を取得します。

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

## ベースラインプロファイルの生成

`:baselineprofile` モジュールと `androidx.baselineprofile` プラグインを使って、アプリの起動と `png-glitch-component` のフレーム処理ホットパスを含むベースラインプロファイルを生成します。`automaticGenerationDuringBuild = true` を設定しているため、エミュレーターまたは実機を接続した状態で `:app:assembleRelease` を実行すると、リリースビルドの作成時にベースラインプロファイルの収集が自動的に走り、生成されたプロファイルと DEX レイアウト最適化が APK に組み込まれます。単体でプロファイル生成のみを実行する場合は以下のコマンドを使用します。

```bash
./gradlew -p samples/android-glitch-camera :app:generateBaselineProfile
```

プロファイル収集時は `MainActivity` にウォームアップ用のフラグが渡されます。物理カメラの入力がないエミュレーター環境でも、合成 RGBA フレームに対する `fromPixels`、`reloadPixels`、`apply`、`renderRgba`、`snapshot` の一連の呼び出しがワーカースレッドで実行され、AOT コンパイルされた WebAssembly コンポーネントのホットパスがプロファイルに記録されます。生成されたプロファイルは `app/src/release/generated/baselineProfiles/` に出力されます。

