# Google Play 掲載用スクリーンショット

作成日: 2026-09-23。Google Play Console への登録は未実施です。

## 画像

- [`assets/store/screenshots/01-input-keyboard-candidates.png`](../assets/store/screenshots/01-input-keyboard-candidates.png): Android の設定検索欄で架空の読み「にほん」を入力し、画面キーボードと候補「日本」を表示しています。システム設定アプリの一般入力欄を使い、個人のメッセージや連絡先は含めていません。
- [`assets/store/screenshots/02-dictionary-management.png`](../assets/store/screenshots/02-dictionary-management.png): アプリの辞書管理画面です。通常の初期設定で導入した `SKK-JISYO.S`、優先順位、配布元を表示しています。

いずれも実際の Android 画面を撮影した縦長 PNG です。寸法は1080×2160ピクセル、24-bit RGB、アルファチャンネルなしです。Play の通常要件である各辺320〜3840ピクセル、長辺が短辺の2倍以内に適合します。掲載に必要な最低2枚を用意しました。スマートフォンのおすすめ表示で推奨される4枚には、あと2枚必要です。

## 撮影方法と検証

撮影には API 36 の専用 `skk-api36` エミュレーターを使いました。表示領域を撮影中だけ1080×2160、420 dpiに設定し、Android の画面キャプチャを取りました。Play の寸法条件を満たすための表示領域設定で、画像の切り抜き、合成、拡大縮小、文字や画面要素の追加は行っていません。キャプチャの全ピクセルを保ったまま、全画素が不透明であることを確認してアルファチャンネルだけを除きました。

画面には同じソース作業セット `19cef1b3` の release APK を使いました。APK は未署名成果物 `app-release-unsigned.apk`（SHA-256: `b34b340102269d72a075a36287de7c837e64463311351fe8d74e76d89578f845`）を、専用エミュレーターへの一時導入に限って開発用署名で署名したものです。debug 版の限定試験辞書は含まれません。辞書画面で配布元 `skk-dev.github.io` を確認し、初期設定が取得した公式 `SKK-JISYO.S` の候補を撮影に使いました。

撮影後は APK を元の debug 版へ戻し、既存の `scripts/test-customization-emulator.py` の `restore_state()` と同じ手順で入力設定ファイルと辞書データベースを撮影前の状態へ復元しました。初期設定で作成された設定ファイルも撮影前に存在しなかったため削除しました。既定IME、IME有効一覧、Android の「ハードウェアキーボード接続中も画面キーボードを表示」設定、エミュレーターの画面寸法と密度が撮影前の値へ戻ったことを確認しました。復旧記録は `/tmp/skk-android-tools/play-store-capture-state/emulator-5588/` にあります。

## 公式仕様

- [Google Play のプレビュー素材要件](https://support.google.com/googleplay/android-developer/answer/9866151?hl=en-GB): スクリーンショットの形式・寸法、掲載上の指針。

この2枚は最低枚数の準備用です。独立した画像レビューでは、1枚目の画面キーボード上の候補欄に「日本」が表示され、2枚目の辞書管理画面に `SKK-JISYO.S` と配布元が表示されていることを確認しました。1枚目の設定検索結果には「No results for にほん」と表示されますが、入力先アプリの検索結果であり、キーボードの変換候補とは別です。Console での登録・受け付け確認は未実施です。
