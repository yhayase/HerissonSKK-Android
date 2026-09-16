package jp.hayase.skk.core

import org.junit.Assert.*
import org.junit.Test

class CandidateLearningTest {
    private fun engine(allow: Boolean = true) = BasicSkkEngine(
        BasicSkkDictionary { query ->
            if (query.readingKey == "にほん") listOf(DictionaryCandidate("日本", "国名"), DictionaryCandidate("二本")) else emptyList()
        }, RegistrationPolicy(enabled = true, sessionGeneration = 19, savingAllowed = allow), learningEnabled = true,
    )

    @Test fun `候補確定と続く通常入力を合成しても学習対象は選択候補一つで固定する`() {
        val engine = engine()
        engine.dispatch(BasicSkkAction.Text("Nihon  "))
        val result = engine.dispatch(BasicSkkAction.Text("a"))
        assertEquals("二本あ", result.commit)
        val request = (result.effects.single() as BasicSkkEffect.LearnCandidate).request
        assertEquals("にほん", request.query.readingKey)
        assertEquals("二本", request.candidate.text)
        assertEquals(19L, request.sessionGeneration)
        assertTrue(engine.dispatch(BasicSkkAction.Enter).effects.isEmpty())
    }

    @Test fun `学習禁止と登録内の候補確定では通常学習を要求しない`() {
        val forbidden = engine(allow = false)
        forbidden.dispatch(BasicSkkAction.Text("Nihon "))
        assertTrue(forbidden.dispatch(BasicSkkAction.Enter).effects.isEmpty())
        val nested = engine()
        nested.dispatch(BasicSkkAction.Text("Michi Nihon "))
        val result = nested.dispatch(BasicSkkAction.Enter)
        assertNull(result.commit)
        assertEquals("日本", result.view.registration?.body)
        assertTrue(result.effects.isEmpty())
    }

    @Test fun `連続確定の要求識別子と注釈を確定時点の値で保持する`() {
        val engine = engine()
        engine.dispatch(BasicSkkAction.Text("Nihon "))
        val first = (engine.dispatch(BasicSkkAction.Enter).effects.single() as BasicSkkEffect.LearnCandidate).request
        engine.dispatch(BasicSkkAction.Text("Nihon  "))
        val second = (engine.dispatch(BasicSkkAction.Enter).effects.single() as BasicSkkEffect.LearnCandidate).request
        assertNotEquals(first.operationId, second.operationId)
        assertEquals("日本", first.candidate.text)
        assertEquals("国名", first.candidate.annotation)
        assertEquals("二本", second.candidate.text)
    }
}
