# Android SKK IME

物理キーボードで使う Android 向け SKK 入力メソッドを Kotlin で開発します。現在はフェーズ 1 の接続確認用 IME を実装しています。完全な SKK 変換や辞書登録はまだ使えません。

- [機能要件・非機能要件・設定要件](docs/requirements.md)
- [ロードマップ](docs/roadmap.md)
- [互換性と受入仕様](docs/compatibility.md)
- [Android 検証計画と未決事項](docs/validation-plan.md)
- [フェーズ 1 の実装・検証記録](docs/phase1.md)
- [ブラウザ版の実装・テストの活用評価](docs/reviews/browser-extension-20260916.md)

開発・手元の端末での利用は、開発者登録なしの ADB 導入から始めます。一般公開と配布手続きは別の到達点として扱います。

## ビルド

JDK 17 以上（検証環境は JDK 21）、Android SDK Platform 35、Build Tools 35.0.0 を用意します。SDK の場所は `ANDROID_HOME` または Git 管理外の `local.properties` の `sdk.dir` に指定します。

```sh
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

APK は `app/build/outputs/apk/debug/app-debug.apk` に生成します。Gradle 8.11.1、Android Gradle Plugin 8.9.2、Kotlin 2.1.20 を固定しています。対応下限は Android 8.0（API 26）、compileSdk／targetSdk は 35 です。一般公開時の targetSdk は公開要件に合わせて別途見直します。

## 手元の端末への導入

```sh
adb devices -l
adb -s <端末シリアル> install -r app/build/outputs/apk/debug/app-debug.apk
```

端末で「SKK（開発版）」を開き、「入力方法の設定を開く」から有効化し、「入力方法を選ぶ」で選択します。別途 `:test-editor:assembleDebug` で作成する `test-editor/build/outputs/apk/debug/test-editor-debug.apk` を導入すると、「SKK 入力確認」アプリで外部送信のない入力欄を試せます。ソフトウェアキーボードは表示しません。

現在の確認用操作は `Ctrl+j`、`Nihon`、Space、Enter です。`日本` を確定でき、もう一度 Enter を押すと入力先の Enter として働きます。Space で `二本` へ、`x` で前候補へ戻せます。登録・学習・送り仮名・完全なローマ字規則は今後の実装です。

## エミュレーターの結合試験

```sh
./gradlew :app:assembleDebug :test-editor:assembleDebug :test-editor:assembleDebugAndroidTest
python3 scripts/test-emulator.py --serial emulator-5580
```

起動済みの専用エミュレーターを指定します。スクリプトは APK を導入し、試験中だけ本 IME を選択して、終了時に以前の入力方法へ戻します。実機はこのスクリプトの対象外です。入力確認と instrumentation は IME とは別のアプリで動作します。IME の登録・選択まで含めて再現するため、ここでは APK をビルドしてからスクリプトで直接 instrumentation を実行します。
