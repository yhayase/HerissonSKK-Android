package se.haya.skk.testeditor

import android.accessibilityservice.AccessibilityServiceInfo
import android.graphics.Rect
import android.os.SystemClock
import android.os.Bundle
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiScrollable
import androidx.test.uiautomator.UiSelector
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** 公開設定と実IMEの指操作で10キーの入力・境界・確定を検証します。 */
@RunWith(AndroidJUnit4::class)
class TenKeyInputTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val device = UiDevice.getInstance(instrumentation)
    private val automation = instrumentation.uiAutomation.apply {
        serviceInfo = serviceInfo.apply {
            flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
    }

    @Before fun configureTenKey() {
        enableTouchKeysThroughSettings(instrumentation)
        configureLayout("10キー")
    }

    @After fun restoreQwerty() { device.pressBack(); configureLayout("QWERTY") }

    @Test fun tapRoundTripAndCancellationDoNotDuplicateCharacters() = withEditor { editor, _ ->
        tap("か")
        awaitText(editor, "か")
        gesture("か", listOf(-1 to 0, 0 to 0, 0 to -1))
        awaitText(editor, "かぐ")
        gesture("な", emptyList(), hold = true, cancel = true)
        awaitText(editor, "かぐ")
        composition(editor, false)
    }

    @Test fun directionalExitsApplyReplaceAndCancelWithoutReturningToCenter() = withEditor { editor, _ ->
        gesture("か", listOf(-1 to 0, 0 to -1))
        awaitText(editor, "ぐ")
        gesture("か", listOf(-1 to 0, 0 to -1, 0 to 0))
        awaitText(editor, "ぐ2")
        gesture("か", listOf(-1 to 0, 0 to 0, -1 to 0, 0 to 0))
        awaitText(editor, "ぐ2か")
        gesture("は", listOf(1 to 0, 0 to 1))
        awaitText(editor, "ぐ2かぽ")
        gesture("は", listOf(1 to 0, 0 to 1, 0 to 0))
        awaitText(editor, "ぐ2かぽは")
        composition(editor, false)
    }

    @Test fun shallowBoundaryCrossingKeepsTheConfirmedDirection() = withEditor { editor, _ ->
        device.waitForIdle()
        val source = Rect().also(key("か")::getBoundsInScreen)
        val threshold = maxOf(android.view.ViewConfiguration.get(instrumentation.targetContext).scaledTouchSlop.toFloat(),
            minOf(source.width(), source.height()) * .2f)
        val density = instrumentation.targetContext.resources.displayMetrics.density
        val down = SystemClock.uptimeMillis()
        fun inject(action: Int, dx: Float) {
            val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action,
                source.exactCenterX() + dx, source.exactCenterY(), 0).apply {
                this.source = InputDevice.SOURCE_TOUCHSCREEN
            }
            try { assertTrue(automation.injectInputEvent(event, true)) } finally { event.recycle() }
        }
        inject(MotionEvent.ACTION_DOWN, 0f)
        inject(MotionEvent.ACTION_MOVE, -threshold - 20f * density)
        repeat(3) {
            inject(MotionEvent.ACTION_MOVE, -threshold + .5f * density)
            inject(MotionEvent.ACTION_MOVE, -threshold - .5f * density)
        }
        inject(MotionEvent.ACTION_UP, -threshold + .5f * density)
        awaitText(editor, "か")
        composition(editor, false)
    }

    @Test fun confirmCommitsReadingWithoutChoosingPrediction() = withEditor { editor, scenario ->
        gesture("か", emptyList(), hold = true)
        awaitText(editor, "か")
        key("書く", exact = false)
        tap("確定")
        awaitText(editor, "か")
        composition(editor, false)
        tap("あ")
        awaitText(editor, "かあ")
        assertNoAction(scenario)
    }

    @Test fun candidateOutlinesAndAnnotationDetailsRemainSeparate() = withEditor { editor, scenario ->
        gesture("た", listOf(1 to 0), hold = true)
        gesture("さ", listOf(0 to -1))
        gesture("た", listOf(0 to 1))
        awaitText(editor, "てすと")
        key("候補1")
        capture("candidate-outlines-completion")
        tap("変換")
        key("候補1")
        capture("candidate-outlines-conversion")
        val keyboardBefore = Rect().also(key("ABC")::getBoundsInScreen)
        // フォーカスを持つ注釈窓の背後は読み上げツリーから隠れるため、実ウィンドウを測ります。
        fun imeBounds(): String {
            val windows = device.executeShellCommand("dumpsys window windows")
            val ime = windows.substringAfter(" InputMethod}:").substringBefore("\n  Window #")
            return checkNotNull(Regex("mFrame=(\\[-?\\d+,-?\\d+\\]\\[-?\\d+,-?\\d+\\])")
                .find(ime)).groupValues[1]
        }
        val imeBefore = imeBounds()
        val editorBefore = Rect()
        scenario.onActivity { editor.getGlobalVisibleRect(editorBefore) }
        val bounds = Rect().also(key("候補1")::getBoundsInScreen)
        val down = SystemClock.uptimeMillis()
        fun inject(action: Int) {
            val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action,
                bounds.exactCenterX(), bounds.exactCenterY(), 0).apply {
                source = InputDevice.SOURCE_TOUCHSCREEN
            }
            try { assertTrue(automation.injectInputEvent(event, true)) } finally { event.recycle() }
        }
        inject(MotionEvent.ACTION_DOWN)
        try { SystemClock.sleep(650) } finally { inject(MotionEvent.ACTION_UP) }
        key("注釈", exact = false)
        capture("candidate-outlines-annotation")
        assertEquals(imeBefore, imeBounds())
        val editorAfter = Rect()
        scenario.onActivity { editor.getGlobalVisibleRect(editorAfter) }
        assertEquals(editorBefore, editorAfter)
        composition(editor, true)
        device.pressBack()
        key("候補1")
        composition(editor, true)
        assertEquals(keyboardBefore, Rect().also(key("ABC")::getBoundsInScreen))
    }

    @Test fun backspaceWorksImmediatelyAfterPostInputKanaModifiers() = withEditor { editor, _ ->
        tap("は")
        tap("小゛゜")
        awaitText(editor, "ば")
        tap("⌫")
        awaitText(editor, "")
        tap("は")
        tap("小゛゜")
        tap("小゛゜")
        awaitText(editor, "ぱ")
        tap("⌫")
        awaitText(editor, "")
        gesture("た", listOf(0 to -1))
        tap("小゛゜")
        awaitText(editor, "っ")
        tap("⌫")
        awaitText(editor, "")
        tap("あ")
        awaitText(editor, "あ")
        composition(editor, false)
    }

    @Test fun outsideAnnotationTouchPassesTheFirstTapHoldAndFlick() {
        repeat(3) { operation -> withEditor { editor, _ ->
            gesture("た", listOf(1 to 0), hold = true)
            gesture("さ", listOf(0 to -1))
            gesture("た", listOf(0 to 1))
            tap("変換")
            val bounds = Rect().also(key("候補1")::getBoundsInScreen)
            val down = SystemClock.uptimeMillis()
            fun inject(action: Int) {
                val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action,
                    bounds.exactCenterX(), bounds.exactCenterY(), 0).apply {
                    source = InputDevice.SOURCE_TOUCHSCREEN
                }
                try { assertTrue(automation.injectInputEvent(event, true)) } finally { event.recycle() }
            }
            inject(MotionEvent.ACTION_DOWN)
            try { SystemClock.sleep(650) } finally { inject(MotionEvent.ACTION_UP) }
            key("注釈", exact = false)
            if (operation == 0) capture("annotation-text-only")
            when (operation) {
                0 -> tap("あ")
                1 -> gesture("あ", emptyList(), hold = true)
                else -> gesture("か", listOf(0 to -1))
            }
            awaitText(editor, if (operation == 2) "候補1く" else "候補1あ")
            composition(editor, operation == 1)
            await("注釈窓が閉じません") {
                automation.windows.mapNotNull { it.root }.none { root ->
                    root.findAccessibilityNodeInfosByText("注釈1").any { it.isVisibleToUser }
                }
            }
        } }
    }

    @Test fun sparseRoundTripCrossingsApplyEachModifier() = withEditor { editor, _ ->
        gesture("か", listOf(-1 to 0, 1 to 0))
        awaitText(editor, "げ")
        gesture("は", listOf(1 to 0, -1 to 0))
        awaitText(editor, "げぴ")
        gesture("や", listOf(1 to 0, 0 to -1))
        awaitText(editor, "げぴゅ")
        gesture("か", listOf(0 to -1, 0 to 1))
        awaitText(editor, "げぴゅ2")
        composition(editor, false)
    }

    @Test fun cancellingOkuriRegistrationKeepsAllTextAsPlainReading() = withEditor { editor, scenario ->
        gesture("ら", listOf(-1 to 0), hold = true)
        gesture("や", listOf(0 to 1), hold = true)
        key("単語登録：りy", exact = false)
        tap("取消")
        awaitText(editor, "りよ")
        composition(editor, true)
        tap("あ")
        awaitText(editor, "りよあ")
        composition(editor, true)
        tap("変換")
        key("単語登録：りよあ", exact = false)
        tap("取消")
        awaitText(editor, "りよあ")
        assertNoAction(scenario)
    }

    @Test fun outboundGuidesShowReturnModifierHints() = withEditor { editor, _ ->
        val cases = listOf("か" to (-1 to 0), "は" to (1 to 0), "や" to (1 to 0), "か" to (0 to -1))
        cases.forEachIndexed { index, (label, offset) ->
            device.waitForIdle()
            val source = Rect().also(key(label)::getBoundsInScreen)
            val down = SystemClock.uptimeMillis()
            fun inject(action: Int, moved: Boolean) {
                val distance = source.height() * .7f
                val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action,
                    source.exactCenterX() + if (moved) offset.first * distance else 0f,
                    source.exactCenterY() + if (moved) offset.second * distance else 0f, 0).apply {
                    this.source = InputDevice.SOURCE_TOUCHSCREEN
                }
                try { assertTrue(automation.injectInputEvent(event, true)) } finally { event.recycle() }
            }
            inject(MotionEvent.ACTION_DOWN, false)
            try {
                inject(MotionEvent.ACTION_MOVE, true)
                SystemClock.sleep(64)
                val folder = java.io.File(instrumentation.targetContext.filesDir, "ten-key-roundtrip-okuri").apply { mkdirs() }
                java.io.File(folder, "return-hint-$index.png").outputStream().use {
                    automation.takeScreenshot().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                }
            } finally { inject(MotionEvent.ACTION_CANCEL, true) }
        }
        awaitText(editor, "")
        composition(editor, false)
    }

    @Test fun movingToModifiersAndMovingAfterHoldDoNotStartReading() = withEditor { editor, _ ->
        gesture("か", listOf(-1 to 0, 0 to 0, 0 to -1), stepMillis = 180)
        awaitText(editor, "ぐ")
        composition(editor, false)
        gesture("は", listOf(1 to 0, 0 to 0, 0 to -1), holdBeforePath = true)
        awaitText(editor, "ぐぷ")
        composition(editor, false)
        gesture("や", listOf(1 to 0, 0 to 0), stepMillis = 220)
        awaitText(editor, "ぐぷゃ")
        composition(editor, false)
    }

    @Test fun geometrySlidersCanBeHiddenWithoutCommittingReading() = withEditor { editor, scenario ->
        fun openAdjustment() {
            device.waitForIdle()
            val box = Rect().also(key("IME切替")::getBoundsInScreen)
            assertTrue(device.swipe(box.centerX(), box.centerY(), box.centerX(), box.centerY(), 140))
            key("サイズ・位置を調整")
        }
        // 前回の試験が途中終了しても、公開操作で標準形状から始めます。
        openAdjustment()
        tap("サイズ・位置を標準に戻す")
        tap("調整を閉じる")
        tap("⇧")
        tap("か")
        awaitText(editor, "か")
        composition(editor, true)
        val before = Rect().also(key("か")::getBoundsInScreen)
        val originalBottom = Rect().also(key("わ")::getBoundsInScreen).bottom
        capture("geometry-initial")
        fun setSlider(label: String, fraction: Float) {
            val node = key(label)
            val range = checkNotNull(node.rangeInfo)
            val arguments = Bundle().apply {
                putFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE,
                    range.min + (range.max - range.min) * fraction)
            }
            assertTrue(node.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.id, arguments))
            device.waitForIdle()
        }
        openAdjustment()
        device.waitForIdle()
        val openedBottom = Rect().also(key("わ")::getBoundsInScreen).bottom
        capture("geometry-opened")
        setSlider("10キーの幅", 0f)
        setSlider("10キーの左右位置", 1f)
        setSlider("10キーの高さ", 0f)
        val after = Rect().also(key("か")::getBoundsInScreen)
        assertTrue("幅を狭めてもキー幅が変わりません", after.width() < before.width())
        assertTrue("右寄せしても位置が変わりません", after.left > before.left)
        val bottomBeforeMargin = Rect().also(key("わ")::getBoundsInScreen).bottom
        capture("geometry-resized")
        assertEquals("高さを下げるとキーボードの下端が移動しました（調整開始直後: $openedBottom）", originalBottom, bottomBeforeMargin)
        setSlider("10キーの下の余白", .25f)
        val bottomAfterMargin = Rect().also(key("わ")::getBoundsInScreen).bottom
        assertTrue("下の余白を増やしてもキー位置が変わりません", bottomAfterMargin < bottomBeforeMargin)
        tap("調整を閉じる")
        assertEquals("調整を閉じると下端が移動しました", bottomAfterMargin,
            Rect().also(key("わ")::getBoundsInScreen).bottom)
        awaitText(editor, "か")
        composition(editor, true)
        tap("か")
        awaitText(editor, "かか")
        openAdjustment()
        tap("サイズ・位置を標準に戻す")
        tap("調整を閉じる")
        awaitText(editor, "かか")
        composition(editor, true)
        assertNoAction(scenario)
    }

    @Test fun paletteInsertsEmojiSequencesIntoTheSameReading() = withEditor { editor, scenario ->
        tap("⇧")
        tap("か")
        tap("記号")
        key("記号・絵文字")
        val tabs = automation.windows.asSequence().mapNotNull { it.root }
            .flatMap { descendants(it).asSequence() }
            .first { it.className?.toString() == "android.widget.HorizontalScrollView" }
        repeat(2) { tabs.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD); device.waitForIdle() }
        tap("顔・人")
        tap("笑顔")
        awaitText(editor, "か😀")
        tap("考える顔")
        awaitText(editor, "か😀🤔")
        composition(editor, true)
        tap("記号・絵文字を閉じる")
        gesture("ア", listOf(0 to -1))
        awaitText(editor, "カ😀🤔")
        composition(editor, false)
        assertNoAction(scenario)
    }

    @Test fun flickGuideIsCenteredOnItsSourceIncludingEdgeKeys() = withEditor { editor, _ ->
        for (label in listOf("か", "ア", "わ")) {
            device.waitForIdle()
            val source = Rect().also(key(label)::getBoundsInScreen)
            val down = SystemClock.uptimeMillis()
            fun inject(action: Int) {
                val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action,
                    source.exactCenterX(), source.exactCenterY(), 0).apply { this.source = InputDevice.SOURCE_TOUCHSCREEN }
                try { assertTrue(automation.injectInputEvent(event, true)) } finally { event.recycle() }
            }
            inject(MotionEvent.ACTION_DOWN)
            try {
                SystemClock.sleep(250)
                val diagnostic = java.io.File(instrumentation.targetContext.filesDir, "ten-key-ui-polish").apply { mkdirs() }
                java.io.File(diagnostic, "active-guide.png").outputStream().use {
                    automation.takeScreenshot().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                }
                // ガイドは触れない子窓なので、描画された選択セルで画面上の位置を検証します。
                val bitmap = automation.takeScreenshot()
                val pixels = IntArray(bitmap.width * bitmap.height)
                bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
                val fill = 0xffc7d8ed.toInt()
                var left = bitmap.width
                var top = bitmap.height
                var right = -1
                var bottom = -1
                pixels.forEachIndexed { index, pixel -> if (pixel == fill) {
                    val x = index % bitmap.width
                    val y = index / bitmap.width
                    left = minOf(left, x); right = maxOf(right, x)
                    top = minOf(top, y); bottom = maxOf(bottom, y)
                } }
                assertTrue("画面上にガイドの選択セルがありません: $label", right >= left && bottom >= top)
                assertTrue("ガイドが元キーから離れています: $label", source.contains((left + right) / 2, (top + bottom) / 2))
                assertTrue("ガイドの左右位置が元キーからずれています: $label", kotlin.math.abs(source.exactCenterX() - (left + right + 1) / 2f) <= 1f)
                assertTrue("ガイドの上下位置が元キーからずれています: $label", kotlin.math.abs(source.exactCenterY() - (top + bottom + 1) / 2f) <= 1f)
                val folder = java.io.File(instrumentation.targetContext.filesDir, "ten-key-ui-polish").apply { mkdirs() }
                java.io.File(folder, "guide-${listOf("か", "ア", "わ").indexOf(label)}.png").outputStream().use {
                    automation.takeScreenshot().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                }
            } finally { inject(MotionEvent.ACTION_CANCEL) }
        }
        awaitText(editor, "")
        composition(editor, false)
    }

    @Test fun keyboardAndPaletteFollowSystemNightMode() {
        fun night(enabled: Boolean) {
            android.os.ParcelFileDescriptor.AutoCloseInputStream(
                automation.executeShellCommand("cmd uimode night ${if (enabled) "yes" else "no"}")
            ).use { it.readBytes() }
            device.waitForIdle()
        }
        try {
            for (dark in listOf(false, true)) {
                night(dark)
                withEditor { _, _ ->
                    device.waitForIdle()
                    val character = Rect().also(key("か")::getBoundsInScreen)
                    val function = Rect().also(key("ABC")::getBoundsInScreen)
                    val bitmap = automation.takeScreenshot()
                    assertEquals(if (dark) 0xff42464c.toInt() else android.graphics.Color.WHITE,
                        bitmap.getPixel(character.left + 8, character.top + 8))
                    assertEquals(if (dark) 0xff292d33.toInt() else 0xffe3e6eb.toInt(),
                        bitmap.getPixel(function.left + 8, function.top + 8))
                    capture(if (dark) "keyboard-night" else "keyboard-day")
                    tap("記号")
                    capture(if (dark) "palette-night" else "palette-day")
                    tap("記号・絵文字を閉じる")
                }
            }
        } finally { night(false) }
    }

    @Test fun heldBackspaceWaitsThenRepeatsAndStopsOnRelease() = withEditor { editor, _ ->
        repeat(6) { tap("か") }
        awaitText(editor, "かかかかかか")
        val box = Rect().also(key("⌫")::getBoundsInScreen)
        val down = SystemClock.uptimeMillis()
        fun inject(action: Int) {
            MotionEvent.obtain(down, SystemClock.uptimeMillis(), action,
                box.centerX().toFloat(), box.centerY().toFloat(), 0).apply {
                source = InputDevice.SOURCE_TOUCHSCREEN
            }.also { try { assertTrue(automation.injectInputEvent(it, true)) } finally { it.recycle() } }
        }
        inject(MotionEvent.ACTION_DOWN)
        try {
            awaitText(editor, "かかかかか")
            SystemClock.sleep(160)
            assertEquals("待ち時間より前に繰返し削除しました", "かかかかか", text(editor))
            SystemClock.sleep(440)
        } finally { inject(MotionEvent.ACTION_UP) }
        val released = text(editor)
        assertTrue("長押しで繰返し削除しません", released.length < 5)
        SystemClock.sleep(200)
        assertEquals("指を離しても削除が続きました", released, text(editor))
    }

    @Test fun heldReadingSurvivesFaceChangesAndConvertsWithoutSubmitting() = withEditor { editor, scenario ->
        gesture("な", listOf(-1 to 0), hold = true)
        awaitText(editor, "に")
        composition(editor, true)
        cycleFaces()
        awaitText(editor, "に")
        composition(editor, true)
        gesture("は", listOf(0 to 1))
        gesture("わ", listOf(0 to -1))
        awaitText(editor, "にほん")
        tap("変換")
        key("▶")
        key("日本", exact = false)
        cycleFaces()
        awaitText(editor, "にほん")
        composition(editor, true)
        key("日本", exact = false)
        tap("確定")
        awaitText(editor, "日本")
        composition(editor, false)
        assertNoAction(scenario)
    }

    @Test fun firstHeldKanaShowsCandidatesWithoutSecondCharacter() = withEditor { editor, scenario ->
        gesture("か", emptyList(), hold = true)
        awaitText(editor, "か")
        composition(editor, true)
        key("書く", exact = false)
        assertNoAction(scenario)
    }

    @Test fun fullWidthAlphaSpaceUsesIdeographicSpaceAndKeepsConversion() = withEditor { editor, scenario ->
        tap("ABC")
        tap("全Ａ")
        tap("abc")
        tap("空白")
        awaitText(editor, "ａ　")
        tap("A")
        tap("空白")
        awaitText(editor, "ａ　 ")
        tap("123")
        tap("かな")
        tap("⇧")
        gesture("な", listOf(-1 to 0))
        gesture("は", listOf(0 to 1))
        gesture("わ", listOf(0 to -1))
        awaitText(editor, "ａ　 にほん")
        tap("ABC")
        tap("全Ａ")
        tap("変換")
        key("日本", exact = false)
        tap("確定")
        awaitText(editor, "ａ　 日本")
        composition(editor, false)
        assertNoAction(scenario)
        // 面と幅は入力欄をまたいで保持されるため、次の試験へ標準状態を戻します。
        tap("A")
        tap("123")
        tap("かな")
    }

    @Test fun alphabetCharactersRemainLiteralEvenDuringCandidates() = withEditor { editor, scenario ->
        tap("⇧")
        gesture("な", listOf(-1 to 0))
        gesture("は", listOf(0 to 1))
        gesture("わ", listOf(0 to -1))
        tap("変換")
        key("日本", exact = false)
        tap("ABC")
        gesture("pqrs", listOf(-1 to 0))
        awaitText(editor, "日本q")
        composition(editor, false)
        gesture("wxyz", listOf(-1 to 0))
        gesture("@", listOf(0 to -1))
        tap("a/A")
        gesture("PQRS", listOf(-1 to 0))
        awaitText(editor, "日本qx/Q")
        tap("A/a")
        tap("123")
        tap("かな")
        composition(editor, false)
        assertNoAction(scenario)
    }

    @Test fun kindFlickConfirmsKanaAndPreservesEnglish() {
        for ((direction, expected) in listOf((0 to -1) to "APIカ", (1 to 0) to "APIｶ")) {
            withEditor { editor, scenario ->
                tap("⇧")
                tap("ABC")
                tap("a/A")
                tap("ABC")
                tap("PQRS")
                gesture("GHI", listOf(0 to -1))
                tap("A/a")
                tap("123")
                tap("かな")
                tap("か")
                awaitText(editor, "APIか")
                gesture("ア", listOf(direction))
                awaitText(editor, expected)
                composition(editor, false)
                assertNoAction(scenario)
            }
        }
    }

    private fun configureLayout(layout: String) {
        device.executeShellCommand("am start -W -a android.intent.action.VIEW -f 0x14000000 -n se.haya.skk/.SettingsActivity")
        device.waitForIdle()
        if (!device.findObject(UiSelector().text("画面キーボード")).waitForExists(1_000)) {
            UiScrollable(UiSelector().scrollable(true)).scrollIntoView(UiSelector().text("画面キーボード"))
        }
        assertTrue(device.findObject(UiSelector().text("画面キーボード")).click())
        fun select(title: String, option: String) {
            assertTrue(device.findObject(UiSelector().text(title)).click())
            assertTrue(device.findObject(UiSelector().text(option)).click())
        }
        select("配列", layout)
        if (layout == "10キー") {
            select("10キーの操作", "ホールド＋往復")
            select("ホールド判定", "標準（350 ms）")
        }
        assertTrue(device.findObject(UiSelector().text("保存").className("android.widget.Button")).click())
        device.waitForIdle()
        await("設定の保存が完了しません") { device.findObject(UiSelector().text("設定")).exists() }
    }

    private fun cycleFaces() { listOf("ABC", "123", "かな").forEach(::tap) }

    private fun tap(label: String) {
        device.waitForIdle()
        val box = Rect().also(key(label)::getBoundsInScreen)
        assertTrue(device.click(box.centerX(), box.centerY()))
        instrumentation.waitForIdleSync()
    }

    private fun gesture(label: String, path: List<Pair<Int, Int>>, hold: Boolean = false, cancel: Boolean = false,
        holdBeforePath: Boolean = false, stepMillis: Long = 0) {
        device.waitForIdle()
        val box = Rect().also(key(label)::getBoundsInScreen)
        val distance = box.height() * .7f
        val down = SystemClock.uptimeMillis()
        var position = 0 to 0
        fun inject(action: Int) {
            val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action,
                box.centerX() + position.first * distance, box.centerY() + position.second * distance, 0).apply {
                source = InputDevice.SOURCE_TOUCHSCREEN
            }
            try { assertTrue(automation.injectInputEvent(event, true)) } finally { event.recycle() }
        }
        inject(MotionEvent.ACTION_DOWN)
        try {
            if (holdBeforePath) SystemClock.sleep(450)
            path.forEach {
                if (stepMillis > 0) SystemClock.sleep(stepMillis)
                position = it
                inject(MotionEvent.ACTION_MOVE)
            }
            if (hold) {
                // フレームワークの移動予測を静止サンプルで収束させてからホールドを測ります。
                repeat(3) {
                    SystemClock.sleep(32)
                    inject(MotionEvent.ACTION_MOVE)
                }
                SystemClock.sleep(450)
            }
            inject(if (cancel) MotionEvent.ACTION_CANCEL else MotionEvent.ACTION_UP)
        } catch (error: Throwable) {
            inject(MotionEvent.ACTION_CANCEL)
            throw error
        }
        instrumentation.waitForIdleSync()
    }

    private fun key(label: String, exact: Boolean = true): AccessibilityNodeInfo {
        var result: AccessibilityNodeInfo? = null
        await("IMEに「$label」がありません") {
            result = automation.windows.asSequence().mapNotNull { it.root }
                .filter { it.packageName?.toString() == "se.haya.skk" }
                .flatMap { it.findAccessibilityNodeInfosByText(label).asSequence() }
                .firstOrNull { node -> node.isVisibleToUser && listOf(node.text, node.contentDescription).any {
                    if (exact) it?.toString() == label else it?.toString()?.startsWith(label) == true
                } }
            result != null
        }
        return checkNotNull(result)
    }

    private fun withEditor(block: (EditText, ActivityScenario<InputTestActivity>) -> Unit) {
        ActivityScenario.launch<InputTestActivity>(android.content.Intent(instrumentation.targetContext,
            InputTestActivity::class.java).putExtra(InputTestActivity.EXTRA_SUPPRESS_LEARNING, true)).use { scenario ->
            lateinit var editor: EditText
            scenario.onActivity { activity ->
                editor = descendants(activity.window.decorView).filterIsInstance<EditText>()
                    .first { it.hint.toString().startsWith("送信") }
                editor.requestFocus()
                activity.getSystemService(InputMethodManager::class.java).showSoftInput(editor, InputMethodManager.SHOW_IMPLICIT)
            }
            key("ABC")
            try { block(editor, scenario) } catch (error: Throwable) {
                val folder = java.io.File(instrumentation.targetContext.filesDir, "ten-key-ui-polish").apply { mkdirs() }
                java.io.File(folder, "failure.png").outputStream().use {
                    automation.takeScreenshot().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                }
                device.dumpWindowHierarchy(java.io.File(folder, "failure.xml"))
                throw error
            }
        }
    }

    private fun capture(name: String) {
        val folder = java.io.File(instrumentation.targetContext.filesDir, "ten-key-ui-polish").apply { mkdirs() }
        java.io.File(folder, "$name.png").outputStream().use {
            automation.takeScreenshot().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
        device.dumpWindowHierarchy(java.io.File(folder, "$name.xml"))
        val descriptor = automation.executeShellCommand("dumpsys window windows")
        android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { input ->
            java.io.File(folder, "$name-window.txt").outputStream().use(input::copyTo)
        }
    }

    private fun assertNoAction(scenario: ActivityScenario<InputTestActivity>) {
        scenario.onActivity { assertEquals("確定で入力先へアクションを送りました", 0, it.editorActionCount) }
    }
    private fun text(editor: EditText): String {
        var value = ""
        instrumentation.runOnMainSync { value = editor.text.toString() }
        return value
    }
    private fun awaitText(editor: EditText, expected: String) = await("本文が一致しません: $expected") { text(editor) == expected }
    private fun composition(editor: EditText, expected: Boolean) = await("未確定範囲が一致しません: $expected") {
        var actual = false
        instrumentation.runOnMainSync { actual = BaseInputConnection.getComposingSpanStart(editor.text) >= 0 }
        actual == expected
    }
    private fun await(message: String, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 8000
        while (!condition()) {
            check(SystemClock.uptimeMillis() < deadline) { message }
            SystemClock.sleep(25)
        }
    }
    private fun descendants(node: AccessibilityNodeInfo): List<AccessibilityNodeInfo> = listOf(node) +
        (0 until node.childCount).flatMap { index -> node.getChild(index)?.let(::descendants).orEmpty() }

    private fun descendants(view: View): List<View> = listOf(view) +
        if (view is ViewGroup) (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()
}
