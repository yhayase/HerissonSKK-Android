package se.haya.skk.settings

import se.haya.skk.core.keys.*
import org.junit.Assert.*
import org.junit.Test

class KeyGestureTextTest {
    @Test fun `全標準割当と空白と記号の表記は情報を失わず往復する`() {
        val values = KeyBindings.defaults.values + listOf(KeyGesture("-"), KeyGesture("<"),
            KeyGesture("😀"), KeyGesture(" ", ctrl = true), KeyGesture(special = SpecialKey.ENTER, ctrl = true))
        values.forEach { assertEquals(it, KeyGestureText.parse(KeyGestureText.format(it))) }
        assertEquals(KeyGesture("Q", ignoreShift = true), KeyGestureText.parse("U-Q"))
        assertEquals(KeyGesture("Q", shift = true), KeyGestureText.parse("S-Q"))
    }

    @Test fun `不明な特殊キーと重複修飾と無効な組を拒否する`() {
        for (text in listOf("", "C-C-j", "C-U-j", "S-U-Q", "U-<ENTER>", "<UNKNOWN>", "ka", "C-")) {
            assertThrows(text, IllegalArgumentException::class.java) { KeyGestureText.parse(text) }
        }
    }
}
