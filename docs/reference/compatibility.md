# 互換性とバージョン要件

このリポジトリで検証・固定しているツールとランタイムのバージョン一覧です。

## 1. 利用者側の要件

| 項目 | Chicory + Gradle プラグイン | Wasmtime + CLI | 備考 |
| :--- | :--- | :--- | :--- |
| ビルド時の JDK | JDK 21 | JDK 21 | `tooling` や `gradle-plugin` などが JVM 21 を対象とするため、`gradle/gradle-daemon-jvm.properties` で JDK 21 を指定しています |
| **実行時の JVM / Android** | **JVM 17 以上** / **Android `minSdk 26` 以上** | **JVM 21 以上** | `runtime-api` と `runtime-chicory` は JVM 17 ターゲットかつ `animalsniffer` で Android API 26 のシグネチャに対して検査済み（[#80](https://github.com/chikoski/wasm-component-kotlin/issues/80)）。Wasmtime の FFM には `--enable-preview --enable-native-access=ALL-UNNAMED` が必要 |
| Kotlin | 2.4.21 で検証 | 2.4.21 で検証 | 生成コードはコンテキストパラメータを使用します。成果物は Kotlin 2.4 メタデータで出力されるため、利用側も Kotlin 2.3 以上が必要です |
| Gradle | 9.9.1 で検証 | 9.9.1 で検証 | Kotlin/JVM および Android 向け `functionalTest` とサンプルビルドで設定キャッシュ再利用を確認済みです |
| Android Gradle Plugin | 9.5.1 で検証 | — | `gradle-api` 9.5.1 に対してビルドしました。ビルトイン Kotlin 構成のサンプルと `functionalTest` で動作を確認済みです。AGP 8 系は検証対象外です |

## 2. コンポーネント処理・ネイティブツールチェーンのバージョン

| ツール / ライブラリ | バージョン | 用途と方針 |
| :--- | :--- | :--- |
| **Chicory** | `1.7.5` | `runtime-chicory`（実行時）および `tooling`（ビルド時コンパイラ、分解ツール実行用 WASI） |
| **Wasmtime / `wasmtime-environ`** | `=48.0.8`（LTS） | `rust-bridge` の `wasmtime` と `decomposer` の `wasmtime-environ` を同じ LTS の同じパッチ版に固定（[ADR-0010](../adr/0010-wasmtime-lts.md)） |
| **Rust ツールチェーン** | `1.97.0` | `rust-bridge/rust-toolchain.toml` と `decomposer/rust-toolchain.toml` で固定（[#38](https://github.com/chikoski/wasm-component-kotlin/issues/38)） |
| **`wasm-tools`** | `1.258.0` | CI とローカルの `integration-tests` / CLI で使用（`cargo install wasm-tools --version 1.258.0 --locked`）。Gradle プラグインのみの利用では不要 |

## 3. モジュール別のターゲット JVM

| モジュール | JVM ターゲット | Android API 26 検査 |
| :--- | :--- | :--- |
| `runtime-api` | 17 | あり（`animalsnifferMain`） |
| `runtime-chicory` | 17 | あり（`animalsnifferMain`） |
| `ast` / `generator` / `cli` / `tooling` / `gradle-plugin` | 21 | —（ビルド時・ホスト専用） |
| `runtime-wasmtime` | 21（`--enable-preview`） | — |
