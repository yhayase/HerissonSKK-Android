package jp.hayase.skk.testeditor

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.UiAutomation
import android.os.Bundle
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** 専用エミュレーターで、公開設定画面から保存した入力規則を実際の IME で確認します。 */
@RunWith(AndroidJUnit4::class)
class CustomizationE2eTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val automation: UiAutomation = instrumentation.uiAutomation.apply {
        serviceInfo = serviceInfo.apply {
            flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
        }
    }

    @Test fun publicCustomizationSaveAppliesToNewEditorAndResetRestoresDefaults() {
        openSettings()
        clickAppText("入力規則・句読点・候補を設定する")
        awaitAppText("入力規則・句読点・候補")
        // 既存の個人設定のまま標準規則を複製すると、以前の独自割当てが混ざります。
        clickAppText("入力設定を標準に戻す")
        awaitAppText("ローマ字規則・句読点・候補設定・Emacs 編集キー・各コマンドのキー設定を標準に戻して保存します。個人辞書と学習の設定は変更しません。")
        clickDialogPositive("標準に戻す")
        awaitAppText("入力設定を標準に戻しました。次の入力欄から反映します。")

        openSettings()
        enableStatusDisplay()
        withInitialEditor("abc", 1) { editor ->
            key(KeyEvent.KEYCODE_A, KeyEvent.META_CTRL_ON)
            awaitEditorSelection(editor, "abc", 0, 3)
        }
        openSettings()
        clickAppText("入力規則・句読点・候補を設定する")
        awaitAppText("入力規則・句読点・候補")

        // 標準表を UI から複製し、既存の ka 規則だけを置き換えます。
        clickAppText("標準規則をコピーして編集")
        chooseSpinner("編集する規則", "ka → か")
        setText("出力（最大64文字）", "か゚")
        clickAppText("選択した規則を変更")
        chooseSpinner("句点（標準: 。）", "．")
        clickAppText("保存")
        awaitAppText("入力設定を保存しました。次の入力欄から反映します。")

        assertEditorOutput("か゚．")

        clickAppText("入力設定を標準に戻す")
        awaitAppText("ローマ字規則・句読点・候補設定・Emacs 編集キー・各コマンドのキー設定を標準に戻して保存します。個人辞書と学習の設定は変更しません。")
        clickDialogPositive("標準に戻す")
        awaitAppText("入力設定を標準に戻しました。次の入力欄から反映します。")

        assertEditorOutput("か。")

        openSettings()
        clickAppText("入力規則・句読点・候補を設定する")
        awaitAppText("入力規則・句読点・候補")
        chooseSpinner("ローマ字規則（標準: 標準規則）", "AZIK規則")
        setSwitch("Emacs 編集キーを有効にする", true)
        setText("かな種別切替", "U-]")
        clickAppText("保存")
        awaitAppText("入力設定を保存しました。次の入力欄から反映します。")

        assertAzikAndEmacsEditing()

        clickAppText("入力設定を標準に戻す")
        awaitAppText("ローマ字規則・句読点・候補設定・Emacs 編集キー・各コマンドのキー設定を標準に戻して保存します。個人辞書と学習の設定は変更しません。")
        clickDialogPositive("標準に戻す")
        awaitAppText("入力設定を標準に戻しました。次の入力欄から反映します。")
    }

    private fun assertEditorOutput(expected: String) {
        ActivityScenario.launch<InputTestActivity>(
            android.content.Intent(instrumentation.targetContext, InputTestActivity::class.java)
                .putExtra(InputTestActivity.EXTRA_SUPPRESS_LEARNING, true),
        ).use { scenario ->
            val editor = scenario.editorStartingWith("複数行 A")
            scenario.onActivity { editor.requestFocus() }
            awaitImeReady(editor)
            key(KeyEvent.KEYCODE_J, KeyEvent.META_CTRL_ON)
            type("ka.")
            awaitText(editor, expected)
        }
    }

    private fun assertAzikAndEmacsEditing() {
        withInitialEditor("", 0) { editor ->
            key(KeyEvent.KEYCODE_J, KeyEvent.META_CTRL_ON)
            type("qklx[xxa")
            awaitText(editor, "んこん[ぁ")
            key(KeyEvent.KEYCODE_RIGHT_BRACKET)
            type("q")
            awaitText(editor, "んこん[ぁン")
        }

        val grapheme = "A👩‍💻B\r\nxy\r\nZ"
        withInitialEditor(grapheme, 6) { editor ->
            key(KeyEvent.KEYCODE_A, KeyEvent.META_CTRL_ON)
            awaitEditorState(editor, grapheme, 0)
            key(KeyEvent.KEYCODE_F, KeyEvent.META_CTRL_ON)
            awaitEditorState(editor, grapheme, 1)
            key(KeyEvent.KEYCODE_F, KeyEvent.META_CTRL_ON)
            awaitEditorState(editor, grapheme, 6)
            key(KeyEvent.KEYCODE_B, KeyEvent.META_CTRL_ON)
            awaitEditorState(editor, grapheme, 1)
            key(KeyEvent.KEYCODE_F, KeyEvent.META_CTRL_ON)
            awaitEditorState(editor, grapheme, 6)
            key(KeyEvent.KEYCODE_D, KeyEvent.META_CTRL_ON)
            awaitEditorState(editor, "A👩‍💻\r\nxy\r\nZ", 6)
        }

        withInitialEditor(grapheme, 1) { editor ->
            key(KeyEvent.KEYCODE_K, KeyEvent.META_CTRL_ON)
            awaitEditorState(editor, "A\r\nxy\r\nZ", 1)
        }

        // 窓末尾を文書末尾と推測しないため、移動先の後にも既知の行を残します。
        val lines = "a👩‍💻b\r\nxy\r\nZabc\r\ntail"
        withInitialEditor(lines, 6) { editor ->
            key(KeyEvent.KEYCODE_N, KeyEvent.META_CTRL_ON)
            awaitEditorState(editor, lines, 11)
            key(KeyEvent.KEYCODE_N, KeyEvent.META_CTRL_ON)
            awaitEditorState(editor, lines, 15)
            key(KeyEvent.KEYCODE_P, KeyEvent.META_CTRL_ON)
            awaitEditorState(editor, lines, 11)
        }

        withInitialEditor("abcd", 4) { editor ->
            key(KeyEvent.KEYCODE_F, KeyEvent.META_CTRL_ON)
            awaitEditorState(editor, "abcd", 4)
            key(KeyEvent.KEYCODE_B, KeyEvent.META_CTRL_ON)
            awaitEditorState(editor, "abcd", 3)
            repeatKey(KeyEvent.KEYCODE_B, KeyEvent.META_CTRL_ON)
            awaitEditorState(editor, "abcd", 1)
            repeatKey(KeyEvent.KEYCODE_F, KeyEvent.META_CTRL_ON)
            awaitEditorState(editor, "abcd", 3)
        }

        withInitialEditor("abc", 3) { editor ->
            key(KeyEvent.KEYCODE_M, KeyEvent.META_CTRL_ON)
            awaitEditorState(editor, "abc\n", 4)
        }

        withInitialEditor("abc\ndef", 2) { editor ->
            key(KeyEvent.KEYCODE_COMMA, KeyEvent.META_ALT_ON or KeyEvent.META_SHIFT_ON)
            awaitEditorState(editor, "abc\ndef", 0)
            key(KeyEvent.KEYCODE_PERIOD, KeyEvent.META_ALT_ON or KeyEvent.META_SHIFT_ON)
            awaitEditorState(editor, "abc\ndef", 7)
        }
    }

    private fun withInitialEditor(initial: String, selection: Int, block: (EditText) -> Unit) {
        ActivityScenario.launch<InputTestActivity>(
            android.content.Intent(instrumentation.targetContext, InputTestActivity::class.java)
                .putExtra(InputTestActivity.EXTRA_SUPPRESS_LEARNING, true)
                .putExtra(InputTestActivity.EXTRA_INITIAL_TEXT, initial)
                .putExtra(InputTestActivity.EXTRA_INITIAL_SELECTION, selection),
        ).use { scenario ->
            val editor = scenario.editorStartingWith("複数行 A")
            scenario.onActivity { editor.requestFocus() }
            awaitImeReady(editor)
            block(editor)
        }
    }

    private fun openSettings() {
        shell("am start -W -f 0x14000000 -n $SKK_PACKAGE/.SettingsActivity")
        awaitAppText("SKK の設定")
    }

    private fun chooseSpinner(description: String, choice: String) {
        val spinner = awaitNode("選択欄がありません: $description") { root ->
            root.takeIf(::isApp)?.let { app -> descendants(app).firstOrNull { node ->
                node.className?.toString() == Spinner::class.java.name &&
                    node.contentDescription?.toString() == description && node.isEnabled
            } }
        }
        assertTrue("選択欄を開けません: $description", clickNodeOrParent(spinner))
        val option = awaitNode("選択肢がありません: $choice") { root ->
            descendants(root).firstOrNull { node -> node.text?.toString() == choice && node.isEnabled }
        }
        assertTrue("選択肢を選べません: $choice", clickNodeOrParent(option))
    }

    private fun setText(description: String, value: String) {
        val field = awaitNode("入力欄がありません: $description") { root ->
            root.takeIf(::isApp)?.let { app -> descendants(app).firstOrNull { node ->
                node.className?.toString() == EditText::class.java.name &&
                    node.contentDescription?.toString() == description && node.isEditable
            } }
        }
        assertTrue("入力欄を更新できません: $description", field.performAction(
            AccessibilityNodeInfo.ACTION_SET_TEXT,
            Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value)
            },
        ))
        awaitNode("入力欄の値を確認できません: $description") { root ->
            root.takeIf(::isApp)?.let { app -> descendants(app).firstOrNull { node ->
                node.className?.toString() == EditText::class.java.name &&
                    node.contentDescription?.toString() == description && node.text?.toString() == value
            } }
        }
    }

    private fun switchLabelMatches(node: AccessibilityNodeInfo, label: String): Boolean {
        val text = node.text?.toString()
        return node.className?.toString() == Switch::class.java.name &&
            (text == label || text == "$label ON" || text == "$label OFF")
    }

    private fun setSwitch(text: String, enabled: Boolean) {
        val control = awaitNode("切替項目がありません: $text") { root ->
            root.takeIf(::isApp)?.let { app -> descendants(app).firstOrNull { node ->
                node.className?.toString() == Switch::class.java.name && node.isCheckable &&
                    switchLabelMatches(node, text)
            } }
        }
        if (control.isChecked != enabled) {
            assertTrue("切替項目を更新できません: $text", clickNodeOrParent(control))
        }
        awaitNode("切替項目の値を確認できません: $text") { root ->
            root.takeIf(::isApp)?.let { app -> descendants(app).firstOrNull { node ->
                node.className?.toString() == Switch::class.java.name &&
                    switchLabelMatches(node, text) && node.isChecked == enabled
            } }
        }
    }

    private fun clickAppText(text: String) {
        val node = awaitNode("操作項目がありません: $text") { root ->
            root.takeIf(::isApp)?.let { app -> descendants(app).firstOrNull { node ->
                node.text?.toString() == text && node.isEnabled
            } }
        }
        assertTrue("操作項目を選べません: $text", clickNodeOrParent(node))
    }

    private fun clickDialogPositive(text: String) {
        val node = awaitNode("確認操作がありません: $text") { root ->
            root.takeIf(::isApp)?.findAccessibilityNodeInfosByViewId("android:id/button1")
                ?.firstOrNull { it.text?.toString() == text && it.isEnabled }
        }
        assertTrue("確認操作を選べません: $text", clickNodeOrParent(node))
    }

    private fun awaitAppText(text: String) {
        awaitNode("画面表示が更新されません: $text") { root ->
            root.takeIf(::isApp)?.findAccessibilityNodeInfosByText(text)?.firstOrNull()
        }
    }

    private fun enableStatusDisplay() {
        val status = awaitNode("状態表示の設定がありません") { root ->
            root.takeIf(::isApp)?.let { app -> descendants(app).firstOrNull { node ->
                node.className?.toString() == Switch::class.java.name && node.isCheckable &&
                    switchLabelMatches(node, "入力モード・候補を表示する")
            } }
        }
        if (!status.isChecked) {
            assertTrue("状態表示を有効にできません", clickNodeOrParent(status))
        }
        awaitNode("状態表示が有効になりません") { root ->
            root.takeIf(::isApp)?.let { app -> descendants(app).firstOrNull { node ->
                node.className?.toString() == Switch::class.java.name &&
                    switchLabelMatches(node, "入力モード・候補を表示する") && node.isChecked
            } }
        }
    }

    private fun awaitImeReady(editor: EditText) {
        val deadline = SystemClock.uptimeMillis() + UI_TIMEOUT_MS
        while (SystemClock.uptimeMillis() < deadline) {
            var served = false
            instrumentation.runOnMainSync {
                served = editor.hasFocus() && editor.isAttachedToWindow &&
                    editor.context.getSystemService(InputMethodManager::class.java).isActive(editor)
            }
            val statusVisible = automation.windows.any { window ->
                val root = window.root
                root?.packageName?.toString() == SKK_PACKAGE &&
                    root.findAccessibilityNodeInfosByViewId("$SKK_PACKAGE:id/input_status").isNotEmpty()
            }
            if (served && statusVisible) return
            SystemClock.sleep(POLL_MS)
        }
        throw AssertionError("入力欄または SKK の入力状態表示が準備できません")
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

    private fun type(value: String) {
        val events = KeyCharacterMap.load(KeyCharacterMap.VIRTUAL_KEYBOARD).getEvents(value.toCharArray())!!
        events.forEach { instrumentation.sendKeySync(it) }
        instrumentation.waitForIdleSync()
    }

    private fun key(code: Int, meta: Int = 0) {
        val now = SystemClock.uptimeMillis()
        instrumentation.sendKeySync(KeyEvent(now, now, KeyEvent.ACTION_DOWN, code, 0, meta,
            KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0, InputDevice.SOURCE_KEYBOARD))
        instrumentation.sendKeySync(KeyEvent(now, SystemClock.uptimeMillis(), KeyEvent.ACTION_UP, code, 0, meta,
            KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0, InputDevice.SOURCE_KEYBOARD))
        instrumentation.waitForIdleSync()
    }

    private fun repeatKey(code: Int, meta: Int) {
        val downTime = SystemClock.uptimeMillis()
        instrumentation.sendKeySync(KeyEvent(downTime, downTime, KeyEvent.ACTION_DOWN, code, 0, meta,
            KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0, InputDevice.SOURCE_KEYBOARD))
        instrumentation.sendKeySync(KeyEvent(downTime, SystemClock.uptimeMillis(), KeyEvent.ACTION_DOWN, code, 1, meta,
            KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0, InputDevice.SOURCE_KEYBOARD))
        instrumentation.sendKeySync(KeyEvent(downTime, SystemClock.uptimeMillis(), KeyEvent.ACTION_UP, code, 0, meta,
            KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0, InputDevice.SOURCE_KEYBOARD))
        instrumentation.waitForIdleSync()
    }

    private fun awaitText(view: TextView, expected: String) {
        val deadline = SystemClock.uptimeMillis() + UI_TIMEOUT_MS
        while (SystemClock.uptimeMillis() < deadline && text(view) != expected) SystemClock.sleep(POLL_MS)
        assertEquals("設定した入力規則または句読点が反映されません", expected, text(view))
    }

    private fun awaitEditorState(editor: EditText, expected: String, selection: Int) {
        val deadline = SystemClock.uptimeMillis() + UI_TIMEOUT_MS
        while (SystemClock.uptimeMillis() < deadline) {
            var actualText = ""
            var actualSelection = -1
            instrumentation.runOnMainSync {
                actualText = editor.text.toString()
                actualSelection = editor.selectionStart
            }
            if (actualText == expected && actualSelection == selection) return
            SystemClock.sleep(POLL_MS)
        }
        var actualText = ""
        var actualSelection = -1
        instrumentation.runOnMainSync {
            actualText = editor.text.toString()
            actualSelection = editor.selectionStart
        }
        assertEquals("外部編集の本文が一致しません", expected, actualText)
        assertEquals("外部編集のカーソル位置が一致しません", selection, actualSelection)
    }

    private fun awaitEditorSelection(editor: EditText, expected: String, start: Int, end: Int) {
        val deadline = SystemClock.uptimeMillis() + UI_TIMEOUT_MS
        while (SystemClock.uptimeMillis() < deadline) {
            var actualText = ""
            var actualStart = -1
            var actualEnd = -1
            instrumentation.runOnMainSync {
                actualText = editor.text.toString()
                actualStart = editor.selectionStart
                actualEnd = editor.selectionEnd
            }
            if (actualText == expected && actualStart == start && actualEnd == end) return
            SystemClock.sleep(POLL_MS)
        }
        throw AssertionError("Emacs 無効時のネイティブ選択が反映されません")
    }

    private fun text(view: TextView): String {
        var value = ""
        instrumentation.runOnMainSync { value = view.text.toString() }
        return value
    }

    private fun awaitNode(message: String, find: (AccessibilityNodeInfo) -> AccessibilityNodeInfo?): AccessibilityNodeInfo {
        val deadline = SystemClock.uptimeMillis() + UI_TIMEOUT_MS
        while (SystemClock.uptimeMillis() < deadline) {
            automation.windows.asSequence().mapNotNull { it.root }.mapNotNull(find).firstOrNull()?.let { return it }
            SystemClock.sleep(POLL_MS)
        }
        throw AssertionError(message + "\\n" + automation.windows.joinToString { window ->
            "package=${window.root?.packageName}, nodes=${descendants(window.root).map { node ->
                "${node.viewIdResourceName}:${node.text}:${node.contentDescription}"
            }.take(16)}"
        })
    }

    private fun clickNodeOrParent(node: AccessibilityNodeInfo): Boolean {
        var current: AccessibilityNodeInfo? = node
        while (current != null) {
            if (current.isEnabled && current.isClickable && current.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
            current = current.parent
        }
        return node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }

    private fun isApp(root: AccessibilityNodeInfo): Boolean = root.packageName?.toString() == SKK_PACKAGE

    private fun descendants(view: View?): List<View> = when (view) {
        null -> emptyList()
        is ViewGroup -> listOf(view) + (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) }
        else -> listOf(view)
    }

    private fun descendants(node: AccessibilityNodeInfo?): List<AccessibilityNodeInfo> = when (node) {
        null -> emptyList()
        else -> listOf(node) + (0 until node.childCount).flatMap { descendants(node.getChild(it)) }
    }

    private fun shell(command: String) {
        val output = android.os.ParcelFileDescriptor.AutoCloseInputStream(
            automation.executeShellCommand(command),
        ).bufferedReader().use { it.readText() }
        check(output.contains("Status: ok") || output.isNotBlank()) { "設定画面を開始できません: $output" }
    }

    private companion object {
        const val SKK_PACKAGE = "jp.hayase.skk"
        const val UI_TIMEOUT_MS = 10_000L
        const val POLL_MS = 25L
    }
}
