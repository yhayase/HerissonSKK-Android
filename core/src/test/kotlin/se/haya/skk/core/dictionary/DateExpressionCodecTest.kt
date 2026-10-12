package se.haya.skk.core.dictionary

import org.junit.Assert.*
import org.junit.Test
import se.haya.skk.core.DictionaryQuery
import se.haya.skk.core.PredictionQuery
import se.haya.skk.core.PredictionSearchResult
import se.haya.skk.core.numeric.NumericSkkDictionary

class DateExpressionCodecTest {
    private val expression = "(skk-current-date nil nil t)"

    @Test fun `日付式と同じ本文の文字列を区別し重複統合と往復で保持する`() {
        for (policy in SkkDictionaryImportPolicy.values()) {
            val source = "datefixture /$expression;*注釈/(concat \"$expression\")/$expression/"
            val document = SkkDictionaryCodec.parseText(source, policy = policy)
            assertEquals(listOf(true, false), document.entries.single().candidates.map { it.dateExpression })
            assertEquals(1, document.diagnostics.duplicateCandidateCount)
            assertEquals(0, document.diagnostics.warnings.count)
            assertTrue(document.entries.single().candidates.first().annotationIsPersonal)
            assertEquals(document.entries, SkkDictionaryCodec.parseText(SkkDictionaryCodec.format(document), policy = policy).entries)
        }
    }

    @Test fun `許可した相対日付と送り条件を保持する`() {
        val document = SkkDictionaryCodec.parseText("datefixture /[に/(skk-relative-date nil nil nil :dd -1);注釈/]/")
        val candidate = document.entries.single().candidates.single()
        assertTrue(candidate.dateExpression)
        assertEquals("に", candidate.okuriCondition)
        assertEquals(document.entries, SkkDictionaryCodec.parseText(SkkDictionaryCodec.format(document)).entries)
    }

    @Test fun `非対応形式だけを警告で除外し壊れた相対日付は拒否する`() {
        for (expression in listOf("(skk-relative-date nil \"%Y\" nil :dd 1)", "(skk-relative-date 'fixture nil nil :dd 1)")) {
            val source = "datefixture /安全/$expression/"
            assertThrows(SkkDictionaryFormatException::class.java) { SkkDictionaryCodec.parseText(source) }
            val document = SkkDictionaryCodec.parseText(source, policy = SkkDictionaryImportPolicy.SKIP_KNOWN_UNSUPPORTED)
            assertEquals(listOf("安全"), document.entries.single().candidates.map { it.text })
            assertEquals(1, document.diagnostics.warnings.count)
        }
        for (expression in listOf("(skk-relative-date nil nil nil :dd nope)", "(skk-relative-date nil nil nil :dd 36601)", "(skk-current-date (progn t))")) {
            assertThrows(SkkDictionaryFormatException::class.java) {
                SkkDictionaryCodec.parseText("datefixture /安全/$expression/", policy = SkkDictionaryImportPolicy.SKIP_KNOWN_UNSUPPORTED)
            }
        }
    }

    @Test fun `日付印を任意の本文に付けた公開モデルを拒否する`() {
        assertThrows(SkkDictionaryFormatException::class.java) {
            SkkDictionaryCodec.format(listOf(SkkDictionaryEntry("datefixture", listOf(SkkDictionaryCandidate("任意", dateExpression = true)))))
        }
    }

    @Test fun `式と文字列の由来と抑止を区別して日付式を予測から除外する`() {
        val candidates = listOf(SkkDictionaryCandidate(expression, dateExpression = true), SkkDictionaryCandidate(expression))
        val personal = SkkDictionarySource("personal", 1, emptyList())
        val system = SkkDictionarySource("system", 2, listOf(SkkDictionaryEntry("datefixture", candidates)))
        val dictionary = CompositeSkkDictionary(personal, listOf(system))
        val results = dictionary.lookup(DictionaryQuery("datefixture"))
        assertEquals(listOf(true, false), results.map { it.dateExpression })
        assertEquals(listOf(true, false), results.map { it.selection!!.origins.single().dateExpression })
        val prediction = dictionary.predict(PredictionQuery("date", limit = 1)) as PredictionSearchResult.Ready
        assertEquals(1, prediction.items.size)
        assertFalse(prediction.items.single().candidate.dateExpression)
        val suppressed = CompositeSkkDictionary(personal, listOf(system), listOf(SuppressedDictionaryCandidate("system", "datefixture", expression, dateExpression = true)))
        assertEquals(listOf(false), suppressed.lookup(DictionaryQuery("datefixture")).map { it.dateExpression })
    }

    @Test fun `数値正規化キーの式を展開せず元の本文と学習先を保持する`() {
        val source = SkkDictionarySource("personal", 1, listOf(SkkDictionaryEntry("date#", listOf(SkkDictionaryCandidate(expression, "注釈", dateExpression = true), SkkDictionaryCandidate(expression)))))
        val numeric = NumericSkkDictionary(CompositeSkkDictionary(source))
        val candidates = numeric.lookup(DictionaryQuery("date12"))
        assertEquals(listOf(true, false), candidates.map { it.dateExpression })
        val candidate = candidates.first()
        assertEquals(expression, candidate.text)
        assertTrue(candidate.dateExpression)
        assertEquals("date#", candidate.learningTarget!!.query.readingKey)
        assertTrue(candidate.learningTarget!!.dateExpression)
        assertTrue(candidate.selection!!.origins.single().dateExpression)
        val prediction = numeric.predict(PredictionQuery("date12")) as PredictionSearchResult.Ready
        assertEquals(listOf(false), prediction.items.map { it.candidate.dateExpression })
    }
    @Test fun `日付だけの候補は予測と数値再検索から漏れない`() {
        val source = SkkDictionarySource("personal", 1, listOf(
            SkkDictionaryEntry("date#", listOf(SkkDictionaryCandidate(expression, dateExpression = true))),
            SkkDictionaryEntry("number#", listOf(SkkDictionaryCandidate("#4"))),
            SkkDictionaryEntry("12", listOf(SkkDictionaryCandidate(expression, dateExpression = true))),
        ))
        val dictionary = CompositeSkkDictionary(source)
        val numeric = NumericSkkDictionary(dictionary)
        assertTrue(dictionary.predict(PredictionQuery("date")) is PredictionSearchResult.ConfirmedEmpty)
        assertTrue(numeric.predict(PredictionQuery("date12")) is PredictionSearchResult.ConfirmedEmpty)
        assertEquals(listOf("12"), numeric.lookup(DictionaryQuery("number12")).map { it.text })
    }

    @Test fun `既知の年 月 複数オフセット形式は評価せず警告で除外する`() {
        val expressions = listOf(
            "(skk-relative-date nil nil nil :mm -1)",
            "(skk-relative-date nil nil nil :yy 2)",
            "(skk-relative-date nil nil nil :yy 1 :mm -1 :dd 2)",
            "(skk-relative-date nil nil nil :mm 1 :mm 2)",
        )
        for (expression in expressions) {
            val source = "datefixture /安全/$expression/"
            assertThrows(SkkDictionaryFormatException::class.java) { SkkDictionaryCodec.parseText(source) }
            val document = SkkDictionaryCodec.parseText(source, policy = SkkDictionaryImportPolicy.SKIP_KNOWN_UNSUPPORTED)
            assertEquals(listOf("安全"), document.entries.single().candidates.map { it.text })
            assertEquals(mapOf(SkkDictionaryWarningReason.UNSUPPORTED_DATE_EXPRESSION to 1), document.diagnostics.warnings.counts)
        }
    }

    @Test fun `相対日付の未知キー 値欠落 非整数と日オフセット上限違反を拒否する`() {
        val expressions = listOf(
            "(skk-relative-date nil nil nil :mm)",
            "(skk-relative-date nil nil nil :xx 1)",
            "(skk-relative-date nil nil nil :yy nope)",
            "(skk-relative-date nil nil nil :yy 1 :dd 36601)",
            "(skk-relative-date nil nil nil :yy 999999999999999999999999)",
        )
        for (expression in expressions) {
            assertThrows(SkkDictionaryFormatException::class.java) {
                SkkDictionaryCodec.parseText("datefixture /安全/$expression/", policy = SkkDictionaryImportPolicy.SKIP_KNOWN_UNSUPPORTED)
            }
        }
    }

}
