# APK 更新時の永続化検証

## 目的

同じ package・同じ署名の旧 APK を現行 APK で置き換えたとき、アプリ専用領域が保持され、辞書 schema v2 が schema v3 へ移行されることを専用エミュレーターで検証します。実利用の既定辞書や設定は試験データに使いません。

この試験が表すのは、旧版と同じ schema の独立 fixture を APK 更新の前後で保持・移行できることです。人が実際に利用している辞書や設定そのものを更新する試験ではありません。

## 前提

- 旧 APK は commit `17a5cf0` の debug APK を別作業ディレクトリで作成し、`--baseline-apk` へ明示します。
- 現行の app APK と test APK は通常の debug 出力へ事前に作成します。実行スクリプトは Gradle を起動しません。
- 試験対象以外の有効な IME が一つ以上ある専用エミュレーターを使います。
- Android SDK の `aapt` と `apksigner`、および `adb` を利用可能にします。

実行例は次のとおりです。

```console
python3 scripts/test-upgrade-emulator.py \
  --serial emulator-5554 \
  --baseline-apk /path/to/baseline/app/build/outputs/apk/debug/app-debug.apk
```

## 事前検査と端末保護

スクリプトは端末を変更する前に、三つの APK をローカルで検査します。baseline と現行 app の package が `se.haya.skk`、test APK の package が `se.haya.skk.test` であること、test APK の instrumentation runner と target package、三つの署名証明書 SHA-256 が一致すること、baseline の versionCode が現行以下であること、baseline と現行 APK の内容が異なることを確認します。test APK は Android の標準出力で versionCode と versionName が空になるため、この二項目を app APK だけに必須とします。一つでも確認できない場合は端末へ APK を導入しません。

端末上では `ro.kernel.qemu=1` を確認した後、現在選択中の IME と試験対象 IME の有効状態を記録します。試験対象が選択中なら別の IME へ切り替え、試験対象 IME を無効化し、状態が安定した後に対象プロセスを停止します。`pidof` でプロセス終了を確認してから baseline を導入します。これにより、schema v3 の実利用既定 DB を旧 APK が開く経路を遮断します。

baseline 導入を試みた後は、試験の成否にかかわらず現行 app APK を再導入してから IME 状態を戻します。現行 APK を復元できなかった場合は試験対象 IME を無効のままにして終了します。`pm clear` と uninstall は使用しません。

## fixture と検証内容

実行ごとにホスト側で正規 UUID を生成し、次の固定形式のアプリ専用パスだけを使います。

| 種類 | パス |
| --- | --- |
| 辞書 | `databases/upgrade-<UUID>.db` |
| カスタマイズ | `files/upgrade-<UUID>-customization.json` |
| 非 DB sentinel | `files/upgrade-<UUID>-sentinel.bin` |

更新前のシード処理は Android の SQLite・JSON・ファイル API だけを使います。現行版の製品クラスを参照する検証クラスとは分離してあるため、旧 APK 上で新しい製品 API を解決しません。

辞書 fixture は schema v2 として、次を保存します。

- 注釈・候補順・送り条件を持つ個人候補と個人辞書世代 7
- 有効な優先システム辞書と無効なシステム辞書、およびその順序と世代
- システム候補一件の非表示指定
- 削除済みソース `deleted-ledger` の最終世代 7

現行 APK への `install -r` 後、現行の `SQLiteDictionaryRepository` で同じ独立 DB を開きます。候補、注釈、送り条件、ソース順、有効状態、非表示指定が保持され、DB version が 3 になったことを確認します。さらに `deleted-ledger` を再取り込みし、台帳から世代 8 が割り当てられることを確認します。

カスタマイズ fixture は documentVersion 2、世代 5 の非既定句読点・括弧・候補表示を保存します。更新後に現行の `CustomizationStore` で読み込み、破損扱いにならず全設定が保持されたことを確認します。sentinel は UUID と実行時の乱数、およびその SHA-256 を含み、更新後に同じ自己検証可能な byte 列が残ることを確認します。

終了時は、その実行で生成した UUID から組み立てた上記 DB、SQLite 補助ファイル、カスタマイズの AtomicFile 補助ファイル、sentinel だけを削除します。instrumentation 引数の `fixture_id` も正規 UUID 以外は拒否します。

## 結果記録

結果は `app/build/reports/upgrade-persistence/<serial>/<UTC日時>/` に保存します。`preflight.json` と `metadata.json` には APK のパス・SHA-256・package・version・署名証明書 SHA-256、ソース commit と dirty 状態、端末 API・fingerprint、runner と導入オプション、IME 復元条件、完了 phase、復旧結果を記録します。各導入処理と instrumentation の生出力も同じディレクトリへ保存します。

instrumentation の成功判定は、一件の test について開始・成功 status が厳密に一組あり、`OK (1 test)` と正常終了 code がある場合だけ成功とします。スキップ、途中終了、複数 test、失敗 status は成功として扱いません。

## 署名が異なる APK への切替

この試験は同じアプリ ID と署名による更新を対象とします。署名が異なる APK は上書き更新できないため、[辞書の退避・復元手順](signing-switch.md)に従います。
