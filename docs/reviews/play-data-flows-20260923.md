# Google Play 公開準備向けデータフロー監査（2026-09-23）

## 目的と判定範囲

Google Play の「データ セーフティ」とプライバシーポリシーを後で作成するため、2026-09-23 時点の作業ツリーにある本番コードから、利用者データ・端末データの取得、端末内処理、永続化、外部送信を整理したものです。本書は実装の事実を示す監査資料であり、Play Console の回答または法的なプライバシーポリシーそのものではありません。

確認対象は `app/src/main`、`core/src/main`、本番依存関係、マニフェストです。テスト専用コードはデータフローの根拠から除外しました。Gradle の依存解決、生成済み merged manifest、実機通信のパケット取得、Google Play の最終質問票は確認していません。GitHub Pages は訪問者 IP のセキュリティログ記録・保存を公開資料で確認しましたが、保持期間や個別削除の可否は未確認です。GitHub Raw の郵便番号辞書配布元、任意 URL、リダイレクト先のログ条件も未確認です。このため、本書だけを根拠に「データを収集しない」と最終申告してはいけません。

## 実装から確認できる全体像

| データ／機能 | 取得・利用の契機 | 端末内の扱い | 外部へのデータフロー | 主な根拠 |
| --- | --- | --- | --- | --- |
| 入力文字、変換中の読み・候補、入力先の周辺テキスト | IME が選択され入力欄を操作するとき。Emacs 編集では入力先のテキスト窓を取得する | 通常変換はセッションのメモリー内。学習を許可した通常欄では読み、候補、確定形、利用順を個人辞書 DB に保存し得る | 本番コードに入力文字をネットワークへ送る経路は見つからない | `SkkInputMethodService.kt:189-281`、`EditorSession.kt:38-107,270-292,400-465,825-845`、`EditorEditPort.kt:148-181,240-320,581-657` |
| パスワード欄、`TYPE_NULL` 欄 | 入力先の `EditorInfo.inputType` | protected session とし、SKK 変換・候補表示・登録・学習・周辺テキスト取得を抑止する。通常キーは入力先へ処理を返す | 本番コードに送信経路は見つからない | `SkkInputMethodService.kt:206-220,949-957,1097-1106`、`EditorSession.kt:100,284-292,400-465` |
| 非パーソナライズ学習指定 | 入力先が `IME_FLAG_NO_PERSONALIZED_LEARNING` を設定 | 候補登録・学習・削除の永続化を許可しない | なし | `SkkInputMethodService.kt:206-250`、`PersonalDataPolicy.kt:4-20` |
| 個人辞書・学習履歴・候補非表示指定 | 利用者の登録、候補確定、削除、辞書取込 | アプリ専用 SQLite `skk-dictionaries.db` に保存。学習保存は既定で有効だが設定で無効化できる | 明示的な SAF 書き出し時だけ、利用者が選んだ保存先へ渡る | `BasicSettingsStore.kt:12-18`、`DictionaryRuntime.kt:10-21`、`SQLiteDictionaryRepository.kt:214-280,1295-1327,1361-1399,1417-1429` |
| 辞書ファイル・完全辞書バックアップ | 利用者がファイルを選択、保存先を選択、復元を確認 | SAF から読み、アプリ専用領域で検証・保存する。完全バックアップは候補、履歴、非表示指定、辞書 URL 等を含む | Android の文書プロバイダーを介し、利用者が選んだ保存先／読込元との間で移動する。保存先がクラウド型かは選択したプロバイダー次第 | `DictionarySettingsActivity.kt:273-315,364-425`、`CompleteDictionaryBackupActivity.kt:61-179`、`SQLiteDictionaryRepository.kt:764-841`、`CompleteDictionaryBackupCodec.kt:92-160` |
| ネットワーク辞書 URL と辞書本文 | 初期設定で S／L が未導入のときの自動 S 取得、または利用者によるカタログ・任意 HTTPS URL・既存辞書更新の選択 | 取得した本文を解析後、保存操作で端末内辞書に追加。元 URL も DB と完全バックアップに保存し得る | HTTPS 配布元と各リダイレクト先へ GET 相当の接続が発生する。接続先には通常、送信元 IP、時刻、要求先ホスト・パス、TLS/HTTP メタデータ、固定 User-Agent が見える | `DictionarySettingsActivity.kt:728-889`、`NetworkDictionaryCatalog.kt:13-30`、`NetworkDictionaryDownloader.kt:63-160`、`SQLiteDictionaryRepository.kt:1298-1306` |
| クリップボードのテキスト | Emacs の kill-line 操作と連続 kill の所有確認 | 削除したテキストをシステムクリップボードに書き、同一セッションのランダムラベル・単一項目・本文一致時だけ読み戻す | OS の共有領域内での処理。アプリ自身が端末外へ送信する経路はない | `SkkInputMethodService.kt:122-135,213,269-279`、`EditorEditPort.kt:62-68,276-315` |
| 起動可能アプリの名称・パッケージ名、現在の入力先パッケージ名 | アプリ別 Emacs 設定画面、各入力セッション | launcher intent に応答するアプリだけを一覧化。選択した上書き対象のパッケージ名を private `SharedPreferences` に保存。入力先パッケージ名は設定選択に利用 | 本番コードに送信経路は見つからない | `AndroidManifest.xml:4-9`、`AppEmacsSettingsActivity.kt:58-77,132-159`、`AppEmacsEditingStore.kt:12-44,209-249`、`SkkInputMethodService.kt:198-204` |
| 基本設定・カスタマイズ | 利用者が設定を保存 | private `SharedPreferences` とアプリ専用 `customization-settings.json` に保存 | 本番コードに送信経路は見つからない | `BasicSettingsStore.kt:29-40,74-109,185-197`、`CustomizationStore.kt:48-67,79-100,137-156,176-189` |

「送信経路は見つからない」は静的に確認した現在の本番ソースについての表現です。OS、文書プロバイダー、配布サーバー、DNS、将来追加する SDK の処理を含む「収集なし」の最終判定ではありません。

## 入力処理と端末内保存

IME は `BIND_INPUT_METHOD` で公開されるサービスです。権限は `INTERNET` のみ宣言され、連絡先、位置情報、マイク、カメラ、広告 ID 等の権限・API 利用は本番ソース検索で見つかりませんでした（`AndroidManifest.xml:2-18`）。入力セッションは `InputConnection` を通じて文字を確定・削除し、Emacs 編集では `ExtractedText` またはカーソル周辺文字を取得して編集結果を計算します（`EditorSession.kt:586-610,672,798-807`、`EditorEditPort.kt:581-657`）。取得した入力先テキストを一般ログへ出力するコードは見つかりませんでした。

通常欄では、候補を確定すると読み、候補テンプレート、送り条件、実際の確定文字列、利用順が端末内 DB に残り得ます（`SQLiteDictionaryRepository.kt:214-260,1361-1373`）。これは単なる一時処理ではなく永続化です。個人データ保存設定は既定値 `true` であり、無効化要求時は保留済み書込も revision で拒否する設計です（`BasicSettingsStore.kt:12-18,60-109`、`PersonalDataPolicy.kt:4-20`）。プライバシーポリシーでは、学習で保存される項目、既定値、無効化方法、既存学習データの削除・書出し方法を説明する必要があります。

パスワード変種（数値、通常、visible、web）と `TYPE_NULL` は protected と判定し、学習を禁止します（`SkkInputMethodService.kt:206-207,1097-1106`）。また、入力先が Android の「パーソナライズ学習をしない」フラグを設定した場合も保存を禁止します。ただし、IME である以上、キーイベントや `EditorInfo` を処理すること自体は避けられないため、「パスワードを一切扱わない」とは表現せず、「パスワード欄を検出して変換・候補・学習を無効化する」と記すのが実装に合います。

## ネットワーク辞書と外部送信メタデータ

通信は既知の GitHub Raw／Pages 辞書配布先については初回設定で S／L が未導入の場合の S 自動取得、または利用者による辞書取得・更新操作で始まります（`NetworkDictionaryCatalog.kt:13-30`、`SetupActivity.kt:38-41`、`InitialDictionaryInstaller.kt:49-67`）。利用者は任意の公開 HTTPS URLも指定できます（`DictionarySettingsActivity.kt:848-889`）。したがって、辞書取得ボタンを押す場合だけが通信契機ではありません。自動テレメトリーとして起動するコードは見つかりませんでした。

ダウンローダーは HTTPS のみを許可し、URL 内認証情報とフラグメントを拒否し、DNS 解決後に private／loopback 等のアドレスを拒否します。最大 5 回のリダイレクトを手動追跡し、各転送先を同じ規則で再検証します（`NetworkDictionaryDownloader.kt:71-100,141-160,184-203`）。固定ヘッダーは `Accept-Encoding: identity` と `User-Agent: skk-android/1` です（同 `85-91`）。コード上、入力文字、個人辞書、端末 ID、インストール済みアプリ一覧を要求本文やヘッダーへ付加していません。

ネットワーク要求には送信元 IP、接続時刻、ホスト・パス、TLS/HTTP 情報、固定 User-Agent が含まれます。GitHub Pages は訪問者 IP をセキュリティ目的でログ記録・保存すると説明しています。IP アドレスはそれだけで位置情報や識別子と決まるものではなく、固定 User-Agent `skk-android/1` はインストール識別子ではありません。これは入力データの送信とは別のフローです。GitHub Pages のログ保持期間・削除方法、IP から位置・利用者識別を推定するかは未確認です。Raw の郵便番号辞書配布元、任意 URL、リダイレクト先については公開者が条件を統一できないため、個別の配布先条件として扱います。

辞書のライセンス表示は外部ブラウザーへ `ACTION_VIEW` を送ります（`DictionarySettingsActivity.kt:728-740,840-845`）。この場合の URL 閲覧履歴・通信は選択されたブラウザー側の処理も関係します。

## ファイル選択、書き出し、バックアップ

個人辞書の取込・書出しと完全バックアップは Storage Access Framework を使い、利用者が対象 URI を明示選択します。ローカル保存先への読書きは端末内です。利用者がクラウド文書プロバイダーを選んだ場合に、そのサービスとの転送が起こり得ます（`DictionarySettingsActivity.kt:273-315,397-425`、`CompleteDictionaryBackupActivity.kt:61-135`）。システム辞書では更新のため read URI 権限を永続化し、辞書を削除した際に不要な権限を解放します（`DictionarySettingsActivity.kt:180-190,364-378`）。

個人辞書の通常書出しには個人候補が含まれ、候補非表示指定は含まれません（`SQLiteDictionaryRepository.kt:264-280`）。完全バックアップには個人・追加辞書の候補、注釈、送り条件、取得元 URL、有効状態・順序、非表示指定、学習した確定文字列と利用順、アプリ版が含まれます（`SQLiteDictionaryRepository.kt:764-841`、`CompleteDictionaryBackupCodec.kt:92-160`）。バックアップは暗号化されていません。クラウド文書プロバイダーへの書出しは利用者が開始・選択する転送です。Google の Drive／Dropbox の例外 FAQ は、アプリがデータにアクセスしないケースを扱うため、アプリが構成・読込みする辞書やバックアップにそのまま当てはまりません。一方、その FAQ の適用外であることだけからすべての SAF 書出しを「収集」と断定することもできません。Console の実際の質問票に照らし、「ファイルとドキュメント」／任意／アプリの機能を推奨案として検証します。

完全バックアップの作成・復元検証ではアプリの `cacheDir` に一時ファイルを作り、ハンドル終了時に削除します（`DictionaryManager.kt:500-529`、`CompleteBackupOperations.kt:21-40`、`ValidatedDictionaryBackup.kt:18-40,58-114`）。異常終了後の残留可能性やファイルシステム上の消去保証は本静的監査では確認していません。

Android の自動クラウドバックアップと端末間転送は、マニフェストで `allowBackup=false`、`fullBackupContent=false` とし、data extraction rules でも全 domain を除外しています（`AndroidManifest.xml:10-12`、`data_extraction_rules.xml:2-16`）。OEM 実装や OS バージョンを含む実機確認は未実施です。アプリ内の明示的な SAF バックアップはこの除外とは別です。

## クリップボードとアプリ一覧

クリップボードは Emacs kill-line の削除文字列を OS 共有クリップボードへ置く用途です。連続 kill の連結時は、セッションごとに生成したランダムラベル、項目数、本文がすべて一致する場合だけ現在のクリップを所有物とみなします（`SkkInputMethodService.kt:213,269-279`、`EditorEditPort.kt:276-315`）。この用途でアプリがクリップボード内容を端末外へ送信する経路はありません。他アプリや OS 機能が読める可能性はありますが、それだけでアプリによる端末外収集とは分類しません。一般的なクリップボード履歴の収集や、任意の他クリップを取り込むコードは見つかりませんでした。

アプリ別 Emacs 設定画面は、マニフェストの package visibility query と `queryIntentActivities` により launcher 起動可能なアプリの表示名とパッケージ名を列挙します（`AndroidManifest.xml:4-9`、`AppEmacsSettingsActivity.kt:132-159`）。一覧全体は永続化せず、利用者が上書きを設定したパッケージ名だけを private `SharedPreferences` に保存します（`AppEmacsEditingStore.kt:12-21,209-249`）。IME は現在の入力先 `EditorInfo.packageName` を使ってその設定を選びます（`SkkInputMethodService.kt:198-204`）。これらをネットワークへ送る経路は見つかりませんでした。

## ログ、SDK、広告、課金

本番ソース内に `android.util.Log`、標準出力、`printStackTrace` によるログ出力は見つかりませんでした。例外は主に失敗種別へ変換され、UI メッセージに使われています。ただし Android OS、WebView ではない外部ブラウザー、文書プロバイダー、ネットワークスタックのログは対象外です。

直接の本番依存はプロジェクト内 `core`、AndroidX Preference、RecyclerView、Material Components、ICU4J です（`app/build.gradle.kts:44-55`、`core/build.gradle.kts:10-12`）。Firebase Analytics、Crashlytics、広告、課金、認証、クラウドストレージ、一般的な HTTP クライアント SDK は宣言されていません。辞書通信は `HttpURLConnection` を直接使います。この結論はビルドファイルの静的確認であり、リリース AAB の依存関係・merged manifest・SDK Index による再確認が必要です。

## Play データ セーフティ回答のための暫定整理

以下は質問票入力前の候補整理です。Google Play のその時点の定義、公開者と配布元の契約・支配関係、公開版 AAB を確認して確定します。

| Play で検討するデータ | 実装上の事実 | 暫定的な論点 |
| --- | --- | --- |
| ユーザー生成コンテンツ／入力文字 | 変換のため端末内処理。通常欄では学習結果を端末内 DB に保存し得る | 端末内だけの処理が Play の「収集」除外条件を満たすか、最新定義で確認する。入力文字をネットワークへ送らないことを公開版で検証する |
| ファイル、ドキュメント | 利用者が SAF で辞書・バックアップを読み書きする | 利用者主導の転送の例外条件と、クラウド文書プロバイダーを選んだ場合の扱いを確認する |
| アプリの情報 | launcher 対応アプリ名・パッケージ名を端末内で列挙し、選択したパッケージ名を保存 | Play の「インストール済みアプリ」等の分類、端末内処理の除外条件を確認する |
| デバイスまたはその他の ID | 広告 ID、Android ID、端末シリアル等を取得するコードは見つからない。既定の S／L／jinmei／geo 辞書は GitHub Pages 配布元へ接続し、GitHub は Pages が訪問者 IP をセキュリティ目的でログ記録・保存すると説明しています | **推奨案（保守的な解釈）:** IP をこのデータ型で収集・共有、非一時的、必須として申告します。目的の候補は、要求処理に伴う「アプリの機能」（推論）と、Pages が明記するセキュリティログ目的です。Google の「不正利用防止、セキュリティ、法令遵守」は一つの分類で、公開資料が示すのはセキュリティ目的です。これはGoogleがIPを一律にこのカテゴリへ入れる明示要件ではなく、確認できた事実から導く提案です。IP はそれだけで位置情報・利用者 ID と決まりません。固定 User-Agent `skk-android/1` はインストール ID ではありません。郵便番号辞書の Raw 配布元、任意 URL、リダイレクト先は別途確認が必要です。[GitHub Pages のログ説明](https://docs.github.com/en/pages/getting-started-with-github-pages/what-is-github-pages#data-collection) |
| アプリの操作／診断 | アナリティクス・クラッシュ SDK と本番ログ送信経路は見つからない | 公開 AAB の SDK と Play SDK Console を再確認する |

## 公開前に確定が必要な事項

1. 公開者は Yasuhiro Hayase、プライバシー問い合わせ先は `herisson@haya.se` です。ポリシーは GitHub Pages に公開済みです: https://yhayase.github.io/HerissonSKK-Android/privacy-policy.html 。Play Console と掲載情報ではこの URL を使用します。
2. Play Console の実際の質問票を見て、GitHub Pages の IP ログに対する上記保守的提案と、クラウド保存先への SAF 転送を対応付けます。SAF は「ファイルとドキュメント」／任意／アプリの機能を推奨案とします。これは利用者が選んだ転送の例外が適用される可能性を残したうえでの提案です。Drive／Dropbox FAQ の「アプリはデータにアクセスしない」例外はアプリが作成・読み取るバックアップにそのまま適用できませんが、反対に全 SAF 転送が収集だとも断定できません。
3. GitHub Pages について、IP のセキュリティログ記録・保存は公開資料で確認済みです。ログ保持期間、個別削除依頼、IP から利用者位置・識別を推定するかは未確認です。Pages の公開資料を `raw.githubusercontent.com`、任意 URL、リダイレクト先へ一般化しません。郵便番号辞書の Raw 配布元の個別条件は未確認です。固定 User-Agent `skk-android/1` はインストール ID ではありません。
4. release AAB から権限、merged manifest、依存 SDK、ネットワークセキュリティ設定を再監査し、静的・動的通信テストで「辞書取得と明示的外部遷移以外の通信がない」という期待を検証します。
5. 個人辞書、学習履歴、候補非表示指定、保存済みアプリ別設定について、UI 上の削除手順とアンインストール時の消去を確認し、ポリシーの保持・削除説明を決めます。
6. 暗号化されない辞書／完全バックアップの内容と、クラウド保存先を利用者が選択することを利用者向け説明に含めます。クリップボードはアプリのコードから端末外へ送信されず、別アプリが読める可能性だけで端末外収集とは扱いません。
7. 初回対象年齢は13–15歳、16–17歳、18歳以上とし、13歳未満は含めない方針です。Console への申告は未実施です。13–15歳・16–17歳が一部地域で子どもと扱われること、および各地の法令を別途確認する必要があります。後で13歳未満を含める場合は Families 要件、Data Safety、IARC、プライバシー説明を再確認し、アプリ更新を提出します。[対象年齢案内](https://support.google.com/googleplay/android-developer/answer/9867159?hl=en)、[Families ポリシー](https://support.google.com/googleplay/android-developer/answer/9893335?hl=en-GB)。

## 監査上の制約

- 監査対象は 2026-09-23 の未コミット変更を含む作業ツリーです。今後の変更で結論は変わります。
- サーバー側、DNS、CDN、Google Play、Android OS、OEM、ブラウザー、文書プロバイダーの実装とポリシーは確認していません。
- Gradle 実行、ADB、実機通信、AAB 解析を行っていません。推移的依存関係と manifest merger の最終結果は未確認です。
- コード上で経路が見つからないことと、法的・Play 上の「収集しない」は同義ではありません。最終申告は公式質問票、公開版バイナリ、運用実態、外部サービスの条件をそろえてレビューします。
