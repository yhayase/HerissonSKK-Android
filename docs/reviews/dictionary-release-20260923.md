# 外部辞書の公開前確認

## 取得元と条件

2026-09-23 に、承認済みの5辞書をカタログと同じ公式 URL から取得し、ファイルのヘッダーと配布元の説明を確認しました。[監査データ](../audit/dictionary-sources-20260923.json)に取得日時、最終 URL、HTTP 更新日時、取得した圧縮ファイルと展開後の SHA-256、サイズ、文字コード、ヘッダーを記録しています。辞書本文はリポジトリや APK に追加していません。

| 辞書 | ヘッダーの条件 | 取得元 |
| --- | --- | --- |
| SKK-JISYO.S | GPL-2.0-or-later | https://skk-dev.github.io/dict/SKK-JISYO.S.gz |
| SKK-JISYO.L | GPL-2.0-or-later | https://skk-dev.github.io/dict/SKK-JISYO.L.gz |
| SKK-JISYO.jinmei | GPL-2.0-or-later | https://skk-dev.github.io/dict/SKK-JISYO.jinmei.gz |
| SKK-JISYO.geo | GPL-2.0-or-later | https://skk-dev.github.io/dict/SKK-JISYO.geo.gz |
| SKK-JISYO.zipcode | Public domain | https://raw.githubusercontent.com/skk-dev/dict/master/zipcode/SKK-JISYO.zipcode |

分類は[公式配布案内](https://skk-dev.github.io/dict/)、[辞書の編集・配布条件](https://github.com/skk-dev/dict/blob/master/committers.md)、[郵便番号辞書の説明](https://github.com/skk-dev/dict/blob/master/zipcode/README.md)とも一致します。郵便番号辞書の生成プログラムの GPL と、生成された辞書の public domain 指定は区別します。

geo のヘッダーは元の郵便データの最終更新を2005-09-30、zipcode は辞書のタイムスタンプを2022-05-04と記しています。取得日が新しくても、最新の住所・郵便番号を保証するものではありません。カタログの採用対象や取得 URL は変更しません。

## 本体との境界

本体の MIT は外部辞書へ適用しません。アイコンの条件はその後変更され、現在は[別の利用条件](../../core/src/main/resources/META-INF/icon-usage.txt)です。現在の製品は公式配布元から端末へ直接取得し、端末内で検索用データへ変換します。アプリの提供者が辞書本文を同梱・転載する構成ではありません。

この範囲で、辞書の GPL を理由に独立した本体コードを GPL に変更する必要があるとは判断しません。また、端末内での私的な利用・変換を、提供者による辞書再配布と同一視しません。[GNU の私的利用に関する説明](https://www.gnu.org/licenses/gpl-faq.html#GPLRequireSourcePostedPublic)と[GPLv2 本文](https://www.gnu.org/licenses/old-licenses/gpl-2.0.en.html)を参照しています。

将来、辞書本体や変換済み辞書を同梱・転載・加工配布する場合は、その版の著作権・許諾・無保証表示、GPL 本文、変更表示、必要な対応ソースの提供条件を別途満たします。更新される URL の提示だけを、配布した版のソース提供の証拠とは扱いません。

## 表示の整備

既存の辞書管理の配布元リンクに加え、カタログの取得確認へライセンス名を表示します。初期設定からも標準の設定項目で外部辞書の条件へ移動できるようにし、ライセンス画面に外部辞書の案内と GPLv2 原文をオフラインで収録します。本体の許諾と辞書の許諾を別の項目にします。

これは配布条件を利用者が確認しやすくする整備です。従来のリンクだけの表示を、現在の直接取得方式における GPL 違反だったとは断定しません。初回 S 辞書の自動導入や取得・保存形式は変更しません。

## 記録の限界

今回の監査は取得時点の公開ファイルを対象とします。アプリが各端末で今後取得する版を固定するものではありません。現行の保存形式は取得元 URL とローカル世代を保持しますが、取得したファイルのハッシュや元のヘッダーを保存しません。ローカル世代を配布元の版と呼びません。端末ごとの取得履歴の保存は今回の実装に含めません。

Google Play の準備と本番署名・公開は着手していません。


## 検証とレビュー

別担当が配布元の条件と実際の5ファイルのヘッダー・ハッシュ・サイズ・件数を照合し、表示と実装もレビューしました。圧縮前の取得ファイルのハッシュは取得処理の記録であり、別担当による再取得はネットワーク制限で完了していません。展開済み本文は全5件を独立に照合しました。

全ゲート `build/reports/local-verification/20260923T034111884829Z` はコア324件、Android単体1,126実行（API 26: 486、30: 150、35: 490）、ホスト31件と lint・APK 検査が成功しました。

レビューで、外部辞書の詳細画面にはない GPL 項目を「この画面」と案内していた点を修正しました。正しい設定階層の文言へ変更後、API 26／35 のライセンス画面各4件を再実行し、最終 APK の7通知がソース本文と一致することを確認しました。表示の単体記録と API 35 の実画面は `build/reports/dictionary-release-ui-20260923/` に保存しています。

最終 debug APK で次の試験が成功しました。

| API | 設定・アプリ別 Emacs 編集 | 辞書管理 UI |
| --- | --- | --- |
| 26 | 2/2、`customization-e2e/emulator-5586/20260923T034354948492Z` | 2/2、`dictionary-settings-ui/emulator-5586/20260923T034528051885Z` |
| 35 | 2/2、`customization-e2e/emulator-5584/20260923T034359663560Z` | 2/2、`dictionary-settings-ui/emulator-5584/20260923T034540825809Z` |

設定試験の記録は `test-editor/build/reports/`、辞書管理は `app/build/reports/` 以下です。設定試験は辞書と設定を復元し、辞書管理試験は独立 DB を使用しています。両エミュレーターの既定 IME も元のものへ戻っています。

表示ライフサイクルは辞書通知変更前の APK（最終 APK と入力ロジックは同一）で API 26／35 各3件成功しました（`customization-e2e/emulator-5586/20260923T033617836663Z`、`emulator-5584/20260923T033624403955Z`）。辞書通知だけの追加を理由として、既に受け入れた実機操作・移行・バックアップ・性能を再実行していません。

成果物と各試験との対応は `build/distributions/release-readiness-20260923-db7de1ea/manifest.json` に保存しています。debug APK の SHA-256 は `db7de1ead566eb463b11b4389ae742489b72e1811aded313af875825acc14b43` です。release APK は未署名であり、本番公開物ではありません。
