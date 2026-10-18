package se.haya.skk.testeditor

import android.accessibilityservice.AccessibilityServiceInfo
import android.graphics.Rect
import android.os.Bundle
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiSelector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Firefox導入済み端末で10キーを有効にし、-e firefox true を付けて実行します。 */
@RunWith(AndroidJUnit4::class)
class FirefoxTenKeyDeletionTest {
    @Test fun selectedAddressDeletesWithOneTenKeyBackspace() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("firefox") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val device = UiDevice.getInstance(instrumentation)
        assumeTrue(device.executeShellCommand("pm list packages org.mozilla.firefox").contains("package:org.mozilla.firefox"))
        val automation = instrumentation.uiAutomation.apply {
            serviceInfo = serviceInfo.apply {
                flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            }
        }
        device.executeShellCommand("am start -W -a android.intent.action.VIEW -d about:blank -p org.mozilla.firefox")
        val toolbar = device.findObject(UiSelector().resourceId("org.mozilla.firefox:id/mozac_browser_toolbar_url_view"))
        assertTrue("Firefoxのアドレス欄がありません", toolbar.waitForExists(5_000))
        assertTrue(toolbar.click())
        fun editor(): AccessibilityNodeInfo? = automation.windows.asSequence().mapNotNull { it.root }
            .flatMap { root -> sequence {
                suspend fun SequenceScope<AccessibilityNodeInfo>.visit(node: AccessibilityNodeInfo) {
                    yield(node)
                    for (index in 0 until node.childCount) node.getChild(index)?.let { visit(it) }
                }
                visit(root)
            } }
            .firstOrNull { it.packageName?.toString() == "org.mozilla.firefox" && it.isEditable && it.isFocused }
        fun await(condition: () -> Boolean) {
            val end = SystemClock.uptimeMillis() + 5_000
            while (!condition()) {
                check(SystemClock.uptimeMillis() < end) { "Firefoxの選択削除が完了しません" }
                SystemClock.sleep(25)
            }
        }
        await { editor() != null }
        val url = "https://example.invalid/selected-address"
        for (reversed in listOf(false, true)) {
            val field = checkNotNull(editor())
            assertTrue(field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, url)
            }))
            await { editor()?.text?.toString() == url }
            assertTrue(checkNotNull(editor()).performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, Bundle().apply {
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, if (reversed) url.length else 0)
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, if (reversed) 0 else url.length)
            }))
            await { editor()?.let { it.textSelectionStart != it.textSelectionEnd } == true }
            device.waitForIdle()
            val key = automation.windows.asSequence().mapNotNull { it.root }
                .filter { it.packageName?.toString() == "se.haya.skk" }
                .flatMap { it.findAccessibilityNodeInfosByText("⌫").asSequence() }
                .first { it.isVisibleToUser && it.text?.toString() == "⌫" }
            val bounds = Rect().also(key::getBoundsInScreen)
            assertTrue(device.click(bounds.centerX(), bounds.centerY()))
            await { editor()?.let { it.textSelectionStart == 0 && it.textSelectionEnd == 0 && it.isShowingHintText } == true ||
                editor()?.text.isNullOrEmpty() }
            assertEquals(0, checkNotNull(editor()).textSelectionStart)
            assertEquals(0, checkNotNull(editor()).textSelectionEnd)
        }
        device.pressBack()
    }
}
