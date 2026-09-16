package jp.hayase.skk.input

import android.view.View
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
        var onEdit: (() -> Unit)? = null
        var onQuery: (() -> Unit)? = null
        override fun getExtractedText(request: ExtractedTextRequest?, flags: Int): ExtractedText {
            queries++
            onQuery?.invoke()
            return ExtractedText().also {
                it.text = text
                it.startOffset = 0
                it.selectionStart = cursor
                it.selectionEnd = cursor
                it.partialStartOffset = -1
            }
        }
        override fun getSurroundingText(beforeLength: Int, afterLength: Int, flags: Int): SurroundingText {
            queries++
            onQuery?.invoke()
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

    @Test fun rejectedRequestIsConsumedAndDisablesFurtherExternalEdits() {
        val f = Fixture()
        f.connection.accept = false
        assertTrue(f.left())
        f.run()
        assertNotNull(f.session.notice)
        assertTrue(f.left())
        f.run()
        assertEquals(1, f.connection.edits)
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
