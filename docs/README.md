# ドキュメント案内

現行の要求・操作・設計と、過去の試験結果を分けて管理します。文書の作成日だけで優先順位を決めず、以下の担当範囲を参照します。

## 利用・公開・計画

| 知りたいこと | 正本 |
| --- | --- |
| 導入、現在使える操作、辞書・設定の使い方 | [利用手順](usage.md) |
| 初回の範囲、後続の優先順位、保留事項 | [ロードマップ](roadmap.md) |
| 製品の機能・品質・設定UIの目的 | [製品要件](requirements.md) |
| 操作例、DDSKKの固定参照、対応範囲 | [互換性仕様](compatibility.md) |
| 対象端末・アプリと受入方法 | [検証計画](validation-plan.md) |
| 初回公開の判定項目と未判定項目 | [リリース受入チェックリスト](release-acceptance.md) |
| 初回追加機能の実装・検証の進捗 | [リリース進捗](release-progress.md) |
| 実装の証拠と残る判定条件 | [試験対応表](test-coverage.md) |
| APK、署名・配布物の確認、公開方針 | [配布・更新](distribution.md) |
| 作業の進め方・品質確認の責務 | [開発手順](development-plan.md) |

## 設計の担当範囲

| 分野 | 契約を管理する文書 |
| --- | --- |
| 状態所有、入力・接続境界、Q／q・終端 | [コア](core-design.md) |
| 登録スタック、保存・確定、通常学習 | [登録・学習](registration-design.md) |
| 候補削除・抑止・解除・由来 | [候補削除](candidate-deletion-design.md) |
| 補完の受諾・巡回・上限 | [補完](completion-design.md) |
| 数値表現・展開・学習先 | [数値変換](numeric-design.md) |
| 保存する入力設定、競合、版移行 | [カスタマイズ](customization-design.md) |
| AZIKの参照版と採用範囲 | [AZIK](azik-profile.md) |
| 内部・入力先の編集境界 | [Emacs編集](emacs-editing-design.md) |
| 候補領域、注釈、全文表示 | [候補表示](candidate-display-design.md) |
| SKK形式、辞書順、入出力、永続化 | [辞書](dictionary-design.md) |
| SQLite検索、有限キャッシュ、非同期読込 | [ストレージとキャッシュ](dictionary-storage-cache-design.md) |
| 完全辞書バックアップの形式・復元 | [完全バックアップ](complete-backup-design.md) |
| HTTPS辞書取得、カタログ、初回S辞書 | [ネットワーク辞書](network-dictionary-design.md) |
| アプリ別Emacs編集の保存・適用 | [アプリ別Emacs編集](app-emacs-design.md) |
| 目的別の初回設定画面とS辞書の提供根拠 | [設定UI](settings-ui-design.md) |
| 物理ポップアップ・画面キーボード・フリック・予測候補 | [入力UI再設計](input-ui-redesign.md)・[使い方](usage.md)・[最終検証](reviews/input-ui-20260920.md) |
| Unicode編集の依存・コーパス | [coreの案内](../core/README.md) |

[起動・打鍵性能](performance.md)、[辞書性能](dictionary-performance.md)、[APK更新保持](upgrade-validation.md) は測定・試験の手順と条件付き結果を管理します。これらの結果だけで製品の受入完了とは判断しません。

## 履歴と証拠

- [継続記録](work-status.md) は日付・コミット・APKに対応した実施履歴です。当時の「残件」を現在の計画へ戻しません。
- [フェーズ1記録](phase1.md) は初期実装・初回実機確認の履歴です。
- [レビュー記録](reviews/README.md) は対象差分についての指摘と反映の証拠です。後の設計や実装を上書きしません。
- 旧ロードマップや変更前の契約はGit履歴で確認します。現行文書に旧仕様と訂正文を積み重ねません。

## 更新時の扱い

要求変更は要件、順序変更はロードマップ、挙動変更は互換性仕様と担当設計、実施結果は試験対応表と履歴を更新します。別文書では要約とリンクに留め、操作表・資源上限・試験件数を複製しません。採用済み設計、未実装の計画、検証済み範囲を区別します。擬似APIは実装の名前やシグネチャと区別し、技術契約とコードが食い違う場合は差分を明示して判断します。
