package se.haya.skk.input

import android.app.Activity
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.FrameLayout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import se.haya.skk.R
import se.haya.skk.settings.ScreenKeyboardLayout
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26])
class LayoutSwitchKeyTest {
    private class Fixture(adjust: Boolean = true) {
        val calls = mutableListOf<String>()
        var current = true
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val key = LayoutSwitchKey(activity, ScreenKeyboardLayout.QWERTY,
            { calls += "layout" }, { calls += "ime" },
            if (adjust) ({ calls += "adjust" }) else null, { current })
        val root = FrameLayout(activity)
        init {
            root.addView(key, FrameLayout.LayoutParams(100, 100))
            activity.setContentView(root)
            root.measure(View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY))
            root.layout(0, 0, 400, 400)
            assertTrue(key.isAttachedToWindow)
        }
        fun touch(action: Int, x: Float = 50f, y: Float = 50f) {
            val now = SystemClock.uptimeMillis()
            MotionEvent.obtain(now, now, action, x, y, 0).also { key.dispatchTouchEvent(it); it.recycle() }
        }
    }

    @Test fun `タップと各方向は離した時だけ一度確定する`() {
        for ((point, expected) in listOf(
            (50f to 50f) to listOf("layout"),
            (130f to 50f) to listOf("ime"),
            (50f to -30f) to listOf("adjust"),
            (-30f to 50f) to emptyList(),
            (50f to 130f) to emptyList(),
        )) {
            val f = Fixture()
            f.touch(MotionEvent.ACTION_DOWN)
            assertTrue(f.key.isGuideShowing)
            f.touch(MotionEvent.ACTION_MOVE, point.first, point.second)
            assertTrue(f.calls.isEmpty())
            f.touch(MotionEvent.ACTION_UP, point.first, point.second)
            f.touch(MotionEvent.ACTION_UP, point.first, point.second)
            assertEquals(expected, f.calls)
            assertFalse(f.key.isGuideShowing)
        }
    }

    @Test fun `調整なしのキーでは上フリックが何も実行しない`() {
        val f = Fixture(adjust = false)
        f.touch(MotionEvent.ACTION_DOWN)
        f.touch(MotionEvent.ACTION_UP, 50f, -30f)
        assertTrue(f.calls.isEmpty())
    }

    @Test fun `ガイドの中央セルは下端の元キーに重なり調整セルは上に出る`() {
        for (adjust in listOf(true, false)) {
            val f = Fixture(adjust)
            f.key.layout(0, 250, 100, 350)
            val location = IntArray(2).also { f.key.getLocationOnScreen(it) }
            f.touch(MotionEvent.ACTION_DOWN)
            val bounds = f.key.guideBounds!!
            val cell = bounds.width() / 2
            assertEquals(cell * if (adjust) 2 else 1, bounds.height())
            assertEquals(location[0] + f.key.width / 2, bounds.left + cell / 2)
            assertEquals(location[1] + f.key.height / 2, bounds.bottom - cell / 2)
            assertTrue(bounds.top < location[1] + f.key.height / 2)
            assertEquals(400, f.root.height)
            f.touch(MotionEvent.ACTION_CANCEL)
            assertNull(f.key.guideBounds)
        }
    }

    @Test fun `外へ動いて戻った操作と履歴だけの移動はタップにならない`() {
        val f = Fixture()
        f.touch(MotionEvent.ACTION_DOWN)
        f.touch(MotionEvent.ACTION_MOVE, 130f, 50f)
        f.touch(MotionEvent.ACTION_UP)
        assertTrue(f.calls.isEmpty())
        f.touch(MotionEvent.ACTION_DOWN)
        val now = SystemClock.uptimeMillis()
        MotionEvent.obtain(now, now, MotionEvent.ACTION_MOVE, 130f, 50f, 0).also {
            it.addBatch(now + 1, 50f, 50f, 1f, 1f, 0)
            f.key.dispatchTouchEvent(it)
            it.recycle()
        }
        f.touch(MotionEvent.ACTION_UP)
        assertTrue(f.calls.isEmpty())
    }

    @Test fun `長押し中は実行せず中央で離した時だけタップとして確定する`() {
        val f = Fixture()
        f.touch(MotionEvent.ACTION_DOWN)
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(
            ViewConfiguration.getLongPressTimeout().toLong() * 2, TimeUnit.MILLISECONDS)
        assertTrue(f.calls.isEmpty())
        f.touch(MotionEvent.ACTION_UP)
        assertFalse(f.key.performLongClick())
        assertEquals(listOf("layout"), f.calls)
        assertFalse(f.key.showAlternativeHint)
        assertEquals("", f.key.text.toString())
    }

    @Test fun `キャンセルと複数指は確定せずガイドを閉じる`() {
        for (action in listOf(MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_POINTER_DOWN)) {
            val f = Fixture()
            f.touch(MotionEvent.ACTION_DOWN)
            f.touch(MotionEvent.ACTION_MOVE, 130f, 50f)
            f.touch(action)
            assertFalse(f.key.isGuideShowing)
            f.touch(MotionEvent.ACTION_UP, 130f, 50f)
            assertTrue(f.calls.isEmpty())
        }
    }

    @Test fun `無効化と破棄と世代変更と切り離しは進行中の操作を取り消す`() {
        for (cancel in listOf<(Fixture) -> Unit>(
            { it.key.isEnabled = false }, { it.key.dispose() },
            { it.current = false }, { it.root.removeView(it.key) }, { it.key.cancelInteraction() },
        )) {
            val f = Fixture()
            f.touch(MotionEvent.ACTION_DOWN)
            f.touch(MotionEvent.ACTION_MOVE, 130f, 50f)
            cancel(f)
            f.touch(MotionEvent.ACTION_UP, 130f, 50f)
            assertTrue(f.calls.isEmpty())
            assertFalse(f.key.isGuideShowing)
        }
        val f = Fixture()
        f.current = false
        assertFalse(f.key.performClick())
        f.current = true
        f.key.dispose()
        assertFalse(f.key.performClick())
    }

    @Test fun `アクセシビリティはタップと入力方法と任意の調整を提供する`() {
        for (adjust in listOf(true, false)) {
            val f = Fixture(adjust)
            val info = AccessibilityNodeInfo.obtain()
            f.key.onInitializeAccessibilityNodeInfo(info)
            assertEquals("QWERTYへ切替", info.contentDescription)
            assertFalse(info.isLongClickable)
            assertTrue(info.actionList.any { it.id == R.id.layout_switch_ime })
            assertEquals(adjust, info.actionList.any { it.id == R.id.layout_switch_geometry })
            assertTrue(f.key.performClick())
            assertTrue(f.key.performAccessibilityAction(R.id.layout_switch_ime, null))
            assertEquals(adjust, f.key.performAccessibilityAction(R.id.layout_switch_geometry, null))
            assertEquals(if (adjust) listOf("layout", "ime", "adjust") else listOf("layout", "ime"), f.calls)
            info.recycle()
        }
    }

    @Test fun `配列ごとに異なる中央アイコンを使い文字は描かない`() {
        val f = Fixture()
        val ten = LayoutSwitchKey(f.activity, ScreenKeyboardLayout.TEN_KEY, {}, {})
        ten.layout(0, 0, 100, 100)
        assertEquals(R.drawable.ic_keyboard_qwerty, f.key.targetIconResource)
        assertEquals(R.drawable.ic_keyboard_ten_key, ten.targetIconResource)
        assertNotNull(ten.centeredIcon)
        assertEquals(50f, ten.centeredIconBounds().centerX())
        assertEquals(50f, ten.centeredIconBounds().centerY())
        assertEquals("", ten.text.toString())
        assertFalse(ten.showAlternativeHint)
    }
}
