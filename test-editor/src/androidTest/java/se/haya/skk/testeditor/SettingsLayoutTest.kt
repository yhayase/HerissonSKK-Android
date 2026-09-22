package se.haya.skk.testeditor

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.UiAutomation
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiScrollable
import androidx.test.uiautomator.UiSelector
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** 実機の標準 Preference 画面で、設定ページの固定操作部と表示文言を確認します。 */
@RunWith(AndroidJUnit4::class)
class SettingsLayoutTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val automation: UiAutomation = instrumentation.uiAutomation.apply {
        serviceInfo = serviceInfo.apply {
            flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
        }
    }
    private val device: UiDevice = UiDevice.getInstance(instrumentation)

    @Test fun romajiAndPunctuationPagesUseNativeRowsWithoutCategorySpinner() {
        openSettings()
        openPage("ローマ字の打ち方")
        awaitText("ローマ字規則")
        val romajiChrome = assertChrome("ローマ字の打ち方")
        assertVisibleText("入力方式")
        assertNoCategorySpinner()
        assertChrome("ローマ字の打ち方", romajiChrome)
        capture("settings-romaji")
        backToSettings()

        openPage("句読点と記号")
        awaitText("句点")
        val punctuationChrome = assertChrome("句読点と記号")
        assertVisibleText("かな入力で使う記号")
        assertVisibleText("句点")
        assertVisibleText("読点")
        assertNoCategorySpinner()
        assertChrome("句読点と記号", punctuationChrome)
        capture("settings-punctuation")
        backToSettings()
    }

    @Test fun longKeyBindingsPageActuallyScrollsWhileChromeAndContentColumnStayFixed() {
        openSettings()
        openPage("キー操作とEmacs編集")
        val anchorText = "Emacs キーバインド"
        val anchor = awaitText(anchorText)
        val viewport = requireNotNull(contentAncestor(anchor)) { "Preference の表示領域がありません" }
        val beforeAnchor = bounds(anchor)
        val beforeChrome = assertChrome("キー操作とEmacs編集")
        assertContentColumn(viewport)

        assertTrue("長いキー割り当てページを前方へスクロールできません",
            viewport.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD))
        awaitContentMovement(anchorText, beforeAnchor)
        assertChrome("キー操作とEmacs編集", beforeChrome)
        capture("settings-keys-after-scroll")
        backToSettings()
    }

    @Test fun basicPagesUseNativePreferenceRowsAndKeepFixedActions() {
        openSettings()
        assertBasicPage(
            page = "確定と改行",
            label = "Enterで確定だけ行う",
            summary = "オフの場合、確定後に改行します",
        )
        assertBasicPage(
            page = "画面キーボードとモード表示",
            label = "物理キーボード接続中は画面の文字キーを隠す",
            summary = "候補とモードはポップアップで表示します",
        )
        assertBasicPage(
            page = "学習と補完",
            label = "変換結果を学習する",
            summary = "登録した語と候補の使用履歴を保存します",
        )
    }

    private fun assertBasicPage(page: String, label: String, summary: String) {
        openPage(page)
        awaitText(label)
        val chrome = assertChrome(page)
        assertVisibleText(label)
        assertVisibleText(summary)
        assertNoCategorySpinner()
        assertChrome(page, chrome)
        capture("settings-basic-${page.hashCode()}")
        backToSettings()
    }

    /** ACTION_VIEW は初期設定への自動遷移を避け、通常設定の入口だけを開きます。 */
    private fun openSettings() {
        device.executeShellCommand(
            "am start -W -a android.intent.action.VIEW -f 0x14000000 -n se.haya.skk/.SettingsActivity",
        )
        device.waitForIdle()
        awaitText("ローマ字の打ち方")
    }

    private fun openPage(title: String) {
        val list = UiScrollable(UiSelector().scrollable(true))
        list.scrollIntoView(UiSelector().text(title))
        check(device.findObject(UiSelector().text(title)).click()) { "設定ページを開けません: $title" }
        device.waitForIdle()
    }

    private fun backToSettings() {
        device.pressBack()
        device.waitForIdle()
        // 一覧は直前のスクロール位置を保持するため、先頭行ではなく固定ヘッダーを待ちます。
        awaitText("設定")
    }

    private data class ChromeBounds(val title: Rect, val back: Rect, val save: Rect, val defaults: Rect)

    private fun assertChrome(title: String, expected: ChromeBounds? = null): ChromeBounds {
        assertVisibleText(title)
        val titleNode = awaitNode("見出しがありません: $title") { it.text?.toString() == title }
        val back = awaitNode("戻るボタンがありません") { it.contentDescription?.toString() == "戻る" }
        assertFullyVisible(back, "戻るボタン")
        val save = assertButton("保存")
        val defaults = assertButton("標準に戻す")
        val actual = ChromeBounds(bounds(titleNode), bounds(back), bounds(save), bounds(defaults))
        expected?.let {
            assertTrue("スクロールで固定ヘッダーの位置が変わりました: ${it.title} / ${actual.title}", it.title == actual.title)
            assertTrue("スクロールで戻るボタンの位置が変わりました: ${it.back} / ${actual.back}", it.back == actual.back)
            assertTrue("スクロールで保存ボタンの位置が変わりました: ${it.save} / ${actual.save}", it.save == actual.save)
            assertTrue("スクロールで標準に戻すボタンの位置が変わりました: ${it.defaults} / ${actual.defaults}", it.defaults == actual.defaults)
        }
        return actual
    }

    private fun assertButton(text: String): AccessibilityNodeInfo {
        val node = awaitNode("操作ボタンがありません: $text") {
            it.text?.toString() == text && it.className?.toString() == "android.widget.Button"
        }
        assertTrue("操作ボタンが無効です: $text", node.isEnabled)
        assertFullyVisible(node, text)
        return node
    }

    private fun assertVisibleText(text: String) {
        val node = awaitNode("表示文言がありません: $text") { it.text?.toString() == text }
        assertFullyVisible(node, text)
        contentAncestor(node)?.let { viewport ->
            val nodeBounds = bounds(node)
            val viewportBounds = bounds(viewport)
            assertTrue("$text が設定本文の表示領域からはみ出しています: $nodeBounds / $viewportBounds",
                viewport.isVisibleToUser && !viewportBounds.isEmpty && viewportBounds.contains(nodeBounds))
        }
    }

    private fun assertNoCategorySpinner() {
        val spinner = allNodes().firstOrNull { it.className?.toString() == "android.widget.Spinner" }
        assertFalse("カテゴリ選択用の Spinner を表示しています", spinner != null)
    }

    private fun assertFullyVisible(node: AccessibilityNodeInfo, label: String) {
        val bounds = bounds(node)
        val screen = Rect(0, 0, device.displayWidth, device.displayHeight)
        assertTrue("$label が画面外または切れています: $bounds / $screen",
            node.isVisibleToUser && !bounds.isEmpty && screen.contains(bounds))
    }

    private fun bounds(node: AccessibilityNodeInfo): Rect = Rect().also(node::getBoundsInScreen)

    private fun contentAncestor(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var current = node.parent
        while (current != null) {
            if (current.className?.toString()?.endsWith("RecyclerView") == true) return current
            current = current.parent
        }
        return null
    }

    private fun assertContentColumn(viewport: AccessibilityNodeInfo) {
        val toolbar = awaitNode("設定画面のツールバーがありません") {
            it.viewIdResourceName == "$SKK_PACKAGE:id/settings_top_bar"
        }
        val available = bounds(toolbar)
        val content = bounds(viewport)
        val maximum = (720 * instrumentation.targetContext.resources.displayMetrics.density).toInt()
        val expectedWidth = minOf(available.width(), maximum)
        assertTrue("設定本文の幅が min(利用可能幅, 720dp) ではありません: $content / $available / max=$maximum",
            kotlin.math.abs(content.width() - expectedWidth) <= 2)
        assertTrue("設定本文が利用可能領域の中央にありません: $content / $available",
            kotlin.math.abs(content.centerX() - available.centerX()) <= 2)
    }

    private fun awaitContentMovement(anchorText: String, before: Rect) {
        val deadline = SystemClock.uptimeMillis() + 5_000
        var current: Rect? = before
        while (SystemClock.uptimeMillis() < deadline) {
            current = allNodes().firstOrNull {
                it.text?.toString() == anchorText && it.isVisibleToUser
            }?.let(::bounds)
            if (current == null || current != before) return
            SystemClock.sleep(25)
        }
        throw AssertionError("スクロール後も本文の位置が変わっていません: $before / $current")
    }

    private fun awaitText(text: String) = awaitNode("表示待機が期限を超えました: $text") {
        it.text?.toString() == text && it.isVisibleToUser
    }

    private fun awaitNode(message: String, predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo {
        val deadline = SystemClock.uptimeMillis() + 8_000
        while (true) {
            allNodes().firstOrNull(predicate)?.let { return it }
            check(SystemClock.uptimeMillis() < deadline) { message }
            SystemClock.sleep(25)
        }
    }

    private fun allNodes(): List<AccessibilityNodeInfo> = automation.windows
        .mapNotNull { it.root }
        .filter { it.packageName?.toString() == "se.haya.skk" }
        .flatMap(::descendants)

    private fun descendants(node: AccessibilityNodeInfo): List<AccessibilityNodeInfo> = buildList {
        add(node)
        for (index in 0 until node.childCount) node.getChild(index)?.let { addAll(descendants(it)) }
    }

    private fun capture(name: String) {
        val directory = instrumentation.targetContext.getExternalFilesDir(null) ?: run {
            Log.w(TAG, "スクリーンショット保存先がありません: $name")
            return
        }
        val image = File(directory, "$name.png")
        val metadata = File(directory, "$name.metadata.txt")
        image.delete()
        val configuration = instrumentation.targetContext.resources.configuration
        val capturedAt = System.currentTimeMillis()
        val bitmap = automation.takeScreenshot()
        metadata.outputStream().bufferedWriter().use { output ->
            output.appendLine("capturedAtMillis=$capturedAt")
            output.appendLine("screenWidthDp=${configuration.screenWidthDp}")
            output.appendLine("screenHeightDp=${configuration.screenHeightDp}")
            output.appendLine("fontScale=${configuration.fontScale}")
            output.appendLine("orientation=${configuration.orientation}")
            output.appendLine("deviceDisplayWidthPx=${device.displayWidth}")
            output.appendLine("deviceDisplayHeightPx=${device.displayHeight}")
            output.appendLine("screenshotWidthPx=${bitmap?.width ?: "null"}")
            output.appendLine("screenshotHeightPx=${bitmap?.height ?: "null"}")
            output.appendLine("screenshot=${if (bitmap == null) "null" else image.name}")
        }
        if (bitmap == null) {
            Log.w(TAG, "スクリーンショットを取得できませんでした: $name")
            return
        }
        image.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }

    private companion object {
        const val SKK_PACKAGE = "se.haya.skk"
        const val TAG = "SettingsLayoutTest"
    }
}
