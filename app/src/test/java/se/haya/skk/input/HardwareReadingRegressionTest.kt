package se.haya.skk.input

import android.view.KeyEvent
import se.haya.skk.core.*
import se.haya.skk.core.keys.KeyBindings
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 設定済みキー配送はコアの文字コマンド解釈を無効にするため、その経路も検査します。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 35])
class HardwareReadingRegressionTest {
    private fun type(engine: BasicSkkEngine, input: String): String {
        val mapper = HardwareKeyMapper()
        return buildString {
            for (character in input) {
                val event = object : KeyEvent(0, 0, ACTION_DOWN, KEYCODE_UNKNOWN, 0,
                    if (character.isUpperCase()) META_SHIFT_ON else 0) {
                    override fun getUnicodeChar(metaState: Int) = character.code
                }
                val decoded = mapper.decodeConfigured(event, engine.state, engine.currentView, KeyBindings(), false)
                assertTrue("キーが処理されません: $character", decoded is HardwareKeyMapper.Decoded.Action)
                append(engine.dispatch((decoded as HardwareKeyMapper.Decoded.Action).action).commit.orEmpty())
            }
        }
    }

    @Test fun `物理キーのlとLも読みの終端処理後に英数へ切り替える`() {
        for ((input, expected, mode) in listOf(
            Triple("Kanl", "かん", InputMode.DIRECT),
            Triple("Kabl", "か", InputMode.DIRECT),
            Triple("KanL", "かん", InputMode.FULLWIDTH),
            Triple("KabL", "か", InputMode.FULLWIDTH),
        )) {
            val engine = BasicSkkEngine { emptyList() }
            assertEquals(input, expected, type(engine, input))
            assertEquals(mode, engine.state.mode)
            assertEquals(InputPhase.IDLE, engine.state.phase)
            assertEquals("", engine.state.pendingRomaji)
        }
    }

    @Test fun `物理キーのTasSiは促音を語幹に残して送りあり検索する`() {
        val queries = mutableListOf<DictionaryQuery>()
        val engine = BasicSkkEngine { query ->
            queries += query
            listOf(DictionaryCandidate("達"))
        }
        type(engine, "TasSi")
        assertEquals(listOf(DictionaryQuery("たっs", "し")), queries)
        assertEquals("達し", engine.currentView.candidate?.committedText)
    }

    @Test fun `物理キーのxtuは小さいつを入力する`() {
        val engine = BasicSkkEngine { emptyList() }
        assertEquals("っ", type(engine, "xtu"))
    }
}
