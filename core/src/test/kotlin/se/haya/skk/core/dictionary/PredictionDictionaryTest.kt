package se.haya.skk.core.dictionary

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import se.haya.skk.core.PredictionQuery
import se.haya.skk.core.PredictionSearchResult

class PredictionDictionaryTest {
    private fun source(id: String, text: String) =
        SkkDictionarySource(id, 1, SkkDictionaryCodec.parseText(text).entries)

    @Test fun `完全一致を含め読み順と辞書順で未使用候補を返し確定文字列を重複させない`() {
        val personal = source("personal", "かな /仮名/共通/")
        val system = source("system", "かな /システム/\nかなう /共通/叶う/")
        val result = CompositeSkkDictionary(personal, listOf(system)).predict(PredictionQuery("かな"))
            as PredictionSearchResult.Ready

        assertEquals(listOf("仮名", "共通", "システム", "叶う"), result.items.map { it.committedText })
        assertEquals(listOf("かな", "かな", "かな", "かなう"), result.items.map { it.historyTarget.readingKey })
        assertTrue(!result.hasMore)
    }

    @Test fun `候補がない完了検索だけを確定空として返す`() {
        val result = CompositeSkkDictionary(systems = listOf(source("system", "べつ /別/")))
            .predict(PredictionQuery("かな"))
        assertEquals(PredictionSearchResult.ConfirmedEmpty, result)
    }

    @Test fun `送りありは明示された送りだけを付け辞書の印を表示しない`() {
        val dictionary = CompositeSkkDictionary(systems = listOf(source(
            "system", "かk /書/[く/書/]/[く/描/]/\nかけk /掛/",
        )))
        val result = dictionary.predict(PredictionQuery("か")) as PredictionSearchResult.Ready

        assertEquals(listOf("書く", "描く"), result.items.map { it.committedText })
        assertEquals(listOf("かk", "かk"), result.items.map { it.historyTarget.readingKey })
        assertEquals(listOf("書", "描"), result.items.map { it.historyTarget.templateText })
        assertEquals(listOf("く", "く"), result.items.map { it.historyTarget.okuriCondition })
    }

    @Test fun `作業上限を越えた検索は空として返さない`() {
        val candidates = (0..PredictionQuery.MAX_WORK_ITEMS).joinToString("/") { "候補$it" }
        val result = CompositeSkkDictionary(systems = listOf(source("system", "かな /$candidates/")))
            .predict(PredictionQuery("か"))
        assertTrue(result is PredictionSearchResult.Indeterminate)
    }

    @Test fun `候補を持つ異なる見出し語の列挙も検索全体の作業上限へ数える`() {
        val entries = (0..4).joinToString("\n") { "か$it /候補$it/" }
        val result = CompositeSkkDictionary(systems = listOf(source("system", entries)))
            .predict(PredictionQuery("か", workLimit = 4))
        assertEquals(
            PredictionSearchResult.Indeterminate(se.haya.skk.core.PredictionSearchFailure.WORK_LIMIT),
            result,
        )
    }

    @Test fun `重なる複数接頭辞は同じ読みの作業領域を重複して保持しない`() {
        val result = CompositeSkkDictionary(systems = listOf(source("system", "かな /仮名/")))
            .predict(PredictionQuery(
                "か", alternativePrefixes = listOf("かな"), totalResultCharsLimit = 16,
            ))
        assertTrue(result is PredictionSearchResult.Ready)
    }

    @Test fun `複数辞書で重複する見出し語の列挙作業も検索上限へ数える`() {
        val first = source("first", "かな /仮名/")
        val second = source("second", "かな /仮名/")
        val result = CompositeSkkDictionary(systems = listOf(first, second))
            .predict(PredictionQuery("か", workLimit = 2))
        assertEquals(
            PredictionSearchResult.Indeterminate(se.haya.skk.core.PredictionSearchFailure.WORK_LIMIT),
            result,
        )
    }
    @Test fun `一文字に六千見出しが一致しても全件の使用順を調べて上位だけ保持する`() {
        val entries = List(6_000) { index ->
            val suffix = index.toString().padStart(4, '0')
            SkkDictionaryEntry("か$suffix", listOf(SkkDictionaryCandidate("候補$suffix")))
        }
        val dictionary = CompositeSkkDictionary(systems = listOf(SkkDictionarySource("system", 1, entries)),
            usageLookup = { if (it.readingKey == "か5999") 10 else null })
        val result = dictionary.predict(PredictionQuery("か")) as PredictionSearchResult.Ready
        assertEquals("候補5999", result.items.first().committedText)
        assertEquals(64, result.items.size)
        assertTrue(result.hasMore)
    }

    @Test fun `読みごとの解決は抑止と注釈の補完と全由来と辞書順位を保持する`() {
        val personal = source("personal", "かな /共通/")
        val first = source("first", "かな /非表示/共通;注釈/")
        val second = source("second", "かな /共通/別/")
        val dictionary = CompositeSkkDictionary(personal, listOf(first, second), setOf(
            SuppressedDictionaryCandidate("first", "かな", "非表示", null),
        ))
        val candidates = dictionary.predictionCandidatesForReading("かな")
        assertEquals(listOf("共通", "別"), candidates.map { it.committedText })
        assertEquals("注釈", candidates.first().candidate.annotation)
        assertEquals(listOf("personal", "first", "second"),
            candidates.first().candidate.selection!!.origins.map { it.dictionaryId })
        assertEquals(listOf(0, 2), candidates.map { it.dictionaryOrder })
        assertEquals(listOf(0, 1), candidates.map { it.candidateOrder })
    }

}
