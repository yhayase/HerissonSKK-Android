package jp.hayase.skk

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Base64
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import jp.hayase.skk.core.DictionaryQuery
import jp.hayase.skk.core.dictionary.SkkDictionaryCodec
import jp.hayase.skk.dictionary.DictionaryManager
import jp.hayase.skk.dictionary.DictionaryManagerStatus
import jp.hayase.skk.dictionary.SQLiteDictionaryRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** 別プロセスの ContentProvider pipe が正常な本文の後に I/O エラーを返す境界を検証します。 */
@RunWith(AndroidJUnit4::class)
class CompleteBackupProviderFailureTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Test fun providerReadErrorCannotPrepareOrApplyRestore() {
        val fixtureId = UUID.randomUUID().toString()
        val databaseName = "backup-provider-failure-$fixtureId.db"
        val repository = SQLiteDictionaryRepository(context, databaseName)
        val serial = Executors.newSingleThreadExecutor()
        val main = Handler(Looper.getMainLooper())
        val manager = DictionaryManager(repository, serial, Executor { check(main.post(it)) },
            ownedExecutor = serial)
        var scenario: ActivityScenario<CompleteDictionaryBackupActivity>? = null
        try {
            repository.replacePersonal(SkkDictionaryCodec.parseText("かな /既存候補;注釈/\n"))
            load(manager)
            val revision = repository.dictionaryRevision()
            val candidates = manager.lookup(DictionaryQuery("かな")).map { it.text to it.annotation }
            val backup = ByteArrayOutputStream().also {
                repository.writeCompleteBackup(it, emptyList(), "provider-failure-test")
            }.toByteArray()
            assertTrue("試験用バックアップが異常に大きいです", backup.size in 1..32 * 1024)
            val uri = Uri.Builder().scheme("content").authority(CompleteBackupFaultProvider.AUTHORITY)
                .appendPath("read-error").appendPath(fixtureId)
                .appendQueryParameter("payload", Base64.encodeToString(backup,
                    Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING))
                .build()
            val opensBefore = providerOpenCount(uri)
            val stagedBefore = stagedFiles()

            CompleteDictionaryBackupActivity.managerFactoryForTest = { manager }
            val launched = ActivityScenario.launch(CompleteDictionaryBackupActivity::class.java)
            scenario = launched
            launched.onActivity { activity ->
                // picker の結果だけを直接渡し、復元の ContentResolver と provider transport は実物を通します。
                val pending = activity.javaClass.getDeclaredField("pendingPicker").apply { isAccessible = true }
                pending.set(activity, 702)
                val result = activity.javaClass.getDeclaredMethod(
                    "onActivityResult", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Intent::class.java,
                ).apply { isAccessible = true }
                result.invoke(activity, 702, Activity.RESULT_OK, Intent().setData(uri))
            }

            awaitStatus(launched, "バックアップの読み書きに失敗しました。")
            assertEquals("対象 Activity が障害 provider を一度だけ開いていません",
                opensBefore + 1, providerOpenCount(uri))
            assertEquals("別の試験用 URI が開かれました", fixtureId,
                context.contentResolver.call(uri, "read-stats", null, null)?.getString("lastFixtureId"))
            launched.onActivity { activity ->
                assertFalse("成功状態を表示しました", status(activity).contains("復元し、入力へ反映"))
                assertNull("失敗入力から復元確認を作りました", privateField(activity, "prepared"))
                assertNull("失敗入力で確認画面を開きました", privateField(activity, "preview"))
            }
            assertEquals(revision, repository.dictionaryRevision())
            assertEquals(candidates, manager.lookup(DictionaryQuery("かな")).map { it.text to it.annotation })
            assertEquals("検証用の一時 DB が残っています", stagedBefore, stagedFiles())
        } finally {
            var failure: Throwable? = null
            fun cleanup(action: () -> Unit) {
                try { action() } catch (error: Throwable) {
                    if (failure == null) failure = error else failure!!.addSuppressed(error)
                }
            }
            cleanup { scenario?.close() }
            CompleteDictionaryBackupActivity.managerFactoryForTest = null
            cleanup { manager.close() }
            cleanup {
                serial.shutdown()
                assertTrue("辞書管理器の終了を待てません", serial.awaitTermination(10, TimeUnit.SECONDS))
            }
            cleanup { context.deleteDatabase(databaseName) }
            failure?.let { throw it }
        }
    }

    private fun load(manager: DictionaryManager) {
        val complete = CountDownLatch(1)
        var status: DictionaryManagerStatus? = null
        manager.loadAsync { status = it; complete.countDown() }
        assertTrue("辞書の読込が完了しません", complete.await(10, TimeUnit.SECONDS))
        assertTrue("辞書の読込に失敗しました", status is DictionaryManagerStatus.Ready)
    }

    private fun awaitStatus(scenario: ActivityScenario<CompleteDictionaryBackupActivity>, expected: String) {
        val deadline = SystemClock.uptimeMillis() + 10_000
        var observed = ""
        while (SystemClock.uptimeMillis() < deadline) {
            scenario.onActivity { observed = status(it) }
            if (observed.contains(expected)) return
            SystemClock.sleep(50)
        }
        throw AssertionError("復元失敗状態を確認できません: $observed")
    }

    private fun status(activity: Activity): String =
        (privateField(activity, "status") as TextView).text.toString()

    private fun privateField(activity: Activity, name: String): Any? =
        activity.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(activity)

    private fun stagedFiles(): Set<String> =
        File(context.cacheDir, "dictionary-restore").listFiles()?.map { it.name }?.toSet().orEmpty()

    private fun providerOpenCount(uri: Uri): Int =
        requireNotNull(context.contentResolver.call(uri, "read-stats", null, null)).getInt("openCount")
}
