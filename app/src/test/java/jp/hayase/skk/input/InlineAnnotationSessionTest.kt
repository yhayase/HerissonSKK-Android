package jp.hayase.skk.input

import android.text.Selection
import android.view.View
import android.view.inputmethod.BaseInputConnection
import jp.hayase.skk.core.BasicSkkAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 35])
class InlineAnnotationSessionTest {
    private class Connection : BaseInputConnection(View(RuntimeEnvironment.getApplication()), true) {
        var writable = true
        val sent = mutableListOf<String>()
        init { Selection.setSelection(editable, 0) }
        override fun setComposingText(text: CharSequence?, cursor: Int): Boolean {
            sent += text.toString()
            return writable && super.setComposingText(text, cursor)
        }
        override fun commitText(text: CharSequence?, cursor: Int): Boolean {
            sent += text.toString()
            return writable && super.commitText(text, cursor)
        }
        override fun finishComposingText(): Boolean = writable && super.finishComposingText()
    }

    @Test fun `注釈は入力接続へ送らず候補の変更と確定で本文だけを渡す`() {
        val connection = Connection()
        val session = EditorSession(1, connection, false, true, 0, 0)
        session.handle(BasicSkkAction.Text("Tesuto "))
        assertEquals("候補1", connection.editable.toString())
        assertEquals("注釈1", session.view.candidate?.selected?.annotation)
        session.handle(BasicSkkAction.ConvertNext)
        assertEquals("候補2", connection.editable.toString())
        session.handle(BasicSkkAction.Enter)
        assertEquals("候補2", connection.editable.toString())
        assertTrue(connection.sent.none { it.contains("注釈") })
    }

    @Test fun `フォーカス切替前に接続が失効しても注釈は本文へ残らない`() {
        val connection = Connection()
        val session = EditorSession(1, connection, false, true, 0, 0)
        session.handle(BasicSkkAction.Text("Tesuto "))
        connection.writable = false
        session.close()
        assertEquals("候補1", connection.editable.toString())
        assertTrue(connection.sent.none { it.contains("注釈") })
    }
}
