package jp.hayase.skk.testeditor

import android.os.SystemClock
import android.accessibilityservice.AccessibilityServiceInfo
import android.view.InputDevice
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** adb で本 IME を有効化した専用エミュレーターで実行します。外部送信はしません。 */
@RunWith(AndroidJUnit4::class)
class PhysicalInputTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test fun candidateEnterDoesNotSendAndNextEnterReachesEditor() {
        ActivityScenario.launch(InputTestActivity::class.java).use { scenario ->
            lateinit var editor: EditText
            lateinit var counter: TextView
            scenario.onActivity { activity ->
                val views = descendants(activity.window.decorView)
                editor = views.filterIsInstance<EditText>().first { it.hint.toString().startsWith("送信") }
                counter = views.filterIsInstance<TextView>().first { it.text.toString().startsWith("入力先への") }
                editor.requestFocus()
            }
            awaitIme()
            key(KeyEvent.KEYCODE_J, KeyEvent.META_CTRL_ON)
            type("Nihon ")
            awaitText(editor, "日本")
            key(KeyEvent.KEYCODE_ENTER)
            awaitText(editor, "日本")
            assertEquals("入力先への Enter／アクション: 0 回", text(counter))
            key(KeyEvent.KEYCODE_ENTER)
            awaitText(counter, "入力先への Enter／アクション: 1 回")
            assertEquals("日本", text(editor))
        }
    }

    @Test fun changingEditorPreservesOldTextAndStartsFreshInput() {
        ActivityScenario.launch(InputTestActivity::class.java).use { scenario ->
            lateinit var first: EditText
            lateinit var second: EditText
            scenario.onActivity { activity ->
                val editors = descendants(activity.window.decorView).filterIsInstance<EditText>()
                first = editors[0]; second = editors[1]
                first.requestFocus()
            }
            awaitIme()
            key(KeyEvent.KEYCODE_J, KeyEvent.META_CTRL_ON)
            type("Ni")
            awaitText(first, "に")
            scenario.onActivity { second.requestFocus() }
            awaitIme()
            key(KeyEvent.KEYCODE_J, KeyEvent.META_CTRL_ON)
            type("a")
            awaitText(second, "あ")
            assertEquals("に", text(first))
        }
    }

    @Test fun externalCursorMovementDoesNotReplaceOldCandidate() {
        ActivityScenario.launch(InputTestActivity::class.java).use { scenario ->
            lateinit var editor: EditText
            scenario.onActivity { activity ->
                editor = descendants(activity.window.decorView).filterIsInstance<EditText>().first()
                editor.requestFocus()
            }
            awaitIme()
            key(KeyEvent.KEYCODE_J, KeyEvent.META_CTRL_ON)
            type("Nihon ")
            awaitText(editor, "日本")
            scenario.onActivity { editor.setSelection(0) }
            instrumentation.waitForIdleSync()
            type("a")
            awaitText(editor, "あ日本")
        }
    }

    private fun type(value: String) {
        val events = KeyCharacterMap.load(KeyCharacterMap.VIRTUAL_KEYBOARD).getEvents(value.toCharArray())!!
        events.forEach { instrumentation.sendKeySync(it) }
        instrumentation.waitForIdleSync()
    }

    private fun awaitIme() {
        instrumentation.waitForIdleSync()
        val automation = instrumentation.uiAutomation
        automation.serviceInfo = automation.serviceInfo.apply {
            flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
        val deadline = SystemClock.uptimeMillis() + 5000
        while (SystemClock.uptimeMillis() < deadline) {
            if (automation.windows.any { window ->
                    window.root?.findAccessibilityNodeInfosByText("かな")?.isNotEmpty() == true
                }) return
            SystemClock.sleep(20)
        }
        val diagnostic = android.os.ParcelFileDescriptor.AutoCloseInputStream(
            automation.executeShellCommand("dumpsys input_method")
        ).bufferedReader().use { reader ->
            reader.lineSequence().filter { line ->
                listOf("mCurMethodId=", "mInputStarted=", "mDecorViewVisible=", "mShowInputRequested=",
                    "hintText=", "mCandidatesVisibility=", "mIsInputViewShown=").any { it in line }
            }.joinToString("\n")
        }
        fail("SKK の入力モード表示が現れません。\n$diagnostic")
    }

    private fun key(code: Int, meta: Int = 0) {
        val now = SystemClock.uptimeMillis()
        instrumentation.sendKeySync(KeyEvent(now, now, KeyEvent.ACTION_DOWN, code, 0, meta,
            KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0, InputDevice.SOURCE_KEYBOARD))
        instrumentation.sendKeySync(KeyEvent(now, SystemClock.uptimeMillis(), KeyEvent.ACTION_UP, code, 0, meta,
            KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0, InputDevice.SOURCE_KEYBOARD))
        instrumentation.waitForIdleSync()
    }

    private fun text(view: TextView): String {
        var value = ""
        instrumentation.runOnMainSync { value = view.text.toString() }
        return value
    }

    private fun awaitText(view: TextView, expected: String) {
        val deadline = SystemClock.uptimeMillis() + 5000
        while (SystemClock.uptimeMillis() < deadline && text(view) != expected) SystemClock.sleep(20)
        assertEquals(expected, text(view))
    }

    private fun descendants(view: View): List<View> = listOf(view) +
        if (view is ViewGroup) (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()
}
