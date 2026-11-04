# Wasmtime クイックスタート

このガイドでは、`wasm2kt` CLI で `.wit` または `.wasm` から Kotlin バインディングを生成し、Rust 製の Wasmtime ブリッジ（Java 21 FFM API または JNI）を使ってネイティブ実行する手順を説明します。

純 JVM や Android で動かしたい場合は、[Android クイックスタート](android-quickstart.md) または [Kotlin/JVM クイックスタート](jvm-quickstart.md) を参照してください。

## 1. 前提ツールの準備

* **JDK 21 以上**: Foreign Function & Memory (FFM) API（`java.lang.foreign`）を使用します
* **Kotlin 2.2.0 以上**: Context Parameters（`-Xcontext-parameters`）を使用します
* **Rust ツールチェーン (`cargo`)**: `rust-bridge/`（このリポジトリでは `1.97.0` に固定）のビルドに使用します
* **`wasm-tools` CLI**: `.wit` や `.wasm` から WIT の JSON AST を抽出するために使用します（CI は `1.258.0` で検証）

```bash
cargo install wasm-tools --version 1.258.0 --locked
```

> Gradle デーモンは `gradle/gradle-daemon-jvm.properties` の指定により JDK 21 で起動します。Homebrew 等で入れた JDK 21 を Gradle が見つけられない場合は、`~/.gradle/gradle.properties` に `org.gradle.java.installations.paths=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home` を追加してください。

## 2. ネイティブ Wasmtime ブリッジのビルド

リポジトリのルートで、`rust-bridge` の共有ライブラリ（macOS: `libwasm2kt_wasmtime_bridge.dylib` / Linux: `libwasm2kt_wasmtime_bridge.so` / Windows: `wasm2kt_wasmtime_bridge.dll`）をビルドします。

```bash
cargo build --release --manifest-path rust-bridge/Cargo.toml
```

`BridgeLibrary` は次の順序でライブラリを探します。

1. JVM システムプロパティ `-Dwasm2kt.bridge.lib=/path/to/lib...` または環境変数 `WASM2KT_BRIDGE_LIB`
2. クラスパス上の `META-INF/wasm2kt/native/<platform>/`（型付きブリッジを jar に同梱した場合）
3. カレントディレクトリまたは親ディレクトリの `rust-bridge/target/release/`（および `debug/`）

## 3. Kotlin バインディングの生成

`wasm2kt`（`./gradlew :cli:run` または `./gradlew run`）に `.wit` ファイルまたは `.wasm` バイナリを渡して生成します。

```bash
# .wit ファイルから生成する
./gradlew run --args="fixtures/arithmetic.wit -o build/generated -p com.example.arithmetic"

# .wasm コンポーネントバイナリから直接生成する（埋め込み WIT では world 名は root になります）
./gradlew run --args="fixtures/arithmetic.wasm -o build/generated -p com.example.arithmetic"
```

指定した出力先に、以下のファイルが生成されます。

* `WitSupport.kt`: セッションの実装（`Session`、`FfmWasmtimeSession`、`JniWasmtimeSession`、`InMemoryWasmtimeSession` など）
* `ArithmeticWorld.kt`（`.wasm` から生成した場合は `RootWorld.kt`）: パッケージのトップレベルに置かれる `withSession`、`openSession`、`imports(...)`、および world がエクスポートする関数・型（[ADR-0012](../adr/0012-world-at-package-top-level.md)）
* import / export された interface がある場合は、`<package>/<ns>/<pkg>/<iface>/` および `<package>/exports/<ns>/<pkg>/<iface>/` 配下のファイル（[ADR-0011](../adr/0011-qualified-interface-keys.md)）

## 4. ホスト側 Gradle プロジェクトの設定

生成されたコードは `io.github.wasm2kt:runtime-wasmtime`（および推移的に `io.github.wasm2kt:runtime-api`）に依存します。`kotlin-reflect` や `kotlinx-serialization` は不要です。

成果物は未公開のため、別プロジェクトから使う場合は `settings.gradle.kts` に `includeBuild("/path/to/wasm-component-kotlin")` を追加するか、このリポジトリで `./gradlew publishToMavenLocal` を実行して `mavenLocal()` から参照します。

```kotlin
// build.gradle.kts
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm") version "2.2.0"
}

dependencies {
    implementation("io.github.wasm2kt:runtime-wasmtime:0.1.0-SNAPSHOT")
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_21)
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

## 5. アプリケーションからの呼び出し

生成されたトップレベルの `withSession(wasmPath) { ... }` の中で、エクスポート関数を呼び出します。ブロックを抜けると Wasmtime の `Store` が閉じられます。

```kotlin
package com.example.arithmetic

import java.nio.file.Paths

fun main() {
    val wasmPath = Paths.get("fixtures/arithmetic.wasm")

    withSession(wasmPath) {
        println("20 + 7 = ${add(20, 7)}")
        println("50 - 8 = ${sub(50, 8)}")
        println("-6 * -7 = ${mul(-6, -7)}")
        println("84 / 2 = ${div(84, 2)}")
    }
}
```

## 6. 事前コンパイル（`.cwasm`）

`openSession` は呼び出しのたびに `.wasm` をコンパイルします。起動時のコンパイルを省くには、`FfmWasmtimeBridge.getDefault().precompileComponent(wasmPath)` で `.cwasm` を作成し、`withPrecompiledSession` / `openPrecompiledSession` で読み込みます。

```kotlin
withPrecompiledSession(Paths.get("build/arithmetic.cwasm")) {
    println(add(1, 2))
}
```

> **注意**: `.cwasm` にはネイティブの機械語が含まれており、Wasmtime は読み込み時にその内容を検証しません。細工された `.cwasm` を渡すとサンドボックスを経ずにコードが実行されるため、**自分のビルドで作成した信頼できる `.cwasm` だけを渡してください**。`withSession` / `openSession` は `.wasm` だけを受け付け、`.cwasm` を渡すとエラーになります。また `.cwasm` は作成したときと同じ Wasmtime バージョンのブリッジでしか読み込めません。

## 7. （任意）型付きブリッジ（`wasm2kt bridge`）

汎用ブリッジ（手順 2）は、どのコンポーネントでもそのまま動かせる代わりに、Wasmtime の `Val` を介して要素ごとに値を変換します。
`list<u8>` など大きなデータを頻繁に受け渡す場合は、`wasm2kt bridge` でコンポーネント専用の型付きブリッジを生成・ビルドできます（[ADR-0016](../adr/0016-typed-bridge-from-wit-json.md)）。

```bash
# 1. ブリッジ内のコンポーネント ID を指定してバインディングを生成する
./gradlew run --args="generate fixtures/counter.wasm -o build/generated -p com.example.counter --bridge-id counter"

# 2. 型付きブリッジのクレートを生成してビルドし、クラスパス用のリソースディレクトリに出力する
./gradlew run --args="bridge -o build/typed-bridge --component counter=fixtures/counter.wasm --build --resources-output build/typed-bridge-resources"
```

`build/typed-bridge-resources` をクラスパス（または jar のリソース）に含めると、`runtime-wasmtime` が自動的にそのライブラリを読み込みます。詳細は [CLI リファレンス](../reference/cli.md) を参照してください。
