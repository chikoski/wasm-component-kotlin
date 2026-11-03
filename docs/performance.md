# Chicory バックエンドの性能とサイズ（#19）

> 計測日: 2026-11-02 / 対象: `main`（f003212）+ この計測用のコード / 再計測: [`.github/workflows/bench.yml`](../.github/workflows/bench.yml)
>
> #15（静的な lift / lower 生成）に着手するかどうかを決めるための計測です。結論と提案は [6. 結論](#6-結論と-15-への提案) にあります。

## 要点

* **呼び出しの固定費は小さい**。引数が整数だけの呼び出しは、JVM で 0.3 µs、ART（エミュレーター）で 3 µs。
  Wasmtime（FFM で 1.8 µs）より速い
* **データを渡すと JSON 境界が支配的になる**。`list<u8>` の往復は、JVM で 1 バイトあたり約 100 ns、ART で約 800 ns かかる
  （64 KiB で JVM 6.6 ms、ART 52 ms）。wasm 側の処理はほぼゼロで、JSON の木と、そこからの要素ごとの lift / lower が時間を使っている
* **計算は、小さな関数なら Wasmtime と同等**。整数ループは Wasmtime の 1.16 倍、JSON の解析は 3 倍。
  ただし大きな wasm 関数を含むコードは遅く、regex は Wasmtime の 53 倍だった。理由は 2 つある
  * HotSpot は 8000 バイトを超えるメソッドを JIT コンパイルしない
  * 64 KiB を超える関数はインタプリタで動く
* **初回のインスタンス化**は ART で 57 ms（小さなコンポーネント）〜 239 ms（1.2 MB のコンポーネント）。2 回目以降は 0.2〜10 ms
* **APK サイズ**は、ランタイム一式で約 180 KB 増える。1.2 MB のコンポーネントでは約 900 KB 増える（R8 適用後）。
  dex のメソッド数の上限にも、D8 / R8 の制限にも引っかからなかった

## 1. 計測の方法

| 項目 | 内容 |
| :--- | :--- |
| JVM | GitHub Actions の `ubuntu-latest`（AMD EPYC 9V74、2 vCPU）、Temurin 21.0.13。`./gradlew -Pwasm2kt.bench :benchmarks:bench :benchmarks:coldStart` |
| ART | 同じランナー上の Android 14（API 34、x86_64）エミュレーター（KVM、4 コア）。[Jetpack Microbenchmark](https://developer.android.com/topic/performance/benchmarking/microbenchmark-overview) 1.3.4、非 debuggable の release テスト APK。`samples/android/benchmark/run-on-device.sh` |
| コンポーネント | `arithmetic`（最小）、`types`（文字列・バイト列・record）、`heavy`（新規。Rust の std + regex + serde_json、1.2 MB。[`fixtures/src/heavy`](../fixtures/src/heavy)） |
| 定常状態 | JVM は自前の固定時間ハーネス（2 秒のウォームアップの後、0.7 秒 × 5 回の中央値）。ART は Microbenchmark の中央値 |
| 初回 | 新しいプロセスで `openSession` と最初の呼び出しにかかる時間を計り、9 回の中央値を取った（JVM は別の JVM を起動、ART は `am instrument` を 1 回ずつ実行） |

比べたバックエンドと層:

* `chicory-aot`: 出荷する経路（ビルド時にコンパイルした core モジュールと、生成した `<Name>Component`）
* `chicory-interp`: 同じ plan を Chicory のインタプリタで実行
* `wasmtime-ffm` / `wasmtime-jni`: 参照実装（ネイティブ）
* 層（Chicory AOT のみ）
  * `core`: core wasm の関数を直接呼ぶ（コンポーネント層なし）
  * `backend`: `ComponentBackend.invoke` に組み立て済みの JSON を渡す（Canonical ABI と JSON の値）
  * `dsl`: 生成された Kotlin の関数（実際にアプリが呼ぶもの）

注意:

* ART の数値はエミュレーターのもので、実機ではない。絶対値より、同じ環境での比較に使う
* GitHub のランナーは実行ごとに CPU が変わる。2 回の実行で、絶対値が最大 30% ほど違った。倍率はほぼ変わらない
* この開発環境（Xeon 2.8 GHz、2 vCPU）でも同じ傾向を確認した

## 2. 呼び出しのオーバーヘッド

### 2.1 引数が整数だけの呼び出し（`add(s32, s32) -> s32`）

| 層 | JVM | ART |
| :--- | ---: | ---: |
| core wasm の関数を直接（AOT） | 41 ns | 51 ns |
| `ComponentBackend.invoke`（AOT） | 213 ns | 2.9 µs |
| 生成 DSL（AOT） | **304 ns** | **3.3 µs** |
| 生成 DSL（インタプリタ） | 419 ns | 7.7 µs |
| Wasmtime FFM / JNI | 1.79 µs / 2.58 µs | — |

コンポーネント層（JSON の値の組み立て、Canonical ABI、インスタンスの状態の検査）で増える時間は、core の直接呼び出しに比べて
JVM で約 260 ns、ART で約 3.2 µs です。ART では core の呼び出しの約 64 倍になります。それでも 1 回 3 µs なので、UI のイベントごとに呼ぶ程度なら問題になりません。

### 2.2 データを渡す呼び出し（`echo-bytes: func(list<u8>) -> list<u8>` など）

| 呼び出し | JVM AOT | JVM Wasmtime FFM | ART AOT |
| :--- | ---: | ---: | ---: |
| `echo-name`（5 文字の文字列） | 2.2 µs | 2.6 µs | 154 µs ※ |
| `echo-bytes`（16 B） | 2.3 µs | 5.4 µs | 16 µs |
| `echo-bytes`（1 KiB） | 87 µs | 206 µs | 735 µs |
| `echo-bytes`（64 KiB） | **6.6 ms** | 16.5 ms | **52 ms** |
| `echo-blob`（record、1 KiB を含む） | 95 µs | 211 µs | 1.2 ms |

64 KiB の内訳（JVM AOT。部分ごとに別々に計測したので、足し合わせると合計を少し超える）:

| 部分 | 時間 |
| :--- | ---: |
| JSON の値への変換と逆変換（生成コードの部分。`FfmJsonCodec`） | 2.8 ms |
| `ComponentBackend.invoke`（JSON の木からの lower、lift して JSON の木へ、wasm の実行） | 4.7 ms |
| 合計（生成 DSL） | 6.6 ms |

* `list<u8>` は JSON の配列として要素ごとに扱われる。JVM で 1 バイトあたり約 100 ns、ART で約 800 ns かかる。
  メモリのコピーなら、64 KiB は数 µs で済む
* Wasmtime では、さらに JSON の木と文字列を相互に変換するので 2.5 倍遅い（64 KiB の変換だけで 7.0 ms）
* ※ ART の文字列が JVM の 70 倍遅いのは、`runtime-chicory` が文字列ごとに `CharsetEncoder` / `CharsetDecoder` を作っているためと考えられる。
  Android ではこれらが ICU の変換器を作るので重い。16 B の `list<u8>`（16 µs）との差が、ほぼこの分にあたる（未検証の推定。下の提案 3）

## 3. 計算の性能（`heavy`）

| 呼び出し | JVM AOT | JVM AOT（大きなメソッドも JIT） | JVM インタプリタ | Wasmtime FFM | ART AOT |
| :--- | ---: | ---: | ---: | ---: | ---: |
| `lcg-sum`（整数ループ 100 万回） | 593 µs | 611 µs | 99.7 ms | 513 µs | 5.6 ms |
| `json-roundtrip`（13 KiB） | 914 µs | 937 µs | 51.3 ms | 306 µs | 6.3 ms |
| `regex-count`（16 B。ほぼパターンのコンパイルだけ） | 1.2 ms | 1.7 ms | 5.8 ms | 20 µs | 2.0 ms |
| `regex-count`（14 KiB） | 3.7 ms | **1.1 ms** | 16.7 ms | 70 µs | 16.4 ms |
| `sort-bytes`（64 KiB。JSON が大半） | 13.4 ms | 14.1 ms | 193 ms | 13.9 ms | 98 ms |

* **小さな関数のループは Wasmtime と同等**（1.16 倍）。インタプリタより 168 倍速く、AOT コンパイルの効果は大きい
  （ART では約 63 倍。インタプリタは 10 万回で計測し、100 万回に換算して比較）
* **大きな関数を含むコードは遅い**。regex（14 KiB）は Wasmtime の 53 倍かかった。サンプリングで時間の内訳を見ると、次のとおり
  * 70%: コンパイル済みの関数 `func_303`（regex_automata の lazy DFA）。JVM バイトコードで 15 KB ある
  * 17%: インタプリタ。`heavy` の 1012 関数のうち 1 つ（`func_366`、wasm で 56 KB）が JVM のメソッドの上限（64 KB）を超え、
    インタプリタにフォールバックしている
* HotSpot は 8000 バイトを超えるメソッドを JIT コンパイルしない（`-XX:+DontCompileHugeMethods` が既定）。`heavy` では 13 個のメソッドがこれを超える。
  `-XX:-DontCompileHugeMethods` を付けると regex（14 KiB）は 3.3 倍速くなった（3.7 ms → 1.1 ms）。パターンのコンパイル（16 B）は
  インタプリタ側にあるので速くならない
* ART（エミュレーター）は JVM の 4〜9 倍遅い。ART の JIT が大きなメソッドを扱えているかは、この計測では確かめていない

## 4. 初回のインスタンス化

`openSession` は、ランタイムのクラスのロード、`.meta` と plan の解析、core のインスタンス化を含みます。

| | JVM AOT | JVM Wasmtime FFM | ART AOT |
| :--- | ---: | ---: | ---: |
| `arithmetic`: 最初の `openSession` | 205 ms | 244 ms | 57 ms |
| `arithmetic`: 最初の呼び出し | 13 ms | 97 ms | 1.0 ms |
| `arithmetic`: 2 回目の `openSession` | 0.2 ms | 2.2 ms | 0.2 ms |
| `heavy`: 最初の `openSession` | 373 ms | 1144 ms | 239 ms |
| `heavy`: 最初の呼び出し | 38 ms | 92 ms | 2.9 ms |
| `heavy`: 2 回目の `openSession` | 1.9 ms | 904 ms | 9.8 ms |

* JVM の 200 ms は、大半が Kotlin、kotlinx.serialization、Chicory のクラスのロード（JVM 起動直後の計測）
* `heavy` の差（ART で +180 ms）は、1.3 MB のマシンクラスのロードと検証、432 KB の `.meta` の解析。2 回目以降は 10 ms で済む
* Wasmtime は `openSession` のたびにコンポーネントをコンパイルする（`heavy` で 0.9 秒）。事前コンパイルした `.cwasm`（`openPrecompiledSession`）を使えば
  この分は消えるが、今回は計測していない
* ART での最初の呼び出しは、メインスレッドの外で行うのがよい（サンプルアプリはそうしている）

## 5. サイズ

### 5.1 ビルド時の出力

| コンポーネント | .wasm | 関数 | インタプリタに回った関数 | マシンクラス | `.meta` | ビルド時間 |
| :--- | ---: | ---: | ---: | ---: | ---: | ---: |
| `arithmetic` | 0.4 KB | 4 | 0 | 43 KB | 0.1 KB | — |
| `types` | 19 KB | 70 | 0 | 83 KB | 1.6 KB | 0.3 s |
| `heavy` | 1.19 MB | 1012 | 1（`func_366`） | 1.33 MB | 432 KB | 1.6 s |

`heavy` の JVM メソッドは 2203 個で、最大は 26 KB のバイトコードです（8000 バイト超が 13 個）。

### 5.2 APK（release、R8 適用後）

| アプリ | APK | 増加分 |
| :--- | ---: | ---: |
| コンポーネントなし（同じ Activity だけ） | 24.6 KB | — |
| `samples/android`（roundtrip + counter） | 205 KB | +181 KB |
| `heavy` だけ | 944 KB | +920 KB |

`samples/android` の dex（R8 後、333 KB）の内訳:

| パッケージ | dex |
| :--- | ---: |
| Chicory（runtime + wasm） | 147 KB |
| Kotlin 標準ライブラリ（一部はもともと必要） | 68 KB |
| kotlinx.serialization | 59 KB |
| wasm2kt のランタイム（runtime-api + runtime-chicory） | 44 KB |
| 生成コード（2 コンポーネント） | 11 KB |

`heavy` について:

* コンパイル済みのコードは dex で約 1.26 MB。R8 は 1 つのクラス（1929 メソッド）にまとめた
* ほかに `.meta` が 432 KB（APK 内で圧縮されて 155 KB）
* 増加分は .wasm の約 0.77 倍
* dex のメソッド数は 4546 で、上限の 65,536 から遠い。minSdk 26 ではネイティブの multidex が使えるので、上限を超えても動く
* D8 / R8 は 26 KB のメソッドを問題なく扱えた

## 6. 結論と #15 への提案

1. **#15 は、バイト列と文字列の高速経路から始めることを提案します**。データを渡す呼び出しでは、時間のほぼすべてが JSON 境界にかかっています。
   たとえば ART で 64 KiB を往復すると 52 ms で、wasm 側の処理はほぼゼロです。WASI 0.3（#13）の `stream<u8>` はまさにこの形なので、
   今のままでは実用になりません。
   * 範囲: 生成コードと `runtime-chicory` の間に、`ByteArray` と `String` をメモリへ直接コピーする型付きの経路を追加する。
     ほかの型は当面 JSON のままにする
   * 時期: #13 の stream より前、遅くとも同時
   * 値の形式を変えずに済む部分に限るので、ADR-0003 と差分テストは維持できる
   * 整数だけの呼び出しは今のままで十分速いので、静的な lift / lower のすべては急がなくてよい
   （#15 に追記済み）
2. **大きな wasm 関数への対策**（#57）
   * 短期: デスクトップの JVM で使う場合は `-XX:-DontCompileHugeMethods` を勧め、ドキュメントに書く。
     インタプリタに回った関数は、今も Gradle プラグインがビルド時に警告している。8000 バイトを超えるメソッドの数も、同じように知らせるとよい
   * 長期: Chicory 側（関数の分割）の課題として上流に伝える
3. **ART の文字列が遅い件**（#58）: `CharsetEncoder` / `CharsetDecoder` を毎回作るのをやめる（スレッドごとに使い回すか、UTF-8 を手で検証する）。小さな修正で済むはず
4. **初回のインスタンス化**: 大きなコンポーネントでは、`.meta` の解析とクラスのロードが目立つ。今は問題にならないが、
   実機で 200 ms を超えるようなら、遅延ロードを検討する

## 7. 再計測の方法

```bash
./gradlew -Pwasm2kt.bench :benchmarks:bench        # 定常状態（JVM）。-Pwasm2kt.bench.filter=<正規表現> で絞り込める
./gradlew -Pwasm2kt.bench :benchmarks:coldStart    # 初回（JVM）
./gradlew -Pwasm2kt.bench :benchmarks:profile -Pwasm2kt.bench.filter=regex   # heavy の呼び出しの時間の内訳
./gradlew -p samples/android :benchmark:assembleReleaseAndroidTest && samples/android/benchmark/run-on-device.sh   # ART（端末を接続して）
```

GitHub Actions の `Bench` ワークフロー（手動実行、または `perf/**` ブランチへの push）は、両方を実行し、結果をジョブのログと artifact に出力します。
`benchmarks` モジュールは `-Pwasm2kt.bench` を付けたときだけビルドに含まれるので、通常の `check` には影響しません。
