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
class CompletionSessionTest {
    private class Connection : BaseInputConnection(View(RuntimeEnvironment.getApplication()), true) {
        val commits = mutableListOf<String>()
        val compositions = mutableListOf<String>()
        var reject = false
        init { Selection.setSelection(editable, 0) }
        override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
            commits += text.toString()
            return super.commitText(text, newCursorPosition)
        }
        override fun setComposingText(text: CharSequence?, newCursorPosition: Int): Boolean {
            compositions += text.toString()
            return !reject && super.setComposingText(text, newCursorPosition)
        }
    }

    private fun dictionary() = CompositeSkkDictionary(
        SkkDictionarySource("personal", 1, SkkDictionaryCodec.parseText("に /二/\nにほん /日本/\n").entries),
        emptyList(),
    )

    private fun session(connection: Connection, protected: Boolean = false, saving: Boolean = true,
        dynamic: Boolean = true, dictionary: BasicSkkDictionary = dictionary()) =
        EditorSession(1, connection, protected, saving, 0, 0, dictionary,
            completionConfig = CompletionConfig(dynamicEnabled = dynamic))

    @Test fun `未受諾補完は入力先へ渡さずSpaceは元の読みを変換する`() {
        val connection = Connection()
        val session = session(connection, saving = false)
        session.handle(BasicSkkAction.Text("Ni"))
        assertEquals("に", connection.editable.toString())
        assertEquals("ほん", session.view.completion?.suffix)
        assertTrue(connection.commits.isEmpty())
        assertFalse(connection.compositions.any { it.contains("ほん") })
        session.handle(BasicSkkAction.Text(" "))
        assertEquals("二", connection.editable.toString())
        session.handle(BasicSkkAction.Enter)
        assertEquals(listOf("二"), connection.commits)
    }

    @Test fun `明示受諾だけが補完全体を入力先compositionへ取り込む`() {
        for (action in listOf(BasicSkkAction.AcceptDynamicCompletion, BasicSkkAction.CompleteForward)) {
            val connection = Connection()
            val session = session(connection)
            session.handle(BasicSkkAction.Text("Ni"))
            session.handle(action)
            assertEquals("にほん", connection.editable.toString())
            assertTrue(connection.commits.isEmpty())
            session.handle(BasicSkkAction.Text(" "))
            session.handle(BasicSkkAction.Enter)
            assertEquals(listOf("日本"), connection.commits)
        }
    }

    @Test fun `確定や終了や外部移動は未受諾部分を入力先へ残さない`() {
        for (operation in 0..2) {
            val connection = Connection()
            val session = session(connection)
            session.handle(BasicSkkAction.Text("Ni"))
            when (operation) {
                0 -> session.handle(BasicSkkAction.Enter)
                1 -> session.close()
                else -> session.onSelection(0, 0, -1, -1)
            }
            assertFalse(connection.editable.toString().contains("ほん"))
            assertFalse(connection.commits.any { it.contains("ほん") })
            assertNull(session.view.completion)
        }
    }

    @Test fun `接続拒否後は補完を再送せず保護欄では検索しない`() {
        val connection = Connection().apply { reject = true }
        val failed = session(connection)
        failed.handle(BasicSkkAction.Text("Ni"))
        assertTrue(failed.failed)
        val count = connection.compositions.size
        assertFalse(failed.handle(BasicSkkAction.AcceptDynamicCompletion))
        assertEquals(count, connection.compositions.size)
        assertNull(failed.view.completion)
        val forbidden = object : BasicSkkDictionary {
            override fun lookup(query: DictionaryQuery): List<DictionaryCandidate> = error("保護欄で検索しました")
            override fun complete(query: CompletionQuery): List<String> = error("保護欄で補完しました")
        }
        val protected = session(Connection(), protected = true, dictionary = forbidden)
        assertFalse(protected.handle(BasicSkkAction.Text("Ni")))
        assertFalse(protected.handle(BasicSkkAction.CompleteForward))
    }

    @Test fun `動的補完は既定で無効でも手動補完を利用できる`() {
        val connection = Connection()
        val session = EditorSession(1, connection, false, true, 0, 0, dictionary())
        session.handle(BasicSkkAction.Text("Ni"))
        assertNull(session.view.completion)
        session.handle(BasicSkkAction.CompleteForward)
        assertEquals("にほん", connection.editable.toString())
    }
}
