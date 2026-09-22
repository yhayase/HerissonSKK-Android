package se.haya.skk.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GraphemeConformanceTest {
    /** Unicode 16 の公式期待値を使い、実装から期待する境界を逆算しません。 */
    @Test fun `Unicode16公式書記素境界ケースを前後へ移動する`() {
        val source = requireNotNull(javaClass.getResourceAsStream("/unicode/16.0.0/GraphemeBreakTest.txt"))
        var cases = 0
        source.bufferedReader(Charsets.UTF_8).useLines { lines ->
            lines.forEachIndexed { lineNumber, line ->
                val body = line.substringBefore('#').trim()
                if (body.isNotEmpty()) {
                    val text = StringBuilder()
                    val expected = mutableListOf<Int>()
                    body.split(Regex("\\s+")).forEach { token ->
                        when (token) {
                            "÷" -> expected += text.length
                            "×" -> Unit
                            else -> text.appendCodePoint(token.toInt(16))
                        }
                    }
                    val buffer = EditableBuffer(text.toString(), 0)
                    val actual = mutableListOf(0)
                    while (buffer.moveRight()) actual += buffer.cursor
                    assertEquals("GraphemeBreakTest.txt:${lineNumber + 1}", expected, actual)
                    for (offset in expected.dropLast(1).asReversed()) {
                        assertTrue(buffer.moveLeft())
                        assertEquals(offset, buffer.cursor)
                    }
                    assertFalse(buffer.moveLeft())
                    cases++
                }
            }
        }
        assertEquals("固定した Unicode 16 の公式ケース数", 1093, cases)
    }
}
