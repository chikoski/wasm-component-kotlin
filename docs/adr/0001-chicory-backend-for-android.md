# 0001. Android 向けに Chicory バックエンドをビルド時コンパイル方式で採用する

* ステータス: 承認
* 日付: 2026-10-30

## 背景

Wasmtime 版（Rust ブリッジを FFM / JNI で呼ぶ方式）を Android で動かすには、ABI ごとの `.so` の配布、Cranelift JIT と ART の
シグナルハンドラ・W^X 制約の両立、`.cwasm` の事前コンパイルなどが必要になる（`ANDROID_INTEGRATION_PLAN.md`）。

## 検討した選択肢

1. **ネイティブの Wasmtime を FFI で呼ぶ** — 仕様への追従が速い / ABI ごとのネイティブ配布と ART との相性問題
2. **純 JVM ランタイム上で、Canonical ABI とコンポーネント処理をすべて自作する** — ネイティブ不要 / 作業量最大、Endive（旧 Chicory）のロードマップと重複
3. **コンポーネントをビルド時に分解し、core モジュールだけを純 JVM ランタイムで実行する（jco の JVM 版）** — ネイティブ不要、実行時は単純 / 実行時に任意のコンポーネントを動的ロードする用途には向かない

## 決定

選択肢 3 を採用する。ランタイムは Chicory。ART は実行時に生成したバイトコードを読み込めないため、core モジュールは
Chicory の build-time compiler でビルド時に JVM バイトコードへコンパイルし、jar として dex に含める。

## 結果

* ネイティブライブラリなしで Android（minSdk 26）と JVM 11 以上で動く
* 生成されるワールド DSL は Wasmtime 版と共通（[ADR-0003](0003-json-value-boundary.md)）
* 実行時のコンポーネント差し替え（ダウンロードした `.wasm` の実行など）は対象外。必要になればインタプリタ経路（`ChicoryComponent.fromDirectory`）で対応する
* Chicory は Bytecode Alliance 傘下で Endive に移行中。座標やパッケージ名の変更に追従が必要
