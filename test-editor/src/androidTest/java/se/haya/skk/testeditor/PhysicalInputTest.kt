package se.haya.skk.testeditor

import android.os.SystemClock
import android.accessibilityservice.AccessibilityServiceInfo
import android.view.InputDevice
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
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

    /** 辞書検索の完了を待たずに送った確定・送り変換・候補ラベルを順序どおり適用します。 */
    @Test fun consecutiveConversionAndCommitKeysStayOrdered() {
        withSendEditor { editor, counter ->
            type("Nihon ")
            key(KeyEvent.KEYCODE_ENTER)
            type("KakU")
            key(KeyEvent.KEYCODE_ENTER)
            type("Tesuto   a")
            // 既存の学習順位は保持し、検索を待たず送ったキー列の順序と一回確定を検証します。
            val expected = Regex("日本書く候補(?:10|[1-9])")
            val deadline = SystemClock.uptimeMillis() + 5000
            while (SystemClock.uptimeMillis() < deadline && !expected.matches(text(editor))) SystemClock.sleep(20)
            assertTrue("確定文字の順序が一致しません: ${text(editor)}", expected.matches(text(editor)))
            instrumentation.runOnMainSync {
                assertEquals(-1, BaseInputConnection.getComposingSpanStart(editor.text))
            }
            awaitText(counter, "入力先への Enter／アクション: 2 回")
        }
    }

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
            key(KeyEvent.KEYCODE_J, KeyEvent.META_CTRL_ON)
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
            awaitIme(normal)
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
            awaitIme(editor)
            key(KeyEvent.KEYCODE_J, KeyEvent.META_CTRL_ON)

            type("Nihon ")

            awaitText(editor, "日本")
        }
    }

    /** K01・K02・I02: Enterは残余確定後に入力先へ渡し、文字種切替は入力先へ渡しません。 */
    @Test fun terminalRomajiEnterReachesEditorAndPreservesKanaModes() {
        withSendEditor { editor, counter ->
            type("n")
            key(KeyEvent.KEYCODE_ENTER)
            awaitText(editor, "ん")
            type("q")
            type("n")
            key(KeyEvent.KEYCODE_ENTER)
            awaitText(editor, "んン")
            type("kana")
            awaitText(editor, "んンカナ")
            key(KeyEvent.KEYCODE_J, KeyEvent.META_CTRL_ON)
            type("kitte")
            awaitText(editor, "んンカナきって")
            awaitText(counter, "入力先への Enter／アクション: 2 回")
        }
    }

    /** K04・K07・K08: 送りや注釈が確定文字へ二重に付かないことを実配送で確認します。 */
    @Test fun okuriAbbrevAndPrefixSuffixCommitExactlyOnce() {
        withSendEditor { editor, counter ->
            type("KaKu")
            awaitText(editor, "書く")
            key(KeyEvent.KEYCODE_J, KeyEvent.META_CTRL_ON)
            type("/API ")
            awaitText(editor, "書くエーピーアイ")
            key(KeyEvent.KEYCODE_J, KeyEvent.META_CTRL_ON)
            type("Dai>")
            awaitText(editor, "書くエーピーアイ第")
            type(">kai ")
            awaitText(editor, "書くエーピーアイ第回")
            key(KeyEvent.KEYCODE_J, KeyEvent.META_CTRL_ON)
            assertEquals("入力先への Enter／アクション: 0 回", text(counter))
        }
    }

    /** 子音の大文字を忘れても、母音の大文字で送りを開始します。 */
    @Test fun uppercaseVowelStartsPendingConsonantOkuri() {
        withSendEditor { editor, counter ->
            type("KakU")
            awaitText(editor, "書く")
            key(KeyEvent.KEYCODE_J, KeyEvent.META_CTRL_ON)
            awaitText(editor, "書く")
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
            key(KeyEvent.KEYCODE_J, KeyEvent.META_CTRL_ON)
            assertEquals("入力先への Enter／アクション: 0 回", text(counter))
        }
    }

    @Test fun cancelRegistrationRestoresLastMenuAndItsLabel() {
        withSendEditor { editor, counter ->
            type("Tesuto   ")
            val candidatePattern = Regex("候補(?:10|[1-9])")
            val initialDeadline = SystemClock.uptimeMillis() + 5000
            while (!candidatePattern.matches(text(editor)) && SystemClock.uptimeMillis() < initialDeadline) {
                SystemClock.sleep(20)
            }
            var lastCandidate = text(editor)
            assertTrue("候補メニューが表示されません", candidatePattern.matches(lastCandidate))
            var registrationShown = false
            repeat(12) {
                if (!registrationShown) {
                    key(KeyEvent.KEYCODE_SPACE)
                    val deadline = SystemClock.uptimeMillis() + 5000
                    while (SystemClock.uptimeMillis() < deadline) {
                        registrationShown = configuredAutomation().windows.any { window ->
                            window.root?.findAccessibilityNodeInfosByText("単語登録")?.isNotEmpty() == true
                        }
                        if (registrationShown) break
                        val current = text(editor)
                        if (candidatePattern.matches(current) && current != lastCandidate) {
                            lastCandidate = current
                            break
                        }
                        // 登録の読みが先に反映されても、元候補を上書きせず登録窓を待ちます。
                        SystemClock.sleep(20)
                    }
                    assertTrue("次の候補または登録窓が表示されません",
                        registrationShown || candidatePattern.matches(text(editor)))
                }
            }
            assertTrue("候補を使い切っても登録に移りません", registrationShown)
            key(KeyEvent.KEYCODE_G, KeyEvent.META_CTRL_ON)
            awaitText(editor, lastCandidate)
            type("a")
            awaitText(editor, lastCandidate)
            assertEquals("入力先への Enter／アクション: 0 回", text(counter))
            key(KeyEvent.KEYCODE_ENTER)
            awaitText(counter, "入力先への Enter／アクション: 1 回")
        }
    }

    /** K06: 十分な高さでは4番目まで表示し、fで選んでもラベル文字を本文へ混ぜません。 */
    @Test fun fourthMenuLabelCommitsCandidateWithoutAnnotationOrLiteralKey() {
        withSendEditor { editor, counter ->
            type("Tesuto   ")
            androidx.test.uiautomator.UiDevice.getInstance(instrumentation).waitForIdle()
            val deadline = SystemClock.uptimeMillis() + 5000
            var selected: String? = null
            while (SystemClock.uptimeMillis() < deadline && selected == null) {
                for (window in configuredAutomation().windows) {
                    val root = window.root ?: continue
                    if (root.packageName?.toString() != SKK_PACKAGE) continue
                    val rows = descendants(root).filter {
                        it.isVisibleToUser &&
                            it.contentDescription?.toString()?.matches(Regex("[asdfjkl]: 候補(?:10|[1-9]).*")) == true
                    }
                    if (rows.size < 4) continue
                    val tile = rows.firstOrNull {
                        it.contentDescription?.toString()?.startsWith("f: 候補") == true
                    } ?: continue
                    val description = tile.contentDescription.toString()
                    val candidate = Regex("^f: (候補(?:10|[1-9]))、").find(description)?.groupValues?.get(1)
                        ?: continue
                    if (description.contains("、注釈: 注釈${candidate.removePrefix("候補")}")) {
                        selected = candidate
                        break
                    }
                }
                if (selected == null) SystemClock.sleep(20)
            }
            assertNotNull("十分な高さで4番目の候補・ラベル・注釈を表示できません", selected)
            type("f")
            awaitText(editor, checkNotNull(selected))
            var confirmed = false
            val commitDeadline = SystemClock.uptimeMillis() + 5000
            while (SystemClock.uptimeMillis() < commitDeadline && !confirmed) {
                instrumentation.runOnMainSync {
                    confirmed = BaseInputConnection.getComposingSpanStart(editor.text) == -1 &&
                        BaseInputConnection.getComposingSpanEnd(editor.text) == -1
                }
                if (!confirmed) SystemClock.sleep(20)
            }
            assertTrue("4番目の候補ラベルだけで確定されません", confirmed)
            assertFalse("候補確定後にラベル文字 f を入力しました", text(editor).endsWith("f"))
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
            awaitIme(editor)
            key(KeyEvent.KEYCODE_J, KeyEvent.META_CTRL_ON)
            block(editor, counter)
        }
    }

    @Test fun candidateEnterConfirmsAndReachesEditorExactlyOnce() {
        launchWithoutLearning().use { scenario ->
            lateinit var editor: EditText
            lateinit var counter: TextView
            scenario.onActivity { activity ->
                val views = descendants(activity.window.decorView)
                editor = views.filterIsInstance<EditText>().first { it.hint.toString().startsWith("送信") }
                counter = views.filterIsInstance<TextView>().first { it.text.toString().startsWith("入力先への") }
                editor.requestFocus()
            }
            awaitIme(editor)
            key(KeyEvent.KEYCODE_J, KeyEvent.META_CTRL_ON)
            type("Nihon ")
            awaitText(editor, "日本")
            key(KeyEvent.KEYCODE_ENTER)
            awaitText(editor, "日本")
            awaitText(counter, "入力先への Enter／アクション: 1 回")
            key(KeyEvent.KEYCODE_ENTER)
            awaitText(counter, "入力先への Enter／アクション: 2 回")
            assertEquals("日本", text(editor))
        }
    }

    @Test fun candidateEnterAddsOneNewlineToMultilineEditor() {
        launchWithoutLearning().use { scenario ->
            val editor = scenario.editorStartingWith("複数行 A")
            scenario.onActivity { editor.requestFocus() }
            awaitIme(editor)
            key(KeyEvent.KEYCODE_J, KeyEvent.META_CTRL_ON)
            type("Nihon ")
            awaitText(editor, "日本")
            key(KeyEvent.KEYCODE_ENTER)
            awaitText(editor, "日本\n")
            instrumentation.runOnMainSync {
                assertEquals(-1, BaseInputConnection.getComposingSpanStart(editor.text))
            }
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
            awaitIme(first)
            key(KeyEvent.KEYCODE_J, KeyEvent.META_CTRL_ON)
            type("Ni")
            awaitText(first, "に")
            scenario.onActivity { second.requestFocus() }
            awaitIme(second)
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
            awaitIme(editor)
            key(KeyEvent.KEYCODE_J, KeyEvent.META_CTRL_ON)
            type("Nihon ")
            awaitText(editor, "日本")
            scenario.onActivity { editor.setSelection(0) }
            // 別プロセスのIMEが外部移動を処理し、未確定範囲を解除するまで待ちます。
            val deadline = SystemClock.uptimeMillis() + 5000
            var preserved = false
            while (SystemClock.uptimeMillis() < deadline && !preserved) {
                instrumentation.runOnMainSync {
                    preserved = editor.selectionStart == 0 && editor.selectionEnd == 0 &&
                        BaseInputConnection.getComposingSpanStart(editor.text) == -1
                }
                if (!preserved) SystemClock.sleep(20)
            }
            assertTrue("外部カーソル移動の処理が完了しません", preserved)
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

    private fun awaitIme(editor: EditText) {
        instrumentation.waitForIdleSync()
        val automation = configuredAutomation()
        val deadline = SystemClock.uptimeMillis() + 5000
        while (SystemClock.uptimeMillis() < deadline) {
            // モードは短時間のポップアップなので、常設の状態ビューを準備条件にしません。
            if (selectedIme(automation) == SKK_IME && isServedEditor(editor)) {
                androidx.test.uiautomator.UiDevice.getInstance(instrumentation).waitForIdle()
                return
            }
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
        fail("SKK と入力欄の接続が準備できません。\n$diagnostic")
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

    private fun descendants(node: AccessibilityNodeInfo?): List<AccessibilityNodeInfo> = when (node) {
        null -> emptyList()
        else -> listOf(node) + (0 until node.childCount).flatMap { descendants(node.getChild(it)) }
    }

    private companion object {
        const val SKK_PACKAGE = "se.haya.skk"
        const val SKK_IME = "$SKK_PACKAGE/.SkkInputMethodService"
    }
}
