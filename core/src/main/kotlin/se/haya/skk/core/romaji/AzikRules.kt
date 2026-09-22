package se.haya.skk.core.romaji

import java.util.Collections

/**
 * QWERTY の論理文字で構成した AZIK プロファイルです。
 *
 * 規則は木村清「AZIK 総合解説書」2025 年 5 月改訂版の動作から独立に構成し、
 * docs/azik-profile.md に記録した SKK 互換動作を追加しています。
 * SKK では `q` を「ん」に使い、かな切替はコマンド層で `[` に割り当てます。
 */
object AzikRules {
    private val incompatibleStandardSpellings = setOf(
        "sha", "shi", "shu", "she", "sho",
        "cha", "chi", "chu", "che", "cho",
        "xtsu", "ltsu", "la", "li", "lu", "le", "lo",
    )

    /** 呼出側で変更できない完全な規則一覧です。 */
    val rules: List<RomajiRule> = Collections.unmodifiableList(buildRules())

    /** 検証と索引構築を一度だけ終えた AZIK 規則集合です。 */
    val ruleSet: RomanRuleSet by lazy { RomanRuleSet.compile(rules) }

    private fun buildRules(): List<RomajiRule> {
        val rules = LinkedHashMap<String, RomajiRule>()

        fun add(input: String, output: String, remaining: String = "") {
            rules[input] = RomajiRule(input, output, remaining)
        }

        // AZIK と競合しない通常ローマ字を基礎にします。促音は常に `;` で入力します。
        Romanizer.standardRules
            .asSequence()
            .filterNot { it.output == "っ" }
            .filterNot { it.input in incompatibleStandardSpellings }
            .forEach { rules[it.input] = it }

        val regularRows = linkedMapOf(
            "k" to listOf("か", "き", "く", "け", "こ"),
            "s" to listOf("さ", "し", "す", "せ", "そ"),
            "t" to listOf("た", "ち", "つ", "て", "と"),
            "n" to listOf("な", "に", "ぬ", "ね", "の"),
            "h" to listOf("は", "ひ", "ふ", "へ", "ほ"),
            "f" to listOf("ふぁ", "ふぃ", "ふ", "ふぇ", "ふぉ"),
            "m" to listOf("ま", "み", "む", "め", "も"),
            "y" to listOf("や", "い", "ゆ", "いぇ", "よ"),
            "r" to listOf("ら", "り", "る", "れ", "ろ"),
            "w" to listOf("わ", "うぃ", "う", "うぇ", "を"),
            "g" to listOf("が", "ぎ", "ぐ", "げ", "ご"),
            "z" to listOf("ざ", "じ", "ず", "ぜ", "ぞ"),
            "d" to listOf("だ", "ぢ", "づ", "で", "ど"),
            "b" to listOf("ば", "び", "ぶ", "べ", "ぼ"),
            "p" to listOf("ぱ", "ぴ", "ぷ", "ぺ", "ぽ"),
        )
        val vowels = "aiueo"
        regularRows.forEach { (prefix, kana) ->
            vowels.forEachIndexed { index, vowel -> add("$prefix$vowel", kana[index]) }
        }

        // 撥音拡張: a/i/u/e/o の下段キーと、左手子音用の a 段互換キーです。
        regularRows.forEach { (prefix, kana) ->
            add(prefix + "z", kana[0] + "ん")
            add(prefix + "n", kana[0] + "ん")
            add(prefix + "k", kana[1] + "ん")
            add(prefix + "j", kana[2] + "ん")
            add(prefix + "d", kana[3] + "ん")
            add(prefix + "l", kana[4] + "ん")
        }
        add("nn", "ん")
        // 表で空欄となる組合せと特殊拡張の優先箇所を除きます。
        listOf("wj", "yk", "yd").forEach(rules::remove)

        // 二重母音拡張。あ行は通常の ai/uu/ei/ou を使います。
        regularRows.forEach { (prefix, kana) ->
            add(prefix + "q", kana[0] + "い")
            add(prefix + "h", kana[2] + "う")
            add(prefix + "w", kana[3] + "い")
            add(prefix + "p", kana[4] + "う")
        }
        listOf("wh", "ww", "yw").forEach(rules::remove)
        add("fp", "ふぉー")
        add("wp", "うぉー")

        val palatalRows = linkedMapOf(
            "ky" to "き", "kg" to "き", "sy" to "し", "x" to "し",
            "ty" to "ち", "c" to "ち", "ny" to "に", "ng" to "に",
            "hy" to "ひ", "hg" to "ひ", "my" to "み", "mg" to "み", "ry" to "り",
            "gy" to "ぎ", "zy" to "じ", "j" to "じ", "by" to "び",
            "py" to "ぴ", "pg" to "ぴ",
        )
        palatalRows.forEach { (prefix, base) ->
            add(prefix + "a", base + "ゃ")
            add(prefix + "u", base + "ゅ")
            add(prefix + "e", base + "ぇ")
            add(prefix + "o", base + "ょ")
            add(prefix + "z", base + "ゃん")
            add(prefix + "n", base + "ゃん")
            add(prefix + "j", base + "ゅん")
            add(prefix + "d", base + "ぇん")
            add(prefix + "l", base + "ょん")
            add(prefix + "q", base + "ゃい")
            add(prefix + "h", base + "ゅう")
            add(prefix + "w", base + "ぇい")
            add(prefix + "p", base + "ょう")
        }

        // 同指打鍵互換と、通常の拡張軸に収まらない頻出文字列です。
        mapOf(
            "kf" to "き", "yf" to "ゆ", "jf" to "じゅ", "hf" to "ふ", "mf" to "む",
            "nf" to "ぬ", "df" to "で", "cf" to "ちぇ", "pf" to "ぽん",
            "kt" to "こと", "st" to "した", "tt" to "たち", "ht" to "ひと",
            "wt" to "わた", "mn" to "もの", "ms" to "ます", "ds" to "です",
            "km" to "かも", "tm" to "ため", "dm" to "でも", "kr" to "から",
            "sr" to "する", "tr" to "たら", "nr" to "なる", "yr" to "よる",
            "rr" to "られ", "zr" to "ざる", "mt" to "また", "tb" to "たび",
            "nb" to "ねば", "bt" to "びと", "gr" to "がら", "gt" to "ごと",
            "nt" to "にち", "dt" to "だち", "wr" to "われ",
            "wf" to "わい", "sf" to "さい", "ss" to "せい",
            "zc" to "ざ", "zv" to "ざい", "zf" to "ぜ", "zx" to "ぜい",
            "mr" to "まる", "dr" to "である", "dg" to "だが", "fr" to "ふる",
        ).forEach { (input, output) -> add(input, output) }

        // 外来音と、単独小書き文字の SKK 互換入力です。
        mapOf(
            "fa" to "ふぁ", "fi" to "ふぃ", "fu" to "ふ", "fe" to "ふぇ", "fo" to "ふぉ",
            "va" to "ゔぁ", "vi" to "ゔぃ", "vu" to "ゔ", "ve" to "ゔぇ", "vo" to "ゔぉ",
            "vz" to "ゔぁん", "vn" to "ゔぁん", "vk" to "ゔぃん", "vd" to "ゔぇん", "vl" to "ゔぉん",
            "vq" to "ゔぁい", "vw" to "ゔぇい", "vp" to "ゔぉー",
            "tgi" to "てぃ", "tgu" to "とぅ", "tgk" to "てぃん", "tgh" to "てゅー", "tgp" to "とぅー",
            "dci" to "でぃ", "dcu" to "どぅ", "dck" to "でぃん", "dch" to "でゅー", "dcp" to "どぅー",
            "wso" to "うぉ", "wl" to "うぉん",
            "lya" to "ゃ", "lyu" to "ゅ", "lyo" to "ょ",
            "xxa" to "ぁ", "xxi" to "ぃ", "xxu" to "ぅ", "xxe" to "ぇ", "xxo" to "ぉ",
            "xya" to "ゃ", "xyu" to "ゅ", "xyo" to "ょ", "xwa" to "ゎ",
        ).forEach { (input, output) -> add(input, output) }

        // 専用キーと SKK で移動したコマンド文字のエスケープです。
        add(";", "っ")
        add("x;", ";")
        add("q", "ん")
        add(":", "ー")
        add("-", "ー")
        add("x[", "[")

        return rules.values.toList()
    }
}
