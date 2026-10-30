# `wasm2kt` コードレビュー結果 (`CODE_REVIEW.md`)

現在の `wasm2kt` コードベース全体（Kotlin AST・コード生成器・FFM/JNI ランタイムブリッジ・Rust Wasmtime ネイティブブリッジ・テストスイート）に対するコードレビュー結果をまとめたドキュメントです。

全体として、**Kotlin 2.2 Context Parameters を活用した DSL 設計、`kotlin-reflect` の完全排除と静的 JSON コーデック生成、FFM と JNI のデュアルバックエンド対応、AOT `.cwasm` プリコンパイル対応、さらに K2JVMCompiler を組み込んだ E2E テスト**まで、非常に完成度が高く堅牢な実装になっています。

以下、優れている点（Strengths）と、本番利用や Android・サーバー環境への組み込みを見据えた改善指摘事項を重要度別（**Critical / Major / Minor**）に整理します。

---

## ✅ 特に優れている点 (Strengths)

1. **リフレクション完全排除と R8 / ProGuard 耐性**
   * [`WitToKotlinPoetConverter.kt`](file:///Users/chikoski/wssm-component-kotin/src/main/kotlin/wasm2kt/generator/WitToKotlinPoetConverter.kt) において、各複合型に [`WitJsonSerializable`](file:///Users/chikoski/wssm-component-kotin/src/main/kotlin/wasm2kt/runtime/FfmWasmtimeBridge.kt#L35-L37) (`toWitJson()`) と `companion object` の `fromWitJson()`、および [`WitHostImportDispatcher`](file:///Users/chikoski/wssm-component-kotin/src/main/kotlin/wasm2kt/runtime/FfmWasmtimeBridge.kt#L43-L46) (`dispatchHostImport`) を静的にコード生成しており、実行時のリフレクションコストがゼロになっています。
2. **リソース所有権（`own<T>` vs `borrow<T>`）の安全なトラッキング**
   * [`buildResourceTypeSpec`](file:///Users/chikoski/wssm-component-kotin/src/main/kotlin/wasm2kt/generator/WitToKotlinPoetConverter.kt#L869-L1043) と [`formatArgumentCode`](file:///Users/chikoski/wssm-component-kotin/src/main/kotlin/wasm2kt/generator/WitToKotlinPoetConverter.kt#L1252-L1264) により、`own<T>` 渡し時に `consumeOwnership()` が呼ばれてホスト側で `isClosed = true` になり、Use-After-Move や二重解放（Double Free）がコンパイル時＋実行時チェックで防がれています。
3. **FFM / JNI / InMemory のクリーンな抽象化**
   * 生成される DSL コードが [`WasmtimeSession`](file:///Users/chikoski/wssm-component-kotin/src/main/kotlin/wasm2kt/generator/WitToKotlinPoetConverter.kt#L1640-L1755) インターフェースのみに依存しており、JVM 21+ (FFM)、Android ART (JNI)、単体テスト (`InMemoryWasmtimeSession`) を透過的に切り替えられる構造になっています。

---

## 🔴 Critical / High（バグ・クラッシュ・不整合のリスクがある項目）

### 1. ホストインポート（Guest → Host 呼び出し）の引数で複合型・リソースが正しくシリアライズされない
* **対象箇所**: [`rust-bridge/src/lib.rs` (`invoke_host_callback`)](file:///Users/chikoski/wssm-component-kotin/rust-bridge/src/lib.rs#L460-L480)
* **問題**:
  ゲストからホスト関数を呼び出す際、引数の `Val` を JSON に変換する処理がプリミティブ型しか対応しておらず、複合型（`Record`, `Variant`, `List`, `Option`, `Result`, `Flags`, `Tuple`, `Resource`）が `_ => json!(format!("{:?}", p))` で Rust の Debug 文字列として出力されてしまいます。
  ```rust
  // rust-bridge/src/lib.rs:477
  Val::String(s) => json!(s),
  _ => json!(format!("{:?}", p)), // RecordやListがDebug文字列になりKotlin側でデコード失敗する
  ```
  一方で、同ファイル内の [`val_to_json`](file:///Users/chikoski/wssm-component-kotin/rust-bridge/src/lib.rs#L286-L398) にはすべての型を正しく JSON 化する実装が既に存在します。
* **修正案**:
  [`register_host_imports`](file:///Users/chikoski/wssm-component-kotin/rust-bridge/src/lib.rs#L415-L438) で `result_types` と同様に `param_types: Vec<Type> = func_ty.params().map(|(_, ty)| ty).collect()` をクロージャにキャプチャし、`invoke_host_callback` 内で `val_to_json` を使って引数をシリアライズするように統一する。

### 2. JNI ホストコールバックでの JVM 例外発生時にエラー原因が消失する
* **対象箇所**: [`rust-bridge/src/lib.rs` (`invoke_host_callback`)](file:///Users/chikoski/wssm-component-kotin/rust-bridge/src/lib.rs#L524-L527) / [`JniWasmtimeBridge.kt` (`JniHostCallback`)](file:///Users/chikoski/wssm-component-kotin/src/main/kotlin/wasm2kt/runtime/JniWasmtimeBridge.kt#L16-L31)
* **問題**:
  FFM バックエンド（[`HostCallbackDispatcher`](file:///Users/chikoski/wssm-component-kotin/src/main/kotlin/wasm2kt/runtime/FfmWasmtimeBridge.kt#L345-L354)）では Kotlin 側の例外メッセージ（`t.message`）をバッファに書き込んで Rust 側へ伝播していますが、JNI バックエンドでは `env.exception_clear()` で例外を消去した後に固定文字列のみを返しているため、ホスト実装内で何が起きたのかデバッグが困難になります。
* **修正案**:
  `JniHostCallback.onHostCall` 側（または Rust JNI 側の `env.exception_occurred()`）で例外をキャッチしてエラーメッセージを構造化レスポンスとして返すか、`Throwable.toString()` を JNI 経由で取得してから `exception_clear()` する。

### 3. C ABI / JNI 境界における Rust `panic` の巻き戻し（Undefined Behavior）リスク
* **対象箇所**: [`rust-bridge/src/lib.rs`](file:///Users/chikoski/wssm-component-kotin/rust-bridge/src/lib.rs#L648-L1073) の全 `extern "C"` / `extern "system"` 関数
* **問題**:
  Rust から C ABI (`extern "C"`) や JNI (`extern "system"`) を越えて panic が伝播すると未定義動作（プロセス即死）になります。また、`wasm_path_ptr` や `func_spec_ptr` に対する null チェックなしに `CStr::from_ptr` を呼んでいる箇所（[L656](file:///Users/chikoski/wssm-component-kotin/rust-bridge/src/lib.rs#L656), [L848-L849](file:///Users/chikoski/wssm-component-kotin/rust-bridge/src/lib.rs#L848-L849)）があります。
* **修正案**:
  FFI エントリポイント全体を `std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| { ... }))` でラップし、ポインタの null チェックを徹底する。

---

## 🟠 Major（パフォーマンス・設計・エッジケースの課題）

### 4. セッションを開くたびに `wasmtime::Engine` の生成と JIT コンパイルが走る
* **対象箇所**: [`rust-bridge/src/lib.rs`](file:///Users/chikoski/wssm-component-kotin/rust-bridge/src/lib.rs#L661-L664), [L694-L697](file:///Users/chikoski/wssm-component-kotin/rust-bridge/src/lib.rs#L694-L697), [L939-L942](file:///Users/chikoski/wssm-component-kotin/rust-bridge/src/lib.rs#L939-L942), [L980-L983](file:///Users/chikoski/wssm-component-kotin/rust-bridge/src/lib.rs#L980-L983)
* **問題**:
  [`ANDROID_INTEGRATION_PLAN.md` (Section 5)](file:///Users/chikoski/wssm-component-kotin/ANDROID_INTEGRATION_PLAN.md#L98-L109) でも課題として挙げられている通り、現在 `openSession` / `withSession` を呼ぶたびに `Engine::new(&config)` と `Component::from_file` / `from_binary` を毎回実行しています。`Engine` の構築は高コストであり、また同一 `.wasm` の繰り返し実行でもコンパイル結果が共有されません。
* **修正案**:
  Rust 側でグローバルな `OnceLock<Engine>`（デフォルト設定の共有 Engine）を持つか、`Engine` インスタンスを再利用できるようにすることで、2回目以降のセッション起動を高速化する。

### 5. FFM ホストコールバックの出力バッファが 64KB 固定で、超過時に無言で JSON が切り詰められる
* **対象箇所**: [`rust-bridge/src/lib.rs`](file:///Users/chikoski/wssm-component-kotin/rust-bridge/src/lib.rs#L487) および [`HostCallbackDispatcher.onHostCall`](file:///Users/chikoski/wssm-component-kotin/src/main/kotlin/wasm2kt/runtime/FfmWasmtimeBridge.kt#L338-L340)
* **問題**:
  FFM のホストコールバックでは Rust 側が `vec![0u8; 65536]` (64KB) のバッファを渡し、Kotlin 側で `minOf(bytes.size, (outBufCap - 1).toInt())` で切り詰めています。ホスト関数が 64KB を超える文字列やリストを返した場合、途中で切れた不正な JSON となり、`serde_json::from_str` で難解なパースエラーになります。
* **修正案**:
  `bytes.size >= outBufCap` の場合は切り詰めるのではなく、Kotlin 側で明示的にバッファ不足エラー（ステータスコード `2` または例外）を返すか、動的アロケーション方式にする。

### 6. 複数のインターフェースに同名型がある場合の衝突 & 複合型内部の Resource デコード
* **対象箇所**:
  1. [`WitToKotlinPoetConverter.kt` (`emitTypeDefinitionIfNeeded`)](file:///Users/chikoski/wssm-component-kotin/src/main/kotlin/wasm2kt/generator/WitToKotlinPoetConverter.kt#L514-L568)
  2. [`WitToKotlinPoetConverter.kt` (`buildDecodeExpression`)](file:///Users/chikoski/wssm-component-kotin/src/main/kotlin/wasm2kt/generator/WitToKotlinPoetConverter.kt#L1183-L1187)
* **問題**:
  1. 異なる 2 つの WIT インターフェース（例: `iface-a` と `iface-b`）がどちらも `record config` を定義している場合、両方とも `<World>World.Config` として直下にフラット出力されるため、Kotlin のクラス名が衝突します。
  2. `WitResult<Counter, String>` や `WitOption<Counter>` のように、リソースハンドルが `result` や `option` や `record` の内部に含まれている場合、[`mapTypeDefByIndex`](file:///Users/chikoski/wssm-component-kotin/src/main/kotlin/wasm2kt/generator/WitToKotlinPoetConverter.kt#L1346-L1348) は Kotlin 型を `Counter` にマッピングしますが、[`buildDecodeExpression`](file:///Users/chikoski/wssm-component-kotin/src/main/kotlin/wasm2kt/generator/WitToKotlinPoetConverter.kt#L1183-L1187) は `FfmJsonCodec.decodeInt(elem)`（`Int`）を生成するため、生成コードが型不一致（`Int` vs `Counter`）でコンパイルエラーになります（トップレベル戻り値は [`returnsOwnedResource`](file:///Users/chikoski/wssm-component-kotin/src/main/kotlin/wasm2kt/generator/WitToKotlinPoetConverter.kt#L1097-L1102) で個別対応されていますが、ジェネリクス内部が未対応です）。
* **修正案**:
  `buildDecodeExpression` に `session` コンテキスト（またはラッパー）を渡せるようにし、`Handle` / `Resource` のデコード式が `Counter(session, FfmJsonCodec.decodeInt(elem))` を生成するようにすると、`result<own<resource>, error>` のような頻出パターンも完全に動作します。

---

## 🟡 Minor / Maintainability（保守性・改善提案）

1. **Unicode スカラー値（サロゲートペア / 絵文字）に対する `char` の扱い**
   * **対象箇所**: [`FfmJsonCodec.kt`](file:///Users/chikoski/wssm-component-kotin/src/main/kotlin/wasm2kt/runtime/FfmWasmtimeBridge.kt#L385) (`decodeChar`: `element.jsonPrimitive.content.first()`)
   * WIT の `char` は Unicode Scalar Value (21-bit `U+0000`〜`U+10FFFF`) ですが、Kotlin の `Char` は 16-bit UTF-16 コード単位です。BMP 外の文字（絵文字など）が渡された場合、`first()` は上位サロゲートのみを切り取ってしまいます。[SPEC_REVIEW.md (2.4)](file:///Users/chikoski/wssm-component-kotin/SPEC_REVIEW.md#L56) の留意事項通りですが、サロゲートペア検証を入れるか KDoc に明記しておくとより安全です。
2. **CLI での `wasm-tools` プロセス実行時の標準出力・エラー出力のデッドロック防止**
   * **対象箇所**: [`Main.kt` (`extractWitJsonViaWasmTools`)](file:///Users/chikoski/wssm-component-kotin/src/main/kotlin/wasm2kt/cli/Main.kt#L96-L98)
   * `stdout` を全て読み切ってから `stderr` を読んでいるため、万が一 `wasm-tools` が大量の警告を `stderr` に出して OS パイプバッファ（通常 16KB〜64KB）が詰まるとデッドロックする可能性があります。別スレッドまたは一時ファイル経由で読み取るとより安全です。
3. **未使用フィールドの整理**
   * [`WitToKotlinPoetConverter.kt`](file:///Users/chikoski/wssm-component-kotin/src/main/kotlin/wasm2kt/generator/WitToKotlinPoetConverter.kt#L82-L83) の `needsWitOption` / `needsWitResult` は `true` が代入されるだけで参照されておらず、実際には常に [`generateCommonSupportFile()`](file:///Users/chikoski/wssm-component-kotin/src/main/kotlin/wasm2kt/generator/WitToKotlinPoetConverter.kt#L1381-L1402) で `WitOption` と `WitResult` が出力されています。不要であれば変数を削除して問題ありません。
4. **テストコード内のハードコードされた `/Users/chikoski/.cargo/bin/wasm-tools` パス**
   * **対象箇所**: [`WitToKotlinPoetConverterTest.kt`](file:///Users/chikoski/wssm-component-kotin/src/test/kotlin/wasm2kt/WitToKotlinPoetConverterTest.kt#L288) ほか複数箇所
   * CI 環境や他の開発者のマシンでもそのままテストが通るよう、環境変数 `WASM_TOOLS_BIN` や `PATH` 検索（フォールバックとして `~/.cargo/bin/wasm-tools`）をヘルパー関数に切り出すことを推奨します。

---

## 📋 優先対応サマリー

| 優先度 | 項目 | 対象ファイル |
| :--- | :--- | :--- |
| **P0 (Critical)** | 1. ホストインポート引数の複合型・リソース JSON シリアライズ対応 (`val_to_json` の利用) | [`rust-bridge/src/lib.rs`](file:///Users/chikoski/wssm-component-kotin/rust-bridge/src/lib.rs) |
| **P0 (Critical)** | 2. JNI ホストコールバックでの例外メッセージ伝播 | [`rust-bridge/src/lib.rs`](file:///Users/chikoski/wssm-component-kotin/rust-bridge/src/lib.rs), [`JniWasmtimeBridge.kt`](file:///Users/chikoski/wssm-component-kotin/src/main/kotlin/wasm2kt/runtime/JniWasmtimeBridge.kt) |
| **P1 (High)** | 3. FFI / JNI 境界の `catch_unwind` と null ポインタ防御 | [`rust-bridge/src/lib.rs`](file:///Users/chikoski/wssm-component-kotin/rust-bridge/src/lib.rs) |
| **P1 (Major)** | 4. `wasmtime::Engine` の共有キャッシュ化によるセッション起動高速化 | [`rust-bridge/src/lib.rs`](file:///Users/chikoski/wssm-component-kotin/rust-bridge/src/lib.rs) |
| **P2 (Major)** | 5. FFM ホストコールバックの 64KB バッファ超過検出 | [`FfmWasmtimeBridge.kt`](file:///Users/chikoski/wssm-component-kotin/src/main/kotlin/wasm2kt/runtime/FfmWasmtimeBridge.kt), [`rust-bridge/src/lib.rs`](file:///Users/chikoski/wssm-component-kotin/rust-bridge/src/lib.rs) |
| **P2 (Major)** | 6. ジェネリクス内部（`result<own<resource>, E>` 等）の Resource デコード対応 | [`WitToKotlinPoetConverter.kt`](file:///Users/chikoski/wssm-component-kotin/src/main/kotlin/wasm2kt/generator/WitToKotlinPoetConverter.kt) |
| **P3 (Minor)** | 7. テスト内の `wasm-tools` パス解決・未使用フィールド整理 | [`WitToKotlinPoetConverterTest.kt`](file:///Users/chikoski/wssm-component-kotin/src/test/kotlin/wasm2kt/WitToKotlinPoetConverterTest.kt), [`WitToKotlinPoetConverter.kt`](file:///Users/chikoski/wssm-component-kotin/src/main/kotlin/wasm2kt/generator/WitToKotlinPoetConverter.kt) |
