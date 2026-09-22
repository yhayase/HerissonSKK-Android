package se.haya.skk.core.romaji

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
class Romanizer(private val ruleSet: RomanRuleSet = RomanRuleSet.standard) {
    constructor(rules: Collection<RomajiRule>) : this(RomanRuleSet.compile(rules))

    /** まだ出力へ変換していないローマ字です。 */
    var pending: String = ""
        private set

    /** [text] を追加して、直ちに確定したかなだけを返します。 */
    fun feed(text: String): String = buildString {
        text.forEach { character ->
            pending += character
            append(resolve(character))
        }
    }

    /** 終端規則を適用し、適用できない未消化文字はそのまま返します。 */
    fun finish(): String = buildString {
        append(resolve())
        while (pending.isNotEmpty()) {
            val exact = ruleSet.exact(pending)
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

    /** 現在の保留列を、未知文字をそのまま出さずに終端規則で確定できるかを返します。 */
    fun canFinishPending(): Boolean = pending.isNotEmpty() && ruleSet.exact(pending) != null

    private fun resolve(retryCharacter: Char? = null): String = buildString {
        var rewrites = 0
        var canRetryCharacter = retryCharacter != null
        while (pending.isNotEmpty()) {
            val exact = ruleSet.exact(pending)
            if (exact != null && exact.terminalOutput == null && !ruleSet.hasLongerPrefix(pending)) {
                append(exact.output)
                pending = exact.remaining
                canRetryCharacter = false
                check(++rewrites <= ruleSet.maxRewrites) { "ローマ字規則の残余処理が上限を超えました" }
                continue
            }
            if (ruleSet.hasPrefix(pending)) return@buildString

            val completedPrefix = ruleSet.longestCompletedPrefix(pending)
            if (completedPrefix != null) {
                append(completedPrefix.output)
                val unconsumed = pending.drop(completedPrefix.input.length)
                pending = completedPrefix.remaining + unconsumed
                if (unconsumed.isEmpty()) canRetryCharacter = false
                check(++rewrites <= ruleSet.maxRewrites) { "ローマ字規則の残余処理が上限を超えました" }
                continue
            }
            if (canRetryCharacter && retryCharacter != null && pending.length > 1) {
                pending = retryCharacter.toString()
                canRetryCharacter = false
                continue
            }
            append(pending.first())
            pending = pending.drop(1)
        }
    }

    companion object {
        /** 独立に記述した基本かな入力用の規則です。DDSKK 等の表は複製していません。 */
        val standardRules: List<RomajiRule> get() = StandardRomajiRules.rules
    }
}

internal object StandardRomajiRules {
    val rules: List<RomajiRule> = buildList {
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
            listOf("a" to "ぁ", "i" to "ぃ", "e" to "ぇ", "o" to "ぉ").forEach { (vowel, small) ->
                add(RomajiRule("f$vowel", "ふ$small"))
            }
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
            add(RomajiRule("xtu", "っ"))
            add(RomajiRule("nn", "ん")); add(RomajiRule("n'", "ん")); add(RomajiRule("n", "", terminalOutput = "ん"))
            "bcdfghjklmpqrstvwxyz".filter { it != 'n' }.forEach { consonant -> add(RomajiRule("n$consonant", "ん", consonant.toString())) }
        // 入出力の対応は DDSKK の標準規則を参照しています。
        mapOf("zh" to "←", "zj" to "↓", "zk" to "↑", "zl" to "→", "zL" to "⇒",
            "z " to "　", "z*" to "※", "z," to "‥", "z-" to "〜", "z." to "…",
            "z/" to "・", "z0" to "○", "z@" to "◎", "z[" to "『", "z]" to "』",
            "z{" to "〖", "z}" to "〗", "z(" to "（", "z)" to "）").forEach { (input, output) ->
            add(RomajiRule(input, output))
        }
        add(RomajiRule("tt", "っ", "t"))
        "bcdfghjklmpqrsvwxyz".forEach { consonant -> add(RomajiRule("$consonant$consonant", "っ", consonant.toString())) }
    }
}
