# アイコンの出典とライセンス

更新日: 2026-09-23

## アプリアイコン

所有者が指定した `HerissonSKK-icon-refined.png` を採用します。この画像は HerissonSKK のブランド識別として扱い、本体の MIT ライセンスの対象ではありません。公式アプリの配布・紹介での使用は認めますが、別製品への再利用には個別の許可が必要で、原則として認めません。詳細と問い合わせ先は[アイコン使用条件](../core/src/main/resources/META-INF/icon-usage.txt)を参照してください。原本の SHA-256 が兄弟プロジェクト `../skk-browser-extension/assets/brand/HerissonSKK-master.png` と一致することを確認しました。同プロジェクトの README とブランド素材の記録に従い、ChatGPT の画像生成で図案を作り、Codex で品質調整とアイコン用の整形を行い、作者が選定した画像と記録します。Android 版ではその原本の画素を変更していません。作者が採用結果を確認し、公開内容に対する最終的な責任を負います。

- 原本: [HerissonSKK-icon-refined.png](../HerissonSKK-icon-refined.png)
- アプリ内の画像: `app/src/main/res/drawable-nodpi/ic_herisson.png`
- 両ファイルの SHA-256: `d57e88b8a12161999b4988310d8aa63c87ad3b29bc3b7d6c987ad398fb091a91`
- 画像のバイト列は変更していません。Android の adaptive icon リソースで白い背景と余白を付け、ランチャーの形状による欠けを防ぎます。
- アプリの識別子は `se.haya.skk` を維持し、表示名だけを **HerissonSKK (for Android)** にします。

## 設定画面の Material Icons

次の3件は公式 Material Icons とパスの形状が一致することを2担当が確認しました。作成時の取得記録を推測で補うのではなく、対応する公式 SVG を出典として記録し、Apache-2.0 の第三者通知を収録します。変更は Android VectorDrawable 形式とテーマ色への対応です。

| ローカルのファイル | 公式 SVG |
| --- | --- |
| `ic_dictionary_add.xml` | [content/add](https://github.com/google/material-design-icons/blob/master/src/content/add/materialicons/24px.svg) |
| `ic_dictionary_delete.xml` | [action/delete_outline](https://github.com/google/material-design-icons/blob/master/src/action/delete_outline/materialicons/24px.svg) |
| `ic_settings_back.xml` | [navigation/arrow_back](https://github.com/google/material-design-icons/blob/master/src/navigation/arrow_back/materialicons/24px.svg) |

[配布元の Apache-2.0 本文](https://github.com/google/material-design-icons/blob/master/LICENSE)に基づき、本体 MIT の対象とは区別します。今回参照した各 SVG の SHA-256 は以下です。

- `content-add`: `475c29758b4a689598f80099714362c0340ad3a4bc111e2d88807bbf4b0f817e`
- `action-delete_outline`: `d8a008dbf82f57c53a1edacf5b8e3302e465d94706561423dce7a60846f57735`
- `navigation-arrow_back`: `e1590e051f577d02ec994e7cc6005a2bc96407a3d1ba2d7ce6825fb80402684c`

`ic_dictionary_drag.xml` と `ic_dictionary_update.xml` には、調べた公式素材と一致する出典を確認できませんでした。単純な図形や更新マークの類似だけで Material Icons 由来と表示することはしません。
