# Android クイックスタート

このガイドでは、Gradle プラグイン `io.github.wasm2kt` を使って `.wasm` コンポーネントをビルド時に JVM バイトコードへコンパイルし、Android アプリから呼び出す手順を説明します。
実行には純 JVM の WebAssembly ランタイムである [Chicory](https://github.com/dylibso/chicory) を使うため、JNI やネイティブライブラリ（`.so`）は不要です。

動くサンプル一式は [`samples/android`](../../samples/android/README.md) にあります。

## 前提条件

* **Gradle デーモン用の JDK 21 以上**: ビルド時のツール群（`tooling` と `gradle-plugin`）が JDK 21 向けにコンパイルされているためです。`gradle/gradle-daemon-jvm.properties` に `toolchainVersion=21` を指定しておくと、シェルの `JAVA_HOME` に関わらず JDK 21 が使われます
* **Android `minSdk 26` 以上**、アプリの `compileOptions` / `jvmTarget` は **Java 17 以上**
* Kotlin 2.4.21 で検証済みです。ルートプロジェクトの `plugins` ブロックに `id("org.jetbrains.kotlin.jvm") version "2.4.21" apply false` を配置すると、AGP 9 のビルトイン Kotlin とバージョンを揃えられます
* Rust ツールチェーンや `wasm-tools` は不要です（コンポーネント分解ツールは WebAssembly としてプラグインに同梱されています）

## 1. リポジトリの取り込み（未公開のため）

現在、成果物は Maven Central や Gradle Plugin Portal には公開していません。このリポジトリをクローンし、`settings.gradle.kts` から composite build（`includeBuild`）で参照します。

```kotlin
// settings.gradle.kts
pluginManagement {
    includeBuild("/path/to/wasm-component-kotlin")
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
    repositories {
        google()
        mavenCentral()
    }
}

// io.github.wasm2kt:runtime-chicory などの依存関係をこのリポジトリのサブプロジェクトに置き換える
includeBuild("/path/to/wasm-component-kotlin")
include(":app")
```

## 2. `build.gradle.kts` の設定

アプリモジュールに `io.github.wasm2kt` プラグインを適用し、`wasm2kt` ブロックで `.wasm` ファイルと出力先のパッケージ名を指定します。

```kotlin
// app/build.gradle.kts
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("io.github.wasm2kt")
}

android {
    namespace = "com.example.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.app"
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

wasm2kt {
    components {
        register("counter") {
            source.set(file("src/main/wasm/counter.wasm"))
            packageName.set("com.example.app.counter")
        }
    }
}
```

プラグインは以下を自動で行います。

* `io.github.wasm2kt:runtime-chicory` を `implementation` に追加します
* `.wasm` を分解して core モジュールを JVM バイトコードにコンパイルし、生成した jar を依存関係に追加します
* Kotlin のバインディングと、セッションの入口となる `CounterComponent` オブジェクトを生成し、各 Android バリアントのソースセットに登録します

## 3. Kotlin からの呼び出し

指定したパッケージのトップレベルに `withSession` と `openSession` が生成されます。`withSession(CounterComponent) { ... }` のブロック内で、エクスポートされた関数やリソースを呼び出せます。

```kotlin
package com.example.app

import com.example.app.counter.CounterComponent
import com.example.app.counter.exports.example.counter.counter_api.Counter
import com.example.app.counter.withSession

fun runCounter(): Int =
    withSession(CounterComponent) {
        Counter(10).use { a ->
            a.increment()
            val b = Counter(31)
            Counter.merge(a, b).use { merged ->
                merged.getValue() // 42
            }
        }
    }
```

> 初回の `openSession` / `withSession` では、コンパイル済みクラスのロードと `.meta` の解析が行われます（コンポーネントの大きさにより数十〜数百 ms）。Android アプリでは、初回の呼び出しをメインスレッドの外（`Dispatchers.Default` など）で行うことを推奨します（[性能とサイズ](../explanation/performance.md#4-初回のインスタンス化)）。

## 次のステップ

* ホスト関数の実装（`imports(...)`）：[ホスト import と interface の共有](../guides/host-imports.md)
* WIT の型と Kotlin の型の対応：[WIT 型マッピングと DSL](../reference/type-mapping.md)
* プラグインの全設定項目：[Gradle プラグイン リファレンス](../reference/gradle-plugin.md)
