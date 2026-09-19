package jp.hayase.skk.dictionary

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import java.util.UUID
import java.util.concurrent.Executor
import jp.hayase.skk.core.CompletionQuery
import jp.hayase.skk.core.DictionaryQuery
import jp.hayase.skk.core.dictionary.DeferredDictionaryReadException
import jp.hayase.skk.core.dictionary.DictionaryUnavailableException
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
class BoundedSQLiteDictionaryTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private val names = mutableListOf<String>()
    private val direct = Executor { it.run() }
    private fun repository(): SQLiteDictionaryRepository {
        val name = "bounded-${UUID.randomUUID()}.db"
        names += name
        return SQLiteDictionaryRepository(context, name)
    }
    @After fun clean() { names.forEach(context::deleteDatabase) }

    @Test fun `起動はメタデータだけを読み検索ミスを遅延し空検索も上限内へ追い出す`() {
        val repository = repository()
        repository.importSystem("large", "辞書", document((0..999).joinToString("\n") { "見出し${(0x4000 + it).toChar()} /候補/" }))
        val manager = DictionaryManager(repository, direct, direct)
        manager.loadAsync()
        assertEquals(DictionaryReadStats(1, 0, 0, 0), repository.readStats)
        assertEquals(0, manager.cacheStats.entries)
        val first = DictionaryQuery("未登録あ")
        assertThrows(DeferredDictionaryReadException::class.java) { manager.lookup(first) }
        assertEquals(0, repository.readStats.keyReads)
        assertTrue(manager.readBlocking { manager.lookup(first) }.isEmpty())
        val reads = repository.readStats.keyReads
        assertTrue(manager.lookup(first).isEmpty())
        assertEquals(reads, repository.readStats.keyReads)
        repeat(300) { index ->
            manager.readBlocking { manager.lookup(DictionaryQuery("未登録${(0x4000 + index).toChar()}")) }
            assertBounded(manager)
        }
        assertEquals(DictionaryCacheStats.MAX_ENTRIES, manager.cacheStats.entries)
        assertThrows(DeferredDictionaryReadException::class.java) { manager.lookup(first) }
        assertEquals(0, repository.readStats.fullSnapshotReads)
        manager.close()
    }

    @Test fun `候補レコード数と文字数の上限で最近使わない検索を追い出す`() {
        val repository = repository()
        repository.replacePersonal(document((0..60).joinToString("\n") { index ->
            "見出し${(0x4000 + index).toChar()} /" + (0..99).joinToString("/") { "候補$it" + "長".repeat(200) } + "/"
        }))
        val manager = DictionaryManager(repository, direct, direct)
        manager.loadAsync()
        repeat(60) { index ->
            assertEquals(100, manager.readBlocking { manager.lookup(DictionaryQuery("見出し${(0x4000 + index).toChar()}")) }.size)
            assertBounded(manager)
        }
        assertTrue(manager.cacheStats.entries < 60)
        assertEquals(0, repository.readStats.fullSnapshotReads)
        manager.close()
    }

    @Test fun `短い候補でもレコード数の上限を超える前に追い出す`() {
        val repository = repository()
        repository.replacePersonal(document((0..60).joinToString("\n") { index ->
            "見出し${(0x4000 + index).toChar()} /" + (0..99).joinToString("/") { "候補$it" } + "/"
        }))
        val manager = DictionaryManager(repository, direct, direct)
        manager.loadAsync()
        repeat(60) { index ->
            manager.readBlocking { manager.lookup(DictionaryQuery("見出し${(0x4000 + index).toChar()}")) }
            assertBounded(manager)
        }
        assertEquals(40, manager.cacheStats.entries)
        assertEquals(4_000, manager.cacheStats.candidateRecords)
        manager.close()
    }

    @Test fun `学習は対象見出し以外の行を書き換えず全件読み込みなしで世代と順位を更新する`() {
        val repository = repository()
        repository.replacePersonal(document("にほん /日本/二本/\nかんじ /漢字/"))
        val manager = DictionaryManager(repository, direct, direct)
        manager.loadAsync()
        val old = manager.readBlocking { manager.lookup(DictionaryQuery("にほん")) }
        SQLiteDatabase.openDatabase(context.getDatabasePath(names.last()).path, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
            db.execSQL("CREATE TRIGGER guard_other_delete BEFORE DELETE ON dictionary_candidates WHEN OLD.entry_key != 'にほん' BEGIN SELECT RAISE(ABORT, '他の見出し'); END")
            db.execSQL("CREATE TRIGGER guard_other_insert BEFORE INSERT ON dictionary_candidates WHEN NEW.entry_key != 'にほん' BEGIN SELECT RAISE(ABORT, '他の見出し'); END")
            db.execSQL("CREATE TRIGGER guard_other_update BEFORE UPDATE ON dictionary_candidates WHEN OLD.entry_key != 'にほん' BEGIN SELECT RAISE(ABORT, '他の見出し'); END")
        }
        var outcome: PersonalWriteResult? = null
        manager.savePersonalCandidate("にほん", SkkDictionaryCandidate("二本"), true) { outcome = it }
        assertEquals(PersonalWriteResult.Applied, outcome)
        val next = manager.readBlocking { manager.lookup(DictionaryQuery("にほん")) }
        assertEquals(listOf("二本", "日本"), next.map { it.text })
        assertTrue(next.first().selection!!.personalGeneration > old.first().selection!!.personalGeneration)
        assertEquals("漢字", manager.readBlocking { manager.lookup(DictionaryQuery("かんじ")) }.single().text)
        assertEquals(0, repository.readStats.fullSnapshotReads)
        assertEquals(2, repository.readStats.metadataReads)
        manager.close()
    }

    @Test fun `公開失敗ではキャッシュした旧世代だけを利用し未読キーへ新しい内容を混ぜない`() {
        val repository = repository()
        repository.replacePersonal(document("にほん /日本/\nかんじ /漢字/"))
        var failMetadata = false
        val manager = DictionaryManager(repository, direct, direct, loadMetadata = {
            if (failMetadata) error("公開失敗") else repository.loadMetadata()
        })
        manager.loadAsync()
        val before = manager.readBlocking { manager.lookup(DictionaryQuery("にほん")) }
        failMetadata = true
        var outcome: PersonalWriteResult? = null
        manager.savePersonalCandidate("にほん", SkkDictionaryCandidate("二本"), true) { outcome = it }
        assertEquals(PersonalWriteResult.SavedButNotApplied, outcome)
        val cached = manager.lookup(DictionaryQuery("にほん"))
        assertEquals(before.map { it.text }, cached.map { it.text })
        assertEquals(before.single().selection!!.personalGeneration, cached.single().selection!!.personalGeneration)
        assertEquals(before.single().selection!!.origins, cached.single().selection!!.origins)
        assertThrows(DictionaryUnavailableException::class.java) {
            manager.readBlocking { manager.lookup(DictionaryQuery("かんじ")) }
        }
        failMetadata = false
        manager.loadAsync()
        assertEquals("二本", manager.readBlocking { manager.lookup(DictionaryQuery("にほん")) }.first().text)
        assertEquals(0, repository.readStats.fullSnapshotReads)
        manager.close()
    }

    @Test fun `空の個人辞書でも由来世代を保持し無効化された同名保存辞書は代替辞書を隠す`() {
        val repository = repository()
        val stored = repository.importSystem("system", "辞書", document("にほん /日本/"))
        val manager = DictionaryManager(repository, direct, direct,
            fallbackSystems = listOf(SkkDictionarySource("system", 1, document("にほん /代替/" ).entries)))
        manager.loadAsync()
        val candidate = manager.readBlocking { manager.lookup(DictionaryQuery("にほん")) }.single()
        assertEquals(0, candidate.selection!!.personalGeneration)
        assertEquals(stored.generation, candidate.selection!!.origins.single().generation)
        manager.setSourceEnabled("system", false) { assertTrue(it is DictionaryManagerWriteResult.Applied) }
        assertTrue(manager.readBlocking { manager.lookup(DictionaryQuery("にほん")) }.isEmpty())
        manager.close()
    }

    @Test fun `補完は抑止と数値正規化を検索世代へ結びコードポイント順で返す`() {
        val repository = repository()
        repository.importSystem("system", "辞書", document("にほん /日本/\nにほんご /日本語/\nあ😀 /補助/\nあ\uE000 /私用/\nだい# /第#0/"))
        val manager = DictionaryManager(repository, direct, direct)
        manager.loadAsync()
        val selection = manager.readBlocking { manager.lookup(DictionaryQuery("にほん")) }.single().selection!!
        manager.deleteSelection(selection, true) { assertEquals(PersonalWriteResult.Applied, it) }
        assertEquals(listOf("にほんご"), manager.readBlocking { manager.complete(CompletionQuery("に")) })
        assertEquals(listOf("あ\uE000", "あ😀"), manager.readBlocking { manager.complete(CompletionQuery("あ")) })
        val numeric = manager.readBlocking { manager.lookup(DictionaryQuery("だい12")) }.single()
        assertEquals("第12", numeric.text)
        assertEquals("だい#", numeric.selection!!.origins.single().entryKey)
        assertEquals(1, numeric.selection!!.personalGeneration)
        assertEquals(0, repository.readStats.fullSnapshotReads)
        manager.close()
    }

    @Test fun `キャッシュ上限を超える数値テンプレートと内側候補は操作内で保持し終了後に解放する`() {
        val repository = repository()
        val annotation = "a".repeat(600_000)
        repository.replacePersonal(document("だい# /第#4;$annotation/\n12 /十二;$annotation/"))
        val manager = DictionaryManager(repository, direct, direct)
        manager.loadAsync()
        val result = manager.readBlocking { manager.lookup(DictionaryQuery("だい12")) }.single()
        assertEquals("第十二", result.text)
        assertEquals(2, repository.readStats.keyReads)
        assertEquals(0, manager.cacheStats.entries)
        assertEquals("第十二", manager.readBlocking { manager.lookup(DictionaryQuery("だい12")) }.single().text)
        assertEquals(4, repository.readStats.keyReads)
        assertBounded(manager)
        manager.close()
    }

    @Test fun `採用済み見出しに多数の候補があっても残りを走査せず次の補完へ進む`() {
        val repository = repository()
        val many = (0..CompletionQuery.MAX_WORK_ITEMS).joinToString("/") { "候補$it" }
        repository.importSystem("system", "辞書", document("かなあ /$many/\nかない /次候補/"))
        val manager = DictionaryManager(repository, direct, direct)
        manager.loadAsync()
        assertEquals(listOf("かなあ", "かない"), manager.readBlocking { manager.complete(CompletionQuery("かな")) })
        assertEquals(0, repository.readStats.keyReads)
        assertEquals(0, repository.readStats.fullSnapshotReads)
        manager.close()
    }

    @Test fun `復元は旧キャッシュと入力許可を失効させ保存されていない代替辞書を戻さない`() {
        val repository = repository()
        repository.replacePersonal(document("にほん /保存候補/"))
        val bytes = java.io.ByteArrayOutputStream().also { repository.writeCompleteBackup(it, emptyList(), "test") }.toByteArray()
        val manager = DictionaryManager(repository, direct, direct, fallbackSystems = listOf(BuiltinDictionary.source))
        manager.loadAsync()
        val oldContext = manager.captureInputWriteContext()
        manager.savePersonalCandidate("にほん", SkkDictionaryCandidate("直前候補"), true) { assertEquals(PersonalWriteResult.Applied, it) }
        val old = manager.readBlocking { manager.lookup(DictionaryQuery("にほん")) }.first()
        assertEquals("直前候補", old.text)
        var prepared: PreparedDictionaryRestore? = null
        manager.prepareCompleteRestore(context, { bytes.inputStream() }) { prepared = (it as CompleteBackupResult.Applied).value }
        manager.restoreComplete(checkNotNull(prepared)) { assertTrue(it is CompleteBackupResult.Applied) }
        assertEquals(0, manager.cacheStats.entries)
        assertFalse(manager.isInputWriteContextCurrent(oldContext))
        val restored = manager.readBlocking { manager.lookup(DictionaryQuery("にほん")) }.single()
        assertEquals("保存候補", restored.text)
        assertTrue(restored.selection!!.personalGeneration > old.selection!!.personalGeneration)
        assertTrue(manager.readBlocking { manager.lookup(DictionaryQuery("API", abbrev = true)) }.isEmpty())
        manager.savePersonalCandidate("にほん", SkkDictionaryCandidate("古い操作"), true, oldContext) {
            assertEquals(PersonalWriteResult.Failed(PersonalWriteFailure.CONFLICT), it)
        }
        assertEquals(0, repository.readStats.fullSnapshotReads)
        manager.close()
    }

    @Test fun `閉じた後に遅延読込が実行されても保管庫を開き直さない`() {
        val repository = repository()
        val manager = DictionaryManager(repository, direct, direct)
        manager.loadAsync()
        val pending = assertThrows(DeferredDictionaryReadException::class.java) { manager.lookup(DictionaryQuery("かな")) }
        manager.close()
        pending.load()
        assertEquals(0, repository.readStats.keyReads)
        assertThrows(IllegalStateException::class.java) { repository.lookup("かな") }
        assertThrows(IllegalStateException::class.java) { repository.loadMetadata() }
    }

    private fun assertBounded(manager: DictionaryManager) {
        val stats = manager.cacheStats
        assertTrue(stats.entries <= DictionaryCacheStats.MAX_ENTRIES)
        assertTrue(stats.candidateRecords <= DictionaryCacheStats.MAX_CANDIDATE_RECORDS)
        assertTrue(stats.retainedChars <= DictionaryCacheStats.MAX_RETAINED_CHARS)
    }
    private fun document(text: String) = SkkDictionaryCodec.parseText(text + "\n")
}
