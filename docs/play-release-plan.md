# Google Play 公開準備

更新日: 2026-09-23

## 現在地

所有者から開発者アカウントの本人確認・Android端末アクセス確認・電話番号登録を含む手続き完了の報告を受けました。公開準備への着手を承認済みです。Console 上のアプリ作成・申告・アップロード・公開は未実施です。

アプリ ID は `se.haya.skk`、minSdk 26、versionCode 1、versionName `0.1.0-dev` です。開始時の compileSdk／targetSdk は35でしたが、36への更新を実装しました。[互換性検証](reviews/api36-20260923.md)に結果を記録します。本番署名は未設定です。既存の未コミット修正を保持し、開発版署名を本番署名として流用しません。

## 作業順序と分担

| 順序 | 開発側で進める作業 | 所有者の判断・操作 | 完了条件 |
| --- | --- | --- | --- |
| 1 | API 36 対応の影響調査、必要な SDK／ビルド環境更新、入力・ポップアップ・設定画面の回帰検証 | 原則不要 | API 36 対象でビルドでき、API 26 の互換性と API 36 の挙動を確認します |
| 2 | 日本語の掲載文案、既存アイコンからストア用素材、画面写真の準備 | 無料・地域制限なしは決定済みです。公開連絡先は herisson@haya.se と決定しました | 掲載内容と実装が一致し、必要素材が揃います |
| 3 | 通信・保存・依存 SDK の監査、プライバシーポリシー案とアプリ内の参照導線、データセーフティ等の回答案を作成します | 公開連絡先は herisson@haya.se と決定済みです。ポリシーはこのリポジトリの GitHub Pages に公開します。申告内容を確認します | 入力内容、学習、辞書取得、ファイル出力の扱いを根拠付きで説明できます |
| 4 | 署名設定、AAB 作成・内容検査、鍵の保管・復旧手順を用意します | Play App Signing の署名方針とアップロード鍵の保管先を決定します | 秘密をリポジトリやログへ出さず、提出用 AAB と検証記録が揃います |
| 5 | Console 入力用の掲載情報・審査用 IME 有効化手順、内部テストの手順を揃えます | アプリ作成、申告・規約の確認、テスト配信を行います | Play 経由の導入・IME 有効化・辞書取得・入力を確認します |
| 6 | クローズドテストの説明、フィードバック整理、不具合対応を行います | テスター募集と参加、運用、本番アクセス申請を行います | Console の要件を満たし、試験結果を説明できます |
| 7 | 最終成果物と掲載情報、既知制約を確認します | 対象国・価格・内容を確認して公開判断を行います | 承認後に本番リリースします |

1〜3は並行して準備できます。Console のアプリ作成自体は AAB 完成前でも可能です。鍵生成や申告の前に、対象と選択内容を具体化します。公開準備の承認は一般公開の承認とは区別します。

## 現行の公式要件と確認点

- [対象 API 要件](https://developer.android.com/google/play/requirements/target-sdk?hl=en): 2026-08-31 以降、新規アプリは Android 16（API 36）以上が必要です。対象を35から36へ更新します。targetSdk 更新だけで最低対応 OS を上げる必要はありません。
- [個人アカウントのテスト要件](https://support.google.com/googleplay/android-developer/answer/14151465?hl=en): 新規個人アカウントは最低12人が連続14日間オプトインするクローズドテストを実施し、その後本番アクセスを申請します。人数・日数の達成だけで自動承認されるわけではありません。既存の自動試験と所有者による実機受入は、この手続きの代替にはなりません。
- [アプリ作成](https://support.google.com/googleplay/android-developer/answer/9859152?hl=en): 名称は HerissonSKK (for Android)、種類はアプリ、既定言語は日本語を案とします。価格は無料、配布地域は制限せず Google Play で配布可能な全地域と決定しました。日本語入力を必要とする利用者を対象にします。
- [Play App Signing](https://developer.android.com/studio/publish/app-signing): Google が管理するアプリ署名鍵と、提出時のアップロード鍵を区別します。Google 生成のアプリ署名鍵を利用する案を基本に、Play 外配布との署名共通化の要否を決めます。現行開発版との署名差による移行は、バックアップを含めて確認します。
- [データセーフティ](https://support.google.com/googleplay/android-developer/answer/10787469?hl=en): 端末内の処理だけでなく、辞書配布元への通信と依存 SDK を確認して申告します。現時点では回答を確定していません。内部テストだけの扱いとクローズドテスト以降の要件を区別します。

公式情報は2026-09-23に確認しました。提出時には Console の実際の要求と再照合します。既存の API 26／35 の受入を無効にはしませんが、API 36 対応と署名・配布経路の変更に対応した追加検証が必要です。

## 今回の確認範囲

API 36 対応、Android 16 の Back 修正、署名前 AAB の作成と同梱通知検査を完了しました。[掲載文案](play-store-listing.md)、[データフロー監査](reviews/play-data-flows-20260923.md)、[プライバシーポリシー本文](site/privacy-policy.html)を整備しました。無料・地域制限なし・公開問い合わせ先 herisson@haya.se を決定済みです。リポジトリは Public、GitHub Pages でのポリシー公開も完了し、[公開 URL](https://yhayase.github.io/HerissonSKK-Android/privacy-policy.html)へのアプリ内導線を実装しました。残る作業は本番署名、掲載画像、Console の申告・操作、クローズドテスト、公開用成果物の最終検査と公開判断です。

API 36 対応の検証を完了しました。API 26・35・36 の設定・入力各2件と物理窓各6件、API 36 のタブレット幅の設定画面を縦横各4件確認しました。[実装・原因・最終結果](reviews/api36-20260923.md)に記録します。

## リポジトリと Pages の公開結果

`yhayase/HerissonSKK-Android` は Public へ変更済みです。GitHub Pages は GitHub Actions の workflow build で配信し、HTTPS を強制しています。デプロイ run [35850480659](https://github.com/yhayase/HerissonSKK-Android/actions/runs/35850480659) は、公開後の main コミット `6b170b4d6ce47c0663a2a7c3f3ec0575f02eddca` で成功しました。未認証の HTTP 確認では、[トップページ](https://yhayase.github.io/HerissonSKK-Android/) と[プライバシーポリシー](https://yhayase.github.io/HerissonSKK-Android/privacy-policy.html)がともに 200 を返し、リダイレクトはなく、公開バイト列がそれぞれ `docs/site/index.html` と `docs/site/privacy-policy.html` に一致しました。時刻と SHA-256 は `build/reports/pages-publication-20260923/http.json` に保存しています。初回の private 時点の push では Pages workflow が skip されましたが、公開後に手動起動した上記 run で配信を確認しています。

公開前の構成・履歴確認とその調査範囲は[公開前整理の記録](reviews/public-repository-cleanup-20260923.md)を参照してください。同記録の「未実施」は作成時点の状態です。現在の Play 残件は、本番署名、ストア掲載素材、Console の申告とアプリ登録、内部・クローズドテスト、公開用成果物の最終検査と公開判断です。リポジトリとポリシーサイトの公開完了を Google Play でのリリースと混同しません。
