# 純 Kotlin コア

Android・画面・実DBなしで入力状態を検証する JVM モジュールです。`BasicSkkEngine` は、限定した標準ローマ字規則、5 入力モード、見出し語・送り・候補・abbrev・接頭辞／接尾辞、候補取消時の検索前状態復元、内部カーソル編集を扱います。辞書は試験用の同期ポートだけであり、完全な辞書、登録、非同期検索、永続化、設定、未確定外の入力欄編集は未実装です。K01〜K08 は限定フィクスチャと標準表での部分的な証拠であり、受入完了を示しません。

## Unicode の境界

編集単位は ICU4J `77.1` の character break、Unicode `16.0` に固定します。OS内蔵のUnicode版に依存して端末ごとに挙動が変わることを避けます。削除による隣接クラスタの結合も再評価し、カーソルは同じ位置以降の最初の境界へ置きます。不正なUTF-16は変更前に拒否します。

独自の境界規則ではHangul結合、CRLF、かなとZWJ、絵文字タグ、インド文字で不具合が再現したため、ICUへ置き換えました。ICU4JのJARは14,663,227バイトです。ライセンスを含めて `app` と `test-editor` の APK に同梱し、API 26／30／35 の専用エミュレーターで `CoreUnicodeTest` を実行しました。これは対象 API での ICU 読み込みと代表的な削除操作の証拠ですが、全端末・全 Unicode 入力方式での互換性や APK 容量の受入判定を示すものではありません。

- [ICU 77の説明](https://unicode-org.github.io/icu/download/77.html)
- ライセンス原文: `src/main/resources/META-INF/icu-LICENSE.txt`。配布物にも同梱します。
- ライセンス取得元: `https://raw.githubusercontent.com/unicode-org/icu/release-77-1/LICENSE`
- 同SHA-256: `451167c55c0fa447cc2d5632714f5e3c567fe4f1e1badefab2c1333852198aca`
- 公式境界テスト: `https://www.unicode.org/Public/16.0.0/ucd/auxiliary/GraphemeBreakTest.txt`
- 同SHA-256: `ee2b9354d270ac061b29f09662cafea06341d77e704b8cc6bd72aaeeda363cb5`

公式テストデータのヘッダーと利用条件の参照を保持しています。取得したデータは変更せず、期待する境界を読んで前後移動を検証します。これは文字列の変異を伴う独自編集テストとは別の試験です。

```sh
./gradlew :core:test
```

2026-09-16 時点の確認では、`BasicSkkEngineTest` 26 件、ローマ字・文字種変換 22 件、編集バッファ 14 件、Unicode 公式書記素境界コーパス 1 件の計 63 件が、失敗・エラー・スキップ 0 件で成功しています。
