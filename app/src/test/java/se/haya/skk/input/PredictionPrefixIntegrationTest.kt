package se.haya.skk.input

import android.content.Context
import android.text.Selection
import android.view.View
import android.view.inputmethod.BaseInputConnection
import java.util.UUID
import java.util.concurrent.Executor
import se.haya.skk.core.BasicSkkAction
import se.haya.skk.core.BasicSkkDictionary
import se.haya.skk.core.BasicSkkEngine
import se.haya.skk.core.DictionaryCandidate
import se.haya.skk.core.DictionaryQuery
import se.haya.skk.core.PredictionQuery
import se.haya.skk.core.PredictionHistoryTarget
import se.haya.skk.core.predictionCandidateRetainedChars
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

    private class QueueExecutor : Executor {
        private val tasks = ArrayDeque<Runnable>()
        val size: Int get() = tasks.size
        override fun execute(command: Runnable) { tasks.addLast(command) }
        fun runAll() { while (tasks.isNotEmpty()) tasks.removeFirst().run() }
    }

    @Test fun `境界付きの最初のかなだけで非同期SQLite予測を表示し空読みは検索しない`() {
        val name = "prediction-first-kana-${UUID.randomUUID()}.db".also(databases::add)
        SQLiteDictionaryRepository(context, name).use { repository ->
            repository.importSystem("system", "試験辞書", SkkDictionaryDocument(listOf(
                SkkDictionaryEntry("か", listOf(SkkDictionaryCandidate("蚊"))),
                SkkDictionaryEntry("かな", listOf(SkkDictionaryCandidate("仮名"))),
                SkkDictionaryEntry("かに", listOf(SkkDictionaryCandidate("蟹"))),
            ), SkkDictionaryEncoding.UTF8))
            val searched = mutableListOf<PredictionQuery>()
            val delegate = object : BasicSkkDictionary {
                override fun lookup(query: DictionaryQuery) = emptyList<DictionaryCandidate>()
                override fun predict(query: PredictionQuery): PredictionSearchResult {
                    searched += query
                    return repository.predict(query, repository.dictionaryRevision())
                }
            }
            val connection = BaseInputConnection(View(context), true)
            Selection.setSelection(connection.editable, 0)
            val background = QueueExecutor()
            val callbacks = QueueExecutor()
            val session = EditorSession(1, connection, false, true, 0, 0, delegate,
                dictionaryExecutor = background, callbackExecutor = callbacks)
            session.setTouchCandidatePresentation(true)
            session.handleTouch(BasicSkkAction.TouchText("あ"))
            session.handleTouch(BasicSkkAction.StartTouchReading())
            assertEquals(0, background.size)
            assertTrue(searched.isEmpty())
            session.handleTouch(BasicSkkAction.Cancel)

            session.handleTouch(BasicSkkAction.TouchText("か", boundary = true))
            assertEquals("か", session.engine.state.reading)
            assertEquals(PredictionSearchFailure.PENDING, session.view.prediction?.failure)
            background.runAll()
            callbacks.runAll()
            assertEquals(listOf("か"), searched.map { it.prefix })
            assertEquals(listOf("蚊", "仮名", "蟹"),
                session.view.prediction!!.items.map { it.committedText })
            assertEquals("あか", connection.editable.toString())

            session.handleTouch(BasicSkkAction.TouchText("な"))
            background.runAll()
            callbacks.runAll()
            assertEquals(listOf("か", "かな"), searched.map { it.prefix })
            assertEquals(listOf("仮名"), session.view.prediction!!.items.map { it.committedText })
            session.handleTouch(BasicSkkAction.Backspace)
            session.handleTouch(BasicSkkAction.Backspace)
            background.runAll()
            callbacks.runAll()
            assertEquals(listOf("か", "かな"), searched.map { it.prefix })
            assertEquals(null, session.view.prediction)
            session.close()
        }
    }

    private fun importBroadKanaPrefix(repository: SQLiteDictionaryRepository) {
        val entries = List(5_998) { index ->
            val suffix = index.toString().padStart(4, '0')
            SkkDictionaryEntry("か$suffix", listOf(SkkDictionaryCandidate("候補$suffix")))
        } + listOf(
            SkkDictionaryEntry("かな", listOf(SkkDictionaryCandidate("仮名"))),
            SkkDictionaryEntry("かわ", listOf(SkkDictionaryCandidate("最後の学習候補"))),
        )
        repository.importSystem("system", "一文字予測の試験辞書", SkkDictionaryDocument(
            entries, SkkDictionaryEncoding.UTF8,
        ))
        repository.promotePersonalCandidate("かわ", SkkDictionaryCandidate("最後の学習候補"),
            PredictionHistoryTarget("かわ", "最後の学習候補", null, "最後の学習候補"))
    }

    @Test fun `六千見出しに一致する一文字予測も読み順の末尾にある最新候補を先頭へ返す`() {
        val name = "prediction-broad-prefix-${UUID.randomUUID()}.db".also(databases::add)
        SQLiteDictionaryRepository(context, name).use { repository ->
            importBroadKanaPrefix(repository)
            val revision = repository.dictionaryRevision()
            val narrowed = repository.predict(PredictionQuery("かな"), revision)
                as PredictionSearchResult.Ready
            assertEquals(listOf("仮名"), narrowed.items.map { it.committedText })

            val firstKana = repository.predict(PredictionQuery("か"), revision)
            assertTrue("一文字の検索も順位を確定した候補を返します: $firstKana",
                firstKana is PredictionSearchResult.Ready)
            firstKana as PredictionSearchResult.Ready
            assertEquals("最後の学習候補", firstKana.items.first().committedText)
            assertEquals("かわ", firstKana.items.first().historyTarget.readingKey)
            assertEquals(PredictionQuery.MAX_RESULTS, firstKana.items.size)
            assertTrue(firstKana.hasMore)
        }
    }

    @Test fun `広い辞書の境界付き一文字かなは次の入力を待たず非同期予測を表示する`() {
        val name = "prediction-broad-boundary-${UUID.randomUUID()}.db".also(databases::add)
        SQLiteDictionaryRepository(context, name).use { repository ->
            importBroadKanaPrefix(repository)
            val delegate = object : BasicSkkDictionary {
                override fun lookup(query: DictionaryQuery) = emptyList<DictionaryCandidate>()
                override fun predict(query: PredictionQuery) =
                    repository.predict(query, repository.dictionaryRevision())
            }
            val connection = BaseInputConnection(View(context), true)
            Selection.setSelection(connection.editable, 0)
            val background = QueueExecutor()
            val callbacks = QueueExecutor()
            val session = EditorSession(1, connection, false, true, 0, 0, delegate,
                dictionaryExecutor = background, callbackExecutor = callbacks)
            session.setTouchCandidatePresentation(true)
            assertEquals(0, background.size)

            session.handleTouch(BasicSkkAction.TouchText("か", boundary = true))
            assertEquals("か", connection.editable.toString())
            assertEquals(PredictionSearchFailure.PENDING, session.view.prediction?.failure)
            background.runAll()
            callbacks.runAll()
            assertEquals("一文字入力の検索結果を次の入力前に表示します", null,
                session.view.prediction?.failure)
            assertEquals("最後の学習候補", session.view.prediction!!.items.first().committedText)
            assertEquals("か", session.engine.state.reading)

            session.handleTouch(BasicSkkAction.TouchText("な"))
            background.runAll()
            callbacks.runAll()
            assertEquals(listOf("仮名"), session.view.prediction!!.items.map { it.committedText })
            session.close()
        }
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
            val perPrefixChars = PredictionQuery.MAX_TOTAL_RESULT_CHARS / 10
            // 走査した全件の累積文字数ではなく、一読みの作業領域と保持する上位候補を制限します。
            val bounded = repository.predict(PredictionQuery(
                "てん", totalResultCharsLimit = perPrefixChars,
            ), repository.dictionaryRevision()) as PredictionSearchResult.Ready
            assertEquals(PredictionQuery.MAX_RESULTS, bounded.items.size)
            assertEquals("点", bounded.items.first().committedText)
            assertTrue(bounded.hasMore)
            assertTrue(bounded.items.sumOf(::predictionCandidateRetainedChars) <= perPrefixChars)
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
