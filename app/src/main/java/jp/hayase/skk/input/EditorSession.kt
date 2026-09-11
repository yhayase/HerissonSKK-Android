package jp.hayase.skk.input

import android.view.inputmethod.InputConnection

/** 入力接続をセッションに固定し、後から別の入力欄へ出力しません。 */
class EditorSession(
    val generation: Long,
    private val connection: InputConnection,
    val protectedInput: Boolean,
    val learningAllowed: Boolean,
    initialStart: Int,
    initialEnd: Int,
) {
    val engine = PilotEngine().apply { reset(protectedInput) }
    var active = true
        private set
    var failed = false
        private set
    private var selectionStart = initialStart
    private var selectionEnd = initialEnd
    private var composingStart = -1
    private var composingEnd = -1
    private var hasEditorComposition = false
    private data class Selection(val start: Int, val end: Int, val composingStart: Int, val composingEnd: Int)
    private val expectedSelections = ArrayDeque<Selection>()

    fun handle(action: InputAction): Boolean {
        if (!active || protectedInput || failed) return false
        val result = engine.apply(action)
        if (!result.handled) return false
        connection.beginBatchEdit()
        try {
            result.commit?.let { text ->
                expectReplacement(text.length, composing = false)
                if (!connection.commitText(text, 1)) return fail()
                hasEditorComposition = false
            }
            val text = engine.composing
            if (text.isNotEmpty() || hasEditorComposition) {
                expectReplacement(text.length, composing = text.isNotEmpty())
                if (!connection.setComposingText(text, 1)) return fail()
                hasEditorComposition = text.isNotEmpty()
                if (text.isEmpty()) connection.finishComposingText()
            }
        } finally {
            connection.endBatchEdit()
        }
        return true
    }

    /** 将来の非同期検索も、この世代と読みが一致する場合だけ結果を採用します。 */
    fun acceptsResult(token: Long, reading: String): Boolean =
        active && !failed && token == generation && engine.composing == reading

    fun onSelection(start: Int, end: Int, candidatesStart: Int, candidatesEnd: Int): Boolean {
        if (!active || failed) return false
        val observed = Selection(start, end, candidatesStart, candidatesEnd)
        if (selectionStart < 0 && hasEditorComposition && candidatesStart >= 0 &&
            start == end && end == candidatesEnd && candidatesEnd - candidatesStart == engine.composing.length) {
            selectionStart = start
            selectionEnd = end
            composingStart = candidatesStart
            composingEnd = candidatesEnd
            return false
        }
        val index = expectedSelections.indexOf(observed)
        if (index >= 0) {
            repeat(index + 1) { expectedSelections.removeFirst() }
            return false
        }
        if (observed == Selection(selectionStart, selectionEnd, composingStart, composingEnd)) return false
        // 外部移動後は範囲を編集せず、既に表示された文字をその位置に残します。
        preserveText()
        selectionStart = start
        selectionEnd = end
        return true
    }

    fun preserveText() {
        if (!active) return
        val finish = hasEditorComposition || composingStart >= 0
        engine.clear()
        expectedSelections.clear()
        composingStart = -1
        composingEnd = -1
        hasEditorComposition = false
        if (finish) connection.finishComposingText()
    }

    fun close() {
        if (!active) return
        preserveText()
        active = false
    }

    private fun expectReplacement(length: Int, composing: Boolean) {
        val start = if (composingStart >= 0) composingStart else minOf(selectionStart, selectionEnd)
        if (start >= 0) {
            selectionStart = start + length
            selectionEnd = selectionStart
            composingStart = if (composing) start else -1
            composingEnd = if (composing) selectionEnd else -1
            expectedSelections.addLast(Selection(selectionStart, selectionEnd, composingStart, composingEnd))
            // 通知が来ない入力先でも無制限に履歴を保持しません。
            if (expectedSelections.size > 64) expectedSelections.removeFirst()
        }
    }

    private fun fail(): Boolean {
        failed = true
        preserveText()
        // 一部の出力が成功した可能性があるため、同じキーを再配送しません。
        return true
    }
}
