package jp.hayase.skk.dictionary

import android.content.Context
import java.util.UUID
import java.util.concurrent.Executor
import jp.hayase.skk.core.DictionaryQuery
import jp.hayase.skk.core.dictionary.SkkDictionaryCodec
import jp.hayase.skk.core.dictionary.SkkDictionaryDocument
import jp.hayase.skk.core.dictionary.SkkDictionaryEntry
import jp.hayase.skk.core.dictionary.SkkDictionaryCandidate
import jp.hayase.skk.core.dictionary.SkkDictionarySource
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class DictionarySuppressionManagerTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private val databases = mutableListOf<String>()

    @After fun removeDatabases() {
        databases.forEach(context::deleteDatabase)
    }

    @Test fun `保存辞書と組込辞書の抑止を再読込で反映し完全一致の復元だけを再表示する`() {
        val repository = SQLiteDictionaryRepository(context, databaseName())
        repository.replacePersonal(document("かな /個人候補/"), 0)
        repository.importSystem("system", "保存辞書", document("かな /保存候補/"))
        val fallback = SkkDictionarySource(
            "builtin",
            7,
            listOf(SkkDictionaryEntry("かな", listOf(SkkDictionaryCandidate("組込候補")))),
        )
        val serial = ManualExecutor()
        val manager = DictionaryManager(
            repository,
            serial,
            Executor { it.run() },
            fallbackSystems = listOf(fallback), deferReads = false,
        )
        manager.loadAsync(); serial.runNext()
        assertEquals(
            listOf("個人候補", "保存候補", "組込候補"),
            manager.lookup(DictionaryQuery("かな")).map { it.text },
        )
        val request = DeleteCandidateRequest(1, listOf(
            StoredCandidateOriginRef("personal", 1, CandidateOriginKind.PERSONAL, "かな", "個人候補", null),
            StoredCandidateOriginRef("system", 1, CandidateOriginKind.STORED_SYSTEM, "かな", "保存候補", null),
            StoredCandidateOriginRef("builtin", 7, CandidateOriginKind.IMMUTABLE_SYSTEM, "かな", "組込候補", null),
        ))

        val expiredPermit = requireNotNull(manager.personalDataPolicy.request(true))
        manager.personalDataPolicy.setAllowed(false)
        assertThrows(PersonalDataPolicyRejectedException::class.java) {
            repository.deleteCandidate(request, mapOf("builtin" to 7L)) {
                manager.personalDataPolicy.accepts(expiredPermit)
            }
        }
        assertEquals(1L, repository.listCandidateSuppressions().personalGeneration)

        manager.personalDataPolicy.setAllowed(true)
        val permit = requireNotNull(manager.personalDataPolicy.request(true))
        repository.deleteCandidate(request, mapOf("builtin" to 7L)) {
            manager.personalDataPolicy.accepts(permit)
        }
        assertEquals(
            listOf("個人候補", "保存候補", "組込候補"),
            manager.lookup(DictionaryQuery("かな")).map { it.text },
        )
        manager.loadAsync(); serial.runNext()
        assertEquals(emptyList<String>(), manager.lookup(DictionaryQuery("かな")).map { it.text })

        val suppressed = repository.listCandidateSuppressions()
        assertEquals(2L, suppressed.personalGeneration)
        assertEquals(listOf("builtin", "system"), suppressed.suppressions.map { it.key.sourceId })
        val stored = suppressed.suppressions.single { it.key.sourceId == "system" }.key
        repository.restoreCandidateSuppression(stored, suppressed.personalGeneration)
        manager.loadAsync(); serial.runNext()
        assertEquals(listOf("保存候補"), manager.lookup(DictionaryQuery("かな")).map { it.text })

        val remaining = repository.listCandidateSuppressions()
        assertEquals(3L, remaining.personalGeneration)
        val builtin = remaining.suppressions.single().key
        repository.restoreCandidateSuppression(builtin, remaining.personalGeneration)
        manager.loadAsync(); serial.runNext()
        assertEquals(
            listOf("保存候補", "組込候補"),
            manager.lookup(DictionaryQuery("かな")).map { it.text },
        )
        assertEquals(4L, repository.listCandidateSuppressions().personalGeneration)

        manager.close(); serial.runAll()
    }

    private fun document(text: String): SkkDictionaryDocument = SkkDictionaryCodec.parseText(text)

    private fun databaseName(): String = "dictionary-suppression-${UUID.randomUUID()}.db".also(databases::add)

    private class ManualExecutor : Executor {
        private val tasks = ArrayDeque<Runnable>()
        override fun execute(command: Runnable) { tasks.addLast(command) }
        fun runNext() { tasks.removeFirst().run() }
        fun runAll() { while (tasks.isNotEmpty()) runNext() }
    }
}
