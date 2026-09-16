package jp.hayase.skk.input

import android.view.inputmethod.InputConnection
import jp.hayase.skk.core.BasicSkkAction
import jp.hayase.skk.core.BasicSkkDictionary
import jp.hayase.skk.core.BasicSkkEngine
import jp.hayase.skk.core.BasicSkkView
import jp.hayase.skk.core.DictionaryCandidate
import jp.hayase.skk.core.DictionaryQuery
import jp.hayase.skk.core.InputPhase

/** 入力接続をセッションに固定し、後から別の入力欄へ出力しません。 */
class EditorSession(
    val generation: Long,
    private val connection: InputConnection,
    val protectedInput: Boolean,
    val learningAllowed: Boolean,
    initialStart: Int,
    initialEnd: Int,
) {
    val engine = BasicSkkEngine(PHASE_TWO_DICTIONARY)
    var view = BasicSkkView(null, null, null)
        private set
    var notice: String? = null
        private set
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

    val displayedComposition: String
        get() = view.candidate?.committedText ?: view.composing.orEmpty()
    val hasComposition: Boolean
        get() = displayedComposition.isNotEmpty() || engine.state.phase != InputPhase.IDLE

    fun handle(action: BasicSkkAction): Boolean {
        if (!active || protectedInput || failed) return false
        val result = engine.dispatch(action)
        if (!result.handled) return false
        view = result.view
        notice = result.notice
        connection.beginBatchEdit()
        try {
            result.commit?.let { text ->
                expectReplacement(text.length, composing = false)
                if (!connection.commitText(text, 1)) return fail()
                hasEditorComposition = false
            }
            val text = displayedComposition
            if (text.isNotEmpty() || hasEditorComposition) {
                expectReplacement(text.length, composing = text.isNotEmpty())
                if (!connection.setComposingText(text, 1)) return fail()
                hasEditorComposition = text.isNotEmpty()
                if (text.isEmpty()) {
                    connection.finishComposingText()
                } else if (!placeInternalCursor(text)) {
                    return fail()
                }
            }
        } finally {
            connection.endBatchEdit()
        }
        return true
    }

    /** 将来の非同期検索も、この世代と読みが一致する場合だけ結果を採用します。 */
    fun acceptsResult(token: Long, reading: String): Boolean =
        active && !failed && token == generation && displayedComposition == reading

    fun onSelection(start: Int, end: Int, candidatesStart: Int, candidatesEnd: Int): Boolean {
        if (!active || failed) return false
        val observed = Selection(start, end, candidatesStart, candidatesEnd)
        if (selectionStart < 0 && hasEditorComposition && candidatesStart >= 0 &&
            start == end && end in candidatesStart..candidatesEnd &&
            candidatesEnd - candidatesStart == displayedComposition.length) {
            selectionStart = start
            selectionEnd = end
            composingStart = candidatesStart
            composingEnd = candidatesEnd
            return false
        }
        val index = expectedSelections.indexOf(observed)
        if (index >= 0) {
            val hasNewerExpectedSelection = index < expectedSelections.lastIndex
            repeat(index + 1) { expectedSelections.removeFirst() }
            if (!hasNewerExpectedSelection) {
                selectionStart = start
                selectionEnd = end
                composingStart = candidatesStart
                composingEnd = candidatesEnd
            }
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
        clearCoreComposition()
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

    private fun placeInternalCursor(text: String): Boolean {
        val requested = if (view.candidate != null) text.length else view.cursor ?: text.length
        if (requested !in 0..text.length) return false
        if (requested == text.length || composingStart < 0 || composingEnd < composingStart) return true
        val absolute = composingStart + requested
        if (absolute !in composingStart..composingEnd) return false
        selectionStart = absolute
        selectionEnd = absolute
        expectedSelections.addLast(Selection(absolute, absolute, composingStart, composingEnd))
        trimExpectedSelections()
        return connection.setSelection(absolute, absolute)
    }

    private fun clearCoreComposition() {
        repeat(3) {
            val state = engine.state
            if (state.phase != InputPhase.IDLE || state.pendingRomaji.isNotEmpty()) {
                view = engine.dispatch(BasicSkkAction.Cancel).view
            }
        }
        notice = null
    }

    private fun expectReplacement(length: Int, composing: Boolean) {
        val start = if (composingStart >= 0) composingStart else minOf(selectionStart, selectionEnd)
        if (start >= 0) {
            selectionStart = start + length
            selectionEnd = selectionStart
            composingStart = if (composing) start else -1
            composingEnd = if (composing) selectionStart else -1
            expectedSelections.addLast(Selection(selectionStart, selectionEnd, composingStart, composingEnd))
            trimExpectedSelections()
        }
    }

    private fun trimExpectedSelections() {
        // 通知が来ない入力先でも無制限に履歴を保持しません。
        while (expectedSelections.size > 64) expectedSelections.removeFirst()
    }

    private fun fail(): Boolean {
        failed = true
        preserveText()
        // 一部の出力が成功した可能性があるため、同じキーを再配送しません。
        return true
    }

    private companion object {
        val PHASE_TWO_DICTIONARY = BasicSkkDictionary { query ->
            when (query) {
                DictionaryQuery("にほん") -> listOf(
                    DictionaryCandidate("日本", "国名・限定試験辞書"),
                    DictionaryCandidate("二本", "本数・限定試験辞書"),
                )
                DictionaryQuery("かk", "く") -> listOf(DictionaryCandidate("書", okuriCondition = "く"))
                DictionaryQuery("API", abbrev = true) -> listOf(DictionaryCandidate("エーピーアイ"))
                DictionaryQuery("だい>") -> listOf(DictionaryCandidate("第"))
                DictionaryQuery(">かい") -> listOf(DictionaryCandidate("回"))
                DictionaryQuery("てすと") -> (1..10).map { index ->
                    DictionaryCandidate("候補$index", "注釈$index")
                }
                else -> emptyList()
            }
        }
    }
}
