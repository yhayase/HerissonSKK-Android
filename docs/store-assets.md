# Google Play 掲載画像

作成日: 2026-09-23。Google Play Console への登録・公開は未実施です。

## 作成済み素材

- [`assets/store/app-icon-512.png`](../assets/store/app-icon-512.png): 512×512、RGBA PNG。指定原本 [`HerissonSKK-icon-refined.png`](../HerissonSKK-icon-refined.png) を縮小した掲載用画像です。角丸・影・文字を追加していません。
- [`assets/store/feature-graphic-1024x500.png`](../assets/store/feature-graphic-1024x500.png): 1024×500、不透明 RGB PNG。指定アイコンを背景上に配置し、掲載名と機能を表す短い文言を日本語・英語で加えています。価格、順位、評価、ストアのバッジや未確認の機能は含めていません。
- [`assets/store/feature-graphic.svg`](../assets/store/feature-graphic.svg): フィーチャーグラフィックの編集可能なレイアウトです。指定アイコン原本を埋め込んでいます。
- [`scripts/make_store_assets.py`](../scripts/make_store_assets.py): 上記2画像を原本から再生成するスクリプトです。原本は変更しません。実行には Python 3、Pillow、PyGObject の librsvg binding、Noto Sans CJK JP、DejaVu Sans が必要です。

再生成コマンド: `python3 scripts/make_store_assets.py`

Google Play の公式要件では、掲載アイコンは32-bit PNG、512×512、1 MB以下で、Google Play 側で角丸と影が適用されます。フィーチャーグラフィックは不透明な24-bit PNGまたはJPEG、1024×500です。今回の画像はいずれも寸法・形式に適合します。最終アップロード前に Play Console が受け付けることを確認してください。

## スクリーンショット

既存の `build/` 以下にある画像は、UI確認やテストの証跡です。確認できた例では `build/distributions/bc1e24a/candidate-menu-api35.png` は320×640、`build/distributions/play-preparation-20260923-19cef1b3/settings-wide-portrait.png` は1200×1600です。これらをストア素材として複製・加工していません。

掲載用に、画面キーボードの変換候補と辞書管理画面を専用エミュレーターで撮影した2枚を用意し、内容を確認しました。撮影条件と復元手順は[スクリーンショット記録](store-screenshots.md)を参照してください。Console での受け付け確認は未実施です。

Google Play の通常のスクリーンショット要件は、JPEGまたはアルファなし24-bit PNG、各辺320〜3840 px、長辺が短辺の2倍以内です。掲載には異なる端末種別を合わせて最低2枚が必要です。アプリのおすすめ表示対象となるには、アプリでは1080×1920以上の縦長または1920×1080以上の横長を4枚用意することが推奨されています。現在の2枚は1080×2160で、寸法と形式の条件に適合します。おすすめ表示に向けて追加する場合は、実際の画面を撮影し、画像を引き伸ばして要件寸法に見せないようにします。

## 公式仕様

- [Google Play のプレビュー素材要件](https://support.google.com/googleplay/android-developer/answer/9866151?hl=en-GB): アイコン、フィーチャーグラフィック、スクリーンショットの形式・寸法と掲載指針。
- [Google Play アイコンのデザイン仕様](https://developer.android.com/distribute/google-play/resources/icon-design-specifications): 512×512、sRGB、1 MB以下、角丸・影を重ねないことなど。
