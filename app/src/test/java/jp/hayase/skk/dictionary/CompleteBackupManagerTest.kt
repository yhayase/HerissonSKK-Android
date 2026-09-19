package jp.hayase.skk.dictionary

import android.content.Context
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.UUID
import java.util.concurrent.Executor
import jp.hayase.skk.core.DictionaryQuery
import jp.hayase.skk.core.dictionary.SkkDictionaryCandidate
import jp.hayase.skk.core.dictionary.SkkDictionaryCodec
import jp.hayase.skk.core.dictionary.SkkDictionarySource
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class CompleteBackupManagerTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private val databases = mutableListOf<String>()
    private val cleanup = mutableListOf<() -> Unit>()

    @After fun clean() {
        cleanup.asReversed().forEach { it() }
        databases.forEach(context::deleteDatabase)
    }

    @Test fun `復元前の入力欄で初めて送信する登録を復元後は拒否する`() {
        val h = harness()
        val old = h.manager.captureInputWriteContext()
        val prepared = prepare(h, backup())
        var restored: CompleteBackupResult<RestoreSummary>? = null
        h.manager.restoreComplete(prepared) { restored = it }
        h.serial.runAll()
        assertTrue(restored is CompleteBackupResult.Applied)
        assertFalse(h.manager.isInputWriteContextCurrent(old))
        val revision = h.repository.dictionaryRevision()
        var result: PersonalWriteResult? = null
        h.manager.savePersonalCandidate("かな", SkkDictionaryCandidate("旧確認"), true, old) { result = it }
        assertEquals(PersonalWriteResult.Failed(PersonalWriteFailure.CONFLICT), result)
        assertEquals(0, h.serial.size)
        assertEquals(revision, h.repository.dictionaryRevision())
        assertEquals(listOf("復元候補"), lookup(h))
    }

    @Test fun `復元の後ろに待機した旧コンテキストの学習は書込直前にも拒否する`() {
        val h = harness()
        val old = h.manager.captureInputWriteContext()
        val prepared = prepare(h, backup())
        h.manager.restoreComplete(prepared) { }
        var result: PersonalWriteResult? = null
        h.manager.savePersonalCandidate("かな", SkkDictionaryCandidate("待機学習"), true, old) { result = it }
        assertEquals(2, h.serial.size)
        h.serial.runAll()
        assertEquals(PersonalWriteResult.Failed(PersonalWriteFailure.CONFLICT), result)
        assertEquals(listOf("復元候補"), lookup(h))
    }

    @Test fun `公開失敗でも旧入力を失効させ再読込だけで反映する`() {
        val h = harness()
        val old = h.manager.captureInputWriteContext()
        val prepared = prepare(h, backup())
        h.failLoad = true
        var restored: CompleteBackupResult<RestoreSummary>? = null
        h.manager.restoreComplete(prepared) { restored = it }
        h.serial.runAll()
        assertTrue(restored is CompleteBackupResult.SavedButNotApplied)
        assertEquals(listOf("元候補"), lookup(h))
        assertFalse(h.manager.isInputWriteContextCurrent(old))
        val current = h.manager.captureInputWriteContext()
        var saved: PersonalWriteResult? = null
        h.manager.savePersonalCandidate("かな", SkkDictionaryCandidate("古い検索由来"), true, current) { saved = it }
        assertEquals(PersonalWriteResult.Failed(PersonalWriteFailure.CONFLICT), saved)
        val revision = h.repository.dictionaryRevision()
        h.manager.loadAsync()
        h.manager.savePersonalCandidate("かな", SkkDictionaryCandidate("再読込待機"), true, current) { saved = it }
        assertEquals(PersonalWriteResult.Failed(PersonalWriteFailure.CONFLICT), saved)
        h.failLoad = false
        h.serial.runAll()
        assertEquals(revision, h.repository.dictionaryRevision())
        assertEquals(listOf("復元候補"), lookup(h))
        h.manager.savePersonalCandidate("かな", SkkDictionaryCandidate("新学習"), true, current) { saved = it }
        h.serial.runAll()
        assertEquals(PersonalWriteResult.Applied, saved)
    }

    @Test fun `確認後の辞書変更と閉じた候補と二回目適用を拒否する`() {
        val h = harness()
        val outdated = prepare(h, backup())
        h.repository.promotePersonalCandidate("かな", SkkDictionaryCandidate("確認後"))
        var result: CompleteBackupResult<RestoreSummary>? = null
        h.manager.restoreComplete(outdated) { result = it }
        h.serial.runAll()
        assertEquals(CompleteBackupResult.Failed(CompleteBackupFailure.CONFLICT), result)
        val closed = prepare(h, backup())
        closed.close()
        h.manager.restoreComplete(closed) { result = it }
        assertEquals(CompleteBackupResult.Failed(CompleteBackupFailure.CONFLICT), result)
        val valid = prepare(h, backup())
        h.manager.restoreComplete(valid) { result = it }
        valid.close() // 適用済み所有権を画面破棄で奪いません。
        h.serial.runAll()
        assertTrue(result is CompleteBackupResult.Applied)
        h.manager.restoreComplete(valid) { result = it }
        assertEquals(CompleteBackupResult.Failed(CompleteBackupFailure.CONFLICT), result)
    }

    @Test fun `復元で保存された組込 ID の削除は保存済み世代で検証する`() {
        val builtin = SkkDictionarySource("builtin", 7, document("かな /組込候補/").entries)
        val h = harness(listOf(builtin))
        val prepared = prepare(h, backup(listOf(builtin)))
        h.manager.restoreComplete(prepared) { }
        h.serial.runAll()
        val selected = h.manager.lookup(DictionaryQuery("かな")).first { it.text == "組込候補" }
        val origin = requireNotNull(selected.selection).origins.single()
        assertTrue(origin.generation > builtin.generation)
        var result: PersonalWriteResult? = null
        h.manager.deleteSelection(requireNotNull(selected.selection), true,
            h.manager.captureInputWriteContext()) { result = it }
        h.serial.runAll()
        assertEquals(PersonalWriteResult.Applied, result)
        assertFalse(lookup(h).contains("組込候補"))
    }

    @Test fun `無効な保存済み組込 ID は検索にも不変承認表にも戻さない`() {
        val builtin = SkkDictionarySource("builtin", 7, document("かな /組込候補/").entries)
        val h = harness(listOf(builtin))
        h.repository.importSystem("builtin", "保存済み", document("かな /保存候補/"))
        h.repository.setSourceEnabled("builtin", false)
        h.manager.loadAsync(); h.serial.runAll()
        assertEquals(listOf("元候補"), lookup(h))
        val generation = h.repository.listSources().first { it.id == "personal" }.generation
        val request = DeleteCandidateRequest(generation, listOf(StoredCandidateOriginRef(
            "builtin", 7, CandidateOriginKind.IMMUTABLE_SYSTEM, "かな", "組込候補", null)))
        var result: PersonalWriteResult? = null
        h.manager.deleteCandidate(request, true, h.manager.captureInputWriteContext()) { result = it }
        h.serial.runAll()
        assertEquals(PersonalWriteResult.Failed(PersonalWriteFailure.CONFLICT), result)
        assertTrue(h.repository.listCandidateSuppressions().suppressions.isEmpty())
    }

    @Test fun `入力 close の失敗では適用可能な候補を返さない`() {
        val h = harness()
        val bytes = backup()
        var result: CompleteBackupResult<PreparedDictionaryRestore>? = null
        h.manager.prepareCompleteRestore(context, {
            object : ByteArrayInputStream(bytes) {
                override fun close() { throw IOException("試験用の close 失敗") }
            }
        }) { result = it }
        h.serial.runAll()
        assertEquals(CompleteBackupResult.Failed(CompleteBackupFailure.IO), result)
        assertEquals(listOf("元候補"), lookup(h))
    }

    @Test fun `配送前の終了はハンドルを掃除し終了後に外部入力を開かない`() {
        val callbacks = ManualExecutor()
        val h = harness(callbacks = callbacks)
        var exported: CompleteBackupResult<CompleteBackupExport>? = null
        h.manager.exportCompleteBackup(context, "test") { exported = it }
        h.serial.runAll()
        h.manager.close(); h.serial.runAll(); callbacks.runAll()
        assertNull(exported)
        val other = harness()
        var opened = false
        other.manager.prepareCompleteRestore(context, { opened = true; backup().inputStream() }) { }
        other.manager.close(); other.serial.runAll()
        assertFalse(opened)
    }

    @Test fun `書き出しハンドルは完全ファイルだけを返し閉じた後は開けない`() {
        val h = harness()
        var result: CompleteBackupResult<CompleteBackupExport>? = null
        h.manager.exportCompleteBackup(context, "test") { result = it }
        h.serial.runAll()
        val exported = (result as CompleteBackupResult.Applied).value
        val prepared = prepare(h, exported.openInput().use { it.readBytes() })
        assertEquals(exported.summary, prepared.summary)
        prepared.close(); exported.close()
        assertThrows(IllegalStateException::class.java) { exported.openInput() }
    }

    private fun prepare(h: Harness, bytes: ByteArray): PreparedDictionaryRestore {
        var result: CompleteBackupResult<PreparedDictionaryRestore>? = null
        h.manager.prepareCompleteRestore(context, { bytes.inputStream() }) { result = it }
        h.serial.runAll()
        return (result as CompleteBackupResult.Applied).value
    }

    private fun backup(fallbacks: List<SkkDictionarySource> = emptyList()): ByteArray {
        val source = SQLiteDictionaryRepository(context, name())
        return source.use {
            it.replacePersonal(document("かな /復元候補/"))
            ByteArrayOutputStream().also { output -> it.writeCompleteBackup(output, fallbacks, "test") }.toByteArray()
        }
    }

    private fun harness(fallbacks: List<SkkDictionarySource> = emptyList(), callbacks: Executor = Executor { it.run() }): Harness {
        val h = Harness(SQLiteDictionaryRepository(context, name()), ManualExecutor())
        h.repository.replacePersonal(document("かな /元候補/"))
        h.manager = DictionaryManager(h.repository, h.serial, callbacks, fallbackSystems = fallbacks,
            loadSnapshot = { if (h.failLoad) error("試験用の公開失敗") else h.repository.loadSnapshot() }, deferReads = false)
        h.manager.loadAsync(); h.serial.runAll()
        cleanup += { h.manager.close(); h.serial.runAll() }
        return h
    }

    private class Harness(val repository: SQLiteDictionaryRepository, val serial: ManualExecutor) {
        lateinit var manager: DictionaryManager
        var failLoad = false
    }
    private fun lookup(h: Harness) = h.manager.lookup(DictionaryQuery("かな")).map { it.text }
    private fun document(text: String) = SkkDictionaryCodec.parseText(text)
    private fun name() = "complete-manager-${UUID.randomUUID()}.db".also(databases::add)
    private class ManualExecutor : Executor {
        private val tasks = ArrayDeque<Runnable>()
        val size get() = tasks.size
        override fun execute(command: Runnable) { tasks.addLast(command) }
        fun runAll() { while (tasks.isNotEmpty()) tasks.removeFirst().run() }
    }
}
