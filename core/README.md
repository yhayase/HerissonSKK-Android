# 純 Kotlin コア

Android・画面・実DBに依存しないKotlin/JVMモジュールです。`BasicSkkEngine` は5入力モード、読み・送り・候補・abbrev・接辞、内部編集、再帰登録、候補学習・削除の効果、補完・数値変換、入力規則の設定を扱います。辞書形式、複合検索、由来、書記素境界もこのモジュールにあります。

SQLite永続化、辞書読込の実行スケジューリング、Android入力接続とUIは `app` の責務です。キャッシュミス時はコア状態を巻き戻し、接続層が非同期読込後に再実行します。「coreにAndroid実装がない」ことと「製品機能が未実装」であることを区別します。

状態契約は [コア設計](../docs/core-design.md)、文書の分担は [案内](../docs/README.md)、検証結果は [試験対応表](../docs/test-coverage.md) を参照します。

## Unicode の境界

編集単位は ICU4J `77.1` の character break、Unicode `16.0` に固定します。OS内蔵のUnicode版に依存して端末ごとに挙動が変わることを避けます。削除による隣接クラスタの結合も再評価し、カーソルは同じ位置以降の最初の境界へ置きます。不正なUTF-16は変更前に拒否します。

独自の境界規則ではHangul結合、CRLF、かなとZWJ、絵文字タグ、インド文字で不具合が再現したため、ICUへ置き換えました。ICU4J のライセンスを配布物へ同梱します。境界の互換性は公式データと独自の編集試験で確認します。

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

コア試験は Android の入力接続や全端末の互換性まで保証しません。対応する結合試験は [試験対応表](../docs/test-coverage.md) を参照します。
