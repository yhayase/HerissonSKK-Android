package se.haya.skk.core

import org.junit.Assert.*
import org.junit.Test
import se.haya.skk.core.dictionary.CandidateSelection
import se.haya.skk.core.dictionary.SelectedCandidateOrigin

class PredictionCandidateCollectorTest {
    private fun candidate(text: String, reading: String = text, used: Long? = null) =
        PredictionCandidate(DictionaryCandidate(text), text,
            PredictionHistoryTarget(reading, text, null, text), lastUsedSequence = used)

    @Test fun `遅れて現れた上位の重複候補が読みと注釈と由来をまとめて置き換える`() {
        val collector = PredictionCandidateCollector(2, 1_024)
        collector.add(candidate("共通", "あ"))
        collector.add(candidate("別", "い"))
        collector.add(candidate("圏外", "う"))
        val latest = candidate("共通", "わ", 10).copy(candidate = DictionaryCandidate(
            "共通", annotation = "最新の注釈", selection = CandidateSelection(2, listOf(
                SelectedCandidateOrigin("personal", 2, true, "わ", "共通", null),
            )),
        ))
        collector.add(latest)
        val result = collector.result() as PredictionSearchResult.Ready
        assertEquals(latest, result.items.first())
        assertEquals(listOf("共通", "別"), result.items.map { it.committedText })
        assertTrue(result.hasMore)
    }

    @Test fun `一度圏外になった本文も後の使用履歴で先頭へ戻る`() {
        val collector = PredictionCandidateCollector(2, 1_024)
        collector.add(candidate("再登場", "あ"))
        collector.add(candidate("一位", "い", 2))
        collector.add(candidate("二位", "う", 1))
        collector.add(candidate("再登場", "わ", 3))
        val result = collector.result() as PredictionSearchResult.Ready
        assertEquals(listOf("再登場", "一位"), result.items.map { it.committedText })
        assertTrue(result.hasMore)
    }

    @Test fun `六十四件ちょうどと六十五件目の異なる本文を区別する`() {
        val collector = PredictionCandidateCollector(64, 65_536)
        repeat(64) { collector.add(candidate("候補$it")) }
        collector.add(candidate("候補0", used = 1))
        assertFalse((collector.result() as PredictionSearchResult.Ready).hasMore)
        collector.add(candidate("追加"))
        val result = collector.result() as PredictionSearchResult.Ready
        assertEquals(64, result.items.size)
        assertTrue(result.hasMore)
    }

    @Test fun `最終的な保持候補だけに文字上限を適用し由来も計上する`() {
        val collector = PredictionCandidateCollector(1, 20)
        collector.add(candidate("長".repeat(100)))
        collector.add(candidate("短", used = 1))
        assertEquals("短", (collector.result() as PredictionSearchResult.Ready).items.single().committedText)
        val withOrigin = candidate("短").copy(candidate = DictionaryCandidate("短",
            selection = CandidateSelection(1, listOf(SelectedCandidateOrigin(
                "辞".repeat(30), 1, true, "短", "短", null,
            )))))
        val bounded = PredictionCandidateCollector(1, 20)
        bounded.add(withOrigin)
        assertEquals(PredictionSearchResult.Indeterminate(PredictionSearchFailure.RESULT_LIMIT), bounded.result())
    }
}
