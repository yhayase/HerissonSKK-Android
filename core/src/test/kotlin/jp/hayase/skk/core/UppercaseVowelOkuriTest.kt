package jp.hayase.skk.core

import jp.hayase.skk.core.romaji.RomajiRule
import jp.hayase.skk.core.romaji.RomanRuleSet
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UppercaseVowelOkuriTest {
    private fun BasicSkkEngine.type(text: String) {
        text.forEach { dispatch(BasicSkkAction.Text(it.toString())) }
    }

    @Test fun `大文字の母音でも直前の子音から送りを開始する`() {
        val expected = DictionaryQuery("かs", "す")
        for (input in listOf("KaSu", "KasU")) {
            val lookups = mutableListOf<DictionaryQuery>()
            val engine = BasicSkkEngine { query ->
                lookups += query
                if (query == expected) listOf(DictionaryCandidate("貸")) else emptyList()
            }

            engine.type(input)

            assertEquals(input, listOf(expected), lookups)
            assertEquals(input, InputPhase.SELECTING, engine.state.phase)
            assertEquals(input, "か", engine.state.reading)
            assertEquals(input, 's', engine.state.okuriConsonant)
            assertEquals(input, "す", engine.state.okuri)
            assertEquals(input, "貸す", engine.currentView.candidate?.committedText)
            assertEquals(input, "貸す", engine.dispatch(BasicSkkAction.Enter).commit)
        }
    }

    @Test fun `複数子音と独自規則でも先頭子音を送りキーにする`() {
        val rules = RomanRuleSet.compile(listOf(RomajiRule("ka", "か"), RomajiRule("cra", "くら")))
        val expected = DictionaryQuery("かc", "くら")
        for (input in listOf("KaCra", "KacrA")) {
            val lookups = mutableListOf<DictionaryQuery>()
            val engine = BasicSkkEngine(BasicSkkDictionary { query ->
                lookups += query
                if (query == expected) listOf(DictionaryCandidate("蔵")) else emptyList()
            }, RegistrationPolicy(), romanRuleSet = rules)

            engine.type(input)

            assertEquals(input, listOf(expected), lookups)
            assertEquals(input, "か", engine.state.reading)
            assertEquals(input, 'c', engine.state.okuriConsonant)
            assertEquals(input, "くら", engine.state.okuri)
            assertEquals(input, "蔵くら", engine.currentView.candidate?.committedText)
        }
    }

    @Test fun `取消後の読みとかな変換も従来の送り入力と一致する`() {
        val states = listOf("KaSu", "KasU").map { input ->
            val engine = BasicSkkEngine { listOf(DictionaryCandidate("貸")) }
            engine.type(input)
            engine.dispatch(BasicSkkAction.Cancel)
            val state = engine.state
            val composing = engine.currentView.composing
            val committed = engine.dispatch(BasicSkkAction.Text("q")).commit
            Triple(state, composing, committed)
        }

        assertEquals(states[0], states[1])
        assertEquals(InputPhase.READING, states[1].first.phase)
        assertEquals("かす", states[1].second)
        assertEquals("カス", states[1].third)
    }

    @Test fun `読み開始と大文字を含む継続規則は送りにしない`() {
        val start = BasicSkkEngine { emptyList() }
        start.type("Kasu")
        assertEquals(InputPhase.READING, start.state.phase)
        assertEquals("かす", start.state.reading)
        assertNull(start.state.okuriConsonant)

        val symbol = BasicSkkEngine { emptyList() }
        symbol.type("KazL")
        assertEquals("か⇒", symbol.state.reading)
        assertNull(symbol.state.okuriConsonant)
    }
}
