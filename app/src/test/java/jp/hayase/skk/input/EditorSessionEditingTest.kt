package jp.hayase.skk.input

import android.view.View
import android.view.KeyEvent
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.ExtractedText
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.SurroundingText
import jp.hayase.skk.core.BasicSkkAction
import jp.hayase.skk.core.editing.EditCommand
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.concurrent.Executor

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 35])
class EditorSessionEditingTest {
    private class Queue : Executor {
        val tasks = ArrayDeque<Runnable>()
        override fun execute(command: Runnable) { tasks.addLast(command) }
        fun run() { while (tasks.isNotEmpty()) tasks.removeFirst().run() }
    }
    private class Connection : BaseInputConnection(View(RuntimeEnvironment.getApplication()), true) {
        var cursor = 2
        var text = "abc\n"
        val targets = ArrayList<Int>()
        var deletions = 0
        var queries = 0
        var edits = 0
        var accept = true
        var snapshotUnavailable = false
        val nativeDown = ArrayList<Int>()
        val pendingNative = ArrayDeque<Int>()
        var deferNative = false
        var onNative: (() -> Unit)? = null
        var onEdit: (() -> Unit)? = null
        var onQuery: (() -> Unit)? = null
        override fun getExtractedText(request: ExtractedTextRequest?, flags: Int): ExtractedText? {
            queries++
            onQuery?.invoke()
            if (snapshotUnavailable) return null
            return ExtractedText().also {
                it.text = text
                it.startOffset = 0
                it.selectionStart = cursor
                it.selectionEnd = cursor
                it.partialStartOffset = -1
            }
        }
        override fun getSurroundingText(beforeLength: Int, afterLength: Int, flags: Int): SurroundingText? {
            queries++
            onQuery?.invoke()
            if (snapshotUnavailable) return null
            return SurroundingText(text, cursor, cursor, 0)
        }
        override fun setSelection(start: Int, end: Int): Boolean {
            edits++
            if (accept) cursor = start
            targets.add(start)
            onEdit?.invoke()
            return accept
        }
        override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
            edits++
            deletions++
            if (accept) {
                text = text.removeRange(cursor - beforeLength, cursor + afterLength)
                cursor -= beforeLength
            }
            onEdit?.invoke()
            return accept
        }
        override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
            edits++
            if (accept) {
                val inserted = text.toString().replace('\n', ' ')
                this.text = this.text.substring(0, cursor) + inserted + this.text.substring(cursor)
                cursor += inserted.length
            }
            onEdit?.invoke()
            return accept
        }
        override fun sendKeyEvent(event: KeyEvent): Boolean {
            if (event.action == KeyEvent.ACTION_DOWN) {
                nativeDown.add(event.keyCode)
                if (deferNative) pendingNative.addLast(event.keyCode) else applyNative(event.keyCode)
            }
            return true
        }
        fun applyNative(code: Int = pendingNative.removeFirst()) {
            cursor = when (code) {
                KeyEvent.KEYCODE_DPAD_LEFT -> (cursor - 1).coerceAtLeast(0)
                KeyEvent.KEYCODE_DPAD_RIGHT -> (cursor + 1).coerceAtMost(text.length)
                KeyEvent.KEYCODE_DEL -> {
                    if (cursor > 0) {
                        text = text.removeRange(cursor - 1, cursor)
                        cursor - 1
                    } else cursor
                }
                KeyEvent.KEYCODE_FORWARD_DEL -> {
                    if (cursor < text.length) text = text.removeRange(cursor, cursor + 1)
                    cursor
                }
                else -> cursor
            }
            onNative?.invoke()
        }
    }
    private class Fixture(protected: Boolean = false, enabled: Boolean = true,
        text: String = "abc\n", cursor: Int = 2) {
        val connection = Connection().also { it.text = text; it.cursor = cursor }
        val workers = Queue()
        val callbacks = Queue()
        var timeout: Runnable? = null
        val session = EditorSession(1, connection, protected, false, cursor, cursor,
            emacsEnabled = enabled, callbackExecutor = callbacks,
            editPortFactory = { input, generation, state, executor, before ->
                EditorEditPort(input, generation, state, executor, workers,
                    EditScheduler { _, task -> timeout = task; EditCancellation { timeout = null } },
                    { 0L }, before)
            })
        fun left() = session.handle(BasicSkkAction.Edit(EditCommand.LEFT))
        fun run() {
            while (workers.tasks.isNotEmpty() || callbacks.tasks.isNotEmpty()) {
                workers.run()
                callbacks.run()
            }
        }
    }

    @Test fun nextCommandAfterVisibleMutationWaitsForPostcheck() {
        for (earlyAcknowledgement in listOf(false, true)) {
            val f = Fixture()
            f.connection.onEdit = {
                if (earlyAcknowledgement) {
                    f.session.onSelection(f.connection.cursor, f.connection.cursor, -1, -1)
                }
                if (f.connection.edits == 1) assertTrue(f.left())
            }
            assertTrue(f.left())
            f.run()
            assertEquals(listOf(1, 0), f.connection.targets)
            assertNull(f.session.notice)
        }
    }

    @Test fun commandsArrivingBeforeCallbackKeepOrderAndUseConfirmedSelections() {
        val f = Fixture(text = "a👩‍💻b\r\nxy\r\nZabc\r\ntail", cursor = 6)
        assertTrue(f.session.handle(BasicSkkAction.Edit(EditCommand.HOME)))
        f.workers.run()
        assertEquals(0, f.connection.cursor)
        assertTrue(f.session.handle(BasicSkkAction.Edit(EditCommand.RIGHT)))
        assertTrue(f.session.handle(BasicSkkAction.Edit(EditCommand.RIGHT)))
        f.run()
        assertEquals(listOf(0, 1, 6), f.connection.targets)
        assertNull(f.session.notice)
    }

    @Test fun filteredNewlineKeepsNextExplicitCommandEvenBeforePostcheckCompletes() {
        for (earlyAcknowledgement in listOf(false, true)) {
            val f = Fixture(text = "abc", cursor = 3)
            f.connection.onEdit = {
                if (earlyAcknowledgement) {
                    f.session.onSelection(f.connection.cursor, f.connection.cursor, -1, -1)
                }
                if (f.connection.edits == 1) assertTrue(f.left())
            }
            assertTrue(f.session.handle(BasicSkkAction.Edit(EditCommand.NEWLINE)))
            f.run()
            assertEquals("abc ", f.connection.text)
            assertEquals(3, f.connection.cursor)
            assertEquals(1, f.connection.edits)
            assertEquals(listOf(KeyEvent.KEYCODE_DPAD_LEFT), f.connection.nativeDown)
        }
    }

    @Test fun rejectedRequestKeepsNextQueuedExplicitCommandWithoutRetryingFirst() {
        val f = Fixture()
        f.connection.accept = false
        f.left()
        f.session.handle(BasicSkkAction.Edit(EditCommand.RIGHT))
        f.workers.run()
        f.connection.accept = true
        f.run()
        assertEquals(listOf(1, 3), f.connection.targets)
        assertEquals(3, f.connection.cursor)
    }

    @Test fun unverifiedNewlineAcknowledgementKeepsQueuedMovement() {
        val f = Fixture(text = "abc", cursor = 3)
        f.connection.snapshotUnavailable = true
        f.connection.onEdit = {
            f.left()
            f.session.onSelection(f.connection.cursor, f.connection.cursor, -1, -1)
        }
        f.session.handle(BasicSkkAction.Edit(EditCommand.NEWLINE))
        f.run()
        assertEquals("abc ", f.connection.text)
        assertEquals(3, f.connection.cursor)
        assertEquals(1, f.connection.edits)
        assertEquals(listOf(KeyEvent.KEYCODE_DPAD_LEFT), f.connection.nativeDown)
        assertNull(f.session.notice)
    }

    @Test fun newlineContinuesNativeSequenceAcrossDelayedSelectionCallback() {
        val f = Fixture(text = "abc", cursor = 3)
        f.left()
        f.run()
        val queries = f.connection.queries
        f.session.handle(BasicSkkAction.Edit(EditCommand.NEWLINE))
        f.session.onSelection(2, 2, -1, -1)
        f.left()
        f.run()
        assertEquals("ab c", f.connection.text)
        assertEquals(2, f.connection.cursor)
        assertEquals(queries, f.connection.queries)
        assertEquals(1, f.connection.edits)
        assertEquals(listOf(KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_LEFT),
            f.connection.nativeDown)
        assertNull(f.session.notice)
    }

    @Test fun queuedVerticalCommandsPreservePreferredColumn() {
        val f = Fixture(text = "a👩‍💻b\r\nxy\r\nZabc\r\ntail", cursor = 6)
        repeat(2) { assertTrue(f.session.handle(BasicSkkAction.Edit(EditCommand.DOWN))) }
        f.run()
        assertEquals(listOf(11, 15), f.connection.targets)
        assertNull(f.session.notice)
    }

    @Test fun delayedAcknowledgementDuringQueuedCommandDoesNotRollBackSelection() {
        val f = Fixture()
        f.connection.onEdit = {
            if (f.connection.edits == 2) f.session.onSelection(1, 1, -1, -1)
        }
        f.left()
        f.left()
        f.session.handle(BasicSkkAction.Edit(EditCommand.RIGHT))
        f.run()
        assertEquals(listOf(1, 0, 1), f.connection.targets)
        assertNull(f.session.notice)
    }

    @Test fun queuedDestructiveCommandsAreAppliedOnceEach() {
        val f = Fixture(text = "abcdef\n", cursor = 1)
        f.connection.onEdit = {
            if (f.connection.edits == 1) {
                assertTrue(f.session.handle(BasicSkkAction.Edit(EditCommand.DELETE)))
            }
        }
        assertTrue(f.session.handle(BasicSkkAction.Edit(EditCommand.DELETE)))
        f.run()
        assertEquals("adef\n", f.connection.text)
        assertEquals(2, f.connection.deletions)
        assertNull(f.session.notice)
    }

    @Test fun noChangeAlsoDrainsWaitingCommands() {
        val f = Fixture(cursor = 0)
        f.left()
        f.left()
        f.session.handle(BasicSkkAction.Edit(EditCommand.RIGHT))
        f.run()
        assertEquals(listOf(1), f.connection.targets)
        assertNull(f.session.notice)
    }

    @Test fun windowEndMovementUsesOneNativeKeyPairWithoutChangingText() {
        val forward = Fixture(text = "abc", cursor = 2)
        assertTrue(forward.session.handle(BasicSkkAction.Edit(EditCommand.RIGHT)))
        forward.run()
        assertEquals(3, forward.connection.cursor)
        assertEquals("abc", forward.connection.text)
        assertEquals(listOf(KeyEvent.KEYCODE_DPAD_RIGHT), forward.connection.nativeDown)
        assertNull(forward.session.notice)

        val backward = Fixture(text = "abc", cursor = 3)
        assertTrue(backward.left())
        backward.run()
        assertEquals(2, backward.connection.cursor)
        assertEquals(listOf(KeyEvent.KEYCODE_DPAD_LEFT), backward.connection.nativeDown)
        assertNull(backward.session.notice)
    }

    @Test fun unchangedNativeMovementDrainsQueuedReverseMovementWithoutAcknowledgement() {
        val f = Fixture(text = "abcd", cursor = 4)
        f.connection.deferNative = true
        f.session.handle(BasicSkkAction.Edit(EditCommand.RIGHT))
        f.left()
        f.left()
        f.session.handle(BasicSkkAction.Edit(EditCommand.RIGHT))
        f.run()
        assertEquals(listOf(KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT), f.connection.nativeDown)
        assertEquals(4, f.connection.cursor)
        assertEquals(2, f.connection.queries)
        while (f.connection.pendingNative.isNotEmpty()) f.connection.applyNative()
        assertEquals(3, f.connection.cursor)
        assertEquals("abcd", f.connection.text)
        assertEquals(0, f.connection.edits)
        assertNull(f.session.notice)
    }

    @Test fun nativeSelectionNotificationBeforeCompletionDoesNotDiscardRepeats() {
        val f = Fixture(text = "abcd", cursor = 4)
        f.connection.onNative = {
            f.session.onSelection(f.connection.cursor, f.connection.cursor, -1, -1)
        }
        repeat(3) { f.left() }
        f.run()
        assertEquals(1, f.connection.cursor)
        assertEquals(3, f.connection.nativeDown.size)
        assertNull(f.session.notice)
    }

    @Test fun queuedNativePageAndBufferCommandsKeepTheirOrder() {
        val f = Fixture(text = "abcd", cursor = 4)
        val commands = listOf(EditCommand.PAGE_DOWN, EditCommand.PAGE_DOWN,
            EditCommand.PAGE_UP, EditCommand.BUFFER_START, EditCommand.BUFFER_END)
        commands.forEach { f.session.handle(BasicSkkAction.Edit(it)) }
        f.run()
        assertEquals(listOf(KeyEvent.KEYCODE_PAGE_DOWN, KeyEvent.KEYCODE_PAGE_DOWN,
            KeyEvent.KEYCODE_PAGE_UP, KeyEvent.KEYCODE_MOVE_HOME, KeyEvent.KEYCODE_MOVE_END),
            f.connection.nativeDown)
        assertEquals(2, f.connection.queries)
        assertNull(f.session.notice)
    }

    @Test fun nativeMovementOrdersDeletionWithoutSpeculativeSelection() {
        val f = Fixture(text = "abcd", cursor = 4)
        f.connection.deferNative = true
        f.left()
        f.run()
        f.connection.applyNative()
        // 移動後の通知が未着でも、同じ接続へ標準キーを順に送ります。
        f.session.handle(BasicSkkAction.Edit(EditCommand.BACKSPACE))
        f.run()
        assertEquals(listOf(KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DEL), f.connection.nativeDown)
        assertEquals(0, f.connection.deletions)
        assertEquals("abcd", f.connection.text)
        f.connection.applyNative()
        assertEquals("abd", f.connection.text)
        assertEquals(2, f.connection.cursor)
        assertNull(f.session.notice)
    }

    @Test fun longHoldKeepsOnlySixtyFourWaitingCommandsAndCanContinueAfterOverflow() {
        val f = Fixture(text = "a".repeat(200) + "\n", cursor = 100)
        repeat(200) { assertTrue(f.left()) }
        f.run()
        assertEquals(65, f.connection.edits)
        assertEquals(35, f.connection.cursor)
        assertTrue(f.left())
        f.run()
        assertEquals(34, f.connection.cursor)
        assertNull(f.session.notice)
    }

    @Test fun independentInputOrSelectionDiscardsAllWaitingCommands() {
        for (key in listOf(false, true)) {
            val f = Fixture()
            repeat(3) { assertTrue(f.left()) }
            if (key) f.session.handle(BasicSkkAction.Text("k"))
            else f.session.onSelection(2, 2, -1, -1)
            f.run()
            assertEquals(0, f.connection.edits)
        }
    }

    @Test fun ordinaryInputAfterVisibleMutationKeepsCompositionAndDiscardsQueuedEdits() {
        val f = Fixture()
        f.connection.onEdit = {
            if (f.connection.edits == 1) {
                f.left()
                assertTrue(f.session.handle(BasicSkkAction.Text("k")))
            }
        }
        f.left()
        f.run()
        assertEquals(1, f.connection.edits)
        assertEquals("k", f.session.displayedComposition)
        assertTrue(f.session.hasComposition)
    }

    @Test fun invalidationBeforeSuccessfulCallbackDoesNotRetainNewlyQueuedCommands() {
        val f = Fixture()
        f.left()
        f.workers.run()
        f.session.onSelection(1, 1, -1, -1)
        f.session.onSelection(1, 1, -1, -1)
        f.left()
        f.callbacks.run()
        f.session.handle(BasicSkkAction.Edit(EditCommand.RIGHT))
        f.run()
        assertEquals(listOf(1, 2), f.connection.targets)
        assertNull(f.session.notice)
    }

    @Test fun independentChangeAfterVisibleMutationDiscardsQueueAndStopsPort() {
        val f = Fixture()
        f.connection.onEdit = {
            if (f.connection.edits == 1) {
                f.left()
                f.session.onSelection(2, 2, -1, -1)
            }
        }
        f.left()
        f.run()
        f.left()
        f.run()
        assertEquals(1, f.connection.edits)
    }

    @Test fun timeoutAfterVisibleDeletionDiscardsQueueWithoutRetry() {
        val f = Fixture(text = "abcdef\n", cursor = 1)
        f.connection.onEdit = {
            assertTrue(f.session.handle(BasicSkkAction.Edit(EditCommand.DELETE)))
            f.timeout!!.run()
        }
        assertTrue(f.session.handle(BasicSkkAction.Edit(EditCommand.DELETE)))
        f.run()
        assertTrue(f.session.handle(BasicSkkAction.Edit(EditCommand.DELETE)))
        f.run()
        assertEquals("acdef\n", f.connection.text)
        assertEquals(1, f.connection.deletions)
        assertNotNull(f.session.notice)
    }

    @Test fun earlyAndLateExpectedAcknowledgementsAllowNextEdit() {
        for (early in listOf(false, true)) {
            val f = Fixture()
            if (early) f.connection.onEdit = { f.session.onSelection(f.connection.cursor, f.connection.cursor, -1, -1) }
            assertTrue(f.left())
            f.run()
            if (!early) f.session.onSelection(1, 1, -1, -1)
            assertTrue(f.left())
            f.run()
            assertEquals(0, f.connection.cursor)
            assertEquals(2, f.connection.edits)
            assertNull(f.session.notice)
        }
    }

    @Test fun previousAcknowledgementDuringNextRequestDoesNotRollBackOrDisableEditing() {
        val f = Fixture()
        assertTrue(f.left())
        f.run()
        f.connection.onEdit = { f.session.onSelection(1, 1, -1, -1) }
        assertTrue(f.left())
        f.run()
        f.connection.onEdit = null
        assertTrue(f.session.handle(BasicSkkAction.Edit(EditCommand.RIGHT)))
        f.run()
        assertEquals(3, f.connection.edits)
        assertEquals(1, f.connection.cursor)
        assertNull(f.session.notice)
    }

    @Test fun unrelatedKeyAndSamePositionNotificationInvalidateQueuedPlan() {
        for (key in listOf(false, true)) {
            val f = Fixture()
            assertTrue(f.left())
            if (key) f.session.handle(BasicSkkAction.Text("k")) else f.session.onSelection(2, 2, -1, -1)
            f.run()
            assertEquals(0, f.connection.edits)
        }
    }

    @Test fun closingOldSessionCannotSendToEitherConnection() {
        val old = Fixture()
        repeat(3) { old.left() }
        old.session.close()
        val next = Fixture()
        old.run()
        assertEquals(0, old.connection.edits)
        assertEquals(0, next.connection.edits)
    }

    @Test fun protectedDisabledAndInternalCompositionNeverReadExternalText() {
        for (f in listOf(Fixture(protected = true), Fixture(enabled = false))) {
            assertFalse(f.left())
            f.run()
            assertEquals(0, f.connection.queries)
        }
        val f = Fixture()
        assertTrue(f.session.handle(BasicSkkAction.Text("K")))
        assertTrue(f.left())
        f.run()
        assertEquals(0, f.connection.queries)
    }

    @Test fun rejectedRequestIsConsumedAndNextExplicitEditCanRecover() {
        val f = Fixture()
        f.connection.accept = false
        assertTrue(f.left())
        f.run()
        assertNotNull(f.session.notice)
        assertEquals(1, f.connection.edits)
        f.connection.accept = true
        assertTrue(f.left())
        f.run()
        assertEquals(2, f.connection.edits)
        assertEquals(1, f.connection.cursor)
    }

    @Test fun unexpectedCallbackDuringRequestPermanentlyStopsPort() {
        val f = Fixture()
        f.connection.onEdit = { f.session.onSelection(2, 2, -1, -1) }
        assertTrue(f.left())
        f.run()
        assertTrue(f.left())
        f.run()
        assertEquals(1, f.connection.edits)
    }

    @Test fun timeoutAndBusyNeverDispatchAlternateRequests() {
        val f = Fixture()
        assertTrue(f.left())
        assertTrue(f.left())
        f.timeout!!.run()
        f.run()
        assertEquals(0, f.connection.edits)
        assertTrue(f.left())
        f.run()
        assertEquals(0, f.connection.edits)
    }

    @Test fun expectedAcknowledgementCannotSurviveAnIndependentNotification() {
        val f = Fixture()
        f.left()
        f.run()
        f.session.onSelection(2, 2, -1, -1)
        f.left()
        f.session.onSelection(1, 1, -1, -1)
        f.run()
        assertEquals(1, f.connection.edits)
    }
}
