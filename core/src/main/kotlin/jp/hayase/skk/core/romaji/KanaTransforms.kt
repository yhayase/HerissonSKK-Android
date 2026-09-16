package jp.hayase.skk.core.romaji

import java.text.Normalizer

/** かなと ASCII の文字種変換です。 */
object KanaTransforms {
    /** ひらがなを全角カタカナへ変換します。 */
    fun hiraganaToKatakana(text: String): String = buildString(text.length) {
        text.forEach { character ->
            append(if (character in 'ぁ'..'ゟ') (character.code + 0x60).toChar() else character)
        }
    }

    /** ひらがなまたは全角カタカナを半角カタカナへ変換します。 */
    fun toHalfwidthKana(text: String): String = buildString {
        text.forEach { character ->
            val katakana = hiraganaToKatakana(character.toString())
            val decomposed = if (katakana.single() in KATAKANA_RANGE) {
                Normalizer.normalize(katakana, Normalizer.Form.NFD)
            } else {
                katakana
            }
            val transformed = if (decomposed.all(HALFWIDTH::containsKey)) decomposed else katakana
            transformed.forEach { normalized ->
                append(HALFWIDTH[normalized] ?: normalized)
            }
        }
    }

    /** ASCII の英数字・記号と空白を全角へ変換します。 */
    fun toFullwidthAscii(text: String): String = buildString(text.length) {
        text.forEach { character ->
            append(
                when (character) {
                    ' ' -> '　'
                    in '!'..'~' -> (character.code + 0xFEE0).toChar()
                    else -> character
                },
            )
        }
    }

    private val HALFWIDTH = mapOf(
        '。' to '｡', '「' to '｢', '」' to '｣', '、' to '､', '・' to '･', 'ヲ' to 'ｦ', 'ァ' to 'ｧ', 'ィ' to 'ｨ', 'ゥ' to 'ｩ', 'ェ' to 'ｪ', 'ォ' to 'ｫ',
        'ャ' to 'ｬ', 'ュ' to 'ｭ', 'ョ' to 'ｮ', 'ッ' to 'ｯ', 'ー' to 'ｰ', 'ア' to 'ｱ', 'イ' to 'ｲ', 'ウ' to 'ｳ', 'エ' to 'ｴ', 'オ' to 'ｵ',
        'カ' to 'ｶ', 'キ' to 'ｷ', 'ク' to 'ｸ', 'ケ' to 'ｹ', 'コ' to 'ｺ', 'サ' to 'ｻ', 'シ' to 'ｼ', 'ス' to 'ｽ', 'セ' to 'ｾ', 'ソ' to 'ｿ',
        'タ' to 'ﾀ', 'チ' to 'ﾁ', 'ツ' to 'ﾂ', 'テ' to 'ﾃ', 'ト' to 'ﾄ', 'ナ' to 'ﾅ', 'ニ' to 'ﾆ', 'ヌ' to 'ﾇ', 'ネ' to 'ﾈ', 'ノ' to 'ﾉ',
        'ハ' to 'ﾊ', 'ヒ' to 'ﾋ', 'フ' to 'ﾌ', 'ヘ' to 'ﾍ', 'ホ' to 'ﾎ', 'マ' to 'ﾏ', 'ミ' to 'ﾐ', 'ム' to 'ﾑ', 'メ' to 'ﾒ', 'モ' to 'ﾓ',
        'ヤ' to 'ﾔ', 'ユ' to 'ﾕ', 'ヨ' to 'ﾖ', 'ラ' to 'ﾗ', 'リ' to 'ﾘ', 'ル' to 'ﾙ', 'レ' to 'ﾚ', 'ロ' to 'ﾛ', 'ワ' to 'ﾜ', 'ン' to 'ﾝ',
        '゙' to 'ﾞ', '゚' to 'ﾟ', 'ヮ' to 'ﾜ', 'ヵ' to 'ｶ', 'ヶ' to 'ｹ',
    )

    private val KATAKANA_RANGE = '\u30A0'..'\u30FF'
}
