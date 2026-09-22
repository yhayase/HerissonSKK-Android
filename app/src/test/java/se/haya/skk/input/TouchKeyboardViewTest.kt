package se.haya.skk.input

import android.app.Activity
import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Insets
import android.os.SystemClock
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.Button
import android.widget.TextView
import se.haya.skk.core.BasicSkkAction
import se.haya.skk.core.InputMode
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.Robolectric
import org.robolectric.shadows.ShadowLooper
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 35])
class TouchKeyboardViewTest {
    private fun view(commands: MutableList<TouchKeyboardCommand> = mutableListOf()) =
        TouchKeyboardView(RuntimeEnvironment.getApplication()) { commands += it }
    private fun attachedView(commands: MutableList<TouchKeyboardCommand> = mutableListOf()): TouchKeyboardView {
        val view = view(commands)
        Robolectric.buildActivity(Activity::class.java).setup().get().setContentView(view)
        return view
    }

    @Test fun `英字面は四段で最下段はSpace読点句点の順になる`() {
        val view = view()
        val area = view.getChildAt(1) as ViewGroup
        assertEquals(4, area.childCount)
        assertEquals(listOf("123", "あ", "🌐", "Space", ",", ".", "↵"),
            buttons(area.getChildAt(3)).map { it.text.toString() })
        assertTrue(buttons(area.getChildAt(2)).any { it.text.toString() == "-" })
    }

    @Test fun `物理用に文字面を隠すと補助行やナビゲーション余白を残さない`() {
        val view = view()
        view.setCharacterAreaVisible(false)
        assertEquals(View.GONE, view.getChildAt(1).visibility)
        assertEquals(0, view.paddingBottom)
        assertEquals(2, view.childCount)
    }

    @Test fun `入力欄の切替はShiftと記号面を初期化する`() {
        val view = view()
        key(view, "⇧").performClick()
        assertNotNull(key(view, "Q"))
        key(view, "123").performClick()
        key(view, "記号2").performClick()
        view.resetForEditorSession()
        assertNotNull(key(view, "q"))
        assertNotNull(key(view, "123"))
    }

    @Test fun `二つの記号面を往復して全ASCII記号と円記号をタップできる`() {
        val commands = mutableListOf<TouchKeyboardCommand>()
        val view = view(commands)
        key(view, "123").performClick()
        val first = TouchKeyboardController.SYMBOL_ROWS_1.flatten() + listOf(",", ".")
        first.forEach { key(view, it).performClick() }
        key(view, "記号2").performClick()
        val second = TouchKeyboardController.SYMBOL_ROWS_2.flatten() + listOf(",", ".")
        second.forEach { key(view, it).performClick() }
        assertEquals((first + second).map { TouchKeyboardCommand.Skk(BasicSkkAction.Text(it)) }, commands)
        val expected = (33..126).map(Int::toChar).filterNot(Char::isLetterOrDigit).map(Char::toString).toSet()
        assertTrue((first + second).containsAll(expected))
        assertTrue("¥" in second)
        key(view, "ABC").performClick()
        key(view, "123").performClick()
        assertNotNull(key(view, "記号2"))
    }

    @Test fun `上下フリックはガイド待ちなしで大文字と記号を一度だけ送る`() {
        val commands = mutableListOf<TouchKeyboardCommand>()
        val view = view(commands)
        flick(key(view, "m"), -50f)
        flick(key(view, "m"), 50f)
        flick(key(view, ","), -50f)
        flick(key(view, "."), -50f)
        assertEquals(listOf("M", "/", "!", "?").map {
            TouchKeyboardCommand.Skk(BasicSkkAction.Text(it))
        }, commands)
    }

    @Test fun `長音キーは入力モードに関わらず原文のハイフンを渡す`() {
        val commands = mutableListOf<TouchKeyboardCommand>()
        val view = view(commands)
        view.updateMode(InputMode.DIRECT)
        key(view, "-").performClick()
        view.updateMode(InputMode.HIRAGANA)
        key(view, "-").performClick()
        assertEquals(listOf(
            TouchKeyboardCommand.Skk(BasicSkkAction.Text("-")),
            TouchKeyboardCommand.Skk(BasicSkkAction.Text("-")),
        ), commands)
    }

    @Test fun `各キーは本文を変えず上下候補だけを控えめに表示する`() {
        val view = view()
        val q = key(view, "q") as TouchKeyboardView.FlickHintButton
        val comma = key(view, ",") as TouchKeyboardView.FlickHintButton
        assertEquals("q", q.text.toString())
        assertEquals(null, q.displayedUpHint)
        assertEquals("1", q.displayedDownHint)
        assertEquals("!", comma.displayedUpHint)
        assertEquals(null, comma.displayedDownHint)

        key(view, "123").performClick()
        val one = key(view, "1") as TouchKeyboardView.FlickHintButton
        assertEquals(null, one.displayedUpHint)
        assertEquals(null, one.displayedDownHint)
    }

    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE) fun `フリック補助は矢印を付けず中心文字と重ならない右上下に収める`() {
        val button = key(laidOutView(), ",") as TouchKeyboardView.FlickHintButton

        val hints = button.hintLayouts()
        val face = button.visibleFaceBounds()
        val center = button.centerLabelLayout()!!.bounds
        assertEquals(listOf("!"), hints.map { it.text })
        assertTrue(hints.none { it.text.contains('↑') || it.text.contains('↓') })
        assertTrue(hints.all { face.contains(it.bounds) })
        assertTrue(hints[0].bounds.centerY() < face.centerY())
        assertTrue(hints.none { android.graphics.RectF.intersects(it.bounds, center) })
    }

    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE) fun `主文字を大きくし句読点を英字のベースライン付近に置く`() {
        val view = laidOutView(width = 320)
        val letter = key(view, "a") as TouchKeyboardView.FlickHintButton
        val comma = key(view, ",") as TouchKeyboardView.FlickHintButton
        val period = key(view, ".") as TouchKeyboardView.FlickHintButton
        val density = view.resources.displayMetrics.density
        assertTrue("${letter.centerLabelLayout()} face=${letter.visibleFaceBounds()} hints=${letter.hintLayouts()}",
            letter.centerLabelLayout()!!.textSize >= 20f * density)
        listOf(comma, period).forEach {
            val glyph = it.centerLabelLayout()!!
            assertTrue(glyph.textSize >= 24f * density)
            assertTrue(glyph.bounds.top > it.visibleFaceBounds().centerY())
            val hint = it.hintLayouts().single()
            assertTrue(it.visibleFaceBounds().right - hint.bounds.right >= 3.9f * density)
            assertTrue(hint.bounds.top - it.visibleFaceBounds().top >= 3.9f * density)
        }
        assertTrue(comma.centerLabelLayout()!!.bounds.height() > period.centerLabelLayout()!!.bounds.height())
    }

    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE) fun `狭いキーと大きな文字でも実グリフを省略せずキー面内で分離する`() {
        val base = RuntimeEnvironment.getApplication()
        val configuration = Configuration(base.resources.configuration).apply { fontScale = 2f }
        val context = base.createConfigurationContext(configuration)
        val button = key(laidOutView(context, width = 320), "q") as TouchKeyboardView.FlickHintButton

        val hints = button.hintLayouts()
        val face = button.visibleFaceBounds()
        val center = button.centerLabelLayout()!!.bounds
        assertEquals(listOf("1"), hints.map { it.text })
        assertTrue(hints.all { face.contains(it.bounds) })
        assertTrue(hints[0].bounds.centerY() > face.centerY())
        assertTrue(hints.none { android.graphics.RectF.intersects(it.bounds, center) })
        assertTrue(hints.single().textSize >= context.resources.displayMetrics.scaledDensity * 5f)
    }

    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE) fun `縦横と文字倍率が変わっても補助グリフはキー面内で中心と交差しない`() {
        val base = RuntimeEnvironment.getApplication()
        listOf(
            Triple(Configuration.ORIENTATION_PORTRAIT, 1f, 320),
            Triple(Configuration.ORIENTATION_PORTRAIT, 1.5f, 320),
            Triple(Configuration.ORIENTATION_LANDSCAPE, 2f, 480),
            Triple(Configuration.ORIENTATION_PORTRAIT, 2f, 800),
        ).forEach { (orientation, fontScale, width) ->
            val configuration = Configuration(base.resources.configuration).apply {
                this.orientation = orientation
                this.fontScale = fontScale
            }
            val context = base.createConfigurationContext(configuration)
            val button = key(laidOutView(context, width), "q") as TouchKeyboardView.FlickHintButton
            val face = button.visibleFaceBounds()
            val center = button.centerLabelLayout()!!.bounds
            val hint = button.hintLayouts().single()

            assertEquals("1", hint.text)
            assertTrue("$orientation/$fontScale/$width: $hint outside $face", face.contains(hint.bounds))
            assertFalse("$orientation/$fontScale/$width: $hint intersects $center",
                android.graphics.RectF.intersects(hint.bounds, center))
        }
    }

    @Test
    @Config(sdk = [35])
    @LooperMode(LooperMode.Mode.PAUSED)
    fun `長押しガイドは短い待機では表示せず約200ミリ秒で表示する`() {
        val view = attachedView()
        val q = key(view, "q")
        event(q, MotionEvent.ACTION_DOWN)
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(150, TimeUnit.MILLISECONDS)
        assertNull(view.visibleFlickGuide)
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(100, TimeUnit.MILLISECONDS)
        assertNotNull(view.visibleFlickGuide)
        event(q, MotionEvent.ACTION_CANCEL)
    }

    @Test fun `長押しと移動中のガイドは中心と上下の選択を更新する`() {
        val view = attachedView()
        val q = key(view, "q")
        event(q, MotionEvent.ACTION_DOWN)
        ShadowLooper.runUiThreadTasksIncludingDelayedTasks()
        assertEquals(TouchKeyboardController.Flick.TAP, view.activeFlickGuide)
        assertEquals("・  q", guideOption(view, 1).text)
        assertEquals(Color.WHITE, guideOption(view, 1).currentTextColor)
        event(q, MotionEvent.ACTION_MOVE, y = -50f)
        assertEquals(TouchKeyboardController.Flick.UP, view.activeFlickGuide)
        assertEquals("↑  Q", guideOption(view, 0).text)
        assertEquals(Color.WHITE, guideOption(view, 0).currentTextColor)
        event(q, MotionEvent.ACTION_MOVE, y = 60f)
        assertEquals(TouchKeyboardController.Flick.DOWN, view.activeFlickGuide)
        assertEquals("↓  1", guideOption(view, 2).text)
        assertEquals(Color.WHITE, guideOption(view, 2).currentTextColor)
        event(q, MotionEvent.ACTION_MOVE)
        assertEquals(TouchKeyboardController.Flick.TAP, view.activeFlickGuide)
        assertEquals(Color.WHITE, guideOption(view, 1).currentTextColor)
        event(q, MotionEvent.ACTION_CANCEL)
        assertEquals(null, view.activeFlickGuide)
    }

    @Test fun `Shift中の中心ガイドは確定する大文字を示す`() {
        val view = attachedView()
        key(view, "⇧").performClick()
        val q = key(view, "Q")
        event(q, MotionEvent.ACTION_DOWN)
        ShadowLooper.runUiThreadTasksIncludingDelayedTasks()
        assertEquals("・  Q", guideOption(view, 1).text)
        event(q, MotionEvent.ACTION_CANCEL)
    }

    @Test fun `一回Shiftを別キーが消費すると残るキーの中心ガイドも更新する`() {
        val commands = mutableListOf<TouchKeyboardCommand>()
        val view = attachedView(commands)
        key(view, "⇧").performClick()
        val q = key(view, "Q")
        val w = key(view, "W")
        event(q, MotionEvent.ACTION_DOWN)
        event(w, MotionEvent.ACTION_DOWN)
        event(w, MotionEvent.ACTION_MOVE, y = -50f)
        event(w, MotionEvent.ACTION_MOVE)
        assertEquals("・  W", guideOption(view, 1).text)
        event(q, MotionEvent.ACTION_UP, y = -50f)
        assertEquals("・  w", guideOption(view, 1).text)
        event(w, MotionEvent.ACTION_UP)
        assertEquals(listOf("Q", "w").map { TouchKeyboardCommand.Skk(BasicSkkAction.Text(it)) }, commands)
    }

    @Test fun `別キーの取消と遅延表示は現在のガイドを消さない`() {
        val view = attachedView()
        val q = key(view, "q")
        val w = key(view, "w")
        event(q, MotionEvent.ACTION_DOWN)
        event(w, MotionEvent.ACTION_DOWN)
        event(w, MotionEvent.ACTION_MOVE, y = -50f)
        assertEquals("↑  W", guideOption(view, 0).text)
        ShadowLooper.runUiThreadTasksIncludingDelayedTasks()
        assertEquals("↑  W", guideOption(view, 0).text)
        event(q, MotionEvent.ACTION_CANCEL)
        assertEquals(TouchKeyboardController.Flick.UP, view.activeFlickGuide)
        assertEquals("↑  W", guideOption(view, 0).text)
        event(w, MotionEvent.ACTION_CANCEL)
    }

    @Test fun `強調中のフリック値だけを一度送信し取消後に遅延ガイドを残さない`() {
        val commands = mutableListOf<TouchKeyboardCommand>()
        val view = view(commands)
        val q = key(view, "q")
        event(q, MotionEvent.ACTION_DOWN)
        event(q, MotionEvent.ACTION_MOVE, y = -50f)
        assertEquals(TouchKeyboardController.Flick.UP, view.activeFlickGuide)
        event(q, MotionEvent.ACTION_UP, y = -50f)
        assertEquals(listOf(TouchKeyboardCommand.Skk(BasicSkkAction.Text("Q"))), commands)
        assertEquals(null, view.activeFlickGuide)

        event(q, MotionEvent.ACTION_DOWN)
        event(q, MotionEvent.ACTION_CANCEL)
        ShadowLooper.runUiThreadTasksIncludingDelayedTasks()
        assertEquals(null, view.activeFlickGuide)
        assertEquals(1, commands.size)
    }

    @Test fun `Shiftを押している間に文字へ触れて先にShiftを離しても大文字となる`() {
        val commands = mutableListOf<TouchKeyboardCommand>()
        val view = view(commands)
        val shift = key(view, "⇧")
        event(shift, MotionEvent.ACTION_DOWN)
        val letter = key(view, "N")
        event(letter, MotionEvent.ACTION_DOWN)
        event(shift, MotionEvent.ACTION_UP)
        event(letter, MotionEvent.ACTION_UP)
        key(view, "i").performClick()
        assertEquals(listOf("N", "i").map { TouchKeyboardCommand.Skk(BasicSkkAction.Text(it)) }, commands)
    }

    @Test fun `一回Shift中に二つの文字へ触れても最初に送った文字だけを大文字にする`() {
        val commands = mutableListOf<TouchKeyboardCommand>()
        val view = view(commands)
        key(view, "⇧").performClick()
        val n = key(view, "N")
        val i = key(view, "I")
        event(n, MotionEvent.ACTION_DOWN)
        event(i, MotionEvent.ACTION_DOWN)
        event(n, MotionEvent.ACTION_UP)
        event(i, MotionEvent.ACTION_UP)
        assertEquals(listOf("N", "i").map { TouchKeyboardCommand.Skk(BasicSkkAction.Text(it)) }, commands)
    }

    @Test fun `一回Shiftは取消で消費せず同時押しShiftは複数文字に使える`() {
        val commands = mutableListOf<TouchKeyboardCommand>()
        val view = view(commands)
        key(view, "⇧").performClick()
        val n = key(view, "N")
        event(n, MotionEvent.ACTION_DOWN)
        event(n, MotionEvent.ACTION_CANCEL)
        key(view, "I").performClick()
        val shift = key(view, "⇧")
        event(shift, MotionEvent.ACTION_DOWN)
        val q = key(view, "Q")
        val w = key(view, "W")
        event(q, MotionEvent.ACTION_DOWN)
        event(w, MotionEvent.ACTION_DOWN)
        event(shift, MotionEvent.ACTION_UP)
        event(q, MotionEvent.ACTION_UP)
        event(w, MotionEvent.ACTION_UP)
        assertEquals(listOf("I", "Q", "W").map { TouchKeyboardCommand.Skk(BasicSkkAction.Text(it)) }, commands)
    }

    @Test fun `取消されたフリックは送信せずキー再構築でも再生しない`() {
        val commands = mutableListOf<TouchKeyboardCommand>()
        val view = view(commands)
        val q = key(view, "q")
        event(q, MotionEvent.ACTION_DOWN)
        event(q, MotionEvent.ACTION_MOVE, y = -50f)
        event(q, MotionEvent.ACTION_CANCEL)
        event(q, MotionEvent.ACTION_UP, y = -50f)
        view.configurationChanged()
        assertTrue(commands.isEmpty())
    }

    @Test fun `モードと右下キーの表示は記号面でも現在値を保つ`() {
        val commands = mutableListOf<TouchKeyboardCommand>()
        val view = view(commands)
        view.updateMode(InputMode.DIRECT)
        view.updatePrimaryAction("検索")
        key(view, "123").performClick()
        key(view, "A").performClick()
        assertEquals(TouchKeyboardCommand.Skk(BasicSkkAction.SetInputMode(InputMode.HIRAGANA)), commands.single())
        assertNotNull(key(view, "検索"))
        view.updateMode(InputMode.DIRECT, enabled = false)
        assertFalse(key(view, "A").isEnabled)
        view.updatePrimaryAction("確定", enabled = false)
        assertFalse(key(view, "確定").isEnabled)
    }

    @Test fun `横画面と大きな文字でも文字キーの高さは増やさない`() {
        val base = RuntimeEnvironment.getApplication()
        val context = base.createConfigurationContext(Configuration(base.resources.configuration).apply {
            orientation = Configuration.ORIENTATION_LANDSCAPE
            fontScale = 2f
        })
        val q = key(TouchKeyboardView(context) {}, "q")
        assertTrue(q.layoutParams.height <= (38 * context.resources.displayMetrics.density).toInt())
        assertEquals(28f, q.textSize / context.resources.displayMetrics.density, .1f)
    }

    @Test @Config(sdk = [35]) fun `ナビゲーション余白は画面キーボードだけに適用する`() {
        val view = view()
        view.dispatchApplyWindowInsets(WindowInsets.Builder()
            .setInsets(WindowInsets.Type.navigationBars(), Insets.of(0, 0, 0, 24)).build())
        assertEquals(24, view.paddingBottom)
        view.setCharacterAreaVisible(false)
        assertEquals(0, view.paddingBottom)
        view.setCharacterAreaVisible(true)
        assertEquals(24, view.paddingBottom)
    }

    private fun flick(button: Button, delta: Float) {
        event(button, MotionEvent.ACTION_DOWN)
        event(button, MotionEvent.ACTION_MOVE, y = 10f + delta)
        event(button, MotionEvent.ACTION_UP, y = 10f + delta)
    }
    private fun laidOutView(context: Context = RuntimeEnvironment.getApplication(), width: Int = 480): TouchKeyboardView =
        TouchKeyboardView(context) {}.apply {
            measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(220, View.MeasureSpec.EXACTLY))
            layout(0, 0, width, 220)
        }
    private fun event(button: Button, action: Int, x: Float = 10f, y: Float = 10f) {
        MotionEvent.obtain(SystemClock.uptimeMillis(), SystemClock.uptimeMillis(), action, x, y, 0).also {
            button.dispatchTouchEvent(it)
            it.recycle()
        }
    }
    private fun key(view: View, label: String) = buttons(view).single { it.text.toString() == label }
    private fun guideOption(view: TouchKeyboardView, index: Int) =
        view.visibleFlickGuide?.getChildAt(index) as? TextView
            ?: throw AssertionError("フリックガイドが表示されていません")
    private fun buttons(view: View): List<Button> = descendants(view).filterIsInstance<Button>()
    private fun descendants(view: View): List<View> = listOf(view) +
        if (view is ViewGroup) (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()
}
