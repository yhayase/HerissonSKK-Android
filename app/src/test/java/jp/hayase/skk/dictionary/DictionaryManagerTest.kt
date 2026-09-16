package jp.hayase.skk.dictionary

import android.content.Context
import java.util.UUID
import java.util.concurrent.Executor
import jp.hayase.skk.core.DictionaryQuery
import jp.hayase.skk.core.dictionary.DictionaryUnavailableException
import jp.hayase.skk.core.dictionary.DictionaryUnavailableReason
import jp.hayase.skk.core.dictionary.SkkDictionaryCodec
import jp.hayase.skk.core.dictionary.SkkDictionaryDocument
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class DictionaryManagerTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private val databases = mutableListOf<String>()

    @After fun removeDatabases() {
        databases.forEach(context::deleteDatabase)
    }

    @Test fun `起動中の検索は空辞書にせずI O完了後だけ公開する`() {
        val repository = SQLiteDictionaryRepository(context, databaseName())
        repository.replacePersonal(document("かな /公開候補/"), 0)
        val serial = ManualExecutor()
        var loads = 0
        val manager = DictionaryManager(
            repository, serial, Executor { it.run() },
            loadSnapshot = { loads++; repository.loadSnapshot() },
        )

        val unavailable = assertThrows(DictionaryUnavailableException::class.java) {
            manager.lookup(DictionaryQuery("かな"))
        }
        assertEquals(DictionaryUnavailableReason.INITIALIZING, unavailable.reason)
        assertEquals(0, loads)

        manager.loadAsync()
        assertEquals(0, loads)
        serial.runNext()

        assertEquals(1, loads)
        assertEquals(listOf("公開候補"), manager.lookup(DictionaryQuery("かな")).map { it.text })
        manager.close(); serial.runAll()
    }

    @Test fun `保留中の再読込は古いスナップショットを検索可能なまま保つ`() {
        val repository = SQLiteDictionaryRepository(context, databaseName())
        repository.replacePersonal(document("かな /旧/"), 0)
        val serial = ManualExecutor()
        val manager = DictionaryManager(repository, serial, Executor { it.run() })
        manager.loadAsync(); serial.runNext()

        manager.loadAsync()

        assertEquals(DictionaryManagerStatus.Ready(DictionaryFreshness.REFRESHING), manager.status)
        assertEquals(listOf("旧"), manager.lookup(DictionaryQuery("かな")).map { it.text })
        repository.replacePersonal(document("かな /新/"), 1)
        serial.runNext()
        assertEquals(listOf("新"), manager.lookup(DictionaryQuery("かな")).map { it.text })
        manager.close(); serial.runAll()
    }

    @Test fun `初回読込失敗は空結果にせず失敗理由を公開する`() {
        val repository = SQLiteDictionaryRepository(context, databaseName())
        val serial = ManualExecutor()
        val manager = DictionaryManager(
            repository, serial, Executor { it.run() },
            loadSnapshot = { error("読込不能") },
        )

        manager.loadAsync(); serial.runNext()

        assertEquals(DictionaryManagerStatus.Unavailable(DictionaryUnavailableReason.FAILED), manager.status)
        val unavailable = assertThrows(DictionaryUnavailableException::class.java) {
            manager.lookup(DictionaryQuery("かな"))
        }
        assertEquals(DictionaryUnavailableReason.FAILED, unavailable.reason)
        manager.close(); serial.runAll()
    }

    @Test fun `保存成功後の再読込失敗は旧公開世代を残して別結果で通知する`() {
        val repository = SQLiteDictionaryRepository(context, databaseName())
        repository.replacePersonal(document("かな /旧/"), 0)
        val serial = ManualExecutor()
        val callbacks = ManualExecutor()
        var loadCount = 0
        val manager = DictionaryManager(
            repository, serial, callbacks,
            loadSnapshot = {
                loadCount++
                if (loadCount == 1) repository.loadSnapshot() else error("再読込失敗")
            },
        )
        manager.loadAsync(); serial.runNext()
        assertEquals(listOf("旧"), manager.lookup(DictionaryQuery("かな")).map { it.text })

        var result: DictionaryManagerWriteResult<DictionarySourceInfo>? = null
        manager.replacePersonal(document("かな /保存済み/"), 1) { result = it }
        serial.runNext()

        assertTrue(result == null)
        assertEquals(DictionaryManagerStatus.Ready(DictionaryFreshness.STALE), manager.status)
        assertEquals(listOf("旧"), manager.lookup(DictionaryQuery("かな")).map { it.text })
        assertEquals(listOf("保存済み"), repository.lookup("かな").asComposite()
            .lookup(DictionaryQuery("かな")).map { it.text })
        callbacks.runNext()
        assertTrue(result is DictionaryManagerWriteResult.SavedButNotApplied)
        manager.close(); serial.runAll()
    }

    @Test fun `書込失敗は公開済み世代と保存済み内容を変えずcallback executorで通知する`() {
        val repository = SQLiteDictionaryRepository(context, databaseName())
        repository.replacePersonal(document("かな /旧/"), 0)
        val serial = ManualExecutor()
        val callbacks = ManualExecutor()
        val manager = DictionaryManager(repository, serial, callbacks)
        manager.loadAsync(); serial.runNext()

        var result: DictionaryManagerWriteResult<DictionarySourceInfo>? = null
        manager.replacePersonal(document("かな /失敗/"), 0) { result = it }
        serial.runNext()

        assertTrue(result == null)
        assertEquals(listOf("旧"), manager.lookup(DictionaryQuery("かな")).map { it.text })
        assertEquals(listOf("旧"), repository.lookup("かな").asComposite()
            .lookup(DictionaryQuery("かな")).map { it.text })
        callbacks.runNext()
        assertEquals(DictionaryManagerWriteResult.Failed, result)
        manager.close(); serial.runAll()
    }

    @Test fun `状態購読はcallback executorで行い解除後は通知しない`() {
        val repository = SQLiteDictionaryRepository(context, databaseName())
        val serial = ManualExecutor()
        val callbacks = ManualExecutor()
        val manager = DictionaryManager(repository, serial, callbacks)
        val states = mutableListOf<DictionaryManagerStatus>()
        val subscription = manager.observe(states::add)

        assertTrue(states.isEmpty())
        callbacks.runNext()
        assertEquals(listOf(DictionaryManagerStatus.Loading), states)
        subscription.close()
        manager.loadAsync(); serial.runNext()
        callbacks.runAll()

        assertEquals(listOf(DictionaryManagerStatus.Loading), states)
        manager.close(); serial.runAll()
    }

    @Test fun `close前に保留した読込と通知は公開も配送もせず検索を無効化する`() {
        val repository = SQLiteDictionaryRepository(context, databaseName())
        repository.replacePersonal(document("かな /公開してはいけない/"), 0)
        val serial = ManualExecutor()
        val callbacks = ManualExecutor()
        val manager = DictionaryManager(repository, serial, callbacks)
        val states = mutableListOf<DictionaryManagerStatus>()
        manager.observe(states::add)
        manager.loadAsync()

        manager.close()
        assertThrows(IllegalStateException::class.java) { manager.observe(states::add) }
        serial.runAll()
        callbacks.runAll()

        assertTrue(states.isEmpty())
        assertEquals(DictionaryManagerStatus.Unavailable(DictionaryUnavailableReason.FAILED), manager.status)
        assertThrows(DictionaryUnavailableException::class.java) {
            manager.lookup(DictionaryQuery("かな"))
        }
    }

    @Test fun `辞書順の要求値はキュー投入時に固定し一覧も非同期で返す`() {
        val repository = SQLiteDictionaryRepository(context, databaseName())
        repository.importSystem("a", "A", document("かな /A/"))
        repository.importSystem("b", "B", document("かな /B/"))
        val serial = ManualExecutor()
        val callbacks = ManualExecutor()
        val manager = DictionaryManager(repository, serial, callbacks)
        val requested = mutableListOf("b", "a")
        manager.setSystemOrder(requested) { }
        requested.reverse()
        var sources: DictionaryManagerWriteResult<List<DictionarySourceInfo>>? = null
        manager.listSources { sources = it }

        serial.runNext()
        serial.runNext()
        assertTrue(sources == null)
        callbacks.runAll()

        val listed = (sources as DictionaryManagerWriteResult.Applied).value
        assertEquals(listOf("personal", "b", "a"), listed.map { it.id })
        manager.close(); serial.runAll()
    }

    private fun document(text: String): SkkDictionaryDocument = SkkDictionaryCodec.parseText(text)

    private fun databaseName(): String = "dictionary-manager-${UUID.randomUUID()}.db".also(databases::add)

    private class ManualExecutor : Executor {
        private val tasks = ArrayDeque<Runnable>()
        override fun execute(command: Runnable) { tasks.addLast(command) }
        fun runNext() { tasks.removeFirst().run() }
        fun runAll() { while (tasks.isNotEmpty()) runNext() }
    }
}
