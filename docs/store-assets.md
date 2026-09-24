# Google Play 掲載素材

本書は掲載画像の原本、作り方、内容確認の基準を示します。掲載文は[掲載情報案](play-store-listing.md)、アプリアイコンの使用条件は[アイコンの出典](icon-provenance.md)を参照してください。Console の素材要件はアップロード時に[公式案内](https://support.google.com/googleplay/android-developer/answer/9866151?hl=en-GB)と照合します。

## アイコンとフィーチャーグラフィック

| 素材 | 原本・用途 |
| --- | --- |
| [`assets/store/app-icon-512.png`](../assets/store/app-icon-512.png) | 指定原本 [`HerissonSKK-icon-refined.png`](../HerissonSKK-icon-refined.png)から縮小した 512×512 の掲載アイコン。角丸・影・文字を追加しません |
| [`assets/store/feature-graphic-1024x500.png`](../assets/store/feature-graphic-1024x500.png) | 1024×500 の不透明な掲載画像。指定アイコンと掲載名、機能を表す文言を含みます |
| [`assets/store/feature-graphic.svg`](../assets/store/feature-graphic.svg) | フィーチャーグラフィックの編集可能なレイアウト。アイコン原本を埋め込んでいます |

`scripts/make_store_assets.py` で画像を原本から再生成できます。Python 3、Pillow、PyGObject の librsvg binding、Noto Sans CJK JP、DejaVu Sans を使用します。原本は変更しません。

```sh
python3 scripts/make_store_assets.py
```

掲載アイコンは 32-bit PNG、512×512、1 MB 以下、フィーチャーグラフィックは不透明な 24-bit PNG または JPEG、1024×500 が基準です。Google Play 側でアイコンの角丸と影を適用するため、画像側に重ねません。形式・寸法に加え、Console での受理を提出時に確認します。[アイコンの公式仕様](https://developer.android.com/distribute/google-play/resources/icon-design-specifications)も参照してください。

## スクリーンショット

| 素材 | 表示内容 |
| --- | --- |
| [`01-input-keyboard-candidates.png`](../assets/store/screenshots/01-input-keyboard-candidates.png) | Android の設定検索欄へ架空の読み「にほん」を入力した画面。HerissonSKK の画面 QWERTY と候補「日本」を表示します。入力先の検索結果はキーボードの候補とは別です |
| [`02-dictionary-management.png`](../assets/store/screenshots/02-dictionary-management.png) | アプリの辞書管理画面。公式 `SKK-JISYO.S`、優先順、配布元を示します |

両画像は専用 Android エミュレーターで実際の画面をキャプチャした 1080×2160 の不透明 RGB PNG です。元の撮影では API 36 の `skk-api36` を 1080×2160、420 dpi に設定し、画面の切り抜き、合成、拡大縮小、文字や画面要素の追加をせず、全画素が不透明なことを確認してアルファチャンネルだけを除きました。撮影には release 構成の APK と公式 S 辞書を使用し、debug 限定試験辞書を含めませんでした。

素材を更新する場合は、実際のアプリ画面と導入した辞書を使い、個人のメッセージや連絡先を含めず、画像を引き伸ばしません。撮影前にエミュレーターの APK、辞書・入力設定、既定 IME、画面寸法・密度を控え、撮影後に復元します。候補欄と入力先アプリの表示を区別して内容を確認します。通常のスクリーンショット要件は各辺 320〜3840 px、長辺が短辺の 2 倍以内、JPEG または不透明な 24-bit PNG です。必要枚数、端末種別、おすすめ表示の追加素材は提出時の公式案内で確認します。
