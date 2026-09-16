package jp.hayase.skk

import android.provider.Settings
import android.content.ActivityNotFoundException
import org.junit.Assert.assertEquals
import org.junit.Test

class SettingsActivityTest {
    @Test
    fun `物理キーボード設定を実際に開けるときは専用画面だけを起動する`() {
        val actions = mutableListOf<String>()

        val result = dispatchPhysicalKeyboardLayoutSettings(hardKeyboardSettingsAvailable = true) { actions += it }

        assertEquals(PhysicalKeyboardLayoutSettingsDispatch.HARD_KEYBOARD, result)
        assertEquals(listOf(Settings.ACTION_HARD_KEYBOARD_SETTINGS), actions)
    }

    @Test
    fun `専用画面の起動失敗時は入力方法設定を実際に起動する`() {
        val actions = mutableListOf<String>()

        val result = dispatchPhysicalKeyboardLayoutSettings(hardKeyboardSettingsAvailable = true) { action ->
            actions += action
            if (action == Settings.ACTION_HARD_KEYBOARD_SETTINGS) throw ActivityNotFoundException()
        }

        assertEquals(PhysicalKeyboardLayoutSettingsDispatch.INPUT_METHOD_FALLBACK, result)
        assertEquals(
            listOf(Settings.ACTION_HARD_KEYBOARD_SETTINGS, Settings.ACTION_INPUT_METHOD_SETTINGS),
            actions,
        )
    }

    @Test
    fun `専用画面の起動が拒否されたときも入力方法設定を実際に起動する`() {
        val actions = mutableListOf<String>()

        val result = dispatchPhysicalKeyboardLayoutSettings(hardKeyboardSettingsAvailable = true) { action ->
            actions += action
            if (action == Settings.ACTION_HARD_KEYBOARD_SETTINGS) throw SecurityException()
        }

        assertEquals(PhysicalKeyboardLayoutSettingsDispatch.INPUT_METHOD_FALLBACK, result)
        assertEquals(
            listOf(Settings.ACTION_HARD_KEYBOARD_SETTINGS, Settings.ACTION_INPUT_METHOD_SETTINGS),
            actions,
        )
    }

    @Test
    fun `両方の設定画面を起動できないときは失敗を返す`() {
        val result = dispatchPhysicalKeyboardLayoutSettings(hardKeyboardSettingsAvailable = false) {
            throw SecurityException()
        }

        assertEquals(PhysicalKeyboardLayoutSettingsDispatch.UNAVAILABLE, result)
    }
}
