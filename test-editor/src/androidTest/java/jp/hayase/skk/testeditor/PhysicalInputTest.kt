package jp.hayase.skk.testeditor

import android.os.SystemClock
import android.accessibilityservice.AccessibilityServiceInfo
import android.view.InputDevice
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.InputMethodManager
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

    /** K14: TABは読みだけを補完し、取消で元へ戻り、受諾後のSpaceで変換します。 */
    @Test fun manualCompletionStaysInCompositionAndCancelRestoresPrefix() {
        withSendEditor { editor, counter ->
            type("Ni")
            awaitText(editor, "に")
            key(KeyEvent.KEYCODE_TAB)
            awaitText(editor, "にほん")
            instrumentation.runOnMainSync {
                assertEquals(0, BaseInputConnection.getComposingSpanStart(editor.text))
                assertEquals(3, BaseInputConnection.getComposingSpanEnd(editor.text))
            }
            key(KeyEvent.KEYCODE_TAB, KeyEvent.META_SHIFT_ON)
            awaitText(editor, "にほん")
            key(KeyEvent.KEYCODE_G, KeyEvent.META_CTRL_ON)
            awaitText(editor, "に")
            key(KeyEvent.KEYCODE_TAB)
            key(KeyEvent.KEYCODE_SPACE)
            awaitText(editor, "日本")
            key(KeyEvent.KEYCODE_ENTER)
            awaitText(editor, "日本")
            assertEquals("入力先への Enter／アクション: 0 回", text(counter))
        }
    }

    /** I10: パスワード欄では SKK の未確定表示も候補表示も作らず、入力先の文字をそのまま通します。 */
    @Test fun passwordEditorBypassesSkkAndKeepsLiteralText() {
        launchWithoutLearning().use { scenario ->
            val password = scenario.editorStartingWith("パスワード")
            scenario.onActivity { password.requestFocus() }
            awaitProtectedEditor(password)

            type("Nihon ")

            awaitText(password, "Nihon ")
            instrumentation.runOnMainSync {
                assertEquals(-1, BaseInputConnection.getComposingSpanStart(password.text))
                assertEquals(-1, BaseInputConnection.getComposingSpanEnd(password.text))
            }
            assertFalse(statusViewIsVisible())
        }
    }

    /** I10: 候補を表示した通常欄からパスワード欄へ移っても、通常欄を保ち未確定文字を持ち込みません。 */
    @Test fun switchingCandidateToPasswordPreservesNormalTextWithoutLeakingComposition() {
        launchWithoutLearning().use { scenario ->
            val normal = scenario.editorStartingWith("送信")
            val password = scenario.editorStartingWith("パスワード")
            scenario.onActivity { normal.requestFocus() }
            awaitIme()
            key(KeyEvent.KEYCODE_J, KeyEvent.META_CTRL_ON)
            type("Nihon ")
            awaitText(normal, "日本")

            scenario.onActivity { password.requestFocus() }
            awaitProtectedEditor(password)
            type("Nihon ")

            assertEquals("日本", text(normal))
            awaitText(password, "Nihon ")
            instrumentation.runOnMainSync {
                assertEquals(-1, BaseInputConnection.getComposingSpanStart(password.text))
                assertEquals(-1, BaseInputConnection.getComposingSpanEnd(password.text))
            }
            assertFalse(statusViewIsVisible())
        }
    }

    /** I10: 学習禁止フラグだけでは通常の変換を止めません。保存抑止は永続辞書の導入後に別途検証します。 */
    @Test fun noPersonalizedLearningEditorStillConverts() {
        launchWithoutLearning().use { scenario ->
            val editor = scenario.editorStartingWith("学習禁止")
            scenario.onActivity { editor.requestFocus() }
            awaitIme()
            key(KeyEvent.KEYCODE_J, KeyEvent.META_CTRL_ON)

            type("Nihon ")

            awaitText(editor, "日本")
        }
    }

    /** K01・K02・I02: 残余の確定と文字種切替で送信を発生させません。 */
    @Test fun terminalRomajiAndKanaModesDoNotSend() {
        withSendEditor { editor, counter ->
            type("n")
            key(KeyEvent.KEYCODE_ENTER)
            awaitText(editor, "ん")
            type("q")
            type("n")
            key(KeyEvent.KEYCODE_ENTER)
            awaitText(editor, "んン")
            key(KeyEvent.KEYCODE_Q, KeyEvent.META_CTRL_ON)
            type("kana")
            awaitText(editor, "んンｶﾅ")
            key(KeyEvent.KEYCODE_J, KeyEvent.META_CTRL_ON)
            type("kitte")
            awaitText(editor, "んンｶﾅきって")
            assertEquals("入力先への Enter／アクション: 0 回", text(counter))
        }
    }

    /** K04・K07・K08: 送りや注釈が確定文字へ二重に付かないことを実配送で確認します。 */
    @Test fun okuriAbbrevAndPrefixSuffixCommitExactlyOnce() {
        withSendEditor { editor, counter ->
            type("KaKu")
            awaitText(editor, "書く")
            key(KeyEvent.KEYCODE_ENTER)
            type("/API ")
            awaitText(editor, "書くエーピーアイ")
            key(KeyEvent.KEYCODE_ENTER)
            type("Dai>")
            awaitText(editor, "書くエーピーアイ第")
            type(">kai ")
            awaitText(editor, "書くエーピーアイ第回")
            key(KeyEvent.KEYCODE_ENTER)
            assertEquals("入力先への Enter／アクション: 0 回", text(counter))
        }
    }

    /** F05・K02: 内部カーソルの編集は入力先の確定済み文字に触れません。 */
    @Test fun internalReadingEditPreservesCommittedPrefix() {
        withSendEditor { editor, _ ->
            type("aNihon ")
            awaitText(editor, "あ日本")
            key(KeyEvent.KEYCODE_G, KeyEvent.META_CTRL_ON)
            key(KeyEvent.KEYCODE_DPAD_LEFT)
            key(KeyEvent.KEYCODE_DPAD_LEFT)
            type("a")
            key(KeyEvent.KEYCODE_FORWARD_DEL)
            type("q")
            awaitText(editor, "あニアン")
        }
    }

    @Test fun cancelEmptyLookupRegistrationRestoresEditableReading() {
        withSendEditor { editor, counter ->
            type("Mitorokupamyu ")
            key(KeyEvent.KEYCODE_G, KeyEvent.META_CTRL_ON)
            awaitText(editor, "みとろくぱみゅ")
            instrumentation.runOnMainSync {
                assertEquals(0, BaseInputConnection.getComposingSpanStart(editor.text))
                assertEquals(editor.text.length, BaseInputConnection.getComposingSpanEnd(editor.text))
            }
            key(KeyEvent.KEYCODE_DEL)
            awaitText(editor, "みとろくぱみ")
            assertEquals("入力先への Enter／アクション: 0 回", text(counter))
        }
    }

    @Test fun cancelRegistrationRestoresLastSingleCandidate() {
        withSendEditor { editor, counter ->
            type("Nihon   ")
            key(KeyEvent.KEYCODE_G, KeyEvent.META_CTRL_ON)
            awaitText(editor, "二本")
            key(KeyEvent.KEYCODE_ENTER)
            assertEquals("入力先への Enter／アクション: 0 回", text(counter))
        }
    }

    @Test fun cancelRegistrationRestoresLastMenuAndItsLabel() {
        withSendEditor { editor, counter ->
            type("Tesuto" + " ".repeat(11))
            key(KeyEvent.KEYCODE_G, KeyEvent.META_CTRL_ON)
            awaitText(editor, "候補10")
            type("a")
            awaitText(editor, "候補10")
            assertEquals("入力先への Enter／アクション: 0 回", text(counter))
            key(KeyEvent.KEYCODE_ENTER)
            awaitText(counter, "入力先への Enter／アクション: 1 回")
        }
    }

    /** K06: 3番目からの一覧ラベルで選び、注釈を本文へ混ぜません。 */
    @Test fun menuLabelCommitsCandidateWithoutAnnotation() {
        withSendEditor { editor, counter ->
            type("Tesuto   ")
            awaitText(editor, "候補3")
            val deadline = SystemClock.uptimeMillis() + 5000
            var menuShown = false
            while (SystemClock.uptimeMillis() < deadline && !menuShown) {
                menuShown = instrumentation.uiAutomation.windows.any { window ->
                    val root = window.root
                    root?.findAccessibilityNodeInfosByText("注釈3")?.isNotEmpty() == true &&
                        root.findAccessibilityNodeInfosByText("a:").isNotEmpty()
                }
                if (!menuShown) SystemClock.sleep(20)
            }
            assertTrue("候補一覧のラベルと注釈が表示されません", menuShown)
            type("a")
            awaitText(editor, "候補3")
            assertEquals("入力先への Enter／アクション: 0 回", text(counter))
            key(KeyEvent.KEYCODE_ENTER)
            awaitText(counter, "入力先への Enter／アクション: 1 回")
        }
    }

    /** 固定候補の操作試験は学習を抑止し、保存の受入試験と分離します。 */
    private fun launchWithoutLearning(): ActivityScenario<InputTestActivity> = ActivityScenario.launch(
        android.content.Intent(instrumentation.targetContext, InputTestActivity::class.java)
            .putExtra(InputTestActivity.EXTRA_SUPPRESS_LEARNING, true),
    )

    private fun withSendEditor(block: (EditText, TextView) -> Unit) {
        launchWithoutLearning().use { scenario ->
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
            block(editor, counter)
        }
    }

    @Test fun candidateEnterDoesNotSendAndNextEnterReachesEditor() {
        launchWithoutLearning().use { scenario ->
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
        launchWithoutLearning().use { scenario ->
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
        launchWithoutLearning().use { scenario ->
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

    private fun ActivityScenario<InputTestActivity>.editorStartingWith(hint: String): EditText {
        lateinit var editor: EditText
        onActivity { activity ->
            editor = descendants(activity.window.decorView).filterIsInstance<EditText>().first {
                it.hint.toString().startsWith(hint)
            }
        }
        return editor
    }

    private fun awaitProtectedEditor(editor: EditText) {
        val automation = configuredAutomation()
        val deadline = SystemClock.uptimeMillis() + 5000
        while (SystemClock.uptimeMillis() < deadline) {
            if (selectedIme(automation) == SKK_IME && isServedEditor(editor) && !statusViewIsVisible(automation)) return
            SystemClock.sleep(20)
        }
        fail("パスワード欄が SKK に保護入力として提供されません。selected=${selectedIme(automation)}, " +
            "served=${isServedEditor(editor)}, status=${statusViewIsVisible(automation)}")
    }

    private fun awaitIme() {
        instrumentation.waitForIdleSync()
        val automation = configuredAutomation()
        val deadline = SystemClock.uptimeMillis() + 5000
        while (SystemClock.uptimeMillis() < deadline) {
            if (automation.windows.any { window ->
                    val root = window.root
                    root?.packageName?.toString() == SKK_PACKAGE &&
                        root.findAccessibilityNodeInfosByViewId("$SKK_PACKAGE:id/input_status").isNotEmpty()
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

    private fun configuredAutomation() = instrumentation.uiAutomation.apply {
        serviceInfo = serviceInfo.apply {
            flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
        }
    }

    private fun selectedIme(automation: android.app.UiAutomation): String =
        android.os.ParcelFileDescriptor.AutoCloseInputStream(
            automation.executeShellCommand("settings get secure default_input_method")
        ).bufferedReader().use { it.readText().trim() }

    private fun isServedEditor(editor: EditText): Boolean {
        var served = false
        instrumentation.runOnMainSync {
            val manager = editor.context.getSystemService(InputMethodManager::class.java)
            served = editor.hasFocus() && editor.isAttachedToWindow && manager.isActive(editor)
        }
        return served
    }

    private fun statusViewIsVisible(automation: android.app.UiAutomation = configuredAutomation()): Boolean =
        automation.windows.any { window ->
            val root = window.root
            root?.packageName?.toString() == SKK_PACKAGE &&
                root.findAccessibilityNodeInfosByViewId("$SKK_PACKAGE:id/input_status").isNotEmpty()
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

    private companion object {
        const val SKK_PACKAGE = "jp.hayase.skk"
        const val SKK_IME = "$SKK_PACKAGE/.SkkInputMethodService"
    }
}
