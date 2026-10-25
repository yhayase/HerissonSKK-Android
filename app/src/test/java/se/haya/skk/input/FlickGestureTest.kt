package se.haya.skk.input

import org.junit.Assert.*
import org.junit.Test

class FlickGestureTest {
    private val metrics = FlickMetrics(24f, 16f, 250f, 8f)
    private fun gesture() = FlickGesture(metrics).apply { start(0f, 0f) }
    @Test fun `開始と中央復帰と取消は始点からの直線距離を使う`() {
        val g = gesture()
        g.move(23.99f, 0f)
        assertEquals(FlickGesture.Region.CENTER, g.region)
        g.move(24f, 0f)
        assertEquals(FlickGesture.Region.RIGHT, g.region)
        g.move(16.01f, 0f)
        assertEquals(FlickGesture.Region.RIGHT, g.region)
        g.move(16f, 0f)
        assertEquals(FlickGesture.Region.CENTER, g.region)
        g.move(150f, 200f)
        assertTrue(g.cancelled)
        g.move(0f, 0f)
        assertNull(g.region)
        assertNull(g.finish(0f, 0f))
    }
    @Test fun `初回の斜めは未確定で確定後の曖昧な角度は維持する`() {
        val g = gesture()
        g.move(20f, 20f)
        assertNull(g.region)
        assertNull(g.finish(20f, 20f))
        val h = gesture()
        h.move(30f, 25f)
        assertEquals(FlickGesture.Region.RIGHT, h.region)
        h.move(30f, 30f)
        assertEquals(FlickGesture.Region.RIGHT, h.region)
        h.move(25f, 30f)
        assertEquals(FlickGesture.Region.DOWN, h.region)
    }
    @Test fun `離す位置だけでは認識も方向変更も中央復帰もしない`() {
        assertNull(gesture().finish(0f, -24f))
        assertEquals(FlickGesture.Region.CENTER, gesture().finish(0f, -23f))
        val g = gesture()
        g.move(0f, -30f)
        assertEquals(FlickGesture.Region.UP, g.finish(0f, 30f))
        assertNull(g.finish(0f, -30f))
        val h = gesture()
        h.move(0f, -30f)
        assertEquals(FlickGesture.Region.UP, h.finish(0f, 0f))
        val abort = gesture()
        abort.move(0f, -30f)
        assertNull(abort.finish(0f, 250f))
        assertTrue(abort.cancelled)
    }
    @Test fun `線分内の中央と反対方向を順番に報告する`() {
        val g = gesture()
        g.move(-40f, 0f)
        assertEquals(listOf(
            FlickGesture.Transition(FlickGesture.Region.LEFT, FlickGesture.Region.CENTER),
            FlickGesture.Transition(FlickGesture.Region.CENTER, FlickGesture.Region.RIGHT)), g.move(40f, 0f))
    }
    @Test fun `QWERTYは左右を取消に対応させ中央復帰をタップにする`() {
        val g = KeyFlickGesture(metrics)
        g.start(0f, 0f)
        assertNull(g.direction(-30f, 0f))
        assertNull(g.finish(-30f, 0f))
        g.start(0f, 0f)
        assertEquals(TouchKeyboardController.Flick.UP, g.direction(0f, -30f))
        assertEquals(TouchKeyboardController.Flick.TAP, g.direction(0f, -16f))
        assertEquals(TouchKeyboardController.Flick.TAP, g.finish(0f, -23f))
    }
}
