package jp.hayase.skk.dictionary

import jp.hayase.skk.core.dictionary.BackupRecord
import jp.hayase.skk.core.dictionary.CompleteDictionaryBackupCodec

import android.app.ActivityManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.Process
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import jp.hayase.skk.core.DictionaryQuery
import jp.hayase.skk.core.dictionary.SkkDictionaryCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** 別プロセスを SQLite の行更新後・コミット前に終了し、ロールバックを実証します。 */
@RunWith(AndroidJUnit4::class)
class DictionaryCrashRecoveryTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val serviceComponent = ComponentName(context, DictionaryCrashTestService::class.java)

    @Test fun `別プロセスを行更新後の未完了トランザクションで終了しても旧世代だけが再公開される`() {
        crashAndAssert(DictionaryCrashTestService.START_IMPORT)
    }

    @Test fun `完全復元のコミット直前のプロセス終了で全旧状態が残る`() {
        crashAndAssert(DictionaryCrashTestService.RESTORE_BEFORE_COMMIT)
    }

    @Test fun `完全復元のコミット後のプロセス終了で未公開でも全復元状態が残る`() {
        crashAndAssert(DictionaryCrashTestService.RESTORE_AFTER_COMMIT)
    }

    private fun crashAndAssert(operation: Int) {
        val databaseName = "dictionary-it-${UUID.randomUUID()}.db"
        val barrier = CountDownLatch(1)
        val connected = CountDownLatch(1)
        val binderDied = CountDownLatch(1)
        var childPid: Int? = null
        var connectedComponent: ComponentName? = null
        var remote: Messenger? = null
        var bound = false
        val callback = Messenger(Handler(Looper.getMainLooper()) { message ->
            if (message.what == DictionaryCrashTestService.ROWS_CHANGED) {
                childPid = message.data.getInt(DictionaryCrashTestService.CHILD_PID, NO_PID)
                barrier.countDown()
            }
            true
        })
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, service: IBinder) {
                connectedComponent = name
                remote = Messenger(service)
                service.linkToDeath({ binderDied.countDown() }, 0)
                connected.countDown()
            }

            override fun onServiceDisconnected(name: ComponentName) = Unit
        }

        var primaryFailure: Throwable? = null
        try {
            seed(databaseName)
            if (operation != DictionaryCrashTestService.START_IMPORT) {
                SQLiteDictionaryRepository(context, databaseName).use { repository ->
                    repository.deleteCandidate(DeleteCandidateRequest(1, listOf(StoredCandidateOriginRef(
                        DictionaryCrashTestService.SYSTEM_ID, 1, CandidateOriginKind.STORED_SYSTEM,
                        "かな", "旧候補", null,
                    ))), emptyMap())
                    repository.setSourceEnabled(DictionaryCrashTestService.SYSTEM_ID, false)
                }
            }
            val oldBytes = SQLiteDictionaryRepository(context, databaseName).use { export(it) }
            val oldRevision = SQLiteDictionaryRepository(context, databaseName).use { it.dictionaryRevision() }
            bound = context.bindService(Intent().setComponent(serviceComponent), connection, Context.BIND_AUTO_CREATE)
            assertTrue("クラッシュ試験サービスへ接続できません", bound)
            assertTrue("サービス接続がタイムアウトしました", connected.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
            assertEquals(serviceComponent, connectedComponent)
            remote!!.send(Message.obtain(null, operation).apply {
                data = Bundle().apply { putString(DictionaryCrashTestService.DATABASE_NAME, databaseName) }
                replyTo = callback
            })
            assertTrue("作業プロセスが行更新後の停止点へ到達しません", barrier.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
            context.unbindService(connection)
            bound = false
            // BIND_AUTO_CREATE による再起動を防いだ後、終了直前の PID を再確認します。
            val verifiedPid = verifyChildPid(requireNotNull(childPid) { "作業プロセスから PID が届きません" })
            Process.killProcess(verifiedPid)
            assertTrue("作業プロセスの Binder 終了が届きません", binderDied.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
            await("作業プロセスの終了") { !childProcessExists() }
            if (operation == DictionaryCrashTestService.RESTORE_AFTER_COMMIT) {
                assertRestoredFixture(databaseName, oldRevision)
            } else {
                if (operation == DictionaryCrashTestService.START_IMPORT) assertOldFixture(databaseName)
                SQLiteDictionaryRepository(context, databaseName).use {
                    org.junit.Assert.assertArrayEquals(oldBytes, export(it))
                    assertEquals(oldRevision, it.dictionaryRevision())
                    assertTrue(it.loadSnapshot().allowFallback)
                }
            }
        } catch (failure: Throwable) {
            primaryFailure = failure
            throw failure
        } finally {
            var cleanupFailure: Throwable? = null
            try {
                if (bound) context.unbindService(connection)
                context.stopService(Intent().setComponent(serviceComponent))
                val knownOrRunningChild = childPid?.takeIf(::isVerifiedChildPid) ?: runningChildPid()
                if (knownOrRunningChild != null) Process.killProcess(knownOrRunningChild)
                await("作業プロセスの後始末") { !childProcessExists() }
                check(java.io.File(context.cacheDir, "crash-$databaseName").let { !it.exists() || it.deleteRecursively() })
                check(context.deleteDatabase(databaseName)) { "試験用データベースを削除できません" }
            } catch (failure: Throwable) {
                cleanupFailure = failure
            }
            if (cleanupFailure != null) {
                primaryFailure?.addSuppressed(cleanupFailure) ?: throw cleanupFailure
            }
        }
    }

    private fun seed(name: String) {
        SQLiteDictionaryRepository(context, name).use { repository ->
            assertEquals(1L, repository.replacePersonal(document("かな /個人旧/\n")).generation)
            assertEquals(1L, repository.importSystem(
                DictionaryCrashTestService.SYSTEM_ID,
                "クラッシュ試験旧",
                document("かな /旧候補/\n"),
            ).generation)
        }
    }

    private fun assertOldFixture(name: String) {
        SQLiteDictionaryRepository(context, name).use { repository ->
            val sources = repository.listSources().associateBy { it.id }
            assertEquals(2, sources.size)
            assertEquals(1L, sources[SQLiteDictionaryRepository.PERSONAL_SOURCE_ID]?.generation)
            assertEquals(1L, sources[DictionaryCrashTestService.SYSTEM_ID]?.generation)
            assertEquals(listOf("個人旧", "旧候補"), repository.loadSnapshot().asComposite()
                .lookup(DictionaryQuery("かな")).map { it.text })
            assertFalse(repository.exportPersonal().decodeToString().contains("新候補"))
        }
    }

    private fun export(repository: SQLiteDictionaryRepository): ByteArray = java.io.ByteArrayOutputStream().also {
        repository.writeCompleteBackup(it, emptyList(), "試験")
    }.toByteArray()

    private fun assertRestoredFixture(name: String, oldRevision: Long) {
        SQLiteDictionaryRepository(context, name).use { repository ->
            assertEquals(oldRevision + 1, repository.dictionaryRevision())
            assertFalse(repository.loadSnapshot().allowFallback)
            val records = mutableListOf<BackupRecord>()
            CompleteDictionaryBackupCodec().validate(export(repository).inputStream(), records::add)
            val expected = mutableListOf<BackupRecord>()
            CompleteDictionaryBackupCodec().validate(CompleteBackupCrashFixture.bytes().inputStream(), expected::add)
            val normalized = records.mapNotNull { record ->
                when (record) {
                    is BackupRecord.Source -> record.copy(generation = record.generation - 1)
                    is BackupRecord.SourceVersion -> when(record.sourceId) {
                        DictionaryCrashTestService.SYSTEM_ID -> { assertEquals(1L, record.lastGeneration); null }
                        "personal", "restored" -> record.copy(lastGeneration = record.lastGeneration - 1)
                        else -> record
                    }
                    is BackupRecord.End -> record.copy(sourceVersionCount = record.sourceVersionCount - 1)
                    else -> record
                }
            }
            assertEquals(expected, normalized)
            assertEquals(listOf("復元個人"), repository.loadSnapshot().asComposite().lookup(DictionaryQuery("かな")).map { it.text })
        }
    }

    private fun verifyChildPid(pid: Int): Int {
        assertNotEquals("作業プロセスが計測プロセスと同一です", Process.myPid(), pid)
        assertTrue("作業 PID が宣言済みの同一 UID 子プロセスではありません", isVerifiedChildPid(pid))
        return pid
    }

    private fun isVerifiedChildPid(pid: Int): Boolean = runningProcesses().any {
        it.pid == pid && it.uid == appUid && it.processName == childProcessName
    }

    private fun runningChildPid(): Int? = runningProcesses().singleOrNull {
        it.uid == appUid && it.processName == childProcessName && it.pid != Process.myPid()
    }?.pid

    private fun childProcessExists(): Boolean = runningChildPid() != null

    @Suppress("DEPRECATION")
    private fun runningProcesses(): List<ActivityManager.RunningAppProcessInfo> =
        (context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).runningAppProcesses.orEmpty()

    private fun await(name: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS)
        while (!condition() && System.nanoTime() < deadline) Thread.sleep(POLL_MILLIS)
        check(condition()) { "$name がタイムアウトしました" }
    }

    private fun document(text: String) = SkkDictionaryCodec.parseText(text)

    private val childProcessName get() = "${context.packageName}:dictionary_crash_test"
    private val appUid get() = context.applicationInfo.uid

    private companion object {
        const val NO_PID = -1
        const val TIMEOUT_SECONDS = 10L
        const val POLL_MILLIS = 25L
    }
}
