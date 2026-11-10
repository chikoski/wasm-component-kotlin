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

`GlitchEngine.kt` は生成された `PngglitchComponent` のセッションと `chikoski:png-glitch/live` の `Session` リソースを管理します。初回フレームで `Session.fromPixels` を呼び出し、2フレーム目以降は `reloadPixels` と `apply` と `renderRgba` を繰り返すことで PNG チャンク構築のオーバーヘッドを抑えます。静止画シャッター押下時には `snapshot` を呼び出し、グリッチ状態の真正な PNG バイト列を取得します。

`RecipePresets.kt` は複数のフィルタを組み合わせたプリセットレシピと、`listFilters` のメタデータから単一フィルタの YAML レシピを組み立てるヘルパーを提供します。`GlitchCameraScreen.kt` は Jetpack Compose でカメラプレビュー、解像度切り替え、前後カメラ切り替え、プリセット選択、単一フィルタのパラメータスライダー、PNG 予測フィルタ戦略、`SeedMode` 切り替え、静止画保存、MP4 録画ボタンを描画します。

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
