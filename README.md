# HerissonSKK (for Android)

Kotlinで開発するAndroid向けSKK入力メソッドです。物理キーボード・画面QWERTY、変換、辞書管理、再帰登録・学習、補完・数値変換、入力設定、AZIK、Emacs編集を実装しています。利用者が気になる振舞いへの対応は受入済みで、未解決試験への対応と公開準備が残ります。

## 制作と AI の利用

HerissonSKK (for Android) は Yasuhiro Hayase が制作・公開するプロジェクトです。開発には OpenAI Codex を使用し、コード・テスト・文書の作成や修正、調査・レビュー・検証作業に AI を活用しています。作者が仕様や採用する変更を判断し、動作を確認しており、公開内容に対する最終的な責任は作者が負います。

アイコンは ChatGPT の画像生成で作成し、Codex を用いて品質調整とアイコン用の整形を行い、作者が選定したものです。兄弟プロジェクトと同一の原本を使用しています。[アイコンの出典とライセンス](docs/icon-provenance.md)に記録しています。

この説明は開発・制作時の AI 利用についてです。アプリの日本語変換は取得済みの SKK 辞書を使って端末内で処理し、入力内容や登録語を AI サービスへ送信しません。

## 名称・ライセンス・配布方針

名称と[アイコン原本](HerissonSKK-icon-refined.png)をアプリの表示名・ランチャーアイコンへ反映しました。本体の独自コード・独自文書・指定のハリネズミのアプリアイコンは [MIT ライセンス](LICENSE)です。第三者部分と外部辞書にはそれぞれの条件が適用されます。[適用範囲](core/src/main/resources/META-INF/license-scope.txt)と[調査・整備記録](docs/licenses.md)を参照してください。Google Play で配布できるよう準備する方針ですが、Play 準備への着手は保留しています。

- [ドキュメント案内](docs/README.md)：各文書の役割と正本
- [利用手順](docs/usage.md)：導入、キー操作、辞書・設定
- [ロードマップ](docs/roadmap.md)：初回範囲と後続の順序
- [要件と試験の対応](docs/test-coverage.md)：実装証拠と残る条件

開発・手元の端末での利用は、開発者登録なしの ADB 導入から始めます。一般公開と配布手続きは別の到達点として扱います。

## ビルド

JDK 17 以上（検証環境は JDK 21）、Android SDK Platform 35、Build Tools 35.0.0 を用意します。SDK の場所は `ANDROID_HOME` または Git 管理外の `local.properties` の `sdk.dir` に指定します。

```sh
python3 scripts/verify-local.py
```

単体試験・ホスト試験・lint・APK の通知と権限を検査し、結果を `build/reports/local-verification/` へ保存します。既存キャッシュだけを使う場合は `--offline` を指定します。[配布・更新手順](docs/distribution.md) と [旧版からの更新検証](docs/upgrade-validation.md) も参照します。

APK は `app/build/outputs/apk/debug/app-debug.apk` に生成します。Gradle 8.11.1、Android Gradle Plugin 8.9.2、Kotlin 2.1.20 を固定しています。対応下限は Android 8.0（API 26）、compileSdk／targetSdk は 35 です。一般公開時の targetSdk は公開要件に合わせて別途見直します。

## エミュレーターの結合試験

```sh
./gradlew :app:assembleDebug :test-editor:assembleDebug :test-editor:assembleDebugAndroidTest
python3 scripts/test-emulator.py --serial emulator-5580
```

起動済みの専用エミュレーターを指定します。スクリプトは APK を導入し、試験中だけ本 IME を選択して、終了時に以前の入力方法へ戻します。実機はこのスクリプトの対象外です。入力確認と instrumentation は IME とは別のアプリで動作します。IME の登録・選択まで含めて再現するため、ここでは APK をビルドしてからスクリプトで直接 instrumentation を実行します。

専用エミュレーターでは「入力モード・候補を表示する」を有効にします（初期値）。再インストールは設定を保持するため、無効に変更したままだと試験の準備待ちが失敗します。実行結果とAPKのSHA-256は `test-editor/build/reports/connection/` 以下へ端末別に保存します。
