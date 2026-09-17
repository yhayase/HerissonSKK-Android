package jp.hayase.skk.core.romaji

import java.util.Collections

/** 検証と索引構築を一度だけ終え、複数の入力状態で再利用できるローマ字規則集合です。 */
class RomanRuleSet private constructor(
    private val byInput: Map<String, RomajiRule>,
    private val prefixes: Set<String>,
    private val longerPrefixes: Set<String>,
    inputCharacters: Set<Char>,
) {
    val ruleCount: Int get() = byInput.size
    /** zL は標準の矢印規則だけを例外とし、通常の読み・送り開始を保ちます。 */
    val supportsSkkCaseConventions: Boolean = byInput.values.all { rule ->
        rule == RomajiRule("zL", "⇒") ||
            (rule.input.none { it.isUpperCase() } && rule.remaining.none { it.isUpperCase() })
    }

    /** 規則入力に現れる文字です。呼出側から変更できないコピーを公開します。 */
    val inputCharacters: Set<Char> = Collections.unmodifiableSet(LinkedHashSet(inputCharacters))

    fun accepts(character: Char): Boolean = character in inputCharacters

    /** 後続文字にしか現れない記号を、単独入力の句読点処理から奪いません。 */
    fun canStart(character: Char): Boolean = character.toString() in prefixes

    /** 未消化入力を完成へ進める文字を、途中のモード切替より先に判定します。 */
    fun continues(pending: String, text: String): Boolean =
        pending.isNotEmpty() && pending.length + text.length <= MAX_INPUT_LENGTH && pending + text in prefixes

    internal val maxRewrites: Int get() = ruleCount + 1
    internal fun exact(value: String): RomajiRule? = byInput[value]
    internal fun hasPrefix(value: String): Boolean = value in prefixes
    internal fun hasLongerPrefix(value: String): Boolean = value in longerPrefixes
    internal fun longestCompletedPrefix(value: String): RomajiRule? =
        (minOf(value.length - 1, MAX_INPUT_LENGTH) downTo 1).firstNotNullOfOrNull { length ->
            byInput[value.substring(0, length)]?.takeIf { it.terminalOutput == null }
        }

    companion object {
        const val MAX_RULES = 4_096
        const val MAX_INPUT_LENGTH = 16
        const val MAX_OUTPUT_LENGTH = 64
        const val MAX_TOTAL_UTF16_UNITS = 1_048_576

        /** 標準規則は初回だけコンパイルし、各 Romanizer で共有します。 */
        val standard: RomanRuleSet by lazy {
            compile(StandardRomajiRules.rules)
        }

        fun compile(rules: Collection<RomajiRule>): RomanRuleSet {
            require(rules.isNotEmpty()) { "ローマ字規則は一件以上必要です" }
            require(rules.size <= MAX_RULES) { "ローマ字規則は${MAX_RULES}件までです" }

            val copied = rules.map { it.copy() }
            var totalUnits = 0L
            val byInput = LinkedHashMap<String, RomajiRule>(copied.size)
            copied.forEach { rule ->
                validateRule(rule)
                totalUnits += rule.input.length.toLong() + rule.output.length + rule.remaining.length +
                    (rule.terminalOutput?.length ?: 0)
                require(totalUnits <= MAX_TOTAL_UTF16_UNITS) {
                    "ローマ字規則の文字列合計は$MAX_TOTAL_UTF16_UNITS UTF-16コード単位までです"
                }
                require(byInput.put(rule.input, rule) == null) {
                    "ローマ字規則の入力は重複できません: ${rule.input}"
                }
            }
            validateNoResidualCycle(byInput)

            val prefixes = HashSet<String>()
            val longerPrefixes = HashSet<String>()
            val inputCharacters = LinkedHashSet<Char>()
            byInput.keys.forEach { input ->
                inputCharacters += input.toList()
                for (length in 1..input.length) prefixes += input.substring(0, length)
                for (length in 1 until input.length) longerPrefixes += input.substring(0, length)
            }
            return RomanRuleSet(
                byInput = Collections.unmodifiableMap(LinkedHashMap(byInput)),
                prefixes = Collections.unmodifiableSet(prefixes),
                longerPrefixes = Collections.unmodifiableSet(longerPrefixes),
                inputCharacters = inputCharacters,
            )
        }

        private fun validateRule(rule: RomajiRule) {
            require(rule.input.length in 1..MAX_INPUT_LENGTH && (rule.input.all(::isGraphicalAscii) ||
                rule.input.length >= 2 && rule.input.last() == ' ' && rule.input.dropLast(1).all(::isGraphicalAscii))) {
                "ローマ字規則の入力は${MAX_INPUT_LENGTH}文字以内のASCII図形文字にします（末尾の空白1文字だけ許可）: ${rule.input}"
            }
            require(rule.remaining.length <= MAX_INPUT_LENGTH && rule.remaining.all(::isGraphicalAscii)) {
                "ローマ字規則の残余は${MAX_INPUT_LENGTH}文字以内のASCII図形文字にします: ${rule.input}"
            }
            require(rule.output.length <= MAX_OUTPUT_LENGTH && isValidUtf16(rule.output)) {
                "ローマ字規則の出力が不正または長すぎます: ${rule.input}"
            }
            require(rule.terminalOutput == null ||
                rule.terminalOutput.length <= MAX_OUTPUT_LENGTH && isValidUtf16(rule.terminalOutput)
            ) { "ローマ字規則の終端出力が不正または長すぎます: ${rule.input}" }
            require(rule.output.isNotEmpty() || rule.terminalOutput != null || rule.remaining.isNotEmpty()) {
                "ローマ字規則には出力、終端出力、または残余が必要です: ${rule.input}"
            }
            require(rule.terminalOutput == null || (rule.output.isEmpty() && rule.remaining.isEmpty())) {
                "終端出力を持つローマ字規則は出力と残余を持てません: ${rule.input}"
            }
        }

        /** Kahn 法を使い、4,096 段の入力でも JVM の再帰スタックへ依存しません。 */
        private fun validateNoResidualCycle(byInput: Map<String, RomajiRule>) {
            val inputs = byInput.keys.toList()
            val indices = inputs.withIndex().associate { (index, input) -> input to index }
            val edges = Array(inputs.size) { mutableSetOf<Int>() }
            val indegree = IntArray(inputs.size)
            inputs.forEachIndexed { source, input ->
                val remaining = byInput.getValue(input).remaining
                for (length in 1..minOf(remaining.length, MAX_INPUT_LENGTH)) {
                    val targetInput = remaining.substring(0, length)
                    val target = indices[targetInput] ?: continue
                    if (byInput.getValue(targetInput).terminalOutput != null) continue
                    if (edges[source].add(target)) indegree[target]++
                }
            }
            val queue = ArrayDeque<Int>()
            indegree.forEachIndexed { index, degree -> if (degree == 0) queue.addLast(index) }
            var visited = 0
            while (queue.isNotEmpty()) {
                val source = queue.removeFirst()
                visited++
                edges[source].forEach { target ->
                    indegree[target]--
                    if (indegree[target] == 0) queue.addLast(target)
                }
            }
            require(visited == inputs.size) { "ローマ字規則の残余に循環があります" }
        }

        private fun isGraphicalAscii(character: Char): Boolean = character.code in 0x21..0x7e

        private fun isValidUtf16(value: String): Boolean {
            var index = 0
            while (index < value.length) {
                val character = value[index]
                if (Character.isLowSurrogate(character)) return false
                if (Character.isHighSurrogate(character)) {
                    if (index + 1 >= value.length || !Character.isLowSurrogate(value[index + 1])) return false
                    index++
                }
                index++
            }
            return true
        }
    }
}
