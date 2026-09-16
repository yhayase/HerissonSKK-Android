package jp.hayase.skk.core.romaji

/** ローマ字列をかなへ変換する一つの規則です。 */
data class RomajiRule(
    val input: String,
    val output: String,
    val remaining: String = "",
    val terminalOutput: String? = null,
)

/**
 * 未消化のローマ字を所有する、Android に依存しない変換器です。
 *
 * 規則の出力は入力ごとに返し、[finish] は終端時だけの出力を適用します。
 */
class Romanizer(rules: Collection<RomajiRule> = standardRules) {
    private val table = RuleTable(rules)

    /** まだ出力へ変換していないローマ字です。 */
    var pending: String = ""
        private set

    /** [text] を追加して、直ちに確定したかなだけを返します。 */
    fun feed(text: String): String = buildString {
        text.forEach { character ->
            pending += character
            append(resolve())
        }
    }

    /** 終端規則を適用し、適用できない未消化文字はそのまま返します。 */
    fun finish(): String = buildString {
        append(resolve())
        while (pending.isNotEmpty()) {
            val exact = table.exact(pending)
            when {
                exact?.terminalOutput != null -> {
                    append(exact.terminalOutput)
                    pending = ""
                }
                exact != null -> {
                    append(exact.output)
                    pending = exact.remaining
                    append(resolve())
                }
                else -> {
                    append(pending)
                    pending = ""
                }
            }
        }
    }

    /** 未消化文字を一文字だけ削除します。 */
    fun backspacePending(): Boolean {
        if (pending.isEmpty()) return false
        pending = pending.dropLast(1)
        return true
    }

    /** 未消化文字を破棄します。 */
    fun reset() {
        pending = ""
    }

    private fun resolve(): String = buildString {
        var rewrites = 0
        while (pending.isNotEmpty()) {
            val exact = table.exact(pending)
            if (exact != null && exact.terminalOutput == null && !table.hasLongerPrefix(pending)) {
                append(exact.output)
                pending = exact.remaining
                check(++rewrites <= table.maxRewrites) { "ローマ字規則の残余処理が上限を超えました" }
                continue
            }
            if (table.hasPrefix(pending)) return@buildString

            val completedPrefix = table.longestCompletedPrefix(pending)
            if (completedPrefix != null) {
                append(completedPrefix.output)
                pending = completedPrefix.remaining + pending.drop(completedPrefix.input.length)
                check(++rewrites <= table.maxRewrites) { "ローマ字規則の残余処理が上限を超えました" }
                continue
            }
            append(pending.first())
            pending = pending.drop(1)
        }
    }

    private class RuleTable(rules: Collection<RomajiRule>) {
        private val byInput: Map<String, RomajiRule>
        private val inputs: Set<String>
        val maxRewrites: Int

        init {
            require(rules.isNotEmpty()) { "ローマ字規則は一件以上必要です" }
            rules.forEach(::validateRule)
            byInput = rules.associateBy { it.input }
            require(byInput.size == rules.size) { "ローマ字規則の入力は重複できません" }
            inputs = byInput.keys
            validateNoResidualCycle()
            maxRewrites = rules.size + 1
        }

        fun exact(value: String): RomajiRule? = byInput[value]
        fun hasPrefix(value: String): Boolean = inputs.any { it.startsWith(value) }
        fun hasLongerPrefix(value: String): Boolean = inputs.any { it.length > value.length && it.startsWith(value) }
        fun longestCompletedPrefix(value: String): RomajiRule? =
            (value.length - 1 downTo 1).firstNotNullOfOrNull { length ->
                byInput[value.substring(0, length)]?.takeIf { it.terminalOutput == null }
            }

        private fun validateRule(rule: RomajiRule) {
            require(rule.input.matches(ROMAJI)) { "ローマ字規則の入力は英小文字または apostrophe にします: ${rule.input}" }
            require(rule.remaining.isEmpty() || rule.remaining.matches(ROMAJI)) {
                "ローマ字規則の残余は英小文字または apostrophe にします: ${rule.remaining}"
            }
            require(rule.output.isNotEmpty() || rule.terminalOutput != null || rule.remaining.isNotEmpty()) {
                "ローマ字規則には出力、終端出力、または残余が必要です: ${rule.input}"
            }
            require(rule.terminalOutput == null || (rule.output.isEmpty() && rule.remaining.isEmpty())) {
                "終端出力を持つローマ字規則は出力と残余を持てません: ${rule.input}"
            }
        }

        private fun validateNoResidualCycle() {
            val edges = inputs.associateWith { input ->
                val residual = byInput.getValue(input).remaining
                inputs.filter { residual.startsWith(it) }
            }
            val visiting = mutableSetOf<String>()
            val visited = mutableSetOf<String>()
            fun visit(input: String) {
                require(visiting.add(input)) { "ローマ字規則の残余に循環があります: $input" }
                if (input in visited) {
                    visiting.remove(input)
                    return
                }
                edges.getValue(input).forEach(::visit)
                visiting.remove(input)
                visited.add(input)
            }
            inputs.forEach(::visit)
        }
    }

    companion object {
        private val ROMAJI = Regex("[a-z']+")

        /** 独立に記述した基本かな入力用の規則です。DDSKK 等の表は複製していません。 */
        val standardRules: List<RomajiRule> = buildList {
            fun row(prefix: String, kana: String) {
                val vowels = "aiueo"
                vowels.forEachIndexed { index, vowel -> add(RomajiRule(prefix + vowel, kana[index].toString())) }
            }
            row("", "あいうえお")
            row("k", "かきくけこ")
            row("s", "さしすせそ")
            add(RomajiRule("shi", "し"))
            row("t", "たちつてと")
            add(RomajiRule("chi", "ち")); add(RomajiRule("tsu", "つ"))
            row("n", "なにぬねの")
            row("h", "はひふへほ")
            add(RomajiRule("fu", "ふ"))
            row("m", "まみむめも")
            add(RomajiRule("ya", "や")); add(RomajiRule("yi", "い")); add(RomajiRule("yu", "ゆ")); add(RomajiRule("yo", "よ"))
            row("r", "らりるれろ")
            add(RomajiRule("wa", "わ")); add(RomajiRule("wi", "うぃ")); add(RomajiRule("wu", "う")); add(RomajiRule("we", "うぇ")); add(RomajiRule("wo", "を")); add(RomajiRule("ye", "いぇ"))
            row("g", "がぎぐげご"); row("z", "ざじずぜぞ")
            add(RomajiRule("ja", "じゃ")); add(RomajiRule("ji", "じ")); add(RomajiRule("ju", "じゅ")); add(RomajiRule("je", "じぇ")); add(RomajiRule("jo", "じょ"))
            row("d", "だぢづでど")
            row("b", "ばびぶべぼ"); row("p", "ぱぴぷぺぽ")
            listOf(
                "ky" to "き", "sy" to "し", "sh" to "し", "ty" to "ち", "ch" to "ち", "ny" to "に",
                "hy" to "ひ", "my" to "み", "ry" to "り", "gy" to "ぎ", "zy" to "じ", "jy" to "じ",
                "by" to "び", "py" to "ぴ", "dy" to "ぢ",
            ).forEach { (prefix, base) ->
                add(RomajiRule(prefix + "a", base + "ゃ")); add(RomajiRule(prefix + "u", base + "ゅ")); add(RomajiRule(prefix + "o", base + "ょ"))
            }
            listOf("x" to "", "l" to "").forEach { (prefix, _) ->
                add(RomajiRule(prefix + "a", "ぁ")); add(RomajiRule(prefix + "i", "ぃ")); add(RomajiRule(prefix + "u", "ぅ")); add(RomajiRule(prefix + "e", "ぇ")); add(RomajiRule(prefix + "o", "ぉ"))
                add(RomajiRule(prefix + "ya", "ゃ")); add(RomajiRule(prefix + "yu", "ゅ")); add(RomajiRule(prefix + "yo", "ょ")); add(RomajiRule(prefix + "tsu", "っ"))
            }
            add(RomajiRule("nn", "ん")); add(RomajiRule("n'", "ん")); add(RomajiRule("n", "", terminalOutput = "ん"))
            "bcdfghjklmpqrstvwxyz".filter { it != 'n' }.forEach { consonant -> add(RomajiRule("n$consonant", "ん", consonant.toString())) }
            add(RomajiRule("tt", "っ", "t"))
            "bcdfghjklmpqrsvwxyz".forEach { consonant -> add(RomajiRule("$consonant$consonant", "っ", consonant.toString())) }
        }
    }
}
