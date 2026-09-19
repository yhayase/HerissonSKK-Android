package jp.hayase.skk.input

import android.text.Selection
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.ExtractedText
import android.view.inputmethod.ExtractedTextRequest
import jp.hayase.skk.core.BasicSkkAction
import jp.hayase.skk.core.BasicSkkDictionary
import jp.hayase.skk.core.CandidateDisplayConfig
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 35])
class CompositionMarkerSessionTest {
    private class Connection : BaseInputConnection(View(RuntimeEnvironment.getApplication()), true) {
        var readable = true
        init { Selection.setSelection(editable, 0) }
        override fun getExtractedText(request: ExtractedTextRequest?, flags: Int): ExtractedText? =
            if (!readable) null else ExtractedText().apply {
                text = editable.toString()
                startOffset = 0
                partialStartOffset = -1
                selectionStart = Selection.getSelectionStart(editable)
                selectionEnd = Selection.getSelectionEnd(editable)
            }
    }

    private fun session(connection: Connection) = EditorSession(1, connection, false, true, 0, 0,
        candidateDisplayConfig = CandidateDisplayConfig(showCompositionMarkers = true))

    private fun EditorSession.type(text: String) {
        text.forEach { assertTrue(handle(BasicSkkAction.Text(it.toString()))) }
    }

    @Test fun `読みと候補の表示記号は確定本文とコア状態に含めない`() {
        val connection = Connection()
        val session = session(connection)
        session.type("Nihon")
        assertEquals("▽にほn", connection.editable.toString())
        assertEquals("にほn", session.displayedComposition)
        session.type(" ")
        assertEquals("▼日本", connection.editable.toString())
        session.handle(BasicSkkAction.Cancel)
        assertEquals("▽にほn", connection.editable.toString())
        session.type(" ")
        session.handle(BasicSkkAction.Enter)
        assertEquals("日本", connection.editable.toString())
        assertEquals(-1, getComposingStart(connection))
    }

    @Test fun `空の読みと内部カーソルは表示記号の一文字を考慮する`() {
        val connection = Connection()
        val session = session(connection)
        session.handle(BasicSkkAction.StartReading)
        assertEquals("▽", connection.editable.toString())
        session.type("kana")
        session.handle(BasicSkkAction.Home)
        assertEquals(1, Selection.getSelectionStart(connection.editable))
        session.type("a")
        assertEquals("▽あかな", connection.editable.toString())
        session.handle(BasicSkkAction.Cancel)
        assertEquals("", connection.editable.toString())
    }

    @Test fun `終了と引用前の保持は表示記号だけを取り除く`() {
        for (close in listOf(false, true)) {
            val connection = Connection()
            val session = session(connection)
            session.type("aNihon ")
            if (close) session.close() else session.preserveText()
            assertEquals("あ日本", connection.editable.toString())
            assertEquals(3, Selection.getSelectionStart(connection.editable))
            assertFalse(session.hasComposition)
        }
    }

    @Test fun `入力先が先にspanを終了して外へ移動しても本文を再挿入しない`() {
        for (target in listOf(0, 1, 4)) {
            val connection = Connection()
            val session = session(connection)
            session.type("aNihon ")
            connection.finishComposingText()
            Selection.setSelection(connection.editable, target)
            session.onSelection(target, target, -1, -1)
            assertEquals("あ日本", connection.editable.toString())
            val expected = if (target > 1) target - 1 else target
            assertEquals(expected, Selection.getSelectionStart(connection.editable))
            assertFalse(session.hasComposition)
        }
    }

    @Test fun `外部の選択範囲を表示記号削除後も保持する`() {
        val connection = Connection()
        val session = session(connection)
        session.type("aNihon ")
        connection.finishComposingText()
        Selection.setSelection(connection.editable, 0, 4)
        session.onSelection(0, 4, -1, -1)
        assertEquals("あ日本", connection.editable.toString())
        assertEquals(0, Selection.getSelectionStart(connection.editable))
        assertEquals(3, Selection.getSelectionEnd(connection.editable))
    }

    @Test fun `読戻し未対応の入力先では表示記号を付けない`() {
        val connection = Connection().apply { readable = false }
        val session = session(connection)
        session.type("Nihon ")
        assertEquals("日本", connection.editable.toString())
        session.close()
        assertEquals("日本", connection.editable.toString())
    }

    @Test fun `入力先の本文変更や読戻し失敗では推測して削除しない`() {
        for (unreadable in listOf(false, true)) {
            val connection = Connection()
            val session = session(connection)
            session.type("Nihon ")
            if (unreadable) connection.readable = false else connection.editable!!.replace(1, 3, "外部")
            val before = connection.editable.toString()
            session.preserveText()
            assertEquals(before, connection.editable.toString())
            assertNotNull(session.notice)
        }
    }

    @Test fun `途中で読戻し不可になってもEnterは候補本文だけを確定する`() {
        val connection = Connection()
        val session = session(connection)
        session.type("Nihon ")
        connection.readable = false
        session.handle(BasicSkkAction.Enter)
        assertEquals("日本", connection.editable.toString())
    }

    @Test fun `登録中は親の読みだけに表示記号を付け終了時に除く`() {
        val connection = Connection()
        val session = EditorSession(1, connection, false, true, 0, 0,
            dictionary = BasicSkkDictionary { emptyList() }, registrationSaver = { _, _ -> },
            candidateDisplayConfig = CandidateDisplayConfig(showCompositionMarkers = true))
        session.type("Michi kana")
        assertEquals("▽みち", connection.editable.toString())
        assertEquals("かな", session.view.registration?.body)
        session.close()
        assertEquals("みち", connection.editable.toString())
    }

    private fun getComposingStart(connection: Connection) =
        BaseInputConnection.getComposingSpanStart(connection.editable!!)
}
