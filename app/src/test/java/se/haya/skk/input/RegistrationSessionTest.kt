package se.haya.skk.input

import android.text.Selection
import android.view.View
import android.view.inputmethod.BaseInputConnection
import se.haya.skk.core.BasicSkkAction
import se.haya.skk.core.BasicSkkDictionary
import se.haya.skk.core.RegistrationSaveRequest
import se.haya.skk.core.RegistrationSaveOutcome
import se.haya.skk.core.RegistrationSaveFailure
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 35])
class RegistrationSessionTest {
    @Test fun `保存中の画面取消は無効で物理C-gの既存操作を変えない`() {
        val f = Fixture()
        f.type("Michi lbody")
        assertTrue(f.session.saveRegistration(f.session.view.registration!!))
        val saving = f.session.view.registration!!
        assertTrue(saving.saving)
        assertFalse(f.session.cancelRegistration(saving))
        assertTrue(f.session.handle(BasicSkkAction.Cancel))
        assertNull(f.session.view.registration)
        f.pending.single().complete(RegistrationSaveOutcome.Applied)
        assertEquals(0, f.connection.commits)
    }

    @Test fun `登録表示の古い操作は別の本文や子階層へ適用しない`() {
        val f = Fixture()
        f.type("Michi lparent")
        val first = f.session.view.registration!!
        assertTrue(f.session.moveRegistrationCursor(0, first))
        assertEquals(0, f.session.view.registration!!.cursor)
        assertFalse(f.session.saveRegistration(first))
        assertFalse(f.session.cancelRegistration(first))
        assertFalse(f.session.moveRegistrationCursor(2, first))
        f.session.handle(BasicSkkAction.Kana)
        f.type("Ko ")
        assertEquals(2, f.session.view.registration!!.depth)
        assertFalse(f.session.cancelRegistration(first))
        val child = f.session.view.registration!!
        assertFalse(f.session.saveRegistration(child))
        assertTrue(f.session.cancelRegistration(child))
        assertEquals(1, f.session.view.registration!!.depth)
        assertTrue(f.pending.isEmpty())
    }

    private class Connection : BaseInputConnection(View(RuntimeEnvironment.getApplication()), true) {
        var commits = 0
        init { Selection.setSelection(editable, 0) }
        override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
            commits++
            return super.commitText(text, newCursorPosition)
        }
    }
    private data class Pending(val request: RegistrationSaveRequest, val complete: (RegistrationSaveOutcome) -> Unit)
    private class Fixture(learningAllowed: Boolean = true) {
        val connection = Connection()
        val pending = mutableListOf<Pending>()
        var renders = 0
        val session = EditorSession(7, connection, false, learningAllowed, 0, 0,
            BasicSkkDictionary { emptyList() },
            registrationSaver = { request, complete -> pending += Pending(request, complete) },
            onStateChanged = { renders++ })
        fun type(value: String) { value.forEach { assertTrue(session.handle(BasicSkkAction.Text(it.toString()))) } }
        fun enter() { assertTrue(session.handle(BasicSkkAction.Enter)) }
        val text: String get() = connection.editable.toString()
    }

    @Test fun registrationBodyNeverReachesEditorBeforeSaveAndSuccessCommitsOnce() {
        val f = Fixture()
        f.type("Michi ")
        assertNotNull(f.session.view.registration)
        assertEquals("みち", f.text)
        f.type("miti")
        assertEquals("みち", f.text)
        assertEquals(0, f.connection.commits)
        f.enter()
        assertEquals(1, f.pending.size)
        assertEquals("みち", f.pending.single().request.candidateText)
        f.enter()
        assertEquals(1, f.pending.size)
        f.pending.single().complete(RegistrationSaveOutcome.Applied)
        assertEquals("みち", f.text)
        assertEquals(1, f.connection.commits)
        assertNull(f.session.view.registration)
        f.pending.single().complete(RegistrationSaveOutcome.Applied)
        assertEquals(1, f.connection.commits)
        assertFalse(f.session.handle(BasicSkkAction.Enter))
    }

    @Test fun closedOrExternallyMovedSessionIgnoresDelayedSaveCompletion() {
        for (close in listOf(false, true)) {
            val f = Fixture()
            f.type("Michi lprivate")
            f.enter()
            assertEquals(1, f.pending.size)
            if (close) f.session.close() else f.session.preserveText()
            f.pending.single().complete(RegistrationSaveOutcome.Applied)
            assertEquals("みち", f.text)
            assertEquals(0, f.connection.commits)
            assertNull(f.session.view.registration)
        }
    }

    @Test fun failedSaveKeepsBodyAndRetryHasNewIdentity() {
        val f = Fixture()
        f.type("Michi lprivate")
        f.enter()
        f.pending.single().complete(RegistrationSaveOutcome.Failed(RegistrationSaveFailure.CAPACITY))
        assertEquals("private", f.session.view.registration?.body)
        assertEquals(0, f.connection.commits)
        f.enter()
        assertEquals(2, f.pending.size)
        assertNotEquals(f.pending[0].request.token, f.pending[1].request.token)
        f.pending[0].complete(RegistrationSaveOutcome.Applied)
        assertEquals(0, f.connection.commits)
        f.pending[1].complete(RegistrationSaveOutcome.SavedButNotApplied)
        assertEquals("private", f.text)
        assertEquals(1, f.connection.commits)
        assertNotNull(f.session.notice)
    }

    @Test fun nestedChildSaveOnlyUpdatesParentAndAbandonKeepsOriginalReading() {
        val f = Fixture()
        f.type("Michi Ko lchild")
        f.enter()
        assertEquals(2, f.session.view.registration?.depth)
        f.pending.single().complete(RegistrationSaveOutcome.Applied)
        assertEquals("child", f.session.view.registration?.body)
        assertEquals(1, f.session.view.registration?.depth)
        assertEquals("みち", f.text)
        assertEquals(0, f.connection.commits)
        f.session.close()
        assertEquals("みち", f.text)
        assertEquals(0, f.connection.commits)
    }

    @Test fun `登録見出しの取消は内側の未確定入力があっても現在階層を取り消す`() {
        val f = Fixture()
        f.type("Michi Ka")
        assertEquals(1, f.session.view.registration?.depth)
        assertEquals("か", f.session.view.registration?.innerComposing)

        assertTrue(f.session.cancelTouchRegistration())
        assertNull(f.session.view.registration)
        assertEquals(0, f.session.engine.state.registrationDepth)
        assertEquals("みち", f.text)
    }

    @Test fun `物理表示と画面表示の切替は登録階層と内側の読みを保持する`() {
        val f = Fixture()
        f.session.setTouchCandidatePresentation(true)
        f.type("Michi Ka")
        assertEquals(1, f.session.view.registration?.depth)
        assertEquals("か", f.session.view.registration?.innerComposing)

        f.session.setTouchCandidatePresentation(false)
        f.session.setTouchCandidatePresentation(true)
        assertEquals(1, f.session.view.registration?.depth)
        assertEquals("か", f.session.view.registration?.innerComposing)
        assertEquals("みち", f.text)
    }

    @Test fun noPersonalizedLearningDoesNotIssueRegistrationSave() {
        val f = Fixture(learningAllowed = false)
        f.type("Michi lprivate")
        f.enter()
        assertTrue(f.pending.isEmpty())
        assertEquals("private", f.session.view.registration?.body)
        assertNotNull(f.session.notice)
        assertEquals("みち", f.text)
        assertEquals(0, f.connection.commits)
    }

    @Test fun protectedSessionNeverCallsSaverAndLateOldCallbackCannotChangeNewConnection() {
        val protectedConnection = Connection()
        val protected = EditorSession(8, protectedConnection, true, false, 0, 0,
            BasicSkkDictionary { error("保護欄で検索してはいけません") },
            registrationSaver = { _, _ -> fail("保護欄で保存してはいけません") })
        assertFalse(protected.handle(BasicSkkAction.Text("Michi ")))
        assertEquals("", protectedConnection.editable.toString())

        val old = Fixture()
        old.type("Michi lprivate")
        old.enter()
        old.session.close()
        val current = Fixture()
        current.type("a")
        old.pending.single().complete(RegistrationSaveOutcome.Applied)
        assertEquals("あ", current.text)
        assertEquals("みち", old.text)
        assertEquals(0, old.connection.commits)
    }

    @Test fun immediateSaveCompletionCommitsOnceWithoutReentrantDuplicate() {
        val connection = Connection()
        var requests = 0
        val session = EditorSession(9, connection, false, true, 0, 0,
            BasicSkkDictionary { emptyList() },
            registrationSaver = { _, complete ->
                requests++
                complete(RegistrationSaveOutcome.Applied)
                complete(RegistrationSaveOutcome.Applied)
            })
        "Michi lprivate".forEach { session.handle(BasicSkkAction.Text(it.toString())) }
        session.handle(BasicSkkAction.Enter)
        assertEquals(1, requests)
        assertEquals(1, connection.commits)
        assertEquals("private", connection.editable.toString())
        assertNull(session.view.registration)
    }
}
