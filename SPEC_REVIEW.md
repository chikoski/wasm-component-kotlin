# `wasm2kt` 仕様書 (SPEC.md) レビュー結果と決定事項の整理

提供いただいた初期仕様書に対するレビュー項目について、これまでの議論で**決定・解決した事項（✅ 解決済み）**と、実装フェーズで順次詳細化する**残課題・留意点（📌 実装時検討）**に整理して更新しました。
決定事項はすべて最新の [SPEC.md](file:///Users/chikoski/wssm-component-kotin/SPEC.md) に反映済みです。

---

## 1. アーキテクチャと生成スコープ（✅ 全て解決・SPEC反映済み）

### 1.1 生成対象の役割とターゲットランタイム
* **ステータス**: ✅ **解決済み**
* **決定内容**:
  * アプリ組み込み用の **ホスト側（Host-side）バインディング** として生成する。
  * コンポーネントモデルへの対応状況と検証のしやすさから、ターゲットランタイムは **Wasmtime** を前提とする（JVM 21+ / Foreign Function & Memory API 等のブリッジを想定）。
  * 生成コードとランタイム実行層は `WasmtimeSession` インターフェース/クラスを介して接続し、Phase 1〜2 ではオフラインのコード生成とコンパイル検証、Phase 3 で Wasmtime 実機連携を行う。

### 1.2 World の表現方法（特異オブジェクト ＋ Context Parameters）
* **ステータス**: ✅ **解決済み**
* **決定内容**:
  * WIT の `world` ごとに **特異オブジェクト（`object <WorldName>`）** を定義する。
  * 実行時のインスタンス状態（Wasmtime の `Store` / `Instance`）は `<WorldName>.Session` (`AutoCloseable`) として表現し、`openSession` およびスコープ関数 `withSession { ... }` を提供する。
  * **エクスポート関数**: 特異オブジェクト `<WorldName>` の **拡張関数** として定義し、実行コンテキストを **Kotlin Context Parameters** (`context(session: <WorldName>.Session)`) で受け取る。
    ```kotlin
    context(session: CalculatorWorld.Session)
    fun CalculatorWorld.add(a: Int, b: Int): Int
    ```
  * **インポート関数/インターフェース**: ホスト側が実装して `openSession` / `withSession` に渡す `<WorldName>.Imports` インターフェースとして定義する。

---

## 2. 型マッピング (`Type Mapping`)（✅ 主要方針決定済み）

### 2.1 追加された WIT データ型 (`flags`, `tuple`, `type` エイリアス)
* **ステータス**: ✅ **解決済み**
* **決定内容**:
  * **`flags` (ビットフラグ)**: `@JvmInline value class <FlagsName>(val raw: UInt)` として生成し、各フラグの Boolean プロパティ（getter）とビット演算メソッド（`or`, `and`）、定数用 `companion object` を持たせる（32個超のフラグの場合は `ULong`）。
  * **`tuple<T1, T2, ...>` (タプル)**: 要素数に応じたジェネリックなデータ型 `data class TupleN<out T0, out T1, ...>(val f0: T0, val f1: T1, ...)` を自動生成して統一的にマッピングする。
  * **型エイリアス (`type foo = bar`, `use`)**: Kotlin の `typealias Foo = Bar` を出力する。

### 2.2 `option<T>` と `result<T, E>` のマッピング方針
* **ステータス**: ✅ **解決済み**
* **決定内容**:
  * `option<T>` と `result<T, E>` はともにカスタムの代数的データ型（`sealed interface`）で統一的に表現する。
  * **`option<T>`**: `sealed interface WitOption<out T>`（`Some<T>(val value: T)` / `None`）を採用。これにより `option<option<T>>` のネスト問題も完全に解消される。
  * **`result<T, E>`**: 任意の WIT エラー型 `E` を型安全に表現するため、`sealed interface WitResult<out T, out E>`（`Ok<T>(val value: T)` / `Err<E>(val error: E)`）を採用。ペイロード省略形（`result<_, E>`, `result<T>`, `result`）の省略箇所は `kotlin.Unit` にマッピングする。

### 2.3 プリミティブ表記の統一 (`f32` / `f64`)
* **ステータス**: ✅ **解決済み**
* **決定内容**:
  * [SPEC.md](file:///Users/chikoski/wssm-component-kotin/SPEC.md) の Section 5.2 にて WIT 標準の `f32` / `f64` 表記に統一済み。

### 2.4 エッジケースの扱い（📌 実装時の詳細ルール）
* **ステータス**: 📌 **基本方針設定済み（Phase 1〜3 で実装）**
* **内容**:
  * **`list<u8>` / `list<s8>`**: 基本は `List<T>` としつつ、パフォーマンス最適化として `ByteArray` / `UByteArray` へのマッピングも考慮する。
  * **`char`**: 基本は `kotlin.Char` にマッピングする（Canonical ABI 変換時にサロゲート領域外・BMP外の扱いに留意）。

---

## 3. リソース (`Resources`) と所有権（✅ 基本構造決定済み）

### 3.1 リソースクラスと `AutoCloseable`・Context Parameters の統合
* **ステータス**: ✅ **解決済み**
* **決定内容**:
  * WIT の `resource` は **特異オブジェクト `<WorldName>` 内部のクラス** として定義し、`java.lang.AutoCloseable` を実装する。
  * **インスタンス生成**: `companion object` の `operator fun invoke(...)` に `context(session: Session)` を付与することで、呼び出し側は `MyWorld.MyResource(...)` と自然に生成できる。
  * **セッションのキャプチャと `close()`**: 生成時に `context(session: Session)` から受け取った `Session` をリソース内部の `private val session: Session` に保持する。これにより、`override fun close()` やメンバメソッドは追加のコンテキストパラメータなしで標準の `.use { ... }` ブロックとそのまま連携できる。

### 3.2 `own<T>` と `borrow<T>` の扱い
* **ステータス**: 📌 **Phase 2〜3 で詳細化**
* **内容**:
  * Kotlin シグネチャ上は同じリソースクラス `T` として受け渡しつつ、`own<T>` をゲスト関数に渡した際はホスト側のハンドル所有権が移動（破棄扱い）されるよう、内部状態フラグ（`isClosed` / `isOwned`）で二重解放を防止する仕組みを Phase 2〜3 で組み込む。

---

## 4. 命名規則・パッケージ構造・CLI（✅ 解決済み）

1. **ケース変換ルール（✅ SPEC 5.6 に反映済み）**:
   * World / Interface / Record / Variant / Enum / Flags / Resource: `PascalCase`
   * 関数 / メソッド / フィールド / Flag プロパティ: `camelCase`
   * Enum エントリ / Flag 定数: `UPPER_SNAKE_CASE`
   * WIT ドキュメントコメント (`///`): KDoc として出力。
2. **CLI オプションの拡充（✅ SPEC 4 に反映済み）**:
   * 複数 World を持つ WIT パッケージに対応するため、`--world (-w)` オプションを追加。
   * `--from-json` 指定時は `<input-path>` を省略可能（省略時は `stdin` から入力）とする仕様を明記。
   * `kotlinx.serialization` で `ignoreUnknownKeys = true` を有効化し、`wasm-tools` のバージョン差異に対する耐性を確保する。
