package se.haya.skk.core.dictionary

import java.nio.charset.CharacterCodingException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class EucJis2004DecoderTest {
    @Test
    fun `2004 追加文字と補助面と結合文字列を正確に復号する`() {
        val vectors = listOf(
            bytes(0xae, 0xa1) to "\u4ff1",
            bytes(0xae, 0xa2) to "\ud840\udc0b",
            bytes(0xa4, 0xf7) to "\u304b\u309a",
            bytes(0x8f, 0xa1, 0xa1) to "\ud840\udc89",
            bytes(0x8e, 0xb6) to "\uff76",
            bytes(0xa1, 0xbd) to "\u2014",
        )
        for ((encoded, expected) in vectors) {
            assertEquals(expected, EucJis2004Decoder.decode(encoded))
        }
    }

    @Test
    fun `ASCII 制御文字を含む混在入力を置換や正規化なしで復号する`() {
        assertEquals(
            "A\t\u4ff1\ud840\udc0b\u304b\u309a\ud840\udc89\uff76\u2014\n\u0000\u007f",
            EucJis2004Decoder.decode(bytes(
                0x41, 0x09, 0xae, 0xa1, 0xae, 0xa2, 0xa4, 0xf7, 0x8f, 0xa1, 0xa1,
                0x8e, 0xb6, 0xa1, 0xbd, 0x0a, 0x00, 0x7f,
            )),
        )
        assertEquals("", EucJis2004Decoder.decode(byteArrayOf()))
    }

    @Test
    fun `ASCII と半角カナの範囲を完全に復号する`() {
        assertEquals((0..0x7f).map(Int::toChar).joinToString(""),
            EucJis2004Decoder.decode((0..0x7f).map(Int::toByte).toByteArray()))
        val encodedKana = (0xa1..0xdf).flatMap { listOf(0x8e.toByte(), it.toByte()) }.toByteArray()
        assertEquals((0xff61..0xff9f).map(Int::toChar).joinToString(""), EucJis2004Decoder.decode(encodedKana))
    }

    @Test
    fun `途中で切れた列と不正な継続バイトを拒否する`() {
        val invalid = listOf(
            bytes(0xa1), bytes(0xfe), bytes(0x8e), bytes(0x8f), bytes(0x8f, 0xa1),
            bytes(0xa1, 0x20), bytes(0xa1, 0xa0), bytes(0xa1, 0xff),
            bytes(0x8e, 0x20), bytes(0x8e, 0xa0), bytes(0x8e, 0xe0), bytes(0x8e, 0xfe), bytes(0x8e, 0xff),
            bytes(0x8f, 0x20, 0xa1), bytes(0x8f, 0xa0, 0xa1), bytes(0x8f, 0xff, 0xa1),
            bytes(0x8f, 0xa1, 0x20), bytes(0x8f, 0xa1, 0xa0), bytes(0x8f, 0xa1, 0xff),
        )
        for (encoded in invalid) {
            assertThrows(CharacterCodingException::class.java) { EucJis2004Decoder.decode(encoded) }
        }
    }

    @Test
    fun `予約バイトと未割当と JIS X 0212 だけの位置は復号しない`() {
        val invalid = (0x80..0x9f).filter { it != 0x8e && it != 0x8f }.map { bytes(it) } +
            listOf(bytes(0xa0), bytes(0xff), bytes(0xa4, 0xfc), bytes(0x8f, 0xa2, 0xaf))
        for (encoded in invalid) {
            assertThrows(CharacterCodingException::class.java) { EucJis2004Decoder.decode(encoded) }
            assertThrows(CharacterCodingException::class.java) {
                EucJis2004Decoder.decode(bytes(0x41) + encoded + bytes(0x42))
            }
        }
    }

    @Test
    fun `一次資料の表と著作権通知をクラスパスから読める`() {
        val mapping = checkNotNull(javaClass.getResourceAsStream("/se/haya/skk/core/dictionary/euc-jis-2004-std.txt"))
            .bufferedReader(Charsets.UTF_8).use { it.readText() }
        val notice = checkNotNull(javaClass.getResourceAsStream("/META-INF/Project-X0213.txt"))
            .bufferedReader(Charsets.UTF_8).use { it.readText() }
        assertTrue(mapping.contains("Copyright (C) 2006, 2009 Project X0213"))
        assertTrue(notice.contains("Copyright (C) 2006, 2009 Project X0213"))
        assertTrue(notice.contains("https://x0213.org/codetable/euc-jis-2004-std.txt"))
    }

    private fun bytes(vararg values: Int): ByteArray = values.map(Int::toByte).toByteArray()
}
