package se.haya.skk.core

import se.haya.skk.core.romaji.Romanizer
import org.junit.Assert.*
import org.junit.Test

class StandardSpecialRomajiTest {
    private val symbols = mapOf("zh" to "←", "zj" to "↓", "zk" to "↑", "zl" to "→", "zL" to "⇒",
        "z " to "　", "z*" to "※", "z," to "‥", "z-" to "〜", "z." to "…",
        "z/" to "・", "z0" to "○", "z@" to "◎", "z[" to "『", "z]" to "』",
        "z{" to "〖", "z}" to "〗", "z(" to "（", "z)" to "）")

    @Test fun symbolsWorkInIdleAndReadingWithoutChangingModeOrStartingLookup() {
        for ((input, expected) in symbols) {
            assertEquals(input, expected, Romanizer().feed(input))
            for (interpret in listOf(true, false)) {
                var searches = 0
                val idle = BasicSkkEngine { searches++; emptyList() }
                val committed = input.mapNotNull { idle.dispatch(BasicSkkAction.Text(it.toString(), interpret)).commit }.joinToString("")
                assertEquals(input, expected, committed)
                assertEquals(InputMode.HIRAGANA, idle.state.mode)
                assertEquals(InputPhase.IDLE, idle.state.phase)
                val reading = BasicSkkEngine { searches++; emptyList() }
                reading.dispatch(BasicSkkAction.StartReading)
                input.forEach { reading.dispatch(BasicSkkAction.Text(it.toString(), interpret)) }
                assertEquals(input, expected, reading.currentView.composing)
                assertEquals(InputPhase.READING, reading.state.phase)
                assertNull(reading.state.okuriConsonant)
                assertEquals(0, searches)
            }
        }
    }

    @Test fun standardForeignSoundsSupportKanaModes() {
        for ((input, expected) in mapOf("fa" to "ふぁ", "fi" to "ふぃ", "fe" to "ふぇ", "fo" to "ふぉ")) {
            assertEquals(expected, Romanizer().feed(input))
            val katakana = BasicSkkEngine { emptyList() }
            katakana.dispatch(BasicSkkAction.ToggleKana)
            val actual = input.mapNotNull { katakana.dispatch(BasicSkkAction.Text(it.toString())).commit }.joinToString("")
            assertEquals(expected.replace('ふ', 'フ').map { if (it in 'ぁ'..'ゖ') (it.code + 0x60).toChar() else it }.joinToString(""), actual)
        }
    }

    @Test fun standalonePunctuationAndDirectInputKeepTheirOwnBehavior() {
        val engine = BasicSkkEngine { emptyList() }
        assertEquals("。", engine.dispatch(BasicSkkAction.Text(".")).commit)
        assertEquals("、", engine.dispatch(BasicSkkAction.Text(",")).commit)
        assertEquals("「", engine.dispatch(BasicSkkAction.Text("[")).commit)
        assertEquals("ー", engine.dispatch(BasicSkkAction.Text("-")).commit)
        engine.dispatch(BasicSkkAction.ToDirect)
        assertEquals("z.", engine.dispatch(BasicSkkAction.Text("z.")).commit)
    }
}
