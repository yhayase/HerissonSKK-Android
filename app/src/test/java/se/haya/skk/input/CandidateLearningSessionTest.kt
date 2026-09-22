package se.haya.skk.input

import android.text.Selection
import android.view.View
import android.view.inputmethod.BaseInputConnection
import se.haya.skk.core.*
import se.haya.skk.dictionary.BuiltinDictionary
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 35])
class CandidateLearningSessionTest {
    private class Connection : BaseInputConnection(View(RuntimeEnvironment.getApplication()), true) {
        var rejectCommit = false
        init { Selection.setSelection(editable, 0) }
        override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean =
            !rejectCommit && super.commitText(text, newCursorPosition)
    }

    @Test fun `確定が成功した候補だけを一度学習し保存失敗でも入力は消さない`() {
        val connection = Connection()
        val learned = mutableListOf<CandidateCommitRequest>()
        var complete: ((RegistrationSaveOutcome) -> Unit)? = null
        val session = EditorSession(1, connection, false, true, 0, 0,
            BuiltinDictionary.dictionary, candidateLearner = { request, callback ->
                assertEquals("二本あ", connection.editable.toString())
                learned += request
                complete = callback
            })
        session.handle(BasicSkkAction.Text("Nihon  "))
        session.handle(BasicSkkAction.Text("a"))
        assertEquals(1, learned.size)
        assertEquals("二本", learned.single().candidate.text)
        complete!!(RegistrationSaveOutcome.Failed(RegistrationSaveFailure.CAPACITY))
        assertEquals("二本あ", connection.editable.toString())
        assertNotNull(session.notice)
        session.handle(BasicSkkAction.Enter)
        assertEquals(1, learned.size)
    }

    @Test fun `確定拒否と学習禁止では学習要求を送らない`() {
        for (allow in listOf(false, true)) {
            val connection = Connection().apply { rejectCommit = allow }
            val session = EditorSession(2, connection, false, allow, 0, 0,
                BuiltinDictionary.dictionary, candidateLearner = { _, _ -> fail("学習してはいけません") })
            session.handle(BasicSkkAction.Text("Nihon "))
            session.handle(BasicSkkAction.Enter)
            assertEquals("日本", connection.editable.toString())
            assertEquals(allow, session.failed)
        }
    }

    @Test fun `保護欄は学習可能な接続設定でもコアと学習を迂回する`() {
        val connection = Connection()
        val session = EditorSession(3, connection, true, true, 0, 0,
            BasicSkkDictionary { error("保護欄で検索してはいけません") },
            candidateLearner = { _, _ -> fail("保護欄で学習してはいけません") })
        assertFalse(session.handle(BasicSkkAction.Text("Nihon ")))
        assertFalse(session.handle(BasicSkkAction.Enter))
        assertEquals("", connection.editable.toString())
    }

    @Test fun `終了した入力欄の学習失敗通知は新しい入力欄を変更しない`() {
        val oldConnection = Connection()
        var complete: ((RegistrationSaveOutcome) -> Unit)? = null
        var oldRenders = 0
        val old = EditorSession(4, oldConnection, false, true, 0, 0,
            BuiltinDictionary.dictionary, onStateChanged = { oldRenders++ },
            candidateLearner = { _, callback -> complete = callback })
        old.handle(BasicSkkAction.Text("Nihon "))
        old.handle(BasicSkkAction.Enter)
        old.close()
        val newConnection = Connection()
        val current = EditorSession(5, newConnection, false, true, 0, 0)
        current.handle(BasicSkkAction.Text("a"))
        complete!!(RegistrationSaveOutcome.Failed(RegistrationSaveFailure.GENERAL))
        assertEquals("日本", oldConnection.editable.toString())
        assertEquals("あ", newConnection.editable.toString())
        assertNull(current.notice)
        assertEquals(0, oldRenders)
    }
}
