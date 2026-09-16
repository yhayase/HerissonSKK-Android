package jp.hayase.skk.input

import android.os.Build
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.ExtractedText
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.SurroundingText
import jp.hayase.skk.core.editing.EditCommand
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicReference

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 35])
class EditorEditPortTest {
    private class Queue : Executor {
        val tasks = ArrayDeque<Runnable>()
        override fun execute(command: Runnable) { tasks.addLast(command) }
        fun run() { while (tasks.isNotEmpty()) tasks.removeFirst().run() }
    }
    private class Scheduler : EditScheduler {
        val tasks = ArrayList<Runnable>()
        override fun schedule(delayMillis: Long, task: Runnable): EditCancellation {
            tasks.add(task)
            return EditCancellation { tasks.remove(task) }
        }
        fun fire() { tasks.toList().forEach { it.run() } }
    }
    private class Connection(var text: String, var start: Int, var end: Int = start) :
        BaseInputConnection(View(RuntimeEnvironment.getApplication()), true) {
        var offset = 0
        var queries = 0
        var edits = 0
        var deleteCalls = 0
        var commitCalls = 0
        var selectionCalls = 0
        var unavailable = false
        var partial = false
        var accept = true
        var ignoreEdit = false
        var throws = false
        var onQuery: ((Int) -> Unit)? = null
        var onEdit: (() -> Unit)? = null
        private fun query() { queries++; onQuery?.invoke(queries) }
        override fun getSurroundingText(beforeLength: Int, afterLength: Int, flags: Int): SurroundingText? {
            query()
            return if (unavailable) null else SurroundingText(text, start, end, offset)
        }
        override fun getExtractedText(request: ExtractedTextRequest?, flags: Int): ExtractedText? {
            query()
            return if (unavailable) null else ExtractedText().also {
                it.text = text
                it.startOffset = offset
                it.selectionStart = start
                it.selectionEnd = end
                it.partialStartOffset = if (partial) 0 else -1
            }
        }
        private fun edit(): Boolean {
            edits++
            onEdit?.invoke()
            if (throws) throw IllegalStateException("試験用の接続失敗")
            return accept && !ignoreEdit
        }
        override fun setSelection(start: Int, end: Int): Boolean {
            selectionCalls++
            if (edit()) { this.start = start - offset; this.end = end - offset }
            return accept
        }
        override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
            deleteCalls++
            if (edit()) {
                val begin = start - beforeLength
                text = text.removeRange(begin, end + afterLength)
                start = begin
                end = begin
            }
            return accept
        }
        override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
            commitCalls++
            if (edit()) {
                val begin = minOf(start, end)
                this.text = this.text.removeRange(begin, maxOf(start, end))
                start = begin
                end = begin
            }
            return accept
        }
    }
    private class Harness(text: String, cursor: Int, anchor: Int = cursor) {
        val connection = Connection(text, anchor, cursor)
        val state = AtomicReference(EditorEditState(true, 7, anchor, cursor, 0))
        val worker = Queue()
        val callbacks = Queue()
        val timer = Scheduler()
        var now = 0L
        val port = EditorEditPort(connection, 7, state::get, callbacks, worker, timer, { now })
        val results = ArrayList<EditorEditResult>()
        fun submit(command: EditCommand) { port.submit(command) { results.add(it) } }
        fun run(command: EditCommand): EditorEditResult {
            submit(command)
            worker.run()
            callbacks.run()
            return results.last()
        }
        fun update(block: (EditorEditState) -> EditorEditState) { state.set(block(state.get())) }
    }

    @Test fun `通常完了の即時コールバックから次の要求を受け付ける`() {
        val connection = Connection("abcdef\n", 3)
        val state = AtomicReference(EditorEditState(true, 7, 3, 3, 0))
        val worker = Queue()
        val results = ArrayList<EditorEditResult>()
        val port = EditorEditPort(connection, 7, state::get, Executor { it.run() }, worker,
            Scheduler(), { 0L })
        port.submit(EditCommand.LEFT) { first ->
            results.add(first)
            state.set(state.get().copy(selectionStart = 2, selectionEnd = 2))
            port.submit(EditCommand.LEFT) { results.add(it) }
        }
        worker.run()
        assertEquals(listOf(EditorEditResult.Outcome.APPLIED, EditorEditResult.Outcome.APPLIED),
            results.map { it.outcome })
        assertEquals(1, connection.start)
        assertEquals(2, connection.edits)
    }

    @Test fun `既知の文書先頭からの行頭移動は末尾証拠を必要としない`() {
        val h = Harness("abc", 3)
        assertEquals(EditorEditResult.Outcome.APPLIED, h.run(EditCommand.HOME).outcome)
        assertEquals(0, h.connection.start)
        assertEquals(1, h.connection.edits)
        assertEquals(3, h.connection.queries)
    }

    @Test fun `途中の左右移動は後続行の全体を要求しない`() {
        for ((command, expected) in listOf(EditCommand.LEFT to 0, EditCommand.RIGHT to 2)) {
            val h = Harness("abcdef", 1)
            val result = h.run(command)
            assertEquals(EditorEditResult.Outcome.APPLIED, result.outcome)
            assertEquals(expected, result.expectedSelectionStart)
        }
    }

    @Test fun `Ckは確認できた行末までを一度だけ削除する`() {
        val h = Harness("abc\r\nrest", 1)
        assertEquals(EditorEditResult.Outcome.APPLIED, h.run(EditCommand.KILL_LINE).outcome)
        assertEquals("a\r\nrest", h.connection.text)
        assertEquals(1, h.connection.deleteCalls)
        assertEquals(0, h.connection.commitCalls)
        val newline = Harness("a\r\nrest", 1)
        assertEquals(EditorEditResult.Outcome.APPLIED, newline.run(EditCommand.KILL_LINE).outcome)
        assertEquals("arest", newline.connection.text)
    }

    @Test fun `前後削除は一つの完全なUnicodeクラスタだけを削除する`() {
        for (cluster in listOf("か\u3099", "👩🏽‍💻", "🇯🇵")) {
            for (command in listOf(EditCommand.BACKSPACE, EditCommand.DELETE)) {
                val h = Harness("A${cluster}BC", if (command == EditCommand.BACKSPACE) 1 + cluster.length else 1)
                assertEquals(EditorEditResult.Outcome.APPLIED, h.run(command).outcome)
                assertEquals("ABC", h.connection.text)
                assertEquals(1, h.connection.deleteCalls)
            }
        }
    }

    @Test fun `既存の逆向き選択は追加の選択要求なしに一度だけ置換する`() {
        for (command in listOf(EditCommand.BACKSPACE, EditCommand.DELETE, EditCommand.KILL_LINE)) {
            val h = Harness("abcdef", 1, 4)
            assertEquals(EditorEditResult.Outcome.APPLIED, h.run(command).outcome)
            assertEquals("aef", h.connection.text)
            assertEquals(1, h.connection.commitCalls)
            assertEquals(0, h.connection.selectionCalls)
            assertEquals(0, h.connection.deleteCalls)
        }
    }

    @Test fun `削除で前後が結合してカーソル補正が必要なら変更しない`() {
        val h = Harness("🇯x🇵rest", 3)
        assertEquals(EditorEditResult.Outcome.UNSUPPORTED, h.run(EditCommand.BACKSPACE).outcome)
        assertEquals(0, h.connection.edits)
    }

    @Test fun `取得窓末尾を行末や文書末尾として削除しない`() {
        for (command in listOf(EditCommand.END, EditCommand.KILL_LINE, EditCommand.DOWN, EditCommand.WORD_FORWARD)) {
            val h = Harness("abc", 1)
            assertEquals(command.toString(), EditorEditResult.Outcome.UNSUPPORTED, h.run(command).outcome)
            assertEquals(0, h.connection.edits)
        }
        val h = Harness("ab\r", 2)
        assertEquals(EditorEditResult.Outcome.UNSUPPORTED, h.run(EditCommand.KILL_LINE).outcome)
    }

    @Test fun `非ゼロoffsetは窓内で確認できた改行からだけ分節する`() {
        val h = Harness("xx\nabcde", 5)
        h.connection.offset = 100
        h.update { it.copy(selectionStart = 105, selectionEnd = 105) }
        assertEquals(EditorEditResult.Outcome.APPLIED, h.run(EditCommand.HOME).outcome)
        assertEquals(3, h.connection.start)
        val unknown = Harness("abcdef", 3)
        unknown.connection.offset = 100
        unknown.update { it.copy(selectionStart = 103, selectionEnd = 103) }
        assertEquals(EditorEditResult.Outcome.UNSUPPORTED, unknown.run(EditCommand.LEFT).outcome)
        assertEquals(0, unknown.connection.edits)
    }

    @Test fun `nullと不明offsetと差分と過大応答を拒否する`() {
        val unavailable = Harness("abc", 1)
        unavailable.connection.unavailable = true
        assertEquals(EditorEditResult.Outcome.UNSUPPORTED, unavailable.run(EditCommand.HOME).outcome)
        val unknown = Harness("abc", 1)
        unknown.connection.offset = -1
        assertEquals(EditorEditResult.Outcome.UNSUPPORTED, unknown.run(EditCommand.HOME).outcome)
        val big = Harness("a".repeat(8193), 1)
        assertEquals(EditorEditResult.Outcome.UNSUPPORTED, big.run(EditCommand.HOME).outcome)
        if (Build.VERSION.SDK_INT < 31) {
            val partial = Harness("abc", 1)
            partial.connection.partial = true
            assertEquals(EditorEditResult.Outcome.UNSUPPORTED, partial.run(EditCommand.HOME).outcome)
        }
    }

    @Test fun `選択不一致とクラスタ途中の位置は変更しない`() {
        val mismatch = Harness("abc", 1)
        mismatch.update { it.copy(selectionStart = 2, selectionEnd = 2) }
        assertEquals(EditorEditResult.Outcome.UNSUPPORTED, mismatch.run(EditCommand.HOME).outcome)
        val cluster = Harness("😀rest", 1)
        assertEquals(EditorEditResult.Outcome.UNSUPPORTED, cluster.run(EditCommand.DELETE).outcome)
    }

    @Test fun `二回の取得間で本文や選択が変われば古い計画を送らない`() {
        val text = Harness("abc\nrest", 1)
        text.connection.onQuery = { count -> if (count == 2) text.connection.text = "xyz\nrest" }
        assertEquals(EditorEditResult.Outcome.STALE, text.run(EditCommand.KILL_LINE).outcome)
        assertEquals(0, text.connection.edits)
        val selection = Harness("abc", 1)
        selection.connection.onQuery = { count -> if (count == 2) selection.update { it.copy(revision = 1) } }
        assertEquals(EditorEditResult.Outcome.STALE, selection.run(EditCommand.HOME).outcome)
        assertEquals(0, selection.connection.edits)
    }

    @Test fun `falseと例外と成功応答後の不一致でも別の削除を再試行しない`() {
        for (mode in 0..2) {
            val h = Harness("abc\nrest", 1)
            when (mode) {
                0 -> h.connection.accept = false
                1 -> h.connection.throws = true
                2 -> h.connection.ignoreEdit = true
            }
            assertEquals(EditorEditResult.Outcome.FAILED_OR_UNKNOWN, h.run(EditCommand.KILL_LINE).outcome)
            assertEquals(1, h.connection.edits)
            assertEquals(EditorEditResult.Outcome.STALE, h.run(EditCommand.KILL_LINE).outcome)
            assertEquals(1, h.connection.edits)
        }
    }

    @Test fun `取得中に期限切れなら一度だけ通知して遅延結果で変更しない`() {
        val h = Harness("abc", 1)
        h.connection.onQuery = { if (it == 1) { h.now = 501; h.timer.fire() } }
        assertEquals(EditorEditResult.Reason.TIMEOUT, h.run(EditCommand.HOME).reason)
        assertEquals(1, h.results.size)
        assertEquals(0, h.connection.edits)
    }

    @Test fun `保護入力と内部未確定と世代変更では取得しない`() {
        for (mode in 0..2) {
            val h = Harness("abc", 1)
            h.update { when (mode) {
                0 -> it.copy(protectedInput = true)
                1 -> it.copy(hasComposition = true)
                else -> it.copy(generation = 8)
            } }
            assertEquals(EditorEditResult.Outcome.STALE, h.run(EditCommand.HOME).outcome)
            assertEquals(0, h.connection.queries)
        }
    }

    @Test fun `一件処理中の追加要求と終了後の旧要求は送信しない`() {
        val h = Harness("abc", 1)
        h.submit(EditCommand.HOME)
        h.submit(EditCommand.HOME)
        h.callbacks.run()
        assertEquals(EditorEditResult.Outcome.BUSY, h.results.single().outcome)
        h.port.close()
        h.worker.run()
        h.callbacks.run()
        assertEquals(2, h.results.size)
        assertEquals(EditorEditResult.Outcome.STALE, h.results.last().outcome)
        assertEquals(0, h.connection.queries)
    }
    @Test fun `縦移動は短い行を経由して目標列を保持する`() {
        val h = Harness("abcd\nx\nABCD\nrest", 3)
        val first = h.run(EditCommand.DOWN)
        assertEquals(EditorEditResult.Outcome.APPLIED, first.outcome)
        assertEquals(6, first.expectedSelectionStart)
        h.update { it.copy(selectionStart = 6, selectionEnd = 6) }
        val second = h.run(EditCommand.DOWN)
        assertEquals(EditorEditResult.Outcome.APPLIED, second.outcome)
        assertEquals(10, second.expectedSelectionStart)
    }

    @Test fun `編集応答中に接続世代が変われば事後確認を別欄へ送らない`() {
        val h = Harness("abc\nrest", 1)
        h.connection.onEdit = { h.update { it.copy(generation = 8) } }
        assertEquals(EditorEditResult.Outcome.STALE, h.run(EditCommand.KILL_LINE).outcome)
        assertEquals(2, h.connection.queries)
        assertEquals(1, h.connection.edits)
    }

    @Test fun `取得後の不正UTF16と絶対位置の加算超過を拒否する`() {
        val malformed = Harness("a\uD800b", 1)
        assertEquals(EditorEditResult.Outcome.UNSUPPORTED, malformed.run(EditCommand.DELETE).outcome)
        assertEquals(0, malformed.connection.edits)
        val overflow = Harness("abc", 1)
        overflow.connection.offset = Int.MAX_VALUE - 1
        overflow.update { it.copy(selectionStart = Int.MAX_VALUE, selectionEnd = Int.MAX_VALUE) }
        assertEquals(EditorEditResult.Outcome.UNSUPPORTED, overflow.run(EditCommand.HOME).outcome)
        assertEquals(0, overflow.connection.edits)
    }

    @Test fun `送信後の予期しない選択と世代内変更は編集ポートを停止する`() {
        for (changedRevision in listOf(false, true)) {
            val h = Harness("abc\nrest", 1)
            h.connection.onEdit = {
                h.update { it.copy(selectionStart = if (changedRevision) 0 else 2,
                    selectionEnd = if (changedRevision) 0 else 2,
                    revision = if (changedRevision) 1 else 0) }
            }
            assertEquals(EditorEditResult.Outcome.FAILED_OR_UNKNOWN, h.run(EditCommand.HOME).outcome)
            assertEquals(EditorEditResult.Reason.POSTCHECK_MISMATCH, h.results.last().reason)
            val queries = h.connection.queries
            assertEquals(EditorEditResult.Outcome.STALE, h.run(EditCommand.HOME).outcome)
            assertEquals(queries, h.connection.queries)
            assertEquals(1, h.connection.edits)
        }
    }

    @Test fun `未取得LFと結合しうるCR末尾への行頭移動を拒否する`() {
        for ((cursor, anchor) in listOf(3 to 1, 3 to 3, 1 to 3)) {
            val h = Harness("ab\r", cursor, anchor)
            assertEquals(EditorEditResult.Outcome.UNSUPPORTED, h.run(EditCommand.HOME).outcome)
            assertEquals(0, h.connection.edits)
        }
        val safe = Harness("ab\r", 1)
        assertEquals(EditorEditResult.Outcome.APPLIED, safe.run(EditCommand.HOME).outcome)
        assertEquals(0, safe.connection.start)
    }

}
