# 0019. async の境界をコルーチンにし、ホストの async な import も suspend fun にする

* ステータス: 承認
* 日付: 2026-11-13

## 背景

[ADR-0018](0018-async-abi-on-chicory.md) で Chicory に async ABI を実装した。ただし、ホストとの境界は同期のままだった。

* 生成コードの `suspend fun` は、コンポーネントが返るまで呼び出し元のスレッドをブロックする（中断点にならない）
* ホストの async な import（`import f: async func`）は普通の関数で、ホストが待つ間、インスタンス全体が止まる
* ホストが渡す stream は、呼び出しの前に最後まで collect する。future は、呼び出しの前に await する
* 同じインスタンスへの呼び出しは、一度に 1 つ

Android では、ホストの I/O をノンブロッキングで書き、UI のスレッドを空けたまま async の関数を呼びたい。Component Model の task の取り消しは、コルーチンの取り消しと意味がほぼ対応する。

## 検討した選択肢

### ホストの async な import

1. **`suspend fun` にする** — 待つ間に他の task が進む。subtask の取り消しがコルーチンに伝わる / ブロックする API は `withContext(Dispatchers.IO)` で包む必要がある
2. **普通の関数のまま** — 簡単 / 待つ間はインスタンス全体が止まる。取り消しが伝わらない。後から変えるとホストの実装が壊れる

### コルーチンへの変換をどこに置くか

1. **runtime をコールバック方式にし、コルーチンへの変換は生成コードか別のモジュールに置く** — runtime-api が依存なしのまま。Java から使える / スコープ、取り消し、import の起動を、コールバックで作ってから橋渡しすることになる
2. **runtime-api が kotlinx-coroutines に依存し、`suspend` の API を公開する** — 構造化された並行性（スコープ、`Job`、`invokeOnCancellation`）をそのまま使え、実装が 1 か所にまとまる / async を使わないアプリにも依存が入る。`suspend` の API は Java から使いにくい

## 決定

ホストの async な import は選択肢 1（`suspend fun`）。変換の置き場所は選択肢 2（runtime-api が kotlinx-coroutines に依存する）。Java からの利用は考えない。同期の関数は、今までどおり Java からも呼べる。

### runtime-api

* `api(kotlinx-coroutines-core)` に依存する
* `ComponentBackend` に、`invokeSuspending`、`readStreamSuspending` / `readFutureSuspending`、`newStream(Flow<WitStreamChunk>)`、`newFuture(suspend () -> ByteArray)` を加える。既定の実装は、ブロックする版を呼ぶか `UnsupportedOperationException` を投げる（Wasmtime バックエンドはこのまま。ADR-0005）
* ホストの async な import は `AsyncHostFunction`（`suspend fun call`）で表す。`WitHostImportDispatcher.asyncHostFunction(key)`、`Linker.defineAsync(key, ...)` を加える。`defineAsync` は、ブロックする `HostFunction` も定義する（async ABI のないバックエンドが呼ぶ）
* `HostResourceTable` は、suspending な呼び出しを `bindSuspending` で束縛する。束縛はスレッドではなくコルーチンのコンテキストが持ち、呼び出しごとに貸し出し（lend）を持つので、複数の呼び出しが同時に進んでよい。メソッドは同期化する

### runtime-chicory

* async ABI の中核（`Concurrency.kt`。継続を JVM のスレッドで表す）は変えない。コルーチンを使うのは、ホストとの境界だけにする
* インスタンスごとにスケジューラーのスレッド（`SessionLoop`。`CoroutineDispatcher`）を持つ。async の状態は、すべてこのスレッドで操作する。ホストの import と stream のコルーチンもここで動く。スレッドは仕事がないと終わり、必要になったらまた作る（閉じ忘れたインスタンスも回収されるように）
* ホストからの呼び出しは、task を始めて中断し、`task.return` で再開する。コルーチンを取り消すと、`task.requestCancellation()` で task に取り消しを届ける。取り消した呼び出しも、task が終わるまでスケジューラーは動かし続ける
* スケジューラーは、ホストが何か（呼び出しの結果や stream の読み出し）を待っている間だけ task を進める（Wasmtime と同じ）。呼び出しが返ったあとも yield し続ける task は、次の呼び出しまで止まる。1 回進めるごとに、待ち行列の仕事（再開する呼び出しや、終わった import）に順番を譲る
* ゲストが async な import を呼ぶと、async で lower していればコルーチンを起動し、すぐ subtask（`STARTED`）を返す。同期で lower していれば、ゲストのスレッドが subtask の完了を待つ。ただし、ブロックできない task（async でない関数）から呼んだときは、そのスレッドでホストの関数を最後まで実行する。subtask の取り消しはコルーチンの取り消しになる
* ホストの import が例外を投げたら、トラップとしてインスタンスを poison し、進行中の呼び出しはすべてその例外で失敗する（Component Model には、subtask を失敗で終える手段がないため）
* ホストが渡す stream は、コンポーネントが前のチャンクを読み終えてから次のチャンクを collect する（背圧）。future は、コンポーネントが読むときに値を作る関数を呼ぶ
* デッドロックの判定は、ホストのコルーチンがまだ何かを作りうる間と、待ち行列に仕事がある間はしない
* 同期の `invoke` は、`invokeSuspending` を `runBlocking` で待つ。同期のホスト import からの呼び戻しは、今までどおり再入として poison する。suspending な import からは、suspending な呼び出しを使う

### 生成コード

* export の `async func` は `invokeSuspending` を呼ぶ `suspend fun`。import の `async func` は、ホストが実装する `suspend fun`（`defineAsync` で登録する）
* `stream<T>` の引数は `Flow` のまま backend に渡し、`future<T>` の引数は、読まれるときに `await()` する。どちらも呼び出しの前に collect / await しない
* 返された stream / future の読み出しは、中断する
* `WasmtimeSession` に `invokeSuspending` / `invokeMethodSuspending` / `withBackendSuspending` を加え、既定の実装はブロックする版を呼ぶ。Chicory の生成セッションは、suspending な呼び出しを同期化しない（並行して進める）

## 結果

* async の関数は、呼び出し元のスレッドを使わない。ゲストがホストを待つ間は、どのスレッドも止まらない。ゲストの wasm を実行している間は、スケジューラーのスレッドとゲストのスレッドが CPU を使う。取り消しは、ゲストが次にホストを待つか yield したときに届く（wasm の実行の途中では止まらない。#77）
* 同じインスタンスへの async の呼び出しは並行に進む。ゲストの CPU の部分は、仕様どおり一度に 1 つ
* runtime-api に kotlinx-coroutines への依存が入る（async を使わないアプリにも）
* ADR-0018 のうち、「呼び出しはブロックする」「ホストの async な import は同期の関数」「ホストが渡す stream / future は呼び出しの前に collect / await する」の部分を置き換える。残りは変わらない
* 確認: `SuspendingCallsTest`（runtime-chicory。async な import、並行の呼び出し、取り消し、`Flow` の stream、中断する読み出し）、`ChicoryGeneratedBindingsTest`（生成コードで同じこと）、`ConformanceTest`
