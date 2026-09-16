package jp.hayase.skk.core

import jp.hayase.skk.core.dictionary.CandidateSelection
import jp.hayase.skk.core.dictionary.SelectedCandidateOrigin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CandidateDeletionTest {
    private fun engine(
        entries: MutableMap<String, List<DictionaryCandidate>>,
        savingAllowed: Boolean = true,
        registration: Boolean = false,
    ) = BasicSkkEngine(
        dictionary = BasicSkkDictionary { entries[it.readingKey].orEmpty() },
        registrationPolicy = RegistrationPolicy(
            enabled = registration,
            sessionGeneration = 41,
            savingAllowed = savingAllowed,
        ),
        deletionEnabled = true,
    )

    @Test fun `Xは候補を確定せず全由来の確認を表示しnで同じ候補へ戻る`() {
        val candidate = candidate("日本", listOf(origin("personal", true), origin("system", false)))
        val engine = engine(mutableMapOf("にほん" to listOf(candidate)))
        engine.dispatch(BasicSkkAction.Text("Nihon "))

        val confirming = engine.dispatch(BasicSkkAction.Text("X"))

        assertNull(confirming.commit)
        assertEquals("日本", confirming.view.candidate?.committedText)
        assertEquals(2, confirming.view.deletion?.originCount)
        assertEquals(1, confirming.view.deletion?.personalOriginCount)
        assertEquals(1, confirming.view.deletion?.systemOriginCount)
        listOf(BasicSkkAction.Enter, BasicSkkAction.Kana, BasicSkkAction.Backspace).forEach {
            val unchanged = engine.dispatch(it)
            assertEquals("日本", unchanged.view.candidate?.committedText)
            assertTrue(unchanged.effects.isEmpty())
        }
        val returned = engine.dispatch(BasicSkkAction.Text("n"))
        assertNull(returned.view.deletion)
        assertEquals("日本", returned.view.candidate?.committedText)
    }

    @Test fun `yは一要求だけを発行し重複完了と別tokenを無視する`() {
        val entries = mutableMapOf("にほん" to listOf(candidate("日本")))
        val engine = engine(entries)
        engine.dispatch(BasicSkkAction.Text("Nihon X"))
        val request = engine.dispatch(BasicSkkAction.Text("y")).deletionRequest()
        assertEquals(41, request.token.sessionGeneration)
        assertEquals("にほん", request.query.readingKey)
        assertEquals("日本", request.displayedText)
        assertTrue(engine.dispatch(BasicSkkAction.Text("y")).effects.isEmpty())

        val stale = request.copy(token = request.token.copy(operationId = request.token.operationId + 1))
        assertFalse(engine.completeCandidateDeletion(
            CandidateDeletionCompletion(stale.token, CandidateDeletionOutcome.Applied),
        ).handled)
        entries["にほん"] = emptyList()
        val applied = engine.completeCandidateDeletion(
            CandidateDeletionCompletion(request.token, CandidateDeletionOutcome.Applied),
        )
        assertNull(applied.commit)
        assertNull(applied.view.candidate)
        assertEquals("にほn", applied.view.composing)
        assertEquals("n", engine.state.pendingRomaji)
        assertFalse(engine.completeCandidateDeletion(
            CandidateDeletionCompletion(request.token, CandidateDeletionOutcome.Applied),
        ).handled)
    }

    @Test fun `保存待ち取消とresetは遅い完了を破棄し読みへ戻す`() {
        val entries = mutableMapOf("にほん" to listOf(candidate("日本")))
        val engine = engine(entries)
        engine.dispatch(BasicSkkAction.Text("Nihon X"))
        val canceledRequest = engine.dispatch(BasicSkkAction.Text("y")).deletionRequest()
        val canceled = engine.dispatch(BasicSkkAction.Cancel)
        assertNull(canceled.view.candidate)
        assertEquals("にほn", canceled.view.composing)
        assertEquals("n", engine.state.pendingRomaji)
        assertTrue(canceled.notice!!.contains("可能性"))
        assertFalse(engine.completeCandidateDeletion(
            CandidateDeletionCompletion(canceledRequest.token, CandidateDeletionOutcome.Applied),
        ).handled)

        engine.dispatch(BasicSkkAction.Text(" X"))
        val resetRequest = engine.dispatch(BasicSkkAction.Text("y")).deletionRequest()
        assertNull(engine.resetComposition().deletion)
        assertFalse(engine.completeCandidateDeletion(
            CandidateDeletionCompletion(resetRequest.token, CandidateDeletionOutcome.Applied),
        ).handled)
    }

    @Test fun `失敗は候補を保ち競合だけ最新候補へ更新して再確認を要求する`() {
        val entries = mutableMapOf("にほん" to listOf(candidate("日本")))
        val engine = engine(entries)
        engine.dispatch(BasicSkkAction.Text("Nihon X"))
        val capacity = engine.dispatch(BasicSkkAction.Text("y")).deletionRequest()
        val failed = engine.completeCandidateDeletion(CandidateDeletionCompletion(
            capacity.token, CandidateDeletionOutcome.Failed(CandidateDeletionFailure.CAPACITY),
        ))
        assertEquals("日本", failed.view.candidate?.committedText)
        assertNull(failed.view.deletion)
        assertTrue(failed.notice!!.contains("容量"))

        val batched = engine.dispatch(BasicSkkAction.Text("Xy"))
        // Xy は確認文字を入力先へ漏らさず、同じバッチ内で削除要求を一度だけ作ります。
        assertEquals(true, engine.currentView.deletion?.saving)
        val conflict = batched.deletionRequest()
        entries["にほん"] = listOf(candidate("二本", dictionaryId = "new", generation = 2))
        val refreshed = engine.completeCandidateDeletion(CandidateDeletionCompletion(
            conflict.token, CandidateDeletionOutcome.Failed(CandidateDeletionFailure.CONFLICT),
        ))
        assertEquals("二本", refreshed.view.candidate?.committedText)
        assertNull(refreshed.view.deletion)
        assertTrue(refreshed.notice!!.contains("もう一度"))
    }

    @Test fun `学習禁止では確認へ入らず候補を保持する`() {
        val engine = engine(mutableMapOf("にほん" to listOf(candidate("日本"))), savingAllowed = false)
        engine.dispatch(BasicSkkAction.Text("Nihon "))
        val denied = engine.dispatch(BasicSkkAction.Text("X"))
        assertNull(denied.view.deletion)
        assertEquals("日本", denied.view.candidate?.committedText)
        assertTrue(denied.effects.isEmpty())
        assertTrue(denied.notice!!.contains("削除できません"))
    }

    @Test fun `保存済み未反映は同じ数値由来の表示変種を除き登録へ自動遷移しない`() {
        val numericOrigin = origin("system", false, entryKey = "だい#", text = "第#4")
        val entries = mutableMapOf("だい12" to listOf(
            candidate("第十二", listOf(numericOrigin), numeric = true),
            candidate("第拾弐", listOf(numericOrigin), numeric = true),
        ))
        val engine = engine(entries, registration = true)
        engine.dispatch(BasicSkkAction.Text("Dai12 X"))
        val request = engine.dispatch(BasicSkkAction.Text("y")).deletionRequest()
        val completed = engine.completeCandidateDeletion(CandidateDeletionCompletion(
            request.token, CandidateDeletionOutcome.SavedButNotApplied,
        ))
        assertNull(completed.view.candidate)
        assertEquals("だい12", completed.view.composing)
        assertEquals(0, engine.state.registrationDepth)
        assertNull(completed.commit)
        assertTrue(completed.notice!!.contains("再読込"))
    }

    @Test fun `登録中の内側削除は親本文とcursorと入力先compositionを変えない`() {
        val entries = mutableMapOf("こ" to listOf(candidate("子")))
        val engine = engine(entries, registration = true)
        engine.dispatch(BasicSkkAction.Text("Michi oyaKo X"))
        val before = engine.currentView.registration
        val request = engine.dispatch(BasicSkkAction.Text("y")).deletionRequest()
        entries["こ"] = emptyList()
        val completed = engine.completeCandidateDeletion(
            CandidateDeletionCompletion(request.token, CandidateDeletionOutcome.Applied),
        )
        assertEquals(before?.body, completed.view.registration?.body)
        assertEquals(before?.cursor, completed.view.registration?.cursor)
        assertEquals("みち", completed.view.composing)
        assertEquals("こ", completed.view.registration?.innerComposing)
        assertNull(completed.commit)
        assertEquals(1, engine.state.registrationDepth)
    }

    private fun BasicSkkResult.deletionRequest(): CandidateDeletionRequest =
        (effects.single() as BasicSkkEffect.DeleteCandidate).request

    private fun candidate(
        text: String,
        origins: List<SelectedCandidateOrigin> = listOf(origin("personal", true)),
        numeric: Boolean = false,
        dictionaryId: String? = null,
        generation: Long = 1,
    ): DictionaryCandidate {
        val actual = dictionaryId?.let { listOf(origin(it, false, generation = generation)) } ?: origins
        return DictionaryCandidate(text, selection = CandidateSelection(7, actual, numeric))
    }

    private fun origin(
        dictionaryId: String,
        personal: Boolean,
        entryKey: String = "にほん",
        text: String = "日本",
        generation: Long = 1,
    ) = SelectedCandidateOrigin(dictionaryId, generation, personal, entryKey, text, null)
}
