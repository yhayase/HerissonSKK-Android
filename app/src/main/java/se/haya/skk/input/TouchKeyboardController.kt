package se.haya.skk.input

import se.haya.skk.core.BasicSkkAction

internal sealed interface TouchKeyboardCommand {
    data class Skk(val action: BasicSkkAction) : TouchKeyboardCommand
    data object SpaceOrConvert : TouchKeyboardCommand
    data object SwitchIme : TouchKeyboardCommand
}

/** 文字面・一回Shift・押しているShiftを、入力先の状態とは別に管理します。 */
internal class TouchKeyboardController {
    enum class Page { LETTERS, SYMBOLS_1, SYMBOLS_2 }
    enum class Flick { TAP, UP, DOWN }

    private var latched = false
    private var held = false
    private var chordUsed = false
    val shifted: Boolean get() = latched || held
    var page: Page = Page.LETTERS
        private set

    fun reset() {
        cancelPointers()
        latched = false
        page = Page.LETTERS
    }

    fun toggleShift() { latched = !latched }

    fun pressShift() {
        held = true
        chordUsed = false
    }

    fun releaseShift(cancelled: Boolean = false) {
        if (!held) return
        held = false
        if (!cancelled && !chordUsed) toggleShift()
        chordUsed = false
    }

    /** 同時押しだけを保持します。一回Shiftは次の文字が実際に送られたときに消費します。 */
    fun captureShift(): Boolean {
        if (held) chordUsed = true
        return held
    }

    fun cancelPointers() {
        held = false
        chordUsed = false
    }

    fun togglePage() {
        page = if (page == Page.LETTERS) Page.SYMBOLS_1 else Page.LETTERS
        latched = false
        cancelPointers()
    }

    fun toggleSymbolPage() {
        page = when (page) {
            Page.SYMBOLS_1 -> Page.SYMBOLS_2
            Page.SYMBOLS_2 -> Page.SYMBOLS_1
            Page.LETTERS -> Page.LETTERS
        }
        cancelPointers()
    }

    fun text(value: String, flick: Flick = Flick.TAP, shiftAtPress: Boolean = captureShift()): TouchKeyboardCommand.Skk {
        val output = previewText(value, flick, shiftAtPress)
        latched = false
        return TouchKeyboardCommand.Skk(BasicSkkAction.Text(output))
    }

    /** 表示用に、現在のShift状態で確定する文字を副作用なしに求めます。 */
    fun previewText(value: String, flick: Flick, shiftAtPress: Boolean): String = when (flick) {
            Flick.UP -> up(value) ?: value
            Flick.DOWN -> down(value) ?: value
            Flick.TAP -> if ((latched || shiftAtPress) && page == Page.LETTERS) value.uppercase() else value
        }

    fun up(value: String): String? = when {
        page == Page.LETTERS && value.length == 1 && value[0] in 'a'..'z' -> value.uppercase()
        value == "," -> "!"
        value == "." -> "?"
        else -> null
    }

    fun down(value: String): String? = if (page == Page.LETTERS) DOWN[value] else null

    companion object {
        val LETTER_ROWS = listOf("qwertyuiop", "asdfghjkl", "zxcvbnm")
        val SYMBOL_ROWS_1 = listOf(
            listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "0"),
            listOf("@", "#", "$", "%", "&", "-", "+", "(", ")", "/"),
            listOf("*", "\"", "'", ":", ";", "!", "?"),
        )
        val SYMBOL_ROWS_2 = listOf(
            listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "0"),
            listOf("[", "]", "{", "}", "<", ">", "_", "=", "\\", "|"),
            listOf("`", "~", "^", "¥", "$", "%", "&"),
        )
        private val DOWN = ("qwertyuiopasdfghjklzxcvbnm".map(Char::toString)).zip(
            listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "0",
                "@", "#", "$", "%", "&", "(", ")", "[", "]", "_", "\"", "'", "<", ">", ":", "/"),
        ).toMap()
    }
}
