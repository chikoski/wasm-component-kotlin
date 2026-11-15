## 変更の概要

このプルリクエストで解決する課題と変更内容の要点を記載します。

## 関連 Issue

関連する Issue を「Closes #番号」または「Refs #番号」の形式で記載します。

Closes #

## 更新確認チェックリスト

変更内容に応じて必要なドキュメントと記録を更新したことを以下の項目で確認します。設計や仕様に関わる変更では `docs/` と `README.md` の該当箇所を反映します。プロジェクトの現状や未解決課題が変わった場合は `docs/STATUS.md` を更新します。重要な設計判断を行った場合は `docs/adr/` に新しい ADR を追加します。破壊的変更や主要な機能変更では `CHANGELOG.md` にも追記します。

- [ ] `docs/` と `README.md` の確認
- [ ] `docs/STATUS.md` の更新
- [ ] `docs/adr/` への ADR 追加
- [ ] `CHANGELOG.md` の更新

## テスト確認チェックリスト

変更した箇所や境界に応じて必要なテストを実行し、通過したことを確認します。設計と実装の不変条件やコマンド例は `CONTRIBUTING.md` にまとめています。

- [ ] `./gradlew check`
- [ ] `animalsnifferMain`
- [ ] `decomposer / PLAN_VERSION` の整合確認
- [ ] `ChicoryDifferentialTest`
- [ ] `typedBridgeTest`
- [ ] `ConformanceTest`
