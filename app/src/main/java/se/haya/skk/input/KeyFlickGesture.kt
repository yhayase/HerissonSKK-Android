package se.haya.skk.input

import kotlin.math.abs

/** ガイド待ち時間とは独立して、離した位置からタップ・上下フリックを決めます。 */
internal class KeyFlickGesture(private val threshold: Float) {
    private var originX = 0f
    private var originY = 0f
    private var active = false

    fun start(x: Float, y: Float) {
        originX = x
        originY = y
        active = true
    }

    val isActive: Boolean get() = active

    fun direction(x: Float, y: Float): TouchKeyboardController.Flick? {
        if (!active) return null
        val dx = x - originX
        val dy = y - originY
        if (abs(dx) < threshold && abs(dy) < threshold) return TouchKeyboardController.Flick.TAP
        if (abs(dy) < threshold || abs(dx) > abs(dy)) return null
        return if (dy < 0) TouchKeyboardController.Flick.UP else TouchKeyboardController.Flick.DOWN
    }

    fun finish(x: Float, y: Float): TouchKeyboardController.Flick? = direction(x, y).also { active = false }
    fun cancel() { active = false }
}
