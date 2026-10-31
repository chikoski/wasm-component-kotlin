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
```

## ヘッドレスエミュレーターで確認する（Android CLI）

[Android CLI](https://developer.android.com/tools/agents/android-cli)（`android` コマンド）を使うと、画面を出さないエミュレーターで確認できます。
CLI はエミュレーターの作成・起動・停止とアプリの起動を担当し、テスト本体は Gradle で実行します。

### 1. エミュレーターを用意して起動する

```bash
android emulator create                         # 既定の medium_phone プロファイルで AVD を作る（初回だけ）
android emulator list                           # AVD の名前を確認
android emulator start --headless medium_phone  # ウィンドウなしで起動。起動が完了するまで待ってから戻る
adb devices                                     # emulator-5554 などが device と表示されれば準備完了
```

### 2. instrumented test（debug ビルド）

```bash
./gradlew -p samples/android :app:connectedAndroidTest
```

結果のレポートは `samples/android/app/build/reports/androidTests/connected/` に出力されます。

### 3. R8 で縮小した release ビルドを動かす

```bash
./gradlew -p samples/android :app:assembleRelease
android run samples/android/app/build/outputs/apk/release/app-release.apk   # インストールして起動
adb emu screenrecord screenshot ./release.png                                # エミュレーターの画面を保存
```

`release.png` に次の 7 行と `took N ms` が表示されていれば成功です。失敗した場合は `Failed: ...` と表示されます。

```text
math-api.scale(7) = 42
describe-code("ERR", 404) = ERR#404
transform-summary(add-count 5).count = 15
check-even(84) = 42
nested-option(none) is none = true
echo-u64(max) = 18446744073709551615
counter merge(11, 31) = 42
```

### 4. エミュレーターを止める

```bash
android emulator stop
```

## 確認の記録

| 日付 | 環境 | 内容 | 結果 |
| :--- | :--- | :--- | :--- |
| 2026-10-31 | ヘッドレスエミュレーター（medium_phone） | `connectedAndroidTest`（debug） | 成功 |
| 2026-10-31 | ヘッドレスエミュレーター（medium_phone） | release（R8 縮小）を `android run` で起動 | 成功（`took 2 ms`）。先頭の行がタイトルバーに隠れる表示崩れがあり、修正済み |
