package jp.hayase.skk.input

import android.view.KeyCharacterMap
import android.view.KeyEvent

class HardwareKeyMapper {
    sealed interface Decoded {
        data object Pass : Decoded
        data object Wait : Decoded
        data class Action(val action: InputAction) : Decoded
    }
    private var accent = 0
    fun reset() { accent = 0 }

    fun decode(event: KeyEvent, ascii: Boolean, composing: Boolean): Decoded {
        if (KeyEvent.isModifierKey(event.keyCode)) return Decoded.Pass
        val noCtrl = event.metaState and KeyEvent.META_CTRL_MASK.inv()
        val unicode = event.getUnicodeChar(noCtrl)
        val plain = event.getUnicodeChar(noCtrl and KeyEvent.META_ALT_MASK.inv())
        val altText = event.isAltPressed && unicode != 0 &&
            (event.metaState and KeyEvent.META_ALT_RIGHT_ON != 0 || unicode != plain)
        if (event.isMetaPressed) return Decoded.Pass
        if (event.isCtrlPressed && !altText) {
            if (event.isAltPressed) return Decoded.Pass
            return when (plain.toChar().lowercaseChar()) {
                'j' -> { reset(); Decoded.Action(InputAction.Kana) }
                'g' -> { reset(); Decoded.Action(InputAction.Cancel) }
                else -> Decoded.Pass
            }
        }
        if (ascii) return Decoded.Pass
        when (event.keyCode) {
            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                if (accent != 0) {
                    val text = String(Character.toChars(accent)); reset()
                    return Decoded.Action(InputAction.Text(text))
                }
                return Decoded.Action(InputAction.Enter)
            }
            KeyEvent.KEYCODE_DEL -> {
                if (accent != 0) { reset(); return Decoded.Wait }
                return Decoded.Action(InputAction.Backspace)
            }
            KeyEvent.KEYCODE_ESCAPE -> {
                if (accent != 0) { reset(); return Decoded.Wait }
                return if (composing) Decoded.Action(InputAction.Cancel) else Decoded.Pass
            }
        }
        if (event.isAltPressed && !altText) return Decoded.Pass
        if (unicode == 0) return Decoded.Pass
        if (unicode and KeyCharacterMap.COMBINING_ACCENT != 0) {
            val previous = accent
            accent = unicode and KeyCharacterMap.COMBINING_ACCENT_MASK
            return if (previous == 0) Decoded.Wait
                else Decoded.Action(InputAction.Text(String(Character.toChars(previous))))
        }
        if (Character.isISOControl(unicode)) return Decoded.Pass
        val text = if (accent != 0) {
            val combined = KeyCharacterMap.getDeadChar(accent, unicode)
            val result = if (combined != 0) String(Character.toChars(combined))
                else String(Character.toChars(accent)) + String(Character.toChars(unicode))
            reset()
            result
        } else String(Character.toChars(unicode))
        return Decoded.Action(InputAction.Text(text))
    }
}
