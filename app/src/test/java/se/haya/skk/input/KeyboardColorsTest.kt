package se.haya.skk.input

import android.content.res.Configuration
import android.graphics.Color
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.math.pow

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 35])
class KeyboardColorsTest {
    private fun colors(night: Boolean): KeyboardColors {
        val base = RuntimeEnvironment.getApplication()
        val configuration = Configuration(base.resources.configuration).apply {
            uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                if (night) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
        }
        return KeyboardColors.resolve(base.createConfigurationContext(configuration))
    }

    @Test fun `昼は白い文字キーと黒い文字になる`() {
        val colors = colors(false)
        assertEquals(Color.WHITE, colors.characterBackground)
        assertEquals(Color.BLACK, colors.text)
        assertTrue(luminance(colors.characterBackground) > luminance(colors.functionBackground))
    }

    @Test fun `夜は文字キーより機能キーを暗くし全キーの文字を読める`() {
        val colors = colors(true)
        assertTrue(luminance(colors.characterBackground) < .15)
        assertTrue(luminance(colors.functionBackground) < luminance(colors.characterBackground))
        assertTrue(luminance(colors.text) > .7)
        listOf(colors.characterBackground, colors.functionBackground, colors.panelBackground,
            colors.selectedBackground, colors.pressed).forEach { background ->
            assertTrue("文字と背景のコントラストが不足しています", contrast(colors.text, background) >= 4.5)
        }
    }

    @Test fun `設定変更後は現在の夜間モードから色を再解決する`() {
        assertNotEquals(colors(false), colors(true))
        val colors = colors(false)
        listOf(colors.characterBackground, colors.functionBackground, colors.panelBackground,
            colors.selectedBackground, colors.pressed).forEach { background ->
            assertTrue(contrast(colors.text, background) >= 4.5)
        }
    }

    private fun luminance(color: Int): Double {
        fun channel(value: Int): Double {
            val srgb = value / 255.0
            return if (srgb <= .04045) srgb / 12.92 else ((srgb + .055) / 1.055).pow(2.4)
        }
        return .2126 * channel(Color.red(color)) + .7152 * channel(Color.green(color)) + .0722 * channel(Color.blue(color))
    }

    private fun contrast(first: Int, second: Int): Double {
        val a = luminance(first)
        val b = luminance(second)
        return (maxOf(a, b) + .05) / (minOf(a, b) + .05)
    }
}
