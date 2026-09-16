package jp.hayase.skk.input

import android.text.Selection
import android.view.View
import android.view.inputmethod.BaseInputConnection
import jp.hayase.skk.core.BasicSkkAction
import jp.hayase.skk.core.CompletionConfig
import jp.hayase.skk.core.dictionary.CompositeSkkDictionary
import jp.hayase.skk.core.dictionary.SkkDictionaryCodec
import jp.hayase.skk.core.dictionary.SkkDictionarySource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 35])
class CompletionRegistrationSessionTest {
    private class Connection : BaseInputConnection(View(RuntimeEnvironment.getApplication()), true) {
        val compositions = mutableListOf<String>()

        init {
            Selection.setSelection(editable, 0)
        }

        override fun setComposingText(text: CharSequence?, newCursorPosition: Int): Boolean {
            compositions += text.toString()
            return super.setComposingText(text, newCursorPosition)
        }
    }

    @Test fun `再帰登録の動的補完は受諾前後とも入力先と親本文へ漏れない`() {
        val dictionary = CompositeSkkDictionary(
            SkkDictionarySource(
                id = "personal",
                generation = 1,
                entries = SkkDictionaryCodec.parseText("こども /子供/\n").entries,
            ),
            emptyList(),
        )
        val connection = Connection()
        val session = EditorSession(
            generation = 1,
            connection = connection,
            protectedInput = false,
            learningAllowed = true,
            initialStart = 0,
            initialEnd = 0,
            dictionary = dictionary,
            registrationSaver = { _, _ -> },
            completionConfig = CompletionConfig(dynamicEnabled = true),
        )

        assertTrue(session.handle(BasicSkkAction.Text("Michi Ko")))
        assertEquals("みち", connection.editable.toString())
        assertEquals("", session.view.registration?.body)
        assertEquals("こ", session.view.registration?.innerComposing)
        assertEquals("ども", session.view.completion?.suffix)
        assertFalse(connection.compositions.any { it.contains("こども") })

        assertTrue(session.handle(BasicSkkAction.AcceptDynamicCompletion))
        assertEquals("みち", connection.editable.toString())
        assertEquals("", session.view.registration?.body)
        assertEquals("こども", session.view.registration?.innerComposing)
        assertFalse(connection.compositions.any { it.contains("こども") })
    }
}
