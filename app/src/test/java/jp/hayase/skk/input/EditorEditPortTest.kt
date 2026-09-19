package jp.hayase.skk.input

import android.os.Build
import android.view.KeyEvent
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
        var surroundingUnavailable = false
        var extractedUnavailable = false
        var surroundingOffset: Int? = null
        val nativeCodes = ArrayList<Int>()
        val clearedMeta = ArrayList<Int>()
        var onClearMeta: (() -> Unit)? = null
        var offset = 0
        var queries = 0
        var edits = 0
        var deleteCalls = 0
        var commitCalls = 0
        var selectionCalls = 0
        var clipboardActions = 0
        var nativeDown = 0
        var windowed = false
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
            if (unavailable || surroundingUnavailable) return null
            if (!windowed) return SurroundingText(text, start, end, surroundingOffset ?: offset)
            val begin = (minOf(start, end) - beforeLength).coerceAtLeast(0)
            val finish = (maxOf(start, end) + afterLength).coerceAtMost(text.length)
            return SurroundingText(text.substring(begin, finish), start - begin, end - begin, begin)
        }
        override fun clearMetaKeyStates(states: Int): Boolean {
            clearedMeta.add(states)
            onClearMeta?.invoke()
            return true
        }
        override fun sendKeyEvent(event: KeyEvent): Boolean {
            if (event.action == KeyEvent.ACTION_DOWN) {
                nativeDown++
                nativeCodes.add(event.keyCode)
                val next = when (event.keyCode) {
                    KeyEvent.KEYCODE_DEL -> {
                        if (end > 0) text = text.removeRange(end - 1, end)
                        (end - 1).coerceAtLeast(0)
                    }
                    KeyEvent.KEYCODE_DPAD_LEFT -> (end - 1).coerceAtLeast(0)
                    KeyEvent.KEYCODE_DPAD_RIGHT -> (end + 1).coerceAtMost(text.length)
                    else -> end
                }
                start = next
                end = next
            }
            return true
        }
        override fun getExtractedText(request: ExtractedTextRequest?, flags: Int): ExtractedText? {
            query()
            return if (unavailable || extractedUnavailable) null else ExtractedText().also {
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
                val inserted = text?.toString().orEmpty()
                this.text = this.text.replaceRange(begin, maxOf(start, end), inserted)
                start = begin + inserted.length
                end = start
            }
            return accept
        }
        override fun performContextMenuAction(id: Int): Boolean {
            clipboardActions++
            if (edit() && id == android.R.id.cut) {
                val begin = minOf(start, end)
                text = text.removeRange(begin, maxOf(start, end))
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

    @Test fun `ネイティブ文書端移動はShift解除中の同じ版の遅延選択通知でも一度送る`() {
        for (changedRevision in listOf(false, true)) {
            val h = Harness("abc", 3)
            assertEquals(EditorEditResult.Outcome.NATIVE_ISSUED, h.run(EditCommand.LEFT).outcome)
            h.connection.onClearMeta = {
                h.update { it.copy(selectionStart = 2, selectionEnd = 2,
                    revision = if (changedRevision) 1 else 0) }
            }
            val result = h.run(EditCommand.BUFFER_START)
            assertEquals(if (changedRevision) EditorEditResult.Outcome.STALE else
                EditorEditResult.Outcome.NATIVE_ISSUED, result.outcome)
            assertEquals(if (changedRevision) listOf(KeyEvent.KEYCODE_DPAD_LEFT) else
                listOf(KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_MOVE_HOME), h.connection.nativeCodes)
        }
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

    @Test fun `選択範囲だけを切り取りまたはコピーする`() {
        val cut = Harness("abcde\n", 3, 1)
        assertEquals(EditorEditResult.Outcome.APPLIED, cut.run(EditCommand.CUT).outcome)
        assertEquals("ade\n", cut.connection.text)
        assertEquals(1, cut.connection.clipboardActions)
        val copy = Harness("abcde\n", 3, 1)
        assertEquals(EditorEditResult.Outcome.APPLIED, copy.run(EditCommand.COPY).outcome)
        assertEquals("abcde\n", copy.connection.text)
        assertEquals(1, copy.connection.start)
        assertEquals(3, copy.connection.end)
        assertEquals(1, copy.connection.clipboardActions)
        val noSelection = Harness("abcde\n", 2)
        assertEquals(EditorEditResult.Outcome.NO_CHANGE, noSelection.run(EditCommand.CUT).outcome)
        assertEquals(0, noSelection.connection.clipboardActions)
    }

    @Test fun `改行を一度だけ挿入し保護欄では実行しない`() {
        val h = Harness("abc", 3)
        assertEquals(EditorEditResult.Outcome.APPLIED, h.run(EditCommand.NEWLINE).outcome)
        assertEquals("abc\n", h.connection.text)
        assertEquals(1, h.connection.commitCalls)
        val protected = Harness("abc", 3)
        protected.update { it.copy(protectedInput = true) }
        assertEquals(EditorEditResult.Outcome.STALE, protected.run(EditCommand.NEWLINE).outcome)
        assertEquals(0, protected.connection.queries)
    }

    @Test fun `長文で取得窓が移動しても改行と切り取りを確認できる`() {
        if (Build.VERSION.SDK_INT < 31) return
        val newline = Harness("a".repeat(5_000), 2_500)
        newline.connection.windowed = true
        assertEquals(EditorEditResult.Outcome.APPLIED, newline.run(EditCommand.NEWLINE).outcome)
        assertEquals('\n', newline.connection.text[2_500])
        val cut = Harness("a".repeat(5_000), 2_501, 2_500)
        cut.connection.windowed = true
        assertEquals(EditorEditResult.Outcome.APPLIED, cut.run(EditCommand.CUT).outcome)
        assertEquals(4_999, cut.connection.text.length)
    }

    @Test fun `長文末尾の左右移動は入力先の標準キーに委ねる`() {
        if (Build.VERSION.SDK_INT < 31) return
        val h = Harness("a".repeat(5_000), 5_000)
        h.connection.windowed = true
        assertEquals(EditorEditResult.Outcome.NATIVE_ISSUED, h.run(EditCommand.LEFT).outcome)
        assertEquals(4_999, h.connection.start)
        assertEquals(1, h.connection.nativeDown)
    }

    @Test fun `長い一行の途中でも左右移動を続けられる`() {
        if (Build.VERSION.SDK_INT < 31) return
        val h = Harness("a".repeat(5_000), 2_500)
        h.connection.windowed = true
        assertEquals(EditorEditResult.Outcome.NATIVE_ISSUED, h.run(EditCommand.LEFT).outcome)
        assertEquals(2_499, h.connection.start)
        h.update { it.copy(selectionStart = 2_499, selectionEnd = 2_499, revision = 1) }
        assertEquals(EditorEditResult.Outcome.NATIVE_ISSUED, h.run(EditCommand.LEFT).outcome)
        assertEquals(2_498, h.connection.start)
        assertEquals(2, h.connection.nativeDown)
        assertEquals(0, h.connection.selectionCalls)
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
        for (command in listOf(EditCommand.KILL_LINE, EditCommand.WORD_FORWARD)) {
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
        assertEquals(EditorEditResult.Outcome.NATIVE_ISSUED, unknown.run(EditCommand.LEFT).outcome)
        assertEquals(0, unknown.connection.edits)
    }

    @Test fun `nullと不明offsetと差分と過大応答を拒否する`() {
        val unavailable = Harness("abc", 1)
        unavailable.connection.unavailable = true
        assertEquals(EditorEditResult.Outcome.NATIVE_ISSUED, unavailable.run(EditCommand.HOME).outcome)
        val unknown = Harness("abc", 1)
        unknown.connection.offset = -2
        assertEquals(EditorEditResult.Outcome.UNSUPPORTED, unknown.run(EditCommand.HOME).outcome)
        val big = Harness("a".repeat(8193), 1)
        assertEquals(EditorEditResult.Outcome.UNSUPPORTED, big.run(EditCommand.HOME).outcome)
        if (Build.VERSION.SDK_INT < 31) {
            val partial = Harness("abc", 1)
            partial.connection.partial = true
            assertEquals(EditorEditResult.Outcome.UNSUPPORTED, partial.run(EditCommand.HOME).outcome)
        }
    }

    @Test fun `選択通知の遅延時は現在の選択で標準キーを一度だけ送る`() {
        val mismatch = Harness("abc", 1)
        mismatch.update { it.copy(selectionStart = 2, selectionEnd = 2) }
        assertEquals(EditorEditResult.Outcome.NATIVE_ISSUED, mismatch.run(EditCommand.LEFT).outcome)
        assertEquals(0, mismatch.connection.start)
        assertEquals(listOf(KeyEvent.KEYCODE_DPAD_LEFT), mismatch.connection.nativeCodes)
        assertEquals(0, mismatch.connection.edits)
        assertEquals(2, mismatch.connection.queries)
    }

    @Test fun `選択通知の遅延時も変化する取得結果や保護状態には変更を送らない`() {
        for (mode in 0..3) {
            val h = Harness("abc", 1)
            h.update { it.copy(selectionStart = 2, selectionEnd = 2) }
            h.connection.onQuery = { count -> if (count == 2) {
                when (mode) {
                    0 -> h.connection.start = 0
                    1 -> h.update { it.copy(revision = 1) }
                    2 -> h.update { it.copy(generation = 8) }
                    3 -> h.update { it.copy(protectedInput = true) }
                }
            } }
            assertEquals(EditorEditResult.Outcome.STALE, h.run(EditCommand.LEFT).outcome)
            assertEquals(0, h.connection.edits)
            assertEquals(0, h.connection.nativeDown)
        }
    }

    @Test fun `選択通知の遅延から削除範囲を推測せずクラスタ途中も変更しない`() {
        val mismatch = Harness("abc", 1)
        mismatch.update { it.copy(selectionStart = 2, selectionEnd = 2) }
        assertEquals(EditorEditResult.Outcome.UNSUPPORTED, mismatch.run(EditCommand.KILL_LINE).outcome)
        assertEquals(0, mismatch.connection.edits)
        val cluster = Harness("😀rest", 1)
        assertEquals(EditorEditResult.Outcome.UNSUPPORTED, cluster.run(EditCommand.DELETE).outcome)
        cluster.update { it.copy(selectionStart = 2, selectionEnd = 2) }
        assertEquals(EditorEditResult.Outcome.UNSUPPORTED, cluster.run(EditCommand.LEFT).outcome)
        assertEquals(0, cluster.connection.nativeDown)
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
            if (mode == 1) {
                assertEquals(EditorEditResult.Outcome.STALE, h.run(EditCommand.KILL_LINE).outcome)
                assertEquals(1, h.connection.edits)
            } else {
                h.connection.accept = true
                h.connection.ignoreEdit = false
                assertEquals(EditorEditResult.Outcome.APPLIED, h.run(EditCommand.KILL_LINE).outcome)
                assertEquals(2, h.connection.edits)
            }
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

    @Test fun `送信後の予期しない選択と世代内変更でも次の照合から再開する`() {
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
            h.connection.onEdit = null
            h.update { it.copy(selectionStart = h.connection.start, selectionEnd = h.connection.end) }
            assertEquals(EditorEditResult.Outcome.NO_CHANGE, h.run(EditCommand.HOME).outcome)
            assertTrue(h.connection.queries > queries)
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

    @Test fun `周辺取得未対応なら抽出テキストの同じ経路で照合する`() {
        if (Build.VERSION.SDK_INT < 31) return
        val h = Harness("abc", 2)
        h.connection.surroundingUnavailable = true
        assertEquals(EditorEditResult.Outcome.APPLIED, h.run(EditCommand.HOME).outcome)
        assertEquals(4, h.connection.queries)
    }

    @Test fun `最終行と末尾文字の取得窓不足は標準キーに一度だけ委ねる`() {
        for ((command, key) in listOf(EditCommand.UP to KeyEvent.KEYCODE_DPAD_UP,
            EditCommand.DOWN to KeyEvent.KEYCODE_DPAD_DOWN,
            EditCommand.BACKSPACE to KeyEvent.KEYCODE_DEL,
            EditCommand.DELETE to KeyEvent.KEYCODE_FORWARD_DEL)) {
            val h = Harness("ab\ncd", if (command == EditCommand.DELETE) 4 else 5)
            assertEquals(EditorEditResult.Outcome.NATIVE_ISSUED, h.run(command).outcome)
            assertEquals(listOf(key), h.connection.nativeCodes)
            assertEquals(0, h.connection.edits)
        }
    }

    @Test fun `本文取得未対応でも標準削除を一度だけ送る`() {
        for ((command, key) in listOf(EditCommand.BACKSPACE to KeyEvent.KEYCODE_DEL,
            EditCommand.DELETE to KeyEvent.KEYCODE_FORWARD_DEL)) {
            val h = Harness("abc", 1)
            h.connection.unavailable = true
            assertEquals(EditorEditResult.Outcome.NATIVE_ISSUED, h.run(command).outcome)
            assertEquals(listOf(key), h.connection.nativeCodes)
            assertEquals(0, h.connection.edits)
        }
    }

    @Test fun `非同期の改行受付後に古い取得が返っても次の編集を再開する`() {
        val h = Harness("abc", 3)
        h.connection.ignoreEdit = true
        assertEquals(EditorEditResult.Reason.POSTCHECK_MISMATCH, h.run(EditCommand.NEWLINE).reason)
        assertEquals(1, h.connection.commitCalls)
        h.connection.ignoreEdit = false
        h.connection.text = "abc\n"
        h.connection.start = 4
        h.connection.end = 4
        h.update { it.copy(selectionStart = 4, selectionEnd = 4) }
        assertEquals(EditorEditResult.Outcome.NATIVE_ISSUED, h.run(EditCommand.LEFT).outcome)
        assertEquals(1, h.connection.commitCalls)
    }

    @Test fun `本文取得不能の改行はENTERに変換せず文字を一度だけ挿入する`() {
        val h = Harness("abc", 1)
        h.connection.unavailable = true
        assertEquals(EditorEditResult.Outcome.NATIVE_ISSUED, h.run(EditCommand.NEWLINE).outcome)
        assertEquals("a\nbc", h.connection.text)
        assertEquals(1, h.connection.commitCalls)
        assertTrue(h.connection.nativeCodes.isEmpty())
    }

    @Test fun `文書端の標準キーから物理Shiftの範囲選択を除く`() {
        for (command in listOf(EditCommand.BUFFER_START, EditCommand.BUFFER_END)) {
            val h = Harness("abc", 1)
            assertEquals(EditorEditResult.Outcome.NATIVE_ISSUED, h.run(command).outcome)
            assertEquals(listOf(KeyEvent.META_SHIFT_MASK), h.connection.clearedMeta)
            assertEquals(1, h.connection.nativeDown)
        }
    }

    @Test fun `取得窓より長い行の行頭移動は標準キーに委ねる`() {
        if (Build.VERSION.SDK_INT < 31) return
        val h = Harness("a".repeat(5_000), 2_500)
        h.connection.windowed = true
        assertEquals(EditorEditResult.Outcome.NATIVE_ISSUED, h.run(EditCommand.HOME).outcome)
        assertEquals(listOf(KeyEvent.KEYCODE_MOVE_HOME), h.connection.nativeCodes)
    }

    @Test fun `本文取得不能の間に世代が変われば標準キーを送らない`() {
        val h = Harness("abc", 1)
        h.connection.unavailable = true
        h.connection.onQuery = { h.update { it.copy(generation = 8) } }
        assertEquals(EditorEditResult.Outcome.STALE, h.run(EditCommand.DELETE).outcome)
        assertTrue(h.connection.nativeCodes.isEmpty())
    }

    @Test fun `絶対offset不明の標準周辺取得では抽出テキストを優先する`() {
        if (Build.VERSION.SDK_INT < 31) return
        val h = Harness("abc", 2)
        h.connection.surroundingOffset = -1
        assertEquals(EditorEditResult.Outcome.APPLIED, h.run(EditCommand.HOME).outcome)
        assertEquals(0, h.connection.start)
        assertTrue(h.connection.nativeCodes.isEmpty())
    }

    @Test fun `相対位置だけのブラウザ接続は標準移動と削除と改行を受け付ける`() {
        if (Build.VERSION.SDK_INT < 31) return
        for (command in listOf(EditCommand.LEFT, EditCommand.RIGHT, EditCommand.BACKSPACE,
            EditCommand.DELETE, EditCommand.NEWLINE)) {
            val h = Harness("abc", 1)
            h.connection.surroundingOffset = -1
            h.connection.extractedUnavailable = true
            assertEquals(EditorEditResult.Outcome.NATIVE_ISSUED, h.run(command).outcome)
            assertEquals(3, h.connection.queries)
            assertEquals(if (command == EditCommand.NEWLINE) 1 else 0, h.connection.commitCalls)
            assertEquals(if (command == EditCommand.NEWLINE) 0 else 1, h.connection.nativeDown)
        }
    }

    @Test fun `相対取得の本文が照合中に変われば削除を送らない`() {
        if (Build.VERSION.SDK_INT < 31) return
        val h = Harness("abc", 1)
        h.connection.surroundingOffset = -1
        h.connection.extractedUnavailable = true
        h.connection.onQuery = { if (it == 3) h.connection.text = "xyz" }
        assertEquals(EditorEditResult.Outcome.STALE, h.run(EditCommand.DELETE).outcome)
        assertTrue(h.connection.nativeCodes.isEmpty())
    }

    @Test fun `本文未取得の選択範囲のコピーと切り取りは入力先自身へ一度委ねる`() {
        for (command in listOf(EditCommand.COPY, EditCommand.CUT)) {
            val h = Harness("abc", 2, 1)
            h.connection.unavailable = true
            assertEquals(EditorEditResult.Outcome.NATIVE_ISSUED, h.run(command).outcome)
            assertEquals(1, h.connection.clipboardActions)
            assertEquals(if (command == EditCommand.CUT) "ac" else "abc", h.connection.text)
            assertTrue(h.connection.nativeCodes.isEmpty())
        }
    }

    @Test fun `末尾の標準削除を選択通知より先に連打しても順番に送る`() {
        val h = Harness("ab", 2)
        assertEquals(EditorEditResult.Outcome.NATIVE_ISSUED, h.run(EditCommand.BACKSPACE).outcome)
        assertEquals("a", h.connection.text)
        val queries = h.connection.queries
        // 入力先の選択通知がまだ来ていない状態で次の明示的なキーを受け取ります。
        assertEquals(2, h.state.get().selectionEnd)
        assertEquals(EditorEditResult.Outcome.NATIVE_ISSUED, h.run(EditCommand.BACKSPACE).outcome)
        assertEquals("", h.connection.text)
        assertEquals(queries, h.connection.queries)
        assertEquals(2, h.connection.nativeDown)
    }

    @Test fun `本文未取得または相対取得でも未選択のコピーと切り取りを送らない`() {
        for (relative in listOf(false, true)) {
            if (relative && Build.VERSION.SDK_INT < 31) continue
            for (command in listOf(EditCommand.COPY, EditCommand.CUT)) {
                val h = Harness("abc", 1)
                if (relative) {
                    h.connection.surroundingOffset = -1
                    h.connection.extractedUnavailable = true
                } else h.connection.unavailable = true
                assertEquals(EditorEditResult.Outcome.NO_CHANGE, h.run(command).outcome)
                assertEquals(0, h.connection.clipboardActions)
                assertEquals("abc", h.connection.text)
            }
        }
    }

}
