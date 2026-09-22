package se.haya.skk.testeditor

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** 同じ実 EditText の回転で、登録スタックと入力本文を失わないことを確認します。 */
@RunWith(AndroidJUnit4::class)
class PhysicalPopupLifecycleTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val automation = instrumentation.uiAutomation.apply {
        serviceInfo = serviceInfo.apply {
            flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
        }
    }

    @Test fun backHidesPhysicalPopupUntilNextKeyWithoutLeavingEditor() {
        withPhysicalRegistration { scenario, editor ->
            val device = androidx.test.uiautomator.UiDevice.getInstance(instrumentation)
            device.pressBack()
            awaitPhysicalPopupHidden()
            scenario.onActivity {
                assertTrue("戻る操作で入力先を離れました", editor.hasWindowFocus())
                editor.setSelection(editor.selectionStart.coerceAtLeast(0))
                editor.context.getSystemService(InputMethodManager::class.java).restartInput(editor)
            }
            awaitPhysicalPopupHidden()
            type("Mitorokupamyu ")
            waitUntil { imeContains("単語登録") }
            device.pressBack()
            awaitPhysicalPopupHidden()
            device.pressBack()
            awaitPhysicalPopupHidden()
        }
    }

    @Test fun physicalPopupDoesNotRemainOnHomeOrFullscreenNonEditor() {
        withPhysicalRegistration { _, _ ->
            val device = androidx.test.uiautomator.UiDevice.getInstance(instrumentation)
            device.pressHome()
            device.waitForIdle()
            awaitPhysicalPopupHidden()
        }
        withPhysicalRegistration { _, _ ->
            ActivityScenario.launch(TypeNullFullscreenActivity::class.java).use { fullscreen ->
                waitUntil {
                    var ready = false
                    fullscreen.onActivity {
                        ready = it.inputConnectionCreated && it.createdInputType == android.text.InputType.TYPE_NULL &&
                            it.input.hasWindowFocus() && it.getSystemService(InputMethodManager::class.java).isActive(it.input)
                    }
                    ready
                }
                awaitPhysicalPopupHidden()
                type("a")
                awaitPhysicalPopupHidden()
                fullscreen.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
            }
        }
    }

    @Test fun nestedRegistrationKeepsInlineCandidateInPhysicalPopupAndConfirmsIntoBody() {
        withPhysicalRegistration { _, _ ->
            type("Goropazu ")
            waitUntil { imeContains("単語登録：ごろぱず") }

            type("Nihon ")
            waitUntil { registrationText() == "▼日本" }
            assertFalse("内部状態の説明を登録欄に混在させました",
                imeContains("操作対象:") || imeContains("登録階層:") || imeContains("変換候補:"))
            captureRegistration("registration-inline")
            assertFalse("インライン候補を物理候補メニューへ昇格させました",
                imeContains("a: 日本") || imeContains("a: 二本"))

            confirmCandidate()
            waitUntil {
                imeContains("単語登録：ごろぱず") && registrationText() == "日本" &&
                    !imeContains("a: 日本")
            }
        }
    }

    private fun withPhysicalRegistration(block: (ActivityScenario<StableEditorActivity>, EditText) -> Unit) {
        ActivityScenario.launch(StableEditorActivity::class.java).use { scenario ->
            lateinit var editor: EditText
            scenario.onActivity { editor = it.editor }
            waitUntil {
                var active = false
                instrumentation.runOnMainSync {
                    active = editor.hasWindowFocus() && editor.context.getSystemService(InputMethodManager::class.java)
                        .isActive(editor)
                }
                active
            }
            androidx.test.uiautomator.UiDevice.getInstance(instrumentation).waitForIdle()
            type("Mitorokupamyu ")
            waitUntil { imeContains("単語登録") }
            block(scenario, editor)
        }
    }

    @Test fun registrationEditsAndConvertsAtOneInlineCursor() {
        withPhysicalRegistration { _, _ ->
            type("maeato")
            waitUntil { registrationText() == "まえあと" }
            physicalKey(KeyEvent.KEYCODE_DPAD_LEFT)
            physicalKey(KeyEvent.KEYCODE_DPAD_LEFT)
            type("Nihon")
            waitUntil(message = { "登録欄の読みが一致しません: ${registrationText()}" }) {
                registrationText() == "まえ▽にほnあと"
            }
            type(" ")
            waitUntil { registrationText() == "まえ▼日本あと" }
            captureRegistration("registration-middle-candidate")
            physicalKey(KeyEvent.KEYCODE_G, KeyEvent.META_CTRL_ON)
            waitUntil { registrationText() == "まえ▽にほnあと" }
            confirmCandidate()
            waitUntil { registrationText() == "まえにほんあと" }
            type("l")
            confirmCandidate()
            type("a")
            waitUntil { registrationText() == "まえにほんああと" }
            repeat(12) { batch ->
                type("a".repeat(12))
                val expected = "まえにほんあ" + "あ".repeat((batch + 1) * 12) + "あと"
                waitUntil(message = { "登録欄の長文が一致しません: ${registrationText()}" }) {
                    registrationText() == expected
                }
            }
            captureRegistration("registration-long-body")
        }
    }

    private fun awaitPhysicalPopupHidden() {
        waitUntil { !physicalPopupVisible() }
        val deadline = SystemClock.uptimeMillis() + 1000
        while (SystemClock.uptimeMillis() < deadline) {
            assertFalse("物理候補が再表示されました", physicalPopupVisible())
            SystemClock.sleep(50)
        }
        // 非操作の透明ホストはアクセシビリティ窓に現れないため、窓管理側も検査します。
        val windows = android.os.ParcelFileDescriptor.AutoCloseInputStream(
            automation.executeShellCommand("dumpsys window windows"),
        ).bufferedReader().use { it.readText() }
        assertFalse("物理候補の透明ホストが残りました",
            Regex("Window #\\d+ Window\\{[^\\n]*SKK 物理候補ホスト").containsMatchIn(windows))
    }

    private fun physicalPopupVisible(): Boolean = automation.windows.mapNotNull { it.root }
        .any { it.packageName?.toString() == "se.haya.skk" && it.isVisibleToUser }

    @Test fun recursiveRegistrationSurvivesSameEditorRotation() {
        ActivityScenario.launch(StableEditorActivity::class.java).use { scenario ->
            lateinit var editor: EditText
            scenario.onActivity { editor = it.editor }
            waitUntil {
                var active = false
                instrumentation.runOnMainSync {
                    active = editor.hasWindowFocus() && editor.context
                        .getSystemService(android.view.inputmethod.InputMethodManager::class.java).isActive(editor)
                }
                active
            }
            androidx.test.uiautomator.UiDevice.getInstance(instrumentation).waitForIdle()
            type("Mitorokupamyu ")
            waitUntil { imeContains("単語登録：みとろくぱみゅ") }
            type("Goropazu ")
            waitUntil { imeContains("単語登録：ごろぱず") }
            var before = ""
            instrumentation.runOnMainSync { before = editor.text.toString() }
            scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
            waitUntil {
                var landscape = false
                scenario.onActivity { landscape = it.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE }
                landscape
            }
            waitUntil { imeContains("単語登録：ごろぱず") }
            scenario.onActivity {
                assertSame("入力先を作り直していません", editor, it.editor)
                assertEquals("回転で入力先本文が変わりました", before, editor.text.toString())
                it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            }
            waitUntil {
                var portrait = false
                scenario.onActivity { portrait = it.resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT }
                portrait
            }
            waitUntil { imeContains("単語登録：ごろぱず") }
        }
    }

    @Test fun configurationDoesNotMoveRegistrationToAnotherEditor() {
        ActivityScenario.launch(StableEditorActivity::class.java).use { scenario ->
            lateinit var first: EditText
            scenario.onActivity { first = it.editor }
            waitUntil {
                var active = false
                instrumentation.runOnMainSync {
                    active = first.hasWindowFocus() && first.context.getSystemService(
                        android.view.inputmethod.InputMethodManager::class.java).isActive(first)
                }
                active
            }
            androidx.test.uiautomator.UiDevice.getInstance(instrumentation).waitForIdle()
            type("Mitorokupamyu ")
            waitUntil { imeContains("単語登録：みとろくぱみゅ") }
            lateinit var second: EditText
            scenario.onActivity {
                it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                second = EditText(it).apply {
                    // 同じ属性でも、別の View への通常開始には状態を移しません。
                    inputType = first.inputType
                    imeOptions = first.imeOptions
                    hint = first.hint
                    importantForAutofill = android.view.View.IMPORTANT_FOR_AUTOFILL_NO
                }
                it.setContentView(second)
                second.requestFocus()
            }
            waitUntil {
                var active = false
                instrumentation.runOnMainSync {
                    active = second.context.getSystemService(
                        android.view.inputmethod.InputMethodManager::class.java).isActive(second)
                }
                active && !imeContains("単語登録：みとろくぱみゅ")
            }
            type("a")
            var secondText = ""
            waitUntil(message = { "別欄の入力結果が一致しません: [$secondText]" }) {
                instrumentation.runOnMainSync { secondText = second.text.toString() }
                secondText == "あ"
            }
            scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
        }
    }

    private fun registrationText(): String? = automation.windows.mapNotNull { it.root }
        .filter { it.packageName?.toString() == "se.haya.skk" }
        .flatMap { it.findAccessibilityNodeInfosByViewId("se.haya.skk:id/registration_editor") }
        .firstOrNull()?.text?.toString()

    private fun captureRegistration(name: String) {
        androidx.test.uiautomator.UiDevice.getInstance(instrumentation).waitForIdle()
        val bitmap = automation.takeScreenshot() ?: return
        val file = java.io.File(instrumentation.targetContext.getExternalFilesDir(null), "$name.png")
        file.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }

    private fun type(value: String) {
        KeyCharacterMap.load(KeyCharacterMap.VIRTUAL_KEYBOARD).getEvents(value.toCharArray())!!
            .forEach(instrumentation::sendKeySync)
    }

    /** Ctrl-J は候補を登録本文へ確定し、入力先の Enter 操作にはしません。 */
    private fun confirmCandidate() {
        physicalKey(KeyEvent.KEYCODE_J, KeyEvent.META_CTRL_ON)
    }

    private fun physicalKey(code: Int, meta: Int = 0) {
        val now = SystemClock.uptimeMillis()
        instrumentation.sendKeySync(KeyEvent(now, now, KeyEvent.ACTION_DOWN, code, 0,
            meta, KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0, InputDevice.SOURCE_KEYBOARD))
        instrumentation.sendKeySync(KeyEvent(now, SystemClock.uptimeMillis(), KeyEvent.ACTION_UP, code, 0,
            meta, KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0, InputDevice.SOURCE_KEYBOARD))
        instrumentation.waitForIdleSync()
    }

    private fun imeContains(value: String): Boolean = automation.windows.mapNotNull { it.root }
        .filter { it.packageName?.toString() == "se.haya.skk" }
        .any { it.findAccessibilityNodeInfosByText(value).isNotEmpty() }

    private fun waitUntil(message: () -> String = { "同じ入力欄の状態またはポップアップが維持されません" },
                          condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 10_000
        while (SystemClock.uptimeMillis() < deadline) {
            if (condition()) return
            SystemClock.sleep(50)
        }
        captureRegistration("registration-failure")
        fail(message())
    }
}
