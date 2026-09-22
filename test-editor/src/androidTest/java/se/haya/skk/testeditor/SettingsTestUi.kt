package se.haya.skk.testeditor

import android.app.Instrumentation
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiScrollable
import androidx.test.uiautomator.UiSelector

/** 公開設定画面から文字キーを有効にし、保存の完了を待ちます。 */
internal fun enableTouchKeysThroughSettings(instrumentation: Instrumentation) {
    val device = UiDevice.getInstance(instrumentation)
    device.executeShellCommand("am start -W -a android.intent.action.VIEW -f 0x14000000 -n se.haya.skk/.SettingsActivity")
    device.waitForIdle()
    val page = "画面キーボードとモード表示"
    UiScrollable(UiSelector().scrollable(true)).scrollIntoView(UiSelector().text(page))
    check(device.findObject(UiSelector().text(page)).click()) { "画面キーボードの設定を開けません" }
    val label = "物理キーボード接続中は画面の文字キーを隠す"
    fun descendants(node: AccessibilityNodeInfo): List<AccessibilityNodeInfo> = buildList {
        add(node)
        for (i in 0 until node.childCount) node.getChild(i)?.let { addAll(descendants(it)) }
    }
    fun switch(): AccessibilityNodeInfo? {
        val roots = instrumentation.uiAutomation.windows.mapNotNull { it.root }
            .filter { it.packageName?.toString() == "se.haya.skk" }
        for (root in roots) {
            var node = root.findAccessibilityNodeInfosByText(label).firstOrNull { it.text?.toString() == label }
            while (node != null) {
                descendants(node).firstOrNull { it.isCheckable }?.let { return it }
                node = node.parent
            }
        }
        return null
    }
    fun await(message: String, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 8000
        while (!condition()) {
            check(SystemClock.uptimeMillis() < deadline) { message }
            SystemClock.sleep(25)
        }
    }
    await("文字キーの設定を読み込めません") { switch()?.isEnabled == true }
    if (switch()?.isChecked == true) {
        check(device.findObject(UiSelector().text(label)).click())
        await("文字キー非表示の下書きを変更できません") { switch()?.isChecked == false }
    }
    check(device.findObject(UiSelector().text("保存").className("android.widget.Button")).click())
    await("文字キー設定の保存が完了しません") {
        instrumentation.uiAutomation.windows.mapNotNull { it.root }.any {
            it.packageName?.toString() == "se.haya.skk" &&
                it.findAccessibilityNodeInfosByText("設定").any { node -> node.text?.toString() == "設定" }
        }
    }
}
