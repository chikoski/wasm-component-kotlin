# wasm2kt Android サンプル

Gradle プラグイン `io.github.wasm2kt` を使い、ビルド時にコンパイルした WebAssembly コンポーネント（`roundtrip`、`counter`）を
Activity から呼び出すサンプルです。このリポジトリのプラグインとランタイムを composite build で使います。

## 準備

Android SDK の場所を指定します（どちらか一方）。

* `samples/android/local.properties` に `sdk.dir=<SDK のパス>` を書く（Android Studio でこのディレクトリを開くと自動で作られます）。
  macOS の既定の場所は `/Users/<ユーザー名>/Library/Android/sdk` です。このファイルはマシン固有なので、git には入れません
* または環境変数 `ANDROID_HOME` を設定する

JDK については、`gradle/gradle-daemon-jvm.properties` の指定により Gradle デーモンが JDK 21 で起動します（JDK 21 のインストールが必要です）。

## 実行

リポジトリのルートで実行します。

```bash
./gradlew -p samples/android :app:testDebugUnitTest      # ホスト JVM で実行
./gradlew -p samples/android :app:connectedAndroidTest   # 接続した端末 / エミュレータ（ART）で実行
./gradlew -p samples/android :app:installRelease         # R8 で縮小した release 版をインストール
```
