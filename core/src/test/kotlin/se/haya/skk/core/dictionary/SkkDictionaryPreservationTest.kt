package se.haya.skk.core.dictionary

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class SkkDictionaryPreservationTest {
    @Test
    fun `ASCII 略語の区分と区分内の順序を保持する`() {
        val source = """
            ;; okuri-ari entries.
            ほぞんz /独自送り候補/
            ;; okuri-nasi entries.
            zfixture /独自略語先頭/
            afixture /独自略語後続/
        """.trimIndent()
        val parsed = SkkDictionaryCodec.parseText(source)
        val output = SkkDictionaryCodec.format(parsed)
        assertTrue(output.indexOf(";; okuri-nasi entries.") < output.indexOf("zfixture /"))
        assertTrue(output.indexOf("zfixture /") < output.indexOf("afixture /"))
        assertEquals(parsed.entries, SkkDictionaryCodec.parseText(output).entries)
        assertEquals(parsed.entries, SkkDictionarySource("fixture", 0, parsed.entries).entriesForBackup().toList())
    }

    @Test
    fun `個人注釈の星と本文先頭の星を区別して往復する`() {
        val source = "fixture-annotation /独自注釈候補;*独自個人注釈/独自星候補;(concat \"*独自本文星\")/"
        val parsed = SkkDictionaryCodec.parseText(source)
        val output = SkkDictionaryCodec.format(parsed)
        assertTrue(output.contains("独自注釈候補;*独自個人注釈"))
        assertTrue(output.contains("独自星候補;(concat \"*独自本文星\")"))
        assertEquals(parsed.entries, SkkDictionaryCodec.parseText(output).entries)
    }

    @Test
    fun `引用した LF CR を本文と注釈だけで保持する`() {
        val parsed = SkkDictionaryCodec.parseText("fixture-lines /(concat \"独自甲\\n独自乙\\r独自丙\");*(concat \"独自注\\012独自釈\\015\")/")
        val candidate = parsed.entries.single().candidates.single()
        assertEquals("独自甲\n独自乙\r独自丙", candidate.text)
        assertEquals("独自注\n独自釈\r", candidate.annotation)
        val output = SkkDictionaryCodec.format(parsed)
        assertEquals(4, output.count { it == '\n' })
        assertEquals(parsed.entries, SkkDictionaryCodec.parseText(output).entries)
        for (condition in listOf("\n", "\r")) {
            assertThrows(SkkDictionaryFormatException::class.java) {
                SkkDictionaryCodec.format(listOf(SkkDictionaryEntry("fixture-invalid", listOf(SkkDictionaryCandidate("独自境界", okuriCondition = condition)))))
            }
        }
    }
    @Test
    fun `区分は明示見出しと初回重複を優先し推定で ASCII を送りなしにする`() {
        val parsed = SkkDictionaryCodec.parseText(";; okuri-nasi entries.\nほぞんk /独自一/\n;; okuri-ari entries.\nほぞんk /独自二/\nfixturez /独自明示/")
        assertEquals(SkkDictionarySection.OKURI_NASI, parsed.entries.first().section)
        assertEquals(SkkDictionarySection.OKURI_ARI, parsed.entries.last().section)
        assertEquals(SkkDictionarySection.OKURI_NASI, SkkDictionaryCodec.parseText("fixturez /独自推定/").entries.single().section)
        assertEquals(SkkDictionarySection.OKURI_ARI, SkkDictionaryCodec.parseText("ほぞんk /独自推定/").entries.single().section)
    }

    @Test
    fun `注釈補完では本文と個人由来を一緒に採用する`() {
        val parsed = SkkDictionaryCodec.parseText("fixture-merge /独自同一/\nfixture-merge /独自同一;*独自補完/\nfixture-merge /独自同一;独自競合/")
        val candidate = parsed.entries.single().candidates.single()
        assertEquals("独自補完", candidate.annotation)
        assertTrue(candidate.annotationIsPersonal)
        assertEquals(1, parsed.diagnostics.annotationConflictCount)
        val source = SkkDictionarySource("personal", 0, listOf(SkkDictionaryEntry("fixture-merge", listOf(SkkDictionaryCandidate("独自同一")))))
        val system = SkkDictionarySource("system", 0, parsed.entries)
        val dictionary = CompositeSkkDictionary(source, listOf(system))
        assertTrue(dictionary.lookup(se.haya.skk.core.DictionaryQuery("fixture-merge")).single().annotationIsPersonal)
        val prediction = dictionary.predict(se.haya.skk.core.PredictionQuery("fixture-merge", abbrev = true))
        assertTrue((prediction as se.haya.skk.core.PredictionSearchResult.Ready).items.single().candidate.annotationIsPersonal)
    }

    @Test
    fun `個人由来だけで注釈がないプログラム候補は拒否する`() {
        assertThrows(SkkDictionaryFormatException::class.java) {
            SkkDictionaryCodec.format(listOf(SkkDictionaryEntry("fixture-empty-annotation", listOf(SkkDictionaryCandidate("独自空注", annotationIsPersonal = true)))))
        }
        assertEquals(false, SkkDictionaryCodec.parseText("fixture-empty-annotation /独自空注;*/").entries.single().candidates.single().annotationIsPersonal)
    }

    @Test
    fun `EUC-JIS-2004 は明示指定と従来復号失敗時だけの自動判定で読む`() {
        val unique = "fixture-encoding /".toByteArray() + byteArrayOf(0xae.toByte(), 0xa1.toByte()) + "/".toByteArray()
        val explicit = SkkDictionaryCodec.parse(unique, SkkDictionaryEncoding.EUC_JIS_2004)
        assertEquals("俱", explicit.entries.single().candidates.single().text)
        assertEquals(SkkDictionaryEncoding.EUC_JIS_2004, SkkDictionaryCodec.parse(unique).encoding)
        val overlap = "fixture-encoding /".toByteArray() + byteArrayOf(0xa1.toByte(), 0xbd.toByte()) + "/".toByteArray()
        assertEquals(SkkDictionaryEncoding.EUC_JP, SkkDictionaryCodec.parse(overlap).encoding)
        assertEquals("—", SkkDictionaryCodec.parse(overlap, SkkDictionaryEncoding.EUC_JIS_2004).entries.single().candidates.single().text)
        assertThrows(SkkDictionaryFormatException::class.java) {
            SkkDictionaryCodec.parse(byteArrayOf(0xa4.toByte(), 0xfc.toByte()), SkkDictionaryEncoding.EUC_JIS_2004)
        }
    }

    @Test
    fun `許可した改行以外の制御文字と不正 Unicode は本文でも拒否する`() {
        for (value in listOf("独自\t", "独自\u0000", "独自\u0001", "独自\u007f", "独自\ud800")) {
            assertThrows(SkkDictionaryFormatException::class.java) {
                SkkDictionaryCodec.format(listOf(SkkDictionaryEntry("fixture-controls", listOf(SkkDictionaryCandidate(value)))))
            }
        }
    }

}
