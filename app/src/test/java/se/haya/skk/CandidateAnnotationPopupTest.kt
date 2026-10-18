package se.haya.skk

import android.app.Activity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 35])
class CandidateAnnotationPopupTest {
    @Test fun `窓外の元のジェスチャーを背後へ通す非フォーカス窓で外側通知を受けて閉じる`() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val anchor = View(activity)
        activity.setContentView(anchor)
        var dismissals = 0
        val popup = CandidateAnnotationPopup(activity, "注釈") { dismissals++ }
        popup.show(anchor)
        assertTrue(popup.isShowing)
        assertFalse(popup.window.isFocusable)
        assertTrue(popup.window.isTouchable)
        assertTrue(popup.window.isOutsideTouchable)
        val decor = popup.content.rootView
        val flags = (decor.layoutParams as WindowManager.LayoutParams).flags
        // NOT_FOCUSABLE は窓外を遮断しないため、背後が元の DOWN から受け取ります。
        assertTrue(flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE != 0)
        assertTrue(flags and WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH != 0)
        val event = MotionEvent.obtain(0, 0, MotionEvent.ACTION_OUTSIDE, -1f, -1f, 0)
        try {
            decor.dispatchTouchEvent(event)
        } finally {
            event.recycle()
        }
        assertFalse(popup.isShowing)
        assertEquals(1, dismissals)
    }

    @Test fun `戻るの押下と指離しは注釈窓を閉じる操作として両方消費する`() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val surface = CandidateStatusView(activity)
        activity.setContentView(surface)
        val popup = CandidateAnnotationPopup(activity, "注釈") {}
        ReflectionHelpers.setField(surface, "annotationPopup", popup)
        popup.show(surface)
        val controller = Robolectric.buildService(SkkInputMethodService::class.java).create()
        val service = controller.get()
        ReflectionHelpers.setField(service, "candidateStatusView", surface)
        try {
            val down = KeyEvent(100, 100, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK, 0)
            assertTrue(service.onKeyDown(down.keyCode, down))
            assertFalse(popup.isShowing)
            assertTrue(service.onKeyUp(down.keyCode, KeyEvent.changeAction(down, KeyEvent.ACTION_UP)))
        } finally {
            controller.destroy()
        }
    }

    @Test fun `短い注釈は本文だけの寸法で表示し操作ボタンを置かない`() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val anchor = View(activity)
        activity.setContentView(anchor)
        val popup = CandidateAnnotationPopup(activity, "注釈") {}
        popup.show(anchor)
        assertEquals(1, popup.content.childCount)
        assertSame(popup.body, popup.content.getChildAt(0))
        val text = popup.body.adapter.getView(0, null, popup.body) as TextView
        val innerWidth = popup.window.width - popup.content.paddingLeft - popup.content.paddingRight
        text.measure(View.MeasureSpec.makeMeasureSpec(innerWidth, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        assertEquals(text.measuredHeight + popup.content.paddingTop + popup.content.paddingBottom,
            popup.window.height)
        assertTrue(popup.window.width <= (420 * activity.resources.displayMetrics.density).toInt())
        popup.dismiss()
    }

    @Test fun `長い注釈は窓内でスクロールする有界の行を再利用する`() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val annotation = "注釈".repeat(LongCandidateBodyView.CHUNK_SIZE)
        val popup = CandidateAnnotationPopup(activity, annotation) {}
        val adapter = popup.body.adapter
        assertTrue(popup.body.isVerticalScrollBarEnabled)
        assertTrue(adapter.count > 1)
        assertEquals(annotation, (0 until adapter.count).joinToString("") { adapter.getItem(it) as String })
        val row = adapter.getView(0, null, popup.body) as TextView
        val recycled = adapter.getView(1, row, popup.body) as TextView
        assertSame(row, recycled)
        assertEquals(adapter.getItem(1), recycled.text.toString())
        assertTrue(recycled.text.length <= LongCandidateBodyView.CHUNK_SIZE + 1)
    }
}
