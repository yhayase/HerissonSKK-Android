package jp.hayase.skk.dictionary

import jp.hayase.skk.core.dictionary.SkkDictionaryCandidate
import android.content.Context
import android.database.sqlite.SQLiteFullException
import java.util.UUID
import java.util.concurrent.Executor
import jp.hayase.skk.core.DictionaryQuery
import jp.hayase.skk.core.dictionary.SkkDictionaryCodec
import jp.hayase.skk.core.dictionary.SkkDictionaryDocument
import jp.hayase.skk.core.dictionary.SkkDictionaryEntry
import jp.hayase.skk.core.dictionary.SkkDictionarySource
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class DictionaryManagerDeletionTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private val databases = mutableListOf<String>()

    @After fun removeDatabases() {
        databases.forEach(context::deleteDatabase)
    }

    @Test fun `削除禁止と待機中の禁止は保存キューへ入れず抑止を作らない`() {
        val repository = SQLiteDictionaryRepository(context, databaseName())
        repository.importSystem("system", "辞書", document("かな /候補/"))
        val serial = ManualExecutor()
        val manager = DictionaryManager(repository, serial, Executor { it.run() })
        val request = systemRequest()
        var denied: PersonalWriteResult? = null

        manager.deleteCandidate(request, false) { denied = it }

        assertEquals(0, serial.size)
        assertEquals(PersonalWriteResult.Failed(PersonalWriteFailure.POLICY_REJECTED), denied)
        manager.deleteCandidate(request, true) { denied = it }
        manager.personalDataPolicy.setAllowed(false)
        serial.runAll()
        assertEquals(PersonalWriteResult.Failed(PersonalWriteFailure.POLICY_REJECTED), denied)
        assertTrue(repository.listCandidateSuppressions().suppressions.isEmpty())
        manager.close(); serial.runAll()
    }

    @Test fun `更新済みまたは消えた由来は競合として通知し抑止を変更しない`() {
        val repository = SQLiteDictionaryRepository(context, databaseName())
        repository.importSystem("system", "辞書", document("かな /候補/"))
        val serial = ManualExecutor()
        val manager = DictionaryManager(repository, serial, Executor { it.run() })
        var result: PersonalWriteResult? = null

        repository.importSystem("system", "辞書", document("かな /置換後/"), 1)
        manager.deleteCandidate(systemRequest(), true) { result = it }
        serial.runAll()

        assertEquals(PersonalWriteResult.Failed(PersonalWriteFailure.CONFLICT), result)
        assertTrue(repository.listCandidateSuppressions().suppressions.isEmpty())
        manager.close(); serial.runAll()
    }

    @Test fun `同じ世代で存在しない個人由来は競合として通知する`() {
        val repository = SQLiteDictionaryRepository(context, databaseName())
        repository.replacePersonal(document("かな /個人候補/"), 0)
        val serial = ManualExecutor()
        val manager = DictionaryManager(repository, serial, Executor { it.run() })
        var result: PersonalWriteResult? = null
        repository.replacePersonal(document("かな /別候補/"), 1)
        val request = DeleteCandidateRequest(2, listOf(
            StoredCandidateOriginRef("personal", 2, CandidateOriginKind.PERSONAL, "かな", "個人候補", null),
        ))

        manager.deleteCandidate(request, true) { result = it }
        serial.runAll()

        assertEquals(PersonalWriteResult.Failed(PersonalWriteFailure.CONFLICT), result)
        assertTrue(repository.listCandidateSuppressions().suppressions.isEmpty())
        manager.close(); serial.runAll()
    }

    @Test fun `容量不足は保存されず容量結果として通知する`() {
        var full = false
        val repository = SQLiteDictionaryRepository(
            context,
            databaseName(),
            DictionaryWriteFailpoint { point ->
                if (full && point == DictionaryWritePoint.AFTER_ROWS_CHANGED) throw SQLiteFullException()
            },
        )
        repository.importSystem("system", "辞書", document("かな /候補/"))
        full = true
        val serial = ManualExecutor()
        val manager = DictionaryManager(repository, serial, Executor { it.run() })
        var result: PersonalWriteResult? = null

        manager.deleteCandidate(systemRequest(), true) { result = it }
        serial.runAll()

        assertEquals(PersonalWriteResult.Failed(PersonalWriteFailure.CAPACITY), result)
        assertTrue(repository.listCandidateSuppressions().suppressions.isEmpty())
        manager.close(); serial.runAll()
    }

    @Test fun `削除後の再読込失敗は旧公開候補を保ち保存済み未適用と通知する`() {
        val repository = SQLiteDictionaryRepository(context, databaseName())
        repository.importSystem("system", "辞書", document("かな /候補/"))
        val serial = ManualExecutor()
        var loads = 0
        val manager = DictionaryManager(
            repository,
            serial,
            Executor { it.run() },
            loadSnapshot = {
                loads++
                if (loads == 1) repository.loadSnapshot() else error("再読込失敗")
            },
        )
        manager.loadAsync(); serial.runAll()
        var result: PersonalWriteResult? = null

        manager.deleteCandidate(systemRequest(), true) { result = it }
        serial.runAll()

        assertEquals(PersonalWriteResult.SavedButNotApplied, result)
        assertEquals(listOf("候補"), manager.lookup(DictionaryQuery("かな")).map { it.text })
        assertEquals(1, repository.listCandidateSuppressions().suppressions.size)
        manager.close(); serial.runAll()
    }

    @Test fun `復元は表示した世代の完全一致一件だけを消して新世代を公開する`() {
        val repository = SQLiteDictionaryRepository(context, databaseName())
        repository.importSystem("system", "辞書", document("かな /候補一/候補二/"))
        repository.deleteCandidate(
            DeleteCandidateRequest(0, listOf(
                StoredCandidateOriginRef("system", 1, CandidateOriginKind.STORED_SYSTEM, "かな", "候補一", null),
                StoredCandidateOriginRef("system", 1, CandidateOriginKind.STORED_SYSTEM, "かな", "候補二", null),
            )),
            emptyMap(),
        )
        val serial = ManualExecutor()
        val manager = DictionaryManager(repository, serial, Executor { it.run() })
        manager.loadAsync(); serial.runAll()
        var listed: CandidateSuppressionSnapshot? = null
        manager.listSuppressions { listed = (it as DictionaryManagerWriteResult.Applied).value }
        serial.runAll()
        val before = requireNotNull(listed)
        val restored = before.suppressions.first { it.key.templateText == "候補一" }.key
        var result: DictionaryManagerWriteResult<DictionarySourceInfo>? = null

        manager.restoreCandidateSuppression(restored, before.personalGeneration) { result = it }
        serial.runAll()

        assertTrue(result is DictionaryManagerWriteResult.Applied)
        assertEquals(listOf("候補一"), manager.lookup(DictionaryQuery("かな")).map { it.text })
        assertEquals(
            listOf("候補二"),
            repository.listCandidateSuppressions().suppressions.map { it.key.templateText },
        )
        assertEquals(before.personalGeneration + 1, repository.listCandidateSuppressions().personalGeneration)
        manager.close(); serial.runAll()
    }

    @Test fun `組込候補の削除は要求ではなく管理器の固定承認表で抑止する`() {
        val repository = SQLiteDictionaryRepository(context, databaseName())
        val serial = ManualExecutor()
        val fallback = SkkDictionarySource(
            "builtin",
            7,
            listOf(SkkDictionaryEntry("かな", listOf(SkkDictionaryCandidate("組込候補")))),
        )
        val manager = DictionaryManager(repository, serial, Executor { it.run() }, fallbackSystems = listOf(fallback))
        manager.loadAsync(); serial.runAll()
        val candidate = manager.lookup(DictionaryQuery("かな")).single()
        var result: PersonalWriteResult? = null

        manager.deleteSelection(requireNotNull(candidate.selection), true) { result = it }
        serial.runAll()

        assertEquals(PersonalWriteResult.Applied, result)
        assertEquals(listOf("builtin"), repository.listCandidateSuppressions().suppressions.map { it.key.sourceId })
        manager.close(); serial.runAll()
    }

    private fun systemRequest() = DeleteCandidateRequest(0, listOf(
        StoredCandidateOriginRef("system", 1, CandidateOriginKind.STORED_SYSTEM, "かな", "候補", null),
    ))

    private fun document(text: String): SkkDictionaryDocument = SkkDictionaryCodec.parseText(text)

    private fun databaseName(): String = "dictionary-manager-delete-${UUID.randomUUID()}.db".also(databases::add)

    private class ManualExecutor : Executor {
        private val tasks = ArrayDeque<Runnable>()
        val size: Int get() = tasks.size
        override fun execute(command: Runnable) { tasks.addLast(command) }
        fun runAll() { while (tasks.isNotEmpty()) tasks.removeFirst().run() }
    }
}
