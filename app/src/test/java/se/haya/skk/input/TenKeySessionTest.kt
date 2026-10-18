package se.haya.skk.input

import android.text.Selection
import android.text.method.TextKeyListener
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.ExtractedText
import android.view.inputmethod.ExtractedTextRequest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import se.haya.skk.core.BasicSkkAction
import se.haya.skk.core.BasicSkkDictionary
import se.haya.skk.core.DictionaryCandidate
import se.haya.skk.core.InputPhase
import java.util.concurrent.Executor

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26])
class TenKeySessionTest {
    private class Queue : Executor {
        val tasks = ArrayDeque<Runnable>()
        override fun execute(command: Runnable) { tasks.addLast(command) }
        fun run() { while (tasks.isNotEmpty()) tasks.removeFirst().run() }
    }

    private class Connection : BaseInputConnection(View(RuntimeEnvironment.getApplication()), true) {
        var provideSnapshot = true
        var rejectCommit = false
        var commits = 0
        var afterCommit: (() -> Unit)? = null
        var liveSnapshot = false
        var onNativeSelection: ((Int, Int) -> Unit)? = null
        init { Selection.setSelection(editable, 0) }
        override fun getExtractedText(request: ExtractedTextRequest?, flags: Int): ExtractedText? =
            if (!provideSnapshot) null else ExtractedText().apply {
                text = if (liveSnapshot) editable else editable.toString()
                startOffset = 0
                partialStartOffset = -1
                selectionStart = Selection.getSelectionStart(editable)
                selectionEnd = Selection.getSelectionEnd(editable)
            }
        override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
            commits++
            val accepted = !rejectCommit && super.commitText(text, newCursorPosition)
            if (accepted) afterCommit?.invoke()
            return accepted
        }
        override fun sendKeyEvent(event: KeyEvent): Boolean {
            if (event.keyCode != KeyEvent.KEYCODE_DEL && event.keyCode != KeyEvent.KEYCODE_FORWARD_DEL)
                return super.sendKeyEvent(event)
            if (event.action == KeyEvent.ACTION_DOWN) {
                // 裸の View にはキー配送先がないため、実際の入力欄と同じ標準削除を適用します。
                val text = requireNotNull(editable)
                if (!TextKeyListener.getInstance().onKeyDown(
                        View(RuntimeEnvironment.getApplication()), text, event.keyCode, event)) return false
                onNativeSelection?.invoke(Selection.getSelectionStart(text), Selection.getSelectionEnd(text))
            }
            return true
        }
    }

    private fun session(
        connection: Connection,
        protected: Boolean = false,
        workers: Executor = Executor { it.run() },
        callbacks: Executor = Executor { it.run() },
    ) = EditorSession(
        1, connection, protected, !protected, 0, 0,
        dictionary = BasicSkkDictionary { query ->
            when (query.readingKey) {
                "にほん" -> listOf(DictionaryCandidate("日本"))
                "APIのせってい" -> listOf(DictionaryCandidate("APIの設定"))
                else -> emptyList()
            }
        },
        callbackExecutor = callbacks,
        editPortFactory = { input, token, state, callbackExecutor, before ->
            EditorEditPort(input, token, state, callbackExecutor, workerExecutor = workers, beforeRequest = before)
        },
    ).also { current ->
        connection.onNativeSelection = { start, end -> current.onSelection(start, end, -1, -1) }
    }

    @Test fun `英字とかなは一つの見出し語として大小を保持して検索する`() {
        val connection = Connection()
        val current = session(connection)
        current.handleTouch(BasicSkkAction.StartTouchReading())
        current.handleTouch(BasicSkkAction.TouchText("API"))
        current.handleTouch(BasicSkkAction.TouchText("のせってい"))
        assertEquals("APIのせってい", current.engine.state.reading)
        current.handleTouchSpaceOrConvert()
        current.handleTouchPrimary()
        assertEquals("APIの設定", connection.editable.toString())
        assertEquals(InputPhase.IDLE, current.engine.state.phase)
    }

    @Test fun `候補中の英字はラベルやコマンドにせず先頭確定後に一度入力する`() {
        for (text in listOf("x", "q", "/", "API", "１２")) {
            val connection = Connection()
            val current = session(connection)
            current.handleTouch(BasicSkkAction.StartTouchReading())
            current.handleTouch(BasicSkkAction.TouchText("にほん"))
            current.handleTouchSpaceOrConvert()
            current.handleTouch(BasicSkkAction.TouchText(text))
            assertEquals("日本$text", connection.editable.toString())
            assertEquals(InputPhase.IDLE, current.engine.state.phase)
            assertEquals(2, connection.commits)
        }
    }

    @Test fun `保護入力では境界付き文字もそのまま確定し検索しない`() {
        val connection = Connection()
        val current = session(connection, protected = true)
        current.handleTouch(BasicSkkAction.TouchText("API", boundary = true))
        current.handleTouch(BasicSkkAction.TouchText("か", boundary = true))
        assertEquals("APIか", connection.editable.toString())
        assertEquals(InputPhase.IDLE, current.engine.state.phase)
        assertFalse(current.handleTouch(BasicSkkAction.StartTouchReading()))
        assertFalse(current.handleTouch(BasicSkkAction.CycleTouchKana))
    }

    @Test fun `全角英字面の空白は変換前の通常入力と保護入力へ全角で送る`() {
        val normalConnection = Connection()
        val normal = session(normalConnection)
        assertTrue(normal.handleTouchSpaceOrConvert(fullWidthSpace = true))
        assertEquals("　", normalConnection.editable.toString())

        val protectedConnection = Connection()
        val protected = session(protectedConnection, protected = true)
        assertTrue(protected.handleTouchSpaceOrConvert(fullWidthSpace = true))
        assertEquals("　", protectedConnection.editable.toString())
    }

    @Test fun `全角英字面でも読み中の空白キーは全角空白を入れず変換する`() {
        val connection = Connection()
        val current = session(connection)
        current.handleTouch(BasicSkkAction.StartTouchReading())
        current.handleTouch(BasicSkkAction.TouchText("にほん"))

        assertTrue(current.handleTouchSpaceOrConvert(fullWidthSpace = true))

        assertEquals("日本", current.view.candidate?.selected?.text)
        assertEquals("日本", connection.editable.toString())
    }

    @Test fun `通常入力のかなに濁点と半濁点を後付けしても二重入力しない`() {
        val connection = Connection()
        val current = session(connection)
        current.handleTouch(BasicSkkAction.TouchText("は"))
        assertTrue(current.handleTouch(BasicSkkAction.CycleTouchKana))
        assertEquals("ば", connection.editable.toString())
        assertTrue(current.handleTouch(BasicSkkAction.CycleTouchKana))
        assertEquals("ぱ", connection.editable.toString())
        assertTrue(current.handleTouch(BasicSkkAction.CycleTouchKana))
        assertEquals("は", connection.editable.toString())
    }

    @Test fun `小書き濁点半濁点の直後は選択通知を待たず一文字削除して入力を続けられる`() {
        for (kana in listOf("は", "や", "つ")) {
            val connection = Connection()
            val current = session(connection)
            current.handleTouch(BasicSkkAction.TouchText(kana))
            assertTrue(current.handleTouch(BasicSkkAction.CycleTouchKana))
            assertNotEquals(kana, connection.editable.toString())
            assertTrue(current.handleTouchBackspace())
            assertEquals("", connection.editable.toString())
            // 通常確定と同じ位置の置換通知が削除完了後へ遅れても、選択を戻しません。
            assertFalse(current.onSelection(1, 1, -1, -1))
            assertFalse(current.onSelection(1, 1, -1, -1))
            assertEquals(0 to 0, current.expectedCursorSelection)
            assertTrue(current.handleTouch(BasicSkkAction.TouchText("あ")))
            assertEquals("あ", connection.editable.toString())
            assertFalse(current.failed)
        }
    }

    @Test fun `かな置換後の削除を終えるまで後続入力を待ち遅い選択通知で位置を戻さない`() {
        val connection = Connection()
        val workers = Queue()
        val callbacks = Queue()
        val current = session(connection, workers = workers, callbacks = callbacks)
        current.handleTouch(BasicSkkAction.TouchText("は"))
        current.handleTouch(BasicSkkAction.CycleTouchKana)
        current.handleTouchBackspace()
        current.handleTouch(BasicSkkAction.TouchText("あ"))
        assertEquals("ば", connection.editable.toString())
        workers.run()
        assertEquals("", connection.editable.toString())
        callbacks.run()
        assertEquals("あ", connection.editable.toString())
        assertEquals(1 to 1, current.expectedCursorSelection)

        // 同じ長さの置換通知が、後続の二文字入力より遅れて到着します。
        current.handleTouch(BasicSkkAction.TouchText("い"))
        assertFalse(current.onSelection(1, 1, -1, -1))
        assertEquals(2 to 2, current.expectedCursorSelection)
        assertFalse(current.onSelection(2, 2, -1, -1))
        current.handleTouchBackspace()
        workers.run()
        callbacks.run()
        assertEquals("あ", connection.editable.toString())
    }

    @Test fun `かな置換の選択通知が削除の実行前へ遅れても待機中の削除を取り消さない`() {
        val connection = Connection()
        val workers = Queue()
        val callbacks = Queue()
        val current = session(connection, workers = workers, callbacks = callbacks)
        current.handleTouch(BasicSkkAction.TouchText("は"))
        current.handleTouch(BasicSkkAction.CycleTouchKana)
        current.handleTouchBackspace()
        assertFalse(current.onSelection(1, 1, -1, -1))
        assertFalse(current.onSelection(1, 1, -1, -1))
        workers.run()
        callbacks.run()
        assertEquals("", connection.editable.toString())
        current.handleTouch(BasicSkkAction.TouchText("あ"))
        assertEquals("あ", connection.editable.toString())
        assertFalse(current.failed)
    }

    @Test fun `かな置換の古い位置通知でも本文が変わっていれば待機中の外部削除を失効する`() {
        val connection = Connection()
        val workers = Queue()
        val callbacks = Queue()
        val current = session(connection, workers = workers, callbacks = callbacks)
        current.handleTouch(BasicSkkAction.TouchText("は"))
        current.handleTouch(BasicSkkAction.CycleTouchKana)
        current.handleTouchBackspace()
        workers.run()
        callbacks.run()
        assertEquals("", connection.editable.toString())
        requireNotNull(connection.editable).append("外")
        Selection.setSelection(connection.editable, 0)
        current.handleTouch(BasicSkkAction.Delete)
        assertFalse(current.onSelection(1, 1, -1, -1))
        workers.run()
        callbacks.run()
        assertEquals("外", connection.editable.toString())
        assertEquals(0 to 0, current.expectedCursorSelection)
        assertFalse(current.failed)
    }

    @Test fun `かな置換後の削除待ちに終了したセッションへ後続入力を反映しない`() {
        val connection = Connection()
        val workers = Queue()
        val callbacks = Queue()
        val current = session(connection, workers = workers, callbacks = callbacks)
        current.handleTouch(BasicSkkAction.TouchText("は"))
        current.handleTouch(BasicSkkAction.CycleTouchKana)
        current.handleTouchBackspace()
        current.handleTouch(BasicSkkAction.TouchText("あ"))
        current.close()
        workers.run()
        callbacks.run()
        assertEquals("ば", connection.editable.toString())
    }

    @Test fun `かな置換後の本文やカーソルが一致しなければ通知待ちの削除を許可しない`() {
        for (changeCursor in listOf(false, true)) {
            val connection = Connection()
            val current = session(connection)
            current.handleTouch(BasicSkkAction.TouchText("は"))
            connection.afterCommit = {
                if (changeCursor) Selection.setSelection(connection.editable, 0)
                else requireNotNull(connection.editable).replace(0, 1, "外")
            }
            assertTrue(current.handleTouch(BasicSkkAction.CycleTouchKana))
            val actual = connection.editable.toString()
            assertTrue(current.handleTouchBackspace())
            assertEquals(actual, connection.editable.toString())
            assertFalse(current.failed)
        }
    }

    @Test fun `入力先の可変スナップショットでも別位置の本文変更を置換成功と見なさない`() {
        val connection = Connection()
        connection.liveSnapshot = true
        val current = session(connection)
        current.handleTouch(BasicSkkAction.TouchText("あは"))
        connection.afterCommit = { requireNotNull(connection.editable).replace(0, 1, "外") }
        assertTrue(current.handleTouch(BasicSkkAction.CycleTouchKana))
        assertEquals("外ば", connection.editable.toString())
        current.handleTouchBackspace()
        assertEquals("外ば", connection.editable.toString())
    }

    @Test fun `読み中のかな循環後もBackspaceで読みの文字を削除する`() {
        val connection = Connection()
        val current = session(connection)
        current.handleTouch(BasicSkkAction.StartTouchReading())
        current.handleTouch(BasicSkkAction.TouchText("は"))
        assertTrue(current.handleTouch(BasicSkkAction.CycleTouchKana))
        assertEquals("ば", current.engine.state.reading)
        assertTrue(current.handleTouchBackspace())
        assertEquals("", current.engine.state.reading)
        assertEquals("", connection.editable.toString())
        assertFalse(current.failed)
    }

    @Test fun `送り候補のかな循環後もBackspaceで読みへ戻り次の押下で送りを削除する`() {
        val connection = Connection()
        val current = EditorSession(1, connection, false, true, 0, 0,
            dictionary = BasicSkkDictionary { listOf(DictionaryCandidate("書")) })
        current.handleTouch(BasicSkkAction.TouchText("か", boundary = true))
        current.handleTouch(BasicSkkAction.TouchText("く", boundary = true))
        assertTrue(current.handleTouch(BasicSkkAction.CycleTouchKana))
        assertEquals("書ぐ", current.view.candidate!!.committedText)
        assertTrue(current.handleTouchBackspace())
        assertEquals(InputPhase.READING, current.engine.state.phase)
        assertEquals("か", current.engine.state.reading)
        assertEquals("ぐ", current.engine.state.okuri)
        assertTrue(current.handleTouchBackspace())
        assertEquals("か", current.engine.state.reading)
        assertEquals("", current.engine.state.okuri)
        assertFalse(current.failed)
    }

    @Test fun `かなの変更はカーソルや本文が確認できない場合に削除しない`() {
        val connection = Connection()
        val current = session(connection)
        current.handleTouch(BasicSkkAction.TouchText("か"))
        connection.provideSnapshot = false
        assertFalse(current.handleTouch(BasicSkkAction.CycleTouchKana))
        assertEquals("か", connection.editable.toString())
        connection.provideSnapshot = true
        Selection.setSelection(connection.editable, 0)
        assertFalse(current.handleTouch(BasicSkkAction.CycleTouchKana))
        assertEquals("か", connection.editable.toString())
    }

    @Test fun `かな置換の確定拒否で元の文字を消さず再送しない`() {
        val connection = Connection()
        val current = session(connection)
        current.handleTouch(BasicSkkAction.TouchText("か"))
        connection.rejectCommit = true
        current.handleTouch(BasicSkkAction.CycleTouchKana)
        assertTrue(current.failed)
        assertEquals("か", connection.editable.toString())
        val commits = connection.commits
        current.handleTouch(BasicSkkAction.CycleTouchKana)
        assertEquals(commits, connection.commits)
    }

    @Test fun `文字種確定で英字と数字を保持し改行を送らない`() {
        for ((half, expected) in listOf(false to "APIガク１２", true to "APIｶﾞｸ１２")) {
            val connection = Connection()
            val current = session(connection)
            current.handleTouch(BasicSkkAction.StartTouchReading())
            current.handleTouch(BasicSkkAction.TouchText("APIがく１２"))
            current.handleTouch(BasicSkkAction.ConvertTouchReading(half))
            assertEquals(expected, connection.editable.toString())
            assertEquals(1, connection.commits)
            assertEquals(InputPhase.IDLE, current.engine.state.phase)
        }
    }

    @Test fun `終了した入力欄へ10キー操作を配送しない`() {
        val connection = Connection()
        val current = session(connection)
        current.close()
        assertFalse(current.handleTouch(BasicSkkAction.TouchText("か")))
        assertFalse(current.handleTouch(BasicSkkAction.CycleTouchKana))
        assertEquals("", connection.editable.toString())
    }
}
