package jp.hayase.skk.core.numeric

import jp.hayase.skk.core.dictionary.SkkDictionaryCandidate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NumericConversionTest {
    @Test fun `全角数字と桁カンマを正規化して複数数値列を抽出する`() {
        val extracted = NumericConversion.extract("れいわ６ねん1,234がつ.５")

        assertEquals("れいわ#ねん#がつ.#", extracted.lookupKey)
        assertEquals(listOf("6", "1234", "5"), extracted.spans)
        assertTrue(extracted.isUsable)
    }

    @Test fun `数値列上限超過は検索キーを返さない`() {
        val extracted = NumericConversion.extract((0..16).joinToString("a"))

        assertEquals(null, extracted.lookupKey)
        assertEquals(listOf(NumericDiagnostic.TOO_MANY_SPANS), extracted.diagnostics)
        assertFalse(extracted.isUsable)
    }

    @Test fun `固定八形式と先頭ゼロを展開する`() {
        val template = candidate("#0|#1|#2|#3|#5|#8|#9")
        val spans = listOf("002048", "2048", "2048", "2048", "2048", "001234", "73")

        val result = NumericConversion.expand(template, spans) { emptyList() }

        assertEquals(
            listOf("002048|２０４８|二〇四八|二千四十八|弐阡四拾八|1,234|７三"),
            result.candidates.map { it.text },
        )
        assertTrue(result.diagnostics.isEmpty())
    }

    @Test fun `形式番号は最長一致し先頭ゼロを無視する`() {
        val accepted = NumericConversion.expand(candidate("A#01B#0003C#000D"), listOf("12", "2048", "9")) {
            emptyList()
        }
        val rejected = NumericConversion.expand(candidate("A#10B"), listOf("12")) { emptyList() }

        assertEquals(listOf("A１２B二千四十八C9D"), accepted.candidates.map { it.text })
        assertEquals(listOf(NumericDiagnostic.UNSUPPORTED_TYPE), rejected.diagnostics)
    }

    @Test fun `数値列は左から対応し余剰列を無視して不足を拒否する`() {
        val extra = NumericConversion.expand(candidate("第#0"), listOf("6", "11")) { emptyList() }
        val missing = NumericConversion.expand(candidate("#0月#0日"), listOf("6")) { emptyList() }

        assertEquals(listOf("第6"), extra.candidates.map { it.text })
        assertEquals(listOf(NumericDiagnostic.MISSING_SPAN), missing.diagnostics)
    }

    @Test fun `位置表記はゼロと二十桁境界を扱い超過を拒否する`() {
        assertEquals("〇", expandOne("#3", "0"))
        assertEquals("零", expandOne("#5", "0"))
        assertEquals("壱萬", expandOne("#5", "10000"))
        assertTrue(expandOne("#3", "1".repeat(20)).contains("京"))
        val tooLarge = NumericConversion.expand(candidate("#5"), listOf("1".repeat(21))) { emptyList() }
        assertEquals(listOf(NumericDiagnostic.NUMBER_TOO_LARGE), tooLarge.diagnostics)
    }

    @Test fun `将棋形式は二桁以外を拒否する`() {
        val result = NumericConversion.expand(candidate("#9"), listOf("7")) { emptyList() }
        assertEquals(listOf(NumericDiagnostic.INVALID_NUMBER_FOR_TYPE), result.diagnostics)
    }

    @Test fun `再検索の複数結果を非再帰で展開し外側メタデータを保つ`() {
        val outer = SkkDictionaryCandidate("地域:#4", annotation = "外側", okuriCondition = "る")
        val result = NumericConversion.expand(outer, listOf("314")) { key ->
            assertEquals("314", key)
            listOf(candidate("北#3区"), candidate("中央区"), candidate("中央区"))
        }

        assertEquals(listOf("地域:北#3区", "地域:中央区"), result.candidates.map { it.text })
        assertTrue(result.candidates.all { it.annotation == "外側" && it.okuriCondition == "る" })
    }

    @Test fun `再検索ゼロ件は元数字を使い複数マーカーは直積にする`() {
        val fallback = NumericConversion.expand(candidate("番号#4"), listOf("404")) { emptyList() }
        val product = NumericConversion.expand(candidate("#4-#4"), listOf("1", "2")) { key ->
            if (key == "1") listOf(candidate("甲"), candidate("乙")) else listOf(candidate("一"), candidate("二"))
        }

        assertEquals(listOf("番号404"), fallback.candidates.map { it.text })
        assertEquals(listOf("甲-一", "甲-二", "乙-一", "乙-二"), product.candidates.map { it.text })
    }

    @Test fun `再検索の直積で同じ本文になった候補を最初の位置へまとめる`() {
        val result = NumericConversion.expand(candidate("#4#4"), listOf("1", "2")) { key ->
            if (key == "1") listOf(candidate("a"), candidate("ab"))
            else listOf(candidate("bc"), candidate("c"))
        }

        assertEquals(listOf("abc", "ac", "abbc"), result.candidates.map { it.text })
    }

    @Test fun `再検索件数と直積と総出力長の上限を候補単位で拒否する`() {
        val lookupLimit = NumericConversion.expand(candidate("#4"), listOf("1")) {
            List(NumericConversion.MAX_LOOKUP_RESULTS + 1) { candidate(it.toString()) }
        }
        val variants = NumericConversion.expand(candidate("#4#4"), listOf("1", "2")) {
            List(17) { candidate("v$it") }
        }
        val output = NumericConversion.expand(candidate("x#4"), listOf("1")) {
            listOf(candidate("a".repeat(32_768)), candidate("b".repeat(32_768)))
        }

        assertEquals(listOf(NumericDiagnostic.LOOKUP_RESULT_LIMIT), lookupLimit.diagnostics)
        assertEquals(listOf(NumericDiagnostic.VARIANT_LIMIT), variants.diagnostics)
        assertEquals(listOf(NumericDiagnostic.OUTPUT_LIMIT), output.diagnostics)
    }

    @Test fun `再検索例外と不正UTF16を本文非依存の診断へ変換する`() {
        val lookupFailure = NumericConversion.expand(candidate("秘密#4"), listOf("1")) { error("秘密本文") }
        val invalid = NumericConversion.expand(candidate("\ud800"), emptyList()) { emptyList() }

        assertEquals(listOf(NumericDiagnostic.LOOKUP_FAILED), lookupFailure.diagnostics)
        assertEquals(listOf(NumericDiagnostic.INVALID_INPUT), invalid.diagnostics)
        assertFalse(lookupFailure.diagnostics.toString().contains("秘密"))
    }

    private fun expandOne(template: String, span: String): String =
        NumericConversion.expand(candidate(template), listOf(span)) { emptyList() }.candidates.single().text

    private fun candidate(text: String) = SkkDictionaryCandidate(text)
}
