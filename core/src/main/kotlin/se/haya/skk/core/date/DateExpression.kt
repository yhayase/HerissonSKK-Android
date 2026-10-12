package se.haya.skk.core.date

data class DateExpression(
    val dayOffset: Int = 0,
    val andTime: Boolean = false,
    val format: DateFormat? = null,
) {
    init {
        require(format == null || (format != DateFormat.DDSKK_ERA_FULLWIDTH && dayOffset == 0 && !andTime)) {
            "固定形式は現在日付のみで使用できます"
        }
    }

    fun canonicalSource(): String = if (format == DateFormat.GREGORIAN_JAPANESE) {
        GREGORIAN_SOURCE
    } else if (format == DateFormat.ISO) {
        ISO_SOURCE
    } else if (dayOffset == 0) {
        if (andTime) "(skk-current-date nil nil t)" else "(skk-current-date)"
    } else {
        "(skk-relative-date nil nil ${if (andTime) "t" else "nil"} :dd $dayOffset)"
    }

    companion object {
        const val MAX_SOURCE_LENGTH = 256
        const val MAX_DAY_OFFSET = 36600

        // 完全一致した二つの定型だけを識別します。コールバックの実行は行いません。
        private const val GREGORIAN_SOURCE = "(skk-current-date (lambda (date-information format gengo and-time) (skk-default-current-date date-information nil 0 nil 0 0 0 and-time)))"
        private const val ISO_SOURCE = "(skk-current-date (lambda (date-information format gengo and-time) (format-time-string \"%Y-%m-%d\")))"

        private const val SPACE = "[ \\t\\r\\n\\f]"
        private val current = Regex(
            "$SPACE*\\(${SPACE}*skk-current-date(?:$SPACE+nil(?:$SPACE+nil(?:$SPACE+(nil|t))?)?)?$SPACE*\\)$SPACE*",
        )
        private val relative = Regex(
            "$SPACE*\\(${SPACE}*skk-relative-date$SPACE+nil$SPACE+nil$SPACE+nil$SPACE+:dd$SPACE+([+-]?[0-9]+)$SPACE*\\)$SPACE*",
        )

        /** 実行可能な Lisp は評価せず、固定された日付式だけを受け入れます。 */
        fun parse(source: String): DateExpression? {
            if (source.length > MAX_SOURCE_LENGTH) return null
            when (source.trim()) {
                GREGORIAN_SOURCE -> return DateExpression(format = DateFormat.GREGORIAN_JAPANESE)
                ISO_SOURCE -> return DateExpression(format = DateFormat.ISO)
            }
            current.matchEntire(source)?.let {
                return DateExpression(andTime = it.groupValues[1] == "t")
            }
            val match = relative.matchEntire(source) ?: return null
            val offset = match.groupValues[1].toIntOrNull() ?: return null
            if (offset !in -MAX_DAY_OFFSET..MAX_DAY_OFFSET) return null
            return DateExpression(dayOffset = offset)
        }
    }
}
