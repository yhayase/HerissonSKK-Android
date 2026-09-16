package jp.hayase.skk.input

import android.content.Context
import android.text.Selection
import android.view.View
import android.view.inputmethod.BaseInputConnection
import jp.hayase.skk.core.BasicSkkAction
import jp.hayase.skk.core.InputMode
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
        var selections = 0
        var lastSelectionStart = -1
        var lastSelectionEnd = -1
        var rejectComposing = false
        var rejectCommit = false
        init { Selection.setSelection(editable, 0) }
        override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
            commits++
            return !rejectCommit && super.commitText(text, newCursorPosition)
        }
        override fun setComposingText(text: CharSequence?, newCursorPosition: Int): Boolean =
            !rejectComposing && super.setComposingText(text, newCursorPosition)
        override fun setSelection(start: Int, end: Int): Boolean {
            selections++
            lastSelectionStart = start
            lastSelectionEnd = end
            return super.setSelection(start, end)
        }
    }
    private fun session(connection: Connection, generation: Long = 1) =
        EditorSession(generation, connection, false, true, 0, 0)
    private fun EditorSession.type(text: String) {
        text.forEach { assertTrue(handle(BasicSkkAction.Text(it.toString()))) }
    }

    @Test fun conversionEnterCommitsOnceAndNextEnterPasses() {
        val connection = Connection()
        val session = session(connection)
        session.type("Nihon ")
        assertEquals("日本", connection.editable.toString())
        assertEquals(0, connection.commits)
        assertTrue(session.handle(BasicSkkAction.Enter))
        assertEquals("日本", connection.editable.toString())
        assertEquals(1, connection.commits)
        assertFalse(session.handle(BasicSkkAction.Enter))
        assertEquals(1, connection.commits)
        assertEquals(-1, BaseInputConnection.getComposingSpanStart(connection.editable!!))
    }

    @Test fun ownSelectionNotificationsDoNotCancelComposition() {
        val connection = Connection()
        val session = session(connection)
        session.type("Ni")
        session.onSelection(1, 1, 0, 1)
        assertEquals("に", session.displayedComposition)
        session.type("hon ")
        session.onSelection(2, 2, 0, 2)
        assertEquals("日本", session.displayedComposition)
    }

    @Test fun delayedOlderSelectionNotificationDoesNotRewindOptimisticCompositionPosition() {
        val connection = Connection()
        val session = session(connection)
        session.type("Nihon ")
        assertTrue(session.handle(BasicSkkAction.Enter))
        session.type("Nihon")
        assertEquals("日本にほn", connection.editable.toString())

        session.onSelection(1, 1, 0, 1)

        assertTrue(session.handle(BasicSkkAction.Left))
        assertEquals(4, connection.lastSelectionStart)
        assertEquals(4, connection.lastSelectionEnd)
    }

    @Test fun cursorMovePreservesExistingTextAndDoesNotReplaceItLater() {
        val connection = Connection()
        val session = session(connection)
        session.type("Nihon ")
        Selection.setSelection(connection.editable, 0)
        session.onSelection(0, 0, 0, 2)
        assertFalse(session.hasComposition)
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
        assertFalse(old.handle(BasicSkkAction.Enter))
        current.type("a")
        assertEquals("に", first.editable.toString())
        assertEquals("あ", second.editable.toString())
    }

    @Test fun cancelCandidateRestoresReadingThenRemovesOnlyComposition() {
        val connection = Connection()
        val session = session(connection)
        session.type("aNihon ")
        session.handle(BasicSkkAction.Cancel)
        assertEquals("あにほn", connection.editable.toString())
        session.handle(BasicSkkAction.Cancel)
        assertEquals("あ", connection.editable.toString())
    }

    @Test fun rejectedEditDoesNotResendKeyOrContinueWithStaleState() {
        val connection = Connection().apply { rejectComposing = true }
        val session = session(connection)
        assertTrue(session.handle(BasicSkkAction.Text("N")))
        assertTrue(session.failed)
        assertFalse(session.handle(BasicSkkAction.Text("i")))
        assertEquals("", connection.editable.toString())
    }

    @Test fun protectedEditorDoesNotReceiveAnyImeMutation() {
        val connection = Connection()
        val session = EditorSession(1, connection, true, false, 0, 0)
        assertFalse(session.handle(BasicSkkAction.Kana))
        assertFalse(session.handle(BasicSkkAction.Text("a")))
        assertEquals(0, connection.commits)
        assertEquals("", connection.editable.toString())
    }

    /** N03・N04: 確定拒否後は同じキーを再配送せず、接続が回復しても旧セッションを再利用しません。 */
    @Test fun rejectedCommitPreservesDisplayedTextAndStopsSession() {
        val connection = Connection()
        val session = session(connection)
        session.type("Nihon ")
        connection.rejectCommit = true
        assertTrue(session.handle(BasicSkkAction.Enter))
        assertTrue(session.failed)
        assertEquals(1, connection.commits)
        assertEquals("日本", connection.editable.toString())
        assertEquals(-1, BaseInputConnection.getComposingSpanStart(connection.editable!!))
        assertFalse(session.acceptsResult(1, ""))
        connection.rejectCommit = false
        assertFalse(session.handle(BasicSkkAction.Enter))
        assertFalse(session.handle(BasicSkkAction.Text("a")))
        assertEquals(1, connection.commits)
        assertEquals("日本", connection.editable.toString())
    }

    /** I10: 通常のモード切替・変換・取消規則より、保護入力の迂回を優先します。 */
    @Test fun protectedSessionPassesAllSkkActionsWithoutChangingExistingText() {
        val connection = Connection()
        connection.editable!!.append("existing")
        Selection.setSelection(connection.editable, 8)
        val session = EditorSession(1, connection, true, false, 8, 8)
        val actions = listOf(BasicSkkAction.Kana, BasicSkkAction.Text("N"), BasicSkkAction.Text(" "),
            BasicSkkAction.Enter, BasicSkkAction.Cancel, BasicSkkAction.Backspace)
        actions.forEach { assertFalse(session.handle(it)) }
        assertEquals("existing", connection.editable.toString())
        assertEquals(0, connection.commits)
        assertFalse(session.hasComposition)
    }

    /** I10・K19 の変換側: 学習禁止だけでは通常欄の変換を禁止しません。保存抑止は辞書導入時に別途検証します。 */
    @Test fun noLearningSessionStillAllowsConversion() {
        val connection = Connection()
        val session = EditorSession(1, connection, false, false, 0, 0)
        assertFalse(session.learningAllowed)
        session.type("Nihon ")
        assertEquals("日本", connection.editable.toString())
        assertTrue(session.handle(BasicSkkAction.Enter))
        assertEquals(1, connection.commits)
        assertEquals("日本", connection.editable.toString())
    }

    @Test fun backspacePendingRomanDoesNotDeleteCommittedPrefix() {
        val connection = Connection()
        val session = session(connection)
        session.type("ak")
        assertTrue(session.handle(BasicSkkAction.Backspace))
        assertEquals("あ", connection.editable.toString())
        assertFalse(session.handle(BasicSkkAction.Backspace))
    }

    @Test fun ordinaryTypingAfterCandidateDoesNotDuplicateCandidate() {
        val connection = Connection()
        val session = session(connection)
        session.type("Nihon a")
        assertEquals("日本あ", connection.editable.toString())
        assertFalse(session.hasComposition)
    }

    @Test fun unknownInitialSelectionStillAllowsCancelBeforeNotification() {
        val connection = Connection()
        val session = EditorSession(1, connection, false, true, -1, -1)
        session.type("Ni")
        assertTrue(session.handle(BasicSkkAction.Cancel))
        assertEquals("", connection.editable.toString())
    }

    @Test fun unknownInitialSelectionCanBeEstablishedByFirstCompositionNotification() {
        val connection = Connection()
        val session = EditorSession(1, connection, false, true, -1, -1)
        session.type("Ni")
        session.onSelection(1, 1, 0, 1)
        assertEquals("に", session.displayedComposition)
        session.type("hon ")
        assertEquals("日本", connection.editable.toString())
    }

    @Test fun unknownInitialSelectionDoesNotGuessInternalCursorPosition() {
        val connection = Connection()
        val session = EditorSession(1, connection, false, true, -1, -1)
        session.type("Nihon")
        assertTrue(session.handle(BasicSkkAction.Left))
        assertEquals(0, connection.selections)
        session.onSelection(3, 3, 0, 3)
        assertTrue(session.handle(BasicSkkAction.Left))
        assertEquals(1, connection.selections)
        assertEquals("にほん", connection.editable.toString())
    }

    @Test fun modesTerminalAndHalfwidthCommitWithoutEditorAction() {
        val connection = Connection()
        val session = session(connection)
        session.type("q")
        session.type("n")
        assertTrue(session.handle(BasicSkkAction.Enter))
        assertEquals("ン", connection.editable.toString())
        assertEquals(InputMode.KATAKANA, session.engine.state.mode)
        assertTrue(session.handle(BasicSkkAction.Halfwidth))
        session.type("n")
        assertTrue(session.handle(BasicSkkAction.Kana))
        assertEquals("ンﾝ", connection.editable.toString())
        assertEquals(InputMode.HIRAGANA, session.engine.state.mode)
    }

    @Test fun okuriAbbrevAndSuffixUseOnlyLimitedInMemoryDictionary() {
        val connection = Connection()
        val session = session(connection)
        session.type("KaKu")
        assertEquals("書く", connection.editable.toString())
        assertTrue(session.handle(BasicSkkAction.Enter))
        session.type("/API ")
        assertEquals("書くエーピーアイ", connection.editable.toString())
        assertTrue(session.handle(BasicSkkAction.Enter))
        session.type("Dai>")
        assertEquals("書くエーピーアイ第", connection.editable.toString())
        session.type(">kai ")
        assertEquals("書くエーピーアイ第回", connection.editable.toString())
    }

    @Test fun candidateMenuShowsLabelsAndAnnotationsButCommitsOnlyText() {
        val connection = Connection()
        val session = session(connection)
        session.type("Tesuto    ")
        val candidate = requireNotNull(session.view.candidate)
        assertEquals("候補4", candidate.selected.text)
        assertEquals("注釈4", candidate.selected.annotation)
        assertEquals('a', candidate.menu.first().label)
        assertEquals("注釈4", candidate.menu.first().candidate.annotation)
        session.type("a")
        assertEquals("候補4", connection.editable.toString())
        assertFalse(connection.editable.toString().contains("注釈"))
    }

    @Test fun internalCursorEditDoesNotTouchCommittedPrefixAndQConvertsWholeReading() {
        val connection = Connection()
        val session = session(connection)
        session.type("aNihon ")
        assertTrue(session.handle(BasicSkkAction.Cancel))
        assertTrue(session.handle(BasicSkkAction.Left))
        assertTrue(session.handle(BasicSkkAction.Left))
        session.type("a")
        assertTrue(session.handle(BasicSkkAction.Delete))
        session.type("q")
        assertEquals("あニアン", connection.editable.toString())
    }
}
