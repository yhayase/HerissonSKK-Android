package jp.hayase.skk.core

import jp.hayase.skk.core.dictionary.CandidateSelection
import jp.hayase.skk.core.dictionary.SelectedCandidateOrigin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BasicSkkCompletionTest {
    private class RecordingDictionary : BasicSkkDictionary {
        var completions: List<String> = emptyList()
        var completionFailure: CompletionException? = null
        var obeyLimit = true
        val completionQueries = mutableListOf<CompletionQuery>()
        val lookupQueries = mutableListOf<DictionaryQuery>()
        var lookupCandidates: List<DictionaryCandidate> = emptyList()

        override fun lookup(query: DictionaryQuery): List<DictionaryCandidate> {
            lookupQueries += query
            return lookupCandidates
        }

        override fun complete(query: CompletionQuery): List<String> {
            completionQueries += query
            completionFailure?.let { throw it }
            return if (obeyLimit) completions.take(query.limit) else completions
        }
    }

    private fun engine(
        dictionary: RecordingDictionary,
        dynamic: Boolean = false,
        registration: Boolean = false,
        deletion: Boolean = false,
    ) = BasicSkkEngine(
        dictionary = dictionary,
        registrationPolicy = RegistrationPolicy(enabled = registration),
        completionConfig = CompletionConfig(dynamicEnabled = dynamic),
        deletionEnabled = deletion,
    )

    @Test fun `TABは固定した列を循環せず進退し取消で原文とcursorへ戻る`() {
        val dictionary = RecordingDictionary().apply { completions = listOf("にほん", "にほんご") }
        val engine = engine(dictionary)
        engine.dispatch(BasicSkkAction.Text("Ni"))

        val first = engine.dispatch(BasicSkkAction.CompleteForward)
        assertEquals("にほん", first.view.composing)
        assertNull(first.commit)
        assertTrue(first.effects.isEmpty())
        dictionary.completions = listOf("にほんじん")
        assertEquals("にほんご", engine.dispatch(BasicSkkAction.CompleteForward).view.composing)
        assertEquals("にほんご", engine.dispatch(BasicSkkAction.CompleteForward).view.composing)
        assertEquals("にほん", engine.dispatch(BasicSkkAction.CompleteBackward).view.composing)

        val canceled = engine.dispatch(BasicSkkAction.Cancel)
        assertEquals("に", canceled.view.composing)
        assertEquals(1, canceled.view.cursor)
        assertEquals(1, dictionary.completionQueries.size)
    }

    @Test fun `終端nは検索前snapshotから確定し候補なしと失敗で完全rollbackする`() {
        val dictionary = RecordingDictionary()
        val engine = engine(dictionary)
        engine.dispatch(BasicSkkAction.Text("Kan"))
        assertEquals("n", engine.state.pendingRomaji)

        val empty = engine.dispatch(BasicSkkAction.CompleteForward)
        assertEquals("かn", empty.view.composing)
        assertEquals("か", engine.state.reading)
        assertEquals("n", engine.state.pendingRomaji)
        assertEquals("かん", dictionary.completionQueries.single().prefix)

        dictionary.completionFailure = CompletionException(CompletionFailure.WORK_LIMIT)
        val failed = engine.dispatch(BasicSkkAction.CompleteForward)
        assertEquals("かn", failed.view.composing)
        assertEquals("n", engine.state.pendingRomaji)
        assertTrue(failed.notice!!.contains("保持"))
    }

    @Test fun `終端nの補完成功後も取消は確定前のpendingへ戻る`() {
        val dictionary = RecordingDictionary().apply { completions = listOf("かんじ") }
        val engine = engine(dictionary)
        engine.dispatch(BasicSkkAction.Text("Kan"))
        assertEquals("かんじ", engine.dispatch(BasicSkkAction.CompleteForward).view.composing)
        val canceled = engine.dispatch(BasicSkkAction.Cancel)
        assertEquals("かn", canceled.view.composing)
        assertEquals("n", engine.state.pendingRomaji)
    }

    @Test fun `通常操作は表示中の手動補完を受諾して一度だけ処理する`() {
        val dictionary = RecordingDictionary().apply {
            completions = listOf("にほん")
            lookupCandidates = listOf(DictionaryCandidate("日本"))
        }
        val engine = engine(dictionary)
        engine.dispatch(BasicSkkAction.Text("Ni"))
        engine.dispatch(BasicSkkAction.CompleteForward)
        val selected = engine.dispatch(BasicSkkAction.Text(" "))
        assertEquals(DictionaryQuery("にほん"), dictionary.lookupQueries.single())
        assertEquals("日本", selected.view.candidate?.committedText)
        assertNull(selected.commit)
    }

    @Test fun `動的補完は個人辞書だけを検索し表示とcompositionを分離する`() {
        val dictionary = RecordingDictionary().apply { completions = listOf("にほん") }
        val engine = engine(dictionary, dynamic = true)
        val proposed = engine.dispatch(BasicSkkAction.Text("Ni"))
        assertEquals("に", proposed.view.composing)
        assertEquals(DynamicCompletionView("に", "ほん"), proposed.view.completion)
        assertEquals(CompletionScope.PERSONAL_ONLY, dictionary.completionQueries.single().scope)
        assertEquals(1, dictionary.completionQueries.single().limit)
        assertTrue(proposed.effects.isEmpty())

        val accepted = engine.dispatch(BasicSkkAction.AcceptDynamicCompletion)
        assertEquals("にほん", accepted.view.composing)
        assertNull(accepted.view.completion)
        assertNull(accepted.commit)
        assertTrue(accepted.effects.isEmpty())
        assertEquals(1, dictionary.completionQueries.size)
    }

    @Test fun `動的提案中のSPCと文字編集は未受諾suffixを使わない`() {
        val dictionary = RecordingDictionary().apply {
            completions = listOf("にほん")
            lookupCandidates = listOf(DictionaryCandidate("二"))
        }
        val converting = engine(dictionary, dynamic = true)
        converting.dispatch(BasicSkkAction.Text("Ni"))
        converting.dispatch(BasicSkkAction.Text(" "))
        assertEquals(DictionaryQuery("に"), dictionary.lookupQueries.single())

        dictionary.lookupQueries.clear()
        val editing = engine(dictionary, dynamic = true)
        editing.dispatch(BasicSkkAction.Text("Ni"))
        val edited = editing.dispatch(BasicSkkAction.Text("k"))
        assertEquals("にk", edited.view.composing)
        assertNull(edited.view.completion)
        assertTrue(dictionary.lookupQueries.isEmpty())
    }

    @Test fun `動的提案中のTABは元の読みから全辞書の手動補完を開始する`() {
        val dictionary = RecordingDictionary().apply { completions = listOf("にほん") }
        val engine = engine(dictionary, dynamic = true)
        engine.dispatch(BasicSkkAction.Text("Ni"))
        val completed = engine.dispatch(BasicSkkAction.CompleteForward)
        assertEquals("にほん", completed.view.composing)
        assertNull(completed.view.completion)
        assertEquals(listOf(CompletionScope.PERSONAL_ONLY, CompletionScope.ALL),
            dictionary.completionQueries.map { it.scope })
    }

    @Test fun `途中cursorと送りと不完全pendingでは検索せず状態を保持する`() {
        val dictionary = RecordingDictionary().apply { completions = listOf("にほん") }
        val cursor = engine(dictionary, dynamic = true)
        cursor.dispatch(BasicSkkAction.Text("Nihon"))
        cursor.dispatch(BasicSkkAction.Left)
        val beforeMiddleTab = dictionary.completionQueries.size
        val middle = cursor.dispatch(BasicSkkAction.CompleteForward)
        assertEquals("にほん", middle.view.composing)
        assertNull(middle.view.completion)
        assertEquals(beforeMiddleTab, dictionary.completionQueries.size)

        dictionary.completionQueries.clear()
        val pending = engine(dictionary)
        pending.dispatch(BasicSkkAction.Text("K"))
        val incomplete = pending.dispatch(BasicSkkAction.CompleteForward)
        assertEquals("k", incomplete.view.composing)
        assertEquals("k", pending.state.pendingRomaji)
        assertTrue(dictionary.completionQueries.isEmpty())

        val okuri = engine(dictionary)
        okuri.dispatch(BasicSkkAction.Text("KaK"))
        val blocked = okuri.dispatch(BasicSkkAction.CompleteForward)
        assertEquals("かk", blocked.view.composing)
        assertEquals('k', okuri.state.okuriConsonant)
        assertTrue(dictionary.completionQueries.isEmpty())
    }

    @Test fun `64件を受理しresetと登録保存待ちでは補完状態を残さない`() {
        val dictionary = RecordingDictionary().apply {
            completions = List(CompletionQuery.MAX_RESULTS) { "に${it.toString().padStart(2, '0')}" }
        }
        val engine = engine(dictionary, dynamic = true, registration = true)
        engine.dispatch(BasicSkkAction.Text("Ni"))
        assertEquals("に00", engine.dispatch(BasicSkkAction.CompleteForward).view.composing)
        repeat(CompletionQuery.MAX_RESULTS - 1) { engine.dispatch(BasicSkkAction.CompleteForward) }
        assertEquals("に63", engine.currentView.composing)
        val clean = engine.resetComposition()
        assertNull(clean.completion)
        assertNull(clean.composing)

        dictionary.completions = emptyList()
        engine.dispatch(BasicSkkAction.Text("Michi tango"))
        val saving = engine.dispatch(BasicSkkAction.Enter)
        assertTrue(saving.effects.single() is BasicSkkEffect.SaveRegistration)
        val ignored = engine.dispatch(BasicSkkAction.CompleteForward)
        assertTrue(ignored.effects.isEmpty())
        assertTrue(ignored.view.registration!!.saving)
        assertNull(ignored.view.completion)
    }

    @Test fun `辞書ポートの件数文字列境界違反は部分適用せず原文を保つ`() {
        val invalidSets = listOf(
            List(CompletionQuery.MAX_RESULTS + 1) { "に$it" },
            listOf("ほか"),
            listOf("に" + "あ".repeat(CompletionQuery.MAX_RESULT_CHARS)),
            listOf("に\uD800"),
            List(17) { "に$it" + "あ".repeat(4_094) },
        )
        for (invalid in invalidSets) {
            val dictionary = RecordingDictionary().apply {
                completions = invalid
                obeyLimit = false
            }
            val engine = engine(dictionary)
            engine.dispatch(BasicSkkAction.Text("Ni"))
            val failed = engine.dispatch(BasicSkkAction.CompleteForward)
            assertEquals("に", failed.view.composing)
            assertEquals("に", engine.state.reading)
            assertTrue(failed.notice!!.contains("保持"))
        }
    }

    @Test fun `未受諾の動的suffixはEnterとかな化と半角化へ混入しない`() {
        fun proposedEngine() = RecordingDictionary().apply {
            completions = listOf("にほん")
        }.let { engine(it, dynamic = true) }.also {
            it.dispatch(BasicSkkAction.Text("Ni"))
            assertTrue(it.currentView.completion != null)
        }

        val entered = proposedEngine().dispatch(BasicSkkAction.Enter)
        assertEquals("に", entered.commit)
        assertNull(entered.view.completion)

        val kana = proposedEngine().dispatch(BasicSkkAction.Kana)
        assertEquals("に", kana.commit)
        assertNull(kana.view.completion)

        val halfwidth = proposedEngine().dispatch(BasicSkkAction.Halfwidth)
        assertEquals("ﾆ", halfwidth.commit)
        assertNull(halfwidth.view.completion)
    }

    @Test fun `カタカナ読みの補完は表示だけを変換し取消で元の読みへ戻る`() {
        val dictionary = RecordingDictionary().apply { completions = listOf("にほん") }
        val engine = engine(dictionary)
        engine.dispatch(BasicSkkAction.Text("qNi"))
        assertEquals("ニホン", engine.dispatch(BasicSkkAction.CompleteForward).view.composing)
        assertEquals("ニ", engine.dispatch(BasicSkkAction.Cancel).view.composing)
        assertEquals("に", dictionary.completionQueries.single().prefix)
    }

    @Test fun `不正な動的補完結果は表示へ出さない`() {
        val dictionary = RecordingDictionary().apply {
            completions = listOf("に\uD800")
            obeyLimit = false
        }
        val proposed = engine(dictionary, dynamic = true).dispatch(BasicSkkAction.Text("Ni"))
        assertEquals("に", proposed.view.composing)
        assertNull(proposed.view.completion)
    }

    @Test fun `再帰登録の動的補完は親本文と入力先compositionへ混入しない`() {
        val dictionary = RecordingDictionary().apply { completions = listOf("こども") }
        val engine = engine(dictionary, dynamic = true, registration = true)
        engine.dispatch(BasicSkkAction.Text("Michi Ko"))
        assertEquals("みち", engine.currentView.composing)
        assertEquals("", engine.currentView.registration?.body)
        assertEquals(DynamicCompletionView("こ", "ども"), engine.currentView.completion)

        val accepted = engine.dispatch(BasicSkkAction.AcceptDynamicCompletion)
        assertEquals("みち", accepted.view.composing)
        assertEquals("", accepted.view.registration?.body)
        assertEquals("こども", accepted.view.registration?.innerComposing)
        assertNull(accepted.commit)
        assertTrue(accepted.effects.isEmpty())
    }

    @Test fun `候補削除確認中は補完検索と受諾を行わない`() {
        val selection = CandidateSelection(1, listOf(
            SelectedCandidateOrigin("system", 1, false, "にほん", "日本", null),
        ))
        val dictionary = RecordingDictionary().apply {
            completions = listOf("にほんご")
            lookupCandidates = listOf(DictionaryCandidate("日本", selection = selection))
        }
        val engine = engine(dictionary, dynamic = true, deletion = true)
        engine.dispatch(BasicSkkAction.Text("Nihon X"))
        dictionary.completionQueries.clear()
        val blocked = engine.dispatch(BasicSkkAction.CompleteForward)
        assertTrue(blocked.view.deletion != null)
        assertTrue(dictionary.completionQueries.isEmpty())
        assertNull(blocked.view.completion)
    }
}
