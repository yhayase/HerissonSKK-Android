package se.haya.skk.testeditor

import android.accessibilityservice.AccessibilityServiceInfo
import android.graphics.Rect
import android.os.SystemClock
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.InputDevice
import android.view.inputmethod.InputMethodManager
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.widget.EditText
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiScrollable
import androidx.test.uiautomator.UiSelector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** 実IMEの公開タッチUIだけで、変換・登録・保護入力・IME選択を確認します。 */
@RunWith(AndroidJUnit4::class)
class TouchInputTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val device = UiDevice.getInstance(instrumentation)
    private val automation = instrumentation.uiAutomation.apply {
        serviceInfo = serviceInfo.apply {
            flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
        }
    }

    @Before fun showTouchKeysWithHardwareKeyboard() {
        enableTouchKeysThroughSettings(instrumentation)
    }

    @Test fun touchOnlyConversionCandidateAndRecursiveRegistration() {
        launch().use { scenario ->
            val editor = scenario.editorStartingWith("送信")
            val counter = scenario.actionCounter()
            requestKeyboard(editor)
            awaitKey("q")
            device.waitForIdle()
            automation.takeScreenshot()?.let { bitmap ->
                java.io.File(instrumentation.targetContext.getExternalFilesDir(null), "touch-key-flick-hints.png")
                    .outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                bitmap.recycle()
            }

            touchRomaji("Nihon")
            tap("Space")
            awaitText(editor, "にほん")
            // 読みと予測候補は変換前にも存在するため、通常変換の表示完了後に座標を取ります。
            awaitNode("▶", exact = true)
            device.waitForIdle()
            val candidateBounds = Rect().also(awaitNode("日本", exact = false)::getBoundsInScreen)
            assertTrue(device.swipe(candidateBounds.centerX(), candidateBounds.centerY(),
                candidateBounds.centerX(), candidateBounds.centerY(), 140))
            awaitKey("閉じる")
            assertEquals("長押しの指離しで候補を確定しません", "にほん", text(editor))
            tap("閉じる")
            tap("確定")
            awaitText(editor, "日本")
            awaitNoComposition(editor)
            awaitText(counter, "入力先への Enter／アクション: 0 回")

            scenario.onActivity { editor.setText("") }
            awaitKey("q")
            touchRomaji("Tesuto")
            tap("Space")
            awaitText(editor, "てすと")
            val shownCandidate = awaitNode("候補", exact = false).let {
                (it.contentDescription ?: it.text).toString().substringBefore("、注釈: ")
            }
            tapTextPrefix(shownCandidate)
            awaitText(editor, shownCandidate)
            awaitNoComposition(editor)

            scenario.onActivity { editor.setText("") }
            awaitKey("q")
            touchRomaji("Nihon")
            tap("Space")
            tap("a")
            awaitText(editor, "日本あ")
            awaitText(counter, "入力先への Enter／アクション: 0 回")

            scenario.onActivity { editor.setText("") }
            awaitKey("q")
            touchRomaji("Mitorokupamyu")
            tap("Space")
            awaitImeText("単語登録：みとろくぱみゅ")
            touchRomaji("Goropazu")
            tap("Space")
            awaitImeText("単語登録：ごろぱず")
            touchRomaji("Nihon")
            tap("Space")
            awaitImeText("▼にほん")
            awaitNode("日本", exact = false)
            device.waitForIdle()
            automation.takeScreenshot()?.let { bitmap ->
                java.io.File(instrumentation.targetContext.getExternalFilesDir(null), "registration-touch.png")
                    .outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                bitmap.recycle()
            }
            tap("確定")
            awaitImeText("日本")
            tap("取消")
            awaitImeText("単語登録：みとろくぱみゅ")
            tap("取消")
        }
    }

    @Test fun protectedTouchInputCanTypeCorrectAndSubmitWithoutSkk() {
        launch().use { scenario ->
            val password = scenario.editorStartingWith("パスワード")
            val counter = scenario.actionCounter()
            requestKeyboard(password)
            awaitKey("q")

            touchRomaji("Nihon")
            tap("Space")
            tap("⌫")
            tap("123")
            tap("1")
            tap("+")
            tap("記号2")
            listOf("\\", "{", "=").forEach(::tap)
            tap("ABC")
            tap("q")
            tap("↵")

            awaitText(password, "Nihon1+\\{=q")
            awaitText(counter, "入力先への Enter／アクション: 1 回")
            assertFalse("保護入力で候補状態が表示されています", automation.windows.any { window ->
                val root = window.root ?: return@any false
                root.packageName?.toString() == SKK_PACKAGE && accessibilityDescendants(root).any {
                    it.isVisibleToUser && it.className?.toString() == TextView::class.java.name &&
                        it.text?.contains("候補") == true
                }
            })
        }
    }

    @Test fun idleTouchEditingFallsBackOnlyAfterSkkDeclinesIt() {
        launch().use { scenario ->
            val editor = scenario.editorStartingWith("複数行 A")
            requestKeyboard(editor)
            awaitKey("q")

            tap("a")
            awaitText(editor, "あ")
            tap("⌫")
            awaitText(editor, "")
            tap("Space")
            tap("↵")
            awaitText(editor, " \n")
            tap("⌫")
            awaitText(editor, " ")
            tap("⌫")
            awaitText(editor, "")
        }
    }

    @Test fun prefixPredictionAndConfirmedEmptyReadingCanBeCommittedWithoutConversion() {
        launch().use { scenario ->
            val editor = scenario.editorStartingWith("送信")
            val counter = scenario.actionCounter()
            requestKeyboard(editor)
            awaitKey("q")
            touchRomaji("Ni")
            awaitText(editor, "に")
            awaitNode("日本", exact = false)
            saveScreenshot("touch-prediction.png")
            tapTextPrefix("日本")
            awaitText(editor, "日本")
            awaitNoComposition(editor)
            awaitText(counter, "入力先への Enter／アクション: 0 回")

            scenario.onActivity { editor.setText("") }
            awaitKey("q")
            touchRomaji("Mitoro")
            awaitText(editor, "みとろ")
            awaitNode("みとろ", exact = true)
            tap("確定")
            awaitNoComposition(editor)
            assertEquals("みとろ", text(editor))
            awaitText(counter, "入力先への Enter／アクション: 0 回")

            scenario.onActivity { editor.setText("") }
            awaitKey("q")
            touchRomaji("Ni")
            awaitText(editor, "に")
            tap("あ")
            awaitNoComposition(editor)
            tap("q")
            awaitText(editor, "にq")
            awaitText(counter, "入力先への Enter／アクション: 0 回")
        }
    }

    @Test fun incompleteRomajiAndDictionaryOkuriConditionsRemainPredictable() {
        launch().use { scenario ->
            val editor = scenario.editorStartingWith("送信")
            val counter = scenario.actionCounter()
            requestKeyboard(editor)
            awaitKey("q")
            touchRomaji("N")
            awaitNode("日本", exact = false)
            touchRomaji("ih")
            awaitText(editor, "にh")
            awaitNode("日本", exact = false)
            tapTextPrefix("日本")
            awaitText(editor, "日本")
            awaitNoComposition(editor)

            scenario.onActivity { editor.setText("") }
            // 送り入力の開始前に、辞書の「かk /書/[く/書/]/」から「書く」を予測します。
            touchRomaji("Ka")
            awaitNode("書く", exact = false)
            tapTextPrefix("書く")
            awaitText(editor, "書く")
            awaitNoComposition(editor)
            awaitText(counter, "入力先への Enter／アクション: 0 回")
        }
    }

    @Test fun katakanaOkuriAndPunctuationCommitWithTheirConversion() {
        launch().use { scenario ->
            val editor = scenario.editorStartingWith("送信")
            val counter = scenario.actionCounter()
            requestKeyboard(editor)
            awaitKey("q")
            tap("q")
            touchRomaji("KaKu")
            awaitNode("書ク", exact = false)
            tap("確定")
            awaitText(editor, "書ク")
            awaitNoComposition(editor)

            tap("q")
            scenario.onActivity { editor.setText("") }
            touchRomaji("Nihon")
            tap(".")
            awaitNode("▶", exact = true)
            tapTextPrefix("日本。")
            awaitText(editor, "日本。")
            awaitNoComposition(editor)
            awaitText(counter, "入力先への Enter／アクション: 0 回")
        }
    }

    @Test fun quickFlickTypesUppercaseAndFirstPageSymbolsWithoutGuideDelay() {
        launch().use { scenario ->
            val editor = scenario.editorStartingWith("複数行 A")
            requestKeyboard(editor)
            awaitKey("q")
            tap("あ")
            flick("q", up = true)
            flick(",", up = true)
            flick(".", up = true)
            flick("m", up = false)
            tap("-")
            awaitText(editor, "Q!?/-")
            heldShiftLetter("n")
            tap("i")
            awaitText(editor, "Q!?/-Ni")
            awaitNoComposition(editor)
        }
    }

    @Test fun heldFlickGuideTracksCenterUpAndDownWithoutChangingFocus() {
        launch().use { scenario ->
            val editor = scenario.editorStartingWith("複数行 A")
            requestKeyboard(editor)
            awaitKey("q")
            tap("あ")
            device.waitForIdle()
            val box = Rect().also(awaitKey("q")::getBoundsInScreen)
            val down = SystemClock.uptimeMillis()
            fun inject(action: Int, dy: Float = 0f) {
                val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action,
                    box.centerX().toFloat(), box.centerY() + dy, 0).apply {
                    source = InputDevice.SOURCE_TOUCHSCREEN
                }
                try { assertTrue(automation.injectInputEvent(event, true)) } finally { event.recycle() }
            }
            inject(MotionEvent.ACTION_DOWN)
            var fingerDown = true
            try {
                awaitFlickGuide(box, selectedRow = 1)
                saveScreenshot("flick-guide-center.png")
                inject(MotionEvent.ACTION_MOVE, -box.height().toFloat())
                awaitFlickGuide(box, selectedRow = 0)
                saveScreenshot("flick-guide-up.png")
                inject(MotionEvent.ACTION_MOVE, box.height().toFloat())
                awaitFlickGuide(box, selectedRow = 2)
                saveScreenshot("flick-guide-down.png")
                assertEquals("指離し前に入力されました", "", text(editor))
                inject(MotionEvent.ACTION_UP, box.height().toFloat())
                fingerDown = false
                awaitText(editor, "1")
                awaitNoComposition(editor)
            } finally {
                if (fingerDown) runCatching { inject(MotionEvent.ACTION_CANCEL) }
            }
            for (label in listOf("m", "p")) {
                val edge = Rect().also(awaitKey(label)::getBoundsInScreen)
                val edgeDown = SystemClock.uptimeMillis()
                fun edgeEvent(action: Int) {
                    val event = MotionEvent.obtain(edgeDown, SystemClock.uptimeMillis(), action,
                        edge.centerX().toFloat(), edge.centerY().toFloat(), 0).apply {
                        source = InputDevice.SOURCE_TOUCHSCREEN
                    }
                    try { assertTrue(automation.injectInputEvent(event, true)) } finally { event.recycle() }
                }
                edgeEvent(MotionEvent.ACTION_DOWN)
                try {
                    awaitFlickGuide(edge, selectedRow = 1)
                    saveScreenshot("flick-guide-edge-$label.png")
                } finally {
                    edgeEvent(MotionEvent.ACTION_CANCEL)
                }
                assertEquals("取消で文字が入力されました", "1", text(editor))
            }
        }
    }

    @Test fun imePickerRemainsTouchableAndDoesNotChangeImeUntilSelection() {
        val selectedBefore = shell("settings get secure default_input_method").trim()
        launch().use { scenario ->
            val editor = scenario.editorStartingWith("送信")
            requestKeyboard(editor)
            awaitKey("IME切替")

            tap("IME切替")
            awaitCondition("入力方法選択画面が開きません") {
                automation.windows.any { window ->
                    val root = window.root ?: return@any false
                    val list = accessibilityDescendants(root).firstOrNull {
                        it.viewIdResourceName == "android:id/select_dialog_listview" && it.isVisibleToUser
                    } ?: return@any false
                    val nodes = accessibilityDescendants(list).toList()
                    nodes.any { it.text?.toString() == "HerissonSKK (for Android)" && it.isVisibleToUser } &&
                        nodes.any { it.isCheckable && it.isVisibleToUser }
                }
            }
            assertEquals("選択前に既定IMEが変わりました", selectedBefore,
                shell("settings get secure default_input_method").trim())
            device.pressBack()
        }
    }

    private fun touchRomaji(value: String) {
        value.forEach { character ->
            if (character.isUpperCase()) {
                tap("Shift")
                tap(character.lowercaseChar().toString().uppercase())
            } else tap(character.toString())
        }
    }

    private fun tap(label: String) {
        // ノードは表示アニメーション中にも現れるため、画面が落ち着いてから座標を取得します。
        device.waitForIdle()
        val node = awaitNode(label, exact = true)
        val bounds = Rect().also(node::getBoundsInScreen)
        assertTrue("キーをタップできません: $label $bounds", !bounds.isEmpty && node.isVisibleToUser)
        device.click(bounds.centerX(), bounds.centerY())
        instrumentation.waitForIdleSync()
    }

    private fun saveScreenshot(name: String) {
        device.waitForIdle()
        automation.takeScreenshot()?.let { bitmap ->
            java.io.File(instrumentation.targetContext.getExternalFilesDir(null), name).outputStream().use {
                bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
            bitmap.recycle()
        }
    }

    private fun awaitFlickGuide(anchor: Rect, selectedRow: Int) {
        // 操作を受け付けない窓はアクセシビリティ一覧に含まれないため、実際の描画を検査します。
        val density = instrumentation.targetContext.resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()
        val width = dp(104)
        val height = dp(132)
        val top = anchor.top - height - dp(4)
        val selected = android.graphics.Color.rgb(0x35, 0x5f, 0x91)
        val inactive = android.graphics.Color.rgb(0xf5, 0xf6, 0xf7)
        awaitCondition("フリックガイドの位置または強調行が一致しません: $selectedRow") {
            val bitmap = automation.takeScreenshot() ?: return@awaitCondition false
            try {
                val left = (anchor.centerX() - width / 2).coerceIn(0, bitmap.width - width)
                val sampleX = left + dp(10)
                top >= 0 && top + height <= bitmap.height && (0..2).all { row ->
                    val sampleY = top + dp(4) + ((height - dp(8)) * (row + .5f) / 3).toInt()
                    bitmap.getPixel(sampleX, sampleY) == if (row == selectedRow) selected else inactive
                }
            } finally {
                bitmap.recycle()
            }
        }
    }

    private fun heldShiftLetter(letter: String) {
        device.waitForIdle()
        val shift = Rect().also(awaitKey("Shift")::getBoundsInScreen)
        val key = Rect().also(awaitKey(letter)::getBoundsInScreen)
        val down = SystemClock.uptimeMillis()
        fun inject(action: Int, ids: IntArray, bounds: List<Rect>) {
            val properties = ids.map { id -> MotionEvent.PointerProperties().apply {
                this.id = id
                toolType = MotionEvent.TOOL_TYPE_FINGER
            } }.toTypedArray()
            val coordinates = bounds.map { box -> MotionEvent.PointerCoords().apply {
                x = box.centerX().toFloat()
                y = box.centerY().toFloat()
                pressure = 1f
                size = 1f
            } }.toTypedArray()
            val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, ids.size,
                properties, coordinates, 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0)
            try { assertTrue(automation.injectInputEvent(event, true)) } finally { event.recycle() }
        }
        inject(MotionEvent.ACTION_DOWN, intArrayOf(0), listOf(shift))
        inject(MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),
            intArrayOf(0, 1), listOf(shift, key))
        inject(MotionEvent.ACTION_POINTER_UP, intArrayOf(0, 1), listOf(shift, key))
        inject(MotionEvent.ACTION_UP, intArrayOf(1), listOf(key))
        instrumentation.waitForIdleSync()
    }

    private fun flick(label: String, up: Boolean) {
        device.waitForIdle()
        val node = awaitKey(label)
        val bounds = Rect().also(node::getBoundsInScreen)
        val distance = (bounds.height() * 0.7f).toInt().coerceAtLeast(12)
        assertTrue(device.swipe(bounds.centerX(), bounds.centerY(), bounds.centerX(),
            bounds.centerY() + if (up) -distance else distance, 5))
        instrumentation.waitForIdleSync()
    }

    private fun tapTextPrefix(prefix: String) {
        device.waitForIdle()
        val node = awaitNode(prefix, exact = false)
        val bounds = Rect().also(node::getBoundsInScreen)
        assertTrue("候補をタップできません: $prefix $bounds", !bounds.isEmpty && node.isVisibleToUser)
        device.click(bounds.centerX(), bounds.centerY())
        instrumentation.waitForIdleSync()
    }

    private fun awaitKey(label: String): AccessibilityNodeInfo = awaitNode(label, exact = true)

    private fun awaitNode(value: String, exact: Boolean, visibleOnly: Boolean = true): AccessibilityNodeInfo {
        var found: AccessibilityNodeInfo? = null
        awaitCondition("タッチUIに「$value」がありません") {
            found = automation.windows.asSequence().mapNotNull { it.root }
                .filter { it.packageName?.toString() == SKK_PACKAGE }
                .flatMap { it.findAccessibilityNodeInfosByText(value).asSequence() }
                .firstOrNull { node ->
                    val text = node.text?.toString().orEmpty()
                    val description = node.contentDescription?.toString().orEmpty()
                    (!visibleOnly || node.isVisibleToUser) && (if (exact) text == value || description == value
                    else text.startsWith(value) || description.startsWith(value))
                }
            found != null
        }
        return checkNotNull(found)
    }

    private fun awaitImeText(value: String) = awaitCondition("IMEに「$value」が表示されません") {
        imeTextContains(value)
    }

    private fun imeTextContains(value: String): Boolean = automation.windows.any { window ->
        val root = window.root
        root?.packageName?.toString() == SKK_PACKAGE &&
            root.findAccessibilityNodeInfosByText(value).isNotEmpty()
    }

    private fun awaitNoComposition(editor: EditText) = awaitCondition("候補タップで確定されません") {
        var start = Int.MIN_VALUE
        instrumentation.runOnMainSync {
            start = android.view.inputmethod.BaseInputConnection.getComposingSpanStart(editor.text)
        }
        start == -1
    }

    // フォーカス取得だけでは表示を要求しないため、入力先アプリとして明示的に要求します。
    private fun requestKeyboard(editor: EditText) = instrumentation.runOnMainSync {
        editor.requestFocus()
        editor.context.getSystemService(InputMethodManager::class.java)
            .showSoftInput(editor, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun launch(): ActivityScenario<InputTestActivity> = ActivityScenario.launch(
        android.content.Intent(instrumentation.targetContext, InputTestActivity::class.java)
            .putExtra(InputTestActivity.EXTRA_SUPPRESS_LEARNING, true),
    )

    private fun ActivityScenario<InputTestActivity>.editorStartingWith(hint: String): EditText {
        lateinit var result: EditText
        onActivity { activity ->
            result = descendants(activity.window.decorView).filterIsInstance<EditText>().first {
                it.hint.toString().startsWith(hint)
            }
        }
        return result
    }

    private fun ActivityScenario<InputTestActivity>.actionCounter(): TextView {
        lateinit var result: TextView
        onActivity { activity ->
            result = descendants(activity.window.decorView).filterIsInstance<TextView>().first {
                it.text.toString().startsWith("入力先への")
            }
        }
        return result
    }

    private fun text(view: TextView): String {
        var result = ""
        instrumentation.runOnMainSync { result = view.text.toString() }
        return result
    }

    private fun awaitText(view: TextView, expected: String) = awaitCondition(
        "本文が一致しません。expected=$expected actual=${text(view)}",
    ) { text(view) == expected }

    private fun awaitCondition(message: String, block: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 7_000
        while (SystemClock.uptimeMillis() < deadline) {
            if (block()) return
            SystemClock.sleep(25)
        }
        fail(message)
    }

    private fun shell(command: String): String = android.os.ParcelFileDescriptor.AutoCloseInputStream(
        automation.executeShellCommand(command),
    ).bufferedReader().use { it.readText() }

    private fun accessibilityDescendants(node: AccessibilityNodeInfo): Sequence<AccessibilityNodeInfo> = sequence {
        yield(node)
        for (index in 0 until node.childCount) {
            node.getChild(index)?.let { yieldAll(accessibilityDescendants(it)) }
        }
    }

    private fun descendants(view: View): List<View> = listOf(view) +
        if (view is ViewGroup) (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()

    private companion object {
        const val SKK_PACKAGE = "se.haya.skk"
    }
}
