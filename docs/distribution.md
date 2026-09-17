# 検証用 APK と配布前の確認

本プロジェクトは現在、プライベートリポジトリと ADB 導入で検証します。一般公開、Play の登録、本番署名鍵の作成・保管、プロジェクト自身の公開ライセンスの決定は、所有者が決める配布手続きです。これらの決定がなくても、開発版の実装と自動試験を進められます。

## 成果物と署名

現在の実機確認用は `build/distributions/bc1e24a/app-debug.apk` です。C-qによる次キーの引用、任意の▽／▼表示、確定直後の学習順位、標準ローマ字、候補一覧のページ表示を改善しました。SHA-256 は `2694b2e538b2abc9f2c4e18a75e106d7a0a63c663424ada3aa6acbaef9cf4770` です。前回と同じ開発署名で上書き更新できます。未コミット変更のないソースからの増分ビルドで、ローカルゲート `20260917T073149711933Z` が成功しています。API 35 の入力・設定・SAF E2E、API 26／35 の候補表示と配布APKの対応は [作業記録](work-status.md) と配布ディレクトリの台帳に記載しています。設定は版4へ移行し、旧標準の半角カナ C-q は引用へ変わります。半角カナは必要に応じて再割当し、表示記号は入力規則設定で有効にします。

前回の実機確認用は `build/distributions/f279fab/app-debug.apk` です。文末・最終行の編集、入力欄の非同期応答、C-m 後の継続編集、M-</> の範囲選択、候補なし登録の取消を修正しました。SHA-256 は `bdbefa0dc3a64ea129956d5ca086a77d61fb59e2a783ec17c687be6153fe3c70` です。前回と同じ開発署名で、アンインストールせず上書き更新できます。未コミット変更のないソースからの増分ビルドで、ローカルゲート `20260917T060634320143Z`、API 26／35 の入力と編集 E2E が成功しています。実アプリ・実機の受入とは区別します。詳細は [作業記録](work-status.md) を参照します。

前回の実機確認用は `build/distributions/81cb7ee/app-debug.apk` です。再帰登録・候補一覧・かな記号・編集キー・辞書一括適用の修正版です。SHA-256 は `ee7fdfeafa2ef70a502d9d50d54e7ecba1de4320ae85f4afd5e0dc7d53135e64` です。前回と同じ開発署名なのでアンインストールせず更新します。検証は [作業記録](work-status.md) に記載しています。入力設定は版3へ保存し、版1／2の読み込みと既存カスタムキーの保持に対応します。


前回の実機確認用は `build/distributions/7398c40/app-debug.apk` です。2026-09-17 の候補表示・公式 L 辞書取り込みの修正版です。SHA-256 は `d24e37ed3bf509510aa738d8b39b5e1fe44951c6b86e2bade8c6cc097d384503`、ソースは `7398c40` です。既存版と同じ開発署名を確認しており、アンインストールせず更新導入します。ローカルゲート `20260917T012947172046Z` が成功しています。生成物を削除したクリーンビルドではなく、未コミット変更のないソースからの増分ビルドです。

- `app/build/outputs/apk/debug/app-debug.apk`: 開発署名・デバッグ可能な検証版です。
- `app/build/outputs/apk/benchmark/app-benchmark.apk`: 開発署名・デバッグ不可・別パッケージの性能計測版です。
- `app/build/outputs/apk/release/app-release-unsigned.apk`: 本番署名を設定していない場合の未署名成果物です。このまま端末へ導入する配布版とは扱いません。

開発署名鍵はリポジトリに含めません。更新導入にはパッケージ名と署名の一致が必要です。署名が異なる場合にアンインストールして回避すると辞書と設定が失われるため、更新検証は `adb install -r` で行い、`pm clear` とアンインストールは使いません。

APK ごとに SHA-256、バイト数、ソースコミットと未コミット変更、ビルド環境、検証結果を記録します。秘密鍵や入力本文を検証記録に含めません。開発版は debug 専用試験サービスを含みますが、本番ソースセットには含めません。

## 依存関係と由来

2026-09-16 の `:app:dependencies --configuration releaseRuntimeClasspath` は次を示しました。

| 実行時依存 | 版 | 通知の根拠 |
| --- | --- | --- |
| Kotlin 標準ライブラリ | 2.1.20 | 配布 POM の Apache 2.0 指定 |
| JetBrains annotations | 13.0 | 配布 POM の Apache 2.0 指定 |
| ICU4J | 77.1 | 配布物のライセンス・第三者通知 |

`META-INF/Apache-2.0.txt`、`META-INF/icu-LICENSE.txt`、`META-INF/third-party-notices.txt` を core のリソースに置き、APK 内にも残します。Apache 本文は開発環境の `/usr/share/common-licenses/Apache-2.0` の原文です。依存関係を変更したら POM と成果物内の通知を再確認します。試験専用の JUnit、Robolectric、AndroidX test は release の実行時依存に含まれません。

DDSKK、AndroidSKK、ブラウザ版は [調査記録](phase1.md) と [レビュー](reviews/browser-extension-20260916.md) に従って挙動を参照します。参考実装のライセンスを理由なく本プロジェクト全体へ転用せず、コードの流用と挙動の独立実装を区別します。AZIK の由来と採用範囲は [専用記録](azik-profile.md) にあります。

## 再検証の実行

SDK を `local.properties` または `ANDROID_HOME` に指定し、`python3 scripts/verify-local.py` を実行します。既存キャッシュだけを使う場合は `--offline` を追加します。ホスト試験、コア・Android 単体、lint、3 種類の APK と試験 APK を構築し、通知の同梱、ZIP 整合性、ネットワーク権限と debug 専用サービスの除外を検査します。結果は `build/reports/local-verification/` に JSON と各コマンドの出力を残します。

## 最終検査

クリーンなソースから JVM・Android 単体、lint、APK を再構築し、専用エミュレーターで入力・設定・辞書・SAF・更新・表示を検証します。実行器は失敗、スキップ、未完了を成功として扱いません。端末設定を変更する実行器では、元の IME・設定・画面状態への復元も合格条件です。

対象実機とアプリ別の互換性は別の受入結果です。エミュレーターの成功を Xiaomi Pad 8、Bluetooth、外部アプリの操作感の保証へ読み替えません。

## 2026-09-16 の検証済み成果物

ソース `f025b9ddb546de6b2bc57b6f0ea40f7e91eed1a9` を生成物のない別チェックアウトで構築し、ローカルゲート `20260916T072547824347Z` が成功しました。結果を `build/reports/clean-verification/f025b9d/`、成果物と台帳を `build/distributions/f025b9d/` にコピーしています。

検証用は `build/distributions/f025b9d/app-debug.apk` です。SHA-256 は `47f5383788833cfc4d73aa4fad79045cdca19b68356c6488a176e716fa467a04` です。同 APK の API 26 入力試験 13 件、API 35 入力 13 件・設定 1 件・狭幅表示とキーボード操作 2 件・辞書 SAF 1 件が成功しました。

増分ビルドとは DEX 内容が異なるため、APK のバイト一致を検証結果として報告しません。クリーン版を別成果物として端末へ導入して確認しました。release は未署名のままで、一般公開はしていません。
