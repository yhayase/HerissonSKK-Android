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
class TenKeyReadingModesStoreTest {
    private val preferences get() = RuntimeEnvironment.getApplication()
        .getSharedPreferences("ten-key-reading-test", 0)

    @Before fun clearPreferences() { preferences.edit().clear().commit() }

    @Test fun `未設定では全入力面を無効とし読込時に書き込まない`() {
        assertEquals(TenKeyReadingModes(), TenKeyReadingModeStore.readFrom(preferences))
        assertTrue(preferences.all.isEmpty())
    }

    @Test fun `旧設定が有効なら未設定の全入力面で引き継ぎ読込時に書き込まない`() {
        preferences.edit().putBoolean("ten_key_auto_start_reading", true).commit()
        val before = preferences.all
        assertEquals(TenKeyReadingModes(true, true, true), TenKeyReadingModeStore.readFrom(preferences))
        assertEquals(before, preferences.all)
    }

    @Test fun `旧設定の無効と型違いは全入力面を無効にする`() {
        preferences.edit().putBoolean("ten_key_auto_start_reading", false).commit()
        assertEquals(TenKeyReadingModes(), TenKeyReadingModeStore.readFrom(preferences))
        preferences.edit().putString("ten_key_auto_start_reading", "true").commit()
        assertEquals(TenKeyReadingModes(), TenKeyReadingModeStore.readFrom(preferences))
    }

    @Test fun `面別キーは旧設定より優先し型違いならその面だけ無効にする`() {
        preferences.edit().putBoolean("ten_key_auto_start_reading", true)
            .putBoolean("ten_key_always_reading_kana", false)
            .putString("ten_key_always_reading_alpha", "true").commit()
        assertEquals(TenKeyReadingModes(number = true), TenKeyReadingModeStore.readFrom(preferences))
        preferences.edit().putBoolean("ten_key_always_reading_alpha", true)
            .putInt("ten_key_always_reading_number", 1).commit()
        assertEquals(TenKeyReadingModes(alpha = true), TenKeyReadingModeStore.readFrom(preferences))
    }

    @Test fun `各入力面を独立して一括保存し別の取得結果からも読み込める`() {
        val context = RuntimeEnvironment.getApplication()
        val persisted = context.getSharedPreferences("settings", 0)
        persisted.edit().clear().commit()
        val combinations = listOf(TenKeyReadingModes(kana = true), TenKeyReadingModes(alpha = true),
            TenKeyReadingModes(number = true), TenKeyReadingModes(true, true, true), TenKeyReadingModes())
        combinations.forEach { modes ->
            assertTrue(TenKeyReadingModeStore.save(context, modes))
            assertEquals(modes, TenKeyReadingModeStore.read(context))
            assertEquals(modes, TenKeyReadingModeStore.readFrom(context.getSharedPreferences("settings", 0)))
        }
        assertEquals(setOf("ten_key_always_reading_kana", "ten_key_always_reading_alpha",
            "ten_key_always_reading_number"), persisted.all.keys)
    }

    @Test fun `面別状態を保存しても旧設定と他の設定を変更しない`() {
        preferences.edit().putBoolean("ten_key_auto_start_reading", true)
            .putString("touch_keyboard_layout", "TEN_KEY").putInt("ten_key_height_dp", 300)
            .putString("unrelated", "保持").commit()
        val before = preferences.all
        assertTrue(TenKeyReadingModeStore.saveTo(preferences, TenKeyReadingModes(alpha = true)))
        before.forEach { (key, value) -> assertEquals(value, preferences.all[key]) }
        assertEquals(TenKeyReadingModes(alpha = true), TenKeyReadingModeStore.readFrom(preferences))
    }

    @Test fun `保存失敗で追加したキーを取り消し旧設定からの引継ぎを維持する`() {
        preferences.edit().putBoolean("ten_key_auto_start_reading", true)
            .putString("unrelated", "保持").commit()
        val before = preferences.all
        assertFalse(TenKeyReadingModeStore.saveTo(preferences, TenKeyReadingModes()) { editor ->
            editor.commit()
            false
        })
        assertEquals(before, preferences.all)
        assertEquals(TenKeyReadingModes(true, true, true), TenKeyReadingModeStore.readFrom(preferences))
    }

    @Test fun `保存例外でも既存値と不正な型を含め全入力面を元へ戻す`() {
        preferences.edit().putBoolean("ten_key_always_reading_kana", true)
            .putLong("ten_key_always_reading_alpha", 1L)
            .putStringSet("ten_key_always_reading_number", setOf("保持")).commit()
        val before = preferences.all
        assertFalse(TenKeyReadingModeStore.saveTo(preferences, TenKeyReadingModes(alpha = true)) { editor ->
            editor.commit()
            error("保存失敗")
        })
        assertEquals(before, preferences.all)
        assertEquals(TenKeyReadingModes(kana = true), TenKeyReadingModeStore.readFrom(preferences))
    }
}
