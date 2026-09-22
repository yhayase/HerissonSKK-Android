package se.haya.skk.core.romaji

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class AzikRulesTest {
    private fun convert(input: String): String = Romanizer(AzikRules.ruleSet).run {
        feed(input) + finish()
    }

    @Test fun `規則一覧とコンパイル済み集合を再利用できる`() {
        assertSame(AzikRules.ruleSet, AzikRules.ruleSet)
        assertTrue(AzikRules.rules.isNotEmpty())
        assertTrue(AzikRules.ruleSet.accepts(';'))
        assertTrue(AzikRules.ruleSet.accepts(':'))
    }

    @Test fun `撥音拡張と互換キーを変換する`() {
        assertEquals("かんきんくんけんこん", convert("kzkkkjkdkl"))
        assertEquals("さんたんわん", convert("sntnwn"))
        assertEquals("あんいんうんえんおん", convert("aqiquqeqoq"))
        assertEquals("ん", convert("nn"))
    }

    @Test fun `二重母音拡張を変換する`() {
        assertEquals("かいくうけいこう", convert("kqkhkwkp"))
        assertEquals("あいううえいおう", convert("aiuueiou"))
        assertEquals("ふぉーうぉー", convert("fpwp"))
    }

    @Test fun `拗音と互換子音へ拡張キーを適用する`() {
        assertEquals("きょう", convert("kgp"))
        assertEquals("にゃんにゃん", convert("ngzngn"))
        assertEquals("しゃしゅしぇしょちゃちゅちぇちょ", convert("xaxuxexocacuceco"))
        assertEquals("ぎゃんじゅうぴぇい", convert("gyzjhpgw"))
    }

    @Test fun `同指互換と特殊拡張を変換する`() {
        assertEquals("きゆじゅふむぬでちぇぽん", convert("kfyfjfhfmfnfdfcfpf"))
        specialExpansionCases.forEach { (input, expected) ->
            assertEquals(input, expected, convert(input))
        }
    }

    @Test fun `専用キーとSKK互換入力を変換する`() {
        assertEquals("っんーー", convert(";q:-"))
        assertEquals("ぁぃぅぇぉゃゅょゎ", convert("xxaxxixxuxxexxoxyaxyuxyoxwa"))
        assertEquals(";[", convert("x;x["))
    }

    @Test fun `外来音を変換する`() {
        foreignSoundCases.forEach { (input, expected) ->
            assertEquals(input, expected, convert(input))
        }
    }

    @Test fun `通常ローマ字とAZIKを同じプロファイルで使える`() {
        assertEquals("かなじょぴゅ", convert("kanajopyu"))
        assertEquals("かんきょう", convert("kzkyou"))
        assertEquals("いあ", convert("ia"))
        assertEquals("いい", convert("ii"))
    }

    @Test(expected = UnsupportedOperationException::class)
    fun `公開規則一覧は変更できない`() {
        @Suppress("UNCHECKED_CAST")
        (AzikRules.rules as MutableList<RomajiRule>).add(RomajiRule("!", "変更"))
    }

    @Test fun `未完成列を保留して終端で処理する`() {
        val romanizer = Romanizer(AzikRules.ruleSet)

        assertEquals("", romanizer.feed("k"))
        assertEquals("k", romanizer.pending)
        assertTrue(romanizer.backspacePending())
        assertEquals("", romanizer.pending)
        assertFalse(romanizer.backspacePending())

        assertEquals("", romanizer.feed("n"))
        assertEquals("ん", romanizer.finish())
    }

    private companion object {
        val specialExpansionCases = linkedMapOf(
            "kt" to "こと", "st" to "した", "tt" to "たち", "ht" to "ひと",
            "wt" to "わた", "mn" to "もの", "ms" to "ます", "ds" to "です",
            "km" to "かも", "tm" to "ため", "dm" to "でも", "kr" to "から",
            "sr" to "する", "tr" to "たら", "nr" to "なる", "yr" to "よる",
            "rr" to "られ", "zr" to "ざる", "mt" to "また", "tb" to "たび",
            "nb" to "ねば", "bt" to "びと", "gr" to "がら", "gt" to "ごと",
            "nt" to "にち", "dt" to "だち", "wr" to "われ",
            "mr" to "まる", "dr" to "である", "dg" to "だが", "fr" to "ふる",
        )

        val foreignSoundCases = linkedMapOf(
            "fa" to "ふぁ", "fi" to "ふぃ", "fu" to "ふ", "fe" to "ふぇ", "fo" to "ふぉ",
            "va" to "ゔぁ", "vi" to "ゔぃ", "vu" to "ゔ", "ve" to "ゔぇ", "vo" to "ゔぉ",
            "vz" to "ゔぁん", "vn" to "ゔぁん", "vk" to "ゔぃん", "vd" to "ゔぇん", "vl" to "ゔぉん",
            "vq" to "ゔぁい", "vw" to "ゔぇい", "vp" to "ゔぉー",
            "tgi" to "てぃ", "tgu" to "とぅ", "tgk" to "てぃん", "tgh" to "てゅー", "tgp" to "とぅー",
            "dci" to "でぃ", "dcu" to "どぅ", "dck" to "でぃん", "dch" to "でゅー", "dcp" to "どぅー",
            "wso" to "うぉ", "wl" to "うぉん", "wp" to "うぉー", "fp" to "ふぉー",
        )
    }
}
