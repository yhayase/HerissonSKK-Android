package se.haya.skk.testeditor

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.UiAutomation
import android.graphics.Rect
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.ScrollView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** 狭い画面と大きい文字で、候補領域の寸法と全文表示のキー配送を確認します。 */
@RunWith(AndroidJUnit4::class)
class CandidateDisplayE2eTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val automation: UiAutomation = instrumentation.uiAutomation.apply {
        serviceInfo = serviceInfo.apply {
            flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
        }
    }

    @Test fun physicalPopupReservesNoBottomSpaceAndTapCommitsOnce() {
        enableStatusDisplay()
        ActivityScenario.launch<InputTestActivity>(
            android.content.Intent(instrumentation.targetContext, InputTestActivity::class.java)
                .putExtra(InputTestActivity.EXTRA_SUPPRESS_LEARNING, true),
        ).use { scenario ->
            val editor = scenario.editorStartingWith("複数行 A")
            var initialHeight = 0
            lateinit var viewport: ScrollView
            scenario.onActivity {
                editor.requestFocus()
                viewport = descendants(it.window.decorView).filterIsInstance<ScrollView>().first()
            }
            awaitImeReady(editor)
            instrumentation.runOnMainSync { initialHeight = viewport.height }
            key(KeyEvent.KEYCODE_J, KeyEvent.META_CTRL_ON)
            type("Tesuto   ")
            val tile = awaitCandidate("a: 候補")
            val selected = Regex("a: (候補[0-9]+)").find(tile.contentDescription.toString())!!.groupValues[1]
            awaitCondition("候補一覧と入力先が一致しません") { editorText(editor) == selected }
            assertCandidateBounds()
            instrumentation.runOnMainSync {
                assertEquals("ポップアップで入力先が縮みました", initialHeight, viewport.height)
            }
            assertTrue(editorHasFocus(editor))
            assertFalse(hasViewId("candidate_full_detail"))
            UiDevice.getInstance(instrumentation).waitForIdle()
            val bounds = Rect().also(awaitCandidate("a: $selected")::getBoundsInScreen)
            UiDevice.getInstance(instrumentation).click(bounds.centerX(), bounds.centerY())
            awaitCondition("タップで候補を確定できません") {
                editorText(editor) == selected && !hasViewId("candidate_status_container")
            }
            type("x")
            awaitCondition("確定後の文字が同じ入力欄へ届きません") { editorText(editor) == selected + "x" }
            assertTrue(editorHasFocus(editor))
        }
    }

    @Test fun physicalCandidateLongPressShowsAnnotationWithoutCommittingOrStealingFocus() {
        reopenCustomizationWithKeyboard()
        resetCustomizationWithKeyboard()
        enableStatusDisplay()
        ActivityScenario.launch<InputTestActivity>(
            android.content.Intent(instrumentation.targetContext, InputTestActivity::class.java)
                .putExtra(InputTestActivity.EXTRA_SUPPRESS_LEARNING, true),
        ).use { scenario ->
            val editor = scenario.editorStartingWith("複数行 A")
            val password = scenario.editorStartingWith("パスワード")
            scenario.onActivity { editor.requestFocus() }
            awaitImeReady(editor)
            key(KeyEvent.KEYCODE_J, KeyEvent.META_CTRL_ON)
            type("Tesuto   ")
            UiDevice.getInstance(instrumentation).waitForIdle()
            val tile = awaitCandidate("a: 候補")
            val selected = Regex("a: (候補[0-9]+)")
                .find(tile.contentDescription.toString())!!.groupValues[1]
            assertTrue(tile.contentDescription.toString().contains("注釈:"))
            val box = Rect().also(tile::getBoundsInScreen)
            UiDevice.getInstance(instrumentation).swipe(box.centerX(), box.centerY(),
                box.centerX(), box.centerY(), 140)
            awaitViewId("candidate_detail_close")
            assertEquals(selected, editorText(editor))
            instrumentation.runOnMainSync {
                assertTrue("長押しで未確定状態が失われました",
                    android.view.inputmethod.BaseInputConnection.getComposingSpanStart(editor.text) >= 0)
            }
            assertTrue(editorHasFocus(editor))
            assertTrue(clickNodeOrParent(awaitViewId("candidate_detail_close")))
            key(KeyEvent.KEYCODE_J, KeyEvent.META_CTRL_ON)
            awaitCondition("確定後も候補窓が残っています") { !hasViewId("candidate_status_container") }
            assertEquals(selected, editorText(editor))
            type("Tesuto   ")
            awaitCandidate("a: 候補")
            scenario.onActivity { password.requestFocus() }
            awaitCondition("保護欄へ候補を持ち越しました") { !hasViewId("candidate_status_container") }
            assertEquals("", editorText(password))
        }
    }

    private fun awaitCandidate(prefix: String): AccessibilityNodeInfo = awaitNode("候補がありません: $prefix") { root ->
        descendants(root).firstOrNull { it.isVisibleToUser &&
            it.contentDescription?.toString()?.startsWith(prefix) == true }
    }

    @Test fun menuPageShowsFirstAndLastLabelsWithoutScrolling() {
        reopenCustomizationWithKeyboard()
        resetCustomizationWithKeyboard()
        enableStatusDisplay()
        ActivityScenario.launch<InputTestActivity>(
            android.content.Intent(instrumentation.targetContext, InputTestActivity::class.java)
                .putExtra(InputTestActivity.EXTRA_SUPPRESS_LEARNING, true),
        ).use { scenario ->
            val editor = scenario.editorStartingWith("複数行 A")
            scenario.onActivity { editor.requestFocus() }
            awaitImeReady(editor)
            key(KeyEvent.KEYCODE_J, KeyEvent.META_CTRL_ON)
            type("Tesuto ")
            awaitCondition("最初のインライン候補が入力先へ表示されません") {
                editorText(editor).matches(Regex("候補[0-9]+"))
            }
            val firstInline = editorText(editor)
            assertFalse("インライン候補を物理候補一覧へ表示しました",
                hasViewId("candidate_status_container"))
            key(KeyEvent.KEYCODE_SPACE)
            awaitCondition("次のインライン候補へ進みません") {
                editorText(editor).matches(Regex("候補[0-9]+")) && editorText(editor) != firstInline
            }
            val secondInline = editorText(editor)
            assertFalse("2番目のインライン候補を物理候補一覧へ表示しました",
                hasViewId("candidate_status_container"))
            key(KeyEvent.KEYCODE_SPACE)
            fun pageHead(): String = Regex("a: (候補[0-9]+)")
                .find(awaitCandidate("a: 候補").contentDescription.toString())!!.groupValues[1]
            val initialHead = pageHead()
            // ノード生成直後は IME の高さが更新前の場合があるため、全行の配置完了を待ちます。
            awaitCondition("候補一覧の全行がスクロールなしで可視になりません") {
                val node = awaitViewId("candidate_status_container")
                val frame = Rect().also(node::getBoundsInScreen)
                val currentRows = descendants(node).filter {
                    it.contentDescription?.toString()?.matches(Regex("[asdfjkl]: 候補[0-9]+.*")) == true
                }
                currentRows.isNotEmpty() && currentRows.all {
                    val bounds = Rect().also(it::getBoundsInScreen)
                    val fullBounds = Rect().also(it::getBoundsInParent)
                    it.isVisibleToUser && !bounds.isEmpty && frame.contains(bounds) &&
                        bounds.height() == fullBounds.height()
                }
            }
            val containerNode = awaitViewId("candidate_status_container")
            val rows = descendants(containerNode).filter {
                it.contentDescription?.toString()?.matches(Regex("[asdfjkl]: 候補[0-9]+.*")) == true
            }
            assertTrue("候補ページが空です", rows.isNotEmpty())
            val pageSize = rows.size
            assertTrue("候補ラベルの上限を超えています", pageSize <= 7)
            if (rows.size >= 2) {
                val first = Rect().also(rows[0]::getBoundsInScreen)
                val second = Rect().also(rows[1]::getBoundsInScreen)
                assertTrue("物理候補が縦に並んでいません", second.top >= first.bottom)
            }
            assertEquals("候補ラベルがページ内の順序と一致しません", "asdfjkl".take(pageSize),
                rows.joinToString("") { it.contentDescription.toString().take(1) })
            val container = Rect().also(containerNode::getBoundsInScreen)
            rows.forEach { row ->
                val bounds = Rect().also(row::getBoundsInScreen)
                assertTrue("候補行が可視ではありません: ${row.text}", row.isVisibleToUser && !bounds.isEmpty)
                assertTrue("スクロールなしで候補行全体を表示できません: $bounds / $container", container.contains(bounds))
                val fullBounds = Rect().also(row::getBoundsInParent)
                assertEquals("候補行が親の表示領域で縦に切れています: ${row.text}",
                    fullBounds.height(), bounds.height())
            }
            rows.forEach { tile ->
                assertTrue("候補タイルの読み上げに注釈がありません", tile.contentDescription.toString().contains("注釈:"))
            }
            assertCandidateBounds()
            assertFalse("一覧に単独候補の表示が重複しています",
                hasViewId("inline_candidate_annotation"))
            assertEquals(initialHead, editorText(editor))
            val firstPageWords = rows.map {
                Regex("候補[0-9]+").find(it.contentDescription.toString())!!.value
            }
            assertTrue("インライン候補が候補メニューへ再表示されました",
                firstPageWords.none { it == firstInline || it == secondInline })
            key(KeyEvent.KEYCODE_SPACE)
            awaitCondition("次ページへ進みません") { pageHead() != initialHead }
            val nextPageWords = descendants(awaitViewId("candidate_status_container")).mapNotNull {
                Regex("^[asdfjkl]: (候補[0-9]+)").find(it.contentDescription?.toString().orEmpty())
                    ?.groupValues?.get(1)
            }
            assertTrue("隣接する候補ページが重複しています",
                firstPageWords.toSet().intersect(nextPageWords.toSet()).isEmpty())
            type("x")
            awaitCondition("前ページで元の候補ページへ戻りません") { pageHead() == initialHead }
            val restoredPageWords = descendants(awaitViewId("candidate_status_container")).mapNotNull {
                Regex("^[asdfjkl]: (候補[0-9]+)").find(it.contentDescription?.toString().orEmpty())
                    ?.groupValues?.get(1)
            }
            assertEquals("前ページで同じ候補境界を復元しません", firstPageWords, restoredPageWords)
            // アクセシビリティ通知の後に描画が反映されるため、撮影前にフレームを待ちます。
            instrumentation.waitForIdleSync()
            SystemClock.sleep(600)
            automation.takeScreenshot()?.let { screenshot ->
                java.io.File(instrumentation.targetContext.getExternalFilesDir(null), "candidate-menu-api35.png")
                    .outputStream().use { screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                screenshot.recycle()
            }
            val seen = mutableSetOf<String>()
            var lastHead = initialHead
            var registered = false
            for (page in 0 until 10) {
                val currentRows = descendants(awaitViewId("candidate_status_container")).filter {
                    it.contentDescription?.toString()?.matches(Regex("[asdfjkl]: 候補[0-9]+.*")) == true
                }
                currentRows.forEach { row ->
                    val word = Regex("候補[0-9]+").find(row.contentDescription.toString())!!.value
                    assertTrue("ページ送りで同じ候補を再表示しました: $word", seen.add(word))
                }
                lastHead = pageHead()
                key(KeyEvent.KEYCODE_SPACE)
                awaitCondition("次ページまたは登録へ進みません") {
                    val roots = automation.windows.mapNotNull { it.root }
                    registered = roots.any { root -> root.findAccessibilityNodeInfosByText("単語登録").isNotEmpty() }
                    registered || roots.flatMap { descendants(it) }.any {
                        it.contentDescription?.toString()?.startsWith("a: 候補") == true &&
                            !it.contentDescription.toString().startsWith("a: $lastHead、")
                    }
                }
                if (registered) break
            }
            assertTrue("末尾ページの次が登録になりません", registered)
            assertEquals("10候補中、インライン2候補以外を一覧で表示します", 8, seen.size)
            key(KeyEvent.KEYCODE_G, KeyEvent.META_CTRL_ON)
            assertEquals(lastHead, pageHead())
            type("a")
            awaitCondition("ラベルでページ先頭候補を確定できません") {
                editorText(editor) == lastHead && !hasViewId("candidate_status_container")
            }
        }
    }

    @Test fun keyboardOnlyCustomizationNavigationSaveDiscardAndReset() {
        openSettings()
        focusAndPressEnter("設定への移動") { root ->
            preferenceRow(root, "候補の表示と選び方")
        }
        awaitAppText("候補の表示と選び方")

        // 既存の有効なカスタム割当てが候補ラベルと競合しないよう、退避済み設定から
        // 試験用の標準値へ物理キーだけで移してから変更・保存を確認します。
        resetCustomizationWithKeyboard()
        reopenCustomizationWithKeyboard()

        val original = selectedCandidateLabels()
        assertEquals("標準候補ラベルを読み取れません", "asdfjkl", original)
        val replacement = if (original == "asdfjkl") "1234567" else "asdfjkl"
        setCandidateLabelsWithKeyboard(replacement)
        focusAndPressEnter("保存") { root -> button(root, "保存") }
        awaitAppText("設定")

        // 保存した値を別画面で読み直し、次の未保存変更が破棄されることを確認します。
        reopenCustomizationWithKeyboard()
        assertEquals("保存した候補ラベルを再読込できません", replacement, selectedCandidateLabels())
        setCandidateLabelsWithKeyboard(original)
        focusAndPressEnter("変更の破棄") { root ->
            descendants(root).firstOrNull { it.contentDescription?.toString() == "戻る" }
        }
        awaitAppText("変更を保存しますか？")
        focusAndPressEnter("破棄の確認") { root -> button(root, "破棄") }
        awaitAppText("設定")

        reopenCustomizationWithKeyboard()
        assertEquals("破棄した値が保存されてしまいました", replacement, selectedCandidateLabels())
        resetCustomizationWithKeyboard()
        reopenCustomizationWithKeyboard()
        assertEquals("標準候補ラベルへ戻りません", "asdfjkl", selectedCandidateLabels())
    }

    private fun resetCustomizationWithKeyboard() {
        focusAndPressEnter("標準設定への復旧") { root -> button(root, "標準に戻す") }
        awaitAppText("この画面の設定を標準値に戻します。保存するまでは反映されません。")
        focusAndPressEnter("標準設定への復旧確認") { root -> button(root, "標準に戻す") }
        awaitAppText("標準値を表示しています。保存すると反映します。")
        focusAndPressEnter("標準設定の保存") { root -> button(root, "保存") }
        awaitAppText("設定")
    }

    private fun reopenCustomizationWithKeyboard() {
        openSettings()
        focusAndPressEnter("設定への再移動") { root ->
            preferenceRow(root, "候補の表示と選び方")
        }
        awaitAppText("候補の表示と選び方")
    }

    private fun openSettings() {
        shell("am start -W -a android.intent.action.VIEW -f 0x14000000 -n $SKK_PACKAGE/.SettingsActivity")
        awaitAppText("設定")
    }

    private fun setCandidateLabelsWithKeyboard(expected: String) {
        val target = { root: AccessibilityNodeInfo ->
            preferenceRow(root, "候補の選択キー")
        }
        focusWithTab("候補選択キー", target)
        settingsKey(KeyEvent.KEYCODE_ENTER)
        focusAndPressEnter("候補ラベル $expected") { root ->
            descendants(root).firstOrNull { it.text?.toString() == expected && it.isEnabled }
        }
        awaitCondition("候補ラベルの値が変わりません: $expected") { selectedCandidateLabels() == expected }
    }

    private fun selectedCandidateLabels(): String {
        val row = awaitNode("候補ラベルの選択欄がありません") { root ->
            root.takeIf { it.packageName?.toString() == SKK_PACKAGE }?.let { app ->
                preferenceRow(app, "候補の選択キー")
            }
        }
        return descendants(row).mapNotNull { it.text?.toString() }
            .firstOrNull { it == "asdfjkl" || it == "1234567" }
            ?: throw AssertionError("候補ラベルの現在値が読めません")
    }

    private fun preferenceRow(root: AccessibilityNodeInfo, title: String): AccessibilityNodeInfo? {
        val label = descendants(root).firstOrNull { it.text?.toString() == title } ?: return null
        var current: AccessibilityNodeInfo? = label
        repeat(5) {
            current?.takeIf { it.isClickable }?.let { return it }
            current = current?.parent
        }
        return null
    }

    private fun button(root: AccessibilityNodeInfo, text: String): AccessibilityNodeInfo? =
        descendants(root).firstOrNull {
            it.className?.toString() == "android.widget.Button" && it.text?.toString() == text
        }

    private fun focusAndPressEnter(
        description: String,
        target: (AccessibilityNodeInfo) -> AccessibilityNodeInfo?,
    ) {
        focusWithTab(description, target)
        settingsKey(KeyEvent.KEYCODE_ENTER)
    }

    private fun focusWithTab(
        description: String,
        target: (AccessibilityNodeInfo) -> AccessibilityNodeInfo?,
    ) {
        repeat(96) {
            val focused = automation.windows.asSequence().mapNotNull { it.root }
                .filter { it.packageName?.toString() == SKK_PACKAGE }
                .mapNotNull(target).any { node -> node.isFocused || descendants(node).any { it.isFocused } }
            if (focused) return
            settingsKey(KeyEvent.KEYCODE_TAB)
        }
        throw AssertionError("物理キーボードの Tab で移動できません: $description")
    }

    private fun awaitAppText(value: String) {
        awaitNode("画面の状態が更新されません: $value") { root ->
            root.takeIf { it.packageName?.toString() == SKK_PACKAGE }
                ?.findAccessibilityNodeInfosByText(value)?.firstOrNull { it.text?.toString() == value }
        }
    }

    private fun enableStatusDisplay() {
        openSettings()
        val displayPage = awaitNode("表示設定への入口がありません") { root ->
            root.takeIf { it.packageName?.toString() == SKK_PACKAGE }
                ?.let { preferenceRow(it, "画面キーボードとモード表示") }
        }
        assertTrue("表示設定を開けません", clickNodeOrParent(displayPage))
        awaitAppText("画面キーボードとモード表示")
        val control = awaitNode("状態表示の設定がありません") { root ->
            root.takeIf { it.packageName?.toString() == SKK_PACKAGE }
                ?.let { findPreferenceSwitch(it, "入力モードを表示する") }
        }
        if (!control.isChecked) assertTrue("状態表示を有効にできません", clickNodeOrParent(control))
        val save = awaitNode("表示設定の保存操作がありません") { root ->
            root.takeIf { it.packageName?.toString() == SKK_PACKAGE }?.let { button(it, "保存") }
        }
        assertTrue("表示設定を保存できません", clickNodeOrParent(save))
        awaitAppText("設定")
    }

    private fun findPreferenceSwitch(root: AccessibilityNodeInfo, title: String): AccessibilityNodeInfo? {
        val label = descendants(root).firstOrNull { it.text?.toString() == title } ?: return null
        var row: AccessibilityNodeInfo? = label
        repeat(4) {
            descendants(row).firstOrNull { it.isCheckable }?.let { return it }
            row = row?.parent
        }
        return null
    }

    private fun awaitImeReady(editor: EditText) {
        awaitCondition("入力欄または候補表示が準備できません") {
            var served = false
            instrumentation.runOnMainSync {
                served = editor.hasFocus() && editor.isAttachedToWindow &&
                    editor.context.getSystemService(InputMethodManager::class.java).isActive(editor)
            }
            served
        }
        UiDevice.getInstance(instrumentation).waitForIdle()
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

    private fun type(value: String) {
        KeyCharacterMap.load(KeyCharacterMap.VIRTUAL_KEYBOARD).getEvents(value.toCharArray())!!
            .forEach(instrumentation::sendKeySync)
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

    /** 別アプリの公開設定画面へは UiAutomation の権限で物理キーを配送します。 */
    private fun settingsKey(code: Int) {
        val now = SystemClock.uptimeMillis()
        val down = KeyEvent(now, now, KeyEvent.ACTION_DOWN, code, 0, 0,
            KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0, InputDevice.SOURCE_KEYBOARD)
        val up = KeyEvent(now, SystemClock.uptimeMillis(), KeyEvent.ACTION_UP, code, 0, 0,
            KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0, InputDevice.SOURCE_KEYBOARD)
        assertTrue("設定画面へキー押下を配送できません: $code", automation.injectInputEvent(down, true))
        assertTrue("設定画面へキー解放を配送できません: $code", automation.injectInputEvent(up, true))
    }

    private fun awaitViewId(name: String): AccessibilityNodeInfo = awaitNode("表示がありません: $name") { root ->
        root.findAccessibilityNodeInfosByViewId("$SKK_PACKAGE:id/$name").firstOrNull()
    }

    private fun hasViewId(name: String): Boolean = automation.windows.asSequence().mapNotNull { it.root }
        .any { it.findAccessibilityNodeInfosByViewId("$SKK_PACKAGE:id/$name").isNotEmpty() }

    private fun assertCandidateBounds() {
        val container = awaitViewId("candidate_status_container")
        val bounds = Rect().also(container::getBoundsInScreen)
        val display = UiDevice.getInstance(instrumentation)
        assertTrue("候補領域が画面幅を超えています: $bounds", bounds.width() <= display.displayWidth)
        assertTrue("候補領域が画面高の40%を超えています: $bounds / ${display.displayHeight}",
            bounds.height() <= (display.displayHeight * .4f).toInt() + 2)
    }

    private fun editorText(editor: EditText): String {
        var result = ""
        instrumentation.runOnMainSync { result = editor.text.toString() }
        return result
    }

    private fun editorHasFocus(editor: EditText): Boolean {
        var result = false
        instrumentation.runOnMainSync { result = editor.hasFocus() }
        return result
    }

    private fun awaitCondition(message: String, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + UI_TIMEOUT_MS
        while (SystemClock.uptimeMillis() < deadline) {
            if (condition()) return
            SystemClock.sleep(25)
        }
        throw AssertionError(message)
    }

    private fun awaitNode(message: String, find: (AccessibilityNodeInfo) -> AccessibilityNodeInfo?): AccessibilityNodeInfo {
        val deadline = SystemClock.uptimeMillis() + UI_TIMEOUT_MS
        while (SystemClock.uptimeMillis() < deadline) {
            automation.windows.asSequence().mapNotNull { it.root }.mapNotNull(find).firstOrNull()?.let { return it }
            SystemClock.sleep(25)
        }
        throw AssertionError(message)
    }

    private fun clickNodeOrParent(node: AccessibilityNodeInfo): Boolean {
        var current: AccessibilityNodeInfo? = node
        while (current != null) {
            if (current.isEnabled && current.isClickable && current.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
            current = current.parent
        }
        return false
    }

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
        android.os.ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(command))
            .bufferedReader().use { it.readText() }
    }

    private companion object {
        const val SKK_PACKAGE = "se.haya.skk"
        const val UI_TIMEOUT_MS = 10_000L
    }
}
