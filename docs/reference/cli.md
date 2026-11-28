# CLI リファレンス (`wasm2kt`)

`wasm2kt` CLI は、ビルドのステップごとに 2 つのサブコマンドを提供します。

* `wasm2kt generate`（サブコマンドを省略した場合の既定）: `.wit` / `.wasm` / JSON AST から Kotlin のバインディングを生成する
* `wasm2kt bridge`: コンポーネント群に対する型付き Wasmtime ブリッジの Rust クレートを生成・ビルドする（[ADR-0016](../adr/0016-typed-bridge-from-wit-json.md)）

リポジトリ内からは `./gradlew :cli:run --args="..."`（または `./gradlew run --args="..."`、作業ディレクトリはリポジトリルート）で実行できます。

---

## 1. `wasm2kt generate`

```text
Usage: wasm2kt generate [<options>] [<input-path>]
```

サブコマンド名を省略して `wasm2kt <input-path> -o ... -p ...` と書いても同じ動作になります。

### 引数とオプション

| 引数 / オプション | 必須 | 説明 |
| :--- | :--- | :--- |
| `<input-path>` | `--from-json` 以外は必須 | `.wit` ファイル、WIT パッケージディレクトリ、`.wasm` コンポーネントバイナリ、または JSON AST ファイルのパス。`--from-json` 指定時に省略すると標準入力から読み込みます |
| `-o`, `--output=<path>` | 必須 | 生成された `.kt` ファイルの出力先ディレクトリ |
| `-p`, `--package=<text>` | 必須 | 生成される Kotlin コードのベースパッケージ名 |
| `-w`, `--world=<text>` | 任意 | 生成対象の WIT `world` 名。WIT に複数の world がある場合に指定します |
| `--from-json` | 任意 | 入力を `wasm-tools component wit --json` の出力として直接読み込みます（`wasm-tools` を呼び出しません） |
| `--no-wasmtime` | 任意 | Wasmtime の FFM と JNI セッション実装の出力を省略します。 |
| `--with=<interface=package>` | 任意（複数可） | import した完全修飾 interface（例: `wasi:filesystem/types@0.3.0=wasm2kt.wasi.wasi.filesystem.types`）を生成せず、指定した既存パッケージの型と `Linker.add` を使います（[ADR-0014](../adr/0014-interface-mappings.md)） |
| `--library=<path>` | 任意（複数可） | `--interfaces-only` で生成したライブラリの jar またはリソースディレクトリ。マニフェストに記載された interface を world が同じバージョンで import していれば、生成せずにそのパッケージを使います |
| `--no-library-interfaces` | 任意 | `--library` のマニフェストによる自動対応付けを無効にし、`--with` の明示指定のみを適用します |
| `--interfaces-only` | 任意 | world 本体やセッションを生成せず、import される interface とそのマニフェスト（`META-INF/wasm2kt/interfaces/<package>.json`）だけを生成します |
| `--resources-output=<path>` | 任意 | `--interfaces-only` のマニフェストの出力先ディレクトリ（省略時は `--output` と同じ） |
| `--bridge-id=<text>` | 任意 | Wasmtime のセッションを、型付きブリッジ（`wasm2kt bridge`）内のこの ID のコンポーネントとして開きます（[ADR-0016](../adr/0016-typed-bridge-from-wit-json.md)） |
| `--wasm-tools-path=<text>` | 任意 | `wasm-tools` 実行バイナリのパス（既定値: `"wasm-tools"`） |

### 実行例

```bash
# .wit ファイルから生成
./gradlew run --args="generate fixtures/counter.wit -o build/generated -p com.example.counter"

# .wasm から生成（型付きブリッジ用の ID を付与）
./gradlew run --args="generate fixtures/counter.wasm -o build/generated -p com.example.counter --bridge-id counter"

# wasm-tools の JSON AST をパイプで渡して生成
wasm-tools component wit --json fixtures/counter.wasm | ./gradlew run --args="generate --from-json -o build/generated -p com.example.counter"
```

---

## 2. `wasm2kt bridge`

プロジェクトで使う 1 つ以上のコンポーネントから、型付きの Wasmtime ブリッジ（Rust の Cargo クレートおよびネイティブ共有ライブラリ）を生成します（[ADR-0016](../adr/0016-typed-bridge-from-wit-json.md)）。
プロセス内で Wasmtime を 1 つだけ読み込むため、複数のコンポーネントを使う場合も 1 回の `wasm2kt bridge` にまとめて `--component` を複数渡します。

```text
Usage: wasm2kt bridge [<options>]
```

### オプション

| オプション | 必須 | 説明 |
| :--- | :--- | :--- |
| `-o`, `--output=<path>` | 必須 | 生成する Cargo クレートの出力先ディレクトリ |
| `--component=<id=path>` | 必須（1 個以上） | ブリッジに含めるコンポーネントの ID（`generate --bridge-id` と一致させる）と、`.wasm` / `.wit` / `.json` のパス |
| `--world=<id=world>` | 任意（複数可） | WIT に複数の world があるコンポーネントについて、対象の world 名を指定します |
| `--build` | 任意 | クレート生成後に `cargo build --release --locked` を実行し、ビルドされた共有ライブラリのパスを標準出力に表示します |
| `--target=<triple>` | 任意 | Rust のビルドターゲットトリプル（省略時はホスト環境） |
| `--target-dir=<path>` | 任意 | Cargo の `target` ディレクトリ。ビルド間で共有すると Wasmtime クレートの再コンパイルを省けます |
| `--resources-output=<path>` | 任意（要 `--build`） | ビルドされた共有ライブラリを `<path>/META-INF/wasm2kt/native/<platform>/` にコピーします。このディレクトリを jar またはクラスパスに含めると、`runtime-wasmtime` が自動的に読み込みます（[#65](https://github.com/chikoski/wasm-component-kotlin/issues/65)） |
| `--cargo=<text>` | 任意 | `cargo` 実行バイナリのパス（既定値: `"cargo"`） |
| `--wasm-tools-path=<text>` | 任意 | `wasm-tools` 実行バイナリのパス（既定値: `"wasm-tools"`） |

### 実行例

```bash
./gradlew run --args="bridge -o build/typed-bridge \
  --component counter=fixtures/counter.wasm \
  --component types=fixtures/types.wasm \
  --build \
  --resources-output build/typed-bridge-resources"
```
