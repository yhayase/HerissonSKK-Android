# 実機の性能測定

## 測定用ビルド

`benchmark` は release の設定を継承し、デバッグ不可とした測定用 IME です。開発用の署名鍵を使い、別パッケージ `se.haya.skk.benchmark` と表示名「SKK（性能測定用）」でインストールします。日常の開発版 `se.haya.skk` は置き換えません。入力エンジンは `BasicSkkEngine` と SQLite の辞書管理器を使います。この起動・打鍵シナリオだけでは大辞書の検索性能を分離できないため、[辞書性能測定](dictionary-performance.md) も用います。

```sh
./gradlew :app:assembleBenchmark :test-editor:assembleDebug :test-editor:assembleDebugAndroidTest
python3 scripts/measure-device.py --serial <端末シリアル>
```

スクリプトは初回起動と起動済みをそれぞれ 30 回、準備入力後の打鍵を 1,000 回測定します。原因切り分けには `--startup-samples` を 1〜30、`--input-samples` を 1〜1,000 で指定して短いスモーク試験にできます。省略時は通常の 30／1,000 回です。instrumentation を直接起動する場合は同じ上限で `startup_samples` と `input_samples` を渡せます。

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

初回起動時間には IME の再選択要求も含まれます。Android は選択中の IME を強制停止すると別の IME に切り替えるため、先に待避します。`ime set` の終了だけでは以前の IME の切断完了を示さないため、待避先について InputMethodManager の選択、サービス接続、bind 完了を確認してから測定用 IME を停止します。停止後の測定用 IME 再選択から計時するため、測定用プロセスの起動時間は除外しません。両方の起動時間には入力確認 Activity の起動、ウィンドウと入力欄の準備待ち、アクセシビリティ観測の時間が含まれます。打鍵時間にはテストのキー注入と Android の配送時間が含まれます。`OnDraw` は描画処理の呼び出しであり、画面への提示完了時刻ではありません。また、物理 Bluetooth キーボードの通信遅延は含みません。現段階の値は比較用の代理指標とし、検証計画にある「IME の入力開始要求／キー受信から表示反映」の測定完了とは扱いません。正確な境界の計測と性能目標の固定は残っています。

接続完了の判定には `dumpsys input_method` の `mCurMethodId`、`mCurId`、`mHaveConnection`、`mBoundToMethod`、`mCurMethod` を使います。これは公開 API ではなく OS 版やメーカー実装で出力形式が変わる可能性があります。対応しない形式では10秒以内に診断値を付けて測定前に失敗し、待機時間を起動時間へ混ぜたり、失敗した標本を再試行したりしません。

入力欄は単一行で、合成文字が順に蓄積する条件です。デバッグ版の入力確認アプリと instrumentation の負荷も測定に含まれます。辞書導入前後・大辞書検索の測定は [辞書性能測定](dictionary-performance.md) と分担します。初回の両端末での代表測定と操作感の評価は [検証計画](validation-plan.md) に従います。

結果は `test-editor/build/reports/performance/<UTC日時>/` に保存します。`metadata.json` に端末情報、ソースコミットと未コミット変更の有無、APK の SHA-256、測定境界を記録します。`instrumentation.txt` に全サンプル、p50／p95／最大値、メモリ情報を記録します。入力先に既存の本文を用いず、合成文字だけで測定します。
