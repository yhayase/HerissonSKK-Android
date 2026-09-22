# 設定画面の標準ウィジェットへの統一

## 目的

設定項目と現在値、セクションと操作行を区別できるようにし、Androidで覚えた設定操作をそのまま使える画面にします。標準部品で表現できる箇所に独自の見出しや入力ダイアログのレイアウトを作りません。

## 変更

- キー割り当てはEditTextPreferenceのtitleとsummaryに項目名と値を分けます。入力欄・確定・取消は既存のダイアログを使います。値の構文検証だけを標準ダイアログの拡張点に追加し、複数設定間の競合検証は従来どおり保存時に行います。
- キー割り当てとアプリ別Emacs編集の現在値は、summary内でテーマの主文字色を使い、読みにくい薄色を避けます。標準のレイアウトや文字サイズは変更しません。
- ローマ字、句読点、候補設定と辞書管理の見出しをPreferenceCategoryに揃え、通常の設定行や手作りTextViewを見出しに流用しません。
- 初期設定は入力方法の有効化・選択、辞書の選択の順にします。入力方法の有効・選択状態を表示します。S/LはListPreferenceの単一選択、追加辞書はCheckBoxPreferenceで選択します。完了ボタンは下部に固定します。
- 辞書管理の項目と文字コード選択をPreference/ListPreferenceに置き換えます。ドラッグはItemTouchHelper、追加はMaterial ComponentsのFloatingActionButtonを使います。
- 共通の画面構成は標準Toolbar、LinearLayout、FrameLayout、TextView、ButtonとAndroidの幅別リソースを使います。幅制限用の独自View/onMeasureを廃止します。既存の保存・破棄・標準化の意味は維持します。

## 必要な拡張の範囲

辞書行には標準Preferenceのアイコン領域を並べ替えハンドルとして使い、widget領域に標準ImageButtonを2個配置します。Preference自体にドラッグ・更新・削除の組合せがないためです。行の本文やセクションのレイアウトは自作しません。キー入力はEditTextPreferenceDialogFragmentCompatを継承して入力エラーと確定可否を更新しますが、入力欄やダイアログのレイアウトは標準のままです。複数フィールドを扱うローマ字規則の編集は既存のEditText/CheckBox/AlertDialogの組合せを維持します。

## 独立レビューと指摘の検証

実装担当と別の担当がレビューし、指摘は別担当が確認しました。

- キー入力ダイアログのtargetFragment設定と再生成可能なクラス公開が必要との指摘は妥当と判定し、修正しました。回転後の未確定編集保持も試験対象にしました。
- 操作が1個の画面でボタンが横幅全体に広がった点は独立確認し、単独時のwrap_contentを復元しました。
- キー割り当ての設定間競合を保存時に検証することは、既存の下書き編集の仕様どおりと判定しました。入力構文と必須値だけをダイアログで検証します。
- 辞書の並べ替えは標準Preferenceのアダプターへ移したため、3行の先頭から末尾・末尾から先頭へのドラッグと、実際の表示順・下書き順・未保存の永続値を確認する試験を追加しました。

- 辞書追加の＋が処理中も有効に見える指摘を独立確認し、処理状態との同期を修正しました。復元したファイル選択の待機中は無効、取消後は有効になることを試験します。

- 複数行ドラッグ試験で、1行の移動後にアダプターが古い順序を返す問題を再現しました。標準アダプターを公開APIで包み、ドラッグ中の表示位置と標準アダプターの位置の対応を保持・更新します。標準の見出し・項目レイアウトは維持します。画面再構築中に旧アダプターが新しいリストを読まないことも独立レビューで確認しました。
- 安定IDが表示順とずれるというレビュー指摘は、依存するPreference 1.2.1の実装を別途確認して誤検知と判定しました。不要なID管理は追加していません。
- エミュレーターの描画確認で、プログラムから作ったDialogPreferenceにタイトルを明示する必要があることを確認して修正しました。長いスイッチ名には標準の複数行表示オプションを指定します。

- PreferenceGroupAdapterの直接継承はRestrictedApiのlintで拒否されたため採用しません。公開APIだけで標準アダプターを包む方式へ変更します。lintを抑制して内部APIへ依存する回避は行いません。
- 担当の難易度は、キー入力ダイアログのライフサイクルと試験の手戻りでTerra/mediumからSol/mediumへ、ドラッグ中の順序同期と公開API境界の手戻りでSol/mediumからAstra/lowへ引き上げました。通常の標準ウィジェット置換と難しいアダプター連携を分けて担当しました。

## 参照

- [Android: 設定の分類と画面階層](https://developer.android.com/develop/ui/views/components/settings/organize-your-settings)
- [Android: 現在値のsummary表示と標準入力欄](https://developer.android.com/develop/ui/views/components/settings/customize-your-settings)
- [Android: Preference部品](https://developer.android.com/develop/ui/views/components/settings/components-and-attributes)

## 検証結果

- ローカルゲート `20260921T143413155962Z` は成功しました。コア312件、Android単体932件、ホスト26件、計1,270件が成功しました。debug/release lint、debug/release/benchmark APK、ライセンス通知・manifest・権限の検査も成功しました。
- API 26・35それぞれで辞書UI2件、設定編集1件、設定レイアウト3件、SAF辞書1件、完全バックアップ1件が成功しました。API 35のタブレット幅と文字サイズ2倍でレイアウト各3件も成功し、計22実行です。試験で退避した設定と画面条件の復元も確認しました。
- SAF試験の固定見出し「とうろく」が自動導入済みS辞書と衝突したため、試験だけをUUID由来の一意な英字見出しへ変更しました。単語登録、本文のEnter確定、SKK形式書き出しの検査は維持し、両APIで再実行して成功しました。変更後の試験APKのビルドとlintも成功しました。
- 320dpの設定一覧・キー入力ダイアログ・初期設定、1000dpのタブレット幅、320dpで文字サイズ2倍の画像を確認しました。画像は `build/reports/settings-standard-preview/` に保存しました。
- 配布物は `build/distributions/settings-standard-20260921-698f138c/` です。IME APKのSHA-256は `698f138ce5c1701eaf0cd3011aaa5cb56ae2930d6ecb587effb92074253523e3` です。12レポート・22実行のIME APKが同じであることと、対応する試験APKが保存されていることを `manifest.json` で検証しました。
- 実機での再確認・端末転送・本番署名・公開は行っていません。
