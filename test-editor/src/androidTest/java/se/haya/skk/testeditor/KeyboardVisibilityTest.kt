package se.haya.skk.testeditor

import android.accessibilityservice.AccessibilityServiceInfo
import android.graphics.Rect
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiScrollable
import androidx.test.uiautomator.UiSelector
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** OS または入力先の明示要求だけでタッチキーを表示することを確認します。 */
@RunWith(AndroidJUnit4::class)
class KeyboardVisibilityTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val device = UiDevice.getInstance(instrumentation)
    private val automation = instrumentation.uiAutomation.apply {
        serviceInfo = serviceInfo.apply {
            flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
        }
    }

    @Before fun enableTouchKeysForHardwareKeyboard() {
        enableTouchKeysThroughSettings(instrumentation)
    }

    @After fun cleanUpNotificationShade() = collapseNotificationShade()

    @Test fun backHideSurvivesRestartNotificationDrawerAndHome() {
        ActivityScenario.launch(InputTestActivity::class.java).use { scenario ->
            val editor = scenario.editorStartingWith("送信")
            requestKeyboard(editor)
            awaitImeVisible()

            device.pressBack()
            awaitImeHiddenFor()
            scenario.onActivity {
                editor.setSelection(editor.selectionStart.coerceAtLeast(0))
                editor.context.getSystemService(InputMethodManager::class.java).restartInput(editor)
            }
            awaitImeHiddenFor()

            assertTrue("通知ドロワーを開けません", device.openNotification())
            awaitCondition("通知ドロワーが開きません") { systemUiHasFocus() }
            collapseNotificationShade()
            awaitImeHiddenFor()

            val bounds = Rect()
            scenario.onActivity { editor.getGlobalVisibleRect(bounds) }
            assertTrue("Home 前に入力欄をタップできません", !bounds.isEmpty &&
                device.click(bounds.centerX(), bounds.centerY()))
            awaitImeVisible()
            device.pressHome()
            device.waitForIdle()
            awaitImeHiddenFor()
        }
    }

    @Test fun tappingEditableEditorReopensKeyboardAndPasswordEditorRemainsUsable() {
        ActivityScenario.launch(InputTestActivity::class.java).use { scenario ->
            val editor = scenario.editorStartingWith("送信")
            val password = scenario.editorStartingWith("パスワード")
            requestKeyboard(editor)
            awaitImeVisible()
            device.pressBack()
            awaitImeHiddenFor()

            val bounds = Rect()
            scenario.onActivity { editor.getGlobalVisibleRect(bounds) }
            assertTrue("通常の入力欄をタップできません", !bounds.isEmpty &&
                device.click(bounds.centerX(), bounds.centerY()))
            awaitImeVisible()

            device.pressBack()
            awaitImeHiddenFor()
            requestKeyboard(password)
            awaitImeVisible()
            type("Nihon")
            awaitCondition("パスワード欄へ入力できません") {
                var value = ""
                instrumentation.runOnMainSync { value = password.text.toString() }
                value == "Nihon"
            }
        }
    }

    @Test fun fullscreenTypeNullInputRequestDoesNotShowKeyboard() {
        ActivityScenario.launch(TypeNullFullscreenActivity::class.java).use { scenario ->
            awaitCondition("TYPE_NULL の入力接続が開始されません") {
                var ready = false
                scenario.onActivity { activity ->
                    val manager = activity.input.context.getSystemService(InputMethodManager::class.java)
                    ready = activity.inputConnectionCreated && activity.input.hasFocus() && manager.isActive(activity.input)
                }
                ready
            }
            awaitImeHiddenFor()
            scenario.onActivity { activity ->
                assertEquals("入力接続の inputType が TYPE_NULL ではありません", android.text.InputType.TYPE_NULL,
                    activity.createdInputType)
                activity.input.context.getSystemService(InputMethodManager::class.java)
                    .showSoftInput(activity.input, InputMethodManager.SHOW_IMPLICIT)
            }
            awaitImeHiddenFor()
        }
    }

    private fun requestKeyboard(editor: EditText) = instrumentation.runOnMainSync {
        editor.requestFocus()
        editor.context.getSystemService(InputMethodManager::class.java)
            .showSoftInput(editor, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun type(value: String) {
        val events = android.view.KeyCharacterMap.load(android.view.KeyCharacterMap.VIRTUAL_KEYBOARD)
            .getEvents(value.toCharArray()) ?: throw AssertionError("入力イベントを作成できません")
        events.forEach { instrumentation.sendKeySync(it) }
        instrumentation.waitForIdleSync()
    }

    private fun ActivityScenario<InputTestActivity>.editorStartingWith(hint: String): EditText {
        lateinit var result: EditText
        onActivity { activity ->
            result = descendants(activity.window.decorView).filterIsInstance<EditText>().first {
                it.hint.toString().startsWith(hint)
            }
        }
        return result
    }

    private fun touchKeysVisible(): Boolean = automation.windows.any { window ->
        val root = window.root ?: return@any false
        window.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD &&
            root.packageName?.toString() == SKK_PACKAGE &&
            root.findAccessibilityNodeInfosByText("q").any { it.isVisibleToUser }
    }

    private fun awaitImeVisible() = awaitCondition("タッチキーが表示されません") { touchKeysVisible() }

    private fun awaitImeHidden() = awaitCondition("タッチキーが表示されたままです") { !touchKeysVisible() }

    private fun awaitImeHiddenFor(durationMillis: Long = 750) {
        awaitImeHidden()
        val deadline = SystemClock.uptimeMillis() + durationMillis
        while (SystemClock.uptimeMillis() < deadline) {
            if (touchKeysVisible()) fail("タッチキーが遅れて表示されました")
            SystemClock.sleep(25)
        }
    }

    private fun collapseNotificationShade() {
        // Back は入力先へ届く場合があるため、SystemUI 自身にドロワーを閉じさせます。
        shell("cmd statusbar collapse")
        awaitCondition("通知ドロワーを閉じられません") { !systemUiHasFocus() }
    }

    private fun systemUiHasFocus(): Boolean = automation.windows.any { window ->
        window.isFocused && window.root?.packageName?.toString() == SYSTEM_UI_PACKAGE
    }

    private fun awaitNode(value: String, exact: Boolean = false, visibleOnly: Boolean = true): AccessibilityNodeInfo {
        var found: AccessibilityNodeInfo? = null
        awaitCondition("設定に「$value」がありません") {
            found = automation.windows.asSequence().mapNotNull { it.root }
                .filter { it.packageName?.toString() == SKK_PACKAGE }
                .flatMap { it.findAccessibilityNodeInfosByText(value).asSequence() }
                .firstOrNull { node ->
                    val text = node.text?.toString().orEmpty()
                    (!visibleOnly || node.isVisibleToUser) &&
                        if (exact) text == value else text.startsWith(value)
                }
            found != null
        }
        return checkNotNull(found)
    }

    private fun awaitCondition(message: String, block: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 7_000
        while (SystemClock.uptimeMillis() < deadline) {
            if (block()) return
            SystemClock.sleep(25)
        }
        fail(message)
    }

    private fun shell(command: String): String = android.os.ParcelFileDescriptor.AutoCloseInputStream(
        automation.executeShellCommand(command),
    ).bufferedReader().use { it.readText() }

    private fun descendants(view: View): List<View> = listOf(view) +
        if (view is ViewGroup) (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()

    private companion object {
        const val SKK_PACKAGE = "se.haya.skk"
        const val SYSTEM_UI_PACKAGE = "com.android.systemui"
    }
}
