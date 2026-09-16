# 検証用 APK と配布前の確認

本プロジェクトは現在、プライベートリポジトリと ADB 導入で検証します。一般公開、Play の登録、本番署名鍵の作成・保管、プロジェクト自身の公開ライセンスの決定は、所有者が決める配布手続きです。これらの決定がなくても、開発版の実装と自動試験を進められます。

## 成果物と署名

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
