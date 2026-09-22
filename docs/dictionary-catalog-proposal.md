# 既知の辞書一覧の検討

2026-09-21。所有者と相談し、S・L・人名・地名・郵便番号の5種類を採用しました。事業所個別の郵便番号は含めません。初期選択と一覧への掲載は区別し、一覧にある辞書をすべて自動導入しません。

| 辞書 | 用途 | 現在の扱い |
| --- | --- | --- |
| S | 基本の一般語 | 既存。初回に自動準備する基礎辞書 |
| L | 広い一般語彙 | 初期設定で選ぶとSを置き換えます |
| jinmei | 人名 | 既存。追加選択 |
| geo | 地名 | 既存。追加選択 |
| zipcode | 7桁の郵便番号から住所 | 今回追加 |
| office.zipcode | 事業所個別の郵便番号 | 今回の対象外 |

既存4種類に郵便番号を加えます。技術用語や多言語の辞書は、希望する分野を確認してから個別に調べます。

公式の[配布一覧](https://skk-dev.github.io/dict/)と[郵便番号辞書のREADME](https://github.com/skk-dev/dict/blob/master/zipcode/README.md)を確認しました。郵便番号辞書と事業所辞書の本文はREADMEでpublic domainとされています。一般辞書のライセンス表示を機械的に使い回さず、辞書ごとの条件と取得元を持たせる必要があります。郵便番号は[公式リポジトリの本文](https://raw.githubusercontent.com/skk-dev/dict/master/zipcode/SKK-JISYO.zipcode)を取得します。2026-09-21の確認で4,130,604バイト、120,394エントリを既存コーデックで正常に解析し、`1000001`から「東京都千代田区千代田」を確認しました。取得物のSHA-256は`0bffb758f22e4f0fbaa1f9cdcc0b623991ffae343ade26c0eafe93af958b99c4`です。これは検証時の識別子で、以後の取得を固定しません。

配布元の最終変更は2022-05-05、コミット`b20a95c79d2def932a788721e00ce5654cfc8161`です。UIに2022年5月の更新日を表示し、最新の住所データとは扱いません。確認は[公式履歴](https://github.com/skk-dev/dict/commits/master/zipcode/SKK-JISYO.zipcode)とGitHubのコミットAPIを使いました。

設定画面では独自の略称を付けず、`SKK-JISYO.S`・`SKK-JISYO.L`・`SKK-JISYO.jinmei`・`SKK-JISYO.geo`・`SKK-JISYO.zipcode`という配布名を表示します。
