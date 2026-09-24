# 開発ガイド

## 開発環境と構成

JDK 17 以上、Android SDK Platform 36、Build Tools 35.0.0、Python 3 とリポジトリの Gradle Wrapper を使います。SDK の場所は各自の Git 管理外 `local.properties` の `sdk.dir` または `ANDROID_HOME` で指定します。`core` は Android に依存しない入力・辞書契約、`app` は IME・設定・保存・Android 接続、`test-editor` は別アプリとの入力試験を担当します。現行の `minSdk`、`compileSdk`、`targetSdk`、依存版は各モジュールの Gradle 定義を確認します。

```sh
./gradlew :core:test
./gradlew :app:testDebugUnitTest -Probolectric.enabledSdks=26
./gradlew :app:lintDebug :app:assembleDebug \
  :test-editor:lintDebug :test-editor:assembleDebug :test-editor:assembleDebugAndroidTest
python3 scripts/verify-local.py
```

`verify-local.py` は単体・ホスト試験、Lint、配布物の通知・権限を検査し、結果を `build/reports/local-verification/` へ保存します。既存キャッシュだけで実行する場合は `--offline` を付けます。debug APK は `app/build/outputs/apk/debug/app-debug.apk` に生成します。

別アプリとの入力経路を専用エミュレーターで確認する場合は、APK を作成してからシリアルを指定します。

```sh
./gradlew :app:assembleDebug :test-editor:assembleDebug :test-editor:assembleDebugAndroidTest
python3 scripts/test-emulator.py --serial emulator-5554
```

入力確認と instrumentation は IME とは別の `test-editor` アプリで動作します。日常利用データのない専用エミュレーターを指定します。ランナーは試験中だけ対象 IME を選択し、終了時に元の入力方法へ戻します。終了後は復元結果を確認します。実機はこのランナーの対象外です。エミュレーターでは「入力モード・候補を表示する」を有効にします（初期値）。APK の再導入でも設定は残るため、無効にしたままだと準備待ちが失敗します。

上の単体試験とビルドは Android の実入力配送を検証しません。専用エミュレーターでの入力、辞書、表示、更新試験は[検証手順](validation-plan.md)と各ランナーの `--help` を参照します。指定する端末のシリアルを確認し、試験前後の IME・辞書・設定の復元結果を確認します。実機の性能は[起動・打鍵測定](performance.md)、辞書は[辞書性能測定](dictionary-performance.md)、同一 package・署名の APK 更新は[更新検証](upgrade-validation.md)に従います。実機、エミュレーター、Robolectric の結果は区別します。

## 変更と検証

変更する振る舞いの期待結果は[要件](requirements.md)、[互換性仕様](compatibility.md)、担当する[設計](README.md)から決めます。参照実装の出力や変更中のコードを、そのまま期待値にしません。正常系に加え、取消、入力先の変更、遅い応答、保存失敗など変更範囲に関係する境界を検証します。既存データを扱う試験は独立した fixture を使い、失敗時の復旧も成功条件に含めます。

関連試験、必要な結合試験、Lint、ビルドを実行し、結果を対象コミット・成果物と結び付けて PR に記録します。テスト件数や網羅率だけを品質の判定にしません。入力消失、二重確定、誤送信、別の入力欄への誤挿入、辞書破損などの重大な回帰は解消してから完了とします。合格済みの無関係な試験を、成果物ハッシュが変わったという理由だけで繰り返す必要はありません。

## 文書の更新

利用者の操作は[利用手順](usage.md)、要求は[要件](requirements.md)、観察可能な操作差は[互換性仕様](compatibility.md)、内部契約と採用理由は担当設計、検証方法は[検証手順](validation-plan.md)、公開方法は[配布手順](distribution.md)を正本とします。要件の F／S／N／I／U などの識別子を維持し、他文書には必要な要約とリンクだけを置きます。試験対応表は要件と試験の関係を示し、現行版での合格宣言には使いません。

作業経緯、担当者・モデル、日々の成功件数や次の作業は docs に追記しません。未解決の個別事項は Issue、変更と検証結果は PR・CI の成果物、公開版固有の判断は対象版のリリース記録に置きます。ライセンス・出典の版付き証拠は[監査資料](audit/README.md)で保持します。文書の末尾に日付付きの進捗を積まず、読者に必要な現行の説明を更新します。
