# `wasm2kt` (`wasm-component-kotlin`) 追加テスト提案書

WebAssembly Component Model の **WIT (Wasm Interface Type) 仕様** および本リポジトリの設計仕様 ([SPEC.md](file:///Users/chikoski/wssm-component-kotin/SPEC.md)) に照らし合わせ、現在のテストスイート ([WitToKotlinPoetConverterTest.kt](file:///Users/chikoski/wssm-component-kotin/integration-tests/src/test/kotlin/wasm2kt/WitToKotlinPoetConverterTest.kt)) でカバーしきれていない機能・境界値・エッジケースを整理しました。

実装の調査を通じて判明した **潜在的な不具合や拡張ポイント** もあわせて記載しています。

---

## 1. 現在のテストカバレッジの整理

現在 [WitToKotlinPoetConverterTest.kt](file:///Users/chikoski/wssm-component-kotin/integration-tests/src/test/kotlin/wasm2kt/WitToKotlinPoetConverterTest.kt) で検証済みの項目は以下の通りです：

| カテゴリ | 検証済みの内容 | テスト方式 |
| :--- | :--- | :--- |
| **基本算術 (`s32`)** | トップレベル関数 (`add`, `sub`, `mul`, `div`) のエクスポートと呼び出し | Wasmtime FFM 実機 |
| **ホストインポート (基本)** | トップレベル関数 (`record-calc(s32, s32)`, `log(string)`) の呼び出し | Wasmtime FFM 実機 / InMemory |
| **リソース (`resource`)** | `counter` のコンストラクタ、メソッド (`increment`, `add`, `get-value`)、静的関数 (`merge(borrow, own)`)、`AutoCloseable` (`.use`) | Wasmtime FFM 実機 / InMemory |
| **複合型 (基本)** | `record point`、`variant status`、`enum color`、`flags permissions` (3フラグ)、`option<string>`、`result<status, string>`、`list<u8>`、`tuple<u32, string>`、`type user-id = u64` | コード生成・コンパイル / InMemory |

---

## 2. 追加すべきテストケースの提案（優先度順）

### 提案 1: 複合データ型・代数的データ型の Wasmtime FFM 実機ラウンドトリップテスト【優先度：高】
* **背景・目的**:
  現在、`record`、`variant`、`enum`、`flags`、`tuple`、`option`、`result`、`list` は `InMemoryWasmtimeSession`（Kotlinメモリ内のモック）でのみ実行テストされており、[FfmJsonCodec.kt](file:///Users/chikoski/wssm-component-kotin/runtime-wasmtime/src/main/kotlin/wasm2kt/runtime/FfmWasmtimeBridge.kt#L285-L560) と Rust ブリッジ ([lib.rs](file:///Users/chikoski/wssm-component-kotin/rust-bridge/src/lib.rs#L67-L384)) を介した **実機 Wasm コンポーネントとの Canonical ABI 変換テスト** がまだありません。
* **テストすべき WIT 定義例**:
  ```wit
  package example:types-roundtrip@0.1.0;

  world types-roundtrip {
      record user-profile {
          id: u64,
          user-name: string,
          roles: permissions,
      }
      flags permissions { read, write, execute }
      enum priority { low, medium, high }
      variant filter-rule {
          all,
          by-priority(priority),
          by-tag(string),
      }
      export echo-profile: func(p: user-profile) -> user-profile;
      export apply-filter: func(rule: filter-rule) -> result<tuple<u32, string>, string>;
  }
  ```
* **検証ポイント**:
  * `kebab-case` フィールド名（例: `user-name`）と Kotlin プロパティ名（`userName`）が [FfmJsonCodec](file:///Users/chikoski/wssm-component-kotin/runtime-wasmtime/src/main/kotlin/wasm2kt/runtime/FfmWasmtimeBridge.kt#L380-L388) ⇔ Rust `Val::Record` 間で正しく相互変換されるか。
  * `@JvmInline value class` (`Permissions`) がリフレクション経由でボックス化・アンボックス化される際に、フィールドやプリミティブ引数として正しくシリアライズ／デシリアライズされるか。

---

### 提案 2: `result` 型のペイロード省略パターンとネストされた `option<option<T>>`【優先度：高】
* **背景・目的**:
  WIT 仕様では `result` 型は以下の4通りの記述がすべて合法です：
  1. `result<T, E>`（成功・失敗ともに値あり）
  2. `result<_, E>`（成功時は値なし＝`Unit`、失敗時は `E`）
  3. `result<T>`（成功時は `T`、失敗時は値なし＝`Unit`）
  4. `result`（成功・失敗ともに値なし＝`WitResult<Unit, Unit>`）
  また、[SPEC.md Section 5.3](file:///Users/chikoski/wssm-component-kotin/SPEC.md#L191-L198) で `WitOption` を導入した最大の理由は **`option<option<T>>` のネスト表現**（`Some(None)` と `None` の区別）です。
* **テストすべき WIT 定義例**:
  ```wit
  world option-result-edge {
      export ping: func(succeed: bool) -> result;
      export validate: func(input: string) -> result<_, string>;
      export try-parse: func(input: string) -> result<s32>;
      export nested-opt: func(v: option<option<s32>>) -> option<option<s32>>;
  }
  ```
* **検証ポイント**:
  * `WitResult<Unit, Unit>`、`WitResult<Unit, String>`、`WitResult<Int, Unit>` が正しく生成され、`Ok(Unit)` や `Err(Unit)` が JSON (`{"result":"ok","value":null}`) を経由して復元されるか。
  * `WitOption.Some(WitOption.None)` と `WitOption.None` が混同されずに往復できるか。

---

### 提案 3: プリミティブ全13種の境界値テスト（特に符号なし整数 `u8`〜`u64` と `char`）【優先度：高】
* **背景・目的**:
  WIT の全プリミティブ型（`bool`, `s8`, `u8`, `s16`, `u16`, `s32`, `u32`, `s64`, `u64`, `f32`, `f64`, `char`, `string`）のうち、Kotlin の符号なし整数（`UByte`, `UShort`, `UInt`, `ULong`）は `@JvmInline value class` として実装されています。
* **テストすべき観点**:
  * **最大値・最小値の境界値**:
    * `u8`: `255u` (`UByte.MAX_VALUE`)
    * `u16`: `65535u` (`UShort.MAX_VALUE`)
    * `u32`: `4294967295u` (`UInt.MAX_VALUE` — 符号付き `Int` では負になる値)
    * `u64`: `18446744073709551615uL` (`ULong.MAX_VALUE` — 符号付き `Long` や JSON 数値精度を超える値)
    * `s64`: `Long.MIN_VALUE` / `Long.MAX_VALUE`
  * **`char` と Unicode スカラー値**:
    * ASCII文字（`'A'`）、日本語文字（`'あ'`）、サロゲートペア領域（絵文字 `'🦀'` などの非BMP文字を扱った際の挙動確認）。
  * ⚠️ **コード調査で判明した注意点**:
    [FfmJsonCodec.kt L408](file:///Users/chikoski/wssm-component-kotin/runtime-wasmtime/src/main/kotlin/wasm2kt/runtime/FfmWasmtimeBridge.kt#L401-L409) で `UByte`, `UShort`, `UInt`, `ULong` を `ctor.call(...)` で生成する際や、関数の直接の戻り値として `as R` でキャストする際、JVM リフレクションのボクシング挙動が正しく機能するかを実機テストで保証する必要があります。

---

### 提案 4: リソース (`resource`) の高度なエラーハンドリングとメソッドからのリソース返却【優先度：高】
* **背景・目的**:
  現在の `counter` テストでは正常系（`constructor` + インスタンスメソッド + `static merge` + `.use`）を検証していますが、以下の WIT リソース仕様および安全性チェックのテストが未実装です。
* **追加すべき検証項目**:
  1. **Use-After-Close / Use-After-Move の例外検証**:
     * `c.close()` 後に `c.increment()` を呼ぶと `IllegalStateException` (`Resource Counter(handle=...) is already closed or moved`) がスローされること。
     * `own<counter>` 引数として別関数（`merge(c1, c2)`）に渡して所有権を消費した後の `c2` に対してメソッドを呼ぶと `IllegalStateException` がスローされること。
  2. **インスタンスメソッドから新しいリソース (`own<T>`) を返すパターン**:
     * 例: `clone-counter: func() -> counter;`
  3. **トップレベル関数やインターフェース関数がリソースを受け取る・返すパターン**:
     * 例: `export create-default-counter: func() -> counter;`
     * ⚠️ **コード調査で判明した潜在バグ**:
       [WitToKotlinPoetConverter.kt L874-L884 (`returnsOwnedResource`)](file:///Users/chikoski/wssm-component-kotin/generator/src/main/kotlin/wasm2kt/generator/WitToKotlinPoetConverter.kt#L874-L884) では、戻り値がリソースであっても、それが `result<counter, string>` や `option<counter>` の内部にラップされている場合、`session` インスタンスを `FfmJsonCodec` に渡せないためデシリアライズに失敗します。また、コンストラクタが複数のリソースが存在するインターフェースにある場合、`newResource("counter", ...)` がインターフェース空間内で解決できるかどうかのテストも有効です。

---

### 提案 5: インターフェース単位のインポート／エクスポートと戻り値のあるホストインポート【優先度：中】
* **背景・目的**:
  現在のテストでは、トップレベルの `import log: func(msg: string)`（戻り値なし）と `export counter-api`（リソースのみ）をテストしていますが、以下のケースが未検証です：
  1. **名前付きインターフェースのエクスポート（フリースタンディング関数を含む場合）**:
     ```wit
     interface math {
         add: func(a: s32, b: s32) -> s32;
     }
     world app {
         export math;
     }
     ```
     生成される `with(AppWorld) { AppWorld.Math.add(1, 2) }` が `math#add` として Wasmtime 側で正しく解決・実行されるかのテスト。
  2. **名前付きインターフェースのインポートと戻り値を持つホストコールバック**:
     ```wit
     interface env-provider {
         get-config: func(key: string) -> string;
         get-limit: func() -> s32;
     }
     world app {
         import env-provider;
         export run: func() -> string;
     }
     ```
     ホスト側の `Imports.envProvider.getConfig(key)` が Wasm ゲストから呼ばれ、ホストが返した文字列や数値が Wasm ゲスト側に正しく渡るかの双方向テスト。
  3. **複合型を引数・戻り値に持つホストインポート**:
     * ⚠️ **コード調査で判明した制限事項**:
       [rust-bridge/src/lib.rs L444-L502 (`invoke_host_callback`)](file:///Users/chikoski/wssm-component-kotin/rust-bridge/src/lib.rs#L444-L502) では、現在プリミティブ型と文字列のみをホストコールバックで変換しており、`Record` や `Variant`、`UByte`〜`ULong` などをホストインポート関数で受け渡しする場合のテストと拡張が必要です。

---

### 提案 6: 32個を超える `flags`（`ULong` バッキングへの切り替え）【優先度：中】
* **背景・目的**:
  WIT 仕様では `flags` のビット数は任意であり、[SPEC.md Section 5.3](file:///Users/chikoski/wssm-component-kotin/SPEC.md#L160-L162) および [WitToKotlinPoetConverter.kt L520](file:///Users/chikoski/wssm-component-kotin/generator/src/main/kotlin/wasm2kt/generator/WitToKotlinPoetConverter.kt#L520) では **32個以下なら `UInt`、33個以上なら `ULong`** をバックエンド型として使用する実装になっています。
* **テスト内容**:
  * 33個〜64個のフラグを持つ `flags large-flags { f0, f1, ..., f32, f33 }` を定義し、`raw: ULong`、`0uL`、`1uL shl 32` 等が生成されてコンパイルおよびビット演算（`or`, `and`）が正しく動作することを検証します。

---

### 提案 7: WIT の識別子エスケープ (`%keyword`) と Kotlin 予約語の衝突テスト【優先度：中】
* **背景・目的**:
  WIT では `%type`、`%class`、`%return` のように `%` プレフィックスで WIT 予約語を識別子として使えます。また、WIT では予約語でなくても Kotlin 側で予約語となる識別子（`val`, `var`, `fun`, `object`, `in`, `is`, `when`, `interface`, `package`）が関数名・引数名・レコードフィールド名に使われることがあります。
* **テストすべき WIT 定義例**:
  ```wit
  record %object {
      %type: string,
      val: s32,
      in: bool,
  }
  export %return: func(fun: string, is: bool) -> %object;
  ```
* **検証ポイント**:
  * [NamingConventions.kt](file:///Users/chikoski/wssm-component-kotin/generator/src/main/kotlin/wasm2kt/generator/NamingConventions.kt) が先頭の `%` を適切に除去するか（⚠️ 現在 `splitWords` は `-`, `_`, `.`, `/` のみ分割しており、`%` が残る可能性があるためテスト追加と修正が推奨されます）。
  * KotlinPoet が生成コード側で `` `val` ``, `` `in` ``, `` `fun` ``, `` `return` `` のようにバッククォートエスケープを行い、`kotlinc` でエラーなくコンパイルできるか。

---

### 提案 8: 複数ワールド (`--world`)・ワールドなしインターフェース単体・標準入力 (`--from-json`) の CLI テスト【優先度：低〜中】
* **背景・目的**:
  [Main.kt](file:///Users/chikoski/wssm-component-kotin/cli/src/main/kotlin/wasm2kt/cli/Main.kt) および [WitToKotlinPoetConverter.kt L87-L95](file:///Users/chikoski/wssm-component-kotin/generator/src/main/kotlin/wasm2kt/generator/WitToKotlinPoetConverter.kt#L87-L95) に実装されている以下の CLI 機能がテストされていません：
  1. 1つの `.wit` ファイル内に複数の `world`（例: `world client` と `world server`）がある場合に、`--world client` 指定時は `ClientWorld.kt` のみが生成され、未指定時は両方が生成されること。
  2. `world` を含まず `interface` のみが定義された `.wit` ファイルを入力した際、`generateStandaloneInterfacesFile()` により `DefaultWorld.kt` が合成されて出力されること。
  3. `--from-json` オプションで `<input-path>` を省略した際、`System.in`（標準入力パイプ）から JSON AST を読み取れること。

---

## 3. まとめ：推奨されるアクションプラン

1. **Step 1（単体・コード生成テストの拡充）**:
   * **提案 2**（`result` の省略形4種、`option<option<T>>`）、**提案 6**（33個以上の `flags`）、**提案 7**（予約語・`%`識別子）、**提案 8**（CLI `--world` / インターフェース単体）のユニットテストを追加。
2. **Step 2（Wasmtime FFM 実機 E2E テストの拡充）**:
   * **提案 1**（複合型の実機ラウンドトリップ）、**提案 3**（全プリミティブ・符号なし整数の境界値）、**提案 4**（リソースの Use-After-Close / Move 例外検証）、**提案 5**（名前付きインターフェースのインポート／エクスポート）の Wasm コンポーネントを作成し、実機テストを追加。
