# HerissonSKK (for Android)

HerissonSKK は、Android 向けの SKK 日本語入力アプリです。物理キーボードと画面 QWERTY で、かな漢字変換、単語登録、学習、補完、辞書管理を利用できます。入力規則やキー操作、候補表示も設定できます。

対応下限は Android 8.0（API 26）です。アプリ ID は `se.haya.skk` です。Google Play での配布はまだ始めていません。ソースから試す場合は [開発ガイド](docs/development.md)に従って APK を作成し、[利用手順](docs/usage.md)から IME の有効化と辞書の準備を行ってください。

変換と学習は端末内で処理します。初期設定で基本辞書がない場合は、公式の S 辞書をネットワークから取得します。辞書取得時の通信、バックアップ、クリップボードの扱いは[プライバシーポリシー](https://yhayase.github.io/HerissonSKK-Android/privacy-policy.html)に記載しています。

本体の独自コードと独自文書は [MIT ライセンス](LICENSE)です。ハリネズミのアプリアイコンには[別の利用条件](core/src/main/resources/META-INF/icon-usage.txt)が適用されます。第三者ライブラリと外部辞書にはそれぞれの条件があります。[ライセンスと出典](docs/licenses.md)、[アイコンの出典](docs/icon-provenance.md)を参照してください。

作者は Yasuhiro Hayase です。開発には OpenAI Codex を使用し、コード・試験・文書の作成や調査に AI を活用しています。アイコンは ChatGPT の画像生成を使って制作し、Codex で整形しました。アプリは入力内容や登録語を AI サービスへ送信しません。

開発に参加する場合は、[開発ガイド](docs/development.md)と[ドキュメント案内](docs/README.md)を参照してください。
