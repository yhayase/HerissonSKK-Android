# 出典・ライセンスの監査資料

ここには、対象版と限界を明示した出典・許諾判断の原資料だけを置きます。通常の実装レビュー、日々の試験結果、公開準備の進捗は保存しません。現行の適用条件は[ライセンスの説明](../licenses.md)、アプリ同梱の[第三者通知](../../core/src/main/resources/META-INF/third-party-notices.txt)と[外部辞書通知](../../core/src/main/resources/META-INF/external-dictionaries.txt)を参照してください。

| 資料 | 対象と限界 |
| --- | --- |
| [辞書の出典](dictionary-sources.md)・`dictionary-sources-20260923.json` | 取得時点の 5 辞書、ヘッダー、条件、ハッシュ。将来の URL 内容は保証しない |
| `release-license-inventory-20260923.json` | 調査時点の依存物、POM、成果物ハッシュ。後の依存変更は対象外 |
| [SCANOSS 照合](scanoss-20260923.md)・[追加確認](scanoss-20260923-followup.md) | 指定コミットのコード・設定だけを対象とした類似性調査。入力、応答、判断の原資料は `scanoss-20260923-*.json` |
| [データ経路](play-data-flows.md) | 指定時点の本番ソースに対する静的確認。Play 申告や公開版の実通信の証明ではない |
| `branding-licenses-20260923.json` | 当時の APK・素材・通知の識別資料。現行のアイコン条件や公開候補の証拠にはしない |

依存物の一覧を再取得するスクリプトは [`scripts/audit/release-license-audit.init.gradle`](../../scripts/audit/release-license-audit.init.gradle)です。`./gradlew -I scripts/audit/release-license-audit.init.gradle auditReleaseLicenses --offline` は `/tmp/skk-release-artifacts.json` へ一覧を出します。依存グラフの辺や DEX に残るクラスの使用解析は出力しません。取得した版・条件と最終配布物は別途照合します。

原資料のハッシュと内容の対応は維持します。日付は資料の対象時点であり、現行版の合格報告ではありません。
