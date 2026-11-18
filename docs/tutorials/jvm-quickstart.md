# Kotlin/JVM クイックスタート（Chicory バックエンド）

このガイドでは、Gradle プラグイン `io.github.wasm2kt` を使って、Kotlin/JVM プロジェクトで `.wasm` コンポーネントをビルド時にコンパイルして実行する手順を説明します。
純 JVM のランタイム（Chicory）で動くため、ネイティブライブラリや `--enable-native-access`、Rust ツールチェーン、`wasm-tools` は不要です。

## 前提条件

* **Gradle デーモン用の JDK 21 以上**（ビルド時ツールが JDK 21 向けのため。生成されたアプリの実行自体は JVM 17 以上で動きます）
* Kotlin 2.4.21 で検証済みです

## 1. リポジトリの取り込み（未公開のため）

成果物はまだ公開していないため、`settings.gradle.kts` でこのリポジトリを `includeBuild` します。

```kotlin
// settings.gradle.kts
pluginManagement {
    includeBuild("/path/to/wasm-component-kotlin")
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}

includeBuild("/path/to/wasm-component-kotlin")
```

## 2. `build.gradle.kts` の設定

```kotlin
// build.gradle.kts
plugins {
    kotlin("jvm") version "2.4.21"
    id("io.github.wasm2kt")
}

wasm2kt {
    components {
        register("calculator") {
            source.set(file("src/main/wasm/arithmetic.wasm"))
            packageName.set("com.example.arithmetic")
        }
    }
}
```

この設定で、ビルド時に次のタスクが実行されます。

1. `wasm2ktDecomposeCalculator`: `arithmetic.wasm` を core モジュールと `plan.json`、`wit.json` に分解する
2. `wasm2ktCompileCalculator`: core モジュールを JVM バイトコードへコンパイルし、`wasm2kt-calculator.jar` にまとめる
3. `wasm2ktGenerateCalculator`: Kotlin バインディングと `CalculatorComponent` を生成し、`main` ソースセットに追加する

## 3. コンポーネントの呼び出し

```kotlin
package com.example.app

import com.example.arithmetic.CalculatorComponent
import com.example.arithmetic.add
import com.example.arithmetic.sub
import com.example.arithmetic.withSession

fun main() {
    withSession(CalculatorComponent) {
        println("20 + 7 = ${add(20, 7)}")
        println("50 - 8 = ${sub(50, 8)}")
    }
}
```

同じパッケージで複数のコンポーネントを使う場合は、`withSession` の名前が重なるため、コンポーネントごとに別パッケージを指定して `import ... as` で別名を付けるか、パッケージ名で修飾します。

## 次のステップ

* 実行可能なサンプルコード：[Example 05: Gradle プラグインと Chicory バックエンド](../../examples/05-chicory-gradle-plugin/README.md)
* Wasmtime（ネイティブ）との違いを確認する：[バックエンドの選び方](../guides/choosing-a-backend.md)
* プラグインの設定項目とキャッシュの挙動：[Gradle プラグイン リファレンス](../reference/gradle-plugin.md)
* ホスト import の実装方法：[ホスト import と interface の共有](../guides/host-imports.md)
