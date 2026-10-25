package se.haya.skk.input

import android.text.InputType
import org.junit.Assert.*
import org.junit.Test
import se.haya.skk.settings.TenKeyOperation

class TenKeyControllerTest {
    private val center = TenKeyController.Direction.CENTER
    private val left = TenKeyController.Direction.LEFT
    private val up = TenKeyController.Direction.UP
    private val right = TenKeyController.Direction.RIGHT
    private val metrics = FlickMetrics(24f, 16f, 250f, 8f)
    private fun gesture(output: (TenKeyController.Direction, TenKeyController.Direction?) -> String? = { direction, modifier -> "$direction/$modifier" }) =
        TenKeyGesture(metrics, TenKeyOperation.HOLD_AND_RETURN, 100f, 50f, output)
    private fun path(vararg points: Pair<Float, Float>) = gesture().apply {
        start(0f, 0f)
        points.forEach { (x, y) -> move(x, y) }
    }
    @Test fun `同じ入力種別の再開は手動選択を維持し種別変更と新規開始では初期面を選ぶ`() {
        val controller = TenKeyController()
        val password = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        controller.startEditorSession(password)
        controller.cycleFace()
        controller.wideNumber = true
        controller.startEditorSession(password, restarting = true)
        assertEquals(TenKeyController.Face.NUMBER, controller.face)
        assertTrue(controller.wideNumber)
        controller.startEditorSession(InputType.TYPE_CLASS_NUMBER, restarting = true)
        assertEquals(TenKeyController.Face.NUMBER, controller.face)
        assertFalse(controller.wideNumber)
        controller.cycleFace()
        controller.startEditorSession(InputType.TYPE_CLASS_NUMBER, restarting = true)
        assertEquals(TenKeyController.Face.KANA, controller.face)
        controller.startEditorSession(InputType.TYPE_CLASS_NUMBER)
        assertEquals(TenKeyController.Face.NUMBER, controller.face)
        controller.startEditorSession(InputType.TYPE_CLASS_TEXT, restarting = true)
        assertEquals(TenKeyController.Face.KANA, controller.face)
    }

    @Test fun `特殊欄の初期面は通常欄の文字種を上書きしない`() {
        val controller = TenKeyController().apply {
            katakana = true
            cycleFace()
            wideAlpha = true
            wideNumber = true
            uppercase = true
        }
        controller.startEditorSession(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        assertEquals(TenKeyController.Face.ALPHA, controller.face)
        assertEquals("a", controller.output(TenKeyController.ALPHA[1], TenKeyController.Direction.CENTER, null))
        controller.cycleFace()
        controller.toggleKind()
        controller.startEditorSession(InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD)
        assertEquals("1", controller.output(TenKeyController.NUMBER[0], TenKeyController.Direction.CENTER, null))
        controller.cycleFace()
        controller.toggleKind()
        controller.startEditorSession(InputType.TYPE_CLASS_TEXT)
        assertEquals(TenKeyController.Face.ALPHA, controller.face)
        assertTrue(controller.katakana)
        assertTrue(controller.wideAlpha)
        assertTrue(controller.wideNumber)
        assertTrue(controller.uppercase)
        controller.cycleFace()
        controller.startEditorSession(InputType.TYPE_CLASS_TEXT)
        assertEquals(TenKeyController.Face.NUMBER, controller.face)
    }

    @Test fun `濁音半濁音小書きと数字を最後の方向から選ぶ`() {
        val controller = TenKeyController()
        assertEquals("ぐ", controller.output(TenKeyController.KANA[1],up,left))
        assertEquals("ぷ", controller.output(TenKeyController.KANA[5],up,right))
        assertEquals("っ", controller.output(TenKeyController.KANA[3],up,right))
        assertEquals("ゃ", controller.output(TenKeyController.KANA[7],center,right))
        assertEquals("2", controller.output(TenKeyController.KANA[1],right,up))
        assertEquals("0", controller.output(TenKeyController.KANA[10],center,up))
    }

    @Test fun `各面の文字種を独立して保持し英字を直接選ぶ`() {
        val controller = TenKeyController(); controller.toggleKind()
        controller.cycleFace(); controller.toggleKind()
        assertEquals("ｄ",controller.output(TenKeyController.ALPHA[2],center,null))
        assertEquals("Ｄ",controller.output(TenKeyController.ALPHA[2],center,right))
        controller.cycleFace(); controller.toggleKind()
        assertEquals("１",controller.output(TenKeyController.NUMBER[0],center,null))
        assertEquals("-",controller.output(TenKeyController.NUMBER[9],center,null))
        controller.cycleFace()
        assertEquals("ア",controller.output(TenKeyController.KANA[0],center,null))
        assertEquals("１",controller.output(TenKeyController.KANA[0],center,up))
    }

    @Test fun `英字二十六文字は下方向なしですべて選べる`() {
        val controller = TenKeyController(); controller.cycleFace()
        val outputs = TenKeyController.ALPHA.subList(1,9).flatMap { row -> listOf(center,left,up,right).mapNotNull { controller.output(row,it,null) } }.joinToString("")
        assertEquals(('a'..'z').toSet(),outputs.toSet())
        assertNull(controller.output(TenKeyController.ALPHA[6],TenKeyController.Direction.DOWN,null))
    }

    @Test fun `行と面で有効な修飾だけに案内名がある`() {
        val controller = TenKeyController()
        assertEquals("濁点", controller.modifierLabel(TenKeyController.KANA[1], left))
        assertEquals("半濁点", controller.modifierLabel(TenKeyController.KANA[5], right))
        assertEquals("小文字", controller.modifierLabel(TenKeyController.KANA[3], right))
        assertNull(controller.modifierLabel(TenKeyController.KANA[4], left))
        assertNull(controller.modifierLabel(TenKeyController.KANA[2], right))
        assertEquals("数字", controller.modifierLabel(TenKeyController.KANA[1], up))
        assertNull(controller.modifierLabel(TenKeyController.KANA[11], up))
        controller.cycleFace()
        assertEquals("大文字", controller.modifierLabel(TenKeyController.ALPHA[1], right))
        controller.cycleFace()
        assertNull(controller.modifierLabel(TenKeyController.NUMBER[0], up))
    }
    @Test fun `方向を出ると修飾を適用し同じ方向では解除する`() {
        val g = path(-30f to 0f, 0f to 0f)
        assertEquals(left, g.modifier)
        assertEquals(up, g.move(0f, -30f))
        assertEquals(left, g.modifier)
        assertEquals("ぐ", TenKeyController().output(TenKeyController.KANA[1], up, g.modifier))
        g.move(0f, 0f)
        assertEquals(up, g.modifier)
        g.move(0f, -30f); g.move(0f, 0f)
        assertNull(g.modifier)
        g.move(-30f, 0f); g.move(30f, 0f); g.move(0f, 0f)
        assertEquals(right, g.modifier)
        g.move(0f, 30f); g.move(0f, 0f)
        assertNull(g.modifier)
    }
    @Test fun `疎な線分と細かい線分で修飾が一致する`() {
        val vertices = listOf(-40f to -20f, -20f to -40f, 40f to 0f, 0f to 0f)
        val sparse = path(*vertices.toTypedArray())
        val dense = path()
        var previous = 0f to 0f
        vertices.forEach { next ->
            for (step in 1..100) dense.move(previous.first + (next.first - previous.first) * step / 100,
                previous.second + (next.second - previous.second) * step / 100)
            previous = next
        }
        assertEquals(sparse.modifier, dense.modifier)
        assertEquals(sparse.direction, dense.direction)
        assertEquals(left, path(-40f to 0f, 40f to 0f).modifier)
    }
    @Test fun `環状帯と曖昧な角度は確定領域を維持する`() {
        val g = path(-30f to 0f)
        assertEquals(left, g.move(-20f, 0f))
        assertEquals(left, g.move(-30f, -30f))
        assertNull(g.modifier)
        assertEquals(up, g.move(-25f, -30f))
        assertEquals(left, g.modifier)
        assertEquals(center, g.move(0f, -16f))
        assertEquals(up, g.modifier)
    }
    @Test fun `ホールド前は固定位置からの変位と領域変更で時間を測り直す`() {
        val g = gesture { _, _ -> "同じ" }
        g.start(0f, 0f)
        g.move(7f, 0f); g.move(-7f, 0f)
        assertEquals(0, g.motionRevision)
        g.move(9f, 0f)
        assertEquals(1, g.motionRevision)
        g.move(30f, 0f)
        assertEquals(2, g.motionRevision)
        assertTrue(g.hold())
        g.move(40f, 0f)
        assertTrue(g.boundary)
    }
    @Test fun `ホールドの解除は最終出力と一キーの距離で判定する`() {
        val g = gesture { _, _ -> "同じ" }
        g.start(0f, 0f); g.move(7f, 0f); assertTrue(g.hold())
        g.move(100f, 0f)
        assertTrue(g.boundary)
        assertEquals(0, g.motionRevision)
        g.move(107f, 0f)
        assertFalse(g.boundary)
        assertEquals(1, g.motionRevision)
        assertTrue(g.hold())
        assertNull(g.finish(107f, 250f))
        assertFalse(g.boundary)
        assertFalse(g.hold())
        listOf(100f to 0f, 0f to 50f, 80f to 30f).forEach { (x, y) ->
            val h = gesture { _, _ -> "同じ" }
            h.start(0f, 0f); h.hold(); h.move(x * .99f, y * .99f)
            assertTrue(h.boundary)
            h.finish(x, y)
            assertFalse(h.boundary)
        }
    }
    @Test fun `線分途中の実際の出力変更は疎密どちらでもホールドを解除する`() {
        val controller = TenKeyController()
        val row = TenKeyController.KANA[1]
        fun prepared() = TenKeyGesture(metrics, TenKeyOperation.HOLD_AND_RETURN, 100f, 100f) { direction, modifier ->
            controller.output(row, direction, modifier)
        }.apply {
            start(0f, 0f)
            move(0f, -40f)
            move(-40f, -25f)
            assertEquals(up, modifier)
            assertEquals("2", controller.output(row, direction!!, modifier))
            assertTrue(hold())
        }
        val sparse = prepared()
        val dense = prepared()
        val revision = sparse.motionRevision
        sparse.move(40f, -25f)
        dense.move(0f, -25f)
        assertEquals("ぐ", controller.output(row, dense.direction!!, dense.modifier))
        assertFalse(dense.boundary)
        dense.move(40f, -25f)
        assertEquals("2", controller.output(row, sparse.direction!!, sparse.modifier))
        assertEquals(sparse.direction, dense.direction)
        assertEquals(sparse.modifier, dense.modifier)
        assertFalse(sparse.boundary)
        assertFalse(dense.boundary)
        assertEquals(revision + 1, sparse.motionRevision)
        assertTrue(sparse.hold())
    }
    @Test fun `修飾後の文字変更はホールドを解除し効果のない修飾は維持する`() {
        val controller = TenKeyController()
        val rows = listOf(TenKeyController.KANA[1], TenKeyController.KANA[0], TenKeyController.KANA[1])
        val modifiers = listOf(left, right, up)
        (rows.zip(modifiers) + listOf(TenKeyController.ALPHA[6] to right)).forEachIndexed { index, (row, selected) ->
            if (index == 3) controller.cycleFace()
            val g = gesture { direction, modifier -> controller.output(row, direction, modifier) }
            g.start(0f, 0f)
            val (x, y) = when (selected) { left -> -30f to 0f; right -> 30f to 0f; else -> 0f to -30f }
            g.move(x, y); assertTrue(g.hold())
            val revision = g.motionRevision
            g.move(0f, 0f)
            assertFalse(g.boundary)
            assertEquals(revision + 1, g.motionRevision)
            assertTrue(g.hold())
        }
        val kana = TenKeyController()
        val g = gesture { _, modifier -> kana.output(TenKeyController.KANA[4], center, modifier) }
        g.start(0f, 0f); g.move(-30f, 0f); g.hold(); g.move(0f, 0f)
        assertEquals(left, g.modifier)
        assertTrue(g.boundary)
    }
    @Test fun `未確定と空文字ではホールドを有効にしない`() {
        val ambiguous = path(30f to 30f)
        assertFalse(ambiguous.hold())
        val empty = gesture { _, _ -> "" }
        empty.start(0f, 0f); assertFalse(empty.hold())
        val flick = TenKeyGesture(metrics, TenKeyOperation.FLICK)
        flick.start(0f, 0f); assertFalse(flick.hold())
        flick.move(-30f, 0f); flick.move(0f, 0f)
        assertNull(flick.modifier)
    }
    @Test fun `離す座標は方向と修飾を変えず境界の距離だけを解除する`() {
        val g = path(-30f to 0f)
        assertEquals(left, g.finish(0f, 0f))
        assertNull(g.modifier)
        assertFalse(g.active)
        val h = gesture { _, _ -> "同じ" }
        h.start(0f, 0f); h.hold()
        assertNull(h.finish(100f, 0f))
        assertFalse(h.boundary)
        val small = gesture { _, _ -> "同じ" }
        small.start(0f, 0f); small.hold()
        assertEquals(center, small.finish(7f, 0f))
        assertTrue(small.boundary)
    }
    @Test fun `入力取消後は戻ってもタイマーでも復活しない`() {
        val g = path(250f to 0f)
        assertTrue(g.cancelled)
        assertFalse(g.active)
        assertNull(g.move(0f, 0f))
        assertFalse(g.hold())
        assertNull(g.finish(0f, 0f))
    }
}
