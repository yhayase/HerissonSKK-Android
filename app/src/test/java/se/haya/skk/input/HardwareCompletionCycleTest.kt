package se.haya.skk.input

import android.view.KeyCharacterMap
import android.view.KeyEvent
import se.haya.skk.core.*
import se.haya.skk.core.keys.KeyBindings
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 35])
class HardwareCompletionCycleTest {
    @Test fun `Tabの後だけ句読点で補完を往復し端では候補を保持する`() {
        for (abbrev in listOf(false, true)) {
            var searches = 0
            val values = if (abbrev) listOf("android", "androidx") else listOf("にほん", "にほんご")
            val engine = BasicSkkEngine(object : BasicSkkDictionary {
                override fun lookup(query: DictionaryQuery) = emptyList<DictionaryCandidate>()
                override fun complete(query: CompletionQuery): List<String> {
                    searches++
                    return values
                }
            })
            val mapper = HardwareKeyMapper()
            fun press(code: Int) {
                val event = KeyEvent(0, 0, KeyEvent.ACTION_DOWN, code, 0, 0,
                    KeyCharacterMap.VIRTUAL_KEYBOARD, 0)
                val decoded = mapper.decodeConfigured(event, engine.state, engine.currentView, KeyBindings(), false)
                assertTrue(decoded is HardwareKeyMapper.Decoded.Action)
                engine.dispatch((decoded as HardwareKeyMapper.Decoded.Action).action)
            }
            engine.dispatch(BasicSkkAction.Text(if (abbrev) "/an" else "Ni"))
            press(KeyEvent.KEYCODE_TAB)
            assertEquals(values[0], engine.state.reading)
            press(KeyEvent.KEYCODE_PERIOD)
            assertEquals(values[1], engine.state.reading)
            press(KeyEvent.KEYCODE_PERIOD)
            assertEquals(values[1], engine.state.reading)
            press(KeyEvent.KEYCODE_COMMA)
            assertEquals(values[0], engine.state.reading)
            press(KeyEvent.KEYCODE_COMMA)
            assertEquals(values[0], engine.state.reading)
            assertEquals(1, searches)
            press(KeyEvent.KEYCODE_ESCAPE)
            assertEquals(if (abbrev) "an" else "に", engine.state.reading)
            assertFalse(engine.state.completionCycling)
            if (abbrev) {
                press(KeyEvent.KEYCODE_PERIOD)
                assertEquals("an.", engine.state.reading)
            } else {
                engine.dispatch(BasicSkkAction.Kana)
                val result = mapper.decodeConfigured(KeyEvent(0, 0, KeyEvent.ACTION_DOWN,
                    KeyEvent.KEYCODE_PERIOD, 0), engine.state, engine.currentView, KeyBindings(), false)
                assertEquals(HardwareKeyMapper.Decoded.Action(BasicSkkAction.Text(".", false)), result)
            }
        }
    }
}
