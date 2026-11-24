# Core Wasm 2.0 公式テストスイート

`spec/` は、WebAssembly 仕様リポジトリの [WebAssembly/spec](https://github.com/WebAssembly/spec) から `test/core/` 直下の `.wast` ファイル群を取り込んだものです。`simd/` サブディレクトリのテストは対象外としています。取り込み元のコミットは `spec/COMMIT` に記録しており、ライセンスは `spec/LICENSE` に従います。

静的 JVM バックエンドの設計方針については [ADR-0023](../../docs/adr/0023-custom-wasm-compiler-and-static-jvm-backend.md) を参照してください。このスイートは、自作の型付き IR 変換および ASM バイトコード生成系が Core Wasm 2.0 の演算、制御フロー、トラップ、リンク規則に適合しているかを検証します。

## 実行手順

テストは `:integration-tests` サブプロジェクトの `CoreWasmConformanceTest` として実行します。実行には `wasm-tools` が必要です。

```bash
./gradlew :integration-tests:test --tests 'wasm2kt.conformance.CoreWasmConformanceTest'
```

## 検証内容

`CoreWasmConformanceTest` は各 `.wast` ファイルを `wasm-tools json-from-wast` で JSON と `.wasm` バイナリへ変換して処理します。各コアモジュールは `ChicoryWasmAdapter` と `AotCoreCompiler` で JVM バイトコードへコンパイルし、`AotCoreCompiler.verifyAll` で検証したうえでメモリ上にロードして実行します。

主な検証対象は次のとおりです。

* 関数の呼び出しと戻り値検証
* 整数と浮動小数点のトラップ検証
* 非正規化 NaN と正規化 NaN の判定
* `spectest` および名前登録モジュールの動的リンク
* リンカ異常とインスタンス化失敗の検証
* 無効および不正形式モジュールの拒否
* スタック枯渇の検出
* スキップ設定の陳腐化検査

バイナリ形式の `assert_malformed` と `assert_invalid` は `ChicoryWasmAdapter` が構文エラーや型・検証エラーを検出して拒否することを検証します。テキスト形式の `assert_malformed` は WAT テキスト構文の検査でありバイナリコンパイラの検証範囲外となるため、`TestAbortedException` によりスキップ対象として記録します。`assert_trap` では `ComponentTrapException` の送出に加えて想定トラップ種別との一致も検査します。コマンドあたりの実行時間は 30 秒で制限し、無限ループによる全体の停止を防いでいます。

## スキップ管理

実行環境の制約や仕様上の拡張方針により失敗するコマンドは `skip.txt` に記載します。`<ファイル名>` だけの指定はそのファイル全体をスキップし、`<ファイル名>:<行番号>` の指定はその行のコマンドだけをスキップします。現在は [ADR-0013](../../docs/adr/0013-multi-memory-adapters.md) および [ADR-0023](../../docs/adr/0023-custom-wasm-compiler-and-static-jvm-backend.md) に基づく複数メモリ対応に反する multi-memory 導入前の Core Wasm 2.0 否定アサーション 13 件のみを記載しています。

`skip.txt` に残された項目がコンパイラやランタイムの修正によって通過するようになった場合、テストは陳腐化エラーとして失敗します。対象コマンドが通るようになった時点で該当行を `skip.txt` から削除してください。

## テストの更新

仕様の新しいコミットを取り込むには `update.sh` を実行します。コミットハッシュを省略した場合は既定の `wg-2.0` コミットを使用します。

```bash
./conformance/core-wasm/update.sh
```

更新後は `CoreWasmConformanceTest` を実行し、新しく追加されたテストや `skip.txt` の整合性を確認します。
