package jp.hayase.skk.dictionary

import jp.hayase.skk.core.dictionary.BackupRecord
import jp.hayase.skk.core.dictionary.BackupSourceKind
import jp.hayase.skk.core.dictionary.CompleteDictionaryBackupCodec

import android.app.Service
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.Process
import java.util.UUID
import java.util.concurrent.CountDownLatch
import jp.hayase.skk.core.dictionary.SkkDictionaryCodec

/** デバッグ APK のみで、未完了 SQLite トランザクションを別プロセスに保持します。 */
class DictionaryCrashTestService : Service() {
    private val messenger = Messenger(Handler(Looper.getMainLooper(), ::handleMessage))

    override fun onBind(intent: Intent): IBinder = messenger.binder

    private fun handleMessage(message: Message): Boolean {
        if (message.what !in listOf(START_IMPORT, RESTORE_BEFORE_COMMIT, RESTORE_AFTER_COMMIT) || message.replyTo == null) return true
        val databaseName = message.data?.getString(DATABASE_NAME) ?: return true
        if (!DATABASE_NAME_PATTERN.matches(databaseName)) return true
        val replyTo = message.replyTo
        if (message.what != START_IMPORT) {
            val mode = message.what
            Thread {
                try {
                    val stagingContext = object : android.content.ContextWrapper(applicationContext) {
                        override fun getCacheDir() = java.io.File(super.getCacheDir(), "crash-$databaseName").apply { mkdirs() }
                    }
                    fun stopAtBoundary() {
                        replyTo.send(Message.obtain(null, ROWS_CHANGED).apply {
                            data = android.os.Bundle().apply { putInt(CHILD_PID, Process.myPid()) }
                        })
                        CountDownLatch(1).await()
                    }
                    ValidatedDictionaryBackup.prepare(stagingContext, CompleteBackupCrashFixture.bytes().inputStream()).use { staged ->
                        SQLiteDictionaryRepository(applicationContext, databaseName, DictionaryWriteFailpoint { point ->
                            if (mode == RESTORE_BEFORE_COMMIT && point == DictionaryWritePoint.BEFORE_PUBLICATION) stopAtBoundary()
                        }).use { repository ->
                            repository.restoreComplete(staged, repository.dictionaryRevision())
                        }
                    }
                    // 復元のコミット後、検索スナップショットは一度も公開せずに停止します。
                    if (mode == RESTORE_AFTER_COMMIT) stopAtBoundary()
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                } catch (_: Throwable) {
                    // 辞書本文や内部ファイル名を IPC へ出しません。
                }
            }.apply { name = "dictionary-restore-crash-test" }.start()
            return true
        }
        Thread {
            val reachedRows = CountDownLatch(1)
            try {
                SQLiteDictionaryRepository(
                    applicationContext,
                    databaseName,
                    DictionaryWriteFailpoint { point ->
                        if (point == DictionaryWritePoint.AFTER_ROWS_CHANGED && reachedRows.count > 0) {
                            replyTo.send(Message.obtain(null, ROWS_CHANGED).apply {
                                data = android.os.Bundle().apply { putInt(CHILD_PID, Process.myPid()) }
                            })
                            reachedRows.countDown()
                            // 親試験がこの停止点でプロセスを終了します。
                            CountDownLatch(1).await()
                        }
                    },
                ).use { repository ->
                    repository.importSystem(
                        id = SYSTEM_ID,
                        name = "クラッシュ試験更新",
                        document = SkkDictionaryCodec.parseText("かな /新候補/\n"),
                        expectedGeneration = 1,
                    )
                }
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            } catch (_: Throwable) {
                // 試験は停止点でプロセスを終了します。DB の内容は IPC へ出しません。
            }
        }.apply { name = "dictionary-crash-test" }.start()
        return true
    }

    companion object {
        const val START_IMPORT = 1
        const val ROWS_CHANGED = 2
        const val RESTORE_BEFORE_COMMIT = 3
        const val RESTORE_AFTER_COMMIT = 4
        const val DATABASE_NAME = "databaseName"
        const val CHILD_PID = "childPid"
        const val SYSTEM_ID = "crash-system"

        private val DATABASE_NAME_PATTERN =
            Regex("dictionary-it-${UUID_PATTERN}\\.db")
        private const val UUID_PATTERN = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"
    }
}

/** 別プロセス試験専用の、小さく固定された完全バックアップです。 */
object CompleteBackupCrashFixture {
    fun bytes(): ByteArray {
        val records = sequenceOf(
            BackupRecord.Header("skk-android-dictionary-backup", 1, "試験"),
            BackupRecord.Source("personal", "復元個人", BackupSourceKind.PERSONAL, 7, true, 0),
            BackupRecord.Source("restored", "復元無効辞書", BackupSourceKind.SYSTEM, 9, false, 0),
            BackupRecord.Candidate("personal", "かな", 0, "復元個人", "注釈", null),
            BackupRecord.Candidate("restored", "おおk", 0, "多", "数量", "く"),
            BackupRecord.Suppression("gone", "かな", "孤立抑止", null),
            BackupRecord.SourceVersion("gone", 12),
            BackupRecord.SourceVersion("personal", 7),
            BackupRecord.SourceVersion("restored", 9),
            BackupRecord.End(2, 2, 1, 3),
        )
        return java.io.ByteArrayOutputStream().also {
            CompleteDictionaryBackupCodec().write(records, it)
        }.toByteArray()
    }
}
