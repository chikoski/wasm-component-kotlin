# fixtures

テスト用の WebAssembly コンポーネントと、その WIT です。integration-tests、tooling、gradle-plugin の functionalTest、
decomposer（`decomposer/regenerate-fixtures.sh`）で共有しています。

| ファイル | 内容 | ソース |
| :--- | :--- | :--- |
| `arithmetic` | 四則演算（最小構成） | — |
| `live_calc` | ホスト import | — |
| `counter` | resource（own / borrow、static） | — |
| `roundtrip` | record / variant / enum / flags / option / result / u64 / 文字列、interface の import / export | — |
| `types` | ADR-0006 の型マッピング（`option` → `T?`、`list<u8>`、`char`、ネストした option） | [`src/types`](src/types)（`build.sh` で再ビルド） |
| `host-res` | ホスト定義 resource（import した interface の `blob`、world 直下の `ticket`）と、ホストを呼ぶコンポーネント定義 resource | [`src/host-res`](src/host-res)（`build.sh` で再ビルド） |
| `abi-edge` | Canonical ABI の検証用。core の値をそのまま返し、不正な char / 判別子 / 文字列 / ポインタ、`cabi_realloc` の異常を起こす | [`src/abi-edge`](src/abi-edge)（手書きの WAT。`build.sh` で再ビルド） |
| `res-edge` | resource の所有権の検証用。コンポーネント定義の resource を、生のハンドル番号で操作する（返したハンドルの再利用など。#31） | [`src/res-edge`](src/res-edge)（手書きの WAT。`build.sh` で再ビルド） |
| `calculator` | WIT のみ（コード生成と InMemory セッションのテスト用）。`calculator.json` は `wasm-tools component wit calculator.wit --json` の出力 | — |

`src/` 以下にソースがあるものは、Rust（`types`、`host-res`）と wasm-tools で再ビルドできます。再ビルドしたら `decomposer/regenerate-fixtures.sh` も実行してください。
