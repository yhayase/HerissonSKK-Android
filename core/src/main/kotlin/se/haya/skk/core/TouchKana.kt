package se.haya.skk.core

/** 画面かな入力の正規化と、ローマ字規則に依存しない送り識別です。 */
object TouchKana {
    fun canonical(text: String): String = buildString(text.length) {
        text.forEach { append(if (it in 'ァ'..'ヶ') (it.code - 0x60).toChar() else it) }
    }

    fun consonant(kana: Char): Char? = when (canonical(kana.toString()).single()) {
        in "あぁ" -> 'a'
        in "いぃ" -> 'i'
        in "うぅ" -> 'u'
        in "えぇ" -> 'e'
        in "おぉ" -> 'o'
        in "かきくけこゕゖ" -> 'k'
        in "がぎぐげご" -> 'g'
        in "さしすせそ" -> 's'
        in "ざじずぜぞ" -> 'z'
        in "たちつてとっ" -> 't'
        in "だぢづでど" -> 'd'
        in "なにぬねのん" -> 'n'
        in "はひふへほ" -> 'h'
        in "ばびぶべぼ" -> 'b'
        in "ぱぴぷぺぽ" -> 'p'
        in "まみむめも" -> 'm'
        in "やゆよゃゅょ" -> 'y'
        in "らりるれろ" -> 'r'
        in "わをゎ" -> 'w'
        'ゔ' -> 'v'
        else -> null
    }

    /** 対象外の書記素は変更しません。カタカナの文字種は保持します。 */
    fun cycle(text: String): String? {
        if (text.length != 1) return null
        val kana = canonical(text).single()
        val group = cycles.firstOrNull { kana in it } ?: return null
        val next = group[(group.indexOf(kana) + 1) % group.length]
        return if (text.single() in 'ァ'..'ヶ') (next.code + 0x60).toChar().toString() else next.toString()
    }

    private val cycles = listOf(
        "あぁ", "いぃ", "うぅゔ", "えぇ", "おぉ", "かが", "きぎ", "くぐ", "けげ", "こご",
        "さざ", "しじ", "すず", "せぜ", "そぞ", "ただ", "ちぢ", "つっづ", "てで", "とど",
        "はばぱ", "ひびぴ", "ふぶぷ", "へべぺ", "ほぼぽ", "やゃ", "ゆゅ", "よょ", "わゎ",
    )
}
