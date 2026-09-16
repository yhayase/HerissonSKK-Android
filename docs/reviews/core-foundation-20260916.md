# コア内部編集・Unicode 境界の独立レビュー

実施日: 2026-09-16。対象は `EditableBuffer.kt`、関連 JVM テスト、Unicode 16 公式テストデータ、ICU4J の依存・出典記録、`CoreUnicodeTest.kt` とエミュレーター試験への組み込みです。ローマ字処理と Android IME 本体へのコア接続は対象外です。

## 結論

初回の独自境界実装で再現した次の5件は、ICU4J 77.1 への置き換えとカーソル処理の修正で解消しました。修正後の対象コードに新しい正しさの問題は見つかっていません。JVM 上の合格を Android API 26 の実行確認や製品全体の完成とは扱いません。

## 指摘と解消確認

初回は JDK 17 からコンパイル済みの `EditableBuffer` を呼ぶ独立した Java プローブで再現しました。修正後も同じプローブを実行しました。

| 指摘 | 初回の再現と影響 | 修正後の観測 |
| --- | --- | --- |
| P1: 削除後の結合でカーソルがクラスタ内部へ残る | `EditableBuffer("\u1100x\u1161", 2)` の BS 後、Hangul L/V が結合する一方でカーソルは1のままで、次の右移動が例外になります。Delete も同じ構造です | 削除後の境界を再計算し、カーソルは2です。次の移動で例外になりません。両削除方向と地域指標の結合を回帰テストへ追加しています |
| P1: End が CRLF を分断する | `EditableBuffer("a\r\nb", 0)` の End が CR と LF の間の2へ移動し、BS が例外になります | End は改行前の1へ止まります。右移動は CRLF 全体を越えて3へ進みます |
| P2: かなを絵文字と誤判定して過剰削除する | `あ\u200Dい` の末尾 BS が全体を削除します。独自の Extended_Pictographic 範囲にかなが含まれていました | BS は末尾の `い` だけを削除し、`あ\u200D` が残ります |
| P2: 絵文字タグ列を分断する | England の旗の末尾 BS が U+E007F だけを削除し、不完全なタグ列を残します。FORMAT を先に Control と扱っていました | 旗のタグ列全体を一度に削除します |
| P2: インド文字の結合を分断する | `\u0915\u094D\u0937`（क्ष）の末尾 BS が `क्` を残します。GB9c がありませんでした | 結合全体を一度に削除します |

追加で ZWNJ を含む `a\u200C` の一括削除も確認しました。挿入前の UTF-16 検証は状態変更前に行われ、不正サロゲートを拒否して元の本文・カーソルを保持します。Home/End は CR/LF を論理行の区切りとして扱い、移動先の境界も検査します。

## テストの独立性・出典

- JVM の生成結果 XML では内部編集14テスト、公式境界テスト1テストが失敗・エラー・スキップ0件です。内部編集テストには固定 seed による2,000回の編集・移動後の境界検査を含みます。この生成試験は完全な操作網羅ではありません。
- 公式境界テストは Unicode 16 の `÷` と `×` を読み、UTF-16 位置の期待リストを組み立てます。前進で得た境界リスト全体と比較し、後退も全期待位置を検査しています。期待値を ICU から生成していないため、自己一致だけの試験ではありません。
- 取得済み公式データは1,093ケースです。SHA-256 は `ee2b9354d270ac061b29f09662cafea06341d77e704b8cc6bd72aaeeda363cb5` で、README の記録と一致しました。ヘッダー・利用条件の参照を保持しています。
- `META-INF/icu-LICENSE.txt` の SHA-256 は `451167c55c0fa447cc2d5632714f5e3c567fe4f1e1badefab2c1333852198aca` で README と一致しました。依存は `com.ibm.icu:icu4j:77.1` に固定されています。配布 APK への実際のライセンス収録は今回確認していません。
- `CoreUnicodeTest` は Android 上で結合濁点、ZWJ 絵文字、国旗、インド文字の削除結果と、Hangul 削除・CRLF 移動の具体的なカーソル位置を検査します。`androidTestImplementation(project(":core"))` と runner の明示的クラス一覧への追加を確認しました。これはテスト APK 内で ICU を読み込む試験であり、IME 本体への接続完了を示しません。

公式コーパスの欠落検出は当初 `cases > 1000` で、1,001〜1,092件への欠落でも通る点を追加指摘しました。固定版の正確な1,093件との一致へ修正済みです。現在のデータが完全であることは件数とハッシュを独立に確認しました。件数 assertion 変更後の該当テスト再実行は主担当が行います。

## 検証の限界

レビュー担当は Gradle を並列起動せず、生成済み XML の確認、ソース検査、修正前後の独立プローブを行いました。主担当から APK ビルドと Lint 成功の報告を受けました。API 30 のエミュレーターで PhysicalInput 3件と CoreUnicode 2件を実行し、5件成功・スキップ0件となった出力を `test-editor/build/reports/emulator-instrumentation.txt` で確認しました。API 26 での実行、縮小・最適化後の配布ビルド、APK 容量への影響、IME の入力先に対する編集・確定・キー配送は、このレビューの合格範囲に含めません。

根拠資料: [ICU 77 の Unicode 16・Android 対応説明](https://unicode-org.github.io/icu/download/77.html)、[ICU の境界判定](https://unicode-org.github.io/icu/userguide/boundaryanalysis/)、[Unicode の書記素境界仕様](https://unicode.org/reports/tr29/)。OS 内蔵 ICU は Android 版ごとに異なるため、同じ固定 ICU4J を JVM と Android で使用する判断は今回の再現性要件に適合します。[Android の Unicode 対応](https://developer.android.com/guide/topics/resources/internationalization)
