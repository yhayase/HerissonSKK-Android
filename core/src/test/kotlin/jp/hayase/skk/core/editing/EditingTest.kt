package jp.hayase.skk.core.editing

import org.junit.Assert.*
import org.junit.Test

class EditingTest {
    private fun plan(text: String, cursor: Int, command: EditCommand, anchor: Int = cursor,
        goal: Int? = null): EditPlan = (Editing.plan(
        EditSnapshot(text, anchor, cursor, "reading", 17), command, goal,
    ) as EditResult.Ready).plan

    @Test fun `文字移動と削除はUnicodeクラスタを分割しない`() {
        for (cluster in listOf("か\u3099", "👩🏽‍💻", "🇯🇵", "\u0915\u094D\u0937", "✈️")) {
            val text = "A${cluster}B"
            assertEquals(1 + cluster.length, plan(text, 1, EditCommand.RIGHT).cursor)
            assertEquals(1, plan(text, 1 + cluster.length, EditCommand.LEFT).cursor)
            assertEquals("AB", plan(text, 1, EditCommand.DELETE).text)
            assertEquals("AB", plan(text, 1 + cluster.length, EditCommand.BACKSPACE).text)
        }
    }

    @Test fun `削除による隣接クラスタの結合後も境界へ置く`() {
        val result = plan("🇯x🇵", 3, EditCommand.BACKSPACE)
        assertEquals("🇯🇵", result.text)
        assertEquals(4, result.cursor)
        assertEquals(DeletedRange(2, 3), result.deletedRange)
    }

    @Test fun `全論理改行の直前へ移動し行末でだけ改行を削除する`() {
        for (separator in listOf("\r\n", "\r", "\n", "\u2028", "\u2029")) {
            val text = "ab${separator}cd"
            assertEquals(2, plan(text, 0, EditCommand.END).cursor)
            assertEquals(2 + separator.length, plan(text, text.length, EditCommand.HOME).cursor)
            assertEquals("a${separator}cd", plan(text, 1, EditCommand.KILL_LINE).text)
            assertEquals("abcd", plan(text, 2, EditCommand.KILL_LINE).text)
            assertEquals(2 + separator.length, plan(text, 2, EditCommand.RIGHT).cursor)
        }
        assertFalse(plan("a\n", 2, EditCommand.KILL_LINE).changed)
        assertEquals(2, plan("a\n", 0, EditCommand.DOWN).cursor)
    }

    @Test fun `短い行を経由しても縦移動の目標クラスタ列を保持する`() {
        val text = "A👩🏽‍💻\tZ\nx\n日本語四"
        val initial = "A👩🏽‍💻\t".length
        val down = plan(text, initial, EditCommand.DOWN)
        assertEquals(text.indexOf("\nx") + 2, down.cursor)
        assertEquals(3, down.preferredColumn)
        val next = plan(text, down.cursor, EditCommand.DOWN, goal = down.preferredColumn)
        assertEquals(text.length - 1, next.cursor)
        assertEquals(3, next.preferredColumn)
        val up = plan(text, next.cursor, EditCommand.UP, goal = next.preferredColumn)
        assertEquals(down.cursor, up.cursor)
        assertNull(plan(text, up.cursor, EditCommand.HOME, goal = up.preferredColumn).preferredColumn)
        assertFalse(plan("abc", 1, EditCommand.UP).changed)
        assertFalse(plan("abc", 1, EditCommand.DOWN).changed)
    }

    @Test fun `選択は方向端へ畳み行操作はactive endを起点にする`() {
        val text = "abc\ndef"
        for ((anchor, active) in listOf(1 to 6, 6 to 1)) {
            for (command in listOf(EditCommand.LEFT, EditCommand.WORD_BACKWARD)) {
                assertEquals(1, plan(text, active, command, anchor).cursor)
            }
            for (command in listOf(EditCommand.RIGHT, EditCommand.WORD_FORWARD)) {
                assertEquals(6, plan(text, active, command, anchor).cursor)
            }
            for (command in listOf(EditCommand.BACKSPACE, EditCommand.DELETE, EditCommand.KILL_LINE)) {
                val result = plan(text, active, command, anchor)
                assertEquals("af", result.text)
                assertEquals(1, result.cursor)
            }
        }
        assertEquals(4, plan(text, 6, EditCommand.HOME, 1).cursor)
        assertEquals(3, plan(text, 1, EditCommand.END, 6).cursor)
    }

    @Test fun `単語はUnicode文字数字と下線の連続で区切る`() {
        val text = "日本語 abc_def-12"
        var cursor = 0
        for (expected in listOf(3, 11, 14)) {
            cursor = plan(text, cursor, EditCommand.WORD_FORWARD).cursor
            assertEquals(expected, cursor)
        }
        for (expected in listOf(12, 4, 0)) {
            cursor = plan(text, cursor, EditCommand.WORD_BACKWARD).cursor
            assertEquals(expected, cursor)
        }
        assertEquals(3, plan("a\u0301b😀", 0, EditCommand.WORD_FORWARD).cursor)
        assertEquals(4, plan("\u0301 Ⅻ½", 0, EditCommand.WORD_FORWARD).cursor)
        assertEquals(3, plan(" - ", 0, EditCommand.WORD_FORWARD).cursor)
        assertEquals(0, plan(" - ", 3, EditCommand.WORD_BACKWARD).cursor)
    }

    @Test fun `不正入力と上限超過を状態変更なしで拒否する`() {
        for ((text, cursor) in listOf("\uD800" to 0, "\uDC00" to 0, "😀" to 1, "a\r\nb" to 2,
            "か\u3099" to 1, "abc" to -1, "abc" to 4)) {
            assertTrue(Editing.plan(EditSnapshot(text, cursor), EditCommand.DELETE) is EditResult.Rejected)
        }
        assertTrue(Editing.plan(EditSnapshot("x".repeat(8193), 0), EditCommand.RIGHT,
            maxLength = Editing.EXTERNAL_LIMIT) is EditResult.Rejected)
        assertTrue(Editing.plan(EditSnapshot("x".repeat(65537), 0), EditCommand.RIGHT) is EditResult.Rejected)
        assertTrue(Editing.plan(EditSnapshot("", 0), EditCommand.UP, -1) is EditResult.Rejected)
        assertTrue(Editing.plan(EditSnapshot("", 0), EditCommand.RIGHT, maxLength = Int.MAX_VALUE) is EditResult.Rejected)
        for (command in EditCommand.entries) assertFalse(plan("", 0, command).changed)
    }

    @Test fun `計画は元の対象と版と選択を保持して入力を変更しない`() {
        val source = EditSnapshot("abc", 2, 1, "child-registration-2", 42)
        val result = (Editing.plan(source, EditCommand.DELETE) as EditResult.Ready).plan
        assertSame(source, result.original)
        assertEquals("abc", source.text)
        assertEquals("ac", result.text)
        assertEquals(DeletedRange(1, 2), result.deletedRange)
        assertNull(result.preferredColumn)
    }
}
