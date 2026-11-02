# fixtures

テスト用の WebAssembly コンポーネントと、その WIT です。integration-tests、tooling、gradle-plugin の functionalTest、
decomposer（`decomposer/regenerate-fixtures.sh`）で共有しています。

| ファイル | 内容 | ソース |
| :--- | :--- | :--- |
| `arithmetic` | 四則演算（最小構成） | — |
| `live_calc` | ホスト import | — |
| `counter` | resource（own / borrow、static） | — |
| `roundtrip` | record / variant / enum / flags / option / result / u64 / 文字列、interface の import / export | — |
| `types` | ADR-0006 の型マッピング（`option` → `T?`、`list<u8>`、`char`、ネストした option）と、タグ付きの option と紛らわしい record（ADR-0009） | [`src/types`](src/types)（`build.sh` で再ビルド） |
| `host-res` | ホスト定義 resource（import した interface の `blob`、world 直下の `ticket`）と、ホストを呼ぶコンポーネント定義 resource | [`src/host-res`](src/host-res)（`build.sh` で再ビルド） |
| `same-name` | 短い名前が同じ interface（`example:http/types` と `example:fs/types`）を、両方 import と export する（#32、ADR-0011）。import 側のホスト resource と export 側のコンポーネント定義 resource | [`src/same-name`](src/same-name)（`build.sh` で再ビルド） |
| `abi-edge` | Canonical ABI の検証用。core の値をそのまま返し、不正な char / 判別子 / 文字列 / ポインタ、`cabi_realloc` の異常を起こす | [`src/abi-edge`](src/abi-edge)（手書きの WAT。`build.sh` で再ビルド） |
| `res-edge` | resource の所有権の検証用。コンポーネント定義の resource を、生のハンドル番号で操作する（返したハンドルの再利用など。#31） | [`src/res-edge`](src/res-edge)（手書きの WAT。`build.sh` で再ビルド） |
| `composed-res` | 子コンポーネントの間で resource を受け渡す合成コンポーネント。アダプターが Chicory 未対応の組み込み関数を使う（#39） | [`src/composed-res`](src/composed-res)（手書きの WAT。`build.sh` で再ビルド） |
| `calculator` | WIT のみ（コード生成と InMemory セッションのテスト用）。`calculator.json` は `wasm-tools component wit calculator.wit --json` の出力 | — |

`calculator.json`、`types.json`、`host-res.json`、`roundtrip.json`、`same-name.json` は、`generator` のユニットテストの入力です（`wasm-tools component wit <name>.wit --json` の出力。wasm-tools を使わずに実行できるよう、コミットしています）。`.wit` を変えたら、同じコマンドで作り直してください。

`src/` 以下にソースがあるものは、Rust（`types`、`host-res`、`same-name`）と wasm-tools で再ビルドできます。再ビルドしたら `decomposer/regenerate-fixtures.sh` も実行してください。
