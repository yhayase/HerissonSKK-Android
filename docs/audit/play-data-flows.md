# データ経路の確認範囲

本資料は、2026-09-23 時点の `app/src/main`、`core/src/main`、本番依存、マニフェストに対する静的確認の範囲と根拠を記録します。Console の回答でも、現行公開版の実通信を証明する資料でもありません。[Console 申告案](../play-console-declarations.md)と[プライバシーポリシー](../site/privacy-policy.html)は用途を分けます。

| データ・機能 | 確認した経路と制約 |
| --- | --- |
| 入力文字・周辺テキスト | IME と入力先の間で端末内処理。通常欄では読み・候補・確定形・利用順が個人辞書 DB に残り得る。本番コードで入力本文をネットワーク送信・一般ログ出力する経路は見つからなかった |
| パスワード欄・`TYPE_NULL` | SKK 変換、候補、登録、学習、周辺テキスト取得を抑止する。IME としてキーイベントや `EditorInfo` は扱う |
| 学習禁止の指定 | `IME_FLAG_NO_PERSONALIZED_LEARNING` や利用者の保存無効設定では学習・登録保存を抑止する。通常欄では学習が既定で有効 |
| 辞書の HTTPS 取得 | S／L が未導入の場合の S 自動取得と、利用者による追加・手動更新がある。固定 User-Agent は `skk-android/1`。入力本文、個人辞書、端末固有 ID、アプリ一覧を要求に付加するコードは見つからなかった |
| 辞書・バックアップの SAF 入出力 | 利用者が選んだ文書プロバイダーを介する。クラウド型の保存先なら事業者への転送があり得る。完全バックアップは学習履歴、辞書 URL、非表示指定等を含み暗号化されない |
| クリップボード | Emacs の kill-line で削除した本文を書き、同一セッションで自ら書いた項目か確認する。OS の共有領域であり、他アプリが読める可能性がある |
| アプリ別設定 | 起動可能なアプリの名前・パッケージ名を端末内で列挙し、選択された上書き対象のパッケージ名だけ保存する |
| 自動バックアップ | マニフェストの `allowBackup=false`、`fullBackupContent=false` と data extraction rules の全 domain 除外を確認した。OEM・OS 別の実機保証ではない |
| SDK・権限 | 本番ソースに Analytics、Crashlytics、広告・課金 SDK の宣言は見つからず、辞書通信に `INTERNET` を使う。最終 AAB の推移的依存や merged manifest はこの調査の対象外 |

辞書のダウンローダーは HTTPS のみ許可し、URL の認証情報・フラグメントと private／loopback 等の宛先を拒否します。最大 5 回のリダイレクトごとに再検証します。接続先には通常、IP、時刻、要求先、TLS／HTTP の情報が伝わります。[GitHub Pages の説明](https://docs.github.com/en/pages/getting-started-with-github-pages/what-is-github-pages#data-collection)には IP のセキュリティログ記録・保存があります。ログ保持期間と個別削除、Raw・任意 URL・転送先の運用条件はこの確認では分かりません。Pages の説明を他の配布先へ一般化しません。

コード上で送信経路が見つからないことは、Play の「収集しない」という最終分類とは異なります。実際の公開 AAB、推移的 SDK、権限、実機通信、Console の質問文、外部事業者の条件を提出時に照合します。GitHub Pages の IP とクラウド文書プロバイダーの SAF 転送の分類は[申告案](../play-console-declarations.md)で保守的な案として扱い、確定した事実と混同しません。

元の静的確認で参照した主要な実装境界は `SkkInputMethodService.kt`、`EditorSession.kt`、`EditorEditPort.kt`、`DictionarySettingsActivity.kt`、`NetworkDictionaryDownloader.kt`、`CompleteDictionaryBackupCodec.kt`、`AppEmacsEditingStore.kt`、`AndroidManifest.xml`、`data_extraction_rules.xml` です。対象の作業ツリーは未コミット変更を含んでいたため、特定のコミットや後の AAB に同じ結論を直接適用できません。
