# 公式辞書の出典と確認範囲

本資料は、2026-09-23 に公式カタログの 5 辞書を取得して確認したファイルと条件の根拠を示します。[原資料](dictionary-sources-20260923.json)には取得時刻、最終 URL、HTTP 更新日時、圧縮ファイルと展開後の SHA-256、サイズ、文字コード、ヘッダーを記録しています。辞書本文はリポジトリと APK に追加していません。

| 辞書 | 配布元で確認した条件 | 取得元 |
| --- | --- | --- |
| SKK-JISYO.S | GPL-2.0-or-later | https://skk-dev.github.io/dict/SKK-JISYO.S.gz |
| SKK-JISYO.L | GPL-2.0-or-later | https://skk-dev.github.io/dict/SKK-JISYO.L.gz |
| SKK-JISYO.jinmei | GPL-2.0-or-later | https://skk-dev.github.io/dict/SKK-JISYO.jinmei.gz |
| SKK-JISYO.geo | GPL-2.0-or-later | https://skk-dev.github.io/dict/SKK-JISYO.geo.gz |
| SKK-JISYO.zipcode | Public domain | https://raw.githubusercontent.com/skk-dev/dict/master/zipcode/SKK-JISYO.zipcode |

条件は[公式配布案内](https://skk-dev.github.io/dict/)、[辞書の編集・配布条件](https://github.com/skk-dev/dict/blob/master/committers.md)、[郵便番号辞書の説明](https://github.com/skk-dev/dict/blob/master/zipcode/README.md)も参照しました。郵便番号辞書の生成プログラムの GPL は、生成された辞書データの public domain 指定と区別します。geo のヘッダーにある元の郵便データの最終更新日は 2005-09-30、zipcode の辞書タイムスタンプは 2022-05-04 です。取得日だけで住所・郵便番号の新しさを保証しません。

この調査は取得時点の公開ファイルを対象とします。URL が後で返す内容や、端末ごとに取得した版は固定・保証しません。現行の保存形式は取得元 URL とローカル世代を保持しますが、元ファイルのハッシュやヘッダーは保存しません。ローカル世代を配布元の版と呼びません。圧縮ファイルのハッシュは取得処理の記録であり、別環境での再取得照合は完了していません。展開済み本文については 5 件を独立に照合しました。

辞書は利用者の端末から配布元へ直接取得し、現行 APK に同梱しません。本体 MIT の範囲とは別です。将来の同梱・転載・加工配布に必要な判断は[ライセンスの適用範囲](../licenses.md)に記載しています。
