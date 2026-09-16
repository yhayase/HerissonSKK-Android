package jp.hayase.skk.dictionary

import android.content.Context
import java.nio.charset.StandardCharsets
import java.util.UUID
import jp.hayase.skk.core.DictionaryQuery
import jp.hayase.skk.core.dictionary.SkkDictionaryCodec
import jp.hayase.skk.core.dictionary.SkkDictionaryCandidate
import jp.hayase.skk.core.dictionary.SkkDictionaryDocument
import jp.hayase.skk.core.dictionary.SkkDictionaryEncoding
import jp.hayase.skk.core.dictionary.SkkDictionaryEntry
import jp.hayase.skk.core.dictionary.SkkDictionarySource
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class SQLiteDictionaryRepositoryTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private val databases = mutableListOf<String>()

    @After fun removeDatabases() {
        databases.forEach(context::deleteDatabase)
    }

    @Test fun `登録と学習は最新の見出しだけを更新し注釈と他の候補を保持する`() {
        val name = databaseName()
        SQLiteDictionaryRepository(context, name).use { repository ->
            repository.replacePersonal(document("にほん /日本;国名/二本/\nほか /保持/"), 0)
            repository.promotePersonalCandidate("にほん", SkkDictionaryCandidate("二本"))
            repository.promotePersonalCandidate("にほん", SkkDictionaryCandidate("日本"))
            repository.promotePersonalCandidate("にほん", SkkDictionaryCandidate("日本"))
            repository.promotePersonalCandidate("かk", SkkDictionaryCandidate("書", okuriCondition = "く"))
            repository.promotePersonalCandidate("かk", SkkDictionaryCandidate("書", okuriCondition = "け"))
        }
        SQLiteDictionaryRepository(context, name).use { repository ->
            val entries = SkkDictionaryCodec.parse(repository.exportPersonal()).entries.associateBy { it.key }
            assertEquals(listOf("日本", "二本"), entries.getValue("にほん").candidates.map { it.text })
            assertEquals("国名", entries.getValue("にほん").candidates.first().annotation)
            assertEquals(listOf("け", "く"), entries.getValue("かk").candidates.map { it.okuriCondition })
            assertEquals("保持", entries.getValue("ほか").candidates.single().text)
            assertEquals(6L, repository.listSources().first().generation)
        }
    }

    @Test fun `一見出しの更新失敗で旧順位と注釈と世代を失わない`() {
        var fail = false
        SQLiteDictionaryRepository(context, databaseName()) { point ->
            if (fail && point == DictionaryWritePoint.BEFORE_PUBLICATION) error("試験用の保存失敗")
        }.use { repository ->
            repository.replacePersonal(document("にほん /日本;国名/二本/"), 0)
            val before = repository.exportPersonal()
            fail = true
            assertThrows(IllegalStateException::class.java) {
                repository.promotePersonalCandidate("にほん", SkkDictionaryCandidate("二本"))
            }
            assertArrayEquals(before, repository.exportPersonal())
            assertEquals(1L, repository.listSources().first().generation)
        }
    }

    @Test fun `置換した内容と辞書順と有効状態をclose後も保持する`() {
        val name = databaseName()
        SQLiteDictionaryRepository(context, name).use { repository ->
            repository.replacePersonal(document("にほん /個人/"), 0)
            repository.importSystem("a", "辞書A", document("にほん /甲/"))
            repository.importSystem("b", "辞書B", document("にほん /乙/"))
            repository.setSystemOrder(listOf("b", "a"))
            repository.setSourceEnabled("a", false)
        }

        SQLiteDictionaryRepository(context, name).use { reopened ->
            val sources = reopened.listSources()
            assertEquals(listOf("personal", "b", "a"), sources.map { it.id })
            assertEquals(listOf(1L, 1L, 1L), sources.map { it.generation })
            assertTrue(sources[1].enabled)
            assertFalse(sources[2].enabled)
            assertEquals(listOf("個人", "乙"), lookupTexts(reopened, "にほん"))
        }
    }

    @Test fun `個人辞書マージは取込順を先頭にし注釈と送り条件を構造で保持する`() {
        SQLiteDictionaryRepository(context, databaseName()).use { repository ->
            repository.replacePersonal(document("""
                にほん /旧;旧注/共通/
                おおk /大/[く/多;既存注/]/
            """.trimIndent()), 0)
            val result = repository.mergePersonal(document("""
                にほん /新/共通;新注/旧/
                おおk /多/[く/多/]/
            """.trimIndent()), 1)

            assertEquals(2L, result.generation)
            val nihon = repository.lookup("にほん").personal!!
                .let { jp.hayase.skk.core.dictionary.CompositeSkkDictionary(it) }
                .resolve(DictionaryQuery("にほん"))
            assertEquals(listOf("新", "共通", "旧"), nihon.map { it.candidate.text })
            assertEquals(listOf(null, "新注", "旧注"), nihon.map { it.candidate.annotation })

            val ooku = repository.lookup("おおk").asComposite().resolve(DictionaryQuery("おおk", "く"))
            assertEquals(listOf("多", "大"), ooku.map { it.candidate.text })
            assertEquals("く", ooku.first().candidate.okuriCondition)
            assertEquals("既存注", ooku.first().candidate.annotation)
        }
    }

    @Test fun `空注釈のプログラム生成文書は既存の非空注釈を消さない`() {
        SQLiteDictionaryRepository(context, databaseName()).use { repository ->
            repository.replacePersonal(document("かな /仮名;既存注釈/"), 0)
            val programmatic = SkkDictionaryDocument(
                listOf(SkkDictionaryEntry("かな", listOf(SkkDictionaryCandidate("仮名", annotation = "")))),
                SkkDictionaryEncoding.UTF8,
            )

            repository.mergePersonal(programmatic, 1)

            val candidate = repository.lookup("かな").asComposite().lookup(DictionaryQuery("かな")).single()
            assertEquals("既存注釈", candidate.annotation)
        }
    }

    @Test fun `システム辞書更新は個人辞書と他のシステム辞書を変更しない`() {
        SQLiteDictionaryRepository(context, databaseName()).use { repository ->
            repository.replacePersonal(document("かな /個人/"), 0)
            repository.importSystem("a", "辞書A", document("かな /旧A/"))
            repository.importSystem("b", "辞書B", document("かな /B/"))
            val updated = repository.importSystem("a", "辞書A改", document("かな /新A/"), 1)

            assertEquals(2L, updated.generation)
            assertEquals(listOf("個人", "新A", "B"), lookupTexts(repository, "かな"))
            assertEquals(
                listOf("個人辞書", "辞書A改", "辞書B"),
                repository.listSources().map { it.name },
            )
        }
    }

    @Test fun `システム辞書削除は個人辞書と他の辞書と優先順を保持する`() {
        val name = databaseName()
        SQLiteDictionaryRepository(context, name).use { repository ->
            repository.replacePersonal(document("かな /個人/"), 0)
            repository.importSystem("a", "辞書A", document("かな /A/"))
            repository.importSystem("b", "辞書B", document("かな /B/"))
            repository.importSystem("c", "辞書C", document("かな /C/"))

            val removed = repository.removeSystem("b", 1)

            assertEquals("辞書B", removed.name)
            assertEquals(listOf("personal", "a", "c"), repository.listSources().map { it.id })
            assertEquals(listOf(0, 0, 2), repository.listSources().map { it.order })
            assertEquals(listOf("個人", "A", "C"), lookupTexts(repository, "かな"))
        }
        SQLiteDictionaryRepository(context, name).use { reopened ->
            assertEquals(listOf("personal", "a", "c"), reopened.listSources().map { it.id })
            assertEquals(listOf(0, 0, 2), reopened.listSources().map { it.order })
            assertEquals(listOf("個人", "A", "C"), lookupTexts(reopened, "かな"))
        }
    }

    @Test fun `システム辞書削除は個人辞書と欠落IDと古い世代を拒否する`() {
        SQLiteDictionaryRepository(context, databaseName()).use { repository ->
            repository.replacePersonal(document("かな /個人/"), 0)
            repository.importSystem("system", "辞書", document("かな /システム/"))

            assertThrows(IllegalArgumentException::class.java) {
                repository.removeSystem(SQLiteDictionaryRepository.PERSONAL_SOURCE_ID, 1)
            }
            assertThrows(IllegalArgumentException::class.java) {
                repository.removeSystem("missing", 1)
            }
            val stale = assertThrows(StaleDictionaryGenerationException::class.java) {
                repository.removeSystem("system", 0)
            }

            assertEquals("system", stale.sourceId)
            assertEquals(listOf("personal", "system"), repository.listSources().map { it.id })
            assertEquals(listOf("個人", "システム"), lookupTexts(repository, "かな"))
        }
    }

    @Test fun `システム辞書削除の例外は内容と世代をclose後も保持する`() {
        for (point in DictionaryWritePoint.entries) {
            val name = databaseName()
            var failure: DictionaryWritePoint? = null
            val repository = SQLiteDictionaryRepository(context, name) { reached ->
                if (reached == failure) throw InjectedWriteFailure()
            }
            repository.importSystem("system", "辞書", document("かな /保持候補/"))
            failure = point

            assertThrows(InjectedWriteFailure::class.java) {
                repository.removeSystem("system", 1)
            }
            assertEquals(listOf("personal", "system"), repository.listSources().map { it.id })
            assertEquals(listOf("保持候補"), lookupTexts(repository, "かな"))
            repository.close()

            SQLiteDictionaryRepository(context, name).use { reopened ->
                assertEquals(1L, reopened.listSources().single { it.id == "system" }.generation)
                assertEquals(listOf("保持候補"), lookupTexts(reopened, "かな"))
            }
        }
    }

    @Test fun `辞書優先順と有効状態を次の検索スナップショットへ反映する`() {
        SQLiteDictionaryRepository(context, databaseName()).use { repository ->
            repository.replacePersonal(document("かな /共通/個人/"), 0)
            repository.importSystem("a", "辞書A", document("かな /A/共通;A注/"))
            repository.importSystem("b", "辞書B", document("かな /B/"))

            val old = repository.lookup("かな")
            repository.setSystemOrder(listOf("b", "a"))
            repository.setSourceEnabled("a", false)
            val current = repository.lookup("かな")

            assertEquals(listOf("共通", "個人", "A", "B"), old.asComposite().lookup(DictionaryQuery("かな")).map { it.text })
            assertEquals(listOf("共通", "個人", "B"), current.asComposite().lookup(DictionaryQuery("かな")).map { it.text })
            assertEquals(listOf("b"), current.systems.map { it.id })
            val bOrigin = current.asComposite().resolve(DictionaryQuery("かな")).last().origins.single()
            assertEquals("b", bOrigin.dictionaryId)
            assertEquals(1L, bOrigin.generation)
            assertFalse(bOrigin.personal)
            assertEquals(listOf("共通", "個人", "A", "B"), old.asComposite().lookup(DictionaryQuery("かな")).map { it.text })
        }
    }

    @Test fun `全件スナップショットは有効辞書の世代と順を一度に固定し再起動後も一致する`() {
        val name = databaseName()
        lateinit var old: DictionaryLookupSnapshot
        SQLiteDictionaryRepository(context, name).use { repository ->
            repository.replacePersonal(document("かな /個人かな/\nにほん /個人日本/"), 0)
            repository.importSystem("a", "辞書A", document("かな /Aかな/\nにほん /A日本/"))
            repository.importSystem("b", "辞書B", document("かな /Bかな/"))
            repository.importSystem("empty", "空辞書", document(""))
            repository.setSystemOrder(listOf("b", "empty", "a"))
            old = repository.loadSnapshot()

            repository.importSystem("b", "辞書B", document("かな /B更新/"), 1)
            repository.setSourceEnabled("a", false)
            val current = repository.loadSnapshot()

            assertEquals(listOf("b", "empty", "a"), old.systems.map { it.id })
            assertEquals(listOf(1L, 1L, 1L), old.systems.map { it.generation })
            assertEquals(listOf("個人かな", "Bかな", "Aかな"), old.asComposite().lookup(DictionaryQuery("かな")).map { it.text })
            assertEquals(listOf("個人日本", "A日本"), old.asComposite().lookup(DictionaryQuery("にほん")).map { it.text })
            assertEquals(listOf("b", "empty"), current.systems.map { it.id })
            assertEquals(listOf(2L, 1L), current.systems.map { it.generation })
            assertEquals(listOf("個人かな", "B更新"), current.asComposite().lookup(DictionaryQuery("かな")).map { it.text })
            assertEquals(listOf("個人かな", "Bかな", "Aかな"), old.asComposite().lookup(DictionaryQuery("かな")).map { it.text })
            assertThrows(UnsupportedOperationException::class.java) {
                @Suppress("UNCHECKED_CAST")
                (current.systems as MutableList<SkkDictionarySource>).add(current.systems.first())
            }
        }

        SQLiteDictionaryRepository(context, name).use { reopened ->
            val snapshot = reopened.loadSnapshot()
            assertEquals(listOf("b", "empty"), snapshot.systems.map { it.id })
            assertEquals(listOf(2L, 1L), snapshot.systems.map { it.generation })
            assertEquals(listOf("個人かな", "B更新"), snapshot.asComposite().lookup(DictionaryQuery("かな")).map { it.text })
        }
    }

    @Test fun `行変更後と公開直前の例外は旧世代をclose後も保持する`() {
        for (point in DictionaryWritePoint.entries) {
            val name = databaseName()
            var failure: DictionaryWritePoint? = null
            val repository = SQLiteDictionaryRepository(context, name) { reached ->
                if (reached == failure) throw InjectedWriteFailure()
            }
            repository.importSystem("system", "辞書", document("かな /旧候補/"))
            failure = point
            val error = assertThrows(InjectedWriteFailure::class.java) {
                repository.importSystem("system", "辞書更新", document("かな /秘密候補/"), 1)
            }
            assertFalse(error.toString().contains("秘密候補"))
            assertEquals(listOf("旧候補"), lookupTexts(repository, "かな"))
            assertEquals(1L, repository.listSources().single { it.id == "system" }.generation)
            repository.close()

            SQLiteDictionaryRepository(context, name).use { reopened ->
                assertEquals(listOf("旧候補"), lookupTexts(reopened, "かな"))
                assertEquals(1L, reopened.listSources().single { it.id == "system" }.generation)
            }
        }
    }

    @Test fun `個人マージの例外は旧候補と世代をclose後も保持する`() {
        for (point in DictionaryWritePoint.entries) {
            val name = databaseName()
            var failure: DictionaryWritePoint? = null
            val repository = SQLiteDictionaryRepository(context, name) { reached ->
                if (reached == failure) throw InjectedWriteFailure()
            }
            repository.replacePersonal(document("かな /旧個人/"), 0)
            failure = point
            assertThrows(InjectedWriteFailure::class.java) {
                repository.mergePersonal(document("かな /新個人/"), 1)
            }
            repository.close()

            SQLiteDictionaryRepository(context, name).use { reopened ->
                assertEquals(listOf("旧個人"), lookupTexts(reopened, "かな"))
                assertEquals(1L, reopened.listSources().first().generation)
            }
        }
    }

    @Test fun `新規システム辞書の失敗は一覧にも候補にも公開しない`() {
        for (point in DictionaryWritePoint.entries) {
            val name = databaseName()
            SQLiteDictionaryRepository(context, name, DictionaryWriteFailpoint { reached ->
                if (reached == point) throw InjectedWriteFailure()
            }).use { repository ->
                assertThrows(InjectedWriteFailure::class.java) {
                    repository.importSystem("new", "新規", document("かな /候補/"))
                }
                assertEquals(listOf("personal"), repository.listSources().map { it.id })
                assertTrue(lookupTexts(repository, "かな").isEmpty())
            }
        }
    }

    @Test fun `古い世代の個人マージとシステム更新を拒否して内容を維持する`() {
        SQLiteDictionaryRepository(context, databaseName()).use { repository ->
            repository.replacePersonal(document("かな /個人/"), 0)
            repository.importSystem("system", "辞書", document("かな /システム/"))

            val personalError = assertThrows(StaleDictionaryGenerationException::class.java) {
                repository.mergePersonal(document("かな /古い更新/"), 0)
            }
            val systemError = assertThrows(StaleDictionaryGenerationException::class.java) {
                repository.importSystem("system", "辞書", document("かな /古い更新/"), 0)
            }

            assertEquals(SQLiteDictionaryRepository.PERSONAL_SOURCE_ID, personalError.sourceId)
            assertEquals("system", systemError.sourceId)
            assertEquals(listOf("個人", "システム"), lookupTexts(repository, "かな"))
            assertEquals(listOf(1L, 1L), repository.listSources().map { it.generation })
        }
    }

    @Test fun `個人辞書を書出してcodecで再読込すると構造と順序が一致する`() {
        val source = document("""
            おおk /大/[く/多;数量/]/
            にほん /日本;国名/(concat "二\057本")/
        """.trimIndent())
        SQLiteDictionaryRepository(context, databaseName()).use { repository ->
            repository.replacePersonal(source, 0)
            val exported = repository.exportPersonal()
            assertFalse(exported.take(3).toByteArray().contentEquals(byteArrayOf(0xef.toByte(), 0xbb.toByte(), 0xbf.toByte())))
            assertTrue(String(exported, StandardCharsets.UTF_8).endsWith("\n"))
            assertEquals(source.entries, SkkDictionaryCodec.parse(exported).entries)
            assertArrayEquals(exported, repository.exportPersonal())
        }
    }

    @Test
    @SQLiteMode(SQLiteMode.Mode.NATIVE)
    fun `破損DBは自動削除せず一般化した例外を返して原本を残す`() {
        val name = databaseName()
        SQLiteDictionaryRepository(context, name).use { repository ->
            repository.replacePersonal(document("ひみつ /非公開本文/"), 0)
        }
        val databaseFile = context.getDatabasePath(name)
        assumeTrue("実ファイルを使う native SQLite でだけ破損処理を確認します", databaseFile.isFile)
        val corrupted = "not-a-sqlite-database".toByteArray(StandardCharsets.UTF_8)
        databaseFile.writeBytes(corrupted)

        val repository = SQLiteDictionaryRepository(context, name)
        val error = try {
            assertThrows(DictionaryDatabaseCorruptionException::class.java) {
                repository.listSources()
            }
        } finally {
            repository.close()
        }
        assertEquals("辞書データベースが破損しています", error.message)
        assertFalse(error.toString().contains("非公開本文"))
        assertArrayEquals(corrupted, databaseFile.readBytes())
    }

    private fun document(text: String): SkkDictionaryDocument = SkkDictionaryCodec.parseText(text)

    private fun lookupTexts(repository: SQLiteDictionaryRepository, key: String): List<String> =
        repository.lookup(key).asComposite().lookup(DictionaryQuery(key)).map { it.text }

    private fun databaseName(): String = "dictionary-${UUID.randomUUID()}.db".also(databases::add)

    private class InjectedWriteFailure : RuntimeException()
}
