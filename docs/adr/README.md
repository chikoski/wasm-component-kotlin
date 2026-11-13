# Architecture Decision Records (ADR)

重要な設計判断を、その時点の背景・選択肢・結果とともに記録します。形式は [MADR](https://adr.github.io/madr/) を簡略化したものです。

* 1 判断 = 1 ファイル。連番 `NNNN-短い名前.md`
* 一度確定した ADR は書き換えません。方針が変わったら新しい ADR を作り、古い ADR のステータスを「置き換え済み（→ ADR-XXXX）」に更新します
* 新しい ADR は [template.md](template.md) をコピーして作成します

| ADR | タイトル | ステータス |
| :--- | :--- | :--- |
| [0001](0001-chicory-backend-for-android.md) | Android 向けに Chicory バックエンドをビルド時コンパイル方式で採用する | 承認 |
| [0002](0002-wasmtime-environ-decomposer.md) | コンポーネントの分解に wasmtime-environ を使い、分解ツールを wasm で同梱する | 承認 |
| [0003](0003-json-value-boundary.md) | バックエンド間の値の受け渡しに JSON 境界を共有する（フェーズ A） | 置き換え済み（→ ADR-0015） |
| [0004](0004-isolated-tooling-classloader.md) | ビルド時ツールを Gradle ワーカーの独立クラスローダーで実行する | 承認 |
| [0005](0005-async-and-wasi03-chicory-only.md) | async と WASI 0.3 は Chicory バックエンドだけで進める | 承認 |
| [0006](0006-type-mapping.md) | WIT → Kotlin の型マッピングを見直す | 承認（world の形は → ADR-0012） |
| [0007](0007-canonical-abi-validation.md) | Canonical ABI の検証は仕様に従い、トラップとホストのエラーを分ける | 承認 |
| [0008](0008-host-defined-resources.md) | ホスト定義 resource を interface + Imports で表し、rep の表で受け渡す | 承認 |
| [0009](0009-json-boundary-option-tag-and-scalar-types.md) | JSON 境界で option のタグを型に基づいて判定し、スカラーの JSON 型を検査する | 置き換え済み（→ ADR-0015） |
| [0010](0010-wasmtime-lts.md) | decomposer と rust-bridge を同じ Wasmtime の LTS に揃える | 承認 |
| [0011](0011-qualified-interface-keys.md) | interface を完全修飾名で識別し、Kotlin では WIT の構造を package に写す | 承認 |
| [0012](0012-world-at-package-top-level.md) | world を package のトップレベルに生成し、export した interface はマーカーを context に取る | 承認 |
| [0013](0013-multi-memory-adapters.md) | 合成コンポーネントのアダプターを multi-memory で検証し、async の組み込み関数を使わない形で生成する | 承認 |
| [0014](0014-interface-mappings.md) | 既存の package の interface を対応表（with）で使い、ライブラリはマニフェストで提供する interface を示す | 承認 |
| [0015](0015-binary-value-boundary.md) | 値の受け渡しを型に沿ったバイナリ形式にし、Wasmtime にはコンポーネントごとの型付きブリッジを加える | 承認（型付きブリッジの作り方は → ADR-0016） |
| [0016](0016-typed-bridge-from-wit-json.md) | 型付きブリッジの Rust の型を wit.json から生成し、bindgen! を使わない | 承認 |
| [0017](0017-component-model-conformance-suite.md) | Component Model の公式テストスイートを写して取り込み、既知の失敗をスキップリストで管理する | 承認 |
| [0018](0018-async-abi-on-chicory.md) | Chicory の async ABI は仕様の定義を移植し、スレッドで継続を表す | 承認（ホストとの境界は → ADR-0019） |
| [0019](0019-suspending-async-boundary.md) | async の境界をコルーチンにし、ホストの async な import も suspend fun にする | 承認 |
| [0020](0020-wasi03-filesystem-sandboxing-and-mounts.md) | WASI 0.3 ファイルシステムのサンドボックス方針と宣言的マウント設定 | 承認 |
