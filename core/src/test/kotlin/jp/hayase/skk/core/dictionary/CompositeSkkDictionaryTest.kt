package jp.hayase.skk.core.dictionary

import jp.hayase.skk.core.DictionaryQuery
import jp.hayase.skk.core.BasicSkkEngine
import jp.hayase.skk.core.BasicSkkAction
import org.junit.Assert.*
import org.junit.Test

class CompositeSkkDictionaryTest {
    private fun source(id: String, text: String, enabled: Boolean = true) =
        SkkDictionarySource(id, 1, SkkDictionaryCodec.parseText(text).entries, enabled)

    @Test fun `K12 個人辞書と有効なシステム辞書の指定順で統合する`() {
        val personal = source("personal", "にほん /二本/日本/")
        val a = source("a", "にほん /日本;国名/日本語/")
        val b = source("b", "にほん /日本海/二本;本数/")
        val disabled = source("disabled", "にほん /出さない/", false)
        val result = CompositeSkkDictionary(personal, listOf(a, disabled, b)).resolve(DictionaryQuery("にほん"))
        assertEquals(listOf("二本", "日本", "日本語", "日本海"), result.map { it.candidate.text })
        assertEquals(listOf("本数", "国名", null, null), result.map { it.candidate.annotation })
        assertEquals(listOf("personal", "b"), result.first().origins.map { it.dictionaryId })
        assertTrue(result.first().origins.first().personal)
        assertEquals(1L, result.first().origins.first().generation)
    }

    @Test fun `K04 一致送りを優先し不一致も残して同じ本文を重複表示しない`() {
        val personal = source("personal", "おおk /大/多/[く/多/]/[き/大/]/")
        val result = CompositeSkkDictionary(personal).lookup(DictionaryQuery("おおk", "く"))
        assertEquals(listOf("多", "大"), result.map { it.text })
        assertEquals(listOf("く", null), result.map { it.okuriCondition })
    }

    @Test fun `下位辞書の送り一致は上位辞書の候補を追い越さない`() {
        val personal = source("personal", "かk /掻/[け/欠/]/")
        val system = source("system", "かk /書/[く/書/]/")
        val result = CompositeSkkDictionary(personal, listOf(system)).lookup(DictionaryQuery("かk", "く"))
        assertEquals(listOf("掻", "欠", "書"), result.map { it.text })
        val engine = BasicSkkEngine(CompositeSkkDictionary(personal, listOf(system)))
        assertEquals("掻く", engine.dispatch(BasicSkkAction.Text("KaKu")).view.candidate?.committedText)
        assertEquals("欠く", engine.dispatch(BasicSkkAction.Text(" ")).view.candidate?.committedText)
        assertEquals("書く", engine.dispatch(BasicSkkAction.Text(" ")).view.candidate?.committedText)
    }

    @Test fun `注釈の競合は上位の非空値を採用して下位の由来も保持する`() {
        val first = source("first", "にほん /日本;上位/")
        val second = source("second", "にほん /日本;下位/")
        val result = CompositeSkkDictionary(systems = listOf(first, second)).resolve(DictionaryQuery("にほん")).single()
        assertEquals("上位", result.candidate.annotation)
        assertEquals(listOf("上位", "下位"), result.origins.map { it.candidate.annotation })
    }

    @Test fun `辞書順を変えた新しいスナップショットは古い検索順位を変更しない`() {
        val a = source("a", "にほん /日本/")
        val b = source("b", "にほん /二本/")
        val sources = mutableListOf(a, b)
        val old = CompositeSkkDictionary(systems = sources)
        sources.reverse()
        val new = CompositeSkkDictionary(systems = sources)
        assertEquals(listOf("日本", "二本"), old.lookup(DictionaryQuery("にほん")).map { it.text })
        assertEquals(listOf("二本", "日本"), new.lookup(DictionaryQuery("にほん")).map { it.text })
    }

    @Test fun `辞書内容の入力リストを後から変更しても公開した世代は変わらない`() {
        val candidates = mutableListOf(SkkDictionaryCandidate("日本"))
        val entries = mutableListOf(SkkDictionaryEntry("にほん", candidates))
        val dictionary = CompositeSkkDictionary(SkkDictionarySource("personal", 8, entries))
        candidates.clear()
        entries.clear()
        assertEquals("日本", dictionary.lookup(DictionaryQuery("にほん")).single().text)
        assertTrue(dictionary.lookup(DictionaryQuery("未知")).isEmpty())
    }

    @Test fun `abbrevは大小文字を保持して完全一致検索する`() {
        val dictionary = CompositeSkkDictionary(systems = listOf(source("a", "API /エーピーアイ/\napi /小文字/")))
        assertEquals("エーピーアイ", dictionary.lookup(DictionaryQuery("API", abbrev = true)).single().text)
        assertEquals("小文字", dictionary.lookup(DictionaryQuery("api", abbrev = true)).single().text)
    }

    @Test fun `重複した辞書IDと未正規化の重複見出し語を拒否する`() {
        val a = source("same", "にほん /日本/")
        assertThrows(IllegalArgumentException::class.java) { CompositeSkkDictionary(a, listOf(a)) }
        val entry = SkkDictionaryEntry("にほん", listOf(SkkDictionaryCandidate("日本")))
        assertThrows(IllegalArgumentException::class.java) { SkkDictionarySource("a", 0, listOf(entry, entry)) }
    }
}
