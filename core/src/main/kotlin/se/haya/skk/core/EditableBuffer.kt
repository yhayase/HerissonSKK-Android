package se.haya.skk.core

import com.ibm.icu.text.BreakIterator
import com.ibm.icu.util.ULocale

/**
 * 未確定の読みや登録本文だけを保持する編集バッファです。
 *
 * [cursor] は UTF-16 の位置ですが、常に拡張書記素クラスタの境界を指します。入力先の文字列は
 * 保持も変更もしません。
 */
class EditableBuffer(
    initialText: String = "",
    initialCursor: Int = initialText.length,
) {
    var text: String = initialText
        private set

    var cursor: Int = initialCursor
        private set

    init {
        requireValidUtf16(text)
        require(cursor in 0..text.length && GraphemeBoundary.isBoundary(text, cursor)) {
            "カーソルは拡張書記素クラスタの境界である必要があります"
        }
    }

    /** カーソル位置へ挿入し、挿入後のクラスタ末尾へカーソルを置きます。 */
    fun insert(value: String) {
        requireValidUtf16(value)
        if (value.isEmpty()) return

        val insertedEnd = cursor + value.length
        text = text.substring(0, cursor) + value + text.substring(cursor)
        cursor = GraphemeBoundary.nextOrSame(text, insertedEnd)
    }

    /** カーソル直前の一つのクラスタを削除します。 */
    fun backspace(): Boolean {
        if (cursor == 0) return false

        val start = GraphemeBoundary.previous(text, cursor)
        text = text.removeRange(start, cursor)
        cursor = GraphemeBoundary.nextOrSame(text, start)
        return true
    }

    /** カーソル直後の一つのクラスタを削除します。 */
    fun delete(): Boolean {
        if (cursor == text.length) return false

        val end = GraphemeBoundary.next(text, cursor)
        text = text.removeRange(cursor, end)
        cursor = GraphemeBoundary.nextOrSame(text, cursor)
        return true
    }

    fun moveLeft(): Boolean {
        if (cursor == 0) return false
        cursor = GraphemeBoundary.previous(text, cursor)
        return true
    }

    fun moveRight(): Boolean {
        if (cursor == text.length) return false
        cursor = GraphemeBoundary.next(text, cursor)
        return true
    }

    /** 現在の論理行の先頭へ移動します。 */
    fun moveHome(): Boolean {
        var start = cursor
        while (start > 0 && text[start - 1] !in "\r\n") start--
        return moveTo(start)
    }

    /** 現在の論理行の末尾へ移動します。 */
    fun moveEnd(): Boolean {
        var end = cursor
        while (end < text.length && text[end] !in "\r\n") end++
        return moveTo(end)
    }

    private fun moveTo(destination: Int): Boolean {
        check(GraphemeBoundary.isBoundary(text, destination))
        if (cursor == destination) return false
        cursor = destination
        return true
    }
}

/** ICU4J 77.1 / Unicode 16 の同じ境界判定を JVM と Android で利用します。 */
internal object GraphemeBoundary {
    private fun iterator(value: String): BreakIterator =
        BreakIterator.getCharacterInstance(ULocale.ROOT).apply { setText(value) }

    fun isBoundary(value: String, offset: Int): Boolean = iterator(value).isBoundary(offset)
    fun previous(value: String, offset: Int): Int = iterator(value).preceding(offset).also {
        check(it != BreakIterator.DONE)
    }
    fun next(value: String, offset: Int): Int = iterator(value).following(offset).also {
        check(it != BreakIterator.DONE)
    }
    fun nextOrSame(value: String, offset: Int): Int {
        val boundaries = iterator(value)
        return if (boundaries.isBoundary(offset)) offset else boundaries.following(offset).also {
            check(it != BreakIterator.DONE)
        }
    }
}

/** 壊れた UTF-16 は状態を書き換える前に拒否します。 */
private fun requireValidUtf16(value: String) {
    var index = 0
    while (index < value.length) {
        val c = value[index]
        require(!Character.isLowSurrogate(c)) { "対応する上位サロゲートがありません" }
        if (Character.isHighSurrogate(c)) {
            require(index + 1 < value.length && Character.isLowSurrogate(value[index + 1])) {
                "対応する下位サロゲートがありません"
            }
            index++
        }
        index++
    }
}
