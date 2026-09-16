package jp.hayase.skk.dictionary

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteFullException
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import java.util.UUID
import jp.hayase.skk.core.DictionaryQuery
import jp.hayase.skk.core.dictionary.SkkDictionaryCodec
import jp.hayase.skk.core.dictionary.SkkDictionaryDocument
import jp.hayase.skk.core.dictionary.SkkDictionaryEncoding
import jp.hayase.skk.core.dictionary.SkkDictionaryError
import jp.hayase.skk.core.dictionary.SkkDictionaryFormatException
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SQLiteDictionaryAndroidTest {
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val fixtures = mutableListOf<String>()

    @After fun removeFixtures() {
        fixtures.forEach { name ->
            check(name.startsWith(FIXTURE_PREFIX))
            context.deleteDatabase(name)
        }
    }

    @Test fun codecStrictEucJpAndUtf8RoundTrip() {
        val source = """
            おおk /大/[く/多;数量/]/
            にほん /日本;国名/二本/
        """.trimIndent()
        val eucJp = Charset.forName("EUC-JP")
        val parsed = SkkDictionaryCodec.parse(source.toByteArray(eucJp))
        assertEquals(SkkDictionaryEncoding.EUC_JP, parsed.encoding)

        val utf8 = SkkDictionaryCodec.encodeUtf8(parsed)
        assertFalse(utf8.copyOfRange(0, 3).contentEquals(UTF8_BOM))
        assertTrue(String(utf8, StandardCharsets.UTF_8).endsWith("\n"))
        assertEquals(parsed.entries, SkkDictionaryCodec.parse(utf8).entries)

        val malformed = assertThrows(SkkDictionaryFormatException::class.java) {
            SkkDictionaryCodec.parse(byteArrayOf(0x8f.toByte()), SkkDictionaryEncoding.EUC_JP)
        }
        assertEquals(SkkDictionaryError.DECODING_FAILED, malformed.error)
    }

    @Test fun repositoryReopenMergeOrderDisableIndexedLookupAndFullSnapshot() {
        val name = fixtureName()
        SQLiteDictionaryRepository(context, name).use { repository ->
            repository.replacePersonal(document("かな /個人旧;旧注/\nにほん /個人日本/"), 0)
            repository.mergePersonal(document("かな /個人新/個人旧/"), 1)
            repository.importSystem("a", "辞書A", document("かな /A/\n別 /別A/"))
            repository.importSystem("b", "辞書B", document("かな /B/\nにほん /B日本/"))
            repository.setSystemOrder(listOf("b", "a"))
            repository.setSourceEnabled("a", false)

            assertEquals(listOf("個人新", "個人旧", "B"), lookup(repository, "かな"))
            assertTrue(lookup(repository, "別").isEmpty())
            val snapshot = repository.loadSnapshot()
            assertEquals(listOf("b"), snapshot.systems.map { it.id })
            assertEquals(listOf("個人日本", "B日本"), snapshot.asComposite().lookup(DictionaryQuery("にほん")).map { it.text })
        }

        assertIndexedFixture(name)
        SQLiteDictionaryRepository(context, name).use { reopened ->
            assertEquals(listOf("personal", "b", "a"), reopened.listSources().map { it.id })
            assertEquals(listOf(true, true, false), reopened.listSources().map { it.enabled })
            assertEquals(listOf(2L, 1L, 1L), reopened.listSources().map { it.generation })
            assertEquals(listOf("個人新", "個人旧", "B"), lookup(reopened, "かな"))
        }
    }

    @Test fun realSQLiteFullRollsBackAndReopenKeepsPublishedGeneration() {
        val name = fixtureName()
        SQLiteDictionaryRepository(context, name).use { repository ->
            repository.importSystem("system", "辞書", document("かな /旧候補/"))
        }
        var configuredPageLimit = 0L
        val repository = SQLiteDictionaryRepository(
            context,
            name,
            DictionaryWriteFailpoint { },
            DictionaryDatabaseConfigurator { database ->
                configuredPageLimit = pragmaLong(database, "page_count")
                database.rawQuery("PRAGMA max_page_count=$configuredPageLimit", null).use { cursor ->
                    check(cursor.moveToFirst())
                    assertEquals(configuredPageLimit, cursor.getLong(0))
                }
                assertEquals(configuredPageLimit, pragmaLong(database, "max_page_count"))
            },
        )
        try {
            assertThrows(SQLiteFullException::class.java) {
                repository.importSystem("system", "更新", largeDocument(), 1)
            }
            assertTrue(configuredPageLimit > 0)
            assertEquals(listOf("旧候補"), lookup(repository, "かな"))
            assertEquals(1L, repository.listSources().single { it.id == "system" }.generation)
        } finally {
            repository.close()
        }
        SQLiteDictionaryRepository(context, name).use { reopened ->
            assertEquals(listOf("旧候補"), lookup(reopened, "かな"))
            assertEquals(1L, reopened.listSources().single { it.id == "system" }.generation)
        }
    }

    @Test fun injectedPublicationFailureIsDistinctAndRollsBack() {
        val name = fixtureName()
        var fail = false
        val repository = SQLiteDictionaryRepository(context, name) { point ->
            if (fail && point == DictionaryWritePoint.BEFORE_PUBLICATION) throw InjectedFailure()
        }
        try {
            repository.replacePersonal(document("かな /旧個人/"), 0)
            fail = true
            val error = assertThrows(InjectedFailure::class.java) {
                repository.mergePersonal(document("かな /非公開本文/"), 1)
            }
            assertFalse(error.toString().contains("非公開本文"))
            assertEquals(listOf("旧個人"), lookup(repository, "かな"))
        } finally {
            repository.close()
        }
        SQLiteDictionaryRepository(context, name).use { reopened ->
            assertEquals(listOf("旧個人"), lookup(reopened, "かな"))
            assertEquals(1L, reopened.listSources().first().generation)
        }
    }

    @Test fun corruptedFixtureIsPreservedAndNotSilentlyRecreated() {
        val name = fixtureName()
        SQLiteDictionaryRepository(context, name).use { repository ->
            repository.replacePersonal(document("ひみつ /非公開本文/"), 0)
        }
        val file = context.getDatabasePath(name)
        val corrupted = "not-a-sqlite-database".toByteArray(StandardCharsets.UTF_8)
        file.writeBytes(corrupted)

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
        assertArrayEquals(corrupted, file.readBytes())
    }

    private fun fixtureName(): String = "$FIXTURE_PREFIX${UUID.randomUUID()}.db".also(fixtures::add)

    private fun document(text: String): SkkDictionaryDocument = SkkDictionaryCodec.parseText(text)

    private fun lookup(repository: SQLiteDictionaryRepository, key: String): List<String> =
        repository.lookup(key).asComposite().lookup(DictionaryQuery(key)).map { it.text }

    private fun assertIndexedFixture(name: String) {
        SQLiteDatabase.openDatabase(context.getDatabasePath(name).path, null, SQLiteDatabase.OPEN_READONLY).use { database ->
            database.rawQuery(
                "SELECT name FROM sqlite_master WHERE type = 'index' AND name = 'candidates_key_lookup'",
                null,
            ).use { cursor -> assertTrue(cursor.moveToFirst()) }
        }
    }

    private fun pragmaLong(database: SQLiteDatabase, name: String): Long =
        database.rawQuery("PRAGMA $name", null).use { cursor ->
            check(cursor.moveToFirst())
            cursor.getLong(0)
        }

    private fun largeDocument(): SkkDictionaryDocument = document(buildString {
        repeat(400) { index ->
            append("みだし").append(index).append(" /")
            append("候補").append(index).append("長".repeat(4096)).append("/\n")
        }
    })

    private class InjectedFailure : RuntimeException()

    private companion object {
        const val FIXTURE_PREFIX = "dictionary-it-"
        val UTF8_BOM = byteArrayOf(0xef.toByte(), 0xbb.toByte(), 0xbf.toByte())
    }
}
