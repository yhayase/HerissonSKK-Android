# 実機の性能測定

## 測定用ビルド

`benchmark` は release の設定を継承し、デバッグ不可とした測定用 IME です。開発用の署名鍵を使い、別パッケージ `jp.hayase.skk.benchmark` と表示名「SKK（性能測定用）」でインストールします。日常の開発版 `jp.hayase.skk` は置き換えません。入力エンジンは同じ固定候補の `PilotEngine` であり、実辞書の検索性能は測りません。

```sh
./gradlew :app:assembleBenchmark :test-editor:assembleDebug :test-editor:assembleDebugAndroidTest
python3 scripts/measure-device.py --serial <端末シリアル>
```

測定中は端末をロックせず、他の入力操作を行わないでください。スクリプトは指定端末に測定用 APK を導入し、測定用 IME を一時的に選択します。終了時は入力確認アプリを停止し、以前の IME 選択と測定用 IME の有効状態を戻します。端末切断やスクリプトの強制終了では復旧処理を完了できない場合があります。その場合は端末の入力方法選択から元の IME に戻します。APK は端末に残ります。

USB インストールが拒否される場合は、次の 3 ファイルを端末のファイルアプリから導入し、`--skip-install` を付けます。手動導入の場合も端末上の APK とローカル APK の SHA-256 が一致することを確認してから測定します。

- `app/build/outputs/apk/benchmark/app-benchmark.apk`
- `test-editor/build/outputs/apk/debug/test-editor-debug.apk`
- `test-editor/build/outputs/apk/androidTest/debug/test-editor-debug-androidTest.apk`

## 測る値と限界

| 指標 | 条件・測定境界 |
| --- | --- |
| 初回起動 30 回 | 別の有効な IME に切り替えてから測定用 IME を停止し、プロセス不在を確認します。IME の再選択要求から、入力確認 Activity を起動し、IME の「かな」表示をアクセシビリティで観測するまでを測ります |
| 起動済み 30 回 | IME プロセスが存在することを確認し、入力確認 Activity の起動要求から同じ表示の観測までを測ります |
| 打鍵 1,000 回 | 20 回の準備入力後、検索欄へ合成キーを注入し、文字数が増えた次の `OnDraw` までを測ります。各入力の結果が合成した期待文字列と一致することも確認します |
| メモリ | 連続入力後と入力確認 Activity を閉じた後に、測定用 IME の `dumpsys meminfo` を取得します |

初回起動時間には IME の再選択要求も含まれます。Android は選択中の IME を強制停止すると別の IME に切り替えるため、先に待避して自動切替との競合を避け、停止後に測定用 IME を再選択します。両方の起動時間には入力確認 Activity の起動とアクセシビリティ観測の時間が含まれます。打鍵時間にはテストのキー注入と Android の配送時間が含まれます。`OnDraw` は描画処理の呼び出しであり、画面への提示完了時刻ではありません。また、物理 Bluetooth キーボードの通信遅延は含みません。現段階の値は比較用の代理指標とし、検証計画にある「IME の入力開始要求／キー受信から表示反映」の測定完了とは扱いません。正確な境界の計測と性能目標の固定は残っています。

入力欄は単一行で、合成文字が順に蓄積する条件です。デバッグ版の入力確認アプリと instrumentation の負荷も測定に含まれます。辞書導入前後の比較や大辞書検索は後続フェーズで追加します。

結果は `test-editor/build/reports/performance/<UTC日時>/` に保存します。`metadata.json` に端末情報、ソースコミットと未コミット変更の有無、APK の SHA-256、測定境界を記録します。`instrumentation.txt` に全サンプル、p50／p95／最大値、メモリ情報を記録します。入力先に既存の本文を用いず、合成文字だけで測定します。

## 現在の検証状態（2026-09-12）

測定用 APK と instrumentation APK のビルド、既存単体テスト 47 件、両アプリの Lint が成功しました。Python スクリプトの構文を確認しました。API 30 の専用エミュレーターで、修正後の初回起動 30 回、起動済み 30 回、合成入力 1,000 回を含む測定全体が成功しました。エミュレーターの数値は実機の評価には使用しません。

Xiaomi Pad 8 への USB インストールは `INSTALL_FAILED_USER_RESTRICTED: Install canceled by user` で拒否されました。実際にユーザーが取消を操作したとは断定しません。手動導入用に 3 APK を `/sdcard/Download/skk-performance/` へ転送済みです。ユーザーによる手動導入後、APK の照合に成功しました。修正した instrumentation APK は ADB で更新できました。Xiaomi Pad 8 の再実行では、入力確認アプリのウィンドウを観測できず IME 表示待ちで失敗しました。原因の切り分けは残っています。終了後、以前の ATOK が選択されていることを確認しました。実機の性能の合格判定はまだ行っていません。
