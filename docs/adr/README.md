# Architecture Decision Records (ADR)

重要な設計判断を、その時点の背景・選択肢・結果とともに記録します。形式は [MADR](https://adr.github.io/madr/) を簡略化したものです。

* 1 判断 = 1 ファイル。連番 `NNNN-短い名前.md`
* 一度確定した ADR は書き換えません。方針が変わったら新しい ADR を作り、古い ADR のステータスを「置き換え済み（→ ADR-XXXX）」に更新します
* 新しい ADR は [template.md](template.md) をコピーして作成します

| ADR | タイトル | ステータス |
| :--- | :--- | :--- |
| [0001](0001-chicory-backend-for-android.md) | Android 向けに Chicory バックエンドをビルド時コンパイル方式で採用する | 承認 |
| [0002](0002-wasmtime-environ-decomposer.md) | コンポーネントの分解に wasmtime-environ を使い、分解ツールを wasm で同梱する | 承認 |
| [0003](0003-json-value-boundary.md) | バックエンド間の値の受け渡しに JSON 境界を共有する（フェーズ A） | 承認 |
| [0004](0004-isolated-tooling-classloader.md) | ビルド時ツールを Gradle ワーカーの独立クラスローダーで実行する | 承認 |
| [0005](0005-async-and-wasi03-chicory-only.md) | async と WASI 0.3 は Chicory バックエンドだけで進める | 承認 |
| [0006](0006-type-mapping.md) | WIT → Kotlin の型マッピングを見直す | 承認 |
