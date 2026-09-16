package jp.hayase.skk.input

import android.content.Context
import android.text.Selection
import android.view.View
import android.view.inputmethod.BaseInputConnection
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 35])
class EditorSessionTest {
    private class Connection : BaseInputConnection(View(RuntimeEnvironment.getApplication()), true) {
        var commits = 0
        var rejectComposing = false
        var rejectCommit = false
        init { Selection.setSelection(editable, 0) }
        override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
            commits++
            return !rejectCommit && super.commitText(text, newCursorPosition)
        }
        override fun setComposingText(text: CharSequence?, newCursorPosition: Int): Boolean =
            !rejectComposing && super.setComposingText(text, newCursorPosition)
    }
    private fun session(connection: Connection, generation: Long = 1) =
        EditorSession(generation, connection, false, true, 0, 0)
    private fun EditorSession.type(text: String) {
        text.forEach { assertTrue(handle(InputAction.Text(it.toString()))) }
    }

    @Test fun conversionEnterCommitsOnceAndNextEnterPasses() {
        val connection = Connection()
        val session = session(connection)
        session.type("Nihon ")
        assertEquals("日本", connection.editable.toString())
        assertEquals(0, connection.commits)
        assertTrue(session.handle(InputAction.Enter))
        assertEquals("日本", connection.editable.toString())
        assertEquals(1, connection.commits)
        assertFalse(session.handle(InputAction.Enter))
        assertEquals(1, connection.commits)
        assertEquals(-1, BaseInputConnection.getComposingSpanStart(connection.editable!!))
    }

    @Test fun ownSelectionNotificationsDoNotCancelComposition() {
        val connection = Connection()
        val session = session(connection)
        session.type("Ni")
        session.onSelection(1, 1, 0, 1)
        assertEquals("に", session.engine.composing)
        session.type("hon ")
        session.onSelection(2, 2, 0, 2)
        assertEquals("日本", session.engine.composing)
    }

    @Test fun cursorMovePreservesExistingTextAndDoesNotReplaceItLater() {
        val connection = Connection()
        val session = session(connection)
        session.type("Nihon ")
        Selection.setSelection(connection.editable, 0)
        session.onSelection(0, 0, 0, 2)
        assertFalse(session.engine.hasComposition)
        assertEquals("日本", connection.editable.toString())
        session.type("a")
        assertEquals("あ日本", connection.editable.toString())
    }

    @Test fun oldSessionCannotWriteToEitherEditorAfterSwitch() {
        val first = Connection()
        val old = session(first)
        old.type("Ni")
        old.close()
        val second = Connection()
        val current = session(second, 2)
        assertFalse(old.acceptsResult(1, "に"))
        assertFalse(current.acceptsResult(1, ""))
        assertFalse(old.handle(InputAction.Enter))
        current.type("a")
        assertEquals("に", first.editable.toString())
        assertEquals("あ", second.editable.toString())
    }

    @Test fun cancelCandidateRestoresReadingThenRemovesOnlyComposition() {
        val connection = Connection()
        val session = session(connection)
        session.type("aNihon ")
        session.handle(InputAction.Cancel)
        assertEquals("あにほん", connection.editable.toString())
        session.handle(InputAction.Cancel)
        assertEquals("あ", connection.editable.toString())
    }

    @Test fun rejectedEditDoesNotResendKeyOrContinueWithStaleState() {
        val connection = Connection().apply { rejectComposing = true }
        val session = session(connection)
        assertTrue(session.handle(InputAction.Text("N")))
        assertTrue(session.failed)
        assertFalse(session.handle(InputAction.Text("i")))
        assertEquals("", connection.editable.toString())
    }

    @Test fun protectedEditorDoesNotReceiveAnyImeMutation() {
        val connection = Connection()
        val session = EditorSession(1, connection, true, false, 0, 0)
        assertFalse(session.handle(InputAction.Kana))
        assertFalse(session.handle(InputAction.Text("a")))
        assertEquals(0, connection.commits)
        assertEquals("", connection.editable.toString())
    }

    /** N03・N04: 確定拒否後は同じキーを再配送せず、接続が回復しても旧セッションを再利用しません。 */
    @Test fun rejectedCommitPreservesDisplayedTextAndStopsSession() {
        val connection = Connection()
        val session = session(connection)
        session.type("Nihon ")
        connection.rejectCommit = true
        assertTrue(session.handle(InputAction.Enter))
        assertTrue(session.failed)
        assertEquals(1, connection.commits)
        assertEquals("日本", connection.editable.toString())
        assertEquals(-1, BaseInputConnection.getComposingSpanStart(connection.editable!!))
        assertFalse(session.acceptsResult(1, ""))
        connection.rejectCommit = false
        assertFalse(session.handle(InputAction.Enter))
        assertFalse(session.handle(InputAction.Text("a")))
        assertEquals(1, connection.commits)
        assertEquals("日本", connection.editable.toString())
    }

    /** I10: 通常のモード切替・変換・取消規則より、保護入力の迂回を優先します。 */
    @Test fun protectedSessionPassesAllSkkActionsWithoutChangingExistingText() {
        val connection = Connection()
        connection.editable!!.append("existing")
        Selection.setSelection(connection.editable, 8)
        val session = EditorSession(1, connection, true, false, 8, 8)
        val actions = listOf(InputAction.Kana, InputAction.Text("N"), InputAction.Text(" "),
            InputAction.Enter, InputAction.Cancel, InputAction.Backspace)
        actions.forEach { assertFalse(session.handle(it)) }
        assertEquals("existing", connection.editable.toString())
        assertEquals(0, connection.commits)
        assertFalse(session.engine.hasComposition)
    }

    /** I10・K19 の変換側: 学習禁止だけでは通常欄の変換を禁止しません。保存抑止は辞書導入時に別途検証します。 */
    @Test fun noLearningSessionStillAllowsConversion() {
        val connection = Connection()
        val session = EditorSession(1, connection, false, false, 0, 0)
        assertFalse(session.learningAllowed)
        session.type("Nihon ")
        assertEquals("日本", connection.editable.toString())
        assertTrue(session.handle(InputAction.Enter))
        assertEquals(1, connection.commits)
        assertEquals("日本", connection.editable.toString())
    }

    @Test fun backspacePendingRomanDoesNotDeleteCommittedPrefix() {
        val connection = Connection()
        val session = session(connection)
        session.type("ak")
        assertTrue(session.handle(InputAction.Backspace))
        assertEquals("あ", connection.editable.toString())
        assertFalse(session.handle(InputAction.Backspace))
    }

    @Test fun ordinaryTypingAfterCandidateDoesNotDuplicateCandidate() {
        val connection = Connection()
        val session = session(connection)
        session.type("Nihon a")
        assertEquals("日本あ", connection.editable.toString())
        assertFalse(session.engine.hasComposition)
    }

    @Test fun unknownInitialSelectionStillAllowsCancelBeforeNotification() {
        val connection = Connection()
        val session = EditorSession(1, connection, false, true, -1, -1)
        session.type("Ni")
        assertTrue(session.handle(InputAction.Cancel))
        assertEquals("", connection.editable.toString())
    }

    @Test fun unknownInitialSelectionCanBeEstablishedByFirstCompositionNotification() {
        val connection = Connection()
        val session = EditorSession(1, connection, false, true, -1, -1)
        session.type("Ni")
        session.onSelection(1, 1, 0, 1)
        assertEquals("に", session.engine.composing)
        session.type("hon ")
        assertEquals("日本", connection.editable.toString())
    }
}
