# 検証用 APK と配布前の確認

## 現在の方針（2026-09-23）

名称は **HerissonSKK (for Android)**、アイコンは所有者指定の `HerissonSKK-icon-refined.png` に決定しました。アプリの表示名・adaptive launcher icon への反映を完了しました。

Google Play ストアへの公開準備を開始しました。所有者から開発者アカウント登録手続きの完了報告を受けています。[公開準備計画](play-release-plan.md)に現行要件、作業順序、所有者との分担を記録します。API 36 対応と release AAB の作成・検査は完了しました。本番署名、ストア掲載画像、Console の申告、クローズドテスト、公開用成果物の最終検査と公開判断が残ります。

価格は無料、配布地域は制限せず Google Play で配布可能な全地域とします。公開問い合わせ先は `herisson@haya.se` です。[プライバシーポリシー](https://yhayase.github.io/HerissonSKK-Android/privacy-policy.html)は GitHub Pages で公開済みで、2026-09-23に HTTP 200 とローカル HTML とのバイト一致を確認しました。[掲載文案](play-store-listing.md)と[ポリシー HTML](site/privacy-policy.html)を参照してください。設定の「その他」から利用者の操作で固定 HTTPS のポリシー URL を外部ブラウザーへ開けます。GitHub リポジトリも Public です。Google Play での配布は未開始で、本番署名、掲載画像、Console の申告、クローズドテスト、公開用成果物の最終検査と公開判断が残ります。

本体の独自コード・独自文書に MIT を採用し、[LICENSE](../LICENSE) とアプリ内表示を追加しました。指定アプリアイコンは HerissonSKK のブランド識別として扱い、MIT の対象ではありません。公式アプリの配布・紹介で使用でき、別製品への再利用には個別の許可が必要で、原則として認めません。[アイコン使用条件](../core/src/main/resources/META-INF/icon-usage.txt)と[調査・適用範囲](licenses.md)に従い、第三者コード・Material Icons・外部辞書の条件を分けて表示します。

ローカルゲート `build/reports/local-verification/20260923T103354295483Z/result.json` はコア324件・Android単体1,133実行・ホスト31件、lint、3構成の APK、release AAB、8件の通知検査に成功しました。物理入力は API 26／35／36 で各6件、API 36 のタブレット幅の表示・復帰は縦横各4件が `build/reports/api36-release-matrix-20260923/wide-result.json` で成功し、API 26／35／36 の `CustomizationE2eTest` 各2件も `build/reports/readiness-final-20260923/result.json` で成功しました。記録した範囲で API 36 の検証を完了しています。[API 36 の検証記録](reviews/api36-20260923.md)を参照してください。debug は検証用署名、release は未署名であり、一般公開していません。

以下は従来の配布方針と検証の履歴です。初回を APK 配布基本とし、Play を必須としない記載は上記方針に置き換わります。初期 S 辞書の挙動や成果物の状態も各記録時点のものです。

## 初回公開の方針（2026-09-19）

初回はAPK配布を基本とし、Play公開は制度・費用・公開条件を確認して再判断します。Play公開を初回の必須条件にはしません。2026-09-19に確認した [Play登録案内](https://support.google.com/googleplay/android-developer/answer/6112435?hl=en) は一回限り25 USDです。無料化・値上げの公式予告は今回確認できていません。新規個人アカウントには [12人・14日間のクローズドテスト等の要件](https://support.google.com/googleplay/android-developer/answer/14151465?hl=en) があります。公開時点で再確認します。

Play外の [Android開発者確認制度](https://developer.android.com/developer-verification) は別に確認します。無料の [限定配布](https://developer.android.com/developer-verification/guides/limited-distribution) は最大20台向けで、無料のPlay公開を意味しません。APK配布にも公開時点の地域・制度・配布経路に応じた条件を確認します。

製品は、依存コード・辞書の条件に問題がなければ非コピーレフトのpermissiveなOSSライセンスにします。具体的なライセンスは未決定です。S辞書のサイズと取得元の条件を調べ、明示的な初回取得方式を採用しました。公開時の条件・表示の確認は残ります。ダウンロード方式だけでライセンス上の確認を省略しません。

ネットワーク辞書のためにINTERNET権限を使用します。権限ゲートもその存在を検査するよう変更しています。取得は辞書画面の明示操作に限定し、起動・入力・定期処理では行いません。取得後の変換はオフラインで動作します。以下の旧APKの権限検査は当時の構成の証拠です。

初期S辞書は明示的な初回取得方式です。[提供判断と調査結果](settings-ui-design.md#初期辞書の提供方法)を参照します。限定試験辞書はdebug版だけで有効にし、release・benchmark版では変換のフォールバックへ渡しません。

SQLiteは版4、完全バックアップの書き出しは版2です。既存SQLiteと版1バックアップを読み込み、取得元を未設定として扱います。新しい版2バックアップは旧版アプリで復元できません。署名が異なる開発版から本番版への移行は、所有者の署名方針決定後に別途確認します。


本プロジェクトは現在、プライベートリポジトリと ADB 導入で検証します。一般公開、Play の登録、本番署名鍵の作成・保管、プロジェクト自身の公開ライセンスの決定は、所有者が決める配布手続きです。これらの決定がなくても、開発版の実装と自動試験を進められます。

## 成果物と署名

現在の試用版は `build/distributions/release-trial-20260920/app-release-trial.apk` です。release構成を既存の開発鍵で署名し、限定試験辞書へのフォールバックを無効にしています。SHA-256 は `3094cf630910297c0321bae6b552a52b8c9bd4a5ff44afe706a10822d60bdde0` です。開始点 `8b3052d` からの未コミット変更を含み、ハッシュ・試験条件・導入手順を同ディレクトリに記録しています。公開ライセンス、本番署名、実機の受入と一般公開は未完了です。

以下は過去の配布履歴です。

前回の実機確認用は `build/distributions/021c62f/app-debug.apk` です。「候補の全文」は選択中の候補または注釈が省略された場合だけ表示し、「ローカル辞書・限定試験辞書」の固定表示を削除しました。SHA-256 は `10b1b53b682d6c89e72b109dccbd0cbb9e0c7db4da169570bd92dcd9e6757a7c` です。前回と同じ開発署名で上書き更新できます。検証結果は [作業記録](work-status.md) と配布ディレクトリの `manifest.json` に記録しています。

前回の実機確認用は `build/distributions/a1d748a/app-debug.apk` です。辞書をSQLite正本とする有限キャッシュへ変更し、起動・学習後の全件読み込みを廃止しました。未キャッシュ検索は非同期で処理します。SHA-256 は `5468041d3174d7e03168d80b188629b3658fb48c0f4dee562b270de94a0e451e` です。前回と同じ開発署名で上書き更新でき、辞書の再取り込みや保存形式の移行は不要です。最終APKに対する検証・性能比較を [作業記録](work-status.md) と配布ディレクトリの `manifest.json` に記録しています。

前回の実機確認用は `build/distributions/1b9faa2/app-debug.apk` です。候補一覧の横並び・幅に応じた件数、小さな注釈、単独候補のカーソル付近への注釈表示、KasU／KaSuの送り認識を改善しました。注釈は本文とは独立したウィンドウに表示し、座標通知がない入力先では省略します。SHA-256 は `3d7f884ab54c8c87963e58bdb7b14889fcb3a0a439ce3cd7c2e3667be6e2ed39` です。前回と同じ開発署名で上書き更新できます。検証結果と配布APKの対応は [作業記録](work-status.md) と配布ディレクトリの `manifest.json` に記載しています。

前回の実機確認用は `build/distributions/bc1e24a/app-debug.apk` です。C-qによる次キーの引用、任意の▽／▼表示、確定直後の学習順位、標準ローマ字、候補一覧のページ表示を改善しました。SHA-256 は `2694b2e538b2abc9f2c4e18a75e106d7a0a63c663424ada3aa6acbaef9cf4770` です。前回と同じ開発署名で上書き更新できます。未コミット変更のないソースからの増分ビルドで、ローカルゲート `20260917T073149711933Z` が成功しています。API 35 の入力・設定・SAF E2E、API 26／35 の候補表示と配布APKの対応は [作業記録](work-status.md) と配布ディレクトリの台帳に記載しています。設定は版4へ移行し、旧標準の半角カナ C-q は引用へ変わります。半角カナは必要に応じて再割当し、表示記号は入力規則設定で有効にします。

前回の実機確認用は `build/distributions/f279fab/app-debug.apk` です。文末・最終行の編集、入力欄の非同期応答、C-m 後の継続編集、M-</> の範囲選択、候補なし登録の取消を修正しました。SHA-256 は `bdbefa0dc3a64ea129956d5ca086a77d61fb59e2a783ec17c687be6153fe3c70` です。前回と同じ開発署名で、アンインストールせず上書き更新できます。未コミット変更のないソースからの増分ビルドで、ローカルゲート `20260917T060634320143Z`、API 26／35 の入力と編集 E2E が成功しています。実アプリ・実機の受入とは区別します。詳細は [作業記録](work-status.md) を参照します。

前回の実機確認用は `build/distributions/81cb7ee/app-debug.apk` です。再帰登録・候補一覧・かな記号・編集キー・辞書一括適用の修正版です。SHA-256 は `ee7fdfeafa2ef70a502d9d50d54e7ecba1de4320ae85f4afd5e0dc7d53135e64` です。前回と同じ開発署名なのでアンインストールせず更新します。検証は [作業記録](work-status.md) に記載しています。入力設定は版3へ保存し、版1／2の読み込みと既存カスタムキーの保持に対応します。


前回の実機確認用は `build/distributions/7398c40/app-debug.apk` です。2026-09-17 の候補表示・公式 L 辞書取り込みの修正版です。SHA-256 は `d24e37ed3bf509510aa738d8b39b5e1fe44951c6b86e2bade8c6cc097d384503`、ソースは `7398c40` です。既存版と同じ開発署名を確認しており、アンインストールせず更新導入します。ローカルゲート `20260917T012947172046Z` が成功しています。生成物を削除したクリーンビルドではなく、未コミット変更のないソースからの増分ビルドです。

- `app/build/outputs/apk/debug/app-debug.apk`: 開発署名・デバッグ可能な検証版です。
- `app/build/outputs/apk/benchmark/app-benchmark.apk`: 開発署名・デバッグ不可・別パッケージの性能計測版です。
- `app/build/outputs/apk/release/app-release-unsigned.apk`: 本番署名を設定していない場合の未署名成果物です。このまま端末へ導入する配布版とは扱いません。

開発署名鍵はリポジトリに含めません。更新導入にはパッケージ名と署名の一致が必要です。署名が異なる場合にアンインストールして回避すると辞書と設定が失われるため、更新検証は `adb install -r` で行い、`pm clear` とアンインストールは使いません。

APK ごとに SHA-256、バイト数、ソースコミットと未コミット変更、ビルド環境、検証結果を記録します。秘密鍵や入力本文を検証記録に含めません。開発版は debug 専用試験サービスを含みますが、本番ソースセットには含めません。

## 依存関係と由来

2026-09-16 の `:app:dependencies --configuration releaseRuntimeClasspath` は次を示しました。

| 実行時依存 | 版 | 通知の根拠 |
| --- | --- | --- |
| Kotlin 標準ライブラリ | 2.1.20 | 配布 POM の Apache 2.0 指定 |
| JetBrains annotations | 13.0 | 配布 POM の Apache 2.0 指定 |
| ICU4J | 77.1 | 配布物のライセンス・第三者通知 |

`META-INF/Apache-2.0.txt`、`META-INF/icu-LICENSE.txt`、`META-INF/third-party-notices.txt` を core のリソースに置き、APK 内にも残します。Apache 本文は開発環境の `/usr/share/common-licenses/Apache-2.0` の原文です。依存関係を変更したら POM と成果物内の通知を再確認します。試験専用の JUnit、Robolectric、AndroidX test は release の実行時依存に含まれません。

DDSKK、AndroidSKK、ブラウザ版は [調査記録](phase1.md) と [レビュー](reviews/browser-extension-20260916.md) に従って挙動を参照します。参考実装のライセンスを理由なく本プロジェクト全体へ転用せず、コードの流用と挙動の独立実装を区別します。AZIK の由来と採用範囲は [専用記録](azik-profile.md) にあります。

## 再検証の実行

SDK を `local.properties` または `ANDROID_HOME` に指定し、`python3 scripts/verify-local.py` を実行します。既存キャッシュだけを使う場合は `--offline` を追加します。ホスト試験、コア・Android 単体、lint、3 種類の APK と試験 APK を構築し、通知の同梱、ZIP 整合性、ネットワーク権限と debug 専用サービスの除外を検査します。結果は `build/reports/local-verification/` に JSON と各コマンドの出力を残します。

## 最終検査

クリーンなソースから JVM・Android 単体、lint、APK を再構築し、専用エミュレーターで入力・設定・辞書・SAF・更新・表示を検証します。実行器は失敗、スキップ、未完了を成功として扱いません。端末設定を変更する実行器では、元の IME・設定・画面状態への復元も合格条件です。

対象実機とアプリ別の互換性は別の受入結果です。エミュレーターの成功を Xiaomi Pad 8、Bluetooth、外部アプリの操作感の保証へ読み替えません。

## 2026-09-16 の検証済み成果物

ソース `f025b9ddb546de6b2bc57b6f0ea40f7e91eed1a9` を生成物のない別チェックアウトで構築し、ローカルゲート `20260916T072547824347Z` が成功しました。結果を `build/reports/clean-verification/f025b9d/`、成果物と台帳を `build/distributions/f025b9d/` にコピーしています。

検証用は `build/distributions/f025b9d/app-debug.apk` です。SHA-256 は `47f5383788833cfc4d73aa4fad79045cdca19b68356c6488a176e716fa467a04` です。同 APK の API 26 入力試験 13 件、API 35 入力 13 件・設定 1 件・狭幅表示とキーボード操作 2 件・辞書 SAF 1 件が成功しました。

増分ビルドとは DEX 内容が異なるため、APK のバイト一致を検証結果として報告しません。クリーン版を別成果物として端末へ導入して確認しました。release は未署名のままで、一般公開はしていません。
