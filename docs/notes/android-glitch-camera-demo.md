# Android カメラグリッチデモの設計と機能改善計画

このドキュメントでは、Android のカメラ入力に MediaPipe と `png-glitch` を組み合わせてグリッチエフェクトをかけるデモアプリの設計をまとめます。あわせて、リアルタイム処理を実現するために `wasm-component-kotlin` と `png-glitch` の双方で必要となる機能改善を整理します。

## 1. デモアプリの全体構成

デモアプリは、カメラからのライブ映像に対して MediaPipe のセグメンテーションと Rust 製の `png-glitch` コンポーネントを適用し、プレビュー表示、動画の録画、静止画の保存を行います。

構成要素は以下の4つです。

* CameraX によるフレーム取得
* MediaPipe Tasks Vision の Image Segmenter
* `wasm-component-kotlin` の Chicory バックエンドで動作する `png-glitch` コンポーネント
* `MediaCodec` と `MediaMuxer` による録画および画像保存パイプライン

CameraX の `ImageAnalysis` から RGBA 形式のフレームを取得します。取得したフレームを MediaPipe の Image Segmenter に渡し、人物領域と背景領域を表すセグメンテーションマスクを生成します。

続いて、RGBA フレームとセグメンテーションマスクのバイト列を `wasm-component-kotlin` が生成したバインディング経由で `png-glitch` コンポーネントに渡します。コンポーネント内部でマスク領域に応じたスキャンラインのフィルタ操作とピクセル破損、元画像との合成を一括で行い、グリッチ適用後の RGBA フレームをホストへ返します。

ライブプレビューでは返された RGBA フレームを画面に描画します。動画の録画時は、カメラの生映像ではなくグリッチ適用後のフレームを `MediaCodec` のエンコーダ入力へ送り、`MediaMuxer` で MP4 ファイルとして書き出します。静止画の保存時は、プレビューと同じ RGBA フレームからの画像保存と、本来の PNG チャンク構造を直接破損させる `png-glitch` 本来の出力の2通りに対応します。

## 2. `png-glitch` 側に必要な機能改善

現行の `png-glitch` は PNG ファイルのバイト列を入力とし、IDAT チャンクの DEFLATE 展開と再圧縮を伴う設計になっています。カメラフレームのリアルタイム処理と Android の Chicory バックエンドでの実行に向けて、以下の4つの機能改善を行います。

* 生の RGBA ピクセル列に対するスキャンライン処理モード
* セグメンテーションマスク連動と領域合成
* フレーム間の状態を保持するリソース指向の API
* WASI に依存しない WebAssembly コンポーネント用クレート

1つ目の改善は、生の RGBA ピクセル列を直接扱うモードの追加です。毎フレーム PNG のエンコードとデコードを繰り返すと、DEFLATE 圧縮と INFLATE 展開の計算負荷がボトルネックになります。さらに、圧縮・展開ライブラリに含まれる大きな関数は Chicory の AOT コンパイラで JVM メソッドサイズの上限である 64 KB に近づきやすく、インタプリタ実行へフォールバックする要因にもなります。そのため、RGBA ピクセル列の各行に対して PNG の 5 種類のスキャンラインフィルタである `None`、`Sub`、`Up`、`Average`、`Paeth` を直接適用し、フィルタ種別の書き換えやバイト破損を加えたうえで直ちに逆フィルタ処理を行って RGBA ピクセル列へ戻す経路を追加します。これにより、PNG 特有の幾何学的なグリッチ表現を維持したまま DEFLATE 処理を省略できます。

2つ目の改善は、セグメンテーションマスクを用いたグリッチ制御と合成です。人物領域のみ、背景領域のみ、あるいは境界付近のみを対象としてスキャンラインのフィルタ変更やバイト破損を適用できるようにします。PNG の `Up` や `Average` や `Paeth` フィルタは前の行のデコード結果に依存し、`Sub` フィルタは左隣のピクセルに依存するため、1 箇所の破損が画面の下方向や右方向へ伝播します。このエラー伝播をマスク領域の内部だけに閉じ込めるか、マスクの外側まであふれさせるかをパラメータで切り替えられるようにし、最終的な元画像とのマスク合成までを Wasm 内の 1 パスで完了させます。

3つ目の改善は、フレーム間の状態と作業バッファを保持する設計への拡張です。WIT の `resource` としてエンジンを定義し、フレーム解像度に応じたスキャンライン作業用バッファや前フレームのピクセル状態をインスタンス内に保持します。これにより、毎フレームのメモリ確保を省くとともに、前フレームのグリッチ結果やフィルタ残差を次フレームへ持ち越す時間軸方向のグリッチ表現が可能になります。

4つ目の改善は、WASI 非依存のコンポーネント構成です。`wasm-component-kotlin` の Chicory バックエンドは現時点で WASI のホスト実装を同梱していません。そのため、`png-glitch` 本体のコア処理をファイルシステムや OS 乱数から切り離し、`wasm32-unknown-unknown` ターゲットと `wit-bindgen` でビルドできるコンポーネント用クレートを用意します。ランダムなフィルタ変更やバイト置換に使う乱数は、ホスト側から WIT 経由でシード値を渡す決定論的な擬似乱数生成器で実装します。

## 3. WIT インターフェースの設計案

上記の要件を満たす `png-glitch` コンポーネントの WIT 定義案を以下に示します。

```wit
package chikoski:png-glitch@0.1.0;

interface types {
    enum filter-mode {
        none,
        sub,
        up,
        average,
        paeth,
        random,
    }

    enum mask-target {
        all,
        foreground,
        background,
        edge,
    }

    record glitch-config {
        filter-override: option<filter-mode>,
        filter-change-rate: f32,
        byte-corrupt-rate: f32,
        byte-shift-amount: u8,
        target: mask-target,
        mask-threshold: u8,
        confine-propagation: bool,
        temporal-persistence: f32,
        seed: u64,
    }
}

interface engine {
    use types.{glitch-config};

    resource glitch-session {
        constructor(width: u32, height: u32);
        resize: func(width: u32, height: u32);
        process-rgba-frame: func(
            rgba: list<u8>,
            mask: list<u8>,
            config: glitch-config
        ) -> list<u8>;
        reset-state: func();
    }

    glitch-png-bytes: func(png-data: list<u8>, config: glitch-config) -> result<list<u8>, string>;
}

world png-glitch {
    export types;
    export engine;
}
```

`glitch-session` リソースはライブプレビューと動画録画のフレーム処理を担当します。`glitch-png-bytes` 関数は静止画保存時に本来の PNG バイナリを直接グリッチさせる用途で使用します。

## 4. `wasm-component-kotlin` 側に必要な機能改善

解像度 1280×720 の RGBA フレームは約 3.68 MB あり、同解像度の 1 チャンネルマスクは約 0.92 MB あります。これらを毎秒 15 から 30 フレームの頻度でやり取りする場合、現行の `wasm-component-kotlin` にはメモリアロケーションとコピーに関する課題があります。

改善項目は以下の4点です。

* セッション単位での `WitWriter` バッファの再利用
* `Lifter` における `list<u8>` 読み出し時の中間 `ByteArray` 排除
* ホスト側で出力用 `ByteArray` または `ByteBuffer` を再利用する API の導入
* 生成セッションの `invoke` における引数ボックス配列の削減

1つ目の課題は、呼び出しごとの `WitWriter` の新規生成とバッファ拡張です。現在、生成コードの `BackendWasmtimeSession.invoke` と `ChicoryComponentInstance.invoke` は、関数呼び出しのたびに初期容量 64 バイトの `WitWriter` を新規作成しています。約 4.6 MB の入力と約 3.68 MB の出力を書き込む際、内部の `ByteArray` が 64 バイトから倍々に再確保されるため、1 フレームあたり 20 MB を超える一時配列がヒープ上に割り当てられます。セッションへのアクセスは `@Synchronized` で直列化されているため、入力用と出力用の `WitWriter` をセッションインスタンスのフィールドとして保持し、呼び出しごとに `reset` メソッドで書き込み位置だけを 0 に戻すことで、2 フレーム目以降のバッファ再確保をなくすことができます。

2つ目の課題は、`runtime-chicory` の `Lifter.loadList` における二重コピーです。現在の実装では `cx.memory.readBytes` を呼んで線形メモリから新しい `ByteArray` を切り出し、その直後に `WitWriter.raw` へ再度コピーしています。これに対し、`WitWriter` に書き込み領域を予約して内部配列とオフセットを返す API、または Chicory の `Memory` から直接 `WitWriter` の内部バッファへ書き込む経路を追加することで、3.68 MB の中間配列の確保とコピーを 1 回分削減できます。

3つ目の課題は、デコード結果として毎回新しい `ByteArray` が生成される点です。`WitReader.bytes` は戻り値として毎回新しい `ByteArray` を確保します。Android のカメラ処理では、あらかじめ確保した 1 つのピクセルバッファを使い回して `Bitmap.copyPixelsFromBuffer` 等に渡すことで GC によるフレームドロップを防ぎます。そのため、`WitReader` に既存の `ByteArray` や `java.nio.ByteBuffer` へ直接読み出すメソッドを追加し、生成コード側でも出力先バッファを受け取れるオーバーロードや、戻り値のバッファを再利用できる仕組みを検討します。

4つ目の課題は、生成コードの `WasmtimeSession.invoke` に渡される `args: Array<out Any?>` です。この配列はテスト用の `InMemoryWasmtimeSession` でしか使われていませんが、`BackendWasmtimeSession` や `FfmWasmtimeSession` でも呼び出しのたびに `arrayOf` による配列生成と引数のボクシングが発生しています。インターフェースから不要な配列生成を取り除くか、`InMemoryWasmtimeSession` 専用の経路に分離することで、毎フレームの不要なオブジェクト生成を減らせます。

## 5. 実装の進め方

作業は以下の順序で段階的に進めます。

1. `wasm-component-kotlin` の `WitWriter` 再利用と `Lifter` の中間配列削減
2. `png-glitch` への生 RGBA スキャンラインモードと WIT コンポーネントクレートの追加
3. `wasm-component-kotlin` の出力バッファ再利用 API の設計と実装
4. `samples/` 配下での Android カメラグリッチデモアプリの実装と実機・エミュレーター計測

まずは既存の生成 API を変えずにランタイム内部で完結する `WitWriter` の再利用と `Lifter` の中間コピー削減から着手し、`benchmarks` モジュールで大きな `list<u8>` の往復性能とアロケーション量の変化を計測します。
