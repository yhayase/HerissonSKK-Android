package se.haya.skk.input

/** 接続経路の検証用です。完全なローマ字規則・辞書はフェーズ 2 で実装します。 */
class PilotEngine {
    enum class Mode { KANA, ASCII }
    var mode = Mode.KANA
        private set
    private var reading = ""
    private var roman = ""
    private var converting = false
    private var candidates = emptyList<String>()
    private var candidateIndex = -1
    val composing: String get() = candidates.getOrNull(candidateIndex) ?: (reading + roman)
    val hasComposition: Boolean get() = converting || composing.isNotEmpty()
    val candidateLabel: String get() = if (candidateIndex >= 0)
        "${candidateIndex + 1}/${candidates.size}  ${candidates[candidateIndex]}" else composing

    data class Result(val handled: Boolean, val commit: String? = null)

    fun reset(ascii: Boolean = false) {
        mode = if (ascii) Mode.ASCII else Mode.KANA
        clear()
    }

    fun clear() {
        reading = ""
        roman = ""
        converting = false
        candidates = emptyList()
        candidateIndex = -1
    }

    fun apply(action: InputAction): Result = when (action) {
        InputAction.Kana -> {
            val text = if (hasComposition) finalizedText() else null
            clear()
            mode = Mode.KANA
            Result(true, text)
        }
        InputAction.Cancel -> {
            if (candidateIndex >= 0) {
                candidates = emptyList()
                candidateIndex = -1
            } else clear()
            Result(mode == Mode.KANA)
        }
        InputAction.Enter -> if (hasComposition) {
            val text = finalizedText()
            clear()
            Result(true, text)
        } else Result(false)
        InputAction.Backspace -> when {
            candidateIndex >= 0 -> {
                candidateIndex--
                if (candidateIndex < 0) candidates = emptyList()
                Result(true)
            }
            roman.isNotEmpty() -> { roman = roman.dropLast(1); Result(true) }
            reading.isNotEmpty() -> {
                reading = reading.substring(0, reading.offsetByCodePoints(reading.length, -1))
                Result(true)
            }
            converting -> { clear(); Result(true) }
            else -> Result(false)
        }
        is InputAction.Text -> text(action.value)
    }

    private fun finalizedText(): String = candidates.getOrNull(candidateIndex)
        ?: (reading + if (roman == "n") "ん" else roman)

    private fun text(value: String): Result {
        if (mode == Mode.ASCII) return Result(false)
        if (value == " " && converting) {
            if (candidateIndex >= 0) candidateIndex = (candidateIndex + 1) % candidates.size
            else {
                reading += if (roman == "n") "ん" else roman
                roman = ""
                candidates = if (reading == "にほん") listOf("日本", "二本") else listOf(reading)
                candidateIndex = 0
            }
            return Result(true)
        }
        if (value == "x" && candidateIndex >= 0) return apply(InputAction.Backspace)
        val prefix = if (candidateIndex >= 0) finalizedText().also { clear() } else ""
        if (value == "l" && !hasComposition) {
            mode = Mode.ASCII
            return Result(true, prefix.ifEmpty { null })
        }
        if (value.length == 1 && value[0] in 'A'..'Z') converting = true
        val lower = value.lowercase()
        if (lower.length != 1 || lower[0] !in 'a'..'z') {
            if (converting) reading += value
            else {
                val text = finalizedText() + value
                clear()
                return Result(true, prefix + text)
            }
        } else {
            roman += lower
            while (roman.isNotEmpty()) {
                val kana = ROMAJI[roman]
                if (kana != null) { reading += kana; roman = ""; break }
                if (ROMAJI.keys.any { it.startsWith(roman) }) break
                if (roman.startsWith("n") && roman.length > 1 && roman[1] !in "aiueoy") {
                    reading += "ん"; roman = roman.drop(1)
                } else {
                    reading += roman.first(); roman = roman.drop(1)
                }
            }
        }
        if (!converting && reading.isNotEmpty()) {
            val text = reading
            reading = ""
            return Result(true, prefix + text)
        }
        return Result(true, prefix.ifEmpty { null })
    }

    private companion object {
        val ROMAJI = mapOf(
            "a" to "あ", "i" to "い", "u" to "う", "e" to "え", "o" to "お",
            "ka" to "か", "ki" to "き", "ku" to "く", "ke" to "け", "ko" to "こ",
            "na" to "な", "ni" to "に", "nu" to "ぬ", "ne" to "ね", "no" to "の",
            "ha" to "は", "hi" to "ひ", "hu" to "ふ", "he" to "へ", "ho" to "ほ",
            "nn" to "ん"
        )
    }
}

sealed interface InputAction {
    data class Text(val value: String) : InputAction
    data object Kana : InputAction
    data object Cancel : InputAction
    data object Enter : InputAction
    data object Backspace : InputAction
}
