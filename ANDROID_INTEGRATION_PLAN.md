# Android アプリへの組み込みに向けた課題と変更点の洗い出し (`ANDROID_INTEGRATION_PLAN.md`)

現在の `wasm2kt` が生成する Kotlin ホストバインディングおよび Wasmtime ランタイムブリッジを **Android アプリケーション（ART ランタイム）** に組み込んで動作させるために必要な変更点・技術的課題を、重要度順（ブロッカー → ランタイム制約 → パフォーマンス・利便性）に洗い出しました。

---

## 1. 最重要ブロッカー：JVM FFM API (`java.lang.foreign`) から JNI への切り替え（または併用）

### 現状の課題
* 現在の [`FfmWasmtimeBridge`](file:///Users/chikoski/wssm-component-kotin/runtime-wasmtime/src/main/kotlin/wasm2kt/runtime/FfmWasmtimeBridge.kt) および生成される `FfmWasmtimeSession` は、Java 21/22 の **Foreign Function & Memory (FFM) API (`java.lang.foreign.*`)** に依存しています。
* しかし、**Android の ART (Android Runtime) は現時点で `java.lang.foreign` パッケージをサポートしていません**（Android SDK にクラスが存在せず、実行時に `NoClassDefFoundError` となります）。

### 必要な変更
1. **JNI (`JniWasmtimeSession` / `JniWasmtimeBridge`) バックエンドの追加**
   * 生成される Kotlin 側の World や Resource はすでに [`WasmtimeSession`](file:///Users/chikoski/wssm-component-kotin/generator/src/main/kotlin/wasm2kt/generator/WitToKotlinPoetConverter.kt#L1128-L1205) インターフェースによって抽象化されているため、**生成される DSL コード（`withSession`, `Counter`, `add` 等）自体は一切変更不要**です。
   * `WasmtimeSession` の実装として、デスクトップ/サーバー JVM 用の `FfmWasmtimeSession` に加え、Android / 汎用 JVM 用の **`JniWasmtimeSession`** を追加します。
2. **Rust ブリッジ (`rust-bridge`) への JNI エントリポイント追加**
   * [rust-bridge/Cargo.toml](file:///Users/chikoski/wssm-component-kotin/rust-bridge/Cargo.toml) に `jni = "0.21"` クレートを追加し、既存の内部ロジック（`wasm2kt_session_open`, `wasm2kt_session_invoke`, `wasm2kt_session_drop_resource`）をそのまま呼ぶ `Java_wasm2kt_runtime_JniWasmtimeBridge_...` 関数群をエクスポートします。
   * ホスト `Imports` のコールバックも、FFM の `upcallStub` の代わりに JNI の `JNIEnv::call_method`（Java オブジェクトのメソッド呼び出し）で実現できます。

---

## 2. Wasmtime の Android クロスコンパイルと実行エンジン（Cranelift JIT vs Pulley インタープリタ）

### 現状の課題
1. **Android NDK ターゲット向けのクロスコンパイル**
   * 現在はホスト OS（macOS `aarch64-apple-darwin`）向けに `.dylib` をビルドしていますが、Android の APK / AAB に同梱するには `jniLibs/` 配下に各 ABI 向けの `.so`（`libwasm2kt_wasmtime_bridge.so`）を配置する必要があります：
     * `arm64-v8a` (`aarch64-linux-android`) — 実機の主流（必須）
     * `x86_64` (`x86_64-linux-android`) — Android エミュレータ用
2. **Android の W^X / SELinux による JIT メモリ制限とシグナルハンドラ競合**
   * Wasmtime のデフォルト実行エンジンである **Cranelift (JIT)** は、実行時に匿名メモリページを `PROT_EXEC`（実行可能）に変更し、またトラップ処理のために `SIGSEGV` / `SIGBUS` シグナルハンドラを登録します。
   * Android (ART) 自体も Null チェックや Stack Overflow 検知に `SIGSEGV` シグナルハンドラを使用しており、また近年の Android セキュリティポリシーや一部のデバイス環境では動的コード生成（JIT）やシグナルハンドラのフックが制限・競合を起こすケースがあります。

### 必要な変更・対策
1. **`cargo-ndk` による Android `.so` ビルド構成の追加**
   * `cargo ndk -t arm64-v8a -t x86_64 -o ./android/src/main/jniLibs build --release` で Android 向け共有ライブラリをビルドできるようにします。
2. **Wasmtime の実行モードの比較と決定（選択肢A: Pulley インタープリタ vs 選択肢B: ネイティブ AOT `.cwasm`）**

   Android 環境における実行方式として、**選択肢A（Pulley インタープリタ）** と **選択肢B（ネイティブ AOT プリコンパイル `.cwasm`）** の Pros / Cons を以下のように比較・整理しました。

   | 評価軸 | 選択肢A: Pulley (インタープリタ) | 選択肢B: ネイティブ AOT (`.cwasm`) |
   | :--- | :--- | :--- |
   | **実行パフォーマンス** | ○（軽量なロジックや状態管理なら十分高速） | **◎（ネイティブ機械語と同等の最高速度）** |
   | **起動速度 (コールドスタート)** | ○（Pulley AOT 併用時なら ◎） | **◎（コンパイル処理ゼロで瞬時にロード）** |
   | **ART シグナルハンドラ・W^X 競合回避** | **◎（`PROT_EXEC` や `SIGSEGV` を一切使わず完全安全）** | ○（機械語実行のため `PROT_EXEC` とシグナルを使用） |
   | **実機 (`arm64`) / エミュレータ (`x86_64`) 共通化** | **◎（単一バイトコード `pulley64` で全 ABI 共通）** | △（ターゲット CPU ABI ごとに `.cwasm` を生成） |
   | **生の `.wasm` の動的ダウンロード実行** | **◎（端末上で安全にバイトコード変換可能）** | ×（信頼された環境で事前コンパイルした `.cwasm` が必要） |
   | **ランタイム `.so` サイズ削減 (`cranelift` 除外)** | ○（Pulley AOT 事前コンパイル併用時のみ除外可） | **◎（端末から `cranelift` コンパイラを完全に除外可能）** |

   #### 採用方針：選択肢B（AOT プリコンパイル `.cwasm`）をベースとしたハイブリッド構成
   * **基本方針（選択肢B）**: アプリのビルド時に `wasmtime compile` で `.wasm` コンポーネントを事前コンパイル（`.cwasm`）し、Android 端末上では `Component::deserialize`（または `deserialize_file`）でロードする方式を主軸とします。これにより、**最高の実行速度・最速のコールドスタート・最小の `.so` バイナリサイズ（`cranelift` コンパイラ除外）** を実現します。
   * **Pulley AOT（選択肢A）とのシームレスな併用**: Wasmtime の `.cwasm` デシリアライズ API は、ネイティブ機械語（`aarch64-linux-android`）だけでなく **Pulley バイトコード向けに事前コンパイルされた `.cwasm` (`--target pulley64`)** も同じコードパスでロードできます。そのため、パフォーマンス重視の標準構成では **ネイティブ AOT** を使い、全 ABI 単一アセット化やシグナルハンドラ完全回避が必要なケースではビルドオプション一つで **Pulley AOT** も選べる設計にします。

---

## 3. Android リソース（`assets` / `raw` / `ByteArray`）からのコンポーネント読み込み

### 現状の課題
* 現在生成される `openSession(wasmPath: Path, imports: Imports)` は **`java.nio.file.Path`（ファイルシステム上のパス）** のみを受け取ります。
* しかし Android アプリでは、`.wasm` ファイルは通常 **`assets/`（`context.assets.open("app.wasm")`）** や **`res/raw/`** にパッケージングされており、直接のファイルパス（`Path`）を持ちません（また、`java.nio.file.Path` 自体が Android API 26 (Android 8.0) 以降でのみ利用可能です）。

### 必要な変更
1. **`ByteArray` / `InputStream` から直接セッションを開けるオーバーロードの生成**
   * `<WorldName>.openSession` および `<WorldName>.withSession` に以下のオーバーロードを追加します：
     ```kotlin
     // 1. 既存の Path 版（デスクトップ / ファイルキャッシュ用）
     fun openSession(wasmPath: Path, imports: Imports = Imports.EMPTY): Session

     // 2. Android assets やネットワーク取得データに最適な ByteArray 版
     fun openSession(wasmBytes: ByteArray, imports: Imports = Imports.EMPTY): Session
     inline fun <R> withSession(
         wasmBytes: ByteArray,
         imports: Imports = Imports.EMPTY,
         block: context(Session) () -> R,
     ): R
     ```
2. **Rust ブリッジへの `Component::from_binary(&engine, bytes)` 追加**
   * ファイルパス経由の `Component::from_file` だけでなく、メモリ上のバイト列（`*const u8, usize` または JNI `jbyteArray`）から直接 `Component::from_binary` でロードする C ABI / JNI 関数を追加します。これにより、Android 上で一時ファイルに書き出す I/O コストをゼロにできます。

---

## 4. `kotlin-reflect` 依存の排除と R8 / ProGuard 縮小への対応

### 現状の課題
* 現在の [`FfmJsonCodec`](file:///Users/chikoski/wssm-component-kotin/runtime-wasmtime/src/main/kotlin/wasm2kt/runtime/FfmWasmtimeBridge.kt#L247-L485) や生成コードでは、戻り値のデシリアライズや Record / Variant のフィールド走査に **`kotlin-reflect` (`typeOf<T>()`, `KClass.primaryConstructor`, `declaredMemberProperties`)** を使用しています。
* Android アプリ開発において **`kotlin-reflect` は大きなデメリット**になります：
  1. **APK サイズの肥大化**（`kotlin-reflect` 単体で数 MB 増加）。
  2. **実行速度の低下**（特に Android ART 上での初回リフレクション呼び出しは非常に重い）。
  3. **R8 / ProGuard による難読化・圧縮でクラッシュする**（生成された `data class` のプロパティ名やコンストラクタ情報が R8 で削られると動作しなくなる）。

### 必要な変更
* **リフレクション完全廃止：コード生成時（`WitToKotlinPoetConverter`）に型専用のシリアライザ／変換コード（または `@Serializable`）を直接出力する**
  * `wasm2kt` はコード生成器であり、コンパイル時にすべての `record`, `variant`, `enum`, `flags`, `tuple`, `WitOption`, `WitResult` の構造を完全に把握しています。
  * そのため、実行時に `kotlin-reflect` でクラス構造を調べるのではなく、**KotlinPoet で各型の変換関数（または `kotlinx.serialization` の `@Serializable` アノテーション）を直接生成コードに出力**すれば、`kotlin-reflect` への依存を **完全にゼロ** にでき、R8 難読化環境でも高速・安全に動作します。

---

## 5. コルーチン (`Dispatchers.Default` / `IO`) とスレッド安全性

### 現状の課題
* Android ではメインスレッド（UI スレッド）での重い処理や I/O が禁じられており、Kotlin Coroutines (`withContext(Dispatchers.Default)`) で非同期に Wasm を呼び出すのが一般的です。
* 一方、Wasmtime の `Store` は `Send` ではありますが同時に複数スレッドからはアクセスできず、また `Engine` の作成や `.wasm` のコンパイルを `withSession` のたびに行うとオーバーヘッドが大きくなります。

### 必要な変更
1. **`Engine` とコンパイル済み `Component` のキャッシュ機構**
   * 重い初期化処理である `wasmtime::Engine` と `wasmtime::component::Component` を複数セッション間で再利用（キャッシュ）し、`withSession` ごとには軽量な `Store` と `Instance` のみを生成する構造に分離します。
2. **Coroutines 親和性の向上**
   * `withSession` は `inline` 関数のため、現状でも `suspend` ブロック内でそのまま利用可能ですが、同一 `Session` への並行アクセスを保護するロック（またはスレッドアフィニティの注意書き）を整備します。

---

## 6. プロジェクトのマルチモジュール化（Generator CLI と Runtime Library の分離）

### 現状の課題
* 現在は単一の Gradle プロジェクト内に「コード生成 CLI (`kotlinpoet`, `clikt`)」と「実行時ブリッジ (`wasm2kt.runtime`)」が同居しています。
* Android アプリに組み込む際、アプリ本体には `kotlinpoet` や `clikt` は不要であり、軽量のランタイムライブラリ（AAR または JAR）と Gradle Plugin だけが必要です。

### 必要な変更（モジュール分割案）
```text
wasm-component-kotlin/
├── wasm2kt-ast/             # WIT JSON AST モデル
├── wasm2kt-generator/       # KotlinPoet コード生成器コア
├── wasm2kt-cli/             # コマンドラインツール
├── wasm2kt-runtime/         # ランタイム共通インターフェース + JNI / FFM ブリッジ (JVM / Android 互換)
├── wasm2kt-gradle-plugin/   # Android / Kotlin プロジェクトのビルド時に自動で .wit からコード生成するプラグイン
└── rust-bridge/             # macOS/Linux/Windows (FFM & JNI) および Android NDK (.so) 向け Rust クレート
```

---

## まとめ：Android 対応の実装ロードマップ案

| フェーズ | 実装項目 | 効果 |
| :--- | :--- | :--- |
| **Step 1 (最優先)** | **`kotlin-reflect` の排除**（コード生成時に型変換ロジックを直接生成） | APK サイズ削減・R8/ProGuard 完全対応・高速化 |
| **Step 2 (必須)** | **`ByteArray` および AOT プリコンパイル (`.cwasm`) からの `openSession` / `withSession` 対応** | Android の `assets` / `res/raw` から直接 `.wasm` / `.cwasm` を高速ロード可能に |
| **Step 3 (必須)** | **JNI ブリッジ (`JniWasmtimeSession`) の追加** | `java.lang.foreign` 非対応の Android ART 上でネイティブ実行可能に |
| **Step 4 (ビルド)** | **モジュール分割 (`wasm2kt-runtime`) & Android NDK (`arm64-v8a`, `x86_64`) / Pulley 対応** | Android アプリ (`build.gradle.kts`) から依存追加するだけで利用可能に |
