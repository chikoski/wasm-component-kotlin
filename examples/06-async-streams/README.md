# Example 06: 非同期処理とストリームのパイプライン実行

WIT の `async func`、`stream`、`future` を組み合わせたコンポーネントを Pure JVM 上の Chicory で実行するサンプルです。ホスト側で中断可能な関数を提供し、双方向のストリームと結果の future を Kotlin Coroutines として扱います。

## 対象となる WIT 定義

[async-pipeline.wit](../../fixtures/async-pipeline.wit) では、ホストが実装する `enricher` インターフェースと、3 つの非同期関数を定義しています。`produce-numbers` は数値列のストリームを返します。`process-items` は文字列ストリームを受け取り、ホスト側の非同期関数を呼び出しながら変換後ストリームと処理件数の future を返します。`sum-stream` は数値ストリームを受け取って総和を返します。

```wit
package example:async-pipeline@0.1.0;

interface enricher {
    format-item: async func(id: u32, payload: string) -> string;
}

world async-pipeline {
    import enricher;

    export produce-numbers: async func(start: u32, count: u32) -> stream<u32>;
    export process-items: async func(items: stream<string>) -> tuple<stream<string>, future<u32>>;
    export sum-stream: async func(values: stream<u32>) -> u32;
}
```

## Gradle の設定

[build.gradle.kts](build.gradle.kts) では `io.github.wasm2kt` プラグインを適用し、`async-pipeline` コンポーネントを登録しています。プラグインが `wasm2ktCompileAsyncPipeline` タスクとバインディング生成タスクを自動構成します。生成された `AsyncPipelineComponent` とサポートコードは `runtime-chicory` 上で動作するため、ネイティブライブラリは不要です。

このプロジェクトに含まれる主要な機能は次のとおりです。

- ホストの `suspend fun` インポート実装
- `Flow` による双方向ストリームの送受信
- `Deferred` による `future` 値の非同期待機
- `coroutineScope` を用いたストリームと future の並行消費

ホストの実装では、`Enricher.formatItem` が `suspend fun` として生成されます。サンプル内の [Main.kt](Main.kt) では `delay` を挟んで非同期処理を行い、結果を返却しています。

コンポーネントが返す `stream` は `kotlinx.coroutines.flow.Flow` に、`future` は `kotlinx.coroutines.Deferred` に写像されます。`processItems` が返す `Pair` のうち、`Flow` をすべて収集するとゲスト側の書き込みが完了し、対となる `Deferred` も解決します。両者を `coroutineScope` 内の別々のコルーチンで並行に消費することで、デッドロックを避けて完了を待機できます。

## 実行手順

ビルドを行う前に、`fixtures/async-pipeline.wasm` をサンプルディレクトリ直下へ `async-pipeline.wasm` としてコピーしてください。

```bash
cp ../../fixtures/async-pipeline.wasm .
./gradlew run
```

実行に成功すると、標準出力に次の結果が表示されます。

```text
numbers = [10, 11, 12, 13], doubledSum = 92, count = 3, formatted = [#1:ALPHA, #2:BETA, #3:GAMMA]
```
