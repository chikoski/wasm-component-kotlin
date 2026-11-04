# 互換性とバージョン要件

このリポジトリで検証・固定しているツールとランタイムのバージョン一覧です。

## 1. 利用者側の要件

| 項目 | Chicory + Gradle プラグイン | Wasmtime + CLI | 備考 |
| :--- | :--- | :--- | :--- |
| **ビルド時の JDK（Gradle デーモン）** | **JDK 21** | **JDK 21** | `tooling`・`gradle-plugin`・`generator`・`cli` は JVM 21 ターゲット。Gradle 8.12 は JDK 25 上では動かないため、`gradle/gradle-daemon-jvm.properties` で 21 を指定 |
| **実行時の JVM / Android** | **JVM 11 以上** / **Android `minSdk 26` 以上** | **JVM 21 以上** | `runtime-api` と `runtime-chicory` は JVM 11 ターゲットかつ `animalsniffer` で Android API 26 のシグネチャに対して検査済み（[#80](https://github.com/chikoski/wasm-component-kotlin/issues/80)）。Wasmtime の FFM には `--enable-preview --enable-native-access=ALL-UNNAMED` が必要 |
| **Kotlin** | **2.2.0 以上** | **2.2.0 以上** | 生成コードが Context Parameters（`-Xcontext-parameters`）を使用 |
| **Gradle** | **8.12** で検証 | **8.12** で検証 | Gradle 9 は未検証（[#20](https://github.com/chikoski/wasm-component-kotlin/issues/20)） |
| **Android Gradle Plugin (AGP)** | **8.7〜8.9** で検証（`gradle-api` 8.7.3、サンプルは 8.9.3） | — | AGP 9 は未検証（[#20](https://github.com/chikoski/wasm-component-kotlin/issues/20)） |

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
| `runtime-api` | 11 | あり（`animalsnifferMain`） |
| `runtime-chicory` | 11 | あり（`animalsnifferMain`） |
| `ast` / `generator` / `cli` / `tooling` / `gradle-plugin` | 21 | —（ビルド時・ホスト専用） |
| `runtime-wasmtime` | 21（`--enable-preview`） | — |
