package jp.hayase.skk;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.util.Base64;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/** instrumentation APK だけに含む、対象アプリ UID 専用の読取障害 provider です。 */
public final class CompleteBackupFaultProvider extends ContentProvider {
    public static final String AUTHORITY = "jp.hayase.skk.test.completebackupfault";
    private static final String TARGET_PACKAGE = "jp.hayase.skk";
    private static final int MAX_ENCODED_CHARS = 43_692;
    private static final int MAX_PAYLOAD_BYTES = 32 * 1024;
    private static final AtomicInteger OPEN_COUNT = new AtomicInteger();
    private static volatile String lastOpenedFixtureId;

    @Override public boolean onCreate() { return true; }

    @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        requireTargetUid();
        List<String> segments = uri.getPathSegments();
        Set<String> names = uri.getQueryParameterNames();
        if (!"r".equals(mode) || !"content".equals(uri.getScheme()) ||
                !AUTHORITY.equals(uri.getAuthority()) || segments.size() != 2 ||
                !"read-error".equals(segments.get(0)) || names.size() != 1 ||
                !names.contains("payload") || uri.getQueryParameters("payload").size() != 1) {
            throw new FileNotFoundException("試験用 URI の形式が不正です");
        }
        String fixtureId = segments.get(1);
        try {
            if (!UUID.fromString(fixtureId).toString().equals(fixtureId)) {
                throw new FileNotFoundException("UUID が不正です");
            }
        } catch (IllegalArgumentException invalid) {
            throw new FileNotFoundException("UUID が不正です");
        }
        String encoded = uri.getQueryParameter("payload");
        if (encoded == null || encoded.length() < 1 || encoded.length() > MAX_ENCODED_CHARS ||
                !encoded.matches("[A-Za-z0-9_-]+")) {
            throw new FileNotFoundException("payload が不正です");
        }
        final byte[] bytes;
        try {
            bytes = Base64.decode(encoded, Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
        } catch (IllegalArgumentException invalid) {
            throw new FileNotFoundException("payload が不正です");
        }
        if (bytes.length == 0 || bytes.length > MAX_PAYLOAD_BYTES) {
            throw new FileNotFoundException("payload が大きすぎます");
        }
        final ParcelFileDescriptor[] pipe;
        try {
            pipe = ParcelFileDescriptor.createReliablePipe();
        } catch (IOException failed) {
            FileNotFoundException wrapped = new FileNotFoundException("試験用 pipe を作成できません");
            wrapped.initCause(failed);
            throw wrapped;
        }
        final ParcelFileDescriptor writer = pipe[1];
        lastOpenedFixtureId = fixtureId;
        OPEN_COUNT.incrementAndGet();
        new Thread(() -> {
            try {
                FileOutputStream output = new FileOutputStream(writer.getFileDescriptor());
                output.write(bytes);
                output.flush();
                writer.closeWithError("注入した provider 読取障害");
            } catch (Exception failure) {
                try { writer.closeWithError("provider が途中で終了しました"); }
                catch (IOException ignored) { /* すでに pipe が閉じています。 */ }
            }
        }, "backup-fault-pipe").start();
        return pipe[0];
    }

    @Override public Bundle call(String method, String arg, Bundle extras) {
        requireTargetUid();
        if (!"read-stats".equals(method) || arg != null || extras != null) {
            throw new UnsupportedOperationException("試験用の固定状態以外は提供しません");
        }
        Bundle result = new Bundle();
        result.putInt("openCount", OPEN_COUNT.get());
        result.putString("lastFixtureId", lastOpenedFixtureId);
        return result;
    }

    @Override public Cursor query(Uri uri, String[] projection, String selection,
            String[] selectionArgs, String sortOrder) {
        throw new UnsupportedOperationException("読取 pipe 以外は提供しません");
    }

    @Override public String getType(Uri uri) {
        throw new UnsupportedOperationException("型情報は提供しません");
    }

    @Override public Uri insert(Uri uri, ContentValues values) {
        throw new UnsupportedOperationException("書込は提供しません");
    }

    @Override public int delete(Uri uri, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException("削除は提供しません");
    }

    @Override public int update(Uri uri, ContentValues values, String selection,
            String[] selectionArgs) {
        throw new UnsupportedOperationException("更新は提供しません");
    }

    private void requireTargetUid() {
        if (getContext() == null) throw new SecurityException("provider が初期化されていません");
        final int targetUid;
        try {
            targetUid = getContext().getPackageManager().getApplicationInfo(TARGET_PACKAGE, 0).uid;
        } catch (PackageManager.NameNotFoundException missing) {
            throw new SecurityException("試験対象アプリがありません", missing);
        }
        if (Binder.getCallingUid() != targetUid) {
            throw new SecurityException("試験対象アプリ以外は利用できません");
        }
    }
}
