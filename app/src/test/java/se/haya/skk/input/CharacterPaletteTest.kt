package se.haya.skk.input

import android.app.Activity
import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.GridView
import android.widget.HorizontalScrollView
import android.widget.PopupWindow
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 35])
class CharacterPaletteTest {
    private fun attachedAnchor(): View {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        return View(activity).also {
            activity.setContentView(it)
            it.layout(0, 0, 320, 200)
        }
    }

    @Test fun `未接続のキーではウィンドウを作らない`() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val palette = CharacterPalette(activity) { fail("選択していません") }
        palette.show(View(activity))
        assertNull(palette.popup)
    }

    @Test fun `ASCII記号と日本語記号を分類から探せる`() {
        val values = CharacterPalette.CATEGORIES.flatMap { it.entries }.map { it.value }.toSet()
        val ascii = (33..126).map(Int::toChar).filterNot(Char::isLetterOrDigit).map(Char::toString)
        assertTrue(values.containsAll(ascii))
        assertTrue(values.containsAll(listOf("。", "、", "「", "」", "￥", "€", "←", "∑")))
        assertTrue(CharacterPalette.CATEGORIES.all { it.entries.isNotEmpty() })
    }

    @Test fun `分類は横へ文字一覧は縦へ移動できて画面内に収まる`() {
        val anchor = attachedAnchor()
        val palette = CharacterPalette(anchor.context) {}
        palette.show(anchor)
        val window = palette.popup!!
        val frame = Rect().also(anchor::getWindowVisibleDisplayFrame)
        assertTrue(window.isShowing)
        assertTrue(window.width <= frame.width())
        assertTrue(window.height <= frame.height())
        assertFalse(window.isFocusable)
        assertEquals(PopupWindow.INPUT_METHOD_NOT_NEEDED, window.inputMethodMode)
        assertEquals(0, window.animationStyle)
        val root = window.contentView
        assertEquals(1, descendants(root).filterIsInstance<HorizontalScrollView>().size)
        val grid = descendants(root).filterIsInstance<GridView>().single()
        assertTrue(grid.isVerticalScrollBarEnabled)
        assertEquals(CharacterPalette.CATEGORIES.first().entries.size, grid.adapter.count)
        key(root, "自然・食べ物").performClick()
        assertTrue(key(root, "自然・食べ物").isSelected)
        assertFalse(key(root, "記号").isSelected)
        assertEquals(CharacterPalette.CATEGORIES.single { it.label == "自然・食べ物" }.entries.size, grid.adapter.count)
        // 画面外の候補にもアダプター経由で移動でき、全候補のViewを常設しません。
        grid.setSelection(grid.adapter.count - 1)
        assertTrue(grid.childCount < grid.adapter.count)
        palette.dismiss()
        assertFalse(window.isShowing)
    }

    @Test fun `結合絵文字や旗を分割せず連続入力して明示的に閉じる`() {
        val anchor = attachedAnchor()
        val selected = mutableListOf<String>()
        val palette = CharacterPalette(anchor.context) { selected += it }
        palette.show(anchor)
        val window = palette.popup!!
        val root = window.contentView
        val grid = descendants(root).filterIsInstance<GridView>().single()
        fun choose(category: String, value: String) {
            key(root, category).performClick()
            val entries = CharacterPalette.CATEGORIES.single { it.label == category }.entries
            val index = entries.indexOfFirst { it.value == value }
            assertTrue(index >= 0)
            val button = grid.adapter.getView(index, null, grid) as Button
            assertTrue(button.contentDescription.isNotEmpty())
            button.performClick()
            assertTrue(window.isShowing)
        }
        choose("顔・人", "👩‍💻")
        choose("顔・人", "👍🏽")
        choose("暮らし", "🇯🇵")
        choose("暮らし", "❤️")
        assertEquals(listOf("👩‍💻", "👍🏽", "🇯🇵", "❤️"), selected)
        key(root, "閉じる").performClick()
        assertFalse(window.isShowing)
        assertNull(palette.popup)
    }

    @Test fun `再表示や親からの終了で古いウィンドウを残さない`() {
        val anchor = attachedAnchor()
        val palette = CharacterPalette(anchor.context) {}
        palette.show(anchor)
        val previous = palette.popup!!
        palette.show(anchor)
        assertFalse(previous.isShowing)
        assertTrue(palette.popup!!.isShowing)
        palette.dismiss()
        palette.dismiss()
        assertNull(palette.popup)
    }

    @Test fun `測定に使った先頭候補も再利用後に接続して読み上げから選べる`() {
        val anchor = attachedAnchor()
        val selected = mutableListOf<String>()
        val palette = CharacterPalette(anchor.context) { selected += it }
        palette.show(anchor)
        val window = palette.popup!!
        val root = window.contentView
        key(root, "顔・人").performClick()
        val grid = descendants(root).filterIsInstance<GridView>().single()
        // PopupWindowの接続を再現しない環境でも、実際のActivityで候補の接続を検証します。
        (grid.parent as ViewGroup).removeView(grid)
        palette.dismiss()
        (anchor.context as Activity).setContentView(grid)
        grid.measure(View.MeasureSpec.makeMeasureSpec(window.width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(window.height, View.MeasureSpec.EXACTLY))
        grid.layout(0, 0, grid.measuredWidth, grid.measuredHeight)
        val first = grid.getChildAt(0) as Button
        assertEquals("😀", first.text.toString())
        assertEquals("笑顔", first.contentDescription)
        assertSame(grid, first.parent)
        assertTrue(first.isAttachedToWindow)
        val matches = arrayListOf<View>()
        grid.findViewsWithText(matches, "笑顔", View.FIND_VIEWS_WITH_CONTENT_DESCRIPTION)
        assertTrue(matches.contains(first))
        first.performClick()
        assertEquals(listOf("😀"), selected)
    }

    private fun key(root: View, label: String): Button = descendants(root).filterIsInstance<Button>()
        .first { it.text.toString() == label }

    private fun descendants(view: View): List<View> = listOf(view) +
        if (view is ViewGroup) (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()
}
