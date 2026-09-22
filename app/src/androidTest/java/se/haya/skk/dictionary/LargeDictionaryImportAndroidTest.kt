package se.haya.skk.dictionary

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import se.haya.skk.core.DictionaryQuery
import se.haya.skk.core.dictionary.SkkDictionaryCodec
import se.haya.skk.core.dictionary.SkkDictionaryEncoding
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LargeDictionaryImportAndroidTest {
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val databaseName = "skk-large-import-${UUID.randomUUID()}.db"

    @After fun removeDatabase() {
        context.deleteDatabase(databaseName)
    }

    @Test fun officialLargeDictionaryImportsAndSurvivesReopen() {
        // 実辞書をアプリ専用の外部領域へ配置した場合だけ実行します。
        val file = File(context.getExternalFilesDir(null), "SKK-JISYO.L")
        assumeTrue("SKK-JISYO.L を ${file.absolutePath} に配置してください", file.isFile)
        val document = SkkDictionaryCodec.parse(file.readBytes())
        assertEquals(SkkDictionaryEncoding.EUC_JP, document.encoding)
        assertTrue(document.entries.size > 100_000)

        SQLiteDictionaryRepository(context, databaseName).use { repository ->
            repository.importSystem("official-l", "SKK-JISYO.L", document)
            assertTrue(repository.listSources().any { it.id == "official-l" })
            assertTrue(repository.lookup("ゆるb").asComposite().lookup(DictionaryQuery("ゆるb")).isNotEmpty())
        }
        SQLiteDictionaryRepository(context, databaseName).use { repository ->
            val candidates = repository.lookup("ゆるb").asComposite().lookup(DictionaryQuery("ゆるb"))
            assertEquals("弛", candidates.first().text)
            assertEquals("[文語]", candidates.first().annotation)
            assertTrue(repository.lookup("/").asComposite().lookup(DictionaryQuery("/")).isNotEmpty())
        }
    }
}
