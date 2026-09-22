package se.haya.skk.input

import se.haya.skk.core.BasicSkkAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class TouchKeyboardControllerTest {
    @Test fun `Shiftは次の一文字だけを大文字の論理入力にする`() {
        val controller = TouchKeyboardController()
        controller.toggleShift()

        assertEquals(BasicSkkAction.Text("N"), controller.text("n").action)
        assertFalse(controller.shifted)
        assertEquals(BasicSkkAction.Text("i"), controller.text("i").action)
    }

    @Test fun `記号面への切替でShiftを持ち越さない`() {
        val controller = TouchKeyboardController()
        controller.toggleShift()
        controller.togglePage()

        assertEquals(TouchKeyboardController.Page.SYMBOLS_1, controller.page)
        assertFalse(controller.shifted)
        assertEquals(BasicSkkAction.Text("1"), controller.text("1").action)
    }

    @Test fun `入力欄の切替でShiftと記号面を初期化する`() {
        val controller = TouchKeyboardController()
        controller.togglePage()
        controller.toggleSymbolPage()

        controller.reset()

        assertEquals(TouchKeyboardController.Page.LETTERS, controller.page)
        assertFalse(controller.shifted)
    }

    @Test fun `記号面の切替は英字面へ戻らず明示的に切り替える`() {
        val controller = TouchKeyboardController()
        controller.togglePage()

        controller.toggleSymbolPage()

        assertEquals(TouchKeyboardController.Page.SYMBOLS_2, controller.page)
        assertEquals(BasicSkkAction.Text("+"), controller.text("+").action)
        assertEquals(TouchKeyboardController.Page.SYMBOLS_2, controller.page)
        controller.togglePage()
        assertEquals(TouchKeyboardController.Page.LETTERS, controller.page)
    }
    @Test fun `英字26キーの下フリックを固定し大文字フリックはShiftで反転しない`() {
        val c = TouchKeyboardController()
        val expected = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "0",
            "@", "#", "$", "%", "&", "(", ")", "[", "]", "_", "\"", "'", "<", ">", ":", "/")
        "qwertyuiopasdfghjklzxcvbnm".forEachIndexed { i, ch ->
            assertEquals(expected[i], c.down(ch.toString()))
        }
        c.toggleShift()
        assertEquals(BasicSkkAction.Text("Q"), c.text("q", TouchKeyboardController.Flick.UP).action)
        assertFalse(c.shifted)
    }

    @Test fun `押下Shiftの取消はトグルを作らず横方向のフリックは入力にならない`() {
        val c = TouchKeyboardController()
        c.pressShift()
        c.releaseShift(cancelled = true)
        assertFalse(c.shifted)
        val gesture = KeyFlickGesture(10f)
        gesture.start(0f, 0f)
        assertEquals(null, gesture.finish(40f, 5f))
        gesture.start(0f, 0f)
        gesture.cancel()
        assertEquals(null, gesture.finish(0f, -40f))
    }
}
