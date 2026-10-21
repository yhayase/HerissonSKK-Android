package se.haya.skk.input

import android.app.Activity
import org.robolectric.Robolectric
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import org.junit.Assert.*
import org.junit.Test
import org.junit.Before
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import se.haya.skk.R
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.concurrent.TimeUnit
import se.haya.skk.core.BasicSkkAction
import se.haya.skk.core.BasicSkkEngine
import se.haya.skk.core.InputMode
import se.haya.skk.core.InputPhase
import se.haya.skk.settings.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26])
class TenKeyKeyboardViewTest {
    @Before fun resetReadingModes() {
        assertTrue(TenKeyReadingModeStore.save(RuntimeEnvironment.getApplication(), TenKeyReadingModes()))
    }
    private fun toggleAuto(view: TenKeyKeyboardView) {
        assertTrue(key(view, "⇧").performAccessibilityAction(R.id.toggle_ten_key_reading, null))
    }
    private fun longPress(button: Button) {
        assertTrue(button.isAttachedToWindow)
        touch(button, MotionEvent.ACTION_DOWN)
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(android.view.ViewConfiguration.getLongPressTimeout().toLong(), TimeUnit.MILLISECONDS)
        touch(button, MotionEvent.ACTION_UP)
    }
    private fun connectedView(settings: TouchKeyboardSettings = TouchKeyboardSettings(),
        controller: TenKeyController = TenKeyController(),
        commands: MutableList<TouchKeyboardCommand> = mutableListOf()): Pair<TenKeyKeyboardView, BasicSkkEngine> {
        val engine = BasicSkkEngine { emptyList() }
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        lateinit var view: TenKeyKeyboardView
        view = TenKeyKeyboardView(activity, settings, controller) { command ->
            commands += command
            if (command is TouchKeyboardCommand.Skk) {
                engine.dispatch(command.action)
                view.updateShiftPending(engine.state.touchShiftPending)
            }
        }
        attachView(activity, view)
        return view to engine
    }
    private fun attachView(activity: Activity, view: TenKeyKeyboardView) {
        activity.setContentView(view)
        view.measure(View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(320, View.MeasureSpec.EXACTLY))
        view.layout(0, 0, 600, 320)
        assertTrue(view.isAttachedToWindow)
    }
    private fun buttons(view: View): List<Button> = when(view) { is Button -> listOf(view); is ViewGroup -> (0 until view.childCount).flatMap { buttons(view.getChildAt(it)) }; else -> emptyList() }
    private fun key(view: View, label: String) = buttons(view).first { it.text.toString() == label }
    private fun touch(button: Button, action: Int, x: Float = 30f, y: Float = 30f) {
        MotionEvent.obtain(SystemClock.uptimeMillis(),SystemClock.uptimeMillis(),action,x,y,0).also { button.dispatchTouchEvent(it); it.recycle() }
    }
    @Test @Config(sdk = [26, 35]) fun `全操作方式と面で長押しだけが自動開始を切り替える`() {
        for (operation in TenKeyOperation.entries) {
            for (face in TenKeyController.Face.entries) {
                TenKeyReadingModeStore.save(RuntimeEnvironment.getApplication(), TenKeyReadingModes())
                val commands = mutableListOf<TouchKeyboardCommand>()
                val controller = TenKeyController().apply { repeat(face.ordinal) { cycleFace() } }
                val (view, engine) = connectedView(TouchKeyboardSettings(operation = operation), controller, commands)
                val shift = key(view, "⇧") as ReadingModeButton
                touch(shift, MotionEvent.ACTION_DOWN)
                Shadows.shadowOf(Looper.getMainLooper()).idleFor((android.view.ViewConfiguration.getLongPressTimeout() - 1).toLong(), TimeUnit.MILLISECONDS)
                touch(shift, MotionEvent.ACTION_UP)
                assertTrue(shift.showShift)
                assertFalse(shift.showAuto)
                assertEquals(BasicSkkAction.StartTouchReading(InputMode.HIRAGANA), (commands.single() as TouchKeyboardCommand.Skk).action)
                touch(shift, MotionEvent.ACTION_DOWN)
                touch(shift, MotionEvent.ACTION_UP)
                assertFalse(shift.showAuto)
                assertEquals(2, commands.size)
                assertEquals(InputPhase.READING, engine.state.phase)
                assertEquals("", engine.state.reading)
                longPress(shift)
                assertTrue(shift.isSelected)
                assertTrue(shift.showAuto)
                assertFalse(shift.showShift)
                assertFalse(engine.state.touchShiftPending)
                assertEquals(InputPhase.READING, engine.state.phase)
                assertEquals("", engine.state.reading)
                assertEquals(2, commands.size)
                longPress(shift)
                assertFalse(shift.showAuto)
                assertEquals(listOf(BasicSkkAction.StartTouchReading(InputMode.HIRAGANA), BasicSkkAction.StartTouchReading(InputMode.HIRAGANA)),
                    commands.map { (it as TouchKeyboardCommand.Skk).action })
            }
        }
    }

    @Test fun `通常状態からの長押しは空の読みを開始せず離しても入力しない`() {
        val commands = mutableListOf<TouchKeyboardCommand>()
        val (view, engine) = connectedView(commands = commands)
        val shift = key(view, "⇧") as ReadingModeButton
        touch(shift, MotionEvent.ACTION_DOWN)
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(
            android.view.ViewConfiguration.getLongPressTimeout().toLong(), TimeUnit.MILLISECONDS)
        assertTrue(shift.showAuto)
        assertFalse(shift.showShift)
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(
            3L * android.view.ViewConfiguration.getLongPressTimeout(), TimeUnit.MILLISECONDS)
        assertTrue(shift.showAuto)
        assertEquals(InputPhase.IDLE, engine.state.phase)
        assertTrue(commands.isEmpty())
        touch(shift, MotionEvent.ACTION_UP)
        assertEquals(InputPhase.IDLE, engine.state.phase)
        assertEquals("", engine.state.reading)
        assertTrue(commands.isEmpty())
    }

    @Test fun `文字入力後のタップで送りを付け自動開始中も送りを維持する`() {
        val commands = mutableListOf<TouchKeyboardCommand>()
        val (view, engine) = connectedView(commands = commands)
        val shift = key(view, "⇧") as ReadingModeButton
        shift.performClick()
        key(view, "か").performClick()
        assertFalse(shift.showShift)
        assertEquals("か", engine.state.reading)
        shift.performClick()
        assertTrue(shift.showShift)
        assertFalse(shift.showAuto)
        longPress(shift)
        assertTrue(shift.showAuto)
        assertFalse(shift.showShift)
        assertFalse(engine.state.touchShiftPending)
        assertEquals(BasicSkkAction.CancelTouchShift, (commands.last() as TouchKeyboardCommand.Skk).action)
        key(view, "か").performClick()
        assertEquals("かか", engine.state.reading)
        shift.performClick()
        key(view, "か").performClick()
        assertFalse(shift.showShift)
        assertTrue(shift.showAuto)
        assertEquals("か", engine.state.okuri)
    }

    @Test fun `面ごとの状態は文字種変更とビュー再生成でも共有され独立する`() {
        val context = RuntimeEnvironment.getApplication() as android.content.Context
        val commands = mutableListOf<TouchKeyboardCommand>()
        val view = TenKeyKeyboardView(context, TouchKeyboardSettings()) { commands += it }
        toggleAuto(view)
        key(view, "ア").performClick()
        assertTrue(key(view, "⇧").isSelected)
        key(view, "カ").performClick()
        key(view, "ABC").performClick()
        assertFalse(key(view, "⇧").isSelected)
        key(view, "abc").performClick()
        key(view, "123").performClick()
        toggleAuto(view)
        key(view, "全１").performClick()
        assertTrue(key(view, "⇧").isSelected)
        key(view, "1").performClick()
        key(view, "かな").performClick()
        assertTrue(key(view, "⇧").isSelected)
        val recreated = TenKeyKeyboardView(context, TouchKeyboardSettings(), view.controller) {}
        assertTrue(key(recreated, "⇧").isSelected)
        key(recreated, "ABC").performClick()
        assertFalse(key(recreated, "⇧").isSelected)
        key(recreated, "全Ａ").performClick()
        assertFalse(key(recreated, "⇧").isSelected)
        key(recreated, "123").performClick()
        assertTrue(key(recreated, "⇧").isSelected)
        val texts = commands.mapNotNull { (it as TouchKeyboardCommand.Skk).action as? BasicSkkAction.TouchText }
        assertEquals(listOf(true, false, true), texts.map { it.autoStartReading })
    }

    @Test fun `中断や面変更後の古いシフトからは切替も読み開始も送らない`() {
        val commands = mutableListOf<TouchKeyboardCommand>()
        val view = TenKeyKeyboardView(RuntimeEnvironment.getApplication(), TouchKeyboardSettings()) { commands += it }
        for (cancel in listOf<(Button) -> Unit>(
            { touch(it, MotionEvent.ACTION_CANCEL) },
            { touch(it, MotionEvent.ACTION_POINTER_DOWN) },
            { view.cancelTouches() },
        )) {
            val shift = key(view, "⇧")
            touch(shift, MotionEvent.ACTION_DOWN)
            touch(shift, MotionEvent.ACTION_MOVE, 30f, -40f)
            cancel(shift)
            touch(shift, MotionEvent.ACTION_UP, 30f, -40f)
            assertFalse(shift.isSelected)
            assertTrue(commands.isEmpty())
        }
        val oldShift = key(view, "⇧")
        touch(oldShift, MotionEvent.ACTION_DOWN)
        key(view, "ABC").performClick()
        touch(oldShift, MotionEvent.ACTION_UP, 30f, -40f)
        assertEquals(TenKeyReadingModes(), TenKeyReadingModeStore.read(RuntimeEnvironment.getApplication()))
        assertTrue(commands.isEmpty())
        val currentShift = key(view, "⇧")
        touch(currentShift, MotionEvent.ACTION_DOWN)
        view.dispose()
        touch(currentShift, MotionEvent.ACTION_UP, 30f, -40f)
        assertTrue(commands.isEmpty())
    }

    @Test fun `移動キャンセル複数指面変更破棄保護欄では長押しタイマーを破棄する`() {
        val commands = mutableListOf<TouchKeyboardCommand>()
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val view = TenKeyKeyboardView(activity, TouchKeyboardSettings()) { commands += it }
        attachView(activity, view)
        val shift = key(view, "⇧")
        longPress(shift)
        assertTrue(shift.isSelected)
        longPress(shift)
        assertFalse(shift.isSelected)
        assertTrue(commands.isEmpty())
        shift.layout(0, 0, 100, 100)
        touch(shift, MotionEvent.ACTION_DOWN, 50f, 50f)
        touch(shift, MotionEvent.ACTION_MOVE, 80f, 50f)
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(android.view.ViewConfiguration.getLongPressTimeout().toLong(), TimeUnit.MILLISECONDS)
        touch(shift, MotionEvent.ACTION_UP, 80f, 50f)
        assertFalse(shift.isSelected)
        touch(shift, MotionEvent.ACTION_DOWN)
        touch(shift, MotionEvent.ACTION_CANCEL)
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(android.view.ViewConfiguration.getLongPressTimeout().toLong(), TimeUnit.MILLISECONDS)
        assertFalse(shift.isSelected)
        touch(shift, MotionEvent.ACTION_DOWN)
        touch(shift, MotionEvent.ACTION_POINTER_DOWN)
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(android.view.ViewConfiguration.getLongPressTimeout().toLong(), TimeUnit.MILLISECONDS)
        assertFalse(shift.isSelected)
        touch(shift, MotionEvent.ACTION_DOWN)
        view.setSkkEnabled(false)
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(android.view.ViewConfiguration.getLongPressTimeout().toLong(), TimeUnit.MILLISECONDS)
        assertFalse(shift.isSelected)
        view.setSkkEnabled(true)
        touch(shift, MotionEvent.ACTION_DOWN)
        view.cancelTouches()
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(android.view.ViewConfiguration.getLongPressTimeout().toLong(), TimeUnit.MILLISECONDS)
        assertFalse(shift.isSelected)
        touch(shift, MotionEvent.ACTION_DOWN)
        key(view, "ABC").performClick()
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(android.view.ViewConfiguration.getLongPressTimeout().toLong(), TimeUnit.MILLISECONDS)
        assertTrue(TenKeyReadingModeStore.read(RuntimeEnvironment.getApplication()) == TenKeyReadingModes())
        val current = key(view, "⇧")
        touch(current, MotionEvent.ACTION_DOWN)
        view.dispose()
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(android.view.ViewConfiguration.getLongPressTimeout().toLong(), TimeUnit.MILLISECONDS)
        assertTrue(commands.isEmpty())
        assertTrue(TenKeyReadingModeStore.read(RuntimeEnvironment.getApplication()) == TenKeyReadingModes())
    }

    @Test fun `シフトのドラッグは全方向で復帰や履歴も含めタップを取り消す`() {
        val commands = mutableListOf<TouchKeyboardCommand>()
        val (view, _) = connectedView(commands = commands)
        val shift = key(view, "⇧") as ReadingModeButton
        shift.layout(0, 0, 100, 100)
        for (pending in listOf(false, true)) {
            view.updateShiftPending(pending)
            for ((x, y) in listOf(50f to 0f, 0f to 50f, 100f to 50f, 50f to 100f)) {
                touch(shift, MotionEvent.ACTION_DOWN, 50f, 50f)
                touch(shift, MotionEvent.ACTION_MOVE, x, y)
                touch(shift, MotionEvent.ACTION_UP, 50f, 50f)
                assertTrue(commands.isEmpty())
                assertNull(view.pendingGuide)
                assertFalse(shift.showAuto)
                assertEquals(pending, shift.showShift)
                touch(shift, MotionEvent.ACTION_DOWN, 50f, 50f)
                val time = SystemClock.uptimeMillis()
                MotionEvent.obtain(time, time, MotionEvent.ACTION_MOVE, x, y, 0).also {
                    it.addBatch(time + 1, 50f, 50f, 1f, 1f, 0)
                    shift.dispatchTouchEvent(it); it.recycle()
                }
                touch(shift, MotionEvent.ACTION_UP, 50f, 50f)
                assertTrue(commands.isEmpty())
            }
        }
    }

    @Test fun `数字修飾も実際の文字種ではなく元の面の自動開始状態を使う`() {
        TenKeyReadingModeStore.save(RuntimeEnvironment.getApplication(), TenKeyReadingModes(kana = true))
        val commands = mutableListOf<TouchKeyboardCommand>()
        val view = TenKeyKeyboardView(RuntimeEnvironment.getApplication(), TouchKeyboardSettings(operation = TenKeyOperation.HOLD_AND_RETURN)) { commands += it }
        fun digit(label: String) {
            val button = key(view, label)
            button.layout(0, 0, 100, 80)
            touch(button, MotionEvent.ACTION_DOWN, 50f, 40f)
            touch(button, MotionEvent.ACTION_MOVE, 50f, -40f)
            touch(button, MotionEvent.ACTION_MOVE, 50f, 40f)
            touch(button, MotionEvent.ACTION_UP, 50f, 40f)
        }
        digit("か")
        key(view, "ABC").performClick()
        digit("abc")
        val texts = commands.mapNotNull { (it as TouchKeyboardCommand.Skk).action as? BasicSkkAction.TouchText }
        assertEquals(listOf("2", "2"), texts.map { it.text })
        assertEquals(listOf(true, false), texts.map { it.autoStartReading })
    }

    @Test fun `保護欄は状態を保存したまま表示と読み操作を無効にする`() {
        val commands = mutableListOf<TouchKeyboardCommand>()
        val view = TenKeyKeyboardView(RuntimeEnvironment.getApplication(), TouchKeyboardSettings()) { commands += it }
        toggleAuto(view)
        view.setSkkEnabled(false)
        val shift = key(view, "⇧") as ReadingModeButton
        assertFalse(shift.isEnabled)
        assertFalse(shift.isSelected)
        assertFalse(shift.showAuto)
        touch(shift, MotionEvent.ACTION_DOWN)
        touch(shift, MotionEvent.ACTION_UP)
        shift.performClick()
        assertFalse(shift.performAccessibilityAction(R.id.toggle_ten_key_reading, null))
        assertTrue(commands.isEmpty())
        key(view, "か").performClick()
        assertFalse(((commands.single() as TouchKeyboardCommand.Skk).action as BasicSkkAction.TouchText).autoStartReading)
        assertTrue(TenKeyReadingModeStore.read(RuntimeEnvironment.getApplication()).kana)
        view.setSkkEnabled(true)
        assertTrue(shift.isSelected)
        assertTrue(shift.showAuto)
    }

    @Test fun `アクセシビリティは現在面の状態と自動開始切替操作を提供する`() {
        val commands = mutableListOf<TouchKeyboardCommand>()
        val view = TenKeyKeyboardView(RuntimeEnvironment.getApplication(), TouchKeyboardSettings()) { commands += it }
        val shift = key(view, "⇧")
        val info = shift.createAccessibilityNodeInfo()
        assertTrue(shift.contentDescription.contains("長押し"))
        assertTrue(AccessibilityNodeInfoCompat.wrap(info).stateDescription.toString().contains("長押しで自動読み開始切替"))
        assertTrue(AccessibilityNodeInfoCompat.wrap(info).stateDescription.toString().contains("かな面、自動読み開始オフ"))
        assertTrue(info.actionList.any { it.id == R.id.toggle_ten_key_reading })
        assertTrue(shift.performAccessibilityAction(R.id.toggle_ten_key_reading, null))
        assertTrue(shift.isSelected)
        assertTrue(commands.isEmpty())
        view.updateShiftPending(true)
        assertTrue(androidx.core.view.ViewCompat.getStateDescription(shift).toString().contains("シフトオン"))
        assertTrue(shift.performAccessibilityAction(R.id.toggle_ten_key_reading, null))
        assertEquals(listOf(TouchKeyboardCommand.Skk(BasicSkkAction.CancelTouchShift)), commands)
        info.recycle()
    }

    @Test fun `自動開始設定は全ての文字面にだけ付け保護欄では付けない`() {
        for (enabled in listOf(false, true)) {
            for (protected in listOf(false, true)) {
                val commands = mutableListOf<TouchKeyboardCommand>()
                TenKeyReadingModeStore.save(RuntimeEnvironment.getApplication(), TenKeyReadingModes(enabled, enabled, enabled))
                val view = TenKeyKeyboardView(RuntimeEnvironment.getApplication(), TouchKeyboardSettings()) { commands += it }
                view.setSkkEnabled(!protected)
                key(view, "か").performClick()
                key(view, "、").performClick()
                key(view, "ABC").performClick()
                key(view, "abc").performClick()
                key(view, "123").performClick()
                key(view, "1").performClick()
                val texts = commands.mapNotNull { (it as TouchKeyboardCommand.Skk).action as? BasicSkkAction.TouchText }
                assertEquals(listOf("か", "、", "a", "1"), texts.map { it.text })
                assertTrue(texts.all { it.autoStartReading == (enabled && !protected) && !it.boundary })
            }
        }
    }

    @Test fun `パレットの記号と絵文字も自動開始設定を引き継ぐ`() {
        for (face in TenKeyController.Face.entries) {
            for (enabled in listOf(false, true)) {
                for (protected in listOf(false, true)) {
                    val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
                    val commands = mutableListOf<TouchKeyboardCommand>()
                    TenKeyReadingModeStore.save(activity, TenKeyReadingModes(kana = enabled && face == TenKeyController.Face.KANA, alpha = enabled && face == TenKeyController.Face.ALPHA, number = enabled && face == TenKeyController.Face.NUMBER))
                    val view = TenKeyKeyboardView(activity, TouchKeyboardSettings()) { commands += it }
                    activity.setContentView(view)
                    if (face != TenKeyController.Face.KANA) key(view, "ABC").performClick()
                    if (face == TenKeyController.Face.NUMBER) key(view, "123").performClick()
                    view.setSkkEnabled(!protected)
                    key(view, "記号").performClick()
                    val paletteField = TenKeyKeyboardView::class.java.getDeclaredField("palette").apply { isAccessible = true }
                    val palette = paletteField.get(view) as CharacterPalette
                    val root = palette.popup!!.contentView
                    fun findGrid(node: View): android.widget.GridView? = when (node) {
                        is android.widget.GridView -> node
                        is ViewGroup -> (0 until node.childCount).firstNotNullOfOrNull { findGrid(node.getChildAt(it)) }
                        else -> null
                    }
                    val grid = findGrid(root)!!
                    for ((category, value) in listOf("記号" to "@", "顔・人" to "👩‍💻")) {
                        key(root, category).performClick()
                        val index = CharacterPalette.CATEGORIES.single { it.label == category }.entries.indexOfFirst { it.value == value }
                        (grid.adapter.getView(index, null, grid) as Button).performClick()
                        assertEquals(TouchKeyboardCommand.Skk(BasicSkkAction.TouchText(value,
                            autoStartReading = enabled && !protected)), commands.last())
                    }
                    palette.dismiss()
                }
            }
        }
    }

    @Test fun `空白コマンドは全角英字面だけ全角空白を指定する`() {
        val commands = mutableListOf<TouchKeyboardCommand>()
        val view = TenKeyKeyboardView(RuntimeEnvironment.getApplication(), TouchKeyboardSettings()) { commands += it }

        key(view, "␣").performClick()
        key(view, "ABC").performClick()
        key(view, "␣").performClick()
        key(view, "全Ａ").performClick()
        key(view, "␣").performClick()
        key(view, "123").performClick()
        key(view, "␣").performClick()

        assertEquals(
            listOf(false, false, true, false),
            commands.map { (it as TouchKeyboardCommand.SpaceOrConvert).fullWidthSpace },
        )
    }
    @Test fun `設定で実際の四行五列を選び面と文字種の操作では送信しない`() {
        val commands = mutableListOf<TouchKeyboardCommand>()
        val view = TouchKeyboardView(RuntimeEnvironment.getApplication()) { commands += it }
        view.configure(TouchKeyboardSettings(layout = ScreenKeyboardLayout.TEN_KEY))
        val area = (view.getChildAt(1) as ViewGroup).getChildAt(0) as ViewGroup
        assertEquals(4,area.childCount)
        for(i in 0..3) assertEquals(5,(area.getChildAt(i) as ViewGroup).childCount)
        key(view,"ア").performClick(); key(view,"ABC").performClick(); key(view,"全Ａ").performClick()
        assertTrue(commands.isEmpty())
        key(view,"abc").performClick()
        assertEquals(listOf(TouchKeyboardCommand.Skk(BasicSkkAction.TouchText("ａ",kanaMode=InputMode.KATAKANA))),commands)
    }
    @Test fun `素早い通常フリックと修飾往復を一度だけ送る`() {
        val commands = mutableListOf<TouchKeyboardCommand>()
        val view = TenKeyKeyboardView(RuntimeEnvironment.getApplication(),TouchKeyboardSettings()) { commands += it }
        val key = key(view,"か")
        touch(key,MotionEvent.ACTION_DOWN); touch(key,MotionEvent.ACTION_MOVE,0f,30f); touch(key,MotionEvent.ACTION_MOVE); touch(key,MotionEvent.ACTION_UP,30f,0f)
        assertEquals(listOf(TouchKeyboardCommand.Skk(BasicSkkAction.TouchText("ぐ"))),commands)
        assertNull(view.pendingGuide)
    }
    @Test fun `ホールド後の修飾操作で境界指定を取り消す`() {
        val commands = mutableListOf<TouchKeyboardCommand>()
        val view = TenKeyKeyboardView(RuntimeEnvironment.getApplication(),TouchKeyboardSettings()) { commands += it }
        Robolectric.buildActivity(Activity::class.java).setup().get().setContentView(view)
        val key = key(view,"は"); touch(key,MotionEvent.ACTION_DOWN)
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(350,TimeUnit.MILLISECONDS)
        assertTrue(view.pendingGuide!!.contains("境界"))
        touch(key,MotionEvent.ACTION_MOVE,60f,30f); touch(key,MotionEvent.ACTION_MOVE); touch(key,MotionEvent.ACTION_UP,30f,0f)
        assertEquals(listOf(TouchKeyboardCommand.Skk(BasicSkkAction.TouchText("ぷ"))),commands)
    }
    @Test fun `静止した通常文字と最終母音だけで境界を指定する`() {
        val commands = mutableListOf<TouchKeyboardCommand>()
        val view = TenKeyKeyboardView(RuntimeEnvironment.getApplication(), TouchKeyboardSettings()) { commands += it }
        Robolectric.buildActivity(Activity::class.java).setup().get().setContentView(view)
        val key = key(view, "か")
        touch(key, MotionEvent.ACTION_DOWN)
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(350, TimeUnit.MILLISECONDS)
        touch(key, MotionEvent.ACTION_UP)
        assertEquals(TouchKeyboardCommand.Skk(BasicSkkAction.TouchText("か", true)), commands.last())
        touch(key, MotionEvent.ACTION_DOWN)
        touch(key, MotionEvent.ACTION_MOVE, 30f, 0f)
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(350, TimeUnit.MILLISECONDS)
        touch(key, MotionEvent.ACTION_UP, 30f, 0f)
        assertEquals(TouchKeyboardCommand.Skk(BasicSkkAction.TouchText("く", true)), commands.last())
    }
    @Test fun `長時間動き続ける同方向フリックは境界にならない`() {
        val commands = mutableListOf<TouchKeyboardCommand>()
        val view = TenKeyKeyboardView(RuntimeEnvironment.getApplication(), TouchKeyboardSettings()) { commands += it }
        Robolectric.buildActivity(Activity::class.java).setup().get().setContentView(view)
        val key = key(view, "か")
        touch(key, MotionEvent.ACTION_DOWN)
        repeat(5) { index ->
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(100, TimeUnit.MILLISECONDS)
            touch(key, MotionEvent.ACTION_MOVE, 30f, 10f - index * 20f)
        }
        touch(key, MotionEvent.ACTION_UP, 30f, -90f)
        assertEquals(TouchKeyboardCommand.Skk(BasicSkkAction.TouchText("く")), commands.single())
    }
    @Test fun `離した座標と履歴の移動もホールドを取り消す`() {
        val commands = mutableListOf<TouchKeyboardCommand>()
        val view = TenKeyKeyboardView(RuntimeEnvironment.getApplication(), TouchKeyboardSettings()) { commands += it }
        Robolectric.buildActivity(Activity::class.java).setup().get().setContentView(view)
        val key = key(view, "か")
        touch(key, MotionEvent.ACTION_DOWN)
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(350, TimeUnit.MILLISECONDS)
        touch(key, MotionEvent.ACTION_UP, 30f, 0f)
        assertEquals(TouchKeyboardCommand.Skk(BasicSkkAction.TouchText("く")), commands.last())
        touch(key, MotionEvent.ACTION_DOWN)
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(350, TimeUnit.MILLISECONDS)
        val time = SystemClock.uptimeMillis()
        MotionEvent.obtain(time, time, MotionEvent.ACTION_MOVE, 0f, 30f, 0).also {
            it.addBatch(time + 1, 30f, 30f, 1f, 1f, 0)
            key.dispatchTouchEvent(it); it.recycle()
        }
        touch(key, MotionEvent.ACTION_UP)
        assertEquals(TouchKeyboardCommand.Skk(BasicSkkAction.TouchText("が")), commands.last())
    }
    @Test fun `調整は文字種を保ち進行中の接触を取り消して永続化する`() {
        val context = RuntimeEnvironment.getApplication()
        val commands = mutableListOf<TouchKeyboardCommand>()
        val view = TouchKeyboardView(context) { commands += it }
        view.configure(TouchKeyboardSettings(layout = ScreenKeyboardLayout.TEN_KEY))
        key(view, "ア").performClick()
        val old = key(view, "カ")
        touch(old, MotionEvent.ACTION_DOWN)
        buttons(view).first { it.contentDescription == "IME切替" }.performLongClick()
        assertTrue(view.adjustingGeometry)
        view.setTenKeyGeometry(300, 70, 100)
        touch(old, MotionEvent.ACTION_UP)
        assertTrue(commands.isEmpty())
        assertEquals(300, TouchKeyboardSettingsStore.read(context).tenKeyHeightDp)
        assertEquals(70, TouchKeyboardSettingsStore.read(context).tenKeyWidthPercent)
        assertEquals(100, TouchKeyboardSettingsStore.read(context).tenKeyPositionPercent)
        view.measure(View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.AT_MOST))
        view.layout(0, 0, view.measuredWidth, view.measuredHeight)
        val keyboard = (view.getChildAt(2) as ViewGroup).getChildAt(0)
        assertEquals(420, keyboard.measuredWidth)
        assertEquals(180, keyboard.left)
        assertEquals(600, view.candidateStatusView.measuredWidth)
        assertTrue(view.measuredHeight <= 400)
        val firstRow = (keyboard as ViewGroup).getChildAt(0) as ViewGroup
        assertEquals(firstRow.getChildAt(0).right, firstRow.getChildAt(1).left)
        assertEquals(0, firstRow.getChildAt(0).background.minimumWidth)
        val recreated = TouchKeyboardView(context) {}
        recreated.configure(TouchKeyboardSettingsStore.read(context).copy(layout = ScreenKeyboardLayout.TEN_KEY))
        recreated.measure(View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.AT_MOST))
        assertEquals(420, ((recreated.getChildAt(1) as ViewGroup).getChildAt(0)).measuredWidth)
        key(view, "調整を閉じる").performClick()
        assertFalse(view.adjustingGeometry)
        key(view, "カ").performClick()
        assertEquals(TouchKeyboardCommand.Skk(BasicSkkAction.TouchText("カ", kanaMode = InputMode.KATAKANA)), commands.single())
        view.configurationChanged()
        assertNotNull(key(view, "カ"))
        view.setTenKeyGeometry(256, 100, 50)
    }
    @Test fun `文字種確定はホールドを要求せず離した時に一度だけ送る`() {
        val commands = mutableListOf<TouchKeyboardCommand>()
        val view = TenKeyKeyboardView(RuntimeEnvironment.getApplication(),TouchKeyboardSettings()) { commands += it }
        view.updateReadingConversion(true)
        val kind = key(view,"ア"); touch(kind,MotionEvent.ACTION_DOWN); touch(kind,MotionEvent.ACTION_MOVE,30f,0f)
        assertTrue(commands.isEmpty()); touch(kind,MotionEvent.ACTION_UP,30f,0f)
        assertEquals(listOf(TouchKeyboardCommand.Skk(BasicSkkAction.ConvertTouchReading())),commands)
        assertEquals("ア",kind.text.toString())
    }
    @Test fun `英字の大文字修飾と境界は独立して保護欄では境界を無効にする`() {
        val commands = mutableListOf<TouchKeyboardCommand>()
        val view = TenKeyKeyboardView(RuntimeEnvironment.getApplication(),TouchKeyboardSettings()) { commands += it }
        key(view,"ABC").performClick()
        val alpha = key(view,"abc")
        touch(alpha,MotionEvent.ACTION_DOWN); touch(alpha,MotionEvent.ACTION_MOVE,60f,30f); touch(alpha,MotionEvent.ACTION_MOVE); touch(alpha,MotionEvent.ACTION_UP)
        assertEquals(TouchKeyboardCommand.Skk(BasicSkkAction.TouchText("A")),commands.single())
        commands.clear(); view.setSkkEnabled(false)
        Robolectric.buildActivity(Activity::class.java).setup().get().setContentView(view)
        touch(alpha,MotionEvent.ACTION_DOWN)
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(500,TimeUnit.MILLISECONDS)
        touch(alpha,MotionEvent.ACTION_UP)
        assertEquals(TouchKeyboardCommand.Skk(BasicSkkAction.TouchText("a")),commands.single())
        assertFalse(key(view,"⇧").isEnabled)
    }
    @Test fun `取消と入力欄更新と非表示は遅延入力を破棄する`() {
        val commands = mutableListOf<TouchKeyboardCommand>()
        val view = TouchKeyboardView(RuntimeEnvironment.getApplication()) { commands += it }
        view.configure(TouchKeyboardSettings(layout=ScreenKeyboardLayout.TEN_KEY))
        Robolectric.buildActivity(Activity::class.java).setup().get().setContentView(view)
        val old = key(view,"か"); touch(old,MotionEvent.ACTION_DOWN); view.resetForEditorSession()
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(500,TimeUnit.MILLISECONDS); touch(old,MotionEvent.ACTION_UP)
        val current = key(view,"か"); touch(current,MotionEvent.ACTION_DOWN); view.setCharacterAreaVisible(false); touch(current,MotionEvent.ACTION_UP)
        assertTrue(commands.isEmpty())
    }
    @Test fun `十字ガイドは元キーを中心に方向を保ち同じポップアップを更新する`() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val view = TenKeyKeyboardView(activity, TouchKeyboardSettings()) {}
        activity.setContentView(view)
        view.measure(View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(320, View.MeasureSpec.EXACTLY))
        view.layout(0, 0, 600, 320)
        val source = key(view, "な")
        touch(source, MotionEvent.ACTION_DOWN)
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(200, TimeUnit.MILLISECONDS)
        val popup = view.guide!!
        assertEquals(0, popup.animationStyle)
        assertNull(popup.enterTransition); assertNull(popup.exitTransition)
        assertFalse(popup.isFocusable); assertFalse(popup.isTouchable)
        val content = popup.contentView as ViewGroup
        val cells = (0 until content.childCount).associate { index ->
            val cell = content.getChildAt(index) as android.widget.TextView
            cell.tag as TenKeyController.Direction to cell
        }
        val center = cells.getValue(TenKeyController.Direction.CENTER)
        fun position(direction: TenKeyController.Direction) = cells.getValue(direction).layoutParams as android.widget.FrameLayout.LayoutParams
        assertTrue(position(TenKeyController.Direction.LEFT).leftMargin < position(TenKeyController.Direction.CENTER).leftMargin)
        assertTrue(position(TenKeyController.Direction.RIGHT).leftMargin > position(TenKeyController.Direction.CENTER).leftMargin)
        assertTrue(position(TenKeyController.Direction.UP).topMargin < position(TenKeyController.Direction.CENTER).topMargin)
        assertTrue(position(TenKeyController.Direction.DOWN).topMargin > position(TenKeyController.Direction.CENTER).topMargin)
        assertEquals("ぬ", cells.getValue(TenKeyController.Direction.UP).text.toString())
        val up = cells.getValue(TenKeyController.Direction.UP) as FlickGuideCell
        assertEquals("5", up.hintSymbol)
        assertEquals(TenKeyController.Direction.UP, up.returnDirection)
        assertTrue(up.hintTextScale < 1f)
        assertTrue(up.hintAlpha < 255)
        touch(source, MotionEvent.ACTION_MOVE, 30f, 0f)
        assertSame(popup, view.guide)
        assertEquals("ぬ", center.text.toString())
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(350, TimeUnit.MILLISECONDS)
        assertEquals("境界 ぬ", center.contentDescription.toString())
        touch(source, MotionEvent.ACTION_CANCEL)
        assertNull(view.guide)
    }
    @Test fun `境界振動は移動後の静止で再発火し同じ静止では一度だけ鳴る`() {
        val context = RuntimeEnvironment.getApplication()
        Shadows.shadowOf(context.getSystemService(android.content.Context.VIBRATOR_SERVICE) as android.os.Vibrator).setHasVibrator(true)
        val view = TenKeyKeyboardView(context, TouchKeyboardSettings(vibrateOnBoundary = true)) {}
        Robolectric.buildActivity(Activity::class.java).setup().get().setContentView(view)
        val source = key(view, "か")
        touch(source, MotionEvent.ACTION_DOWN)
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(350, TimeUnit.MILLISECONDS)
        assertEquals(android.view.HapticFeedbackConstants.LONG_PRESS, Shadows.shadowOf(source).lastHapticFeedbackPerformed())
        source.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(500, TimeUnit.MILLISECONDS)
        assertEquals(android.view.HapticFeedbackConstants.KEYBOARD_TAP, Shadows.shadowOf(source).lastHapticFeedbackPerformed())
        touch(source, MotionEvent.ACTION_MOVE, 30f, 0f)
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(350, TimeUnit.MILLISECONDS)
        assertEquals(android.view.HapticFeedbackConstants.LONG_PRESS, Shadows.shadowOf(source).lastHapticFeedbackPerformed())
        touch(source, MotionEvent.ACTION_CANCEL)
    }
    @Test fun `同じ文字の指ずれと離した座標では一キー未満の境界を保つ`() {
        val commands = mutableListOf<TouchKeyboardCommand>()
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val view = TenKeyKeyboardView(activity, TouchKeyboardSettings()) { commands += it }
        activity.setContentView(view)
        view.measure(View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(320, View.MeasureSpec.EXACTLY))
        view.layout(0, 0, 600, 320)
        val source = key(view, "か")
        touch(source, MotionEvent.ACTION_DOWN, 60f, 40f)
        touch(source, MotionEvent.ACTION_MOVE, 60f, 0f)
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(350, TimeUnit.MILLISECONDS)
        touch(source, MotionEvent.ACTION_MOVE, 60f, -20f)
        assertTrue(view.pendingGuide!!.startsWith("境界"))
        touch(source, MotionEvent.ACTION_UP, 60f, -24f)
        assertEquals(TouchKeyboardCommand.Skk(BasicSkkAction.TouchText("く", true)), commands.single())
    }
    @Test fun `取消振動は短い二回で継続移動では繰り返さずセッション取消で予約を破棄する`() {
        val context = RuntimeEnvironment.getApplication()
        Shadows.shadowOf(context.getSystemService(android.content.Context.VIBRATOR_SERVICE) as android.os.Vibrator).setHasVibrator(true)
        val view = TenKeyKeyboardView(context, TouchKeyboardSettings(vibrateOnBoundary = true)) {}
        Robolectric.buildActivity(Activity::class.java).setup().get().setContentView(view)
        val source = key(view, "か")
        val shadow = Shadows.shadowOf(source)
        val clock = Shadows.shadowOf(Looper.getMainLooper())
        val tap = android.view.HapticFeedbackConstants.KEYBOARD_TAP
        val marker = android.view.HapticFeedbackConstants.CONTEXT_CLICK
        fun begin() {
            touch(source, MotionEvent.ACTION_DOWN)
            clock.idleFor(350, TimeUnit.MILLISECONDS)
            assertEquals(android.view.HapticFeedbackConstants.LONG_PRESS, shadow.lastHapticFeedbackPerformed())
        }
        begin()
        touch(source, MotionEvent.ACTION_MOVE, 30f, 0f)
        assertEquals(tap, shadow.lastHapticFeedbackPerformed())
        source.performHapticFeedback(marker)
        // 描画時にも模擬時刻が進むため、二回目の直前1 msには依存しません。
        clock.idleFor(10, TimeUnit.MILLISECONDS)
        assertEquals(marker, shadow.lastHapticFeedbackPerformed())
        clock.idleFor(60, TimeUnit.MILLISECONDS)
        assertEquals(tap, shadow.lastHapticFeedbackPerformed())
        source.performHapticFeedback(marker)
        touch(source, MotionEvent.ACTION_MOVE, 30f, -2f)
        clock.idleFor(60, TimeUnit.MILLISECONDS)
        assertEquals(marker, shadow.lastHapticFeedbackPerformed())
        view.cancelTouches()
        begin()
        touch(source, MotionEvent.ACTION_MOVE, 30f, 0f)
        view.cancelTouches()
        source.performHapticFeedback(marker)
        clock.idleFor(400, TimeUnit.MILLISECONDS)
        assertEquals(marker, shadow.lastHapticFeedbackPerformed())
    }
    @Test fun `振動設定と振動子の有無は境界認識と取消の両方に適用する`() {
        val context = RuntimeEnvironment.getApplication()
        listOf(false to true, true to false).forEach { (enabled, hardware) ->
            Shadows.shadowOf(context.getSystemService(android.content.Context.VIBRATOR_SERVICE) as android.os.Vibrator).setHasVibrator(hardware)
            val view = TenKeyKeyboardView(context, TouchKeyboardSettings(vibrateOnBoundary = enabled)) {}
            Robolectric.buildActivity(Activity::class.java).setup().get().setContentView(view)
            val source = key(view, "か")
            val marker = android.view.HapticFeedbackConstants.CONTEXT_CLICK
            source.performHapticFeedback(marker)
            touch(source, MotionEvent.ACTION_DOWN)
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(350, TimeUnit.MILLISECONDS)
            assertEquals(marker, Shadows.shadowOf(source).lastHapticFeedbackPerformed())
            touch(source, MotionEvent.ACTION_MOVE, 30f, 0f)
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(60, TimeUnit.MILLISECONDS)
            assertEquals(marker, Shadows.shadowOf(source).lastHapticFeedbackPerformed())
            view.dispose()
        }
    }
    @Test fun `取消の二回目の振動は再構成と保護欄への切替と破棄で停止する`() {
        val context = RuntimeEnvironment.getApplication()
        Shadows.shadowOf(context.getSystemService(android.content.Context.VIBRATOR_SERVICE) as android.os.Vibrator).setHasVibrator(true)
        listOf<(TenKeyKeyboardView) -> Unit>(
            { key(it, "ABC").performClick() }, { it.setSkkEnabled(false) }, { it.dispose() },
        ).forEach { cancel ->
            val view = TenKeyKeyboardView(context, TouchKeyboardSettings()) {}
            Robolectric.buildActivity(Activity::class.java).setup().get().setContentView(view)
            val source = key(view, "か")
            touch(source, MotionEvent.ACTION_DOWN)
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(350, TimeUnit.MILLISECONDS)
            touch(source, MotionEvent.ACTION_MOVE, 30f, 0f)
            cancel(view)
            val marker = android.view.HapticFeedbackConstants.CONTEXT_CLICK
            source.performHapticFeedback(marker)
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(400, TimeUnit.MILLISECONDS)
            assertEquals(marker, Shadows.shadowOf(source).lastHapticFeedbackPerformed())
        }
    }
    @Test fun `離した座標で境界取消になっても二回目の短い振動を送る`() {
        val context = RuntimeEnvironment.getApplication()
        Shadows.shadowOf(context.getSystemService(android.content.Context.VIBRATOR_SERVICE) as android.os.Vibrator).setHasVibrator(true)
        val view = TenKeyKeyboardView(context, TouchKeyboardSettings()) {}
        Robolectric.buildActivity(Activity::class.java).setup().get().setContentView(view)
        val source = key(view, "か")
        touch(source, MotionEvent.ACTION_DOWN)
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(350, TimeUnit.MILLISECONDS)
        touch(source, MotionEvent.ACTION_UP, 30f, 0f)
        source.performHapticFeedback(android.view.HapticFeedbackConstants.CONTEXT_CLICK)
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(60, TimeUnit.MILLISECONDS)
        assertEquals(android.view.HapticFeedbackConstants.KEYBOARD_TAP, Shadows.shadowOf(source).lastHapticFeedbackPerformed())
    }
    @Test fun `境界ガイドは十字の外周十二辺だけを強調する`() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val view = TenKeyKeyboardView(activity, TouchKeyboardSettings()) {}
        activity.setContentView(view)
        view.measure(View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(320, View.MeasureSpec.EXACTLY))
        view.layout(0, 0, 600, 320)
        touch(key(view, "な"), MotionEvent.ACTION_DOWN)
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(350, TimeUnit.MILLISECONDS)
        val content = view.guide!!.contentView as FlickGuideLayout
        assertTrue(content.boundary)
        assertEquals(5, content.outlineCells.size)
        val edges = content.outlineEdges()
        assertEquals(12, edges.size)
        val w = content.outlineCellWidth; val h = content.outlineCellHeight
        assertTrue(listOf(w, 0f, 2 * w, 0f) in edges)
        assertTrue(listOf(0f, 2 * h, 0f, h) in edges)
        assertFalse(listOf(w, h, 2 * w, h) in edges)
        assertFalse(listOf(0f, 0f, 3 * w, 0f) in edges)
    }
    @Test fun `削除は即時一回と四百ミリ秒後から六十ミリ秒間隔で離すと停止する`() {
        val commands = mutableListOf<TouchKeyboardCommand>()
        val view = TenKeyKeyboardView(RuntimeEnvironment.getApplication(), TouchKeyboardSettings()) { commands += it }
        Robolectric.buildActivity(Activity::class.java).setup().get().setContentView(view)
        val delete = key(view, "⌫")
        touch(delete, MotionEvent.ACTION_DOWN)
        assertEquals(1, commands.size)
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(399, TimeUnit.MILLISECONDS)
        assertEquals(1, commands.size)
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(1, TimeUnit.MILLISECONDS)
        assertEquals(2, commands.size)
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(120, TimeUnit.MILLISECONDS)
        assertEquals(4, commands.size)
        touch(delete, MotionEvent.ACTION_UP)
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(500, TimeUnit.MILLISECONDS)
        assertEquals(4, commands.size)
        delete.performClick()
        assertEquals(5, commands.size)
        assertTrue(commands.all { it == TouchKeyboardCommand.Skk(BasicSkkAction.Backspace) })
    }
    @Test fun `削除の繰返しは取消と再構築とセッション取消で止まる`() {
        val commands = mutableListOf<TouchKeyboardCommand>()
        val view = TenKeyKeyboardView(RuntimeEnvironment.getApplication(), TouchKeyboardSettings()) { commands += it }
        Robolectric.buildActivity(Activity::class.java).setup().get().setContentView(view)
        touch(key(view, "⌫"), MotionEvent.ACTION_DOWN)
        touch(key(view, "⌫"), MotionEvent.ACTION_CANCEL)
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(500, TimeUnit.MILLISECONDS)
        assertEquals(1, commands.size)
        val old = key(view, "⌫")
        touch(old, MotionEvent.ACTION_DOWN)
        key(view, "ABC").performClick()
        touch(old, MotionEvent.ACTION_UP)
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(500, TimeUnit.MILLISECONDS)
        assertEquals(2, commands.size)
        touch(key(view, "⌫"), MotionEvent.ACTION_DOWN)
        view.cancelTouches()
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(500, TimeUnit.MILLISECONDS)
        assertEquals(3, commands.size)
        touch(key(view, "⌫"), MotionEvent.ACTION_DOWN)
        touch(key(view, "⌫"), MotionEvent.ACTION_POINTER_DOWN)
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(500, TimeUnit.MILLISECONDS)
        assertEquals(4, commands.size)
        touch(key(view, "⌫"), MotionEvent.ACTION_DOWN)
        (view.parent as ViewGroup).removeView(view)
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(500, TimeUnit.MILLISECONDS)
        assertEquals(5, commands.size)
    }
    @Test fun `切替先だけを示し全角出力でも英数字キーはASCIIを保つ`() {
        val commands = mutableListOf<TouchKeyboardCommand>()
        val view = TenKeyKeyboardView(RuntimeEnvironment.getApplication(), TouchKeyboardSettings()) { commands += it }
        assertEquals("読み開始・送り境界、長押しで自動読み開始切替", key(view, "⇧").contentDescription)
        assertNotNull(key(view, "⏎"))
        view.updateSpaceAction(true)
        key(view, "ABC").performClick()
        assertNotNull(key(view, "変換"))
        key(view, "全Ａ").performClick()
        assertNotNull(key(view, "A")); assertNotNull(key(view, "abc")); assertNotNull(key(view, "@"))
        key(view, "abc").performClick()
        assertEquals(TouchKeyboardCommand.Skk(BasicSkkAction.TouchText("ａ")), commands.last())
        key(view, "123").performClick()
        key(view, "全１").performClick()
        assertNotNull(key(view, "かな"))
        key(view, "1").performClick()
        assertEquals(TouchKeyboardCommand.Skk(BasicSkkAction.TouchText("１")), commands.last())
        view.updateSpaceAction(false)
        assertEquals("空白", key(view, "␣").contentDescription)
    }

    @Test fun `左端の文字種と最下段わのガイド中心は元キーの画面中心を保つ`() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val view = TenKeyKeyboardView(activity, TouchKeyboardSettings()) {}
        activity.setContentView(view)
        view.measure(View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(320, View.MeasureSpec.EXACTLY))
        view.layout(0, 0, 600, 320)
        view.updateReadingConversion(true)
        for (label in listOf("ア", "わ")) {
            val source = key(view, label)
            touch(source, MotionEvent.ACTION_DOWN)
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(200, TimeUnit.MILLISECONDS)
            val popup = view.guide!!
            val cells = popup.contentView as ViewGroup
            val center = (0 until cells.childCount).map(cells::getChildAt).first { it.tag == TenKeyController.Direction.CENTER }
            val params = center.layoutParams as android.widget.FrameLayout.LayoutParams
            val sourcePosition = IntArray(2).also(source::getLocationOnScreen)
            val bounds = view.guideBounds!!
            assertEquals(sourcePosition[0] + source.width / 2f, bounds.left + params.leftMargin + params.width / 2f, 0f)
            assertEquals(sourcePosition[1] + source.height / 2f, bounds.top + params.topMargin + params.height / 2f, 0f)
            val emptyDirections = if (label == "ア") setOf(TenKeyController.Direction.LEFT, TenKeyController.Direction.DOWN) else setOf(TenKeyController.Direction.DOWN)
            (0 until cells.childCount).map(cells::getChildAt).filter { it.tag in emptyDirections }.forEach { assertEquals(View.INVISIBLE, it.visibility) }
            val visible = android.graphics.Rect().also(source::getWindowVisibleDisplayFrame)
            assertTrue(bounds.left >= visible.left); assertTrue(bounds.right <= visible.right)
            assertTrue(bounds.top >= visible.top); assertTrue(bounds.bottom <= visible.bottom)
            touch(source, MotionEvent.ACTION_CANCEL)
        }
    }

    @Test fun `削除キーから外へ移動すると戻っても繰返しを再開しない`() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val commands = mutableListOf<TouchKeyboardCommand>()
        val view = TenKeyKeyboardView(activity, TouchKeyboardSettings()) { commands += it }
        activity.setContentView(view)
        view.measure(View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(320, View.MeasureSpec.EXACTLY))
        view.layout(0, 0, 600, 320)
        val source = key(view, "⌫")
        val slop = android.view.ViewConfiguration.get(activity).scaledTouchSlop
        touch(source, MotionEvent.ACTION_DOWN)
        touch(source, MotionEvent.ACTION_MOVE, source.width + slop.toFloat() + 1f, 30f)
        touch(source, MotionEvent.ACTION_MOVE)
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(600, TimeUnit.MILLISECONDS)
        touch(source, MotionEvent.ACTION_UP)
        assertEquals(listOf(TouchKeyboardCommand.Skk(BasicSkkAction.Backspace)), commands)
    }

    @Test fun `中央を飛び越す離し位置と履歴から往復修飾を認識する`() {
        val commands = mutableListOf<TouchKeyboardCommand>()
        val view = TenKeyKeyboardView(RuntimeEnvironment.getApplication(), TouchKeyboardSettings()) { commands += it }
        val source = key(view, "か")
        touch(source, MotionEvent.ACTION_DOWN)
        touch(source, MotionEvent.ACTION_MOVE, 0f, 30f)
        touch(source, MotionEvent.ACTION_UP, 60f, 30f)
        assertEquals(TouchKeyboardCommand.Skk(BasicSkkAction.TouchText("げ")), commands.last())
        touch(source, MotionEvent.ACTION_DOWN)
        val time = SystemClock.uptimeMillis()
        MotionEvent.obtain(time, time, MotionEvent.ACTION_MOVE, 0f, 30f, 0).also {
            it.addBatch(time + 1, 60f, 30f, 1f, 1f, 0)
            source.dispatchTouchEvent(it); it.recycle()
        }
        touch(source, MotionEvent.ACTION_UP, 60f, 30f)
        assertEquals(TouchKeyboardCommand.Skk(BasicSkkAction.TouchText("げ")), commands.last())
    }
    @Test fun `上で離す最後の線分だけでも左からの修飾を認識する`() {
        val commands = mutableListOf<TouchKeyboardCommand>()
        val view = TenKeyKeyboardView(RuntimeEnvironment.getApplication(), TouchKeyboardSettings()) { commands += it }
        val source = key(view, "か")
        val time = SystemClock.uptimeMillis()
        fun event(action: Int, x: Float, y: Float) {
            MotionEvent.obtain(time, time, action, x, y, 0).also { source.dispatchTouchEvent(it); it.recycle() }
        }
        event(MotionEvent.ACTION_DOWN, 30f, 30f)
        event(MotionEvent.ACTION_MOVE, 0f, 30f)
        event(MotionEvent.ACTION_UP, 30f, 0f)
        assertEquals(TouchKeyboardCommand.Skk(BasicSkkAction.TouchText("ぐ")), commands.single())
        commands.clear()
        event(MotionEvent.ACTION_DOWN, 30f, 30f)
        event(MotionEvent.ACTION_UP, 30f, 0f)
        assertEquals(TouchKeyboardCommand.Skk(BasicSkkAction.TouchText("く")), commands.single())
    }
    @Test fun `離すイベントの履歴から方向の退出を順に認識する`() {
        val commands = mutableListOf<TouchKeyboardCommand>()
        val view = TenKeyKeyboardView(RuntimeEnvironment.getApplication(), TouchKeyboardSettings()) { commands += it }
        val source = key(view, "か")
        touch(source, MotionEvent.ACTION_DOWN)
        val time = SystemClock.uptimeMillis()
        MotionEvent.obtain(time, time, MotionEvent.ACTION_MOVE, 0f, 30f, 0).also {
            it.addBatch(time + 1, 30f, 0f, 1f, 1f, 0)
            it.action = MotionEvent.ACTION_UP
            source.dispatchTouchEvent(it); it.recycle()
        }
        assertEquals(TouchKeyboardCommand.Skk(BasicSkkAction.TouchText("ぐ")), commands.single())
    }
    @Test fun `往路は戻す修飾を案内し復帰後は現在の修飾と解除を示す`() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val view = TenKeyKeyboardView(activity, TouchKeyboardSettings()) {}
        activity.setContentView(view)
        val source = key(view, "は")
        touch(source, MotionEvent.ACTION_DOWN)
        touch(source, MotionEvent.ACTION_MOVE, 60f, 30f)
        assertTrue(view.pendingGuide!!.contains("゜"))
        assertFalse(view.pendingGuide!!.contains("小"))
        assertTrue(view.pendingGuide!!.contains("゛"))
        assertTrue(view.pendingGuide!!.contains("6"))
        touch(source, MotionEvent.ACTION_MOVE)
        assertTrue(view.pendingGuide!!.contains("ぱ\n゜"))
        fun guideCells() = (view.guide!!.contentView as ViewGroup).let { content ->
            (0 until content.childCount).map { content.getChildAt(it) as FlickGuideCell }.associateBy { it.tag as TenKeyController.Direction }
        }
        assertTrue(guideCells().getValue(TenKeyController.Direction.CENTER).modifierActive)
        assertTrue(guideCells().getValue(TenKeyController.Direction.RIGHT).modifierActive)
        assertEquals(TenKeyController.Direction.RIGHT, guideCells().getValue(TenKeyController.Direction.RIGHT).returnDirection)
        touch(source, MotionEvent.ACTION_MOVE, 60f, 30f)
        assertTrue(guideCells().getValue(TenKeyController.Direction.RIGHT).modifierActive)
        assertFalse(view.pendingGuide!!.contains("戻す"))
        assertFalse(view.pendingGuide!!.contains("往復"))
        assertFalse(view.pendingGuide!!.contains("選択中"))
        val cells = view.guide!!.contentView as ViewGroup
        assertTrue((0 until cells.childCount).map(cells::getChildAt).any { it.contentDescription?.contains("戻すと解除") == true })
        touch(source, MotionEvent.ACTION_MOVE)
        assertFalse(guideCells().getValue(TenKeyController.Direction.CENTER).modifierActive)
        touch(source, MotionEvent.ACTION_CANCEL)
    }
    @Test fun `数字の案内は各かな行の出力と一致する`() {
        val view = TenKeyKeyboardView(RuntimeEnvironment.getApplication(), TouchKeyboardSettings()) {}
        listOf("あ" to "1", "か" to "2", "さ" to "3", "た" to "4", "な" to "5", "は" to "6", "ま" to "7", "や" to "8", "ら" to "9", "わ" to "0").forEach { (kana, digit) ->
            val source = key(view, kana)
            touch(source, MotionEvent.ACTION_DOWN)
            touch(source, MotionEvent.ACTION_MOVE, 30f, 0f)
            assertTrue("$kana の数字案内", view.pendingGuide!!.contains("\n$digit"))
            assertFalse(view.pendingGuide!!.contains("数字"))
            touch(source, MotionEvent.ACTION_CANCEL)
        }
    }

    @Test fun `修飾しない行とフリック専用方式に往復案内を出さない`() {
        val view = TenKeyKeyboardView(RuntimeEnvironment.getApplication(), TouchKeyboardSettings()) {}
        val source = key(view, "な")
        touch(source, MotionEvent.ACTION_DOWN)
        touch(source, MotionEvent.ACTION_MOVE, 0f, 30f)
        assertFalse(view.pendingGuide!!.contains("濁点"))
        assertFalse(view.pendingGuide!!.contains("小"))
        touch(source, MotionEvent.ACTION_CANCEL)
        val flick = TenKeyKeyboardView(RuntimeEnvironment.getApplication(), TouchKeyboardSettings(operation = TenKeyOperation.FLICK)) {}
        val kana = key(flick, "は")
        touch(kana, MotionEvent.ACTION_DOWN); touch(kana, MotionEvent.ACTION_MOVE, 60f, 30f)
        assertFalse(flick.pendingGuide!!.contains("戻す"))
        assertFalse(flick.pendingGuide!!.contains("往復"))
    }

    @Test fun `読み確定の方向は利用可能な読みがある時だけ表示して送信する`() {
        val commands = mutableListOf<TouchKeyboardCommand>()
        val view = TenKeyKeyboardView(RuntimeEnvironment.getApplication(), TouchKeyboardSettings()) { commands += it }
        fun flick() {
            val kind = key(view, "ア")
            touch(kind, MotionEvent.ACTION_DOWN)
            touch(kind, MotionEvent.ACTION_MOVE, 30f, 0f)
        }
        flick()
        assertFalse(view.pendingGuide!!.contains("カナ"))
        touch(key(view, "ア"), MotionEvent.ACTION_UP, 30f, 0f)
        assertTrue(commands.isEmpty())
        view.updateReadingConversion(true)
        flick()
        assertTrue(view.pendingGuide!!.contains("カナ"))
        assertTrue(view.pendingGuide!!.contains("ｶﾅ"))
        touch(key(view, "ア"), MotionEvent.ACTION_UP, 30f, 0f)
        assertEquals(listOf(TouchKeyboardCommand.Skk(BasicSkkAction.ConvertTouchReading())), commands)
        commands.clear()
        view.setSkkEnabled(false)
        flick()
        assertFalse(view.pendingGuide!!.contains("カナ"))
        touch(key(view, "ア"), MotionEvent.ACTION_UP, 30f, 0f)
        assertTrue(commands.isEmpty())
    }
    private fun assertShiftDrawing() {
        val view = TenKeyKeyboardView(RuntimeEnvironment.getApplication(), TouchKeyboardSettings()) {}
        val shift = key(view, "⇧") as ReadingModeButton
        shift.layout(0, 0, 100, 100)
        assertEquals(key(view, "ア").currentTextColor, shift.currentTextColor)
        fun symbolBitmap(): android.graphics.Bitmap = android.graphics.Bitmap.createBitmap(100, 100,
            android.graphics.Bitmap.Config.ARGB_8888).also { shift.draw(android.graphics.Canvas(it)) }
        val center = shift.arrowBounds()
        assertEquals(50f, center.centerX(), .01f)
        assertEquals(50f, center.centerY(), .01f)
        val outlined = symbolBitmap()
        view.updateShiftPending(true)
        val filled = symbolBitmap()
        val insideX = center.centerX().toInt()
        val insideY = (center.bottom - center.height() * .2f).toInt()
        assertNotEquals(outlined.getPixel(insideX, insideY), filled.getPixel(insideX, insideY))
        assertEquals(shift.currentTextColor, filled.getPixel(insideX, insideY))
        val lamp = shift.lampBounds()
        assertTrue(lamp.top > center.bottom)
        assertTrue(lamp.bottom < shift.height)
        val inactive = filled.getPixel(lamp.centerX().toInt(), lamp.centerY().toInt())
        toggleAuto(view)
        val active = symbolBitmap()
        assertEquals(android.graphics.Color.rgb(74, 210, 126), active.getPixel(lamp.centerX().toInt(), lamp.centerY().toInt()))
        assertNotEquals(inactive, active.getPixel(lamp.centerX().toInt(), lamp.centerY().toInt()))
        assertEquals(center, shift.arrowBounds())
    }

    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE) @Config(sdk = [26, 35])
    fun `シフトは中央の輪郭と塗りつぶしと下側ランプを同じ文字色で描く`() {
        assertShiftDrawing()
    }

    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE) @Config(sdk = [26, 35], qualifiers = "night")
    fun `夜間のシフトも中央とランプを他のキーと同じ文字色で描く`() {
        assertShiftDrawing()
    }

    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE) fun `地球キーだけに長押し別機能の補助記号を付ける`() {
        val view = TenKeyKeyboardView(RuntimeEnvironment.getApplication(), TouchKeyboardSettings()) {}
        val globe = buttons(view).first { it.contentDescription == "IME切替" } as AlternativeFunctionButton
        assertEquals("IME切替", globe.contentDescription)
        assertEquals("", globe.text.toString())
        val globeDrawable = globe.centeredIcon!!
        assertNotNull(globeDrawable)
        globeDrawable.setBounds(0, 0, globeDrawable.intrinsicWidth, globeDrawable.intrinsicHeight)
        val bitmap = android.graphics.Bitmap.createBitmap(globeDrawable.intrinsicWidth,
            globeDrawable.intrinsicHeight, android.graphics.Bitmap.Config.ARGB_8888)
        globeDrawable.draw(android.graphics.Canvas(bitmap))
        val visiblePixels = (0 until bitmap.width).flatMap { x ->
            (0 until bitmap.height).mapNotNull { y -> bitmap.getPixel(x, y).takeIf { it ushr 24 != 0 } }
        }
        assertTrue(visiblePixels.isNotEmpty())
        assertTrue(visiblePixels.all { (it and 0x00ffffff) == (globe.currentTextColor and 0x00ffffff) })
        assertEquals("…", globe.alternativeHint)
        globe.layout(0, 0, 80, 64)
        assertTrue(globe.alternativeHintBounds().top > globe.height / 2f)
        assertTrue(globe.alternativeHintBounds().bottom < globe.height)
        assertEquals(globe.width / 2f, globe.centeredIconBounds().centerX(), .01f)
        assertEquals(globe.height / 2f, globe.centeredIconBounds().centerY(), .01f)
        val iconBounds = globe.centeredIconBounds()
        globe.showAlternativeHint = false
        assertEquals(iconBounds, globe.centeredIconBounds())
        assertTrue(globe.compoundDrawables.all { it == null })
        assertFalse(key(view, "ア") is AlternativeFunctionButton)
        assertFalse(key(view, "⌫") is AlternativeFunctionButton)
    }

    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE) @Config(qualifiers = "night")
    fun `夜間の地球儀も他のキーと同じ文字色で描画する`() {
        val view = TenKeyKeyboardView(RuntimeEnvironment.getApplication(), TouchKeyboardSettings()) {}
        val globe = buttons(view).first { it.contentDescription == "IME切替" } as AlternativeFunctionButton
        assertEquals(key(view, "ア").currentTextColor, globe.currentTextColor)
        val icon = requireNotNull(globe.centeredIcon)
        val bitmap = android.graphics.Bitmap.createBitmap(icon.intrinsicWidth, icon.intrinsicHeight,
            android.graphics.Bitmap.Config.ARGB_8888)
        icon.setBounds(0, 0, bitmap.width, bitmap.height)
        icon.draw(android.graphics.Canvas(bitmap))
        val opaquePixels = (0 until bitmap.width).flatMap { x ->
            (0 until bitmap.height).map { y -> bitmap.getPixel(x, y) }
        }.filter { it ushr 24 == 255 }
        assertTrue(opaquePixels.isNotEmpty())
        assertTrue(opaquePixels.all { it == globe.currentTextColor })
    }

}
