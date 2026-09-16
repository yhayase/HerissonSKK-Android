package jp.hayase.skk.input

import android.text.Selection
import android.view.View
import android.view.inputmethod.BaseInputConnection
import jp.hayase.skk.core.*
import jp.hayase.skk.core.dictionary.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 35])
class CandidateDeletionSessionTest {
    private class Connection : BaseInputConnection(View(RuntimeEnvironment.getApplication()), true) {
        var commits = 0
        var rejectComposing = false
        init { Selection.setSelection(editable, 0) }
        override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
            commits++
            return super.commitText(text, newCursorPosition)
        }
        override fun setComposingText(text: CharSequence?, newCursorPosition: Int): Boolean =
            !rejectComposing && super.setComposingText(text, newCursorPosition)
    }

    private fun dictionary(text: String): BasicSkkDictionary = CompositeSkkDictionary(
        SkkDictionarySource("personal", 0, emptyList()),
        listOf(SkkDictionarySource("system", 1, SkkDictionaryCodec.parseText(text).entries)),
    )

    @Test fun `確認と削除完了は文字を確定せず二重完了も再描画しない`() {
        var current = dictionary("にほん /日本/二本/")
        val connection = Connection()
        val requests = mutableListOf<CandidateDeletionRequest>()
        var complete: ((CandidateDeletionOutcome) -> Unit)? = null
        var renders = 0
        val session = EditorSession(1, connection, false, true, 0, 0,
            BasicSkkDictionary { current.lookup(it) }, onStateChanged = { renders++ },
            candidateDeleter = { request, callback -> requests += request; complete = callback })
        session.handle(BasicSkkAction.Text("Nihon "))
        session.handle(BasicSkkAction.Text("X"))
        assertNotNull(session.view.deletion)
        session.handle(BasicSkkAction.Enter)
        assertTrue(requests.isEmpty())
        session.handle(BasicSkkAction.Text("y"))
        session.handle(BasicSkkAction.Text("y"))
        assertEquals(1, requests.size)
        assertEquals("日本", connection.editable.toString())
        assertEquals(0, connection.commits)
        current = dictionary("にほん /二本/")
        complete!!(CandidateDeletionOutcome.Applied)
        assertEquals("二本", connection.editable.toString())
        assertEquals(0, connection.commits)
        assertNull(session.view.deletion)
        assertEquals(1, renders)
        complete!!(CandidateDeletionOutcome.Applied)
        assertEquals(1, renders)
        session.handle(BasicSkkAction.Enter)
        assertEquals(1, connection.commits)
    }

    @Test fun `取消と学習禁止は削除要求を出さず保存中取消後の完了を捨てる`() {
        val source = dictionary("にほん /日本/二本/")
        val connection = Connection()
        var complete: ((CandidateDeletionOutcome) -> Unit)? = null
        var requests = 0
        val session = EditorSession(2, connection, false, true, 0, 0, source,
            candidateDeleter = { _, callback -> requests++; complete = callback })
        session.handle(BasicSkkAction.Text("Nihon "))
        session.handle(BasicSkkAction.Text("X"))
        session.handle(BasicSkkAction.Text("n"))
        assertEquals(0, requests)
        assertEquals("日本", connection.editable.toString())
        session.handle(BasicSkkAction.Text("X"))
        session.handle(BasicSkkAction.Text("y"))
        session.handle(BasicSkkAction.Cancel)
        val abandoned = connection.editable.toString()
        complete!!(CandidateDeletionOutcome.Applied)
        assertEquals(abandoned, connection.editable.toString())
        assertEquals(0, connection.commits)

        val forbidden = EditorSession(3, Connection(), false, false, 0, 0, source,
            candidateDeleter = { _, _ -> fail("学習禁止欄で削除してはいけません") })
        forbidden.handle(BasicSkkAction.Text("Nihon "))
        forbidden.handle(BasicSkkAction.Text("X"))
        assertNull(forbidden.view.deletion)
        assertNotNull(forbidden.notice)
    }

    @Test fun `終了した接続の削除完了は次の接続へ出力しない`() {
        val oldConnection = Connection()
        var complete: ((CandidateDeletionOutcome) -> Unit)? = null
        var renders = 0
        val old = EditorSession(4, oldConnection, false, true, 0, 0, dictionary("にほん /日本/"),
            onStateChanged = { renders++ }, candidateDeleter = { _, callback -> complete = callback })
        old.handle(BasicSkkAction.Text("Nihon "))
        old.handle(BasicSkkAction.Text("X"))
        old.handle(BasicSkkAction.Text("y"))
        old.close()
        val newConnection = Connection()
        val next = EditorSession(5, newConnection, false, true, 0, 0)
        next.handle(BasicSkkAction.Text("a"))
        complete!!(CandidateDeletionOutcome.Failed(CandidateDeletionFailure.CAPACITY))
        assertEquals("あ", newConnection.editable.toString())
        assertEquals(0, renders)
        assertEquals(0, oldConnection.commits)
    }

    @Test fun `登録内の候補削除で親本文と入力先の読みを変えない`() {
        var current = dictionary("にほん /日本/二本/")
        val connection = Connection()
        var complete: ((CandidateDeletionOutcome) -> Unit)? = null
        val session = EditorSession(6, connection, false, true, 0, 0,
            BasicSkkDictionary { current.lookup(it) }, registrationSaver = { _, _ -> fail("未保存です") },
            candidateDeleter = { _, callback -> complete = callback })
        session.handle(BasicSkkAction.Text("Michi "))
        session.handle(BasicSkkAction.Text("maNihon "))
        val originalEditor = connection.editable.toString()
        val originalBody = session.view.registration!!.body
        session.handle(BasicSkkAction.Text("X"))
        session.handle(BasicSkkAction.Text("y"))
        current = dictionary("にほん /二本/")
        complete!!(CandidateDeletionOutcome.Applied)
        assertEquals(originalEditor, connection.editable.toString())
        assertEquals(originalBody, session.view.registration!!.body)
        assertEquals("二本", session.view.registration!!.innerCandidate?.selected?.text)
        assertEquals(0, connection.commits)
    }

    @Test fun `保護入力と接続失敗は削除要求を発行しない`() {
        var requests = 0
        val protected = EditorSession(7, Connection(), true, true, 0, 0, dictionary("にほん /日本/"),
            candidateDeleter = { _, _ -> requests++ })
        listOf(BasicSkkAction.Text("Nihon "), BasicSkkAction.Text("X"), BasicSkkAction.Text("y"))
            .forEach { assertFalse(protected.handle(it)) }
        assertEquals(0, requests)

        val connection = Connection()
        val failed = EditorSession(8, connection, false, true, 0, 0, dictionary("にほん /日本/"),
            candidateDeleter = { _, _ -> requests++ })
        failed.handle(BasicSkkAction.Text("Nihon "))
        assertTrue(failed.handle(BasicSkkAction.Text("X")))
        connection.rejectComposing = true
        assertTrue(failed.handle(BasicSkkAction.Text("y")))
        assertTrue(failed.failed)
        assertEquals(0, requests)
        assertFalse(failed.handle(BasicSkkAction.Text("y")))
        assertEquals(0, requests)
    }

    @Test fun `外部選択移動後の遅い削除完了は入力先と再描画を変えない`() {
        val connection = Connection()
        var complete: ((CandidateDeletionOutcome) -> Unit)? = null
        var renders = 0
        val session = EditorSession(9, connection, false, true, 0, 0, dictionary("にほん /日本/二本/"),
            onStateChanged = { renders++ }, candidateDeleter = { _, callback -> complete = callback })
        session.handle(BasicSkkAction.Text("Nihon Xy"))
        val before = connection.editable.toString()

        assertTrue(session.onSelection(20, 20, -1, -1))
        complete!!(CandidateDeletionOutcome.Applied)

        assertEquals(before, connection.editable.toString())
        assertEquals(0, connection.commits)
        assertEquals(0, renders)
        assertNull(session.view.deletion)
    }

    @Test fun `保存済み未反映と各失敗は確定せず適切な候補表示を保つ`() {
        run {
            val connection = Connection()
            var complete: ((CandidateDeletionOutcome) -> Unit)? = null
            val session = EditorSession(10, connection, false, true, 0, 0,
                dictionary("にほん /日本/二本/"), candidateDeleter = { _, callback -> complete = callback })
            session.handle(BasicSkkAction.Text("Nihon Xy"))
            complete!!(CandidateDeletionOutcome.SavedButNotApplied)
            assertEquals("二本", connection.editable.toString())
            assertEquals("二本", session.view.candidate?.selected?.text)
            assertTrue(session.notice!!.contains("再読込"))
            assertEquals(0, connection.commits)
        }

        CandidateDeletionFailure.entries.forEachIndexed { index, failure ->
            var current = dictionary("にほん /日本/二本/")
            val connection = Connection()
            var complete: ((CandidateDeletionOutcome) -> Unit)? = null
            val session = EditorSession(20L + index, connection, false, true, 0, 0,
                BasicSkkDictionary { current.lookup(it) },
                candidateDeleter = { _, callback -> complete = callback })
            session.handle(BasicSkkAction.Text("Nihon Xy"))
            if (failure == CandidateDeletionFailure.CONFLICT) current = dictionary("にほん /二本/")

            complete!!(CandidateDeletionOutcome.Failed(failure))

            val expected = if (failure == CandidateDeletionFailure.CONFLICT) "二本" else "日本"
            assertEquals(expected, connection.editable.toString())
            assertEquals(expected, session.view.candidate?.selected?.text)
            assertNotNull(session.notice)
            assertNull(session.view.deletion)
            assertEquals(0, connection.commits)
        }
    }

    @Test fun `確認取消と削除成功失敗は候補学習を発行しない`() {
        var current = dictionary("にほん /日本/二本/")
        var learned = 0
        var complete: ((CandidateDeletionOutcome) -> Unit)? = null
        val connection = Connection()
        val session = EditorSession(30, connection, false, true, 0, 0,
            BasicSkkDictionary { current.lookup(it) },
            candidateLearner = { _, _ -> learned++ },
            candidateDeleter = { _, callback -> complete = callback })
        session.handle(BasicSkkAction.Text("Nihon Xn"))
        assertEquals(0, learned)

        session.handle(BasicSkkAction.Text("Xy"))
        complete!!(CandidateDeletionOutcome.Failed(CandidateDeletionFailure.CAPACITY))
        assertEquals(0, learned)

        session.handle(BasicSkkAction.Text("Xy"))
        current = dictionary("にほん /二本/")
        complete!!(CandidateDeletionOutcome.Applied)
        assertEquals(0, learned)
        assertEquals(0, connection.commits)
    }

    @Test fun `登録内の確認取消と待機取消と失敗は親本文と入力先を保つ`() {
        val current = dictionary("にほん /日本/二本/")
        val connection = Connection()
        val completions = mutableListOf<(CandidateDeletionOutcome) -> Unit>()
        val session = EditorSession(31, connection, false, true, 0, 0, current,
            registrationSaver = { _, _ -> fail("未保存です") },
            candidateDeleter = { _, callback -> completions += callback })
        session.handle(BasicSkkAction.Text("Michi "))
        session.handle(BasicSkkAction.Text("maNihon "))
        val editor = connection.editable.toString()
        val body = session.view.registration!!.body
        val cursor = session.view.registration!!.cursor

        session.handle(BasicSkkAction.Text("Xn"))
        assertParentUnchanged(session, connection, editor, body, cursor)

        session.handle(BasicSkkAction.Text("Xy"))
        session.handle(BasicSkkAction.Cancel)
        completions.removeFirst()(CandidateDeletionOutcome.Applied)
        assertParentUnchanged(session, connection, editor, body, cursor)

        session.handle(BasicSkkAction.Text(" Xy"))
        completions.removeFirst()(CandidateDeletionOutcome.Failed(CandidateDeletionFailure.GENERAL))
        assertParentUnchanged(session, connection, editor, body, cursor)
        assertEquals(0, connection.commits)
    }

    private fun assertParentUnchanged(
        session: EditorSession,
        connection: Connection,
        editor: String,
        body: String,
        cursor: Int,
    ) {
        assertEquals(editor, connection.editable.toString())
        assertEquals(body, session.view.registration?.body)
        assertEquals(cursor, session.view.registration?.cursor)
    }
}
