package se.haya.skk.core.romaji

import org.junit.Assert.assertEquals
import org.junit.Test

class KanaTransformsTest {
    @Test
    fun `ひらがなをカタカナへ変換する`() {
        assertEquals("カナヴヵヶヽヾヿ", KanaTransforms.hiraganaToKatakana("かなゔゕゖゝゞゟ"))
    }

    @Test
    fun `半角カナは濁点と半濁点を分けて出力する`() {
        assertEquals("ｶﾅｶﾞﾊﾟｳﾞ", KanaTransforms.toHalfwidthKana("かながぱゔ"))
        assertEquals("ｶﾞﾊﾟ", KanaTransforms.toHalfwidthKana("ガパ"))
    }

    @Test
    fun `半角カナ変換はカナ以外の正規化形を変えない`() {
        assertEquals("é각ｶﾞ", KanaTransforms.toHalfwidthKana("é각が"))
    }

    @Test
    fun `半角カナ変換ではひらがなの反復記号をカタカナへ変換する`() {
        assertEquals("ヽヾ", KanaTransforms.toHalfwidthKana("ゝゞ"))
    }

    @Test
    fun `ASCII を全角へ変換する`() {
        assertEquals("Ａｚ０９！　", KanaTransforms.toFullwidthAscii("Az09! "))
    }
}
