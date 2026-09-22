package se.haya.skk.core

import se.haya.skk.core.dictionary.DictionaryUnavailableException
import se.haya.skk.core.dictionary.DictionaryUnavailableReason
import org.junit.Assert.*
import org.junit.Test

class DictionaryAvailabilityTest {
    @Test fun `辞書の準備中や読込失敗を未登録と混同せず読みと残余を保持する`() {
        for (reason in DictionaryUnavailableReason.entries) {
            var available = false
            val engine = BasicSkkEngine {
                if (!available) throw DictionaryUnavailableException(reason)
                listOf(DictionaryCandidate("日本"))
            }
            engine.dispatch(BasicSkkAction.Text("Nihon"))
            val before = engine.state
            val result = engine.dispatch(BasicSkkAction.Text(" "))
            assertEquals(before, engine.state)
            assertNull(result.commit)
            assertNull(result.view.candidate)
            assertEquals("にほn", result.view.composing)
            assertTrue(result.notice.orEmpty().contains("辞書"))
            assertFalse(result.notice.orEmpty().contains("単語登録"))
            available = true
            assertEquals("日本", engine.dispatch(BasicSkkAction.Text(" ")).view.candidate?.committedText)
            assertEquals("日本", engine.dispatch(BasicSkkAction.Enter).commit)
        }
    }

    @Test fun `辞書の準備中でもabbrevの途中編集位置を検索前へ戻す`() {
        val engine = BasicSkkEngine { throw DictionaryUnavailableException(DictionaryUnavailableReason.INITIALIZING) }
        engine.dispatch(BasicSkkAction.Text("/API"))
        engine.dispatch(BasicSkkAction.Left)
        val before = engine.state
        val view = engine.currentView
        assertNull(engine.dispatch(BasicSkkAction.Text(" ")).commit)
        assertEquals(before, engine.state)
        assertEquals(view, engine.currentView)
    }
}
