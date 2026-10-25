package se.haya.skk.input

import android.app.Activity
import android.content.Context
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.Window
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 35])
class KeyboardTouchWindowCallbackTest {
    private class Receiver(context: Context) : View(context) {
        val events = mutableListOf<MotionEvent>()
        var receivedRawY = 0f
        var receivedHistory = 0
        var handled = true
        override fun dispatchTouchEvent(event: MotionEvent): Boolean {
            events += event
            receivedRawY = event.rawY
            receivedHistory = event.historySize
            return handled
        }
    }

    private class Fixture {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val window = activity.window
        val receiver = Receiver(activity)
        val delegated = mutableListOf<MotionEvent>()
        var keyCalls = 0
        var contentCalls = 0
        var captures = true
        var hitTests = 0
        val original = object : Window.Callback by window.callback {
            override fun dispatchTouchEvent(event: MotionEvent): Boolean {
                delegated += event
                return true
            }
            override fun dispatchKeyEvent(event: KeyEvent): Boolean { keyCalls++; return true }
            override fun onContentChanged() { contentCalls++ }
        }
        val callback: KeyboardTouchWindowCallback
        init {
            activity.setContentView(receiver)
            window.decorView.measure(exact(480), exact(800))
            window.decorView.layout(0, 0, 480, 800)
            window.callback = original
            callback = KeyboardTouchWindowCallback.install(window) { _, _ -> hitTests++; captures }
        }
        fun event(action: Int, y: Float = 120f) = MotionEvent.obtain(10, 20, action, 100f, y, 0)
        private fun exact(size: Int) = View.MeasureSpec.makeMeasureSpec(size, View.MeasureSpec.EXACTLY)
    }

    @Test fun `所有した操作は元の座標と履歴を一度だけビューへ渡す`() {
        val f = Fixture()
        val attributes = WindowManagerSnapshot(f.window)
        val down = f.event(MotionEvent.ACTION_DOWN)
        assertTrue(f.window.decorView.dispatchTouchEvent(down))
        val move = f.event(MotionEvent.ACTION_MOVE, 110f).apply { addBatch(30, 100f, -715f, 1f, 1f, 0) }
        assertTrue(f.window.decorView.dispatchTouchEvent(move))
        assertEquals(listOf(down, move), f.receiver.events)
        assertSame(move, f.receiver.events.last())
        assertEquals(-715f, f.receiver.receivedRawY, 0f)
        assertEquals(1, f.receiver.receivedHistory)
        assertEquals(-715f, move.rawY, 0f)
        assertTrue(f.delegated.isEmpty())
        assertEquals(1, f.hitTests)
        attributes.assertUnchanged(f.window)
        f.callback.dispose()
    }

    @Test fun `未処理の所有イベントを元のコールバックへ再配送しない`() {
        val f = Fixture()
        f.receiver.handled = false
        assertFalse(f.callback.dispatchTouchEvent(f.event(MotionEvent.ACTION_DOWN)))
        assertEquals(1, f.receiver.events.size)
        assertTrue(f.delegated.isEmpty())
    }

    @Test fun `複数指と領域外移動でも終了まで所有し終了後は新しい開始を判定する`() {
        for (terminal in listOf(MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL)) {
            val f = Fixture()
            f.callback.dispatchTouchEvent(f.event(MotionEvent.ACTION_DOWN))
            f.captures = false
            val pointers = arrayOf(0, 1).map { id -> MotionEvent.PointerProperties().apply { this.id = id } }.toTypedArray()
            val coordinates = arrayOf(100f, 200f).map { x -> MotionEvent.PointerCoords().apply { this.x = x; y = 120f } }.toTypedArray()
            val second = MotionEvent.obtain(10, 21, MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),
                2, pointers, coordinates, 0, 0, 1f, 1f, 0, 0, 0, 0)
            f.callback.dispatchTouchEvent(second)
            val releaseSecond = MotionEvent.obtain(10, 22, MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),
                2, pointers, coordinates, 0, 0, 1f, 1f, 0, 0, 0, 0)
            f.callback.dispatchTouchEvent(releaseSecond)
            f.callback.dispatchTouchEvent(f.event(MotionEvent.ACTION_MOVE, -715f))
            f.callback.dispatchTouchEvent(f.event(terminal, -715f))
            assertTrue(f.delegated.isEmpty())
            assertEquals(1, f.hitTests)
            val next = f.event(MotionEvent.ACTION_DOWN)
            f.callback.dispatchTouchEvent(next)
            assertEquals(listOf(next), f.delegated)
            assertEquals(2, f.hitTests)
        }
    }

    @Test fun `文字面以外で始まった操作は途中で文字面へ入っても元へ渡す`() {
        val f = Fixture()
        f.captures = false
        val events = listOf(f.event(MotionEvent.ACTION_DOWN), f.event(MotionEvent.ACTION_MOVE), f.event(MotionEvent.ACTION_UP))
        events.forEach { f.callback.dispatchTouchEvent(it); f.captures = true }
        assertEquals(events, f.delegated)
        assertTrue(f.receiver.events.isEmpty())
        assertEquals(1, f.hitTests)
    }

    @Test fun `リセットと破棄で所有を解除し元のコールバックを条件付きで復元する`() {
        val f = Fixture()
        f.callback.dispatchTouchEvent(f.event(MotionEvent.ACTION_DOWN))
        f.callback.reset()
        val move = f.event(MotionEvent.ACTION_MOVE)
        f.callback.dispatchTouchEvent(move)
        assertEquals(listOf(move), f.delegated)
        assertTrue(f.callback.isInstalledOn(f.window))
        f.callback.dispose()
        assertSame(f.original, f.window.callback)
        assertFalse(f.callback.isInstalledOn(f.window))
        val down = f.event(MotionEvent.ACTION_DOWN)
        f.callback.dispatchTouchEvent(down)
        assertEquals(listOf(move, down), f.delegated)
        val next = KeyboardTouchWindowCallback.install(f.window) { _, _ -> true }
        val replacement = object : Window.Callback by f.original {}
        f.window.callback = replacement
        next.dispose()
        assertSame(replacement, f.window.callback)
    }

    @Test fun `キーと他の窓コールバックは元へそのまま委譲する`() {
        val f = Fixture()
        assertTrue(f.callback.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_SHIFT_LEFT)))
        f.callback.onContentChanged()
        assertEquals(1, f.keyCalls)
        assertEquals(1, f.contentCalls)
        assertTrue(f.delegated.isEmpty())
    }

    @Test fun `文字面の開始判定は画面座標を使い候補面と非表示面を除く`() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val keyboard = TouchKeyboardView(activity) {}
        activity.setContentView(keyboard, android.view.ViewGroup.LayoutParams(480, 320))
        val decor = activity.window.decorView
        decor.measure(View.MeasureSpec.makeMeasureSpec(480, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.EXACTLY))
        decor.layout(0, 0, 480, 800)
        decor.translationY = 400f
        val area = keyboard.getChildAt(1)
        val location = IntArray(2)
        area.getLocationOnScreen(location)
        val visible = android.graphics.Rect()
        val hasVisibleArea = area.getLocalVisibleRect(visible)
        val metrics = "screen=${location.contentToString()}, size=${area.width}x${area.height}, visible=$visible"
        assertTrue(metrics, location[1] >= 400)
        assertTrue(metrics, hasVisibleArea)
        val x = location[0] + area.width / 2f
        val y = location[1] + area.height / 2f
        assertTrue(metrics, visible.contains(area.width / 2, area.height / 2))
        assertTrue(metrics, keyboard.isCharacterAreaHit(x, y))
        assertFalse(keyboard.isCharacterAreaHit(x, location[1] - 1f))
        assertFalse(keyboard.isCharacterAreaHit(location[0] - 1f, y))
        keyboard.setCharacterAreaVisible(false)
        assertFalse(keyboard.isCharacterAreaHit(x, y))
    }

    private class WindowManagerSnapshot(window: Window) {
        private val attributes = android.view.WindowManager.LayoutParams().apply { copyFrom(window.attributes) }
        fun assertUnchanged(window: Window) {
            val current = android.view.WindowManager.LayoutParams().apply { copyFrom(window.attributes) }
            assertEquals(0, current.copyFrom(attributes))
        }
    }
}
