package se.haya.skk.core

import se.haya.skk.core.dictionary.DeferredDictionaryReadException
import se.haya.skk.core.numeric.NumericSkkDictionary
import org.junit.Assert.*
import org.junit.Test

class DeferredDictionaryEngineTest {
    private class ColdDictionary(val entries: Map<String, List<DictionaryCandidate>>) : BasicSkkDictionary {
        val ready = mutableSetOf<String>()
        override fun lookup(query: DictionaryQuery): List<DictionaryCandidate> {
            if (query.readingKey !in ready) throw DeferredDictionaryReadException { ready += query.readingKey }
            return entries[query.readingKey].orEmpty()
        }
    }

    private fun pending(block: () -> Unit): DeferredDictionaryReadException {
        try { block() } catch (pending: DeferredDictionaryReadException) { return pending }
        throw AssertionError("辞書参照の延期が必要です")
    }

    @Test fun `変換前のローマ字と読みを完全に戻して一度だけ確定する`() {
        val dictionary = ColdDictionary(mapOf("にほん" to listOf(DictionaryCandidate("日本"))))
        val engine = BasicSkkEngine(dictionary)
        engine.dispatch(BasicSkkAction.Text("Nihon"))
        val state = engine.state
        val view = engine.currentView
        val deferred = pending { engine.dispatch(BasicSkkAction.Text(" ")) }
        assertEquals(state, engine.state)
        assertEquals(view, engine.currentView)
        deferred.load()
        assertEquals("日本", engine.dispatch(BasicSkkAction.Text(" ")).view.candidate?.committedText)
        assertEquals("日本", engine.dispatch(BasicSkkAction.Enter).commit)
        assertFalse(engine.dispatch(BasicSkkAction.Enter).handled)
    }

    @Test fun `同一バッチの確定と学習効果は途中の検索延期で漏れない`() {
        val dictionary = ColdDictionary(mapOf("にほん" to listOf(DictionaryCandidate("日本"))))
        val engine = BasicSkkEngine(dictionary, RegistrationPolicy(), learningEnabled = true)
        val action = BasicSkkAction.Text("kanaNihon a")
        val before = engine.state
        pending { engine.dispatch(action) }.load()
        assertEquals(before, engine.state)
        val result = engine.dispatch(action)
        assertEquals("かな日本あ", result.commit)
        assertEquals(1, result.effects.size)
    }

    @Test fun `数値の内側検索も延期し外側候補を失わず再試行する`() {
        val dictionary = ColdDictionary(mapOf("#" to listOf(DictionaryCandidate("#4円")),
            "12" to listOf(DictionaryCandidate("十二"))))
        val engine = BasicSkkEngine(NumericSkkDictionary(dictionary))
        engine.dispatch(BasicSkkAction.StartAbbrev)
        engine.dispatch(BasicSkkAction.Text("12", false))
        val before = engine.state
        pending { engine.dispatch(BasicSkkAction.ConvertNext) }.load()
        assertEquals(before, engine.state)
        pending { engine.dispatch(BasicSkkAction.ConvertNext) }.load()
        assertEquals(before, engine.state)
        assertEquals("十二円", engine.dispatch(BasicSkkAction.ConvertNext).view.candidate?.committedText)
    }

    @Test fun `動的補完の延期は入力自体も戻し再試行で同じ文字を重複しない`() {
        var ready = false
        val dictionary = object : BasicSkkDictionary {
            override fun lookup(query: DictionaryQuery) = emptyList<DictionaryCandidate>()
            override fun complete(query: CompletionQuery): List<String> {
                if (!ready) throw DeferredDictionaryReadException { ready = true }
                return listOf("かな")
            }
        }
        val engine = BasicSkkEngine(dictionary, RegistrationPolicy(), completionConfig = CompletionConfig(dynamicEnabled = true))
        val before = engine.state
        pending { engine.dispatch(BasicSkkAction.Text("Ka")) }.load()
        assertEquals(before, engine.state)
        val result = engine.dispatch(BasicSkkAction.Text("Ka"))
        assertEquals("か", result.view.composing)
        assertEquals(DynamicCompletionView("か", "な"), result.view.completion)
    }

    @Test fun `再帰登録の検索延期は親本文と深さを戻す`() {
        val dictionary = ColdDictionary(emptyMap())
        dictionary.ready += "みち"
        val engine = BasicSkkEngine(dictionary, RegistrationPolicy(enabled = true))
        engine.dispatch(BasicSkkAction.Text("Michi "))
        engine.dispatch(BasicSkkAction.Text("かなNihon"))
        val before = engine.state
        val view = engine.currentView
        pending { engine.dispatch(BasicSkkAction.ConvertNext) }.load()
        assertEquals(before, engine.state)
        assertEquals(view, engine.currentView)
        engine.dispatch(BasicSkkAction.ConvertNext)
        assertEquals(2, engine.state.registrationDepth)
    }
    @Test fun `数値登録本文の準備を延期しても保存要求と確定を重複しない`() {
        val dictionary = ColdDictionary(mapOf("12" to listOf(DictionaryCandidate("十二"))))
        dictionary.ready += "#"
        val engine = BasicSkkEngine(NumericSkkDictionary(dictionary), RegistrationPolicy(enabled = true))
        engine.dispatch(BasicSkkAction.StartAbbrev)
        engine.dispatch(BasicSkkAction.Text("12", false))
        engine.dispatch(BasicSkkAction.ConvertNext)
        engine.dispatch(BasicSkkAction.Text("#4", false))
        val before = engine.state
        pending { engine.dispatch(BasicSkkAction.Enter) }.load()
        assertEquals(before, engine.state)
        val request = (engine.dispatch(BasicSkkAction.Enter).effects.single() as BasicSkkEffect.SaveRegistration).request
        assertEquals(1L, request.token.operationId)
        assertEquals("十二", request.committedText)
        val completion = RegistrationSaveCompletion(request.token, RegistrationSaveOutcome.Applied)
        assertEquals("十二", engine.completeRegistration(completion).commit)
        assertFalse(engine.completeRegistration(completion).handled)
    }

    @Test fun `削除完了の再検索を延期しても保存済み削除を再要求しない`() {
        val candidate = DictionaryCandidate("日本", selection = se.haya.skk.core.dictionary.CandidateSelection(
            1, listOf(se.haya.skk.core.dictionary.SelectedCandidateOrigin("personal", 1, true,
                "にほん", "日本", null))))
        var defer = false
        var removed = false
        val engine = BasicSkkEngine(BasicSkkDictionary {
            if (defer) throw DeferredDictionaryReadException { defer = false; removed = true }
            if (removed) emptyList() else listOf(candidate)
        }, RegistrationPolicy(), deletionEnabled = true)
        engine.dispatch(BasicSkkAction.Text("Nihon X"))
        val request = (engine.dispatch(BasicSkkAction.Text("y")).effects.single() as BasicSkkEffect.DeleteCandidate).request
        val before = engine.currentView
        defer = true
        val completion = CandidateDeletionCompletion(request.token, CandidateDeletionOutcome.Applied)
        pending { engine.completeCandidateDeletion(completion) }.load()
        assertEquals(before, engine.currentView)
        val result = engine.completeCandidateDeletion(completion)
        assertNull(result.view.candidate)
        assertTrue(result.effects.isEmpty())
        assertFalse(engine.completeCandidateDeletion(completion).handled)
    }

    @Test fun `手動補完の延期は末尾の未確定ローマ字を保持する`() {
        var ready = false
        val engine = BasicSkkEngine(object : BasicSkkDictionary {
            override fun lookup(query: DictionaryQuery) = emptyList<DictionaryCandidate>()
            override fun complete(query: CompletionQuery): List<String> {
                if (!ready) throw DeferredDictionaryReadException { ready = true }
                return listOf("にほんご")
            }
        })
        engine.dispatch(BasicSkkAction.Text("Nihon"))
        val before = engine.state
        pending { engine.dispatch(BasicSkkAction.CompleteForward) }.load()
        assertEquals(before, engine.state)
        assertEquals("にほんご", engine.dispatch(BasicSkkAction.CompleteForward).view.composing)
    }

}
