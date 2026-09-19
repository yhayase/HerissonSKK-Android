package jp.hayase.skk.input

import android.view.KeyCharacterMap
import android.view.KeyEvent
import jp.hayase.skk.core.BasicSkkAction
import jp.hayase.skk.core.BasicSkkState
import jp.hayase.skk.core.BasicSkkView
import jp.hayase.skk.core.InputMode
import jp.hayase.skk.core.InputPhase
import jp.hayase.skk.core.keys.KeyBindings
import jp.hayase.skk.core.keys.KeyGesture
import jp.hayase.skk.core.keys.SpecialKey
import jp.hayase.skk.core.keys.SkkCommand
import jp.hayase.skk.core.editing.EditCommand

class HardwareKeyMapper {
    sealed interface Decoded {
        data object Pass : Decoded
        data object Wait : Decoded
        data object QuoteNext : Decoded
        data class Action(val action: BasicSkkAction) : Decoded
    }
    private var accent = 0
    fun reset() { accent = 0 }

    /** 待機中の取消・素通しだけを判定し、デッドキーの状態を変更しません。 */
    fun previewConfigured(event: KeyEvent, state: BasicSkkState, view: BasicSkkView,
        bindings: KeyBindings, emacsEnabled: Boolean,
        rules: jp.hayase.skk.core.romaji.RomanRuleSet): Decoded {
        val savedAccent = accent
        return try { decodeConfigured(event, state, view, bindings, emacsEnabled, rules) }
        finally { accent = savedAccent }
    }

    /** 文字生成後に意味操作を解決し、未割当文字に旧来のコマンド解釈を重ねません。 */
    fun decodeConfigured(event: KeyEvent, state: BasicSkkState, view: BasicSkkView,
        bindings: KeyBindings, emacsEnabled: Boolean,
        rules: jp.hayase.skk.core.romaji.RomanRuleSet = jp.hayase.skk.core.romaji.RomanRuleSet.standard): Decoded {
        if (KeyEvent.isModifierKey(event.keyCode)) return Decoded.Pass
        val noCtrl = event.metaState and KeyEvent.META_CTRL_MASK.inv()
        val unicode = event.getUnicodeChar(noCtrl)
        val plain = event.getUnicodeChar(noCtrl and KeyEvent.META_ALT_MASK.inv())
        val altText = event.isAltPressed && unicode != 0 &&
            (event.metaState and KeyEvent.META_ALT_RIGHT_ON != 0 || unicode != plain)
        val composing = view.composing != null || state.phase != InputPhase.IDLE || view.registration != null
        if (view.deletion != null || state.registrationSaving) {
            if (event.keyCode == KeyEvent.KEYCODE_BACK) return Decoded.Pass
            if (event.isMetaPressed) { reset(); return Decoded.Action(BasicSkkAction.Enter) }
            if (event.keyCode == KeyEvent.KEYCODE_ESCAPE && !event.isCtrlPressed && !event.isAltPressed) {
                reset(); return Decoded.Action(BasicSkkAction.Cancel)
            }
            val special = specialKey(event.keyCode)
            val scalar = if (special == null && unicode != 0 &&
                unicode and KeyCharacterMap.COMBINING_ACCENT == 0 && !Character.isISOControl(unicode))
                String(Character.toChars(unicode)) else null
            if (special != null || scalar != null) {
                val gesture = KeyGesture(scalar, special, event.isCtrlPressed && !altText,
                    event.isAltPressed && !altText, event.isShiftPressed)
                if (bindings.resolve(gesture, state, view, emacsEnabled) == BasicSkkAction.Cancel) {
                    reset(); return Decoded.Action(BasicSkkAction.Cancel)
                }
                if (scalar in listOf("y", "n") && (!event.isCtrlPressed && !event.isAltPressed || altText)) {
                    reset(); return Decoded.Action(BasicSkkAction.Text(checkNotNull(scalar), false))
                }
            }
            reset()
            return Decoded.Action(BasicSkkAction.Enter)
        }
        if (!event.isMetaPressed && !altText) {
            val special = specialKey(event.keyCode)
            val scalar = if (special == null && plain != 0 &&
                plain and KeyCharacterMap.COMBINING_ACCENT == 0 && !Character.isISOControl(plain))
                String(Character.toChars(plain)) else null
            if (special != null || scalar != null) {
                val gesture = KeyGesture(scalar, special, event.isCtrlPressed, event.isAltPressed,
                    event.isShiftPressed)
                if (bindings.resolveCommand(gesture, state, view, emacsEnabled) == SkkCommand.QUOTE_NEXT) {
                    reset(); return Decoded.QuoteNext
                }
            }
        }
        if (event.isMetaPressed) return Decoded.Pass
        if (!altText && (event.isCtrlPressed || event.isAltPressed)) {
            val special = specialKey(event.keyCode)
            val text = plain.takeIf { it != 0 && !Character.isISOControl(it) &&
                it and KeyCharacterMap.COMBINING_ACCENT == 0 }?.let { String(Character.toChars(it)) }
            if (text != null || special != null) {
                val action = bindings.resolve(KeyGesture(if (special == null) text else null, special, ctrl = event.isCtrlPressed,
                    alt = event.isAltPressed, shift = event.isShiftPressed), state, view, emacsEnabled)
                if (action != null) { reset(); return Decoded.Action(action) }
            }
            if (emacsEnabled && event.isCtrlPressed && !event.isAltPressed && !event.isShiftPressed) {
                val command = when (special) {
                    SpecialKey.HOME -> EditCommand.BUFFER_START
                    SpecialKey.END -> EditCommand.BUFFER_END
                    else -> null
                }
                if (command != null) { reset(); return Decoded.Action(BasicSkkAction.Edit(command)) }
            }
            return Decoded.Pass
        }
        val special = specialKey(event.keyCode)
        if (special != null && accent == 0 && !altText) {
            bindings.resolve(KeyGesture(special = special, shift = event.isShiftPressed), state, view, emacsEnabled)?.let {
                reset(); return Decoded.Action(it)
            }
            // 再割当済みの確定・補完キーを従来の固定操作として実行しません。
            if (special == SpecialKey.ENTER || special == SpecialKey.TAB) return Decoded.Pass
            if (event.isShiftPressed) return Decoded.Pass
            if (emacsEnabled && special in listOf(SpecialKey.PAGE_UP, SpecialKey.PAGE_DOWN)) {
                reset()
                return Decoded.Action(BasicSkkAction.Edit(
                    if (special == SpecialKey.PAGE_UP) EditCommand.PAGE_UP else EditCommand.PAGE_DOWN))
            }
        }
        if (state.mode == InputMode.DIRECT && !altText && accent == 0 && unicode != 0 &&
            unicode and KeyCharacterMap.COMBINING_ACCENT == 0 && !Character.isISOControl(unicode)) {
            bindings.resolve(KeyGesture(String(Character.toChars(unicode)), shift = event.isShiftPressed), state, view, emacsEnabled)?.let {
                return Decoded.Action(it)
            }
        }
        val decoded = decode(event, state.mode == InputMode.DIRECT && state.registrationDepth == 0, composing, false)
        if (decoded !is Decoded.Action || decoded.action !is BasicSkkAction.Text) return decoded
        val text = decoded.action.text
        if (view.deletion != null && text in listOf("y", "n")) {
            return Decoded.Action(BasicSkkAction.Text(text, interpretCommands = false))
        }
        if (rules.continues(state.pendingRomaji, text)) {
            return Decoded.Action(BasicSkkAction.Text(text, interpretCommands = false))
        }
        if (!altText && unicode and KeyCharacterMap.COMBINING_ACCENT == 0 && text.codePointCount(0, text.length) == 1) {
            bindings.resolve(KeyGesture(text, shift = event.isShiftPressed), state, view, emacsEnabled)?.let {
                return Decoded.Action(it)
            }
        }
        return Decoded.Action(BasicSkkAction.Text(text, interpretCommands = false))
    }

    private fun specialKey(code: Int): SpecialKey? = when (code) {
            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> SpecialKey.ENTER
            KeyEvent.KEYCODE_TAB -> SpecialKey.TAB
            KeyEvent.KEYCODE_ESCAPE -> SpecialKey.ESCAPE
            KeyEvent.KEYCODE_DPAD_LEFT -> SpecialKey.LEFT
            KeyEvent.KEYCODE_DPAD_RIGHT -> SpecialKey.RIGHT
            KeyEvent.KEYCODE_MOVE_HOME -> SpecialKey.HOME
            KeyEvent.KEYCODE_MOVE_END -> SpecialKey.END
            KeyEvent.KEYCODE_PAGE_UP -> SpecialKey.PAGE_UP
            KeyEvent.KEYCODE_PAGE_DOWN -> SpecialKey.PAGE_DOWN
            KeyEvent.KEYCODE_DEL -> SpecialKey.BACKSPACE
            KeyEvent.KEYCODE_FORWARD_DEL -> SpecialKey.DELETE
            else -> null
        }

    fun decode(event: KeyEvent, ascii: Boolean, composing: Boolean,
        dynamicCompletionAvailable: Boolean = false): Decoded {
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
                'j' -> { reset(); Decoded.Action(BasicSkkAction.Kana) }
                'g' -> { reset(); Decoded.Action(BasicSkkAction.Cancel) }
                else -> Decoded.Pass
            }
        }
        if (ascii) return Decoded.Pass
        when (event.keyCode) {
            KeyEvent.KEYCODE_TAB -> return if (composing && !event.isAltPressed) {
                reset()
                Decoded.Action(if (event.isShiftPressed) BasicSkkAction.CompleteBackward
                    else BasicSkkAction.CompleteForward)
            } else Decoded.Pass
            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                if (accent != 0) {
                    val text = String(Character.toChars(accent)); reset()
                    return Decoded.Action(BasicSkkAction.Text(text))
                }
                return Decoded.Action(BasicSkkAction.Enter)
            }
            KeyEvent.KEYCODE_DEL -> {
                if (accent != 0) { reset(); return Decoded.Wait }
                return Decoded.Action(BasicSkkAction.Backspace)
            }
            KeyEvent.KEYCODE_ESCAPE -> {
                if (accent != 0) { reset(); return Decoded.Wait }
                return if (composing) Decoded.Action(BasicSkkAction.Cancel) else Decoded.Pass
            }
            KeyEvent.KEYCODE_DPAD_LEFT -> return if (composing) Decoded.Action(BasicSkkAction.Left) else Decoded.Pass
            KeyEvent.KEYCODE_DPAD_RIGHT -> return if (composing) Decoded.Action(
                if (dynamicCompletionAvailable && !event.isAltPressed && !event.isShiftPressed)
                    BasicSkkAction.AcceptDynamicCompletion else BasicSkkAction.Right) else Decoded.Pass
            KeyEvent.KEYCODE_MOVE_HOME -> return if (composing) Decoded.Action(BasicSkkAction.Home) else Decoded.Pass
            KeyEvent.KEYCODE_MOVE_END -> return if (composing) Decoded.Action(BasicSkkAction.End) else Decoded.Pass
            KeyEvent.KEYCODE_FORWARD_DEL -> return if (composing) Decoded.Action(BasicSkkAction.Delete) else Decoded.Pass
        }
        if (event.isAltPressed && !altText) return Decoded.Pass
        if (unicode == 0) return Decoded.Pass
        if (unicode and KeyCharacterMap.COMBINING_ACCENT != 0) {
            val previous = accent
            accent = unicode and KeyCharacterMap.COMBINING_ACCENT_MASK
            return if (previous == 0) Decoded.Wait
                else Decoded.Action(BasicSkkAction.Text(String(Character.toChars(previous))))
        }
        if (Character.isISOControl(unicode)) return Decoded.Pass
        val text = if (accent != 0) {
            val combined = KeyCharacterMap.getDeadChar(accent, unicode)
            val result = if (combined != 0) String(Character.toChars(combined))
                else String(Character.toChars(accent)) + String(Character.toChars(unicode))
            reset()
            result
        } else String(Character.toChars(unicode))
        return Decoded.Action(BasicSkkAction.Text(text))
    }
}
