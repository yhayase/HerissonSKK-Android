package jp.hayase.skk.input

import android.view.KeyCharacterMap
import android.view.KeyEvent
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 35])
class HardwareKeyMapperTest {
    private fun key(code: Int, meta: Int = 0) = KeyEvent(0, 0, KeyEvent.ACTION_DOWN,
        code, 0, meta, KeyCharacterMap.VIRTUAL_KEYBOARD, 0)

    @Test fun ctrlJIsCommandRatherThanControlCharacter() {
        val decoded = HardwareKeyMapper().decode(key(KeyEvent.KEYCODE_J, KeyEvent.META_CTRL_ON), true, false)
        assertEquals(HardwareKeyMapper.Decoded.Action(InputAction.Kana), decoded)
    }

    @Test fun appShortcutAndAsciiTextPassThrough() {
        val mapper = HardwareKeyMapper()
        assertEquals(HardwareKeyMapper.Decoded.Pass,
            mapper.decode(key(KeyEvent.KEYCODE_A, KeyEvent.META_CTRL_ON), false, false))
        assertEquals(HardwareKeyMapper.Decoded.Pass,
            mapper.decode(key(KeyEvent.KEYCODE_A), true, false))
    }

    @Test fun shiftProducesUppercaseText() {
        assertEquals(HardwareKeyMapper.Decoded.Action(InputAction.Text("N")),
            HardwareKeyMapper().decode(key(KeyEvent.KEYCODE_N, KeyEvent.META_SHIFT_ON), false, false))
    }

    @Test fun escapeOnlyCancelsComposition() {
        val mapper = HardwareKeyMapper()
        assertEquals(HardwareKeyMapper.Decoded.Pass, mapper.decode(key(KeyEvent.KEYCODE_ESCAPE), false, false))
        assertEquals(HardwareKeyMapper.Decoded.Action(InputAction.Cancel),
            mapper.decode(key(KeyEvent.KEYCODE_ESCAPE), false, true))
    }

    private fun mapped(code: Int, meta: Int = 0, characters: (Int) -> Int) =
        object : KeyEvent(0, 0, ACTION_DOWN, code, 0, meta) {
            override fun getUnicodeChar(metaState: Int) = characters(metaState)
        }

    @Test fun logicalCharacterTakesPrecedenceOverUsKeyPosition() {
        val mapper = HardwareKeyMapper()
        assertEquals(HardwareKeyMapper.Decoded.Action(InputAction.Text("a")),
            mapper.decode(mapped(KeyEvent.KEYCODE_Q) { 'a'.code }, false, false))
        assertEquals(HardwareKeyMapper.Decoded.Action(InputAction.Kana),
            mapper.decode(mapped(KeyEvent.KEYCODE_Q, KeyEvent.META_CTRL_ON) { 'j'.code }, true, false))
    }

    @Test fun altGrTextIsNotDiscardedAsShortcut() {
        val event = mapped(KeyEvent.KEYCODE_Q, KeyEvent.META_ALT_ON or KeyEvent.META_ALT_RIGHT_ON) {
            if (it and KeyEvent.META_ALT_ON != 0) '@'.code else 'q'.code
        }
        assertEquals(HardwareKeyMapper.Decoded.Action(InputAction.Text("@")),
            HardwareKeyMapper().decode(event, false, false))
    }

    @Test fun deadKeyCombinesAndResetDoesNotLeakAccentIntoNextSession() {
        val mapper = HardwareKeyMapper()
        val accent = mapped(KeyEvent.KEYCODE_APOSTROPHE) { KeyCharacterMap.COMBINING_ACCENT or 0x00b4 }
        val e = mapped(KeyEvent.KEYCODE_E) { 'e'.code }
        assertEquals(HardwareKeyMapper.Decoded.Wait, mapper.decode(accent, false, false))
        assertEquals(HardwareKeyMapper.Decoded.Action(InputAction.Text("é")), mapper.decode(e, false, false))
        mapper.decode(accent, false, false)
        mapper.reset()
        assertEquals(HardwareKeyMapper.Decoded.Action(InputAction.Text("e")), mapper.decode(e, false, false))
    }
}
