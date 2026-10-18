package se.haya.skk

import android.text.Selection
import android.view.View
import android.view.inputmethod.BaseInputConnection
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import se.haya.skk.core.BasicSkkAction
import se.haya.skk.core.BasicSkkDictionary
import se.haya.skk.core.DictionaryCandidate
import se.haya.skk.input.EditorSession
import se.haya.skk.core.PredictionSearchFailure
import java.util.concurrent.Executor

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26])
class TouchReadingCapabilityTest {
    private fun session(protected: Boolean = false): EditorSession {
        val connection = BaseInputConnection(View(RuntimeEnvironment.getApplication()), true)
        Selection.setSelection(connection.editable, 0)
        return EditorSession(1, connection, protected, !protected, 0, 0,
            BasicSkkDictionary { query -> if (query.readingKey == "かな") listOf(DictionaryCandidate("仮名")) else emptyList() },
            registrationSaver = { _, _ -> })
    }

    @Test fun `読みと候補だけで読み確定を利用でき通常入力と保護入力では利用できない`() {
        assertFalse(touchReadingConversionAvailable(null))
        val current = session()
        assertFalse(touchReadingConversionAvailable(current))
        current.handleTouch(BasicSkkAction.StartTouchReading())
        assertTrue(touchReadingConversionAvailable(current))
        current.handleTouch(BasicSkkAction.TouchText("かな"))
        current.handleTouchSpaceOrConvert()
        assertNotNull(current.view.candidate)
        assertTrue(touchReadingConversionAvailable(current))
        current.handleTouchPrimary()
        assertFalse(touchReadingConversionAvailable(current))
        assertFalse(touchReadingConversionAvailable(session(protected = true)))
        current.close()
        assertFalse(touchReadingConversionAvailable(current))
    }

    @Test fun `登録中は内側の読みがある時だけ読み確定を利用できる`() {
        val current = session()
        current.handleTouch(BasicSkkAction.StartTouchReading())
        current.handleTouch(BasicSkkAction.TouchText("みち"))
        current.handleTouchSpaceOrConvert()
        assertNotNull(current.view.registration)
        assertFalse(touchReadingConversionAvailable(current))
        current.handleTouch(BasicSkkAction.StartTouchReading())
        current.handleTouch(BasicSkkAction.TouchText("かな"))
        assertTrue(touchReadingConversionAvailable(current))
        current.handleTouchSpaceOrConvert()
        assertNotNull(current.view.registration!!.innerCandidate)
        assertTrue(touchReadingConversionAvailable(current))
        current.handleTouchPrimary()
        assertFalse(touchReadingConversionAvailable(current))
        assertTrue(current.saveRegistration(current.view.registration!!))
        assertTrue(current.view.registration!!.saving)
        assertFalse(touchReadingConversionAvailable(current))
        assertFalse(touchPrimaryActionAvailable(current))
    }

    @Test fun `予測の待機中と未対応でも画面確定は有効で読みを変えず確定できる`() {
        for (pending in listOf(false, true)) {
            val connection = BaseInputConnection(View(RuntimeEnvironment.getApplication()), true)
            Selection.setSelection(connection.editable, 0)
            val direct = Executor { it.run() }
            val current = EditorSession(1, connection, false, true, 0, 0,
                BasicSkkDictionary { emptyList() }, callbackExecutor = direct,
                dictionaryExecutor = if (pending) Executor { } else direct)
            current.setTouchCandidatePresentation(true)
            current.handleTouch(BasicSkkAction.TouchText("か", boundary = true))
            assertEquals(if (pending) PredictionSearchFailure.PENDING else PredictionSearchFailure.UNSUPPORTED,
                current.view.prediction?.failure)
            assertTrue(touchPrimaryActionAvailable(current))
            assertTrue(current.handleTouchPrimary())
            assertEquals("か", connection.editable.toString())
            assertEquals(se.haya.skk.core.InputPhase.IDLE, current.engine.state.phase)
        }
    }

    @Test fun `画面確定は保護入力でも使え終了または失敗したセッションでは使えない`() {
        assertFalse(touchPrimaryActionAvailable(null))
        val current = session(protected = true)
        assertTrue(touchPrimaryActionAvailable(current))
        current.close()
        assertFalse(touchPrimaryActionAvailable(current))
        val rejected = object : BaseInputConnection(View(RuntimeEnvironment.getApplication()), true) {
            override fun commitText(text: CharSequence?, newCursorPosition: Int) = false
        }
        val failed = EditorSession(1, rejected, false, true, 0, 0, BasicSkkDictionary { emptyList() })
        failed.handleTouch(BasicSkkAction.TouchText("か"))
        assertTrue(failed.failed)
        assertFalse(touchPrimaryActionAvailable(failed))
    }
}
