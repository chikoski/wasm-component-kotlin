# ドキュメント目次

`wasm2kt` は、WebAssembly コンポーネントから Kotlin のホストバインディングを生成します。
実行バックエンドは純 JVM で動作し Android に対応する `AOT` と `CHICORY`、および Rust ブリッジによるネイティブ実行を行う `Wasmtime` を備えています。Gradle プラグイン `io.github.wasm2kt` では、同期コンポーネントと非同期コンポーネントの双方で自作 `AOT` バックエンドが既定です。

目的と立場に合わせて、以下の入口から読み進めてください。

## 読者別の入口

| 読者 | やりたいこと | 読む順番 |
| :--- | :--- | :--- |
| **Android アプリ開発者** | `.wasm` コンポーネントを Android アプリに組み込んで呼ぶ | [Android クイックスタート](tutorials/android-quickstart.md) → [Gradle プラグイン](reference/gradle-plugin.md) → [型マッピング](reference/type-mapping.md) |
| **Kotlin/JVM 開発者（純 JVM）** | ネイティブライブラリなしで `.wasm` をビルド時にコンパイルして呼ぶ | [Kotlin/JVM クイックスタート](tutorials/jvm-quickstart.md) → [Gradle プラグイン](reference/gradle-plugin.md) → [型マッピング](reference/type-mapping.md) |
| **サーバー JVM 開発者（Wasmtime）** | CLI でバインディングを生成し、Wasmtime でネイティブ実行する | [Wasmtime クイックスタート](tutorials/wasmtime-quickstart.md) → [CLI リファレンス](reference/cli.md) → [バックエンドの選び方](guides/choosing-a-backend.md) |
| **コンポーネント作者** | `wasm2kt` のホスト上で動く `.wasm` コンポーネントを作る | [コンポーネント作者向けガイド](guides/authoring-components.md) → [機能の対応表](reference/feature-support.md) |
| **貢献者・メンテナー** | ビルド・テスト手順や内部構造、設計判断を確認する | [CONTRIBUTING.md](../CONTRIBUTING.md) → [アーキテクチャ](explanation/architecture.md) → [ADR](adr/README.md) → [STATUS.md](STATUS.md) |

---

## 一覧

### チュートリアル (`tutorials/`)

* [Android クイックスタート](tutorials/android-quickstart.md): Gradle プラグインで `.wasm` をビルド時にコンパイルし、Android アプリから呼び出す
* [Kotlin/JVM クイックスタート](tutorials/jvm-quickstart.md): Gradle プラグインと Chicory バックエンドで、Kotlin/JVM から呼び出す
* [Wasmtime クイックスタート](tutorials/wasmtime-quickstart.md): `wasm2kt` CLI でバインディングを生成し、Wasmtime ブリッジで実行する
* [機能別サンプル集 (`examples/`)](../examples/README.md): 四則演算、ホスト import、`resource`、単体テスト、Gradle プラグイン、非同期ストリーム、WASI 0.3 CLI の実行可能なサンプルコード

### ガイド (`guides/`)

* [バックエンドの選び方](guides/choosing-a-backend.md): `AOT`、`CHICORY`、および `Wasmtime` の汎用ブリッジと型付きブリッジの違い
* [ホスト import と interface の共有](guides/host-imports.md): `imports(...)`、`Linker`、ホスト定義 `resource`、既存パッケージへの対応付け（`with`）
* [リソースと所有権](guides/resources-and-ownership.md): コンポーネント定義 `resource`、`own` と `borrow`、`AutoCloseable`
* [エラーとトラップ](guides/errors-and-traps.md): `ComponentTrapException`、インスタンスの poison、ホスト側の引数エラーとの違い
* [テストの書き方](guides/testing.md): `InMemoryWasmtimeSession` と `ChicoryComponent.fromDirectory`
* [コンポーネント作者向けガイド](guides/authoring-components.md): 対応する WIT 機能、Rust でのビルド、未対応機能の事前検査
* [トラブルシューティング](guides/troubleshooting.md): JDK・Gradle デーモン、ネイティブブリッジの探索、大きな wasm 関数など

### リファレンス (`reference/`)

* [WIT 型マッピングと DSL](reference/type-mapping.md): WIT の各型・world・interface と生成される Kotlin コードの対応
* [Gradle プラグイン](reference/gradle-plugin.md): `wasm2kt` 拡張 DSL、生成されるタスク、成果物、キャッシュの挙動
* [CLI リファレンス](reference/cli.md): `wasm2kt generate` と `wasm2kt bridge` のオプション
* [機能の対応表](reference/feature-support.md): WIT・Canonical ABI・WASI のバックエンド別対応状況
* [互換性とバージョン要件](reference/compatibility.md): JDK、Kotlin、Gradle、AGP、Android `minSdk`、Wasmtime、Rust、`wasm-tools`

### 仕組みと計測 (`explanation/`)

* [アーキテクチャ](explanation/architecture.md): モジュール構成、コード生成、Chicory バックエンド、Wasmtime ブリッジの全体像
* [ビルドの順序](explanation/build-pipeline.md): 分解・AOT コンパイル・コード生成・型付きブリッジのビルドパイプライン
* [呼び出しの経路](explanation/call-path.md): Kotlin の関数呼び出しからバイナリ形式（`WitWriter` / `WitReader`）を経て wasm 関数に至る経路
* [性能とサイズ](explanation/performance.md): JVM と ART での呼び出し時間、初回インスタンス化、APK サイズの計測結果

### 記録 (`adr/`・`notes/`・`archive/`)

* [プロジェクトの現状とアクションアイテム (`STATUS.md`)](STATUS.md)
* [設計判断の記録 (`adr/`)](adr/README.md)
* [作業メモ (`notes/`)](notes/phase1-implementation-tasks.md)
* [過去の記録 (`archive/`)](archive/SPEC.md)
* [変更履歴 (`CHANGELOG.md`)](../CHANGELOG.md)
