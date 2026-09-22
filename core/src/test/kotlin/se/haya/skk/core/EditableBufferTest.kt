package se.haya.skk.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EditableBufferTest {
    @Test fun `削除で隣のクラスタが結合してもカーソルは次の境界に置く`() {
        for (backspace in listOf(true, false)) {
            val buffer = EditableBuffer("\u1100x\u1161", if (backspace) 2 else 1)
            if (backspace) buffer.backspace() else buffer.delete()
            assertEquals("\u1100\u1161", buffer.text)
            assertEquals(2, buffer.cursor)
            assertFalse(buffer.moveRight())
            assertTrue(buffer.backspace())
            assertEquals("", buffer.text)
        }
        val flag = EditableBuffer("🇯x🇵", 3)
        flag.backspace()
        assertEquals("🇯🇵", flag.text)
        assertEquals(4, flag.cursor)
        flag.backspace()
        assertEquals("", flag.text)
    }

    @Test fun `CRLFのendと削除は改行の途中に止まらない`() {
        val buffer = EditableBuffer("a\r\nb", 0)
        buffer.moveEnd()
        assertEquals(1, buffer.cursor)
        buffer.moveRight()
        assertEquals(3, buffer.cursor)
        buffer.backspace()
        assertEquals("ab", buffer.text)
        assertEquals(1, buffer.cursor)
        buffer.moveHome()
        assertEquals(0, buffer.cursor)
    }

    @Test fun `かなはZWJで絵文字として結合しない`() {
        val buffer = EditableBuffer("あ\u200Dい")
        buffer.backspace()
        assertEquals("あ\u200D", buffer.text)
    }

    @Test fun `絵文字タグ列とインド文字の結合を分断しない`() {
        val england = "\uD83C\uDFF4\uDB40\uDC67\uDB40\uDC62\uDB40\uDC65\uDB40\uDC6E\uDB40\uDC67\uDB40\uDC7F"
        for (cluster in listOf(england, "\u0915\u094D\u0937", "a\u200C")) {
            val buffer = EditableBuffer(cluster)
            buffer.backspace()
            assertEquals("", buffer.text)
        }
    }

    @Test fun `不正なサロゲートを拒否して編集前の内容を保持する`() {
        for (invalid in listOf("\uD800", "\uDC00", "\uD800a")) {
            val buffer = EditableBuffer("かな")
            org.junit.Assert.assertThrows(IllegalArgumentException::class.java) { buffer.insert(invalid) }
            assertEquals("かな", buffer.text)
            assertEquals(2, buffer.cursor)
            org.junit.Assert.assertThrows(IllegalArgumentException::class.java) { EditableBuffer(invalid) }
        }
    }

    @Test fun `生成した編集列でも常に境界を保つ`() {
        val random = java.util.Random(20260916L)
        val tokens = listOf("a", "か", "\u3099", "\u1100", "\u1161", "🇯", "🇵", "👩", "💻", "\u200D", "\r\n")
        val buffer = EditableBuffer()
        repeat(2000) { step ->
            when (random.nextInt(8)) {
                0, 1 -> buffer.insert(tokens[random.nextInt(tokens.size)])
                2 -> buffer.backspace()
                3 -> buffer.delete()
                4 -> buffer.moveLeft()
                5 -> buffer.moveRight()
                6 -> buffer.moveHome()
                7 -> buffer.moveEnd()
            }
            assertTrue("seed=20260916 step=$step", GraphemeBoundary.isBoundary(buffer.text, buffer.cursor))
        }
    }

    @Test
    fun `途中への挿入と削除は入力先ではなく内部の読みだけを変更する`() {
        val buffer = EditableBuffer("にほん")

        assertTrue(buffer.moveLeft())
        assertTrue(buffer.moveLeft())
        buffer.insert("あ")
        assertEquals("にあほん", buffer.text)
        assertTrue(buffer.backspace())
        assertEquals("にほん", buffer.text)
        assertTrue(buffer.delete())
        assertEquals("にん", buffer.text)
    }

    @Test
    fun `結合濁点は一つのクラスタとして削除する`() {
        val buffer = EditableBuffer("か\u3099")

        assertTrue(buffer.backspace())
        assertEquals("", buffer.text)
        assertEquals(0, buffer.cursor)
    }

    @Test
    fun `サロゲート絵文字と異体字セレクタを分割しない`() {
        val buffer = EditableBuffer("😀✈️")

        assertTrue(buffer.moveLeft())
        assertEquals("😀".length, buffer.cursor)
        assertTrue(buffer.backspace())
        assertEquals("✈️", buffer.text)
        assertTrue(buffer.delete())
        assertEquals("", buffer.text)
    }

    @Test
    fun `肌色を含むZWJ絵文字は一つのクラスタとして削除する`() {
        val emoji = "👩🏽‍💻"
        val text = "登録$emoji 本文"
        val buffer = EditableBuffer(text)

        buffer.moveHome()
        repeat(3) { buffer.moveRight() }
        assertEquals("登録".length + emoji.length, buffer.cursor)
        assertTrue(buffer.backspace())
        assertEquals("登録 本文", buffer.text)

        val deleteBuffer = EditableBuffer(text, "登録".length)
        assertTrue(deleteBuffer.delete())
        assertEquals("登録 本文", deleteBuffer.text)
    }

    @Test
    fun `地域指標の国旗は一つのクラスタとして移動と削除をする`() {
        val flag = "🇯🇵"
        val buffer = EditableBuffer("A${flag}B")

        assertTrue(buffer.moveHome())
        assertTrue(buffer.moveRight())
        assertTrue(buffer.moveRight())
        assertEquals(1 + flag.length, buffer.cursor)
        assertTrue(buffer.backspace())
        assertEquals("AB", buffer.text)

        val deleteBuffer = EditableBuffer("A${flag}B", 1)
        assertTrue(deleteBuffer.delete())
        assertEquals("AB", deleteBuffer.text)
    }

    @Test
    fun `挿入が後続の絵文字とZWJ列を作る場合もカーソルを境界に保つ`() {
        val buffer = EditableBuffer("👩💻", "👩".length)

        buffer.insert("‍")

        assertEquals("👩‍💻", buffer.text)
        assertEquals(buffer.text.length, buffer.cursor)
        assertTrue(buffer.backspace())
        assertEquals("", buffer.text)
    }

    @Test
    fun `homeとendは論理行の境界へ移動する`() {
        val buffer = EditableBuffer("あ\nか\u3099き", "あ\nか\u3099".length)

        assertTrue(buffer.moveHome())
        assertEquals("あ\n".length, buffer.cursor)
        assertTrue(buffer.moveEnd())
        assertEquals(buffer.text.length, buffer.cursor)
        assertFalse(buffer.moveEnd())
    }

    @Test(expected = IllegalArgumentException::class)
    fun `クラスタの途中を初期カーソルにはできない`() {
        EditableBuffer("😀", 1)
    }
}
