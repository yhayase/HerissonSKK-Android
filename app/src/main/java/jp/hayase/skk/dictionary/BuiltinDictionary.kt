package jp.hayase.skk.dictionary

import jp.hayase.skk.core.BasicSkkDictionary
import jp.hayase.skk.core.dictionary.CompositeSkkDictionary
import jp.hayase.skk.core.dictionary.SkkDictionaryCodec
import jp.hayase.skk.core.dictionary.SkkDictionarySource

/** 独自の受入試験用データです。日常利用向けの完全な辞書ではありません。 */
object BuiltinDictionary {
    val source: SkkDictionarySource by lazy {
        val text = """
            にほん /日本;国名・限定試験辞書/二本;本数・限定試験辞書/
            かk /書/[く/書/]/
            API /エーピーアイ/
            だい> /第/
            >かい /回/
        """.trimIndent() + "\nてすと /" + (1..10).joinToString("/") { "候補$it;注釈$it" } + "/\n"
        SkkDictionarySource("__builtin_fixture__", 1, SkkDictionaryCodec.parseText(text).entries)
    }

    val dictionary: BasicSkkDictionary by lazy { CompositeSkkDictionary(systems = listOf(source)) }
}
