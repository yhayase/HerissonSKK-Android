# アイコンの出典とライセンス

更新日: 2026-09-24

この文書は、アプリに含まれるアイコン素材の出典と利用条件を記録します。

## アプリアイコン

HerissonSKK のアプリアイコンは、ChatGPT の画像生成で作成し、Codex で品質調整とアイコン用の整形を行い、作者が選定した画像です。

| 対象 | ファイル |
| --- | --- |
| 原本 | [HerissonSKK-icon-refined.png](../HerissonSKK-icon-refined.png) |
| アプリ内の画像 | [ic_herisson.png](../app/src/main/res/drawable-nodpi/ic_herisson.png) |

原本、アプリ内の画像、およびこれらを用いたランチャーアイコン・掲載用画像には、[アイコン使用条件](../core/src/main/resources/META-INF/icon-usage.txt)が適用されます。本体の MIT ライセンスの対象には含まれません。公式アプリの配布・紹介には利用できます。別製品のアイコンやブランド表示への流用には、事前に作者の個別許可が必要です。問い合わせ先はアイコン使用条件に記載しています。

## 設定画面の Material Icons

設定画面では、Android-Iconics が配布する Google Material アイコンフォントを実行時に描画します。アイコンのパスデータをアプリ独自の VectorDrawable として複製していません。

| 表示箇所 | Google Material アイコン識別子 |
| --- | --- |
| 辞書を追加 | `GoogleMaterial.Icon.gmd_add` |
| 辞書を削除 | `GoogleMaterial.Icon.gmd_delete_outline` |
| 辞書の並べ替え | `GoogleMaterial.Icon.gmd_drag_indicator` |
| 辞書を更新 | `GoogleMaterial.Icon.gmd_refresh` |
| 設定画面に戻る | `GoogleMaterial.Icon.gmd_arrow_back` |

実行時依存関係は次のとおりです。

| Maven 座標 | 版 | ライセンス |
| --- | --- | --- |
| [`com.mikepenz:iconics-core`](https://github.com/mikepenz/Android-Iconics/tree/v5.4.0) | `5.4.0` | Apache License 2.0 |
| [`com.mikepenz:iconics-typeface-api`](https://github.com/mikepenz/Android-Iconics/tree/v5.4.0) | `5.4.0` | Apache License 2.0 |
| [`com.mikepenz:google-material-typeface`](https://github.com/mikepenz/Android-Iconics/tree/v5.4.0) | `4.0.0.3-kotlin` | Apache License 2.0 |

Google Material アイコンセットの出典は[Google Material Design Icons](https://github.com/google/material-design-icons)で、ライセンスは[配布元の Apache-2.0 表示](https://github.com/google/material-design-icons/blob/master/LICENSE)を参照してください。Android-Iconics のライセンス本文は[リポジトリの Apache-2.0 ライセンス](https://github.com/mikepenz/Android-Iconics/blob/v5.4.0/LICENSE)を参照してください。アプリ内の[第三者通知](../core/src/main/resources/META-INF/third-party-notices.txt)にも依存関係とライセンスを記載しています。
