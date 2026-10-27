package se.haya.skk.core

import org.junit.Assert.*
import org.junit.Test

class PredictionPagingTest {
    private class Dictionary : BasicSkkDictionary {
        val queries = mutableListOf<PredictionQuery>()
        var pending = false
        var failure: PredictionSearchFailure? = null
        override fun lookup(query: DictionaryQuery) = emptyList<DictionaryCandidate>()
        override fun predict(query: PredictionQuery): PredictionSearchResult {
            queries += query
            failure?.let { return PredictionSearchResult.Indeterminate(it) }
            if (pending) return PredictionSearchResult.Indeterminate(PredictionSearchFailure.PENDING)
            return PredictionSearchResult.Ready((0 until query.limit).map { index ->
                val text = "${query.prefix}候補$index"
                PredictionCandidate(DictionaryCandidate(text), text,
                    PredictionHistoryTarget(query.prefix, text, null, text))
            }, hasMore = true)
        }
    }

    private fun engine(dictionary: Dictionary, capacity: Int = 4) = BasicSkkEngine(
        dictionary, RegistrationPolicy(enabled = true), candidatePageCapacityProvider = { _, _ -> capacity },
    ).also {
        it.dispatch(BasicSkkAction.SetTouchPrediction(true))
        it.dispatch(BasicSkkAction.StartReading)
        it.dispatch(BasicSkkAction.Text("ka", interpretCommands = false))
    }

    @Test fun `最初は16件だけ取得し保存済みのページ間では検索しない`() {
        val dictionary = Dictionary()
        val engine = engine(dictionary)
        assertEquals(listOf(16), dictionary.queries.map { it.limit })
        repeat(3) { engine.dispatch(BasicSkkAction.NextCandidatePage) }
        assertEquals(12, engine.currentView.prediction!!.pageStart)
        assertEquals(1, dictionary.queries.size)
        engine.dispatch(BasicSkkAction.NextCandidatePage)
        assertEquals(listOf(16, 32), dictionary.queries.map { it.limit })
        assertEquals(16, engine.currentView.prediction!!.pageStart)
        engine.dispatch(BasicSkkAction.PreviousCandidatePage)
        assertEquals(12, engine.currentView.prediction!!.pageStart)
        engine.dispatch(BasicSkkAction.NextCandidatePage)
        assertEquals(2, dictionary.queries.size)
    }

    @Test fun `追加取得中は元のページを残し完了後に次のページへ進む`() {
        val dictionary = Dictionary()
        val engine = engine(dictionary)
        repeat(3) { engine.dispatch(BasicSkkAction.NextCandidatePage) }
        val before = engine.currentView.prediction!!
        dictionary.pending = true
        engine.dispatch(BasicSkkAction.NextCandidatePage)
        val pending = engine.currentView.prediction!!
        assertEquals(before.items, pending.items)
        assertEquals(12, pending.pageStart)
        assertTrue(pending.loadingMore)
        assertFalse(pending.canNextPage)
        engine.dispatch(BasicSkkAction.NextCandidatePage)
        assertEquals(listOf(16, 32, 32), dictionary.queries.map { it.limit })
        dictionary.pending = false
        engine.dispatch(BasicSkkAction.RefreshPrediction)
        assertEquals(16, engine.currentView.prediction!!.pageStart)
        assertFalse(engine.currentView.prediction!!.loadingMore)
    }

    @Test fun `入力が変わると追加要求と古い候補を捨て16件から始める`() {
        val dictionary = Dictionary()
        val engine = engine(dictionary)
        repeat(3) { engine.dispatch(BasicSkkAction.NextCandidatePage) }
        val stale = engine.currentView.prediction!!
        dictionary.pending = true
        engine.dispatch(BasicSkkAction.NextCandidatePage)
        engine.dispatch(BasicSkkAction.Text("na", interpretCommands = false))
        assertTrue(engine.currentView.prediction!!.items.isEmpty())
        assertEquals(16, dictionary.queries.last().limit)
        dictionary.pending = false
        engine.dispatch(BasicSkkAction.RefreshPrediction)
        assertEquals(0, engine.currentView.prediction!!.pageStart)
        assertTrue(engine.currentView.prediction!!.items.all { it.committedText.startsWith("かな") })
        assertNull(engine.dispatch(BasicSkkAction.CommitPrediction(0, expectedView = stale)).commit)
        assertEquals("かな候補0", engine.dispatch(BasicSkkAction.CommitPrediction(0)).commit)
    }

    @Test fun `64件まで取得し最後のページで追加要求を止める`() {
        val dictionary = Dictionary()
        val engine = engine(dictionary, 5)
        repeat(100) { engine.dispatch(BasicSkkAction.NextCandidatePage) }
        assertEquals(listOf(16, 32, 48, 64), dictionary.queries.map { it.limit })
        val view = engine.currentView.prediction!!
        assertEquals(64, view.items.size)
        assertEquals(63, view.pageStart)
        assertEquals(1, view.pageSize)
        assertFalse(view.canNextPage)
        assertFalse(view.hasMore)
    }

    @Test fun `追加取得の失敗では既知の候補を残し追加要求を停止する`() {
        val dictionary = Dictionary()
        val engine = engine(dictionary)
        repeat(3) { engine.dispatch(BasicSkkAction.NextCandidatePage) }
        dictionary.failure = PredictionSearchFailure.UNAVAILABLE
        engine.dispatch(BasicSkkAction.NextCandidatePage)
        assertEquals(16, engine.currentView.prediction!!.items.size)
        assertEquals(12, engine.currentView.prediction!!.pageStart)
        assertFalse(engine.currentView.prediction!!.canNextPage)
        repeat(3) { engine.dispatch(BasicSkkAction.NextCandidatePage) }
        assertEquals(2, dictionary.queries.size)
        engine.resetPredictionDemand()
        dictionary.failure = null
        engine.dispatch(BasicSkkAction.RefreshPrediction)
        assertEquals(16, dictionary.queries.last().limit)
        assertEquals(0, engine.currentView.prediction!!.pageStart)
    }
    @Test fun `登録の内部予測は独立して取得し空の登録から戻ると外側のページを復元する`() {
        val dictionary = Dictionary()
        val engine = engine(dictionary)
        engine.dispatch(BasicSkkAction.NextCandidatePage)
        assertEquals(4, engine.currentView.prediction!!.pageStart)
        engine.dispatch(BasicSkkAction.RegisterCandidate)
        assertNotNull(engine.currentView.registration)
        engine.dispatch(BasicSkkAction.StartReading)
        engine.dispatch(BasicSkkAction.Text("na", interpretCommands = false))
        assertEquals(0, engine.currentView.prediction!!.pageStart)
        engine.dispatch(BasicSkkAction.NextCandidatePage)
        assertEquals(4, engine.currentView.prediction!!.pageStart)
        val internal = engine.currentView.prediction!!
        engine.dispatch(BasicSkkAction.Cancel)
        engine.dispatch(BasicSkkAction.Enter)
        assertNull(engine.currentView.registration)
        assertEquals(4, engine.currentView.prediction!!.pageStart)
        assertTrue(engine.currentView.prediction!!.items.all { it.committedText.startsWith("か") })
        assertNull(engine.dispatch(BasicSkkAction.CommitPrediction(4, expectedView = internal)).commit)
        assertEquals("か候補4", engine.dispatch(BasicSkkAction.CommitPrediction(4)).commit)
    }

    @Test fun `未完了の同じ読みは通常操作後も要求を更新する`() {
        val dictionary = Dictionary().also { it.pending = true }
        val engine = engine(dictionary)
        assertEquals(1, dictionary.queries.size)
        engine.dispatch(BasicSkkAction.Left)
        assertEquals(2, dictionary.queries.size)
        assertEquals(dictionary.queries.first(), dictionary.queries.last())
        dictionary.pending = false
        engine.dispatch(BasicSkkAction.RefreshPrediction)
        assertEquals(16, engine.currentView.prediction!!.items.size)
    }

    @Test fun `辞書更新後に登録から戻ると古い取得範囲を復元せず再検索する`() {
        val dictionary = Dictionary()
        val engine = engine(dictionary)
        repeat(4) { engine.dispatch(BasicSkkAction.NextCandidatePage) }
        assertEquals(32, engine.currentView.prediction!!.items.size)
        engine.dispatch(BasicSkkAction.RegisterCandidate)
        engine.resetPredictionDemand()
        engine.dispatch(BasicSkkAction.Enter)
        assertEquals(16, engine.currentView.prediction!!.items.size)
        assertEquals(0, engine.currentView.prediction!!.pageStart)
        assertEquals(listOf(16, 32, 16), dictionary.queries.map { it.limit })
    }

}
