package se.haya.skk.testeditor

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Rect
import android.graphics.Bitmap
import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import androidx.test.core.app.ActivityScenario
import androidx.lifecycle.Lifecycle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import org.json.JSONObject

/** 登録面と実キーボードを座標で操作し、入力先の保護と固定配置を確認します。 */
@RunWith(AndroidJUnit4::class)
class RegistrationModalTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val device = UiDevice.getInstance(instrumentation)
    private val automation = instrumentation.uiAutomation.apply {
        serviceInfo = serviceInfo.apply {
            flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
        }
    }

    @Test fun modalKeepsGeometryAndRoutesKeyboardTouchesUntilCancel() {
        ActivityScenario.launch(InputTestActivity::class.java).use { scenario ->
            val fields = prepare(scenario)
            val before = stableGeometry(fields.first)
            val reading = openRegistration(fields.first)
            assertGeometry(before, fields.first)
            assertFooterAboveKeyboard()
            assertTrue("登録見出しが表示されません", requiredId("registration_title").text.toString().contains("単語登録"))

            val initialSelection = selection(fields.first)
            val otherSelection = selection(fields.second)
            val appText = text(fields.first)
            val otherBounds = bounds(fields.second)
            // 登録面の背後にある別入力欄の位置へ、実際のタッチを送ります。
            assertTrue(device.click(otherBounds.centerX(), otherBounds.centerY()))
            SystemClock.sleep(200)
            instrumentation.runOnMainSync {
                assertTrue("背後の入力欄へフォーカスが移りました", fields.first.hasFocus())
                assertFalse("別入力欄がフォーカスされました", fields.second.hasFocus())
            }
            assertEquals("背後の入力欄の選択が変わりました", initialSelection, selection(fields.first))
            assertEquals("別入力欄の選択が変わりました", otherSelection, selection(fields.second))
            assertEquals("背後へのタップで登録が閉じました", 1, nodes().count { it.contentDescription?.toString() == MODAL })

            instrumentation.sendStringSync("sousakugo")
            await { registrationText().isNotEmpty() }
            val body = registrationText()
            val key = nodes().firstOrNull {
                it.isClickable && (it.text?.toString() in CHARACTER_KEYS ||
                    it.contentDescription?.toString() in CHARACTER_KEYS) && rect(it).top >= keyboardTop()
            }
            assertTrue("タッチ可能な文字キーが見つかりません", key != null)
            tap(requireNotNull(key))
            await { registrationText() != body }
            assertEquals("登録中の文字キーがアプリ本文を変更しました", appText, text(fields.first))
            assertGeometry(before, fields.first)
            assertFooterAboveKeyboard()
            tap(requiredId("registration_cancel"))
            await { modal() == null }
            assertEquals("取消後に外側の読みへ戻りません", reading, text(fields.first))
            assertGeometry(before, fields.first)
            assertNoAppAction(scenario)
        }
    }

    @Test fun backCancelsOneFrameAndChangingEditorRemovesModal() {
        ActivityScenario.launch(InputTestActivity::class.java).use { scenario ->
            val fields = prepare(scenario)
            val before = stableGeometry(fields.first)
            val reading = openRegistration(fields.first)
            // 本文が空の登録枠でも、Back 一回で外側の読みへ戻ります。
            assertTrue(device.pressBack())
            await { modal() == null }
            assertEquals("Back 後に外側の読みへ戻りません", reading, text(fields.first))
            assertGeometry(before, fields.first)
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_SPACE)
            await { modal() != null }
            scenario.onActivity {
                fields.second.requestFocus()
                showKeyboard(fields.second)
            }
            await { modal() == null }
            assertTrue("入力先切替でキーボードが消えました", backspace() != null)
            scenario.onActivity { assertTrue("入力先が切り替わりません", fields.second.hasFocus()) }
            assertNoAppAction(scenario)
        }
    }

    @Test fun backCancelsExactlyOneNestedRegistrationFrame() {
        ActivityScenario.launch(InputTestActivity::class.java).use { scenario ->
            val fields = prepare(scenario)
            val before = stableGeometry(fields.first)
            val reading = openRegistration(fields.first)
            val outerTitle = requiredId("registration_title").text.toString()
            instrumentation.sendStringSync(uniqueChildRomaji())
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_SPACE)
            await {
                id("registration_title")?.text?.toString() != outerTitle &&
                    id("registration_parent")?.text?.isNotEmpty() == true
            }
            assertEquals("入れ子の登録がアプリ本文を変更しました", reading, text(fields.first))
            assertGeometry(before, fields.first)
            assertFooterAboveKeyboard()

            // KeyEvent と OnBackInvoked が重なっても、一回の Back は一階層だけを閉じます。
            assertTrue(device.pressBack())
            await { modal() != null && id("registration_title")?.text?.toString() == outerTitle }
            SystemClock.sleep(300)
            assertTrue("Back 一回で外側の登録まで閉じました", modal() != null)
            assertEquals("外側の登録へ戻りません", outerTitle, requiredId("registration_title").text.toString())
            assertEquals("入れ子の取消がアプリ本文を変更しました", reading, text(fields.first))
            assertGeometry(before, fields.first)
            assertFooterAboveKeyboard()

            assertTrue(device.pressBack())
            await { modal() == null }
            assertEquals("外側の取消後に読みへ戻りません", reading, text(fields.first))
            assertGeometry(before, fields.first)
            assertNoAppAction(scenario)
        }
    }

    @Test fun registerButtonSavesChildAndOuterCancelDiscardsOuterDraft() {
        ActivityScenario.launch(InputTestActivity::class.java).use { scenario ->
            val fields = prepare(scenario)
            val before = stableGeometry(fields.first)
            val reading = openRegistration(fields.first)
            val outerTitle = requiredId("registration_title").text.toString()
            val childRomaji = uniqueChildRomaji()
            instrumentation.sendStringSync(childRomaji)
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_SPACE)
            await {
                id("registration_title")?.text?.toString() != outerTitle &&
                    id("registration_parent")?.text?.isNotEmpty() == true
            }
            val childReading = requiredId("registration_title").text.toString().removePrefix("単語登録：")
            instrumentation.sendStringSync("sousakugo")
            await { id("registration_register")?.isEnabled == true && registrationText().isNotEmpty() }
            val childBody = registrationText()
            assertFooterAboveKeyboard()
            assertGeometry(before, fields.first)
            // 子の登録は直ちに保存されるため、失敗時もこの一件だけを回復できる情報を先に残します。
            val recovery = File(instrumentation.targetContext.cacheDir, "registration-modal-entry-${UUID.randomUUID()}.json")
            recovery.writeText(JSONObject().put("romaji", childRomaji).put("reading", childReading)
                .put("candidate", childBody).toString())
            var failure: Throwable? = null
            try {
                tap(requiredId("registration_register"))
                await {
                    modal() != null && id("registration_title")?.text?.toString() == outerTitle &&
                        registrationText().contains(childBody)
                }
                assertEquals("内側の登録がアプリ本文を変更しました", reading, text(fields.first))
                assertGeometry(before, fields.first)
                assertFooterAboveKeyboard()
                // 外側の入力案は取り消します。保存済みの子の一件は finally で個別に削除します。
                tap(requiredId("registration_cancel"))
                await { modal() == null }
                assertEquals("外側の取消後に読みへ戻りません", reading, text(fields.first))
                assertGeometry(before, fields.first)
                assertNoAppAction(scenario)
            } catch (error: Throwable) {
                failure = error
                throw error
            } finally {
                try {
                    deleteCreatedChild(fields.first, childRomaji, childReading, childBody)
                    assertTrue("試験用の回復記録を削除できません", recovery.delete())
                } catch (cleanupError: Throwable) {
                    captureFailure()
                    if (failure != null) failure.addSuppressed(cleanupError) else throw cleanupError
                }
            }
        }
    }

    @Test fun landscapeLongBodyKeepsFooterReachableAndRotationRemovesModal() {
        ActivityScenario.launch(InputTestActivity::class.java).use { scenario ->
            var previousOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            scenario.onActivity {
                previousOrientation = it.requestedOrientation
                it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            }
            try {
                await {
                    var landscape = false
                    scenario.onActivity { landscape = it.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE }
                    landscape
                }
                val fields = prepare(scenario)
                val before = stableGeometry(fields.first)
                openRegistration(fields.first)
                // 単語は保存せず、本文をスクロールが必要な長さまで伸ばします。
                instrumentation.sendStringSync("sousakugo".repeat(28))
                await { registrationText().length >= 100 }
                assertGeometry(before, fields.first)
                assertFooterAboveKeyboard()
                assertRegistrationEditorHasVisibleLine()
                captureVerifiedLandscape()
                val textBeforeCancel = text(fields.first)
                tap(requiredId("registration_cancel"))
                await { modal() == null }
                assertEquals("長い登録本文がアプリへ漏れました", textBeforeCancel, text(fields.first))
                assertGeometry(before, fields.first)
                instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_SPACE)
                await { modal() != null }
                scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
                await {
                    var portrait = false
                    scenario.onActivity { portrait = it.resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT }
                    portrait && modal() == null
                }
                assertTrue("回転後に登録面が残っています", modal() == null)
                assertNoAppAction(scenario)
            } catch (error: Throwable) {
                captureFailure()
                throw error
            } finally {
                scenario.onActivity { it.requestedOrientation = previousOrientation }
            }
        }
    }

    private fun prepare(scenario: ActivityScenario<InputTestActivity>): Pair<EditText, EditText> {
        await {
            var ready = false
            if (scenario.state == Lifecycle.State.RESUMED) {
                scenario.onActivity { activity ->
                    val decor = activity.window.decorView
                    ready = decor.isAttachedToWindow && decor.hasWindowFocus()
                }
            }
            ready
        }
        assertEquals("入力確認画面が再開されていません", Lifecycle.State.RESUMED, scenario.state)
        lateinit var result: Pair<EditText, EditText>
        scenario.onActivity { activity ->
            activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            val fields = editors(activity.window.decorView)
            result = fields[0] to fields[1]
            fields[0].setText("")
            fields[1].setText("入力先切替用")
            fields[1].setSelection(2)
            showKeyboard(fields[0])
        }
        var attempts = 1
        var nextAttempt = SystemClock.uptimeMillis() + 750
        await {
            val visible = backspace() != null
            if (!visible && attempts < 6 && SystemClock.uptimeMillis() >= nextAttempt) {
                // 回転後の接続準備が遅れた場合だけ、同じ入力欄への表示要求を再送します。
                scenario.onActivity { activity ->
                    val decor = activity.window.decorView
                    if (decor.isAttachedToWindow && decor.hasWindowFocus()) {
                        showKeyboard(result.first)
                        attempts++
                    }
                }
                nextAttempt = SystemClock.uptimeMillis() + 750
            }
            visible
        }
        return result
    }

    private fun openRegistration(editor: EditText): String {
        instrumentation.sendStringSync("Ponyuporopamyu")
        val reading = text(editor)
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_SPACE)
        await { modal() != null && id("registration_cancel") != null }
        assertEquals("登録開始時に外側の読みが変わりました", reading, text(editor))
        return reading
    }

    private data class Geometry(val backspace: Rect, val editor: Rect, val candidateStrip: Rect)

    private fun geometry(editor: EditText): Geometry? = backspace()?.let {
        Geometry(rect(it), bounds(editor), candidateStripBounds())
    }

    private fun stableGeometry(editor: EditText): Geometry {
        var last: Geometry? = null
        var frames = 0
        await {
            val next = geometry(editor)
            if (next != null && next.backspace.bottom <= device.displayHeight && next == last) frames++
            else { last = next; frames = 0 }
            frames >= 5
        }
        return requireNotNull(last)
    }

    private fun assertGeometry(before: Geometry, editor: EditText) {
        repeat(5) {
            assertEquals("登録の表示でキーボードまたは入力欄が移動しました", before, geometry(editor))
            SystemClock.sleep(50)
        }
    }

    private fun assertFooterAboveKeyboard() {
        val top = keyboardTop()
        val density = instrumentation.targetContext.resources.displayMetrics.density
        listOf("registration_register", "registration_cancel").forEach { name ->
            val bounds = rect(requiredId(name))
            assertTrue("登録操作が表示領域外です: $name bounds=$bounds displayWidth=${device.displayWidth}", bounds.left >= 0 && bounds.right <= device.displayWidth && bounds.top >= 0)
            assertTrue("登録操作がキーボードに重なっています: $name", bounds.bottom <= top)
            assertTrue("登録操作が下端から離れています: $name", top - bounds.bottom <= 48 * density)
        }
    }

    // モーダル側にも同じ ID があるため、画面下側の通常候補欄を選びます。
    // 通常候補欄の下端が文字キー領域の上端です。候補欄自体の座標も別途固定を確認します。
    private fun candidateStripBounds(): Rect = nodes()
        .filter { it.viewIdResourceName == "$IME:id/candidate_status_container" }
        .map(::rect).maxByOrNull { it.top } ?: error("通常候補欄が見つかりません")

    private fun keyboardTop(): Int = candidateStripBounds().bottom

    private fun uniqueChildRomaji(): String {
        val syllables = listOf("a", "i", "u", "e", "o", "ka", "ki", "ku", "ke", "ko", "sa", "si", "su", "se", "so", "ta")
        val suffix = UUID.randomUUID().toString().replace("-", "").take(12)
            .map { syllables[it.digitToInt(16)] }.joinToString("")
        return "Myu$suffix"
    }

    /** 生成した読みと候補の一致を確認した一件だけを、通常の削除確認から除去します。 */
    private fun deleteCreatedChild(editor: EditText, romaji: String, reading: String, candidate: String) {
        repeat(4) { key(KeyEvent.KEYCODE_G, KeyEvent.META_CTRL_ON) }
        await { modal() == null && text(editor).isEmpty() }
        instrumentation.sendStringSync(romaji)
        await { withoutSkkMarker(text(editor)) == reading }
        val readingPresentation = text(editor)
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_SPACE)
        await { modal() != null || soleTouchCandidate() == candidate }
        if (modal() != null) {
            assertEquals("別の読みの登録面が開きました", "単語登録：$reading", requiredId("registration_title").text.toString())
            tap(requiredId("registration_cancel"))
            return
        }
        assertEquals("削除時の読みが試験で保存した読みと一致しません", reading, withoutSkkMarker(text(editor)))
        assertEquals("削除対象が試験で保存した唯一の候補と一致しません", candidate, soleTouchCandidate())
        key(KeyEvent.KEYCODE_X, KeyEvent.META_SHIFT_ON)
        val confirmation = "削除確認: $reading → $candidate"
        await { nodes().any { it.text?.toString()?.contains(confirmation) == true } }
        // 確認表示の読みと表記が両方一致するまで、削除の y は送信しません。
        assertTrue("削除確認の対象が一致しません", nodes().any { it.text?.toString()?.contains(confirmation) == true })
        instrumentation.sendStringSync("y")
        await {
            nodes().none { it.text?.toString()?.contains(confirmation) == true } &&
                modal() == null && text(editor) == readingPresentation
        }
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_SPACE)
        await { modal() != null && id("registration_title")?.text?.toString() == "単語登録：$reading" }
        tap(requiredId("registration_cancel"))
        await { modal() == null }
    }

    // タッチ表示ではアプリ本文は ▼読みのままで、選択候補の表記は IME の一覧にあります。
    private fun soleTouchCandidate(): String? {
        val candidates = nodes().filter { node ->
            if (!node.isClickable || node.className?.toString() != "android.widget.LinearLayout" ||
                node.contentDescription.isNullOrBlank()) return@filter false
            var parent = node.parent
            var insideCandidates = false
            while (parent != null) {
                if (parent.viewIdResourceName == "$IME:id/candidate_status_container") insideCandidates = true
                parent = parent.parent
            }
            insideCandidates
        }
        return candidates.singleOrNull()?.contentDescription?.toString()
    }

    private fun withoutSkkMarker(value: String): String = when (value.firstOrNull()) {
        '▽', '▼' -> value.substring(1)
        else -> value
    }

    private fun key(code: Int, modifiers: Int) {
        val now = SystemClock.uptimeMillis()
        instrumentation.sendKeySync(KeyEvent(now, now, KeyEvent.ACTION_DOWN, code, 0, modifiers))
        instrumentation.sendKeySync(KeyEvent(now, SystemClock.uptimeMillis(), KeyEvent.ACTION_UP, code, 0, modifiers))
    }

    private fun assertRegistrationEditorHasVisibleLine() {
        val editor = requiredId("registration_editor")
        val visible = rect(editor)
        var parent = editor.parent
        // 長い本文の全体高ではなく、スクロール枠と祖先で切り取られた可視高を確認します。
        while (parent != null) {
            if (!visible.intersect(rect(parent))) visible.setEmpty()
            parent = parent.parent
        }
        val modalBounds = rect(requireNotNull(modal()))
        if (!visible.intersect(modalBounds)) visible.setEmpty()
        val footerTop = minOf(rect(requiredId("registration_register")).top,
            rect(requiredId("registration_cancel")).top)
        visible.bottom = minOf(visible.bottom, footerTop)
        val minimumHeight = 16 * instrumentation.targetContext.resources.displayMetrics.density
        assertTrue("横画面で登録本文の一行が見えません: $visible", !visible.isEmpty && visible.height() >= minimumHeight)
    }

    /** 長い合成本文を表示した検証済み画面を、取消と回転の前に記録します。 */
    private fun captureVerifiedLandscape() {
        val bitmap = requireNotNull(automation.takeScreenshot()) { "横画面の検証画像を取得できません" }
        try {
            File(instrumentation.targetContext.cacheDir, "registration-modal-verified-landscape.png")
                .outputStream().use { output ->
                    assertTrue("横画面の検証画像を保存できません", bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
                }
        } finally {
            bitmap.recycle()
        }
    }

    private fun modal() = nodes().firstOrNull { it.contentDescription?.toString() == MODAL }
    private fun id(name: String) = nodes().firstOrNull { it.viewIdResourceName == "$IME:id/$name" }
    private fun requiredId(name: String) = requireNotNull(id(name)) { "登録操作が見つかりません: $name" }
    private fun registrationText() = id("registration_editor")?.text?.toString().orEmpty()
    private fun backspace() = nodes().firstOrNull { it.text?.toString() == "⌫" || it.contentDescription?.toString() == "⌫" }
    private fun rect(node: AccessibilityNodeInfo) = Rect().also(node::getBoundsInScreen)

    private fun nodes(): List<AccessibilityNodeInfo> {
        fun descend(node: AccessibilityNodeInfo): List<AccessibilityNodeInfo> = listOf(node) +
            (0 until node.childCount).flatMap { node.getChild(it)?.let(::descend).orEmpty() }
        return automation.windows.flatMap { it.root?.let(::descend).orEmpty() }
            .filter { it.isVisibleToUser && it.packageName?.toString() == IME }
    }

    private fun tap(node: AccessibilityNodeInfo) {
        val bounds = rect(node)
        assertTrue("登録操作のタップに失敗しました", device.click(bounds.centerX(), bounds.centerY()))
    }

    private fun showKeyboard(editor: EditText) {
        editor.requestFocus()
        editor.post {
            editor.context.getSystemService(InputMethodManager::class.java)
                .showSoftInput(editor, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    private fun bounds(view: View): Rect {
        val result = Rect()
        instrumentation.runOnMainSync {
            val location = IntArray(2)
            view.getLocationOnScreen(location)
            result.set(location[0], location[1], location[0] + view.width, location[1] + view.height)
        }
        return result
    }

    private fun text(editor: EditText): String {
        var result = ""
        instrumentation.runOnMainSync { result = editor.text.toString() }
        return result
    }

    private fun selection(editor: EditText): Pair<Int, Int> {
        var result = 0 to 0
        instrumentation.runOnMainSync { result = editor.selectionStart to editor.selectionEnd }
        return result
    }

    private fun assertNoAppAction(scenario: ActivityScenario<InputTestActivity>) {
        scenario.onActivity { assertEquals("登録操作がアプリのアクションを実行しました", 0, it.editorActionCount) }
    }

    private fun editors(view: View): List<EditText> = when (view) {
        is EditText -> listOf(view)
        is ViewGroup -> (0 until view.childCount).flatMap { editors(view.getChildAt(it)) }
        else -> emptyList()
    }

    private fun await(condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 20_000
        while (SystemClock.uptimeMillis() < deadline) {
            if (condition()) return
            SystemClock.sleep(50)
        }
        if (!condition()) {
            captureFailure()
            throw AssertionError("単語登録モーダルが期待状態に到達しませんでした")
        }
    }

    /** 後始末で表示が消える前に、合成入力だけを含む試験画面をアプリ専用領域へ残します。 */
    private fun captureFailure() {
        val directory = File(instrumentation.targetContext.cacheDir, "registration-modal-failure")
        directory.mkdirs()
        runCatching { device.dumpWindowHierarchy(File(directory, "hierarchy.xml")) }
        runCatching {
            automation.takeScreenshot()?.let { bitmap ->
                try {
                    File(directory, "screen.png").outputStream().use {
                        bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
                    }
                } finally {
                    bitmap.recycle()
                }
            }
        }
    }

    private companion object {
        const val IME = "se.haya.skk"
        const val MODAL = "単語登録モーダル"
        val CHARACTER_KEYS = setOf("あ", "か", "a", "q")
    }
}
