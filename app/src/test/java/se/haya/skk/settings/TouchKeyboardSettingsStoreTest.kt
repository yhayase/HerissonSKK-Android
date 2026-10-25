package se.haya.skk.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 35])
class TouchKeyboardSettingsStoreTest {
    private val preferences get() = RuntimeEnvironment.getApplication()
        .getSharedPreferences("touch-keyboard-test", 0)

    @Before fun clearPreferences() { preferences.edit().clear().commit() }

    @Test fun `旧設定がない場合はQWERTYを選び既定値を返す`() {
        assertEquals(TouchKeyboardSettings(), TouchKeyboardSettingsStore.readFrom(preferences))
    }

    @Test fun `形状値は境界を含む範囲だけ読み込み型違いと範囲外は既定値に戻す`() {
        preferences.edit().putInt("ten_key_height_dp", 160).putInt("ten_key_width_percent", 60)
            .putInt("ten_key_position_percent", 100).putInt("ten_key_bottom_padding_dp", 160).commit()
        assertEquals(TouchKeyboardSettings(tenKeyHeightDp = 160, tenKeyWidthPercent = 60,
            tenKeyPositionPercent = 100, tenKeyBottomPaddingDp = 160), TouchKeyboardSettingsStore.readFrom(preferences))
        preferences.edit().putInt("ten_key_height_dp", 401).putString("ten_key_width_percent", "60")
            .putInt("ten_key_position_percent", -1).putString("ten_key_bottom_padding_dp", "0").commit()
        assertEquals(TouchKeyboardSettings(), TouchKeyboardSettingsStore.readFrom(preferences))
    }

    @Test fun `設定を一括保存して再読込し未設定の旧ユーザーも保持する`() {
        val expected = TouchKeyboardSettings(ScreenKeyboardLayout.TEN_KEY, TenKeyOperation.HOLD,
            250, false)
        assertTrue(TouchKeyboardSettingsStore.saveTo(preferences, expected))
        assertEquals(expected, TouchKeyboardSettingsStore.readFrom(preferences))
        assertEquals(setOf("touch_keyboard_layout", "ten_key_operation", "ten_key_hold_millis",
            "ten_key_vibrate_on_boundary", "ten_key_height_dp", "ten_key_width_percent",
            "ten_key_position_percent", "ten_key_bottom_padding_dp"), preferences.all.keys)
    }

    @Test fun `形状だけの保存では他の設定を変更しない`() {
        val settingsPreferences = RuntimeEnvironment.getApplication().getSharedPreferences("settings", 0)
        settingsPreferences.edit().clear().commit()
        val before = TouchKeyboardSettings(ScreenKeyboardLayout.TEN_KEY, TenKeyOperation.HOLD,
            250, false, 224, 80, 25, 48)
        assertTrue(TouchKeyboardSettingsStore.saveTo(settingsPreferences, before))
        val modes = TenKeyReadingModes(alpha = true)
        assertTrue(TenKeyReadingModeStore.saveTo(settingsPreferences, modes))
        assertTrue(TouchKeyboardSettingsStore.saveGeometry(RuntimeEnvironment.getApplication(), 300, 70, 75))
        assertEquals(before.copy(tenKeyHeightDp = 300, tenKeyWidthPercent = 70,
            tenKeyPositionPercent = 75), TouchKeyboardSettingsStore.readFrom(settingsPreferences))
        assertTrue(TouchKeyboardSettingsStore.saveGeometry(RuntimeEnvironment.getApplication(), 300, 70, 75, 160))
        assertEquals(160, TouchKeyboardSettingsStore.readFrom(settingsPreferences).tenKeyBottomPaddingDp)
        assertFalse(TouchKeyboardSettingsStore.saveGeometry(RuntimeEnvironment.getApplication(), 300, 70, 75, 161))
        assertFalse(TouchKeyboardSettingsStore.saveGeometry(RuntimeEnvironment.getApplication(), 401, 70, 75))
        assertEquals(modes, TenKeyReadingModeStore.readFrom(settingsPreferences))
    }

    @Test fun `配列だけの保存は操作形状と面別読み設定を保持する`() {
        val before = TouchKeyboardSettings(operation = TenKeyOperation.FLICK, holdMillis = 500,
            vibrateOnBoundary = false, tenKeyHeightDp = 320, tenKeyWidthPercent = 70,
            tenKeyPositionPercent = 80, tenKeyBottomPaddingDp = 72)
        assertTrue(TouchKeyboardSettingsStore.saveTo(preferences, before))
        val modes = TenKeyReadingModes(kana = true, alpha = true)
        assertTrue(TenKeyReadingModeStore.saveTo(preferences, modes))
        val previous = preferences.all.toMap()
        assertTrue(TouchKeyboardSettingsStore.saveLayoutTo(preferences, ScreenKeyboardLayout.TEN_KEY))
        assertEquals(before.copy(layout = ScreenKeyboardLayout.TEN_KEY), TouchKeyboardSettingsStore.readFrom(preferences))
        assertEquals(previous + ("touch_keyboard_layout" to "TEN_KEY"), preferences.all)
        assertEquals(modes, TenKeyReadingModeStore.readFrom(preferences))
    }

    @Test fun `配列保存の失敗時は元の値と型を復元する`() {
        for (previous in listOf<Any?>(null, "QWERTY", 9)) {
            preferences.edit().clear().commit()
            when (previous) {
                is String -> preferences.edit().putString("touch_keyboard_layout", previous).commit()
                is Int -> preferences.edit().putInt("touch_keyboard_layout", previous).commit()
            }
            assertFalse(TouchKeyboardSettingsStore.saveLayoutTo(preferences, ScreenKeyboardLayout.TEN_KEY) {
                it.commit(); false
            })
            assertEquals(previous, preferences.all["touch_keyboard_layout"])
        }
    }

    @Test fun `不正な保存値は保存せず不正な保存済み値は既定値へ戻す`() {
        preferences.edit().putString("touch_keyboard_layout", "UNKNOWN")
            .putString("ten_key_operation", "UNKNOWN").putInt("ten_key_hold_millis", 999).apply()
        assertEquals(TouchKeyboardSettings(), TouchKeyboardSettingsStore.readFrom(preferences))
        val valid = TouchKeyboardSettings(ScreenKeyboardLayout.TEN_KEY, TenKeyOperation.HOLD, 250, false)
        assertTrue(TouchKeyboardSettingsStore.saveTo(preferences, valid))
        assertEquals(valid, TouchKeyboardSettingsStore.readFrom(preferences))
    }

    @Test fun `ホールド時間は100から1000msの10ms刻みを受け付け不正値を既定値へ戻す`() {
        for (value in listOf(100, 350, 1000)) {
            preferences.edit().putInt("ten_key_hold_millis", value).commit()
            assertEquals(value, TouchKeyboardSettingsStore.readFrom(preferences).holdMillis)
            assertTrue(TouchKeyboardSettingsStore.saveTo(preferences, TouchKeyboardSettings(holdMillis = value)))
        }
        for (value in listOf(99, 1001, 101, 349)) {
            preferences.edit().putInt("ten_key_hold_millis", value).commit()
            assertEquals(350, TouchKeyboardSettingsStore.readFrom(preferences).holdMillis)
            assertTrue(runCatching { TouchKeyboardSettings(holdMillis = value) }.exceptionOrNull() is IllegalArgumentException)
        }
    }

    @Test fun `設定の保存と標準値への保存は現在の面別状態と旧設定を変更しない`() {
        preferences.edit().putBoolean("ten_key_auto_start_reading", true).commit()
        val modes = TenKeyReadingModes(kana = true, number = true)
        assertTrue(TenKeyReadingModeStore.saveTo(preferences, modes))
        assertTrue(TouchKeyboardSettingsStore.saveTo(preferences,
            TouchKeyboardSettings(layout = ScreenKeyboardLayout.TEN_KEY)))
        assertTrue(TouchKeyboardSettingsStore.saveTo(preferences, TouchKeyboardSettings()))
        assertEquals(modes, TenKeyReadingModeStore.readFrom(preferences))
        assertTrue(preferences.getBoolean("ten_key_auto_start_reading", false))
    }

    @Test fun `commit失敗時はメモリー上の値も元へ戻す`() {
        preferences.edit().putString("touch_keyboard_layout", "QWERTY")
            .putString("ten_key_operation", "FLICK").putInt("ten_key_hold_millis", 500)
            .putBoolean("ten_key_vibrate_on_boundary", true).commit()
        val before = TouchKeyboardSettingsStore.readFrom(preferences)
        val attempted = TouchKeyboardSettings(ScreenKeyboardLayout.TEN_KEY, TenKeyOperation.HOLD, 250, false)
        assertFalse(TouchKeyboardSettingsStore.saveTo(preferences, attempted) { editor ->
            editor.commit()
            false
        })
        assertEquals(before, TouchKeyboardSettingsStore.readFrom(preferences))
    }
}
