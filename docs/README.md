# ドキュメント案内

| 読者の目的 | 文書 |
| --- | --- |
| 導入・入力・辞書・設定 | [利用手順](usage.md)、[アプリ間の辞書移行](package-migration.md) |
| 機能・操作の契約 | [製品要件](requirements.md)、[互換性仕様](compatibility.md) |
| 内部設計 | [コア](core-design.md)、[辞書](dictionary-design.md)、[入力 UI](input-ui-design.md) と各分野の設計文書 |
| 開発と変更時の検証 | [開発ガイド](development.md)、[検証手順](validation-plan.md)、[要件と試験の対応](test-coverage.md) |
| 性能・更新の再現試験 | [起動・打鍵性能](performance.md)、[辞書性能](dictionary-performance.md)、[APK 更新保持](upgrade-validation.md) |
| 優先順位・公開判定 | [ロードマップ](roadmap.md)、[リリース受入条件](release-acceptance.md)、[配布手順](distribution.md) |
| プライバシー・利用条件 | [プライバシーポリシー](site/privacy-policy.html)、[ライセンス](licenses.md)、[アイコン出典](icon-provenance.md) |
| 版を特定した出典・ライセンスの根拠 | [監査資料](audit/README.md) |
| 固定版の外部実装の比較 | [参考資料](reference/README.md) |

文書には現行の契約と再現可能な手順を記載します。作業の実施状況は [Issue](https://github.com/yhayase/HerissonSKK-Android/issues)、変更と検証結果は PR・CI の成果物で確認します。

## 設計の担当範囲

| 分野 | 正本 |
| --- | --- |
| 入力状態・Android 接続 | [コア](core-design.md)、[互換性仕様](compatibility.md) |
| 物理・タッチ入力と候補表示 | [入力 UI](input-ui-design.md)、[キーボード表示](keyboard-visibility.md) |
| 登録・学習・候補削除 | [登録](registration-design.md)、[登録 UI](registration-ui-design.md)、[候補削除](candidate-deletion-design.md) |
| 補完・数値・編集 | [補完](completion-design.md)、[数値](numeric-design.md)、[Emacs 編集](emacs-editing-design.md) |
| 辞書形式・保存・復元 | [辞書](dictionary-design.md)、[有限キャッシュ](dictionary-storage-cache-design.md)、[完全バックアップ](complete-backup-design.md) |
| 辞書取得・設定 | [ネットワーク辞書](network-dictionary-design.md)、[設定 UI](settings-ui-design.md)、[カスタマイズ](customization-design.md) |
| 入力規則・アプリ別設定 | [AZIK](azik-profile.md)、[アプリ別 Emacs](app-emacs-design.md) |
