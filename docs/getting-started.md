# Getting Started: 環境構築から最初の Wasm コンポーネント実行まで

このガイドでは、`wasm2kt` を使って WebAssembly Component Model (`.wit` / `.wasm`) から Kotlin のホストバインディングを生成し、実際に Wasmtime 上で動かすまでの手順を解説します。

---

## 1. 前提ツールの準備

以下のツールがインストールされていることを確認してください。

### 1.1 JDK 21 以上
JVM Foreign Function & Memory (FFM) API (`java.lang.foreign`) を使用するため、JDK 21 以上が必要です（Homebrew の場合）：
```bash
brew install openjdk@21
```

> Gradle デーモンは `gradle/gradle-daemon-jvm.properties` により JDK 21 で起動します。シェルの `JAVA_HOME` が JDK 25 などを指していても構いません。
> Homebrew の JDK を Gradle が見つけられない場合は、`~/.gradle/gradle.properties` に次を追加してください（マシン固有の設定なので、リポジトリには入れません）。
>
> ```properties
> org.gradle.java.installations.paths=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
> ```

### 1.2 Rust ツールチェーン & `wasm-tools`
Rust 製の Wasmtime ブリッジのビルドと、WIT から JSON AST を抽出するための `wasm-tools` をインストールします：
```bash
cargo install wasm-tools
```

---

## 2. ネイティブ Wasmtime ブリッジ (`rust-bridge`) のビルド

`wasm2kt` が生成したバインディングは、実行時に Java 21 FFM API を通じて `rust-bridge` 内の共有ライブラリ（macOS: `libwasm2kt_wasmtime_bridge.dylib` / Linux: `libwasm2kt_wasmtime_bridge.so` / Windows: `wasm2kt_wasmtime_bridge.dll`）を呼び出します。

リポジトリルートで以下のコマンドを実行してビルドします：

```bash
cargo build --release --manifest-path rust-bridge/Cargo.toml
```

> **補足**: `FfmWasmtimeBridge` はデフォルトで `rust-bridge/target/release/` 配下のライブラリを自動探索します。任意のパスに配置した場合は、JVM システムプロパティ `-Dwasm2kt.bridge.lib=/path/to/libwasm2kt_wasmtime_bridge.dylib` または環境変数 `WASM2KT_BRIDGE_LIB` で指定できます。

---

## 3. Kotlin バインディングの生成

`wasm2kt` は **`.wit` ファイル** と **コンパイル済みの `.wasm` コンポーネントバイナリ** のどちらからでもコードを生成できます。

### パターンA: `.wit` ファイルから生成する（推奨）

`.wit` ファイルには元の `world` 名（例: `world arithmetic` や `world counter-world`）が保持されているため、`ArithmeticWorld` や `CounterWorld` のような分かりやすい名前の特異オブジェクトが生成されます。

```bash
./gradlew run --args="fixtures/arithmetic.wit -o build/generated -p com.example.arithmetic"
```

生成されるファイル：
* `build/generated/com/example/arithmetic/WitSupport.kt`（共通型 `WitOption`, `WitResult`, `WasmtimeSession`, `FfmWasmtimeSession`, `InMemoryWasmtimeSession`）
* `build/generated/com/example/arithmetic/ArithmeticWorld.kt`（`object ArithmeticWorld` とエクスポート拡張関数群）

### パターンB: `.wasm` コンポーネントバイナリから直接生成する

手元に `.wit` ファイルがなく `.wasm` バイナリのみがある場合でも、`wasm-tools component wit --json` がバイナリ埋め込みの WIT 型情報を抽出します。
（※ `.wasm` バイナリ内の埋め込み WIT では world 名が `root` に正規化されるため、生成される特異オブジェクト名は `RootWorld` になります）

```bash
./gradlew run --args="fixtures/arithmetic.wasm -o build/generated -p com.example.arithmetic"
```

---

## 4. ホスト側 Gradle プロジェクトの設定

生成された `.kt` ファイルを自分の Kotlin アプリケーションに組み込む際は、`build.gradle.kts` に以下の設定を追加します。

```kotlin
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm") version "2.2.0"
    kotlin("plugin.serialization") version "2.2.0"
}

dependencies {
    implementation("org.jetbrains.kotlin:kotlin-reflect:2.2.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.0")
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_21)
        // Kotlin 2.2+ の Context Parameters を有効化
        freeCompilerArgs.addAll(
            "-Xcontext-parameters",
            "-Xjdk-release=21",
        )
    }
}

// Java 21 FFM API とネイティブアクセスを実行時に許可
tasks.withType<JavaExec>().configureEach {
    jvmArgs("--enable-preview", "--enable-native-access=ALL-UNNAMED")
}

tasks.test {
    useJUnitPlatform()
    jvmArgs("--enable-preview", "--enable-native-access=ALL-UNNAMED")
}
```

---

## 5. アプリケーションからの呼び出し

生成された `<WorldName>.withSession(wasmPath) { ... }` を使うだけで、コンテキストパラメータ経由でエクスポート関数やリソースを型安全に呼び出せます。

```kotlin
package com.example.arithmetic

import java.nio.file.Paths

fun main() {
    val wasmPath = Paths.get("fixtures/arithmetic.wasm")

    ArithmeticWorld.withSession(wasmPath) {
        println("20 + 7 = ${ArithmeticWorld.add(20, 7)}")
        println("50 - 8 = ${ArithmeticWorld.sub(50, 8)}")
        println("-6 * -7 = ${ArithmeticWorld.mul(-6, -7)}")
        println("84 / 2 = ${ArithmeticWorld.div(84, 2)}")
    }
}
```

### 事前コンパイル（`.cwasm`）

起動時のコンパイルを省くには、`FfmWasmtimeBridge.getDefault().precompileComponent(wasmPath)` で `.cwasm` を作ります。読み込むときは `withPrecompiledSession` / `openPrecompiledSession` を使います。

```kotlin
ArithmeticWorld.withPrecompiledSession(Paths.get("build/arithmetic.cwasm")) {
    println(ArithmeticWorld.add(1, 2))
}
```

`.cwasm` にはネイティブの機械語が入っていて、Wasmtime はその中身を検証しません。そのため、**自分のビルドで作ったものなど、信頼できる `.cwasm` だけを渡してください**。細工した `.cwasm` を読み込むと、WebAssembly のサンドボックスを経ずにコードが動きます。

`withSession` / `openSession` は `.wasm` だけを受け付け、`.cwasm` を渡すとエラーになります。また、`.cwasm` は作ったときと同じブリッジ（同じ Wasmtime）でしか読み込めません。
