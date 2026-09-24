# SCANOSS の4検出に関する由来と限界（2026-09-23）

## 対象と当初の不確実性

「未確認」は違反や流用の検出を意味しません。3件は SCANOSS が示した比較先の本文を取得できず、1件は短い XML の完全一致だけでは作成元を判定できませんでした。

| 対象 | ファイルの役割 | 最初の検出と懸念 |
| --- | --- | --- |
| `app/src/main/AndroidManifest.xml` | IME サービス・設定画面・必要権限等を OS に宣言 | Stack Overflow 改訂版 `70725406/1` との部分一致。比較先本文が取得できず、CC-BY-SA-4.0 の表示が該当部分に関係するか未確認 |
| `app/src/main/java/se/haya/skk/SystemInsets.kt` | ステータスバー等の表示色・画面余白を調整 | `aliencodingjava/my-app-update` の部分一致。比較先本文が取得できず、SCANOSS の EPL/GPL/Apache 表示の適用先が未確認 |
| `app/src/test/java/se/haya/skk/ServiceConfigurationTest.kt` | パスワード入力欄の保護や IME 権限等を検証するテスト | PassButler の部分一致。比較先本文が取得できず、AGPL 表示がローカルのテストに関係するか未確認。本番 APK の実装ではないが、ソース公開時には確認対象 |
| `app/src/main/res/xml/data_extraction_rules.xml` | クラウドバックアップ・端末間移行の除外設定 | AusweisApp の設定と630 bytesすべて一致。作成元が分からず、単なる定型設定の一致と複製を区別できなかった |

SCANOSS の割合はコピーの確率ではなく、比較元のライセンス名だけで本体への適用を決めることもできません。

## バックアップ XML：生成の根拠

[証拠一覧](scanoss-20260923-followup-evidence.json)が特定する `2026-09-11T13:32:34.723Z` のパッチは、Android Lint の `DataExtractionRules` 警告に対応して XML と manifest 属性を追加しています。パッチから復元した XML は初回コミット `ae1d187` と現行ファイルにバイト単位で一致します。パッチ前の対象記録に AusweisApp / Governikus への言及はありません。

この設定は Android の公式スキーマで、2種類のバックアップに対して5種類の保存領域を除外する構成です。[Android のバックアップ設定仕様](https://developer.android.com/identity/data/autobackup)

Lint 警告、生成パッチ、初回コミット、現行ファイルがつながるため、ローカルで生成した標準設定と判断します。AusweisApp との完全一致は残る事実です。対象記録にない過去の接触やモデルの学習由来まで排除する証明ではありません。

SCANOSS は MIT を返しましたが、[AusweisApp 1.26.5 の LICENSE.txt](https://raw.githubusercontent.com/governikus/ausweisapp/1.26.5/LICENSE.txt) はソースを EUPL v1.2 と記載しています。MIT は利用ライブラリのライセンス付録に含まれるものです。SCANOSS の表示を上流本体の条件として採用したのは不正確でした。この訂正だけでローカル XML に EUPL が適用されると判断するものではありません。

作成操作の識別情報とファイルハッシュは[証拠一覧](scanoss-20260923-followup-evidence.json)にあります。

## Manifest：公式の定型宣言とローカルの根拠

Stack Exchange 公式 API は対象改訂版に HTTP 200 と空の `items` を返し、通常ページ、StackPrinter、検索・公開アーカイブからも比較本文を取得できませんでした。投稿の削除やアーカイブの不存在を示す結果ではありません。

[Android 公式 IME ガイド](https://developer.android.com/develop/ui/views/touch-and-input/creating-input-method) には、`BIND_INPUT_METHOD`、`android.view.InputMethod`、`android.view.im` 等を使う同じ基本宣言があります。`queries` も [Android の標準宣言](https://developer.android.com/guide/topics/manifest/queries-element) です。ローカルでは IME 登録部分が初回コミット `ae1d187`、アプリ一覧用宣言などが後続コミットで追加されており、単一の外部断片をそのまま追加したことを示す Git 記録ではありません。

[証拠一覧](scanoss-20260923-followup-evidence.json)が特定するパッチには、Android 公式 IME ガイドを参照した後に標準宣言を追加した順序が残ります。Git の段階的な追加とも整合し、ローカルで構成した標準宣言と判断します。指定された比較先本文との直接照合はできておらず、許諾・表示の追加を要する具体的な流用も確認できていません。

## SystemInsets：変更の根拠

比較先の公開リポジトリ一覧、移転候補、パッケージ名・ファイル名からは指定版の本文を取得できませんでした。

ローカルのパッチと Git 差分は、9月11日の余白調整関数とシステムバー設定、9月22日の `MaterialColors` を使うテーマ対応の追加を示します。後者の差分が検出範囲9–28行の実質的な内容と一致します。

[View の旧表示フラグ](https://developer.android.com/reference/android/view/View) と [WindowInsetsController](https://developer.android.com/reference/android/view/WindowInsetsController) は Android 標準 API です。対象コードはこれらを OS バージョンと Material テーマに応じて使い分け、ローカルの段階的な差分と整合します。比較元の上流603–622行との直接比較と、そのファイル固有のライセンス確認はできていません。本文を取得できないことだけを流用の根拠とは扱いません。

## ServiceConfigurationTest：何を検証するコードか

検出されたローカル6–26行は、JUnit / Robolectric の import・テスト用注釈と、`passwordVariantsAreProtected` という試験の一部です。試験では、通常・表示可能・Web・数値のパスワード欄を、本 IME の `SkkInputMethodService.isPassword` が正しく判定するか確認します。通常のテキスト欄や URL 入力欄をパスワード扱いしないことも検証します。

ここに並ぶパスワード等の定数はアプリ独自の定義ではなく、[Android の InputType](https://developer.android.com/reference/android/text/InputType) が公開するものです。自動入力アプリと IME の両方がこれらを使うことは自然です。ただし、これがこのフィンガープリント一致の直接の原因だったという説明は、比較元本文を取得していないため推定です。

公開リポジトリの記録と GitHub イベントアーカイブには比較先の過去のコミット識別子がありますが、該当 `StructureParser.kt` の本文は取得できていません。リポジトリ情報やタグ一覧は本文の代わりにはなりません。

[証拠一覧](scanoss-20260923-followup-evidence.json)が特定する `2026-09-11T13:27:48.631Z` のパッチは、この試験を他の IME 試験3件とともに追加し、パスワード4種と通常テキストの判定を含みます。初回コミット `ae1d187`、URI 等の判定を加えた `f279fab`、現行ファイルの検出範囲はその内容と整合します。

判断は **共通のテスト枠組み・Android API を使ったプロジェクト固有の試験であり、実質的なコード流用の証拠なし** です。比較元の5–25行が未取得なので「誤検知が確定した」「AGPL に関する可能性を完全に排除した」とまでは言いません。AGPL というリポジトリの表示と短い部分一致だけを理由に、この試験を削除したり書き換えたりする根拠はありません。

## 判断と限界

| 対象 | 追加調査後の判断 | 残る限界 |
| --- | --- | --- |
| Manifest | 公式仕様に沿ってローカルで構成した標準宣言 | Stack Overflow の指定本文との直接比較は未実施 |
| SystemInsets | 標準 Android API を使った段階的なローカル実装 | 指定版の上流本文との直接比較は未実施 |
| ServiceConfigurationTest | 共通 API・テスト枠組みによる固有の試験。実質的流用の証拠なし | 比較元未取得のため、誤検知と断定する直接比較は未実施 |
| バックアップ XML | Lint 対応でローカル生成した標準設定 | 完全一致は事実。対象のパッチ・Git 記録を越えた絶対的な独立性の証明ではない |

**対象4件について、修正・追加の許諾表示を要求する具体的な流用は確認されませんでした。** 比較元を取得できない3件は監査の限界として残しますが、それだけを理由にリリース阻害事項として扱いません。上流本文を後日入手できた場合や、別の具体的な流用証拠が出た場合は再評価します。

本体への MIT 採用全体を無条件に保証する調査ではありません。既存の依存通知、辞書・画像等の条件整備は別途残ります。本資料は由来とライセンス条件の限定確認であり、ビルド・実機試験の結果ではありません。
