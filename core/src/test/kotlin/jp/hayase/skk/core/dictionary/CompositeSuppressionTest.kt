package jp.hayase.skk.core.dictionary

import jp.hayase.skk.core.DictionaryQuery
import org.junit.Assert.*
import org.junit.Test

class CompositeSuppressionTest {
    private fun source(id: String, text: String, generation: Long = 1) =
        SkkDictionarySource(id, generation, SkkDictionaryCodec.parseText(text).entries)

    @Test fun `抑止は由来と送り条件の完全一致だけに作用して表示本文を重複させない`() {
        val a = source("a", "おおk /多;一般/[く/多;一致/]/[き/多;別条件/]/")
        val b = source("b", "おおk /多;別辞書/大/")
        val hidden = listOf(SuppressedDictionaryCandidate("a", "おおk", "多", "く"))
        val result = CompositeSkkDictionary(systems = listOf(a, b), suppressions = hidden)
            .resolve(DictionaryQuery("おおk", "く"))
        assertEquals(listOf("多", "大"), result.map { it.candidate.text })
        assertEquals("一般", result.first().candidate.annotation)
        assertEquals(listOf(null, "き", null), result.first().origins.map { it.candidate.okuriCondition })
        assertEquals(listOf("a", "a", "b"), result.first().origins.map { it.dictionaryId })
    }

    @Test fun `全システム由来を抑止しても個人候補と別キーは保持する`() {
        val personal = source("personal", "かな /仮名;個人/")
        val system = source("system", "かな /仮名;システム/別/\nべつ /仮名/")
        val hidden = listOf(
            SuppressedDictionaryCandidate("system", "かな", "仮名"),
            SuppressedDictionaryCandidate("personal", "かな", "仮名"),
        )
        val dictionary = CompositeSkkDictionary(personal, listOf(system), hidden)
        assertEquals(listOf("仮名", "別"), dictionary.lookup(DictionaryQuery("かな")).map { it.text })
        assertEquals(listOf("personal"), dictionary.resolve(DictionaryQuery("かな")).first().origins.map { it.dictionaryId })
        assertEquals("仮名", dictionary.lookup(DictionaryQuery("べつ")).single().text)
        assertEquals(listOf("別"), CompositeSkkDictionary(systems = listOf(system), suppressions = hidden)
            .lookup(DictionaryQuery("かな")).map { it.text })
    }

    @Test fun `同じ辞書の更新後も抑止し別IDには作用せず元リスト変更から独立する`() {
        val hidden = mutableListOf(SuppressedDictionaryCandidate("stable", "かな", "仮名"))
        val updated = source("stable", "かな /仮名;更新注釈/新候補/", 9)
        val newSource = source("new", "かな /仮名;新辞書/")
        val dictionary = CompositeSkkDictionary(systems = listOf(updated, newSource), suppressions = hidden)
        hidden.clear()
        val result = dictionary.resolve(DictionaryQuery("かな"))
        assertEquals(listOf("新候補", "仮名"), result.map { it.candidate.text })
        assertEquals(listOf("new"), result.last().origins.map { it.dictionaryId })
        assertEquals("新辞書", result.last().candidate.annotation)
    }
}
