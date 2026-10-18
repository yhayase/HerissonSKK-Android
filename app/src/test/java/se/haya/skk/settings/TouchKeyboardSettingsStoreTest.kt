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
            250, false, tenKeyAutoStartReading = true)
        assertTrue(TouchKeyboardSettingsStore.saveTo(preferences, expected))
        assertEquals(expected, TouchKeyboardSettingsStore.readFrom(preferences))
        assertEquals(setOf("touch_keyboard_layout", "ten_key_operation", "ten_key_hold_millis",
            "ten_key_vibrate_on_boundary", "ten_key_height_dp", "ten_key_width_percent",
            "ten_key_position_percent", "ten_key_bottom_padding_dp", "ten_key_auto_start_reading"), preferences.all.keys)
    }

    @Test fun `形状だけの保存では他の設定を変更しない`() {
        val settingsPreferences = RuntimeEnvironment.getApplication().getSharedPreferences("settings", 0)
        settingsPreferences.edit().clear().commit()
        val before = TouchKeyboardSettings(ScreenKeyboardLayout.TEN_KEY, TenKeyOperation.HOLD,
            250, false, 224, 80, 25, 48, tenKeyAutoStartReading = true)
        assertTrue(TouchKeyboardSettingsStore.saveTo(settingsPreferences, before))
        assertTrue(TouchKeyboardSettingsStore.saveGeometry(RuntimeEnvironment.getApplication(), 300, 70, 75))
        assertEquals(before.copy(tenKeyHeightDp = 300, tenKeyWidthPercent = 70,
            tenKeyPositionPercent = 75), TouchKeyboardSettingsStore.readFrom(settingsPreferences))
        assertTrue(TouchKeyboardSettingsStore.saveGeometry(RuntimeEnvironment.getApplication(), 300, 70, 75, 160))
        assertEquals(160, TouchKeyboardSettingsStore.readFrom(settingsPreferences).tenKeyBottomPaddingDp)
        assertFalse(TouchKeyboardSettingsStore.saveGeometry(RuntimeEnvironment.getApplication(), 300, 70, 75, 161))
        assertFalse(TouchKeyboardSettingsStore.saveGeometry(RuntimeEnvironment.getApplication(), 401, 70, 75))
    }

    @Test fun `不正な保存値は保存せず不正な保存済み値は既定値へ戻す`() {
        preferences.edit().putString("touch_keyboard_layout", "UNKNOWN")
            .putString("ten_key_operation", "UNKNOWN").putInt("ten_key_hold_millis", 999).apply()
        assertEquals(TouchKeyboardSettings(), TouchKeyboardSettingsStore.readFrom(preferences))
        val valid = TouchKeyboardSettings(ScreenKeyboardLayout.TEN_KEY, TenKeyOperation.HOLD, 250, false)
        assertTrue(TouchKeyboardSettingsStore.saveTo(preferences, valid))
        assertEquals(valid, TouchKeyboardSettingsStore.readFrom(preferences))
    }

    @Test fun `自動読み開始の型違いは無効へ戻す`() {
        preferences.edit().putString("ten_key_auto_start_reading", "true").commit()
        assertFalse(TouchKeyboardSettingsStore.readFrom(preferences).tenKeyAutoStartReading)
    }

    @Test fun `保存失敗では既存の自動読み開始設定も元へ戻す`() {
        preferences.edit().putBoolean("ten_key_auto_start_reading", true).commit()
        assertFalse(TouchKeyboardSettingsStore.saveTo(preferences, TouchKeyboardSettings()) { editor ->
            editor.commit()
            false
        })
        assertTrue(TouchKeyboardSettingsStore.readFrom(preferences).tenKeyAutoStartReading)
    }

    @Test fun `commit失敗時はメモリー上の値も元へ戻す`() {
        preferences.edit().putString("touch_keyboard_layout", "QWERTY")
            .putString("ten_key_operation", "FLICK").putInt("ten_key_hold_millis", 500)
            .putBoolean("ten_key_vibrate_on_boundary", true).commit()
        val before = TouchKeyboardSettingsStore.readFrom(preferences)
        val attempted = TouchKeyboardSettings(ScreenKeyboardLayout.TEN_KEY, TenKeyOperation.HOLD, 250, false,
            tenKeyAutoStartReading = true)
        assertFalse(TouchKeyboardSettingsStore.saveTo(preferences, attempted) { editor ->
            editor.commit()
            false
        })
        assertEquals(before, TouchKeyboardSettingsStore.readFrom(preferences))
    }
}
