# 0020. WASI 0.3 ファイルシステムのサンドボックス方針と宣言的マウント設定

* ステータス: 承認
* 日付: 2026-11-13

## 背景

`wasi:filesystem@0.3.0` のホスト実装では、Android、サーバーサイド JVM、CLI の各環境でファイルシステム要件が大きく異なります。CLI では指定ディレクトリの読み書きに加えて作業ディレクトリ周辺へのアクセスが必要になり、サーバーサイドではマルチテナント隔離や読み取り専用の設定ファイル配布が求められます。Android では Scoped Storage の制約により、`java.io.File` で直接開けない共有ストレージやアセット、ドキュメントプロバイダが存在します。

特に Android のダウンロードフォルダやドキュメントフォルダ、写真ライブラリなどは `java.nio.file.Path` だけでは扱えません。これらをゲストへ公開するには、`ContentResolver` や `DocumentFile`、`MediaStore`、`AssetManager` をラップする仕組みが必要です。同時に、`runtime-wasi` は純粋な JVM 11 モジュールとして維持するため、Android SDK への直接依存を持たせない設計が不可欠です。

## 検討した選択肢

検討した構成は 3 つあります。

* `java.nio.file.Path` 固定の実装
* 起動時にディレクトリを一括許可する全体公開方式
* ケーパビリティベースの宣言的マウント DSL と差し替え可能な `Descriptor` 契約

`java.nio.file.Path` 固定の実装は標準ライブラリだけで完結する利点がありますが、Android の共有ストレージや `assets`、テスト用のインメモリファイルシステムに対応できません。全体公開方式は設定が簡単である一方、ホストの任意パスへのアクセスを防げないため WASI のセキュリティモデルに反します。ケーパビリティベースの宣言的マウント DSL と差し替え可能な `Descriptor` 契約は、既定値を安全なゼロ権限に保ちつつ、標準の `Path` 実装と環境固有のストレージ実装を同じモデルで扱えます。

## 決定

`wasi:filesystem@0.3.0` のホスト実装は、ケーパビリティベースの明示的マウントと `Descriptor` の差し替え可能性を組み合わせた方針を採用します。

既定の状態では事前オープン済みディレクトリを一切公開しません。`wasi:filesystem/preopens` の `get-directories` は、明示的にマウントが登録されない限り空のリストを返します。

ホスト側でマウント設定を記述するために、`Wasi03.Builder` および `WasiFilesystem` に宣言的な `mounts` DSL を提供します。DSL では次の操作を組み合わせてマウントポイントを構成します。

* `readOnly`
* `readWrite`
* `dir`
* `custom`
* `mount`

`readOnly` と `readWrite` は `java.nio.file.Path` を指定する短縮記法であり、`dir` は読み取り専用フラグやディスパッチャを伴う指定に使います。`custom` はホストが用意した任意の `Descriptor` ファクトリやインスタンスを直接バインドするための口であり、Android の `ContentResolver` や `AssetManager`、仮想ファイルシステムへの橋渡しを担います。`mount` はブロック構文で詳細な設定を記述するために使います。

標準のホスト実装として `WasiPathDescriptor` を提供します。`WasiPathDescriptor` は JVM 11 と Android API 26 以降で利用可能な `java.nio.file.Path` と `FileChannel` を用い、すべてのブロッキング I/O を設定された `CoroutineDispatcher` 上で実行します。

`WasiPathDescriptor` は事前オープンされたルートディレクトリの外への脱出を厳格に防ぎます。先頭のスラッシュ、バックスラッシュ、NUL 文字、ルートを越える `..` 参照、絶対パスを指すシンボリックリンク、ルート外へ到達する相対シンボリックリンクは `ErrorCode.NotPermitted` として拒否します。また、読み取り専用マウントでの書き込みや作成、変更系操作は `ErrorCode.ReadOnly` として拒否します。

## 結果

この方針により、`runtime-wasi` に Android SDK 依存を持ち込まずに標準 JVM と Android の両方の要件を満たせます。CLI やサーバーサイドでは `mounts` DSL に `Path` を渡すだけで安全なサンドボックスディレクトリを公開できます。

Android アプリでは共有ストレージや `assets` を扱う際にホスト側で `Descriptor` を実装して `custom` でマウントします。標準の `WasiPathDescriptor` はハードリンクの作成など一部操作を同一ルート内の安全な範囲に制限するため、ルートを跨ぐリンクや移動は拒否されます。
