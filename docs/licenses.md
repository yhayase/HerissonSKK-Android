# ライセンスの適用範囲と出典

HerissonSKK 本体の独自コードと独自文書には [MIT ライセンス](../LICENSE)が適用されます。アプリ同梱の同一本文は [`HerissonSKK-MIT.txt`](../core/src/main/resources/META-INF/HerissonSKK-MIT.txt)です。ハリネズミのアプリアイコンは MIT の対象外で、[アイコンの利用条件](../core/src/main/resources/META-INF/icon-usage.txt)が適用されます。第三者ライブラリ・資料と外部辞書にはそれぞれの条件が残ります。アプリ内の「その他」→「ライセンス」から適用範囲、第三者通知、Apache、ICU、GPLv2 の本文、外部辞書の説明をオフラインで確認できます。

## 第三者ライブラリ

リリース時の依存物、正確な座標・版、出典と条件の正本はアプリ同梱の[第三者通知](../core/src/main/resources/META-INF/third-party-notices.txt)です。現在の通知は AndroidX、Material Components、Kotlin、ICU4J、Android-Iconics と Google Material Typeface 等を含みます。依存版の選択元はビルド定義です。通知に記載する Apache License 2.0 の対象物には [Apache 本文](../core/src/main/resources/META-INF/Apache-2.0.txt)を、ICU4J には [ICU の本文・第三者通知](../core/src/main/resources/META-INF/icu-LICENSE.txt)を収録します。第三者部分の条件を本体 MIT に置き換えません。

特定の依存グラフと成果物に対する確認方法・結果・ハッシュは[ライセンス監査資料](audit/README.md)に残します。調査時点の一覧は、変更後の依存物や公開 AAB を自動的に保証しません。POM のライセンス欄だけで判断しにくい `com.google.guava:listenablefuture:1.0` は、親 POM と[公式ソースのヘッダー](https://raw.githubusercontent.com/google/guava/v26.0/android/guava/src/com/google/common/util/concurrent/ListenableFuture.java)も根拠にしています。ICU の原文にある GPL の記載は ICU4C 用ファイルに関するもので、ICU4J 全体を GPL とする記載ではありません。

## 外部辞書

公式カタログの S、L、人名、地名辞書には GPL-2.0-or-later、郵便番号辞書データには public domain の指定があります。郵便番号辞書を生成するプログラムの GPL と、生成後の辞書データの指定は区別します。[アプリ同梱の外部辞書説明](../core/src/main/resources/META-INF/external-dictionaries.txt)に取得元と表示を記載しています。取得時点のファイル、ヘッダー、ハッシュ、文字コードと条件の根拠は[辞書出典資料](audit/dictionary-sources.md)から確認できます。取得 URL が将来返すファイルは変わり得るため、その資料だけで端末ごとの取得版を保証しません。

公式辞書は現行 APK に同梱せず、利用者の端末から配布元へ直接接続して取得します。初回の S 辞書取得は製品が開始する操作であり、任意の持込み辞書と同じ扱いにはしません。辞書の GPL を独立した本体コードへ一律に適用する必要はないと判断していますが、辞書本文や変換済み辞書を今後同梱・転載・加工配布する場合は、その版の著作権、許諾、無保証表示、変更表示、GPL 本文、対応ソースの提供条件を別途確認します。更新される URL の提示だけを、配布した版の対応ソースの証拠にしません。[公式配布案内](https://skk-dev.github.io/dict/)と[辞書の編集・配布条件](https://github.com/skk-dev/dict/blob/master/committers.md)を参照してください。

## アイコンとソースの由来

アプリアイコンの原本、APK 内の画像、掲載画像に適用する条件と、Google Material アイコンの出典は[アイコンの出典](icon-provenance.md)に記載しています。DDSKK、AndroidSKK、ブラウザ版は挙動・互換性の参考資料です。参照実装のライセンスを本体へ転用せず、コードの複製と動作の独立実装を区別します。AZIK の規則と由来は[AZIK 仕様](azik-profile.md)を参照してください。Unicode の試験データと Gradle Wrapper も第三者部分であり、本体の独自コード・文書と同一の許諾表示にはしません。

コードの類似性は機能上の入出力一致だけから断定できません。[SCANOSS による限定照合](audit/scanoss-20260923.md)と[追加確認](audit/scanoss-20260923-followup.md)は、対象版・検出内容・判断根拠・未確認範囲を記録しています。調査はコード全体の独立性を無条件に証明しません。

## 配布時の確認

依存物、アイコン、辞書の同梱範囲を変更したら、ビルド定義、通知、許諾原文、アプリ内表示、公開成果物を照合します。`scripts/verify-local.py` はルート MIT と同梱本文の一致、およびライセンスリソースの収録を確認します。版固有の検査と配布手順は[配布・更新](distribution.md)に従います。アプリ利用時のデータの扱いは[プライバシーポリシー](site/privacy-policy.html)が正本です。
