package se.haya.skk.input

import android.content.Context
import java.util.UUID
import java.util.concurrent.Executor
import se.haya.skk.core.BasicSkkAction
import se.haya.skk.core.BasicSkkDictionary
import se.haya.skk.core.BasicSkkEngine
import se.haya.skk.core.DictionaryCandidate
import se.haya.skk.core.DictionaryQuery
import se.haya.skk.core.PredictionQuery
import se.haya.skk.core.PredictionSearchFailure
import se.haya.skk.core.PredictionSearchResult
import se.haya.skk.core.dictionary.SkkDictionaryCandidate
import se.haya.skk.core.dictionary.SkkDictionaryDocument
import se.haya.skk.core.dictionary.SkkDictionaryEncoding
import se.haya.skk.core.dictionary.SkkDictionaryEntry
import se.haya.skk.dictionary.SQLiteDictionaryRepository
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
class PredictionPrefixIntegrationTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private val databases = mutableListOf<String>()
    private val direct = Executor { it.run() }

    @After fun removeDatabases() {
        databases.forEach(context::deleteDatabase)
    }

    @Test fun `てnのかな展開と字面を非同期経路からSQLiteへ個別に問い合わせる`() {
        val name = "prediction-prefix-${UUID.randomUUID()}.db".also(databases::add)
        SQLiteDictionaryRepository(context, name).use { repository ->
            val entries = listOf(
                SkkDictionaryEntry("てな", listOf(SkkDictionaryCandidate("手名"))),
                SkkDictionaryEntry("てん", listOf(SkkDictionaryCandidate("点"))),
                SkkDictionaryEntry("てn", listOf(SkkDictionaryCandidate("英字", okuriCondition = "ん"))),
            ) + List(710) { index ->
                val suffix = index.toString().padStart(4, '0')
                SkkDictionaryEntry("てん$suffix", listOf(SkkDictionaryCandidate("候補$suffix")))
            }
            repository.importSystem("system", "試験辞書", SkkDictionaryDocument(
                entries, SkkDictionaryEncoding.UTF8,
            ))
            val oldPerPrefixChars = PredictionQuery.MAX_TOTAL_RESULT_CHARS / 10
            assertEquals(
                PredictionSearchResult.Indeterminate(PredictionSearchFailure.RESULT_LIMIT),
                repository.predict(PredictionQuery(
                    "てん", totalResultCharsLimit = oldPerPrefixChars,
                ), repository.dictionaryRevision()),
            )
            val searched = mutableListOf<String>()
            val delegate = object : BasicSkkDictionary {
                override fun lookup(query: DictionaryQuery) = emptyList<DictionaryCandidate>()
                override fun predict(query: PredictionQuery): PredictionSearchResult {
                    searched += query.prefix
                    return repository.predict(query, repository.dictionaryRevision())
                }
            }
            val revision = AsyncPredictionRevision(1, 1, repository.dictionaryRevision())
            val async = AsyncPredictionDictionary(delegate, direct, direct, { revision }) { _, _ -> }
            val engine = BasicSkkEngine(async)
            engine.dispatch(BasicSkkAction.SetTouchPrediction(true))
            engine.dispatch(BasicSkkAction.StartReading)
            engine.dispatch(BasicSkkAction.Text("te", interpretCommands = false))
            searched.clear()

            engine.dispatch(BasicSkkAction.Text("n", interpretCommands = false))

            assertTrue(listOf("てな", "てに", "てぬ", "てね", "ての").all(searched::contains))
            assertTrue("てん" in searched)
            assertTrue("てn" in searched)
            assertEquals(2, searched.count { it == "てん" || it == "てn" })
            val candidates = engine.currentView.prediction!!.items.map { it.committedText }
            assertTrue("手名" in candidates)
            assertTrue("点" in candidates)
            assertTrue("英字ん" in candidates)
        }
    }
}
