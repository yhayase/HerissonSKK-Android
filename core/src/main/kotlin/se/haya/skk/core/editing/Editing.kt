package se.haya.skk.core.editing

import com.ibm.icu.lang.UCharacter
import com.ibm.icu.lang.UCharacterCategory
import com.ibm.icu.text.BreakIterator
import com.ibm.icu.util.ULocale

/** SKK の状態別優先順位を適用した後に渡す編集操作です。 */
enum class EditCommand {
    HOME, END, LEFT, RIGHT, UP, DOWN, BACKSPACE, DELETE, KILL_LINE, WORD_BACKWARD, WORD_FORWARD,
    PAGE_DOWN, PAGE_UP, BUFFER_START, BUFFER_END, CUT, COPY, PASTE, UNDO, NEWLINE,
}

/** 完全な編集対象です。外部取得窓の完全性・世代確認は呼び出し元の責務です。 */
data class EditSnapshot(
    val text: String,
    val selectionStart: Int,
    val selectionEnd: Int = selectionStart,
    val targetId: String = "",
    val revision: Long = 0,
)

/** UTF-16 の半開区間です。 */
data class DeletedRange(val start: Int, val end: Int)

/** 適用前に original の対象・版・本文・選択との一致を呼び出し元で確認します。 */
data class EditPlan(
    val original: EditSnapshot,
    val text: String,
    val cursor: Int,
    val deletedRange: DeletedRange?,
    val preferredColumn: Int?,
) {
    val changed: Boolean get() = text != original.text ||
        cursor != original.selectionStart || cursor != original.selectionEnd
}

sealed interface EditResult {
    data class Ready(val plan: EditPlan) : EditResult
    data class Rejected(val reason: Reason) : EditResult
    enum class Reason { LIMIT, INVALID_UTF16, INVALID_SELECTION, INVALID_COLUMN }
}

/** Android に依存せず、入力を変更しない編集計画を作ります。 */
object Editing {
    const val INTERNAL_LIMIT = 65_536
    const val EXTERNAL_LIMIT = 8_192

    fun plan(
        snapshot: EditSnapshot,
        command: EditCommand,
        preferredColumn: Int? = null,
        maxLength: Int = INTERNAL_LIMIT,
    ): EditResult {
        val text = snapshot.text
        if (maxLength !in 0..INTERNAL_LIMIT || text.length > maxLength) return reject(EditResult.Reason.LIMIT)
        if (!validUtf16(text)) return reject(EditResult.Reason.INVALID_UTF16)
        if (preferredColumn != null && preferredColumn !in 0..INTERNAL_LIMIT) return reject(EditResult.Reason.INVALID_COLUMN)
        val boundaries = boundaries(text)
        val anchor = snapshot.selectionStart
        val cursor = snapshot.selectionEnd
        if (anchor !in 0..text.length || cursor !in 0..text.length ||
            boundaries.binarySearch(anchor) < 0 || boundaries.binarySearch(cursor) < 0) {
            return reject(EditResult.Reason.INVALID_SELECTION)
        }
        val lines = lines(text, boundaries)
        val lineIndex = lines.indexOfLast { it.start <= cursor }
        val line = lines[lineIndex]
        val selected = anchor != cursor
        val low = minOf(anchor, cursor)
        val high = maxOf(anchor, cursor)
        var destination = cursor
        var removed: DeletedRange? = null
        var goal: Int? = null
        val index = boundaries.binarySearch(cursor)
        when (command) {
            EditCommand.LEFT -> destination = if (selected) low else boundaries[(index - 1).coerceAtLeast(0)]
            EditCommand.RIGHT -> destination = if (selected) high else boundaries[(index + 1).coerceAtMost(boundaries.lastIndex)]
            EditCommand.HOME -> destination = line.start
            EditCommand.END -> destination = line.end
            EditCommand.UP, EditCommand.DOWN -> {
                goal = preferredColumn ?: (index - boundaries.binarySearch(line.start))
                val next = lineIndex + if (command == EditCommand.UP) -1 else 1
                if (next in lines.indices) {
                    val target = lines[next]
                    val first = boundaries.binarySearch(target.start)
                    val last = boundaries.binarySearch(target.end)
                    destination = boundaries[first + goal.coerceAtMost(last - first)]
                }
            }
            EditCommand.BACKSPACE -> removed = if (selected) DeletedRange(low, high)
                else DeletedRange(boundaries[(index - 1).coerceAtLeast(0)], cursor)
            EditCommand.DELETE -> removed = if (selected) DeletedRange(low, high)
                else DeletedRange(cursor, boundaries[(index + 1).coerceAtMost(boundaries.lastIndex)])
            EditCommand.KILL_LINE -> removed = if (selected) DeletedRange(low, high)
                else DeletedRange(cursor, if (cursor < line.end) line.end else line.after)
            EditCommand.WORD_FORWARD -> {
                if (selected) destination = high else {
                    var next = index
                    while (next < boundaries.lastIndex && !word(text, boundaries[next])) next++
                    while (next < boundaries.lastIndex && word(text, boundaries[next])) next++
                    destination = boundaries[next]
                }
            }
            EditCommand.WORD_BACKWARD -> {
                if (selected) destination = low else {
                    var next = index
                    while (next > 0 && !word(text, boundaries[next - 1])) next--
                    while (next > 0 && word(text, boundaries[next - 1])) next--
                    destination = boundaries[next]
                }
            }
            EditCommand.BUFFER_START -> destination = 0
            EditCommand.BUFFER_END -> destination = text.length
            // 表示領域の高さと選択範囲は内部バッファに存在しません。
            EditCommand.PAGE_DOWN, EditCommand.PAGE_UP, EditCommand.CUT, EditCommand.COPY,
            EditCommand.PASTE, EditCommand.UNDO,
            EditCommand.NEWLINE -> Unit
        }
        removed = removed?.takeIf { it.start != it.end }
        val resultText = removed?.let { text.removeRange(it.start, it.end) } ?: text
        if (removed != null) {
            // 削除で両側が結合する場合は新しいクラスタの末尾へ置きます。
            val after = boundaries(resultText)
            val found = after.binarySearch(removed.start)
            destination = after[if (found >= 0) found else -found - 1]
        }
        return EditResult.Ready(EditPlan(snapshot, resultText, destination, removed, goal))
    }

    private fun reject(reason: EditResult.Reason) = EditResult.Rejected(reason)

    private fun boundaries(text: String): IntArray {
        val iterator = BreakIterator.getCharacterInstance(ULocale.ROOT).apply { setText(text) }
        val result = ArrayList<Int>()
        var offset = iterator.first()
        while (offset != BreakIterator.DONE) {
            result.add(offset)
            offset = iterator.next()
        }
        return result.toIntArray()
    }

    private data class Line(val start: Int, val end: Int, val after: Int)

    private fun lines(text: String, boundaries: IntArray): List<Line> {
        val result = ArrayList<Line>()
        var start = 0
        for (index in 0 until boundaries.lastIndex) {
            val offset = boundaries[index]
            if (text[offset] in "\r\n\u2028\u2029") {
                val after = boundaries[index + 1]
                result.add(Line(start, offset, after))
                start = after
            }
        }
        result.add(Line(start, text.length, text.length))
        return result
    }

    private fun word(text: String, offset: Int): Boolean {
        val codePoint = text.codePointAt(offset)
        return codePoint == '_'.code || UCharacter.isLetter(codePoint) ||
            when (UCharacter.getType(codePoint)) {
                UCharacterCategory.DECIMAL_DIGIT_NUMBER.toInt(),
                UCharacterCategory.LETTER_NUMBER.toInt(),
                UCharacterCategory.OTHER_NUMBER.toInt() -> true
                else -> false
            }
    }

    private fun validUtf16(text: String): Boolean {
        var offset = 0
        while (offset < text.length) {
            val current = text[offset++]
            if (current.isLowSurrogate()) return false
            if (current.isHighSurrogate() && (offset == text.length || !text[offset++].isLowSurrogate())) return false
        }
        return true
    }
}
