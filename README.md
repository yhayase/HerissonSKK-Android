# Android SKK IME

物理キーボードで使う Android 向け SKK 入力メソッドを Kotlin で開発します。現在は基本変換、ローカル辞書管理、再帰登録、通常候補の学習、数値変換を実装して検証しています。同梱辞書は限定された試験用データです。候補削除は実装しAPI差の検証を継続しています。高度な設定と全対象アプリの検証は未完了で、日常利用版の完成判定はしていません。

- [機能要件・非機能要件・設定要件](docs/requirements.md)
- [ロードマップ](docs/roadmap.md)
- [互換性と受入仕様](docs/compatibility.md)
- [Android 検証計画と未決事項](docs/validation-plan.md)
- [フェーズ 1 の実装・検証記録](docs/phase1.md)
- [ブラウザ版の実装・テストの活用評価](docs/reviews/browser-extension-20260916.md)
- [自律開発の手順と品質判定](docs/development-plan.md)
- [要件とテストの対応](docs/test-coverage.md)
- [コア設計](docs/core-design.md)
- [現在の作業と検証結果](docs/work-status.md)

開発・手元の端末での利用は、開発者登録なしの ADB 導入から始めます。一般公開と配布手続きは別の到達点として扱います。

## ビルド

JDK 17 以上（検証環境は JDK 21）、Android SDK Platform 35、Build Tools 35.0.0 を用意します。SDK の場所は `ANDROID_HOME` または Git 管理外の `local.properties` の `sdk.dir` に指定します。

```sh
./gradlew :core:test :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

APK は `app/build/outputs/apk/debug/app-debug.apk` に生成します。Gradle 8.11.1、Android Gradle Plugin 8.9.2、Kotlin 2.1.20 を固定しています。対応下限は Android 8.0（API 26）、compileSdk／targetSdk は 35 です。一般公開時の targetSdk は公開要件に合わせて別途見直します。

## 手元の端末への導入

```sh
adb devices -l
adb -s <端末シリアル> install -r app/build/outputs/apk/debug/app-debug.apk
```

端末で「SKK（開発版）」を開き、「入力方法の設定を開く」から有効化し、「入力方法を選ぶ」で選択します。別途 `:test-editor:assembleDebug` で作成する `test-editor/build/outputs/apk/debug/test-editor-debug.apk` を導入すると、「SKK 入力確認」アプリで外部送信のない入力欄を試せます。ソフトウェアキーボードは表示しません。

現在の確認用操作は `Ctrl+j`、`Nihon`、Space、Enter です。`日本` を確定でき、もう一度 Enter を押すと入力先の Enter として働きます。Space で `二本` へ、`x` で前候補へ戻せます。`KaKu` で `書く`、`/API` とSpaceで `エーピーアイ` を確認できます。`q` はひらがな／カタカナ、`Ctrl+q` は半角カナ、`l` は直接入力、`L` は全角英数へ切り替えます。見出し語中は矢印・Home/End・Backspace/Deleteで内部編集できます。単語登録と辞書管理は追加検証中です。完全なローマ字規則と高度な設定は今後の実装です。候補表示中の `X` で削除対象を確認し、`y` で削除・非表示、`n` で戻ります。システム候補の非表示は辞書管理画面から解除できます。

## エミュレーターの結合試験

```sh
./gradlew :app:assembleDebug :test-editor:assembleDebug :test-editor:assembleDebugAndroidTest
python3 scripts/test-emulator.py --serial emulator-5580
```

起動済みの専用エミュレーターを指定します。スクリプトは APK を導入し、試験中だけ本 IME を選択して、終了時に以前の入力方法へ戻します。実機はこのスクリプトの対象外です。入力確認と instrumentation は IME とは別のアプリで動作します。IME の登録・選択まで含めて再現するため、ここでは APK をビルドしてからスクリプトで直接 instrumentation を実行します。

専用エミュレーターでは「入力モード・候補を表示する」を有効にします（初期値）。再インストールは設定を保持するため、無効に変更したままだと試験の準備待ちが失敗します。実行結果とAPKのSHA-256は `test-editor/build/reports/connection/` 以下へ端末別に保存します。


## ローカル辞書と登録

設定画面の「辞書を管理する」から、UTF-8またはEUC-JPのSKKテキストを選びます。追加システム辞書は個人辞書と別に保存し、有効・無効、優先順、同じ辞書の更新、取り外しを扱います。適用前に文字コード・件数・重複を表示します。取り外しはアプリ内の対象システム辞書だけに作用し、選択元のファイルや個人辞書を削除しません。

個人辞書はマージと全置換を選べます。全置換前には「個人辞書を書き出す」で退避してください。書き出しはUTF-8・BOMなし・LFです。標準SKKテキストに含められない将来の抑止記録等を含む完全バックアップ形式とは区別します。

未登録語の変換は単語登録へ進みます。登録本文と内側の変換はIME内で表示し、最上位の保存成功後だけ入力先へ確定します。内側の読み・候補・残余があるEnterはその確定だけ、次のEnterは単語の保存です。Ctrl+gは内側から取り消します。保存中に入力欄を切り替えた場合、受理済みの保存は完了する場合がありますが、遅い完了が別の入力欄へ文字を挿入することはありません。

「入力から単語を登録・学習する」をオフにすると、新しい入力由来の保存と待機中の保存を抑止します。入力先の学習禁止要求でも登録保存を抑止します。設定画面から明示する辞書取り込みは別操作です。

保存・復旧の専用エミュレーター試験は次で実行します。各ケースはUUID名の試験DBだけを作成・削除します。

```sh
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest
python3 scripts/test-dictionary-emulator.py --serial emulator-5580
```

SAF画面・実IMEを通る退避付きE2Eは `scripts/test-saf-dictionary-emulator.py` で実行します。API26・30・35で成功しました。成功条件には元の個人辞書の再取り込みと、再書き出し後のバイト比較を含みます。復元失敗時はDownloadsのUUID名のbackupとrecovery JSONを残し、成功として扱いません。最新の合否は[継続記録](docs/work-status.md)を参照します。

数値変換は初期状態で有効です。数字を含む見出しを `#` に正規化して検索し、`#0`〜`#5`、`#8`、`#9` を展開します。学習と登録は元テンプレートを保存し、表示した展開結果だけを入力先へ確定します。詳細と資源上限は[数値変換仕様](docs/numeric-design.md)を参照します。
