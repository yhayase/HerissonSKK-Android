package se.haya.skk.settings

import android.content.res.Configuration
import android.content.res.Configuration.ORIENTATION_LANDSCAPE
import android.content.res.Configuration.ORIENTATION_PORTRAIT
import android.content.res.Configuration.ORIENTATION_UNDEFINED
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
    private val context get() = RuntimeEnvironment.getApplication()
    private val preferences get() = context.getSharedPreferences("touch-keyboard-test", 0)
    private val settingsPreferences get() = context.getSharedPreferences("settings", 0)

    @Before fun clearPreferences() {
        preferences.edit().clear().commit()
        settingsPreferences.edit().clear().commit()
    }

    @Test fun `旧設定がない場合はQWERTYを選び既定値を返す`() {
        assertEquals(TouchKeyboardSettings(), TouchKeyboardSettingsStore.readFrom(preferences))
    }

    @Test fun `形状値は境界を含む範囲だけ読み込み型違いと範囲外は既定値に戻す`() {
        preferences.edit().putInt("ten_key_height_dp", 100).putInt("ten_key_width_percent", 10)
            .putInt("ten_key_position_percent", 100).putInt("ten_key_bottom_padding_dp", 160).commit()
        assertEquals(TouchKeyboardSettings(tenKeyHeightDp = 100, tenKeyWidthPercent = 10,
            tenKeyPositionPercent = 100, tenKeyBottomPaddingDp = 160), TouchKeyboardSettingsStore.readFrom(preferences))
        preferences.edit().putInt("ten_key_height_dp", 401).putString("ten_key_width_percent", "60")
            .putInt("ten_key_position_percent", -1).putString("ten_key_bottom_padding_dp", "0").commit()
        assertEquals(TouchKeyboardSettings(), TouchKeyboardSettingsStore.readFrom(preferences))
    }

    @Test fun `一括保存は共通設定と指定した向きの形状を書き込む`() {
        val expected = TouchKeyboardSettings(ScreenKeyboardLayout.TEN_KEY, TenKeyOperation.HOLD,
            250, false)
        assertTrue(TouchKeyboardSettingsStore.saveTo(preferences, expected))
        assertEquals(expected, TouchKeyboardSettingsStore.readFrom(preferences))
        assertEquals(setOf("touch_keyboard_layout", "ten_key_operation", "ten_key_hold_millis",
            "ten_key_vibrate_on_boundary", "ten_key_height_dp_portrait", "ten_key_width_percent_portrait",
            "ten_key_position_percent_portrait", "ten_key_bottom_padding_dp_portrait", "qwerty_split_portrait",
            "qwerty_gap_percent_portrait", "qwerty_height_dp_portrait", "qwerty_width_percent_portrait",
            "qwerty_position_percent_portrait", "qwerty_bottom_padding_dp_portrait"), preferences.all.keys)
    }

    @Test fun `旧形状を両方の向きで読み込み縦向きの調整後も横向きでは旧形状を使う`() {
        val legacy = TouchKeyboardSettings(
            tenKeyHeightDp = 280, tenKeyWidthPercent = 80, tenKeyPositionPercent = 30,
            tenKeyBottomPaddingDp = 40, qwertySplit = true, qwertyGapPercent = 36,
            qwertyHeightDp = 220, qwertyWidthPercent = 72, qwertyPositionPercent = 60,
            qwertyBottomPaddingDp = 56,
        )
        settingsPreferences.edit()
            .putInt("ten_key_height_dp", legacy.tenKeyHeightDp)
            .putInt("ten_key_width_percent", legacy.tenKeyWidthPercent)
            .putInt("ten_key_position_percent", legacy.tenKeyPositionPercent)
            .putInt("ten_key_bottom_padding_dp", legacy.tenKeyBottomPaddingDp)
            .putBoolean("qwerty_split", legacy.qwertySplit)
            .putInt("qwerty_gap_percent", legacy.qwertyGapPercent)
            .putInt("qwerty_height_dp", legacy.qwertyHeightDp)
            .putInt("qwerty_width_percent", legacy.qwertyWidthPercent)
            .putInt("qwerty_position_percent", legacy.qwertyPositionPercent)
            .putInt("qwerty_bottom_padding_dp", legacy.qwertyBottomPaddingDp)
            .commit()
        assertEquals(legacy, TouchKeyboardSettingsStore.read(context, ORIENTATION_PORTRAIT))
        assertEquals(legacy, TouchKeyboardSettingsStore.read(context, ORIENTATION_LANDSCAPE))

        assertTrue(TouchKeyboardSettingsStore.saveGeometry(context, 300, 70, 75, orientation = ORIENTATION_UNDEFINED))
        assertTrue(TouchKeyboardSettingsStore.saveQwertyGeometry(context, heightDp = 200, orientation = ORIENTATION_PORTRAIT))
        assertTrue(TouchKeyboardSettingsStore.saveQwertySplit(context, false, ORIENTATION_PORTRAIT))
        val portrait = legacy.copy(
            tenKeyHeightDp = 300, tenKeyWidthPercent = 70, tenKeyPositionPercent = 75,
            qwertySplit = false, qwertyHeightDp = 200,
        )
        assertEquals(portrait, TouchKeyboardSettingsStore.read(context, ORIENTATION_PORTRAIT))
        assertEquals(portrait, TouchKeyboardSettingsStore.read(context, ORIENTATION_UNDEFINED))
        assertEquals(legacy, TouchKeyboardSettingsStore.read(context, ORIENTATION_LANDSCAPE))
    }

    @Test fun `向き別の形状を保存し共通設定は両方の向きに反映する`() {
        val portrait = TouchKeyboardSettings(
            tenKeyHeightDp = 300, tenKeyWidthPercent = 90, tenKeyPositionPercent = 20, tenKeyBottomPaddingDp = 30,
            qwertyHeightDp = 250, qwertyWidthPercent = 80, qwertyPositionPercent = 40, qwertyBottomPaddingDp = 50,
        )
        val landscape = TouchKeyboardSettings(
            layout = ScreenKeyboardLayout.TEN_KEY, operation = TenKeyOperation.FLICK,
            holdMillis = 450, vibrateOnBoundary = false, tenKeyHeightDp = 200, tenKeyWidthPercent = 65,
            tenKeyPositionPercent = 60, tenKeyBottomPaddingDp = 15, qwertySplit = true, qwertyGapPercent = 45,
            qwertyHeightDp = 160, qwertyWidthPercent = 70, qwertyPositionPercent = 70, qwertyBottomPaddingDp = 25,
        )
        val landscapeContext = context.createConfigurationContext(Configuration(context.resources.configuration).apply {
            orientation = ORIENTATION_LANDSCAPE
        })
        assertTrue(TouchKeyboardSettingsStore.save(context, portrait, ORIENTATION_PORTRAIT))
        assertTrue(TouchKeyboardSettingsStore.save(landscapeContext, landscape))
        assertEquals(landscape, TouchKeyboardSettingsStore.read(landscapeContext))
        assertEquals(portrait.copy(layout = landscape.layout, operation = landscape.operation,
            holdMillis = landscape.holdMillis, vibrateOnBoundary = landscape.vibrateOnBoundary),
            TouchKeyboardSettingsStore.read(context, ORIENTATION_PORTRAIT))

        val operation = TouchKeyboardSettings(operation = TenKeyOperation.HOLD, holdMillis = 260, vibrateOnBoundary = true)
        assertTrue(TouchKeyboardSettingsStore.saveOperation(landscapeContext, operation))
        assertEquals(landscape.copy(operation = operation.operation, holdMillis = operation.holdMillis,
            vibrateOnBoundary = operation.vibrateOnBoundary), TouchKeyboardSettingsStore.read(landscapeContext))
        assertEquals(portrait.copy(layout = landscape.layout, operation = operation.operation,
            holdMillis = operation.holdMillis, vibrateOnBoundary = operation.vibrateOnBoundary),
            TouchKeyboardSettingsStore.read(context, ORIENTATION_PORTRAIT))
    }

    @Test fun `分割と形状の保存はそれぞれ他の設定を保持する`() {
        val before = TouchKeyboardSettings(layout = ScreenKeyboardLayout.TEN_KEY,
            tenKeyHeightDp = 300, qwertyGapPercent = 36, qwertyHeightDp = 220)
        assertTrue(TouchKeyboardSettingsStore.save(context, before))
        assertTrue(TouchKeyboardSettingsStore.saveQwertySplit(context, true))
        assertEquals(before.copy(qwertySplit = true), TouchKeyboardSettingsStore.read(context))
        assertTrue(TouchKeyboardSettingsStore.saveQwertyGeometry(context, 48, 320))
        assertFalse(TouchKeyboardSettingsStore.saveQwertyGeometry(context, 49, 320))
        assertTrue(TouchKeyboardSettingsStore.saveQwertyGeometry(context, 48, 100))
        assertFalse(TouchKeyboardSettingsStore.saveQwertyGeometry(context, 48, 99))
        assertEquals(before.copy(qwertySplit = true, qwertyGapPercent = 48, qwertyHeightDp = 100),
            TouchKeyboardSettingsStore.read(context))
    }

    @Test fun `QWERTYの幅と余白は境界を保存し省略時は現在値を維持する`() {
        val before = TouchKeyboardSettings(tenKeyHeightDp = 300, qwertySplit = true)
        assertTrue(TouchKeyboardSettingsStore.save(context, before))
        assertTrue(TouchKeyboardSettingsStore.saveQwertyGeometry(context, 48, 320, 60, 100, 160))
        assertTrue(TouchKeyboardSettingsStore.saveQwertyGeometry(context, 28, 200))
        val expected = before.copy(qwertyWidthPercent = 60, qwertyPositionPercent = 100, qwertyBottomPaddingDp = 160)
        assertEquals(expected, TouchKeyboardSettingsStore.read(context))
        assertFalse(TouchKeyboardSettingsStore.saveQwertyGeometry(context, 28, 200, widthPercent = 59))
        assertFalse(TouchKeyboardSettingsStore.saveQwertyGeometry(context, 28, 200, positionPercent = 101))
        assertFalse(TouchKeyboardSettingsStore.saveQwertyGeometry(context, 28, 200, bottomPaddingDp = 161))
        assertEquals(expected, TouchKeyboardSettingsStore.read(context))
    }

    @Test fun `QWERTYの不正な幅と余白は既定値に戻す`() {
        preferences.edit().putString("qwerty_width_percent", "60")
            .putInt("qwerty_position_percent", -1).putInt("qwerty_bottom_padding_dp", 161).commit()
        assertEquals(TouchKeyboardSettings(), TouchKeyboardSettingsStore.readFrom(preferences))
    }

    @Test fun `形状だけの保存では他の設定を変更しない`() {
        val before = TouchKeyboardSettings(ScreenKeyboardLayout.TEN_KEY, TenKeyOperation.HOLD,
            250, false, 224, 80, 25, 48)
        assertTrue(TouchKeyboardSettingsStore.save(context, before))
        val modes = TenKeyReadingModes(alpha = true)
        assertTrue(TenKeyReadingModeStore.saveTo(settingsPreferences, modes))
        assertTrue(TouchKeyboardSettingsStore.saveGeometry(context, 300, 70, 75))
        assertEquals(before.copy(tenKeyHeightDp = 300, tenKeyWidthPercent = 70,
            tenKeyPositionPercent = 75), TouchKeyboardSettingsStore.read(context))
        assertTrue(TouchKeyboardSettingsStore.saveGeometry(context, 300, 70, 75, 160))
        assertEquals(160, TouchKeyboardSettingsStore.read(context).tenKeyBottomPaddingDp)
        assertTrue(TouchKeyboardSettingsStore.saveGeometry(context, 300, 10, 75))
        assertEquals(10, TouchKeyboardSettingsStore.read(context).tenKeyWidthPercent)
        assertFalse(TouchKeyboardSettingsStore.saveGeometry(context, 300, 9, 75))
        assertFalse(TouchKeyboardSettingsStore.saveGeometry(context, 300, 70, 75, 161))
        assertFalse(TouchKeyboardSettingsStore.saveGeometry(context, 401, 70, 75))
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
            .putBoolean("ten_key_vibrate_on_boundary", true)
            .putInt("ten_key_height_dp", 280)
            .putInt("ten_key_height_dp_portrait", 300)
            .putInt("ten_key_width_percent_landscape", 80)
            .commit()
        val before = preferences.all
        val attempted = TouchKeyboardSettings(ScreenKeyboardLayout.TEN_KEY, TenKeyOperation.HOLD, 250, false)
        assertFalse(TouchKeyboardSettingsStore.saveTo(preferences, attempted, ORIENTATION_LANDSCAPE) { editor ->
            editor.commit()
            false
        })
        assertEquals(before, preferences.all)
    }
}
