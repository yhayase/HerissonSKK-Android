package se.haya.skk.input

import android.view.MotionEvent
import android.view.ViewConfiguration
import android.widget.Button

/** 接触開始時に実行し、400ms後から60ms間隔で繰り返します。戻り値で予約を取り消します。 */
@android.annotation.SuppressLint("ClickableViewAccessibility")
internal fun attachKeyRepeat(button: Button, isCurrent: () -> Boolean = { true }, action: () -> Unit): () -> Unit {
    val slop = ViewConfiguration.get(button.context).scaledTouchSlop
    var repeating = false
    val repeat = object : Runnable {
        override fun run() {
            if (!repeating || !isCurrent()) return
            action()
            if (repeating && isCurrent()) button.postDelayed(this, 60)
        }
    }
    fun clear() { repeating = false; button.removeCallbacks(repeat); button.isPressed = false }
    button.setOnTouchListener { _, event ->
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> if (isCurrent()) {
                clear(); repeating = true; button.isPressed = true
                action()
                if (repeating && isCurrent()) button.postDelayed(repeat, 400)
            }
            MotionEvent.ACTION_MOVE -> if (event.x < -slop || event.x > button.width + slop || event.y < -slop || event.y > button.height + slop) clear()
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_POINTER_DOWN -> clear()
        }
        true
    }
    return ::clear
}
