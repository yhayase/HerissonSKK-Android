# 要件と試験の対応

本書は要件と試験クラス・ランナーの対応、試験が示す範囲を示します。対応する試験があることは、任意の APK で合格したことや実機で受け入れたことを意味しません。対象版の実行結果は PR・CI・リリース成果物に記録します。試験の選択と受入条件は[検証手順](validation-plan.md)を参照します。

| 証拠の層 | 主な試験・記録 | 示せる範囲 |
| --- | --- | --- |
| コア JVM | `BasicSkkEngineTest`、登録・補完・数値・書記素・辞書形式・複合辞書の試験 | Android と独立した入力状態、候補・注釈・順序、境界・不変条件 |
| Android 単体 | `EditorSessionTest`、`EditorEditPortTest`、`DictionaryManagerTest`、`CustomizationStoreTest`、各 Activity 試験 | 入力接続の状態と失敗、保存・世代・競合・公開、設定の適用 |
| Android SQLite | `SQLiteDictionaryAndroidTest`、`DictionaryCrashRecoveryTest`、`CompleteDictionaryBackupAndroidTest`、`CandidateStatusAndroidTest` | 実 SQLite の容量不足、未コミット終了、v1/v2 移行、復元・台帳、長文表示用状態 |
| 専用エミュレーター | `PhysicalInputTest`、`SafDictionaryE2eTest`、`CustomizationE2eTest`、`CompleteBackupSafE2eTest`、`CandidateDisplayE2eTest` | 別アプリの代表入力欄、物理キー、SAF、狭幅・倍率表示 |
| 更新・障害境界 | `UpgradePersistenceSeedTest`／`UpgradePersistenceTest`、`CompleteBackupProviderFailureTest`、各専用ランナー | 同署名APK更新の独立fixtureと別プロセスの実provider読取障害を検証。実利用データや任意 provider の保証ではない |
| ホスト・成果物 | Python ランナー試験、`verify-local.py`、`distribution.md` | 失敗・スキップ判定、APK・通知・権限などの検査。対象成果物のハッシュを記録します |

## 機能要件 F01〜F15

| ID | 対応する試験・範囲 | 範囲外・追加判定 |
| --- | --- | --- |
| F01・F02 | 基本エンジン、ローマ字・文字種・未消化状態の JVM 試験、`PhysicalInputTest` の代表キー列 | 実機の配列・対象アプリと表示を受入確認します |
| F03 | 送り、abbrev、接辞と送り条件のコア・複合辞書試験、入力 E2E の代表列 | 実辞書分布と対象アプリで送り・注釈を確認します |
| F04 | 候補往復・一覧選択・注釈・取消のコア試験、入力 E2E、長文・狭幅・倍率の表示試験を扱う | 対象アプリの実入力と実機の読取りは別の判定です |
| F05 | 内部カーソル、途中挿入・削除、読み直しのコア・`EditorSession` 試験と入力 E2E | 対象実機・アプリでの物理キー配送は別の判定です |
| F06 | 再帰登録、本文分離、内側完了、失敗・遅延完了と登録内編集のコア・接続試験、SAF E2E の代表登録 | 複数アプリの接続切断・切替は代表 E2E の外です |
| F07 | SQLite 個人候補、学習順位、削除・抑止・解除、故障時 rollback、SAF E2E の再読込、同署名旧 APK からの独立 fixture 更新を扱う | 独立 fixture は実利用データの更新を保証しません |
| F08 | `DictionarySettingsActivityTest` と repository の追加・更新・削除・有効状態・順序、完全バックアップ E2E | 合成辞書は実辞書の分布と容量を代表しません |
| F09 | `SkkDictionaryCodecTest` の UTF-8／EUC-JP・注釈・送り・順序、SAF E2E、別プロセスの実 provider が正常本文後に返す読取障害を扱う | 出力先 close 障害の実 provider 経路と機種別 SAF を確認します。出力 close は単体の障害注入のみです |
| F10 | 保護欄の SKK 迂回、学習禁止欄の変換継続、保存許可の受付時・書込直前検査をコア・接続・入力 E2E で確認 | 実アプリの入力種別・OS 診断経路は単体試験の外です |
| F11 | 通常／動的補完の受諾・取消・未受諾分離、数値八形式のコア・接続・SAF E2E | 実辞書の負荷と対象アプリの挙動は別に評価します |
| F12 | `CustomizationStoreTest`、`CustomizationActivityTest`、キー衝突と AZIK のコア試験、入力設定 E2E、表示境界の端末試験 | 対象実機・物理配列は単体試験の外です |
| F13 | 内部と入力先の任意 Emacs 編集、無効時の引渡し、Unicode と連続編集を `EditorEditPortTest` と入力設定 E2E で確認 | 周辺文字や選択範囲を返さない入力先では編集能力が制限されます |
| F14 | `HardwareKeyMapperTest`、`KeyPressLedgerTest`、入力欄・選択通知の接続試験 | 合成キーは US／JIS 実機配送、AltGr・デッドキー、Bluetooth 接続変更を代替しません |
| F15 | `CandidateStatusViewTest`、`CandidateStatusServiceTest`、入力 E2E と長文・倍率・狭幅の専用表示試験 | 実機での読取り・操作感は自動試験の外です |

## 追加機能

| 要件 | 対応する試験と境界 |
| --- | --- |
| F16 画面 QWERTY | `TouchInputTest`、`TouchKeyboardControllerTest`、候補・登録表示試験。配列、Shift、フリック、候補選択・取消、物理接続時の表示を扱います。実際の押しやすさは自動試験の対象外です |
| F20 ネットワーク辞書 | `NetworkDictionaryDownloaderTest`、`NetworkDictionaryLiveTest`、辞書設定 UI 試験。HTTPS 制約、明示取得・取消、再取得、旧辞書保持と実 URL の経路を分けます。SAF 試験だけを通信の証拠にしません |
| F21 初期 S 辞書 | `InitialDictionaryInstallerTest` と初期設定 UI 試験。取得失敗時の再試行、S／L の選択競合、debug fixture と release の取得経路を区別します |
| F22 アプリ別 Emacs | `AppEmacsEditingStoreTest`、`AppEmacsSettingsActivityTest`、`CustomizationE2eTest`。継承、保存失敗、セッション固定と入力先の切替を扱います |
| F23 IME 切替 | `ImeSwitchServiceTest` と入力 E2E。タッチ・物理の双方を扱います |
| 設定 UI・候補表示 | 設定 Activity の単体試験、`SettingsLayoutTest`、`CandidateDisplayE2eTest`。狭幅・文字倍率と入力欄の可視性を扱い、利用者の読取り・操作感は別に判断します |

数値変換・日付、回転後の変換状態保持、再変換、端末間同期の優先順位は[ロードマップ](roadmap.md)を参照します。

## 設定要件 S01〜S08

| ID | 対応する試験・範囲 | 範囲外・追加判定 |
| --- | --- | --- |
| S01 | 新しい通常欄のひらがなと保護欄の直接通過は入力 E2E。物理キーボード接続時の文字キー非表示は `KeyboardVisibilityTest`・`KeyboardVisibilityServiceTest`。物理配列設定への案内は設定画面の単体試験 | 案内先と接続・切断時の端末表示を確認します。初回必須の文字キー画面は上表で別に管理します |
| S02 | 標準／変更キー、衝突拒否と初期化、Emacs オン／オフ・入力先編集の設定試験と E2E | OS 配列・対象アプリのショートカット、設定画面のキーボードのみの操作を確認します。アプリ別オン／オフは初回追加要件、アプリ別個別キー割り当ては後続2です |
| S03 | 標準・CUSTOM・AZIK 規則、保存時検証と復旧、入力設定 E2E | 対象実機で規則の入力と復旧を確認します。規則ファイル入出力はその他の後続、入力試行は初回UI設計で必要性を判断します |
| S04 | 句読点・括弧の選択とセッション固定をコア・画面・E2E で確認 | 全記号・モードの適用範囲を要件の未決部分と照合します。記号別上書きは提案です |
| S05 | `asdfjkl`／`1234567`、固定／AUTO ページのコア・設定試験。`RedesignedCandidateViewTest` は長い候補本文の折返しと無省略、注釈の長押し詳細、指離しで確定しないことを扱います。`CandidateDisplayE2eTest` は代表候補の狭幅・文字倍率での可視性と長押し中の未確定維持を扱います | 実機の操作感は自動試験の外です。一覧開始位置は実装済みです。任意ラベル列の編集は後続3です |
| S06 | `DictionarySettingsActivityTest`、repository と SAF E2E の追加・マージ／置換・更新・削除・有効化・順序、保存抑止。各ソースの状態表示を単体試験で扱う | 実ファイルと provider 失敗、状態表示とキーボード操作の端末確認が残ります。出典・件数・日時表示と個人辞書消去は提案です |
| S07 | 動的補完の初期値オフと設定切替、通常補完と受諾の分離、数値変換のコア・接続試験 | 設定変更を通した実機の操作感は自動試験の外です。数値のオン／オフ、日付・再変換・ネットワーク設定は確定済み必須ではありません |
| S08 | AtomicFile 設定の検証、世代競合、保存失敗・再読込・初期化、個人辞書と分離した復旧を単体・設定 E2E で確認。一般設定 3 項目にも非同期 commit と失敗表示・再試行を追加 | 一般設定の障害・回転・再試行、キーボードだけの移動・保存・取消を分けて検証します。設定ファイル入出力とカテゴリ初期化は提案です |

一般設定の保存中は対象スイッチを保留表示にし、成功を `commit()` が true を返した後だけ表示します。保存失敗時は変更前のキーの有無と値へ戻し、再試行を示します。個人データ保存をオフにする操作は待機中の書込許可を直ちに失効させ、失敗しても同じプロセス内で自動再許可しません。失敗したオフは画面再作成後もオフ・未保存として表示します。保存不能のままプロセスが終了すると以前保存したオンへ戻る可能性があるため、画面で明示します。永続領域への保存が失敗した後のプロセスを越えるオフ保証はありません。

## 非機能要件 N01〜N12

| ID | 対応する試験・範囲 | 範囲外・追加判定 |
| --- | --- | --- |
| N01・N02 | Android に依存しない `core` JVM 試験、境界・生成操作列、失敗再現と設定別の回帰試験 | 要件ごとの変更時に維持します。件数を網羅率と見なしません |
| N03 | 接続単体と `PhysicalInputTest` の入力 E2E | Xiaomi Pad 8 と優先アプリで Enter、欄変更、選択変更、長押しを受入確認します |
| N04 | SQLiteFull、未コミット子プロセス終了、公開失敗後の旧 snapshot と再読込、入力欄の古い完了拒否、同署名更新の独立 fixture、実 provider 読取障害 | 実利用データの更新と実端末の接続・停止を確認します。電源断試験とは同一視しません |
| N05 | 保護欄迂回、学習禁止、ユーザー保存無効、待機中の方針変更のテスト | 静的なログ経路の確認と、OS・実アプリの入力種別・診断挙動は別の検証です |
| N06 | [合成辞書性能測定](dictionary-performance.md) と [入力接続の性能測定](performance.md) | エミュレーターの測定値を実機の性能値に転用しません |
| N07 | Gradle 定義の minSdk と端末試験 | 更新後の OS・アプリ版・配列・接続と制約を記録します |
| N08 | ICU4J／Unicode 16 の書記素コーパスと Android 代表試験、入力先編集の境界検査 | 対象アプリの限られた周辺文字・選択範囲で制約を確認します |
| N09 | 候補の有界プレビューは `CandidateStatusViewTest`、長い本文の分割・復元は `RedesignedCandidateViewTest`。幅・高さ、狭幅・文字倍率での代表候補の配置は `CandidateDisplayE2eTest` | 巨大本文の実画面スクロール、実機の読取り・フォーカス、読み上げはこれらの試験だけでは保証しません |
| N10 | コア、永続化、接続、表示・設定のモジュール分離と差替え可能な試験 | 構造上の分離だけでは接続の正しさを保証しません |
| N11 | Gradle 定義、JVM・Android・ホスト試験、lint と APK 通知・権限ゲート | 公開候補の commit と成果物を特定して検査します |
| N12 | [配布前確認](distribution.md) の依存通知とバックアップ、SQLite v2 移行・完全復元、API 26／30／35 の同署名更新保持と配布物検査 | 実利用データの更新は独立 fixture 試験で保証しません。本番署名・ライセンス・公開条件は成果物ごとに判断します |

## 重要な検証限界

- 実 SQLite の障害注入は実際の電源断や任意の文書プロバイダーの失敗を保証しません。出力先 close の障害は単体注入で扱います。
- 入力先がカーソル座標や周辺文字を返さない場合、注釈表示と外部編集の能力には制約があります。[入力 UI 設計](input-ui-design.md)と[編集設計](emacs-editing-design.md)を参照します。
- 辞書の適用前プレビューは件数・文字コード等を示します。本文標本は表示せず、自動文字コード判別の意味上の正しさまでは保証しません。
- 辞書読込中の待機操作キューは現行コードで 1,024 件に制限されています。上限を超えた入力を消費したまま保持しない経路があり、「受理した入力を失わない」という[キャッシュ設計の契約](dictionary-storage-cache-design.md)との未解決の差です。1,025 件目を含む回帰試験と実装修正が必要です。キャッシュ容量と外部編集キューの上限とは別です。
