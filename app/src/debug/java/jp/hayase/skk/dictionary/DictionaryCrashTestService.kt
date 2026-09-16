package jp.hayase.skk.dictionary

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
        if (message.what != START_IMPORT || message.replyTo == null) return true
        val databaseName = message.data?.getString(DATABASE_NAME) ?: return true
        if (!DATABASE_NAME_PATTERN.matches(databaseName)) return true
        val replyTo = message.replyTo
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
        const val DATABASE_NAME = "databaseName"
        const val CHILD_PID = "childPid"
        const val SYSTEM_ID = "crash-system"

        private val DATABASE_NAME_PATTERN =
            Regex("dictionary-it-${UUID_PATTERN}\\.db")
        private const val UUID_PATTERN = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"
    }
}
