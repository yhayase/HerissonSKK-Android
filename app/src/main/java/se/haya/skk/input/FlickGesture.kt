package se.haya.skk.input

import android.content.Context
import android.view.ViewConfiguration
import kotlin.math.abs
import kotlin.math.sqrt

/** 距離はすべて押下位置を基準とするピクセル値です。 */
internal data class FlickMetrics(val activationPx: Float, val returnPx: Float, val cancelPx: Float, val slopPx: Float) {
    init {
        require(returnPx >= 0f && activationPx > returnPx && cancelPx > activationPx && slopPx >= 0f)
    }
    companion object {
        fun fromContext(context: Context): FlickMetrics {
            val density = context.resources.displayMetrics.density
            val slop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
            return FlickMetrics(maxOf(24f * density, slop), 16f * density, 250f * density, slop)
        }
    }
}

/** 接触中の観測点間を直線で結び、通過した確定領域を順番に返します。 */
internal class FlickGesture(private val metrics: FlickMetrics) {
    enum class Region { CENTER, LEFT, UP, RIGHT, DOWN }
    data class Transition(val from: Region?, val to: Region?)
    var region: Region? = Region.CENTER
        private set
    var active = false
        private set
    var cancelled = false
        private set
    private var originX = 0f
    private var originY = 0f
    private var lastX = 0.0
    private var lastY = 0.0
    private var confirmedDirection = false

    fun start(x: Float, y: Float) {
        originX = x; originY = y; lastX = 0.0; lastY = 0.0
        active = true; cancelled = false; region = Region.CENTER; confirmedDirection = false
    }
    private fun outside(x: Double, y: Double) = x * x + y * y >= metrics.cancelPx.toDouble() * metrics.cancelPx
    fun move(x: Float, y: Float): List<Transition> {
        if (!active) return emptyList()
        val toX = (x - originX).toDouble(); val toY = (y - originY).toDouble()
        if (outside(toX, toY)) { cancel(); return emptyList() }
        val dx = toX - lastX; val dy = toY - lastY
        val cuts = mutableListOf(0.0, 1.0)
        fun cut(t: Double) { if (t > 0.0 && t < 1.0) cuts.add(t) }
        fun circle(radius: Float) {
            val a = dx * dx + dy * dy
            if (a == 0.0) return
            val b = 2.0 * (lastX * dx + lastY * dy)
            val c = lastX * lastX + lastY * lastY - radius.toDouble() * radius
            val discriminant = b * b - 4.0 * a * c
            if (discriminant >= 0.0) {
                val root = sqrt(discriminant)
                cut((-b - root) / (2.0 * a)); cut((-b + root) / (2.0 * a))
            }
        }
        circle(metrics.returnPx); circle(metrics.activationPx)
        // 四つの直線で軸優位の境界をすべて分割します。
        listOf(1.0 to 1.2, 1.0 to -1.2, 1.2 to 1.0, 1.2 to -1.0).forEach { (a, b) ->
            val delta = a * dx + b * dy
            if (delta != 0.0) cut(-(a * lastX + b * lastY) / delta)
        }
        val transitions = mutableListOf<Transition>()
        fun sample(t: Double) {
            val px = lastX + dx * t; val py = lastY + dy * t
            val radius2 = px * px + py * py
            val next = when {
                radius2 <= metrics.returnPx.toDouble() * metrics.returnPx -> Region.CENTER
                radius2 < metrics.activationPx.toDouble() * metrics.activationPx -> region
                abs(px) >= 1.2 * abs(py) -> if (px < 0) Region.LEFT else Region.RIGHT
                abs(py) >= 1.2 * abs(px) -> if (py < 0) Region.UP else Region.DOWN
                confirmedDirection -> region
                else -> null
            }
            if (next != null && next != Region.CENTER) confirmedDirection = true
            if (next != region) { transitions.add(Transition(region, next)); region = next }
        }
        val sorted = cuts.distinct().sorted()
        sorted.forEachIndexed { index, t ->
            sample(t)
            if (index < sorted.lastIndex) sample((t + sorted[index + 1]) / 2.0)
        }
        lastX = toX; lastY = toY
        return transitions
    }
    /** UP座標は取消だけに使用し、選択を変更しません。 */
    fun finish(x: Float, y: Float): Region? {
        if (!active) return null
        val dx = (x - originX).toDouble(); val dy = (y - originY).toDouble()
        if (outside(dx, dy) || (!confirmedDirection && dx * dx + dy * dy >= metrics.activationPx.toDouble() * metrics.activationPx)) {
            cancel(); return null
        }
        active = false
        return region
    }
    fun cancel() { active = false; cancelled = true; region = null }
}
