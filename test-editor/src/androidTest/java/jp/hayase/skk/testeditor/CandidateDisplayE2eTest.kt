package jp.hayase.skk.testeditor

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
import android.widget.Spinner
import android.widget.Switch
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

    @Test fun narrowLargeFontCandidateDetailKeepsImeFocusAndCommitsOnce() {
        enableStatusDisplay()
        ActivityScenario.launch<InputTestActivity>(
            android.content.Intent(instrumentation.targetContext, InputTestActivity::class.java)
                .putExtra(InputTestActivity.EXTRA_SUPPRESS_LEARNING, true),
        ).use { scenario ->
            val editor = scenario.editorStartingWith("複数行 A")
            scenario.onActivity { editor.requestFocus() }
            awaitImeReady(editor)
            key(KeyEvent.KEYCODE_J, KeyEvent.META_CTRL_ON)
            type("Tesuto   ")

            assertCandidateBounds()

            val before = editorText(editor)
            val detailButton = awaitViewId("candidate_full_detail")
            assertTrue("全文表示を開けません", clickNodeOrParent(detailButton))
            val detail = awaitViewId("candidate_detail_text")
            assertTrue("候補本文のページが空です", !detail.text.isNullOrEmpty())

            key(KeyEvent.KEYCODE_DPAD_RIGHT)
            assertEquals("全文ページ操作で候補を確定または変更しました", before, editorText(editor))
            assertTrue("全文ページ操作で入力先のフォーカスを失いました", editorHasFocus(editor))

            key(KeyEvent.KEYCODE_ENTER)
            awaitCondition("候補が確定しません") {
                editorText(editor) == before && !hasViewId("candidate_detail_text")
            }
            assertEquals("候補を一度だけ確定できません", before, editorText(editor))
            assertFalse("確定後も全文表示が残っています", hasViewId("candidate_detail_text"))
            assertTrue("候補確定後に入力先のフォーカスを失いました", editorHasFocus(editor))
        }

        // 画面下端の入力欄でも、長い状態表示によって入力欄やアプリ領域が隠れないことを確認します。
        ActivityScenario.launch<InputTestActivity>(
            android.content.Intent(instrumentation.targetContext, InputTestActivity::class.java)
                .putExtra(InputTestActivity.EXTRA_SUPPRESS_LEARNING, true),
        ).use { scenario ->
            lateinit var editor: EditText
            lateinit var viewport: ScrollView
            scenario.onActivity { activity ->
                activity.window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
                viewport = descendants(activity.window.decorView).filterIsInstance<ScrollView>().first()
                editor = descendants(activity.window.decorView).filterIsInstance<EditText>().last()
                editor.requestFocus()
                viewport.fullScroll(View.FOCUS_DOWN)
            }
            awaitImeReady(editor)
            key(KeyEvent.KEYCODE_J, KeyEvent.META_CTRL_ON)
            type("/n09-display-unmatched ")
            type("a".repeat(300))
            val status = awaitViewId("input_status")
            assertTrue("長い登録本文が通常表示へ展開されました",
                status.text?.toString().orEmpty().length < 1_000)
            assertCandidateBounds()
            awaitCondition("候補表示が画面下端の入力欄またはアプリ領域を覆っています") {
                val candidateTop = Rect().also(awaitViewId("candidate_status_container")::getBoundsInScreen).top
                var visible = false
                instrumentation.runOnMainSync {
                    val editorRect = Rect()
                    val viewportRect = Rect()
                    val editorVisible = editor.getGlobalVisibleRect(editorRect)
                    val viewportVisible = viewport.getGlobalVisibleRect(viewportRect)
                    val line = editor.layout?.getLineForOffset(editor.selectionStart)
                    val caretBottom = line?.let { editorRect.top + editor.totalPaddingTop +
                        editor.layout.getLineBottom(it) - editor.scrollY }
                    visible = editorVisible && viewportVisible &&
                        !editorRect.isEmpty && !viewportRect.isEmpty &&
                        editorRect.height() >= editor.height - 2 &&
                        editorRect.bottom <= candidateTop + 2 &&
                        caretBottom != null && caretBottom <= candidateTop + 2 &&
                        viewportRect.bottom <= candidateTop + 2
                }
                visible
            }
        }
    }

    @Test fun singleCandidateAnnotationIsInlineAndNeverCommitted() {
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
            type("Nihon ")
            awaitCondition("単独候補の注釈が候補の近くにありません") {
                awaitViewId("inline_candidate_annotation").text?.toString() == "国名・限定試験辞書"
            }
            assertEquals("注釈がアプリの本文に混入しました", "日本", editorText(editor))
            assertTrue("注釈表示が入力欄のフォーカスを奪いました", editorHasFocus(editor))
            val status = awaitViewId("input_status").text?.toString().orEmpty()
            assertFalse("単独候補がステータスに重複しています", status.contains("日本") || status.contains("国名"))
            assertFalse("単独候補の情報ボタンが残っています", hasViewId("candidate_full_detail"))
            val annotation = Rect().also(awaitViewId("inline_candidate_annotation")::getBoundsInScreen)
            var caretLine = Rect()
            instrumentation.runOnMainSync {
                val location = IntArray(2).also(editor::getLocationOnScreen)
                val line = editor.layout.getLineForOffset(editor.selectionStart)
                val start = android.view.inputmethod.BaseInputConnection.getComposingSpanStart(editor.text)
                val left = editor.layout.getPrimaryHorizontal(start.coerceAtLeast(0)).toInt()
                val right = editor.layout.getPrimaryHorizontal(editor.selectionStart).toInt()
                caretLine = Rect(location[0] + editor.totalPaddingLeft + minOf(left, right) - editor.scrollX,
                    location[1] + editor.totalPaddingTop + editor.layout.getLineTop(line) - editor.scrollY,
                    location[0] + editor.totalPaddingLeft + maxOf(left, right) - editor.scrollX,
                    location[1] + editor.totalPaddingTop + editor.layout.getLineBottom(line) - editor.scrollY)
            }
            assertFalse("注釈が変換中の行を隠しています", Rect.intersects(annotation, caretLine))
            assertTrue("注釈が候補から離れています", minOf(kotlin.math.abs(annotation.bottom - caretLine.top),
                kotlin.math.abs(annotation.top - caretLine.bottom)) <= caretLine.height() * 2)
            instrumentation.waitForIdleSync()
            SystemClock.sleep(300)
            automation.takeScreenshot()?.let { bitmap ->
                java.io.File(instrumentation.targetContext.getExternalFilesDir(null), "candidate-inline.png")
                    .outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                bitmap.recycle()
            }
            UiDevice.getInstance(instrumentation).click(annotation.centerX(), annotation.centerY())
            assertTrue("注釈へのタッチが入力フォーカスを奪いました", editorHasFocus(editor))
            key(KeyEvent.KEYCODE_SPACE)
            awaitCondition("次の単独候補の注釈へ更新されません") {
                awaitViewId("inline_candidate_annotation").text?.toString() == "本数・限定試験辞書"
            }
            assertEquals("二本", editorText(editor))
            key(KeyEvent.KEYCODE_G, KeyEvent.META_CTRL_ON)
            awaitCondition("取消後も注釈が残っています") { !hasViewId("inline_candidate_annotation") }
            key(KeyEvent.KEYCODE_SPACE)
            key(KeyEvent.KEYCODE_ENTER)
            awaitCondition("確定後も注釈が残っています") { !hasViewId("inline_candidate_annotation") }
            assertEquals("日本", editorText(editor))
            type("Nihon ")
            awaitViewId("inline_candidate_annotation")
            scenario.onActivity { password.requestFocus() }
            awaitCondition("別の入力欄へ注釈が持ち越されました") { !hasViewId("inline_candidate_annotation") }
            assertEquals("フォーカス変更で注釈が本文へ残りました", "日本日本", editorText(editor))
            assertEquals("", editorText(password))
        }
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
            type("Tesuto   ")
            fun menuRow(label: Char, number: Int): AccessibilityNodeInfo =
                awaitNode("候補一覧の $label が表示されません") { root ->
                    descendants(root).firstOrNull { it.contentDescription?.toString()?.startsWith("$label: 候補$number") == true }
                }
            menuRow('a', 3)
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
            val configuration = instrumentation.targetContext.resources.configuration
            if (configuration.screenWidthDp >= 280 * configuration.fontScale) {
                assertTrue("横幅があるのに候補が横に並びません", pageSize >= 2)
                val first = Rect().also(rows[0]::getBoundsInScreen)
                val second = Rect().also(rows[1]::getBoundsInScreen)
                assertEquals("最初の二候補が同じ行にありません", first.top, second.top)
                assertTrue("二候補の横位置が重なっています", second.left >= first.right)
            }
            assertTrue("候補一覧が三行以上を占有しています", rows.map {
                Rect().also(it::getBoundsInScreen).top
            }.distinct().size <= 2)
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
                awaitViewId("input_status").text?.toString().orEmpty().contains("3/10 候補3"))
            assertEquals("候補3", editorText(editor))
            // アクセシビリティ通知の後に描画が反映されるため、撮影前にフレームを待ちます。
            instrumentation.waitForIdleSync()
            SystemClock.sleep(600)
            automation.takeScreenshot()?.let { screenshot ->
                java.io.File(instrumentation.targetContext.getExternalFilesDir(null), "candidate-menu-api35.png")
                    .outputStream().use { screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                screenshot.recycle()
            }
            var pageStart = 3
            while (pageStart + pageSize <= 10) {
                key(KeyEvent.KEYCODE_SPACE)
                pageStart += pageSize
                menuRow('a', pageStart)
            }
            key(KeyEvent.KEYCODE_SPACE)
            awaitCondition("末尾ページの次が登録になりません") {
                awaitViewId("input_status").text?.toString().orEmpty().contains("単語登録")
            }
            key(KeyEvent.KEYCODE_G, KeyEvent.META_CTRL_ON)
            menuRow('a', pageStart)
            type("a")
            assertEquals("候補$pageStart", editorText(editor))
        }
    }

    @Test fun keyboardOnlyCustomizationNavigationSaveDiscardAndReset() {
        openSettings()
        focusAndPressEnter("設定への移動") { root ->
            descendants(root).firstOrNull { it.className?.toString() == "android.widget.Button" &&
                it.text?.toString() == "入力規則・句読点・候補を設定する" }
        }
        awaitAppText("入力規則・句読点・候補")
        awaitAppText("保存済み設定を表示しています")

        // 既存の有効なカスタム割当てが候補ラベルと競合しないよう、退避済み設定から
        // 試験用の標準値へ物理キーだけで移してから変更・保存を確認します。
        resetCustomizationWithKeyboard()

        val original = selectedCandidateLabels()
        assertEquals("標準候補ラベルを読み取れません", "asdfjkl", original)
        val replacement = if (original == "asdfjkl") "1234567" else "asdfjkl"
        setCandidateLabelsWithKeyboard(replacement)
        focusAndPressEnter("保存") { root -> button(root, "保存") }
        awaitAppText("入力設定を保存しました。次の入力欄から反映します。")

        // 保存した値を別画面で読み直し、次の未保存変更が破棄されることを確認します。
        reopenCustomizationWithKeyboard()
        assertEquals("保存した候補ラベルを再読込できません", replacement, selectedCandidateLabels())
        setCandidateLabelsWithKeyboard(original)
        focusAndPressEnter("変更の破棄") { root -> button(root, "変更を破棄して閉じる") }
        awaitAppText("保存していない変更を破棄して閉じますか。")
        focusAndPressEnter("破棄の確認") { root -> button(root, "破棄して閉じる") }
        awaitAppText("SKK の設定")

        reopenCustomizationWithKeyboard()
        assertEquals("破棄した値が保存されてしまいました", replacement, selectedCandidateLabels())
        resetCustomizationWithKeyboard()
        assertEquals("標準候補ラベルへ戻りません", "asdfjkl", selectedCandidateLabels())
    }

    private fun resetCustomizationWithKeyboard() {
        focusAndPressEnter("標準設定への復旧") { root -> button(root, "入力設定を標準に戻す") }
        awaitAppText("ローマ字規則・句読点・候補設定・Emacs 編集キー・各コマンドのキー設定を標準に戻して保存します。個人辞書と学習の設定は変更しません。")
        focusAndPressEnter("標準設定への復旧確認") { root -> button(root, "標準に戻す") }
        awaitAppText("入力設定を標準に戻しました。次の入力欄から反映します。")
    }

    private fun reopenCustomizationWithKeyboard() {
        openSettings()
        focusAndPressEnter("設定への再移動") { root ->
            button(root, "入力規則・句読点・候補を設定する")
        }
        awaitAppText("保存済み設定を表示しています")
    }

    private fun openSettings() {
        shell("am start -W -f 0x14000000 -n $SKK_PACKAGE/.SettingsActivity")
        awaitAppText("SKK の設定")
    }

    private fun setCandidateLabelsWithKeyboard(expected: String) {
        val target = { root: AccessibilityNodeInfo ->
            descendants(root).firstOrNull { it.className?.toString() == Spinner::class.java.name &&
                it.contentDescription?.toString() == "候補の選択キー（標準: asdfjkl）" }
        }
        focusWithTab("候補選択キー", target)
        settingsKey(KeyEvent.KEYCODE_DPAD_CENTER)
        awaitNode("候補ラベルの選択肢が開きません") { root ->
            descendants(root).firstOrNull { it.text?.toString() == expected }
        }
        val other = if (expected == "asdfjkl") KeyEvent.KEYCODE_DPAD_UP else KeyEvent.KEYCODE_DPAD_DOWN
        settingsKey(other)
        settingsKey(KeyEvent.KEYCODE_ENTER)
        awaitCondition("候補ラベルの値が変わりません: $expected") { selectedCandidateLabels() == expected }
    }

    private fun selectedCandidateLabels(): String {
        val spinner = awaitNode("候補ラベルの選択欄がありません") { root ->
            root.takeIf { it.packageName?.toString() == SKK_PACKAGE }?.let { app ->
                descendants(app).firstOrNull { it.className?.toString() == Spinner::class.java.name &&
                    it.contentDescription?.toString() == "候補の選択キー（標準: asdfjkl）" }
            }
        }
        return descendants(spinner).mapNotNull { it.text?.toString() }
            .firstOrNull { it == "asdfjkl" || it == "1234567" }
            ?: throw AssertionError("候補ラベルの現在値が読めません")
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
        val control = awaitNode("状態表示の設定がありません") { root ->
            root.takeIf { it.packageName?.toString() == SKK_PACKAGE }?.let { app ->
                descendants(app).firstOrNull { node ->
                    node.className?.toString() == Switch::class.java.name && node.isCheckable &&
                        node.text?.toString()?.removeSuffix(" ON")?.removeSuffix(" OFF") ==
                        "入力モード・候補を表示する"
                }
            }
        }
        if (!control.isChecked) assertTrue("状態表示を有効にできません", clickNodeOrParent(control))
    }

    private fun awaitImeReady(editor: EditText) {
        awaitCondition("入力欄または候補表示が準備できません") {
            var served = false
            instrumentation.runOnMainSync {
                served = editor.hasFocus() && editor.isAttachedToWindow &&
                    editor.context.getSystemService(InputMethodManager::class.java).isActive(editor)
            }
            served && hasViewId("input_status")
        }
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
        const val SKK_PACKAGE = "jp.hayase.skk"
        const val UI_TIMEOUT_MS = 10_000L
    }
}
