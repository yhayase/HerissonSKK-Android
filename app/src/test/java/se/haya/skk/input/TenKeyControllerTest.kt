package se.haya.skk.input

import android.text.InputType

import org.junit.Assert.*
import org.junit.Test
import se.haya.skk.settings.TenKeyOperation

class TenKeyControllerTest {
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

    private val center = TenKeyController.Direction.CENTER
    private val left = TenKeyController.Direction.LEFT
    private val up = TenKeyController.Direction.UP
    private val right = TenKeyController.Direction.RIGHT
    @Test fun `往復で修飾を切り替え同じ往復で解除し移動で境界を取り消す`() {
        val gesture = TenKeyGesture(10f, TenKeyOperation.HOLD_AND_RETURN)
        gesture.start(0f,0f)
        assertTrue(gesture.hold()); assertFalse(gesture.hold())
        gesture.move(-20f,0f); gesture.move(0f,0f)
        assertEquals(left,gesture.modifier)
        gesture.move(20f,0f); gesture.move(0f,0f)
        assertEquals(right,gesture.modifier)
        gesture.move(20f,0f); gesture.move(0f,0f)
        assertNull(gesture.modifier); assertFalse(gesture.boundary)
        gesture.cancel(); assertFalse(gesture.hold())
    }
    private fun heldGesture(width: Float = 100f, height: Float = 50f,
        output: (TenKeyController.Direction, TenKeyController.Direction?) -> String? = { _, _ -> "同じ文字" },
    ) = TenKeyGesture(20f, TenKeyOperation.HOLD_AND_RETURN, 8f, 2f, width, height, output)

    @Test fun `同じ文字ならスロップを超えてもホールドを保ち認識位置から一キーで取り消す`() {
        val gesture = heldGesture()
        gesture.start(0f, 0f)
        gesture.move(7f, 0f)
        assertTrue(gesture.hold())
        gesture.move(100f, 0f)
        assertTrue(gesture.boundary)
        assertEquals(0, gesture.motionRevision)
        gesture.move(107f, 0f)
        assertFalse(gesture.boundary)
        assertEquals(1, gesture.motionRevision)
        assertTrue(gesture.hold())
        gesture.move(110f, 0f)
        assertTrue(gesture.boundary)
    }
    @Test fun `縦横と斜めの移動をキーの縦横寸法で正規化する`() {
        listOf(100f to 0f, 0f to 50f, 80f to 30f).forEach { (x, y) ->
            val gesture = heldGesture()
            gesture.start(0f, 0f); gesture.hold()
            gesture.move(x * .99f, y * .99f)
            assertTrue(gesture.boundary)
            gesture.move(x, y)
            assertFalse(gesture.boundary)
        }
    }
    @Test fun `出力文字の濁音小書き数字と英字大小の変更は一キー未満でも取り消す`() {
        val controller = TenKeyController()
        val rows = listOf(TenKeyController.KANA[1], TenKeyController.KANA[0], TenKeyController.KANA[1])
        val modifiers = listOf(left, right, up)
        (rows.zip(modifiers) + listOf(TenKeyController.ALPHA[1] to right)).forEachIndexed { index, (row, selected) ->
            if (index == 3) controller.cycleFace()
            val gesture = heldGesture { direction, modifier -> controller.output(row, direction, modifier) }
            gesture.start(0f, 0f)
            val (x, y) = when (selected) { left -> -30f to 0f; right -> 30f to 0f; else -> 0f to -30f }
            gesture.move(x, y); gesture.hold()
            gesture.move(0f, 0f)
            assertFalse(gesture.boundary)
        }
    }
    @Test fun `効果のない修飾への変更では出力文字が同じなら境界を保つ`() {
        val controller = TenKeyController()
        val gesture = heldGesture { _, modifier -> controller.output(TenKeyController.KANA[4], center, modifier) }
        gesture.start(0f, 0f); gesture.move(-30f, 0f); gesture.hold(); gesture.move(0f, 0f)
        assertEquals(left, gesture.modifier)
        assertTrue(gesture.boundary)
    }
    @Test fun `フリック方式ではホールドも往復も修飾しない`() {
        val gesture = TenKeyGesture(10f, TenKeyOperation.FLICK)
        gesture.start(0f,0f); assertFalse(gesture.hold())
        gesture.move(-20f,0f); gesture.move(0f,0f)
        assertNull(gesture.modifier)
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
    @Test fun `中央のサンプルを省いた左右通過と密な軌跡は同じ修飾になる`() {
        fun path(vararg points: Pair<Float, Float>): TenKeyGesture = TenKeyGesture(10f, TenKeyOperation.HOLD_AND_RETURN).apply {
            start(0f, 0f)
            points.forEach { (x, y) -> move(x, y) }
        }
        val sparse = path(-20f to 0f, 20f to 0f)
        val dense = path(-20f to 0f, -10f to 0f, 0f to 0f, 10f to 0f, 20f to 0f)
        assertEquals(left, sparse.modifier)
        assertEquals(dense.modifier, sparse.modifier)
        sparse.move(0f, 0f); dense.move(0f, 0f)
        assertEquals(right, sparse.modifier)
        assertEquals(dense.modifier, sparse.modifier)
        assertEquals(right, path(20f to 0f, -20f to 0f).modifier)
        assertEquals(up, path(0f to -20f, 0f to 20f).modifier)
        // 元の中央領域の端寄りでも、十分に戻れば指の開始点との一致を要求しません。
        assertEquals(left, path(-25f to 8f, 8f to 8f).modifier)
    }
    private fun path(vararg points: Pair<Float, Float>) = TenKeyGesture(10f, TenKeyOperation.HOLD_AND_RETURN, 8f, 2f).apply {
        start(0f, 0f)
        points.forEach { (x, y) -> move(x, y) }
    }
    @Test fun `方向から別の領域へ出ると修飾を切り替え中央は修飾しない`() {
        val gesture = path(-30f to 0f, 0f to -30f)
        assertEquals(left, gesture.modifier)
        assertEquals("ぐ", TenKeyController().output(TenKeyController.KANA[1], up, gesture.modifier))
        gesture.move(0f, 0f)
        assertEquals(up, gesture.modifier)
        assertEquals("2", TenKeyController().output(TenKeyController.KANA[1], center, gesture.modifier))
        assertNull(path(-30f to 0f, 0f to 0f, -30f to 0f, 0f to 0f).modifier)
        assertEquals(right, path(-30f to 0f, 30f to 0f, 0f to 0f).modifier)
        assertNull(path(-30f to 0f, 0f to 0f, 0f to 30f, 0f to 0f).modifier)
    }
    @Test fun `境界の四ピクセル帯では確定領域を保持する`() {
        val gesture = path(-30f to 0f)
        repeat(5) { gesture.move(-11.9f, 0f); gesture.move(-8.1f, 0f) }
        assertNull(gesture.modifier)
        gesture.move(-7f, 0f)
        assertEquals(left, gesture.modifier)
        repeat(5) { gesture.move(-11.9f, 0f); gesture.move(-8.1f, 0f) }
        assertEquals(left, gesture.modifier)
        gesture.move(-13f, 0f); gesture.move(-7f, 0f)
        assertNull(gesture.modifier)
    }
    @Test fun `境界からちょうど二ピクセル内側で領域を確定する`() {
        val gesture = path(-30f to 0f)
        gesture.move(-8f, 0f)
        assertEquals(left, gesture.modifier)
        gesture.move(-12f, 0f)
        gesture.move(-8f, 0f)
        assertNull(gesture.modifier)
        val tangent = path(-20f to 4f, 4f to -20f)
        val subdivided = path(-20f to 4f, -8f to -8f, 4f to -20f)
        assertEquals(tangent.modifier, subdivided.modifier)
    }
    @Test fun `斜め境界からの垂直距離が二ピクセルを超えるまで保持する`() {
        val gesture = path(-30f to -20f)
        assertEquals(up, gesture.move(-20f, -22f)) // 出力方向は確定領域と独立です。
        assertNull(gesture.modifier)
        gesture.move(-20f, -23f)
        assertEquals(left, gesture.modifier)
        gesture.move(-22f, -20f)
        assertEquals(left, gesture.modifier)
        gesture.move(-23f, -20f)
        assertEquals(up, gesture.modifier)
    }
    @Test fun `斜め境界の二ピクセル直前と直後を区別する`() {
        val gesture = path(-30f to -20f)
        val margin = 20.0 + 2.0 * kotlin.math.sqrt(2.0)
        val rounded = margin.toFloat()
        val inside = if (rounded.toDouble() < margin) Math.nextUp(rounded) else rounded
        gesture.move(-20f, -Math.nextDown(inside))
        assertNull(gesture.modifier)
        gesture.move(-20f, -inside)
        assertEquals(left, gesture.modifier)
    }
    @Test fun `線分の分割と境界上のサンプルは修飾を変えない`() {
        val vertices = listOf(-30f to -20f, -20f to -30f, 30f to 0f, 0f to 0f)
        assertEquals(up, path(-30f to -13f, 30f to -13f).modifier)
        val sparse = path(*vertices.toTypedArray())
        val dense = path()
        var previous = 0f to 0f
        vertices.forEach { next ->
            for (step in 1..100) dense.move(previous.first + (next.first - previous.first) * step / 100,
                previous.second + (next.second - previous.second) * step / 100)
            previous = next
        }
        assertEquals(sparse.modifier, dense.modifier)
        val boundary = path(-30f to 0f, -12f to 0f, -8f to 0f, -7f to 0f)
        assertEquals(path(-30f to 0f, -7f to 0f).modifier, boundary.modifier)
    }
    @Test fun `行に効果がない方向でも直前の修飾を置き換える`() {
        val gesture = path(-30f to 0f, 0f to 0f, 30f to 0f, 0f to 0f)
        assertEquals(right, gesture.modifier)
        assertEquals("さ", TenKeyController().output(TenKeyController.KANA[2], center, gesture.modifier))
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

    @Test fun `中央内部を短く通る軌跡と角に触れるだけの軌跡を区別する`() {
        // h=2 の中央確定範囲は一辺16です。x+y=-16 はその角に到達します。
        val tangent = path(-20f to 4f, 4f to -20f)
        val through = path(-20f to 5f, 5f to -20f)
        assertEquals(left, tangent.modifier)
        assertEquals(left, through.modifier)
        through.move(0f, 0f)
        assertEquals(up, through.modifier)
        val dense = path(-20f to 4f, -8f to -8f, 4f to -20f)
        assertEquals(tangent.modifier, dense.modifier)
    }

}
