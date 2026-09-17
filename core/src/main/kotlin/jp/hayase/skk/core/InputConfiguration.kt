package jp.hayase.skk.core

/** かな入力だけに適用する句読点・記号の不変設定です。 */
data class PunctuationConfig(
    val period: String = "。",
    val comma: String = "、",
    val fullwidthParentheses: Boolean = true,
    val fullwidthBrackets: Boolean = false,
    val fullwidthSymbols: Boolean = false,
) {
    init {
        require(period in listOf("。", "．", ".")) { "句点の設定が不正です" }
        require(comma in listOf("、", "，", ",")) { "読点の設定が不正です" }
    }

    fun render(character: Char): String = when (character) {
        '.' -> period
        ',' -> comma
        '(' -> if (fullwidthParentheses) "（" else "("
        ')' -> if (fullwidthParentheses) "）" else ")"
        '-' -> "ー"
        '[' -> "「"
        ']' -> "」"
        '{' -> if (fullwidthBrackets) "｛" else "{"
        '}' -> if (fullwidthBrackets) "｝" else "}"
        else -> if (fullwidthSymbols && character in '!'..'~' && !character.isLetterOrDigit())
            (character.code + 0xFEE0).toChar().toString() else character.toString()
    }
}

enum class CandidatePageMode { FIXED, AUTO }

/** ラベルは論理文字であり、OSの物理配列を再配置しません。 */
data class CandidateDisplayConfig(
    val labels: String = "asdfjkl",
    val pageMode: CandidatePageMode = CandidatePageMode.FIXED,
    val fixedPageSize: Int = 7,
    val inlineCandidateCount: Int = 2,
    val showCompositionMarkers: Boolean = false,
) {
    init {
        require(labels.length in 1..16 && labels.all { it.code in 0x21..0x7e } &&
            labels.toSet().size == labels.length && labels.none { it in "xX>" }) {
            "候補ラベルは重複や候補操作との競合がないASCII文字にします"
        }
        require(fixedPageSize in 1..labels.length) { "候補数はラベル数以内にします" }
        require(inlineCandidateCount in 0..9) { "単独表示する候補数は0から9件にします" }
    }

    /** 新しい候補選択の開始時にだけ計算し、表示中のラベル対応は変更しません。 */
    fun pageSize(availableWidthDp: Float, fontScale: Float): Int = when (pageMode) {
        CandidatePageMode.FIXED -> fixedPageSize
        CandidatePageMode.AUTO -> {
            if (!availableWidthDp.isFinite() || !fontScale.isFinite() || availableWidthDp <= 0 || fontScale <= 0) 1
            else (availableWidthDp / (96f * fontScale)).toInt().coerceIn(1, labels.length)
        }
    }
}
