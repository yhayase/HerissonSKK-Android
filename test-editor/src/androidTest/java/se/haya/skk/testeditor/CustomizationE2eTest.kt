package se.haya.skk.testeditor

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
        // 既存の個人設定を各ページで標準値へ戻し、独自割当てが試験へ混ざらないようにします。
        resetPages("ローマ字の打ち方", "句読点と記号", "キー操作とEmacs編集", "候補の表示と選び方")

        enableStatusDisplay()
        withInitialEditor("abc", 1) { editor ->
            key(KeyEvent.KEYCODE_A, KeyEvent.META_CTRL_ON)
            awaitEditorSelection(editor, "abc", 0, 3)
        }
        openSettings()
        openCustomizationPage("ローマ字の打ち方")

        // 標準表を UI から複製し、既存の ka 規則だけを置き換えます。
        choosePreference("ローマ字規則", "カスタム")
        clickAppText("標準規則をコピーして編集")
        clickAppText("ka → か")
        setText("入力される文字", "か゚")
        clickDialogPositive("変更")
        saveCurrentPage()
        openCustomizationPage("句読点と記号")
        choosePreference("句点", "．　全角ピリオド")
        saveCurrentPage()

        assertEditorOutput("か゚．")

        openSettings()
        resetPages("ローマ字の打ち方", "句読点と記号")

        assertEditorOutput("か。")

        openSettings()
        openCustomizationPage("キー操作とEmacs編集")
        choosePreference("Emacs キーバインド", "IME とアプリ")
        saveCurrentPage()
        openCustomizationPage("候補の表示と選び方")
        setSwitch("未確定文字に ▽／▼ を表示する", true)
        saveCurrentPage()
        assertStandardSymbolsMarkersAndQuote()
        assertEnterAndKillSemantics()
        openSettings()
        openCustomizationPage("候補の表示と選び方")
        setSwitch("未確定文字に ▽／▼ を表示する", false)
        saveCurrentPage()
        openCustomizationPage("キー操作とEmacs編集")
        choosePreference("Emacs キーバインド", "IME とアプリ")
        clickAppText("半角カナ")
        setText("半角カナ", "C-S-Q", standardPreference = true)
        clickDialogPositive(instrumentation.targetContext.getString(android.R.string.ok))
        clickAppText("かな種別切替")
        setText("かな種別切替", "U-]", standardPreference = true)
        clickDialogPositive(instrumentation.targetContext.getString(android.R.string.ok))
        saveCurrentPage()
        openCustomizationPage("ローマ字の打ち方")
        choosePreference("ローマ字規則", "AZIK")
        saveCurrentPage()

        assertAzikAndEmacsEditing()
        WebEditorAssertions.run(instrumentation)

        openSettings()
        resetPages("ローマ字の打ち方", "句読点と記号", "キー操作とEmacs編集", "候補の表示と選び方")
    }

    @Test fun emacsModeAndAppOverrideSelectTheWholeEditingScope() {
        openSettings()
        resetPages("ローマ字の打ち方", "キー操作とEmacs編集", "候補の表示と選び方")
        selectTestAppMode("全体設定に従う")
        for ((label, internal, external) in listOf(
            Triple("無効", false, false),
            Triple("IME 内のみ", true, false),
            Triple("IME とアプリ", true, true),
        )) {
            selectGlobalEmacsMode(label)
            assertEmacsScope(internal, external)
        }
        // 全体が有効でも、アプリ別の選択が IME 内を含む範囲全体を置き換えます。
        selectTestAppMode("無効")
        assertEmacsScope(false, false)
        selectTestAppMode("IME 内のみ")
        assertEmacsScope(true, false)
        selectGlobalEmacsMode("無効")
        selectTestAppMode("IME とアプリ")
        assertEmacsScope(true, true)
        selectTestAppMode("全体設定に従う")
        assertEmacsScope(false, false)
        openSettings()
        resetPages("キー操作とEmacs編集")
    }

    private fun selectGlobalEmacsMode(label: String) {
        openSettings()
        openCustomizationPage("キー操作とEmacs編集")
        choosePreference("Emacs キーバインド", label)
        saveCurrentPage()
    }

    private fun selectTestAppMode(label: String) {
        openSettings()
        clickAppText("アプリごとの編集キー")
        awaitAppText("アプリ別 Emacs 編集")
        // 旧パッケージの同名アプリが共存しても、対象の ID で絞り込みます。
        val search = awaitNode("アプリ検索欄がありません") { root ->
            root.takeIf(::isApp)?.findAccessibilityNodeInfosByViewId("android:id/search_src_text")
                ?.firstOrNull()
        }
        assertTrue(search.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "se.haya.skk.testeditor")
        }))
        val app = awaitNode("対象アプリがありません") { root ->
            root.takeIf(::isApp)?.let(::descendants)?.firstOrNull {
                val lines = it.text?.toString()?.lines().orEmpty()
                it.viewIdResourceName == "android:id/summary" &&
                    lines.size == 3 && lines[2] == "se.haya.skk.testeditor"
            }
        }
        assertTrue(clickNodeOrParent(app))
        chooseDialogItem(label)
        awaitNode("アプリ別設定の保存が反映されません: $label") { root ->
            if (!isApp(root)) return@awaitNode null
            val nodes = descendants(root)
            val selected = nodes.any { node ->
                val lines = node.text?.toString()?.lines().orEmpty()
                lines.size == 3 && lines[1] == label && lines[2] == "se.haya.skk.testeditor"
            }
            val idle = nodes.any { it.text?.toString() in setOf(
                "保存しました。次の入力欄から反映します。",
                "変更は選択したときに保存され、次の入力欄から反映します。",
            ) }
            val dialog = nodes.any { it.className?.toString() == "android.widget.CheckedTextView" }
            root.takeIf { selected && idle && !dialog }
        }
    }

    private fun chooseDialogItem(label: String) {
        val option = awaitNode("選択肢がありません: $label") { root ->
            descendants(root).firstOrNull { it.text?.toString() == label && it.isEnabled }
        }
        assertTrue("選択肢を選べません: $label", clickNodeOrParent(option))
    }

    private fun assertEmacsScope(internal: Boolean, external: Boolean) {
        for (composing in listOf(false, true)) {
            ActivityScenario.launch<InputTestActivity>(
                android.content.Intent(instrumentation.targetContext, InputTestActivity::class.java)
                    .putExtra(InputTestActivity.EXTRA_SUPPRESS_LEARNING, true)
                    .putExtra(InputTestActivity.EXTRA_INITIAL_TEXT, if (composing) "" else "abc")
                    .putExtra(InputTestActivity.EXTRA_INITIAL_SELECTION, if (composing) 0 else 2),
            ).use { scenario ->
                val editor = scenario.editorStartingWith("複数行 A")
                scenario.onActivity { editor.requestFocus() }
                awaitImeReady(editor)
                key(KeyEvent.KEYCODE_J, KeyEvent.META_CTRL_ON)
                if (composing) {
                    type("Ka")
                    awaitText(editor, "か")
                }
                if (composing) {
                    key(KeyEvent.KEYCODE_B, KeyEvent.META_CTRL_ON)
                    type("ta")
                    key(KeyEvent.KEYCODE_J, KeyEvent.META_CTRL_ON)
                    awaitText(editor, if (internal) "たか" else "かた")
                } else {
                    // 無効時の Android 標準 C-a は全選択、有効時は Emacs の行頭移動です。
                    key(KeyEvent.KEYCODE_A, KeyEvent.META_CTRL_ON)
                    awaitEditorSelection(editor, "abc", 0, if (external) 0 else 3)
                }
            }
        }
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

    private fun assertStandardSymbolsMarkersAndQuote() {
        withInitialEditor("", 0) { editor ->
            type("zhzjzkzlz.z/fafifefo")
            awaitText(editor, "←↓↑→…・ふぁふぃふぇふぉ")
        }
        withInitialEditor("", 0) { editor ->
            type("Nihon")
            awaitText(editor, "▽にほn")
            key(KeyEvent.KEYCODE_SPACE)
            awaitText(editor, "▼日本")
            key(KeyEvent.KEYCODE_SPACE)
            awaitText(editor, "▼二本")
            key(KeyEvent.KEYCODE_G, KeyEvent.META_CTRL_ON)
            awaitText(editor, "▽にほn")
            key(KeyEvent.KEYCODE_SPACE)
            key(KeyEvent.KEYCODE_J, KeyEvent.META_CTRL_ON)
            awaitText(editor, "日本")
            type("Ka")
            awaitText(editor, "日本▽か")
            key(KeyEvent.KEYCODE_Q, KeyEvent.META_CTRL_ON)
            awaitText(editor, "日本か")
            key(KeyEvent.KEYCODE_N, KeyEvent.META_CTRL_ON)
            awaitText(editor, "日本か")
        }
        ActivityScenario.launch<InputTestActivity>(
            android.content.Intent(instrumentation.targetContext, InputTestActivity::class.java)
                .putExtra(InputTestActivity.EXTRA_SUPPRESS_LEARNING, true),
        ).use { scenario ->
            val editor = scenario.editorStartingWith("複数行 A")
            scenario.onActivity { editor.requestFocus() }
            awaitImeReady(editor)
            scenario.onActivity { it.receivedKeys.clear() }
            repeatKey(KeyEvent.KEYCODE_Q, KeyEvent.META_CTRL_ON)
            repeatKey(KeyEvent.KEYCODE_N, KeyEvent.META_CTRL_ON)
            scenario.onActivity { activity ->
                assertTrue(activity.receivedKeys.none { it.keyCode == KeyEvent.KEYCODE_Q })
                val quoted = activity.receivedKeys.filter { it.keyCode == KeyEvent.KEYCODE_N }
                assertEquals(listOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP), quoted.map { it.action })
                assertTrue(quoted.all { it.isCtrlPressed })
                assertEquals(1, quoted[1].repeatCount)
            }
        }
    }

    private fun assertEnterAndKillSemantics() {
        // 外部へ送信しない専用欄で、検索・送信とも Enter と C-m の通知を比較します。
        for (hint in listOf("検索", "送信")) {
            ActivityScenario.launch<InputTestActivity>(InputTestActivity::class.java).use { scenario ->
                val editor = scenario.editorStartingWith(hint)
                scenario.onActivity { editor.requestFocus() }
                awaitImeReady(editor)
                type("l")
                type("abc")
                awaitText(editor, "abc")
                key(KeyEvent.KEYCODE_ENTER)
                val deadline = SystemClock.uptimeMillis() + UI_TIMEOUT_MS
                var count = 0
                while (SystemClock.uptimeMillis() < deadline && count < 1) {
                    scenario.onActivity { count = it.editorActionCount }
                    SystemClock.sleep(POLL_MS)
                }
                assertEquals(1, count)
                scenario.onActivity { it.receivedKeys.clear() }
                key(KeyEvent.KEYCODE_M, KeyEvent.META_CTRL_ON)
                val secondDeadline = SystemClock.uptimeMillis() + UI_TIMEOUT_MS
                while (SystemClock.uptimeMillis() < secondDeadline && count < 2) {
                    scenario.onActivity { count = it.editorActionCount }
                    SystemClock.sleep(POLL_MS)
                }
                assertEquals(2, count)
                awaitText(editor, "abc")
                scenario.onActivity { activity ->
                    val enter = activity.receivedKeys.filter { it.keyCode == KeyEvent.KEYCODE_ENTER }
                    assertEquals(listOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP), enter.map { it.action })
                    assertTrue(enter.none { it.isCtrlPressed || it.isShiftPressed || it.isAltPressed })
                    assertTrue(activity.receivedKeys.none { it.keyCode == KeyEvent.KEYCODE_M })
                }
            }
        }
        withInitialEditor("abc\ndef", 1) { editor ->
            key(KeyEvent.KEYCODE_K, KeyEvent.META_CTRL_ON)
            awaitEditorState(editor, "a\ndef", 1)
            key(KeyEvent.KEYCODE_K, KeyEvent.META_CTRL_ON)
            awaitEditorState(editor, "adef", 1)
            key(KeyEvent.KEYCODE_K, KeyEvent.META_CTRL_ON)
            awaitEditorState(editor, "a", 1)
            key(KeyEvent.KEYCODE_K, KeyEvent.META_CTRL_ON)
            awaitEditorState(editor, "a", 1)
            key(KeyEvent.KEYCODE_Y, KeyEvent.META_CTRL_ON)
            awaitEditorState(editor, "abc\ndef", 7)
            key(KeyEvent.KEYCODE_B, KeyEvent.META_CTRL_ON)
            awaitEditorState(editor, "abc\ndef", 6)
            key(KeyEvent.KEYCODE_K, KeyEvent.META_CTRL_ON)
            awaitEditorState(editor, "abc\nde", 6)
            key(KeyEvent.KEYCODE_Y, KeyEvent.META_CTRL_ON)
            awaitEditorState(editor, "abc\ndef", 7)
        }
        for (backspace in listOf(KeyEvent.KEYCODE_DEL to 0, KeyEvent.KEYCODE_H to KeyEvent.META_CTRL_ON)) {
            withInitialEditor("", 0) { editor ->
                type("Nihon")
                key(KeyEvent.KEYCODE_SPACE)
                awaitText(editor, "▼日本")
                key(backspace.first, backspace.second)
                awaitText(editor, "日")
            }
        }
        withInitialEditor("", 0) { editor ->
            type("K")
            key(KeyEvent.KEYCODE_H, KeyEvent.META_CTRL_ON)
            awaitText(editor, "▽")
            key(KeyEvent.KEYCODE_H, KeyEvent.META_CTRL_ON)
            awaitText(editor, "")
        }
    }

    private fun assertAzikAndEmacsEditing() {
        withInitialEditor("", 0) { editor ->
            key(KeyEvent.KEYCODE_Q, KeyEvent.META_CTRL_ON or KeyEvent.META_SHIFT_ON)
            type("kana")
            awaitText(editor, "ｶﾅ")
        }
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

        // 最終行も削除でき、削除した文字列をクリップボード経由で戻せます。
        withInitialEditor("head\nA👩‍💻B", 6) { editor ->
            key(KeyEvent.KEYCODE_K, KeyEvent.META_CTRL_ON)
            awaitEditorState(editor, "head\nA", 6)
            key(KeyEvent.KEYCODE_Y, KeyEvent.META_CTRL_ON)
            awaitEditorState(editor, "head\nA👩‍💻B", 12)
        }
        // 入力先のUndo単位に従います。削除と貼り付けが結合されない独立欄で確認します。
        withInitialEditor("head\nA", 6) { editor ->
            key(KeyEvent.KEYCODE_Y, KeyEvent.META_CTRL_ON)
            awaitEditorState(editor, "head\nA👩‍💻B", 12)
            key(KeyEvent.KEYCODE_SLASH, KeyEvent.META_CTRL_ON)
            awaitEditorState(editor, "head\nA", 6)
        }

        // 複数行の列保持と、別ケースで最終行への往復を確認します。
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
            key(KeyEvent.KEYCODE_P, KeyEvent.META_CTRL_ON)
            awaitEditorState(editor, "abc\n", 0)
            key(KeyEvent.KEYCODE_N, KeyEvent.META_CTRL_ON)
            awaitEditorState(editor, "abc\n", 4)
            key(KeyEvent.KEYCODE_H, KeyEvent.META_CTRL_ON)
            awaitEditorState(editor, "abc", 3)
            key(KeyEvent.KEYCODE_M, KeyEvent.META_CTRL_ON)
            awaitEditorState(editor, "abc\n", 4)
            key(KeyEvent.KEYCODE_B, KeyEvent.META_CTRL_ON)
            awaitEditorState(editor, "abc\n", 3)
            key(KeyEvent.KEYCODE_D, KeyEvent.META_CTRL_ON)
            awaitEditorState(editor, "abc", 3)
        }

        withInitialEditor("abc\nxyz", 7) { editor ->
            key(KeyEvent.KEYCODE_P, KeyEvent.META_CTRL_ON)
            awaitEditorState(editor, "abc\nxyz", 3)
            key(KeyEvent.KEYCODE_N, KeyEvent.META_CTRL_ON)
            awaitEditorState(editor, "abc\nxyz", 7)
            key(KeyEvent.KEYCODE_H, KeyEvent.META_CTRL_ON)
            awaitEditorState(editor, "abc\nxy", 6)
            key(KeyEvent.KEYCODE_B, KeyEvent.META_CTRL_ON)
            awaitEditorState(editor, "abc\nxy", 5)
            key(KeyEvent.KEYCODE_D, KeyEvent.META_CTRL_ON)
            awaitEditorState(editor, "abc\nx", 5)
        }
        withInitialEditor("A👩‍💻", 6) { editor ->
            key(KeyEvent.KEYCODE_H, KeyEvent.META_CTRL_ON)
            awaitEditorState(editor, "A", 1)
            key(KeyEvent.KEYCODE_H, KeyEvent.META_CTRL_ON)
            awaitEditorState(editor, "", 0)
            key(KeyEvent.KEYCODE_M, KeyEvent.META_CTRL_ON)
            awaitEditorState(editor, "\n", 1)
        }

        for (mode in listOf("no_snapshot", "extracted_only", "unknown_offset")) {
            withInitialEditor("abc\nxyz", 7, mode) { editor ->
                key(KeyEvent.KEYCODE_P, KeyEvent.META_CTRL_ON)
                awaitEditorState(editor, "abc\nxyz", 3)
                key(KeyEvent.KEYCODE_N, KeyEvent.META_CTRL_ON)
                awaitEditorState(editor, "abc\nxyz", 7)
                key(KeyEvent.KEYCODE_H, KeyEvent.META_CTRL_ON)
                awaitEditorState(editor, "abc\nxy", 6)
                key(KeyEvent.KEYCODE_B, KeyEvent.META_CTRL_ON)
                awaitEditorState(editor, "abc\nxy", 5)
                key(KeyEvent.KEYCODE_D, KeyEvent.META_CTRL_ON)
                awaitEditorState(editor, "abc\nx", 5)
                key(KeyEvent.KEYCODE_M, KeyEvent.META_CTRL_ON)
                awaitEditorState(editor, "abc\nx\n", 6)
                key(KeyEvent.KEYCODE_B, KeyEvent.META_CTRL_ON)
                awaitEditorState(editor, "abc\nx\n", 5)
            }
        }
        // C-m は commitText の改行フィルターを経由せず、Enter と同じ経路を使います。
        withInitialEditor("abc", 3, "filtered_newline") { editor ->
            key(KeyEvent.KEYCODE_M, KeyEvent.META_CTRL_ON)
            awaitEditorState(editor, "abc\n", 4)
            key(KeyEvent.KEYCODE_B, KeyEvent.META_CTRL_ON)
            awaitEditorState(editor, "abc\n", 3)
            key(KeyEvent.KEYCODE_D, KeyEvent.META_CTRL_ON)
            awaitEditorState(editor, "abc", 3)
        }

        withInitialEditor("abc\ndef", 2) { editor ->
            physicalMetaBoundary(KeyEvent.KEYCODE_COMMA, editor, "abc\ndef", 0)
            awaitEditorState(editor, "abc\ndef", 0)
            physicalMetaBoundary(KeyEvent.KEYCODE_PERIOD, editor, "abc\ndef", 7)
            awaitEditorState(editor, "abc\ndef", 7)
        }
    }

    private fun withInitialEditor(initial: String, selection: Int,
        connectionMode: String? = null, block: (EditText) -> Unit) {
        ActivityScenario.launch<InputTestActivity>(
            android.content.Intent(instrumentation.targetContext, InputTestActivity::class.java)
                .putExtra(InputTestActivity.EXTRA_SUPPRESS_LEARNING, true)
                .putExtra(InputTestActivity.EXTRA_INITIAL_TEXT, initial)
                .putExtra(InputTestActivity.EXTRA_INITIAL_SELECTION, selection)
                .putExtra(InputTestActivity.EXTRA_CONNECTION_MODE, connectionMode),
        ).use { scenario ->
            val editor = scenario.editorStartingWith("複数行 A")
            scenario.onActivity { editor.requestFocus() }
            awaitImeReady(editor)
            block(editor)
        }
    }

    private fun openSettings() {
        shell("am start -W -a android.intent.action.VIEW -f 0x14000000 -n $SKK_PACKAGE/.SettingsActivity")
        awaitAppText("設定")
    }

    private fun openCustomizationPage(title: String) {
        clickAppText(title)
        awaitAppText(title)
    }

    private fun choosePreference(title: String, choice: String) {
        clickAppText(title)
        val option = awaitNode("選択ダイアログに項目がありません: $choice") { root ->
            // 現在値の要約にも同じ文字列があるため、開いたダイアログのリストに限定します。
            if (!isApp(root)) return@awaitNode null
            val list = descendants(root).firstOrNull {
                it.className?.toString() == "android.widget.ListView" && it.isVisibleToUser
            } ?: return@awaitNode null
            descendants(list).firstOrNull { node ->
                node.text?.toString() == choice && node.isEnabled && node.isVisibleToUser
            }
        }
        assertTrue("選択肢を選べません: $choice", clickNodeOrParent(option))
    }

    private fun saveCurrentPage() {
        clickAppText("保存")
        awaitAppText("設定")
    }

    private fun resetPages(vararg pages: String) {
        pages.forEach { page ->
            openCustomizationPage(page)
            clickAppText("標準に戻す")
            awaitAppText("この画面の設定を標準値に戻します。保存するまでは反映されません。")
            clickDialogPositive("標準に戻す")
            awaitAppText("標準値を表示しています。保存すると反映します。")
            saveCurrentPage()
        }
    }

    private fun setText(description: String, value: String, standardPreference: Boolean = false) {
        if (standardPreference) awaitAppText(description)
        fun matches(node: AccessibilityNodeInfo) = if (standardPreference)
            node.viewIdResourceName == "android:id/edit"
        else node.contentDescription?.toString() == description
        val field = awaitNode("入力欄がありません: $description") { root ->
            root.takeIf(::isApp)?.let { app -> descendants(app).firstOrNull { node ->
                node.className?.toString() == EditText::class.java.name &&
                    matches(node) && node.isEditable
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
                    matches(node) && node.text?.toString() == value
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
            root.takeIf(::isApp)?.let { findPreferenceSwitch(it, text) }
        }
        if (control.isChecked != enabled) {
            assertTrue("切替項目を更新できません: $text", clickNodeOrParent(control))
        }
        awaitNode("切替項目の値を確認できません: $text") { root ->
            root.takeIf(::isApp)?.let { findPreferenceSwitch(it, text)?.takeIf { node ->
                node.isChecked == enabled
            } }
        }
    }

    private fun findPreferenceSwitch(root: AccessibilityNodeInfo, title: String): AccessibilityNodeInfo? {
        descendants(root).firstOrNull { it.text?.toString() == title }?.let { label ->
            var row: AccessibilityNodeInfo? = label
            repeat(4) {
                descendants(row).firstOrNull { it.isCheckable }?.let { return it }
                row = row?.parent
            }
        }
        return descendants(root).firstOrNull { it.className?.toString() == Switch::class.java.name &&
            it.isCheckable && switchLabelMatches(it, title) }
    }

    private fun clickAppText(text: String) {
        val deadline = SystemClock.uptimeMillis() + UI_TIMEOUT_MS
        var scrollAction = AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
        while (SystemClock.uptimeMillis() < deadline) {
            val app = automation.windows.asSequence().mapNotNull { it.root }.firstOrNull(::isApp)
            val node = descendants(app).firstOrNull {
                val label = it.text?.toString()
                it.isEnabled && label == text
            }
            if (node != null) {
                assertTrue("操作項目を選べません: $text", clickNodeOrParent(node))
                return
            }
            descendants(app).firstOrNull { it.isScrollable }?.let { scrollable ->
                if (!scrollable.performAction(scrollAction)) {
                    scrollAction = if (scrollAction == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) {
                        AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
                    } else {
                        AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                    }
                    scrollable.performAction(scrollAction)
                }
            }
            SystemClock.sleep(POLL_MS)
        }
        throw AssertionError("操作項目がありません: $text\n" + automation.windows.joinToString { window ->
            "package=${window.root?.packageName}, nodes=${descendants(window.root).map { node ->
                "${node.viewIdResourceName}:${node.text}:${node.isScrollable}"
            }}"
        })
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
            root.takeIf(::isApp)?.findAccessibilityNodeInfosByText(text)
                ?.firstOrNull { it.text?.toString() == text }
        }
    }

    private fun enableStatusDisplay() {
        openCustomizationPage("画面キーボードとモード表示")
        val status = awaitNode("状態表示の設定がありません") { root ->
            root.takeIf(::isApp)?.let { findPreferenceSwitch(it, "入力モードを表示する") }
        }
        if (!status.isChecked) {
            assertTrue("状態表示を有効にできません", clickNodeOrParent(status))
        }
        awaitNode("状態表示が有効になりません") { root ->
            root.takeIf(::isApp)?.let { findPreferenceSwitch(it, "入力モードを表示する")
                ?.takeIf { node -> node.isChecked } }
        }
        saveCurrentPage()
    }

    private fun awaitImeReady(editor: EditText) {
        instrumentation.waitForIdleSync()
        val deadline = SystemClock.uptimeMillis() + UI_TIMEOUT_MS
        while (SystemClock.uptimeMillis() < deadline) {
            if (selectedIme() == SKK_IME && isServedEditor(editor)) return
            SystemClock.sleep(POLL_MS)
        }
        throw AssertionError("SKK と入力欄の接続が準備できません。selected=${selectedIme()}, " +
            "served=${isServedEditor(editor)}")
    }

    private fun selectedIme(): String = android.os.ParcelFileDescriptor.AutoCloseInputStream(
        automation.executeShellCommand("settings get secure default_input_method"),
    ).bufferedReader().use { it.readText().trim() }

    private fun isServedEditor(editor: EditText): Boolean {
        var served = false
        instrumentation.runOnMainSync {
            // IMM の接続開始だけでなく、SKK が現在の接続を処理できるまで待ちます。
            served = editor.hasFocus() && editor.isAttachedToWindow &&
                editor.context.getSystemService(InputMethodManager::class.java).isActive(editor) &&
                (editor as InputTestActivity.ImeConnectionProbe).imeConnectionReady
        }
        return served
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
        instrumentation.sendStringSync(value)
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

    /** 実キーボード同様に Shift/Alt の押下も入力先へ届け、ラッチした選択を検出します。 */
    private fun physicalMetaBoundary(code: Int, editor: EditText, expected: String, cursor: Int) {
        val now = SystemClock.uptimeMillis()
        instrumentation.sendKeySync(KeyEvent(now, now, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_SHIFT_LEFT, 0,
            KeyEvent.META_SHIFT_ON, KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0, InputDevice.SOURCE_KEYBOARD))
        instrumentation.sendKeySync(KeyEvent(now, now, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ALT_LEFT, 0,
            KeyEvent.META_SHIFT_ON or KeyEvent.META_ALT_ON, KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0, InputDevice.SOURCE_KEYBOARD))
        try {
            key(code, KeyEvent.META_ALT_ON or KeyEvent.META_SHIFT_ON)
            // 修飾キーを離す前に、移動完了と選択がないことを確認します。
            awaitEditorState(editor, expected, cursor)
        } finally {
            instrumentation.sendKeySync(KeyEvent(now, SystemClock.uptimeMillis(), KeyEvent.ACTION_UP,
                KeyEvent.KEYCODE_ALT_LEFT, 0, KeyEvent.META_SHIFT_ON, KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0,
                InputDevice.SOURCE_KEYBOARD))
            instrumentation.sendKeySync(KeyEvent(now, SystemClock.uptimeMillis(), KeyEvent.ACTION_UP,
                KeyEvent.KEYCODE_SHIFT_LEFT, 0, 0, KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0, InputDevice.SOURCE_KEYBOARD))
        }
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
        awaitEditorSelection(editor, expected, selection, selection)
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
        var actual = ""
        instrumentation.runOnMainSync {
            actual = "${editor.text} [${editor.selectionStart}, ${editor.selectionEnd}]"
        }
        throw AssertionError("編集結果が一致しません: expected=$expected [$start, $end], actual=$actual")
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
            }.take(100)}"
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
        const val SKK_PACKAGE = "se.haya.skk"
        const val SKK_IME = "$SKK_PACKAGE/.SkkInputMethodService"
        const val UI_TIMEOUT_MS = 10_000L
        const val POLL_MS = 25L
    }
}
