# AndroidSKK の品質・拡張性評価

履歴資料です。対象時点の判定を保持しています。現在の仕様・残件との区別は [レビュー履歴の案内](README.md) を参照します。

評価日: 2026-09-12

対象: [kachaya-ime/AndroidSKK](https://github.com/kachaya-ime/AndroidSKK)、コミット [`f5bf85c6d4482647531c608bc1037fc4206cea16`](https://github.com/kachaya-ime/AndroidSKK/tree/f5bf85c6d4482647531c608bc1037fc4206cea16)（2026-09-10）。ビルド設定の版は 2.1.0 です。ユーザーが導入した APK とこのソースコミットの完全な一致は検証していません。

## 結論

機能の幅はありますが、現状を「十分な自動テストで保護され、SKK の細部まで正しく、長期の拡張に安心して使える基盤」とは評価できません。状態・モードの分離や Trie によるローマ字処理は良い出発点です。しかし、回帰テストの不在に加え、登録・学習・辞書形式・入力接続の失敗処理で具体的な問題を確認しました。

先に示した「貢献を優先する」という勧めは、基盤の改善を作者と合意できることを条件に修正します。画面キーボード非表示などの小さな変更は貢献しやすい一方、希望機能一式を足す前には、テスト導入、辞書処理の修正、コアと Android 接続の責務整理が必要です。機能数の多さだけを理由に全面採用することは勧めません。独自実装もまだ基盤段階であり、こちらのほうが完成度・信頼性が高いという結論ではありません。

## 調査方法と限界

- ソース、マニュアル、設定、ビルド設定、辞書作成ツール、入力試験スクリプトを読みました。対象リポジトリに変更は加えていません。
- 追跡対象を確認したところ `app/src/test/`、`app/src/androidTest/` のテストコード、および `.github` 等の CI 設定はありませんでした。JUnit 等の依存宣言はあります。`scripts/test_input.ps1` はキーを送るスクリプトで、出力の期待値比較・自動合否判定はありません。非公開・外部のテストの存在は不明です。
- [調査用ハーネス](../../scripts/review/androidskk-core.py)で、対象の engine 配下 19 Java ファイルを変更せずコンパイルし、同梱の実 JDBM 1.0 と一時辞書を使って実行しました。Android の Context、表示、InputConnection、設定、ログ等は最小の代替クラスです。ローマ字・カナ表は対象の JSON の値を実 RomajiMap 等へ投入し、AssetLoader の Android 読み込み経路は通していません。
- 22 項目の診断用アサーションが成立しました。これは製品の 22 テストが合格したという意味ではなく、正常系、問題の再現、仕様差、残存状態を確認した数です。[実行結果](androidskk-core-results.txt)を添付します。
- 基本かな `kana`、促音 `kitte`、`Nihon Space`、その Enter 確定、次の Enter の引き渡し、`KaKu` の送りあり変換を小さい正常系として確認しました。
- AndroidSKK の APK 全体のビルド・Lint・実機での回帰試験、DDSKK 実行との網羅的な差分試験は行っていません。Android のライフサイクル、実際のキーリピート配送、描画、性能はこのハーネスでは検証できません。これらを実機再現済みとは扱いません。

再実行には JDK と対象のチェックアウトが必要です。ハーネスは対象コードと JDBM を実行し、一時ディレクトリに合成辞書を作ります。実機や既存の個人辞書には触れません。

```sh
python3 scripts/review/androidskk-core.py /path/to/AndroidSKK
```

## 観点別の評価

| 観点 | 評価 | 根拠 |
| --- | --- | --- |
| 機能の幅 | 強み | 入力モード、変換、補完、登録用スタック、辞書管理、画面キーボードを実装しています |
| 責務の分離 | 部分的 | 状態・モード・変換表・辞書・画面のクラス分離はありますが、コアが具体的な InputService と Android API を直接使います |
| 拡張性 | 大きな変更には整理が必要 | 編集位置を持たない可変バッファ、状態クラスによる直接変更、固定辞書、表示と状態遷移の結合が制約になります |
| 自動テスト | 不十分 | 追跡されたテストケース・CI がなく、キー送信スクリプトも結果を検証しません |
| SKK 正確性 | 部分的に動作、重要な欠陥あり | 小さな正常系は成立しましたが、登録、数値、送り条件、未消化ローマ字に問題・仕様差があります |
| データの保全 | 改善が必要 | 入出力で情報を失う経路、保存失敗の握りつぶし、辞書の更新・所有権の問題があります |
| Android 接続 | 防御が不足 | 接続 API の失敗を無視し、再変換履歴を入力先に結び付けず、長押しの状態遷移も保護していません |
| プライバシー | 修正優先度が高い | リリース設定でも学習内容を Log.i へ渡す経路と、学習禁止フラグを処理しない点があります |
| 性能 | 未測定、懸念あり | UI スレッドから同期検索・学習保存・大量取込が実行されます。実測なしに遅いとは断定しません |
| ビルド・保守 | 基礎はあるが品質ゲート不足 | Wrapper、版管理、辞書ビルドツールはあります。Lint の失敗をビルド失敗にせず、CI と再現テストがありません |

## 再現できた主な問題

### 1. 単語登録を終えても登録語が入力先・親の登録へ出力されません

重要度: 高。ハーネスで再現しました。

未登録の `Michi Space` の登録内で `miti` を入力し Enter を押すと、辞書には保存され、登録スタックからも取り除かれますが、入力先への確定出力は空です。再帰登録でも `Mura Space aMori Space mori Enter` の子登録完了後、親には `あ` だけが残り、`もり` が追加されません。

`finishRegistration()` は保存とスタックの除去をしますが、その後に登録文字を入力先・親バッファへ反映しません。通常変換が実装されていることと、登録経路が正しいことは別です。

根拠: [SKKEngine.java:1032](https://github.com/kachaya-ime/AndroidSKK/blob/f5bf85c6d4482647531c608bc1037fc4206cea16/app/src/main/java/io/github/kachaya/skk/engine/SKKEngine.java#L1032)、[SKKStateDirect.java](https://github.com/kachaya-ime/AndroidSKK/blob/f5bf85c6d4482647531c608bc1037fc4206cea16/app/src/main/java/io/github/kachaya/skk/engine/SKKStateDirect.java)。

### 2. 学習により注釈が確定対象の本文になります

重要度: 高。ハーネスで再現しました。

`anno /日本;国名/` を取り込むと、初回は候補 `日本`、注釈 `国名` として読み込まれます。その候補の rawCandidate を学習経路の `addEntry` へ渡すと、次回の先頭候補は本文 `日本;国名`、注釈なしになります。

候補本文と注釈を含む辞書表現を同じ String で受け渡し、保存時にセミコロン全体をエスケープすることが原因です。単なる表示問題ではなく、辞書へ保存される表現が変わります。同梱辞書が注釈を除去しているため通常入力で目立たなくても、ユーザー辞書の取り込み・拡張では問題になります。

根拠: [Dictionary.java:448](https://github.com/kachaya-ime/AndroidSKK/blob/f5bf85c6d4482647531c608bc1037fc4206cea16/app/src/main/java/io/github/kachaya/skk/engine/Dictionary.java#L448)、同ファイルの `escape`、`findCandidates`。

### 3. 正規の辞書表現を取り込めず、送り条件のない候補も見失います

重要度: 高。ハーネスで再現しました。

- `literal /(concat "\057")/` のような正規のエスケープ表現を持つ行は、スペースで全分割した要素数が 2 を超えるため、何も通知せず読み飛ばされます。空白を含む候補・注釈も影響します。
- `あk /飽/` を個人辞書へ取り込んでも、送り `く` の検索では候補がありません。`validateForOkuri` は送りブロックに一致しないユーザー候補を除外します。ブロックなしを制約なしとして扱う DDSKK の標準的な運用との互換性に影響します。

根拠: [Dictionary.java:429](https://github.com/kachaya-ime/AndroidSKK/blob/f5bf85c6d4482647531c608bc1037fc4206cea16/app/src/main/java/io/github/kachaya/skk/engine/Dictionary.java#L429)、同ファイルの `validateForOkuri`。厳密送り照合は DDSKK では設定事項です。この差を意図するなら設定と仕様の明示が必要です。

### 4. 数字を含むキーの検索と学習が一貫していません

重要度: 中〜高。ハーネスで再現しました。

- 個人辞書に `1234567 /住所/` が存在しても検索結果は空です。数字を必ず `#` に置き換えて検索し、元のキーを検索しません。郵便番号など数字そのものを見出しにする辞書の利用に関わります。
- `だい# /第#0/` で `Dai12 Space` を変換すると `第12` になりますが、確定時には `だい12 /第#0/` に学習します。次回の検索キー `だい#` と一致しません。
- `#4` は旧字体数値へ変換され、DDSKK の辞書再検索と異なります。`#5` も独自の混合表記で、`#8`・`#9` は専用処理がありません。これらはマニュアルにも表れる仕様差で、すべてを偶発的なバグとは分類しません。

根拠: [Dictionary.java:219](https://github.com/kachaya-ime/AndroidSKK/blob/f5bf85c6d4482647531c608bc1037fc4206cea16/app/src/main/java/io/github/kachaya/skk/engine/Dictionary.java#L219)、[SKKEngine.java:1082](https://github.com/kachaya-ime/AndroidSKK/blob/f5bf85c6d4482647531c608bc1037fc4206cea16/app/src/main/java/io/github/kachaya/skk/engine/SKKEngine.java#L1082)、[Candidate.java:145](https://github.com/kachaya-ime/AndroidSKK/blob/f5bf85c6d4482647531c608bc1037fc4206cea16/app/src/main/java/io/github/kachaya/skk/engine/Candidate.java#L145)、[DDSKK の数値処理](https://github.com/skk-dev/ddskk/blob/8c47f46e38a29a0f3eabcd524268d20573102467/skk-num.el)。

### 5. 入力接続が確定を拒否しても状態を失います

重要度: 高。拒否する InputConnection 代替クラスで再現しました。

候補表示後に `commitText` が false を返す条件で Enter を処理すると、出力は空のまま、エンジンは未確定なしへ移ります。`pickCandidate` はさらに学習を進めます。`commitTextSKK` が接続 API の結果を返さないため、上位処理が成功と失敗を区別できません。

Android 上での特定アプリの失敗を再現したものではありませんが、API が失敗する条件への防御不足を示します。再変換の `deleteSurroundingText` も結果を確認せず成功扱いにしています。

根拠: [SKKEngine.java:474](https://github.com/kachaya-ime/AndroidSKK/blob/f5bf85c6d4482647531c608bc1037fc4206cea16/app/src/main/java/io/github/kachaya/skk/engine/SKKEngine.java#L474)、[InputService.java:927](https://github.com/kachaya-ime/AndroidSKK/blob/f5bf85c6d4482647531c608bc1037fc4206cea16/app/src/main/java/io/github/kachaya/skk/InputService.java#L927)。

### 6. 未消化ローマ字と Unicode 編集に欠陥があります

重要度: 中。ハーネスで再現しました。

- 直接かな入力で `n` の後に Enter を処理しても、未消化 `n` が残り、Enter は未処理として入力先へ渡されます。通常候補の Enter が正しくても、状態ごとの処理は揃っていません。
- 登録バッファに絵文字を入れて Backspace を処理すると、UTF-16 の末尾だけが削除され、単独の上位サロゲートが残ります。入力欄の実機表示ではなく、登録バッファの状態を直接確認しました。

根拠: [SKKStateDirect.java](https://github.com/kachaya-ime/AndroidSKK/blob/f5bf85c6d4482647531c608bc1037fc4206cea16/app/src/main/java/io/github/kachaya/skk/engine/SKKStateDirect.java)、`processEnter` と `processBackspace`。

## プライバシー・Android 接続の追加所見

### 学習内容のログ出力

`Dictionary.logI` はコメントと異なり `BuildConfig.DEBUG` を確認せず `Log.i` を呼びます。`addEntry` は読み・候補・送りを渡します。ハーネスで DEBUG=false とした条件でもこの経路を確認しました。端末上の全アプリがログを読めるという意味ではなく、OS ログや診断収集へ入力内容を残す経路があるという指摘です。

根拠: [Dictionary.java:66](https://github.com/kachaya-ime/AndroidSKK/blob/f5bf85c6d4482647531c608bc1037fc4206cea16/app/src/main/java/io/github/kachaya/skk/engine/Dictionary.java#L66)。ネットワーク送信の証拠ではありません。マニフェストには INTERNET 権限がありません。

### 入力先の学習禁止要求

ソースで `IME_FLAG_NO_PERSONALIZED_LEARNING` の処理を確認できませんでした。入力開始はパスワード等を ASCII モードにする処理を持ちますが、セッション単位の学習抑止はなく、学習は全体設定で判断しています。通常のパスワード入力が必ず保存されるとは言えませんが、「初期モードが ASCII」と「学習を禁止する」は別の保証です。

根拠: [InputService.java:203](https://github.com/kachaya-ime/AndroidSKK/blob/f5bf85c6d4482647531c608bc1037fc4206cea16/app/src/main/java/io/github/kachaya/skk/InputService.java#L203)、[Android の学習禁止フラグ](https://developer.android.com/reference/android/view/inputmethod/EditorInfo#IME_FLAG_NO_PERSONALIZED_LEARNING)。これはコード経路の所見で、実機の学習禁止欄での試験は未実施です。

### キー配送・再変換・選択位置

- Enter の key-up 消費用フラグはありますが、長押しの repeat を抑える条件がありません。候補の最初の down が確定した後、repeat の down は通常入力の Enter として流れる経路があります。実機配送による再現は未実施です。
- 再変換前にカーソル直前の文字列を照合するのは良い防御です。一方、`resetOnStartInput` 後も `mLastConversion` は残ります（ハーネスで確認）。別入力欄が同じ文字列で終わる場合に、別セッションの履歴で再変換する余地があります。元の文字列を照合しない無条件削除ではありません。
- `onUpdateSelection` は状態更新をせず、主に CursorAnchorInfo の見た目の位置・可視性に依存します。入力先変更、選択範囲変更、古い通知の遅延に対する世代管理は確認できません。アプリをまたぐ編集機能を加える前に検証が必要です。

根拠: [InputService.java:629](https://github.com/kachaya-ime/AndroidSKK/blob/f5bf85c6d4482647531c608bc1037fc4206cea16/app/src/main/java/io/github/kachaya/skk/InputService.java#L629)、[SKKEngine.java:842](https://github.com/kachaya-ime/AndroidSKK/blob/f5bf85c6d4482647531c608bc1037fc4206cea16/app/src/main/java/io/github/kachaya/skk/engine/SKKEngine.java#L842)。

## 保守・拡張・性能

良い点は、状態を enum に分け、入力モードと変換状態を区別していること、ローマ字規則を JSON と Trie で扱うこと、キー配列関連のクラスを分けていることです。ローカル辞書、Android の標準入力 API、非公開の辞書ツール Activity、IME のバインド権限も基本的な構成として妥当です。

ただし SKKEngine は 1,398 行で、状態遷移、辞書検索、学習、再変換、登録、日付、Android の表示用 Span を作る処理を持ちます。コンストラクタで Context を具体的な InputService にキャストするため、インターフェースを差し替えるだけでコアを試験する構造ではありません。行数自体を欠陥とはしませんが、責務の集中を示しています。InputService は 1,154 行、InputView は 700 行です。

希望する拡張への影響:

| 拡張 | 必要な整理 |
| --- | --- |
| 画面キーボードの自動非表示 | IME 表示と文字キー表示の分離。比較的小さな貢献候補です |
| 複数システム辞書 | 固定のメイン・個人 BTree から辞書集合と検索順位の管理へ変更します。生の文字列の扱いと入出力不具合も修正が必要です |
| 見出し語のカーソル編集 | 現在は StringBuilder の末尾編集と各状態からの直接変更です。編集位置、削除単位、未消化ローマ字、送り境界を定義する必要があります |
| AZIK・任意ローマ字マップ | 表を持つ点は有利ですが、静的共有・起動時読込で、設定変更や規則の整合性、特殊コマンドとの競合のテストが必要です |
| 選択キー・可変候補数 | キー解釈、現在候補の index、表示・ページの責務を整理する必要があります |
| Emacs 編集キー | 既存の矢印への変換だけでは足りません。入力先の能力と編集範囲を確認し、変換中と確定済みテキスト編集の優先順位を定義します |

辞書検索・学習保存はキー処理から同期呼び出しされ、取込も UI の結果コールバック内で全件処理・毎行 commit しています。「バックグラウンドで読み込む」とするコメントと処理が一致しません。大辞書での遅延・ANR は測定していないため断定しませんが、辞書拡張に先立つ検証事項です。

保存・読込の IOException を握りつぶす箇所が多く、呼び出し元へ成否を返しません。システム辞書更新はファイルサイズだけで判定し、同サイズの内容変更を検知できません。コピーも一時ファイルとの入れ替えではありません。さらにサービスと辞書ツールが各々 Dictionary を生成し、DB 接続の明示的 close がありません。キャッシュの一貫性や資源解放の所有者が不明確です。DB 破損を実証したわけではありません。

根拠: [Dictionary.java](https://github.com/kachaya-ime/AndroidSKK/blob/f5bf85c6d4482647531c608bc1037fc4206cea16/app/src/main/java/io/github/kachaya/skk/engine/Dictionary.java)、[DictionaryTool.java](https://github.com/kachaya-ime/AndroidSKK/blob/f5bf85c6d4482647531c608bc1037fc4206cea16/app/src/main/java/io/github/kachaya/skk/DictionaryTool.java)。

ビルド面では Wrapper、依存バージョン、同梱辞書 DB、辞書作成タスクが追跡されています。一方、`abortOnError false` と `checkReleaseBuilds false` により Lint は品質ゲートになっていません。辞書作成の入力に upstream の master とハッシュ未固定のダウンロードがあり、同じ生成結果を再現する保証も不足します。同梱 DB から APK を作ることと、DB 自体を同じ入力から再生成することは区別します。JDBM が古いことだけで脆弱と判断はしませんが、バイナリ依存の由来・保守・更新方法は確認対象です。

根拠: [app/build.gradle](https://github.com/kachaya-ime/AndroidSKK/blob/f5bf85c6d4482647531c608bc1037fc4206cea16/app/build.gradle)、[data/prepare.bat](https://github.com/kachaya-ime/AndroidSKK/blob/f5bf85c6d4482647531c608bc1037fc4206cea16/data/prepare.bat)。Apache-2.0 の表示はありますが、参照元コード・辞書・同梱ライブラリの権利関係全体を今回監査したわけではありません。作者のレビュー応答や設計変更への意向も未確認です。

## 採用判断

現状のまま大規模な追加機能を積むことは勧めません。貢献するなら、まず今回の最小再現ケースを正式な回帰テストへ移し、登録・辞書・ログ・学習禁止・接続失敗を直すことを優先します。そのうえでコアと Android 接続を分ける方向に合意できるかが重要です。

作者が基盤改修を受け入れるなら、既存の変換・画面・辞書に投資を活かせます。方向性が合わない場合、単純にフォークするだけでは同じ欠陥と保守負担を引き継ぐため、独自コアを続ける価値が高まります。こちらの受入仕様と検証用アプリを比較基準に使い、小規模な改修での学びを得てから基盤を決めるのが妥当です。Issue・PR の投稿や開発基盤の変更は、この評価では行っていません。
