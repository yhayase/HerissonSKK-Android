package se.haya.skk.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import se.haya.skk.core.romaji.RomajiRule
import se.haya.skk.core.romaji.RomanRuleSet

class UppercaseBoundaryTest {
    private fun BasicSkkEngine.type(text: String): String =
        text.mapNotNull { dispatch(BasicSkkAction.Text(it.toString())).commit }.joinToString("")

    private fun uppercaseForms(text: String): List<String> =
        (1 until (1 shl text.length)).map { mask ->
            text.mapIndexed { index, character ->
                if (mask and (1 shl index) != 0) character.uppercaseChar() else character
            }.joinToString("")
        }

    @Test fun `ローマ字の途中の大文字から未完成の読みを引き継ぐ`() {
        for (mode in listOf(InputMode.HIRAGANA, InputMode.KATAKANA, InputMode.HALFWIDTH)) {
            for ((roman, kana) in listOf("ka" to "か", "kya" to "きゃ", "sya" to "しゃ", "sha" to "しゃ", "cha" to "ちゃ")) {
                for (input in uppercaseForms(roman)) {
                    val engine = BasicSkkEngine { emptyList() }
                    when (mode) {
                        InputMode.KATAKANA -> engine.dispatch(BasicSkkAction.ToggleKana)
                        InputMode.HALFWIDTH -> engine.dispatch(BasicSkkAction.Halfwidth)
                        else -> Unit
                    }
                    assertEquals(input, "", engine.type(input))
                    assertEquals(input, InputPhase.READING, engine.state.phase)
                    assertEquals(input, kana, engine.state.reading)
                    assertEquals(input, mode, engine.state.mode)
                    assertEquals(input, "", engine.state.pendingRomaji)
                    assertNull(input, engine.state.okuriConsonant)
                    val expected = when (mode) {
                        InputMode.KATAKANA -> se.haya.skk.core.romaji.KanaTransforms.hiraganaToKatakana(kana)
                        InputMode.HALFWIDTH -> se.haya.skk.core.romaji.KanaTransforms.toHalfwidthKana(kana)
                        else -> kana
                    }
                    assertEquals(input, expected, engine.currentView.composing)
                }
            }
        }
    }

    @Test fun `大文字の前に確定した促音と読み開始後の促音を区別する`() {
        for ((input, expectedCommit, expectedReading) in listOf(
            Triple("sSu", "", "っす"), Triple("Ssu", "", "っす"), Triple("SSu", "", "っす"),
            Triple("ssA", "っ", "さ"), Triple("ssU", "っ", "す"),
            Triple("mMa", "", "んま"), Triple("mmA", "ん", "ま"),
        )) {
            val engine = BasicSkkEngine { emptyList() }
            assertEquals(input, expectedCommit, engine.type(input))
            assertEquals(input, expectedReading, engine.state.reading)
            assertEquals(input, InputPhase.READING, engine.state.phase)
            assertNull(input, engine.state.okuriConsonant)
        }
    }

    @Test fun `通常入力の保留nは大文字の前で確定し読み中のnyは継続する`() {
        for ((input, expectedCommit, expectedReading) in listOf(
            Triple("nA", "ん", "あ"), Triple("nYa", "ん", "や"), Triple("nNa", "ん", "な"),
            Triple("nKa", "ん", "か"), Triple("nyA", "", "にゃ"), Triple("NYa", "", "にゃ"),
            Triple("NyA", "", "にゃ"), Triple("NNa", "", "んあ"),
        )) {
            val engine = BasicSkkEngine { emptyList() }
            assertEquals(input, expectedCommit, engine.type(input))
            assertEquals(input, expectedReading, engine.state.reading)
            assertNull(input, engine.state.okuriConsonant)
        }
    }

    @Test fun `送りの拗音は大文字が子音の途中でも同じ検索キーになる`() {
        for ((input, query) in listOf(
            "KasYa" to DictionaryQuery("かs", "しゃ"), "KasyA" to DictionaryQuery("かs", "しゃ"),
            "KaSya" to DictionaryQuery("かs", "しゃ"), "AsYa" to DictionaryQuery("あs", "しゃ"),
            "AkYa" to DictionaryQuery("あk", "きゃ"), "AkyA" to DictionaryQuery("あk", "きゃ"),
            "KanYa" to DictionaryQuery("かんy", "や"), "KanyA" to DictionaryQuery("かn", "にゃ"),
            "KanA" to DictionaryQuery("かんa", "あ"), "NihonK " to DictionaryQuery("にほんk", "k"),
        )) {
            assertQuery(input, query)
        }
    }

    @Test fun `送り開始前の重子音は語幹に含め大文字母音からの送りを保持する`() {
        for ((input, query) in listOf(
            "KesSu" to DictionaryQuery("けっs", "す"), "KanNa" to DictionaryQuery("かっn", "な"),
            "KamMa" to DictionaryQuery("かっm", "ま"), "SsU" to DictionaryQuery("っs", "す"),
            "sSU" to DictionaryQuery("っs", "す"), "SSU" to DictionaryQuery("っs", "す"),
        )) {
            assertQuery(input, query)
        }
    }

    private fun assertQuery(input: String, expected: DictionaryQuery) {
        val lookups = mutableListOf<DictionaryQuery>()
        val engine = BasicSkkEngine { query ->
            lookups += query
            listOf(DictionaryCandidate("候補"))
        }
        assertEquals(input, "", engine.type(input))
        assertEquals(input, listOf(expected), lookups)
        assertEquals(input, InputPhase.SELECTING, engine.state.phase)
        assertEquals(input, "候補" + expected.okuri.orEmpty(), engine.currentView.candidate?.committedText)
    }

    @Test fun `nの後のqとlを残余にせずモード切替として扱う`() {
        for ((input, expectedCommit, expectedMode) in listOf(
            Triple("nq", "ん", InputMode.KATAKANA), Triple("nl", "ん", InputMode.DIRECT),
            Triple("nL", "ん", InputMode.FULLWIDTH), Triple("Kanq", "カン", InputMode.HIRAGANA),
            Triple("Kanl", "かん", InputMode.DIRECT), Triple("KanL", "かん", InputMode.FULLWIDTH),
        )) {
            val engine = BasicSkkEngine { emptyList() }
            assertEquals(input, expectedCommit, engine.type(input))
            assertEquals(input, expectedMode, engine.state.mode)
            assertEquals(input, InputPhase.IDLE, engine.state.phase)
            assertEquals(input, "", engine.state.pendingRomaji)
        }
    }

    @Test fun `既存の終端処理と大文字記号規則を保持する`() {
        for ((input, expectedCommit, expectedMode) in listOf(
            Triple("kq", "k", InputMode.KATAKANA), Triple("kl", "k", InputMode.DIRECT),
            Triple("kL", "k", InputMode.FULLWIDTH), Triple("zL", "⇒", InputMode.HIRAGANA),
        )) {
            val engine = BasicSkkEngine { emptyList() }
            assertEquals(input, expectedCommit, engine.type(input))
            assertEquals(input, expectedMode, engine.state.mode)
        }
        val reading = BasicSkkEngine { emptyList() }
        reading.type("KazL")
        assertEquals("か⇒", reading.state.reading)
        assertNull(reading.state.okuriConsonant)
    }

    @Test fun `独自規則の継続をモード切替より優先し未完成の大文字接続も保持する`() {
        val rules = RomanRuleSet.compile(listOf(
            RomajiRule("ka", "か"), RomajiRule("cra", "くら"),
            RomajiRule("nq", "★"), RomajiRule("nl", "☆"), RomajiRule("zL", "⇒"),
        ))
        fun engine() = BasicSkkEngine(BasicSkkDictionary { emptyList() }, RegistrationPolicy(), romanRuleSet = rules)
        for ((input, expected) in listOf("nq" to "★", "nl" to "☆", "zL" to "⇒")) {
            val engine = engine()
            assertEquals(input, expected, engine.type(input))
            assertEquals(input, InputMode.HIRAGANA, engine.state.mode)
            assertEquals(input, InputPhase.IDLE, engine.state.phase)
        }
        val reading = engine()
        assertEquals("", reading.type("crA"))
        assertEquals("くら", reading.state.reading)
        val okuri = engine()
        okuri.type("KacRa")
        assertEquals("か", okuri.state.reading)
        assertEquals('c', okuri.state.okuriConsonant)
        assertEquals("くら", okuri.state.okuri)
    }

    @Test fun `読み中のslashは状態を保って通知し引用入力なら文字を挿入する`() {
        val engine = BasicSkkEngine { emptyList() }
        engine.type("Kan")
        val before = engine.state
        val rejected = engine.dispatch(BasicSkkAction.Text("/"))
        assertEquals(before, engine.state)
        assertEquals("読み入力中は abbrev に切り替えられません", rejected.notice)
        assertTrue(rejected.handled)
        assertNull(rejected.commit)
        engine.dispatch(BasicSkkAction.Text("/", interpretCommands = false))
        assertEquals("かん/", engine.state.reading)
        val symbol = BasicSkkEngine { emptyList() }
        symbol.type("Kaz/")
        assertEquals("か・", symbol.state.reading)
    }
}
