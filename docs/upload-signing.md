# Google Play のアップロード署名

本書は Play App Signing 用のアップロード鍵の保管・復旧・AAB 署名手順です。提出用 AAB の個別のハッシュと判定は、その版の[成果物記録](releases/0.1.0-candidate.md)で管理します。

## 鍵の役割

Play App Signing では Google が生成するアプリ署名鍵を選びます。端末へ配信される APK は Google がその鍵で署名します。開発側は別のアップロード鍵を保持し、その鍵で AAB を署名して Console に提出します。Google 生成のアプリ署名秘密鍵をローカルで作成・取得・保管しません。外部配布には Console から取得する Google 署名済み APK を用い、Play 配布版と署名をそろえる方針です。

アップロード鍵を紛失・漏えいした場合は Console でリセットを申請できます。リセットは Google が保持するアプリ署名鍵を変更しません。初回のアプリ署名鍵の選択は更新互換性に影響するため、初回 AAB の登録時に設定を確認し、開発鍵やアップロード鍵をアプリ署名鍵として提供しません。[Android の署名案内](https://developer.android.com/studio/publish/app-signing)を提出時に確認します。

## 保管と復元

アップロード鍵はリポジトリ外に保管し、アクセス権を所有者に限定します。秘密鍵とパスワードをリポジトリ、CI 成果物、共同作業ログに置きません。パスワードはコマンド引数、環境変数、シェル履歴、Gradle 設定、ログ、文書に書かず、対話入力します。`.gitignore` の `*.jks` は誤追加の補助であり、鍵の保管場所にはなりません。

現行アップロード鍵の公開証明書 SHA-256 フィンガープリントは `72:ED:2A:6B:35:72:C1:57:54:43:A1:58:36:A6:24:51:54:8A:6D:06:7D:38:ED:98:5A:9E:B6:8A:49:6B:B2:BA` です。エイリアスは `herissonskk-upload`、鍵形式は PKCS12 です。公開証明書と指紋は秘密ではありませんが、秘密鍵とパスワードを推測できる情報を記録しません。

バックアップの復旧は、復元した鍵で使い捨て JAR を署名・検証し、元の鍵と公開証明書が一致することで確認します。証明書の一致だけを復旧成功とは扱いません。`RESTORED_STORE` には元の鍵と異なる場所の復元物を指定します。パスワードは `jarsigner` と `keytool` の対話画面で入力します。

```sh
set -eu
umask 077
UPLOAD_STORE="/鍵の保管先/upload.jks"
RESTORED_STORE="/復元先/upload.jks"
test -f "$UPLOAD_STORE" && test -f "$RESTORED_STORE"
test "$UPLOAD_STORE" != "$RESTORED_STORE"
TEST_DIR="$(mktemp -d)"
trap 'rm -rf "$TEST_DIR"' EXIT
printf '復旧試験\n' > "$TEST_DIR/payload.txt"
jar --create --file "$TEST_DIR/test.jar" -C "$TEST_DIR" payload.txt
jarsigner -keystore "$RESTORED_STORE" -storetype PKCS12 \
  -signedjar "$TEST_DIR/test-signed.jar" "$TEST_DIR/test.jar" herissonskk-upload
jarsigner -verify -verbose -certs "$TEST_DIR/test-signed.jar"
keytool -exportcert -keystore "$UPLOAD_STORE" -storetype PKCS12 \
  -alias herissonskk-upload -file "$TEST_DIR/original.der"
keytool -exportcert -keystore "$RESTORED_STORE" -storetype PKCS12 \
  -alias herissonskk-upload -file "$TEST_DIR/restored.der"
cmp "$TEST_DIR/original.der" "$TEST_DIR/restored.der"
```

`jarsigner` の出力で署名が有効なことを確認します。自己署名証明書を信頼チェーンに含められない場合、`jarsigner -verify -strict` は終了コード 4 になることがあります。使い捨て JAR は試験後に削除します。失敗したバックアップを復旧確認済みと扱いません。

## AAB の作成と提出前検査

`./gradlew :app:bundleRelease` で未署名 AAB を作ります。対象版の成果物記録に未署名ファイルの SHA-256、サイズ、アプリ ID、versionCode、versionName、SDK、権限、同梱通知を記録します。署名スクリプトは入力ハッシュを確認し、既存の署名先ファイルを上書きせず、対話入力でパスワードを受け取ります。署名後に `jarsigner -verify`、署名前後の ZIP 内容、署名者の公開証明書、SHA-256 を確認します。

```sh
set -eu
UNSIGNED_AAB="/成果物の保管先/app-release-unsigned.aab"
SIGNED_AAB="/成果物の保管先/app-release-upload-signed.aab"
test -f "$UNSIGNED_AAB" && test -f "$SIGNED_AAB"
keytool -printcert -file "/公開証明書の保管先/upload-public.pem"
keytool -printcert -jarfile "$SIGNED_AAB"
sha256sum "$UNSIGNED_AAB" "$SIGNED_AAB"
jarsigner -verify -verbose -certs "$SIGNED_AAB"
```

公開 AAB と掲載情報に同じ名称・版を使用し、更新では versionCode を増やします。Console に同じアプリ ID・versionCode が未登録であることを提出前に確認します。提出後は Console の **アップロード証明書**を現行の公開証明書と照合します。**アプリ署名証明書**は別の値になることが正常です。Play 経由で導入した版と Console から取得した配布 APK の署名を確認します。開発署名 APK から本番署名 APK への上書き更新はできないため、辞書・設定の退避と復元は[移行手順](package-migration.md)で扱います。
