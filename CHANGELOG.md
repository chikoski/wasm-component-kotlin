# Changelog

このファイルには、`wasm2kt` の破壊的変更（`!` 付きコミット・PR）と主要な機能変更を記録します。

## Unreleased (`main`)

### 破壊的変更 (Breaking Changes)

* **Component Model の async に対応し、`plan.json` を v5 へ更新** ([#16](https://github.com/chikoski/wasm-component-kotlin/issues/16), [ADR-0018](docs/adr/0018-async-abi-on-chicory.md))
  * Chicory バックエンドで、async lift（callback 形式と stackful 形式）、`task.return`、waitable set、subtask、`stream` / `future` / `error-context`、協調スレッドの組み込み関数、合成コンポーネントの async の呼び出しを実行できるようにしました
  * 生成コードでは、export の `async func` を `suspend fun`、`future<T>` を `Deferred<T>`、`stream<T>` を `Flow<T>`（`stream<u8>` は `Flow<ByteArray>`）にしました（ADR-0006）。これらを使う world の生成コードは `kotlinx-coroutines-core` に依存します
  * 子コンポーネントの間で resource を受け渡す合成コンポーネントも動くようになりました（#70 の一部）
  * 古い `plan.json`（v4 以前）は読み込めません。再分解が必要です。`ComponentBackend` には stream / future のメソッドが加わりました（既定の実装は `UnsupportedOperationException`）
* **async の境界をコルーチンに** ([ADR-0019](docs/adr/0019-suspending-async-boundary.md))
  * export の `async func` は、呼び出し元のスレッドをブロックせずに中断する `suspend fun` になりました。同じセッションへの呼び出しを並行に進められ、コルーチンを取り消すとコンポーネントの task が取り消されます
  * ホストが実装する import の `async func` は `suspend fun` になりました（ホストの実装の変更が必要です）
  * `runtime-api` が `kotlinx-coroutines-core` に依存するようになりました。`ComponentBackend` に `invokeSuspending` などの suspending なメソッド、`WitHostImportDispatcher` に `asyncHostFunction`、`Linker` に `defineAsync` が加わりました

* **値の境界を JSON からバイナリ形式（`WitWriter` / `WitReader`）へ変更** ([#62](https://github.com/chikoski/wasm-component-kotlin/pull/62), [ADR-0015](docs/adr/0015-binary-value-boundary.md))
  * 生成コードとバックエンド（`runtime-chicory` / `runtime-wasmtime`）の間の値の受け渡しを、`kotlinx.serialization` の `JsonElement` から型に沿ったバイナリ形式に置き換えました
  * `runtime-api` から `FfmJsonCodec` と `kotlinx-serialization-json` への依存を削除しました。古いバージョンの生成コードやネイティブブリッジとは互換性がありません
* **world の API をパッケージのトップレベルに生成し、export された interface はマーカーを context に取る形に変更** ([#52](https://github.com/chikoski/wasm-component-kotlin/pull/52), [ADR-0012](docs/adr/0012-world-at-package-top-level.md))
  * `<WorldName>World` シングルトン `object` を廃止し、`Session`、`withSession`、`openSession`、`imports(...)`、および world 直下の関数・型を指定パッケージのトップレベルに生成するようにしました
  * 1 回の生成で扱う world を 1 つにしました
  * export された interface の関数は `object` のメンバーではなく、マーカー interface（`<Iface>Exports`）を context に取るトップレベル関数になりました
* **interface を完全修飾名で識別し、WIT のパッケージ階層を Kotlin パッケージに対応付け** ([#51](https://github.com/chikoski/wasm-component-kotlin/pull/51), [#32](https://github.com/chikoski/wasm-component-kotlin/issues/32), [ADR-0011](docs/adr/0011-qualified-interface-keys.md))
  * import した interface は `<base>.<ns>.<pkg>.<iface>`、export した interface は `<base>.exports.<ns>.<pkg>.<iface>` に生成されるようになりました
  * ホスト import の受け渡しを、すべての必須 import をラムダで受け取る `imports(...)` 関数と `Linker.add(impl)` に変更しました
* **`decomposer` と `rust-bridge` を Wasmtime 48 LTS（`=48.0.8`）に統一し、`plan.json` を v3（のち #53 で v4）へ更新** ([#49](https://github.com/chikoski/wasm-component-kotlin/pull/49), [#34](https://github.com/chikoski/wasm-component-kotlin/issues/34), [ADR-0010](docs/adr/0010-wasmtime-lts.md))
  * 古い `plan.json`（v1 / v2 / v3）は `runtime-chicory` で読み込めません。再分解が必要です
* **事前コンパイル済み `.cwasm` の読み込みを専用エントリポイントに分離** ([#41](https://github.com/chikoski/wasm-component-kotlin/pull/41), [#33](https://github.com/chikoski/wasm-component-kotlin/issues/33))
  * `openSession` / `withSession` は `.wasm` コンポーネントのみを受け付け、`.cwasm` を渡すとエラーになります。`.cwasm` の読み込みには `openPrecompiledSession` / `withPrecompiledSession` を使用します
* **ホスト定義 `resource` の表現を `WitHostResource` とホスト用 interface に変更** ([#29](https://github.com/chikoski/wasm-component-kotlin/pull/29), [#14](https://github.com/chikoski/wasm-component-kotlin/issues/14), [ADR-0008](docs/adr/0008-host-defined-resources.md))
  * world 直下または import した interface の `resource` は、ハンドルクラスではなくホストが実装する `interface`（`WitHostResource`）として生成されます
* **WIT → Kotlin の型マッピングを改訂** ([#27](https://github.com/chikoski/wasm-component-kotlin/pull/27), [#17](https://github.com/chikoski/wasm-component-kotlin/issues/17), [ADR-0006](docs/adr/0006-type-mapping.md))
  * `option<T>` を `T?`（ネスト時のみ `WitOption<T>`）、`list<u8>` を `ByteArray`、`char` を `CodePoint` に変更しました

### 主な追加・改善

* WASI 0.3 ホスト設定 API、Gradle 連携、および結合フィクスチャの追加 [#13](https://github.com/chikoski/wasm-component-kotlin/issues/13) [#98](https://github.com/chikoski/wasm-component-kotlin/issues/98) [#99](https://github.com/chikoski/wasm-component-kotlin/issues/99) [#100](https://github.com/chikoski/wasm-component-kotlin/issues/100) [#101](https://github.com/chikoski/wasm-component-kotlin/issues/101) [#102](https://github.com/chikoski/wasm-component-kotlin/issues/102) [ADR-0020](docs/adr/0020-wasi03-filesystem-sandboxing-and-mounts.md) [ADR-0022](docs/adr/0022-wasi03-scope-and-host-configuration.md)

`runtime-wasi` に `wasi03Imports`、パッケージ群ごとの有効化フラグ、セッション単位の入出力ストリームプロバイダを追加しました。`runtime-api` には `Linker.addAll` と `ImportsFactory.plus` を導入し、マッピング済み interface を含む生成ワールドには `imports` の `base` オーバーロードを生成するようにしました。Gradle プラグインには `addWasiDependency` プロパティを追加し、`wasi-03` フィクスチャと機能テストを含む結合テストを整備しています。`wasi:http` は [#117](https://github.com/chikoski/wasm-component-kotlin/issues/117)、`wasi:sockets` は [#118](https://github.com/chikoski/wasm-component-kotlin/issues/118) の P2 課題へ分離しました。

* **Wasmtime の型付きブリッジ (`wasm2kt bridge`) とクラスパスからの読み込み** ([#64](https://github.com/chikoski/wasm-component-kotlin/pull/64), [#67](https://github.com/chikoski/wasm-component-kotlin/pull/67), [ADR-0016](docs/adr/0016-typed-bridge-from-wit-json.md))
* **既存のパッケージへの interface 対応付け（`with` / `--interfaces-only` / マニフェスト自動検出）** ([#55](https://github.com/chikoski/wasm-component-kotlin/pull/55), [#54](https://github.com/chikoski/wasm-component-kotlin/issues/54), [ADR-0014](docs/adr/0014-interface-mappings.md))
* **メモリを持つ子コンポーネント同士の合成（multi-memory アダプターと文字列 transcoder）** ([#53](https://github.com/chikoski/wasm-component-kotlin/pull/53), [#47](https://github.com/chikoski/wasm-component-kotlin/issues/47), [ADR-0013](docs/adr/0013-multi-memory-adapters.md))
* **Component Model 公式テストスイートの取り込み** ([#72](https://github.com/chikoski/wasm-component-kotlin/pull/72), [#30](https://github.com/chikoski/wasm-component-kotlin/issues/30), [ADR-0017](docs/adr/0017-component-model-conformance-suite.md))
* **ゲスト `resource` 引数のハンドル所属検査と、呼び出し成功後の `own` 消費** ([#83](https://github.com/chikoski/wasm-component-kotlin/pull/83), [#76](https://github.com/chikoski/wasm-component-kotlin/issues/76))
* **複数スレッドからの同一セッション利用時の `HostResourceTable` 束縛の直列化** ([#82](https://github.com/chikoski/wasm-component-kotlin/pull/82), [#75](https://github.com/chikoski/wasm-component-kotlin/issues/75))
* **Chicory 未対応機能のビルド時検査と、キャッシュヒット時の警告再表示** ([#46](https://github.com/chikoski/wasm-component-kotlin/pull/46), [#86](https://github.com/chikoski/wasm-component-kotlin/pull/86), [#39](https://github.com/chikoski/wasm-component-kotlin/issues/39), [#78](https://github.com/chikoski/wasm-component-kotlin/issues/78))
* **Chicory バックエンドと Gradle プラグイン（`io.github.wasm2kt`）の追加** ([#4](https://github.com/chikoski/wasm-component-kotlin/pull/4), [#21](https://github.com/chikoski/wasm-component-kotlin/pull/21), [ADR-0001](docs/adr/0001-chicory-backend-for-android.md))
