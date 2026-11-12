# 0018. Chicory の async ABI は仕様の定義を移植し、スレッドで継続を表す

* ステータス: 承認
* 日付: 2026-11-12

## 背景

Component Model の async（#16。task、subtask、waitable と waitable set、`task.return`、`stream` / `future` / `error-context`、callback 形式と stackful 形式、協調スレッドの組み込み関数）を、Chicory バックエンドで実装する（[ADR-0005](0005-async-and-wasi03-chicory-only.md)）。

制約は次のとおり。

* `runtime-chicory` は JVM 11 と Android の API 26 の範囲で動く（#80）。スタックの切り替え（継続）を直接扱う手段がない
* stackful 形式（callback のない async lift）、同期の lower で async 関数を呼ぶ呼び出し、`waitable-set.wait`、`thread.*` は、ゲストのスタックを途中で止めて、あとで再開する必要がある
* 正解は、仕様の参照定義（`design/mvp/canonical-abi/definitions.py`）と公式テストスイート（[ADR-0017](0017-component-model-conformance-suite.md)）。参照定義は、継続を「1 つずつしか動かない Python のスレッド」で表している
* 合成したコンポーネントの間の呼び出しは、wasmtime-environ 48 が生成する fused adapter が、Wasmtime 独自の組み込み関数（`prepare-call`、`async-start-call`、`sync-start-call`、`enter-sync-call` / `exit-sync-call`、`stream-transfer` など）で行う
* Wasmtime との差分テストでは確かめられない（ADR-0005）

調査の結果、wasmtime-environ 48 は async ABI の実行に必要な情報をすべて plan に出せる。すべての組み込み関数（`Trampoline` の各種）、async lift の `callback`（`GlobalInitializer::ExtractCallback`）、`context.get` / `context.set`（`UnsafeIntrinsic`）、stream / future / error-context のテーブルとその要素の型である。
また、wasm-tools 1.258 は公式テストの `async/` のうち 45 ファイルを変換でき（残り 2 つは仕様の `nyi.txt` に載っている）、そのすべてを wasmtime-environ 48 で分解できる。

## 検討した選択肢

1. **参照定義を移植し、継続を JVM のスレッドで表す（一度に 1 つだけ動かす）** — 参照定義と 1 対 1 に対応し、stackful 形式と協調スレッドも扱える / スレッドの受け渡しの費用がかかる。Chicory の Machine を JVM のスレッドごとに分ける必要がある
2. **callback 形式だけを実装する（スタックを止めない）** — スレッドが要らない / stackful 形式、同期の lower からの async の呼び出し、`thread.*` を扱えず、公式テストの多くが通らない
3. **ゲストのコードを CPS などに変換して止められるようにする** — スレッドが要らない / Chicory のコンパイラに手を入れることになり、作業量が大きすぎる

## 決定

選択肢 1。

### 実行のしくみ

* `Concurrency.kt` に、参照定義の `ComponentInstance`、`Thread`、`Task`、`Waitable`、`WaitableSet`、`Subtask`、`End`（stream / future の端）を移植する。`AsyncAbi.kt` に、`canon lift` / `canon lower`、組み込み関数、fused adapter の組み込み関数を置く
* 継続はデーモンスレッドのプールで動かす。継続の再開と中断は `Semaphore` で受け渡し、動いているスレッドは常に 1 つだけにする（メモリの可視性もこれで保つ）。ホスト resource の束縛（`HostResourceTable`、ADR-0008）は、再開するスレッドに引き継ぐ
* fused adapter が行う同期のゲスト間呼び出し（`enter-sync-call` / `exit-sync-call`）は、呼び出し元と同じスタックで動かす（Wasmtime と同じ）。呼び出し先がブロックすると、呼び出し元のスタックごと止まる
* Chicory の Machine は、オペランドスタックと呼び出しスタックを Machine ごとに持つ。async を使うコンポーネントでは、JVM のスレッドごとに Machine を作る（`ThreadLocal`）
* ハンドルの表は、参照定義と Wasmtime と同じく、子コンポーネントのインスタンスごとに 1 つにする（resource、waitable、waitable set、error context が同じ番号の空間を使う）。async を使わないコンポーネントも同じ表を使う
* async を使わないコンポーネント（plan に async の組み込み関数、async の option、callback、async の関数型がない）は、今までどおり呼び出し側のスレッドで直接動かす。wasmtime-environ の concurrency support も、async を使うコンポーネントのときだけ有効にする（decomposer が事前にバイナリを調べる）。async を使わないコンポーネントの plan とアダプターは変わらない（[ADR-0013](0013-multi-memory-adapters.md)）
* ホストからの呼び出しは task として実行し、結果が返るまでスケジューラーを回す。進められるスレッドがなくなったら、デッドロックとしてトラップする。ホストの引数の誤りは、今までどおりトラップにしない（ADR-0007）

### 参照定義との違い

* 参照定義がランダムに選ぶところは、決まった 1 つを選ぶ。待っているスレッドのうち、先に待ち始めたものを再開する。`wait_until` は、条件がすでに成り立っていればブロックしない（`yield` と callback の `YIELD` は必ず譲る）。`thread.new-indirect` は失敗しない。`error-context.new` は、ゲストのメッセージをそのまま持つ
* 同期の関数（`async` でない関数型）の task は、返る前にブロックできない（`cannot block a synchronous task before returning`。Wasmtime と公式テストの規則）。ただし、同じインスタンスに進められる別のスレッドがあれば、ブロックしてよい（公式テストの `during-sync-call-*`）。fused adapter の検査（`task-may-block` の global）は常に通し、実際にブロックする箇所で検査する
* `task.return` の検査は Wasmtime に合わせる。結果の型を比べ、`task.return` がメモリを指定したときだけ、lift のメモリと比べる

### ホストとの境界

* stream / future / error-context の値は、ホストとの境界（ADR-0015 のバイナリ形式）では整数のハンドルで受け渡す。`ComponentBackend` に `readStream` / `readFuture` / `dropReadable` / `newStream` / `newFuture` を加える（既定の実装は `UnsupportedOperationException`。Wasmtime バックエンドは対応しない）
* ホストが作る stream / future は、すべての値を持った書き込み側の端として渡す。コンポーネントが読み終えると、その端を drop する
* ゲストの間のコピーは、値をバイナリ形式にして運び、値に含まれるハンドル（own の resource、stream、future、error context）はインスタンスの表の間で移す。ホストが読み書きする stream / future の要素は、ハンドルを含まないものに限る
* 生成コードは ADR-0006 のとおり、export の `async func` を `suspend fun`、`future<T>` を `Deferred<T>`、`stream<T>` を `Flow<T>`（`stream<u8>` は `Flow<ByteArray>`）にする。ただし、呼び出しはコンポーネントが返るまでスレッドをブロックする（中断点にはならない）。stream / future は、export の引数と結果のトップレベルでだけ受け渡せる。ホスト import の `async func` は、同期の関数として実装する

## 結果

* async の正しさは、公式テストスイートの `async/` と、`async-stream` フィクスチャの E2E テストで確かめる。通らない項目は `conformance/skip.txt` に理由を付けて残す。残るのは、wasmtime-environ 48 が同じインスタンスや祖先のインスタンスへの再入をアダプターでトラップにする項目と、wasmparser 0.254 が受け付けてしまう検証の項目で、Wasmtime の LTS を上げると変わりうる（[ADR-0010](0010-wasmtime-lts.md)）
* async を使うコンポーネントでは、ホストからの呼び出しごとにスレッドの受け渡しが起きる。async を使わないコンポーネントの性能と動作は変わらない
* 終わらなかったゲストのスレッドは、インスタンスを閉じたとき、またはトラップで poison したときに割り込みで止める
* ビルド時にコンパイルしたモジュールでも、JVM のスレッドごとに Machine を作る。コンパイル済みのコードは JVM のスタックで動くので、ゲストのスレッドのスタックは、そのスレッドの大きさ（8 MiB を指定）に収まる必要がある
* 今後の課題: ホストの async な import（ホストの処理が終わる前に、ゲストに制御を返す）、コルーチンの中断点として呼び出すこと、入れ子の値の中の stream / future、WASI 0.3（#13）
