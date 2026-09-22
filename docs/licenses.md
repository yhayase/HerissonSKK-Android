# MIT ライセンス採用の調査

調査日: 2026-09-23。本体のライセンスは候補の評価段階であり、この文書によって MIT を適用するものではありません。

## 結論

確認した Android 版の実行時依存関係について、本体の独自コードを MIT で公開することを妨げる条件は見つかっていません。本体を MIT とし、第三者ライブラリ・辞書の既存ライセンスを維持する構成が可能と判断します。ただし、現行の第三者通知には版の古い記載と依存物の列挙不足があり、公開前の整備が必要です。MIT の採用可否と、配布物のライセンス対応完了は別に扱います。

この判断は今回確認した依存グラフ・配布物・由来記録に基づくものです。出典の記載がない複製の不存在や、画像の権利関係を保証するものではありません。

## 調査範囲と証拠

`:app:releaseRuntimeClasspath` を Gradle のオフライン解決で取得しました。独自 core を除き、68モジュール・68種類の成果物（解決結果は70行で、core と lifecycle-viewmodel の同一ハッシュの重複を含む）を保守的に対象としました。POM から取得したライセンス情報、POM・該当する親 POM・成果物のハッシュ、既存 release APK の通知のハッシュは[調査データ](audit/release-license-inventory-20260923.json)に保存しました。個々のクラスが DEX に残るかという使用解析ではありません。収集に使った [Gradle init script](audit/release-license-audit.init.gradle) は `./gradlew -I docs/audit/release-license-audit.init.gradle auditReleaseLicenses --offline` で `/tmp/skk-release-artifacts.json` へフラットな一覧を出します。依存グラフの辺は保存していません。

既存 APK に `META-INF/Apache-2.0.txt`、`META-INF/icu-LICENSE.txt`、`META-INF/third-party-notices.txt` が含まれることを確認しました。既存 APK はゲート `20260922T131016693085Z` の成果物で、当時のコミットは `8b3052d`、未コミット変更ありです。調査時のコミットからの再ビルドではありません。新しい製品ビルドや動作試験は行っていません。

兄弟プロジェクト `../skk-browser-extension/docs/licenses.md` と `docs/audit/license-inventory.json` も参照しました。同プロジェクトの WXT・Vite・wanakana・textarea-caret の結論を、Android 版の依存関係の証拠には転用していません。

## 実行時依存関係

| 対象 | 確認した条件 | MIT 採用への影響 |
| --- | --- | --- |
| AndroidX、Material 1.12.0、Kotlin 2.1.20、coroutines 1.6.4、JetBrains annotations、Error Prone annotations | Apache-2.0。解決済み各 POM を確認 | 本体 MIT と共存可能。Apache の本文・適用される表示を維持する |
| Guava listenablefuture 1.0 | 親 POM guava-parent 26.0-android が Apache-2.0。公式ソースのヘッダーも確認 | 同上。現行通知での個別記載が不足している |
| ICU4J 77.1 | Unicode-3.0 と同梱の第三者条件 | 本体 MIT と共存可能。ICU の許諾・著作権・第三者通知を保持する |

Apache-2.0 は、再配布時の本文・表示・該当する NOTICE の維持などを求めますが、本体の独自部分へ Apache-2.0 を一律に適用する条件ではありません。MIT を選んでも、依存物が MIT へ変わるわけではありません。[Apache-2.0 第4節](https://www.apache.org/licenses/LICENSE-2.0#redistribution)

ICU の許諾本文には、著作権・許諾表示の保持条件と第三者部分の条件があります。本文中の GPL 記載は ICU4C 用 `aclocal.m4` と `config.guess` に関するもので、ICU4J 全体が GPL という意味ではありません。今回の Java 実行時依存と区別します。[ICU 77.1 の LICENSE](https://github.com/unicode-org/icu/blob/release-77-1/LICENSE)

Guava の許諾は POM のライセンス欄が空という理由だけで不明とせず、親 POM と [v26.0 の ListenableFuture ヘッダー](https://raw.githubusercontent.com/google/guava/v26.0/android/guava/src/com/google/common/util/concurrent/ListenableFuture.java)で確認しました。

## 現在の通知の不足

`core/src/main/resources/META-INF/third-party-notices.txt` は coroutines を1.6.1と記載していますが、release は1.6.4です。Guava listenablefuture、Error Prone annotations、AndroidX の一部の名前も網羅していません。Apache 本文は存在しますが、それだけで全ての由来・必要な表示を網羅したとは判定しません。

公開前に、解決済み依存一覧に沿って通知を更新し、各配布物・出典の該当する NOTICE と著作権表示を照合する必要があります。今回の調査では本体コードや通知の修正は行いません。

## 独自コード・辞書・画像の境界

DDSKK・AndroidSKK は挙動の参照先であり、由来記録ではコード移植と区別しています。外部の実装を読み込んで動作を比較する調査スクリプトはありますが、調査した範囲でそれらのエンジンソースの同梱は見つかりませんでした。AZIK は[専用記録](azik-profile.md)に独立した規則生成と互換動作の由来があります。

独立監査では、`Romanizer.kt` の z 記号対応と `AzikRules.kt` の特殊な対応について、参照元の記録と独立実装を宣言するコメントだけでは作成過程を完全には確認できない点を指摘しました。機能上の入出力対応が一致することだけを GPL コードの複製とは判断しません。確認した範囲で複製の証拠はありませんが、公開時の由来記録には規則・文章・実装コードを区別して残します。

辞書操作・戻るボタンの既存ベクターには Material Icons と似た形状があり、取得元・版の記録がありません。類似だけでは出典を確定しません。[Material Icons 自体は Apache-2.0](https://github.com/google/material-design-icons/blob/master/LICENSE)であり、そこからの利用なら本体 MIT と両立しますが、出典と変更の記録・必要な表示の整備が残ります。

リポジトリ公開には APK 以外の第三者部分もあります。`core/src/test/resources/unicode/16.0.0/GraphemeBreakTest.txt` は Unicode の公式試験データで、ヘッダーと利用条件を維持します。Gradle wrapper のスクリプト・JAR もビルド用の第三者部分として Apache-2.0 の表示を保持します。自作の試験コードと一括して MIT と表示しません。

外部の SKK 辞書はネットワークやファイルから読み込む独立したデータです。本体の MIT は辞書の再許諾ではなく、辞書ごとの条件は残ります。今後、辞書を APK・ソース公開・Play 配布物へ同梱する場合も別途条件を満たす必要があります。

特に S 辞書は `InitialDictionaryInstaller` が初回に自動取得する製品側のデータであり、任意のユーザー持込みだけではありません。現行 release は限定試験辞書のフォールバックを無効にしており、公式辞書ファイルは APK に同梱していません。取得した版・著作権・許諾の記録や表示を、辞書の取得元 URL だけで十分と判断せず整理する必要があります。

現在のカタログの S・L・人名・地名には GPL-2.0-or-later、郵便番号辞書には public domain の指定があります。郵便番号辞書の生成プログラムは GPL ですが、生成された辞書の指定とは区別します。[辞書配布元の説明](https://github.com/skk-dev/dict/blob/master/committers.md)

採用する名称は **HerissonSKK (for Android)**、アイコンは所有者指定の `HerissonSKK-icon-refined.png` です。名称・アイコンの選定と、第三者への再利用許諾は別の判断です。画像をコードと同じ MIT に含めるか、別条件にするかは未決定とし、由来と再配布権限の記録が必要です。今回、画像を加工したり新しい画像へ置換したりしていません。

## MIT を採用する場合の残件

1. 所有者が本体への MIT 採用を決定し、著作権者・年と適用範囲を明記した LICENSE を追加する。
2. 第三者部分を本体 MIT の対象と混同しないよう、実際の依存版に合う通知・必要な本文を整備する。
3. 外部辞書・試験データ・画像の由来と条件を分けて記録する。
4. 最終配布物で本文・通知の収録を確認する。

MIT は著作権表示と許諾文の保持を条件とするライセンスです。[MIT 本文](https://opensource.org/license/mit)

Google Play での配布準備は所有者の方針として採用しますが、この調査では着手しません。Play の手続きや最新の公開条件への適合を検証済みとは扱いません。

## 独立レビュー

由来調査と依存関係・結論のレビューを別担当が行い、指摘をさらに別担当が検証しました。成果物70行のうち2行が同一物の重複である点と、調査時のコミットを既存 APK のビルド元と混同し得る点を修正しました。Play 方針の採用と着手保留が矛盾するという指摘は、所有者の発言と照合して誤検知と判定しました。調査の独立性は由来の完全な証明を意味しません。
