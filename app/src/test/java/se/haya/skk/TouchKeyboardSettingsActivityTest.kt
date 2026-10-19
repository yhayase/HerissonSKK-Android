package se.haya.skk

import android.app.AlertDialog
import android.os.Looper
import android.os.Bundle
import androidx.preference.ListPreference
import androidx.preference.SeekBarPreference
import androidx.preference.SwitchPreferenceCompat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.shadows.ShadowDialog
import se.haya.skk.settings.ScreenKeyboardLayout
import se.haya.skk.settings.TenKeyReadingModes
import se.haya.skk.settings.TenKeyReadingModeStore
import se.haya.skk.settings.TenKeyOperation
import se.haya.skk.settings.TouchKeyboardSettings
import se.haya.skk.settings.TouchKeyboardSettingsStore
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 35])
class TouchKeyboardSettingsActivityTest {
    private fun start(): TouchKeyboardSettingsActivity {
        RuntimeEnvironment.getApplication().getSharedPreferences("settings", 0).edit().clear().commit()
        return Robolectric.buildActivity(TouchKeyboardSettingsActivity::class.java).setup().get()
    }

    @Test fun `常時読み入力の全体設定は配列変更後も表示しない`() {
        val activity = start()
        val page = activity.supportFragmentManager.findFragmentById(R.id.settings_content) as TouchKeyboardSettingsFragment
        assertNull(page.findPreference<SwitchPreferenceCompat>("auto_start_reading"))
        page.findPreference<ListPreference>("layout")!!.callChangeListener("TEN_KEY")
        assertNull(page.findPreference<SwitchPreferenceCompat>("auto_start_reading"))
    }

    @Test fun `標準に戻して再生成した下書きを保存しても入力中の面別状態を維持する`() {
        RuntimeEnvironment.getApplication().getSharedPreferences("settings", 0).edit().clear().commit()
        val controller = Robolectric.buildActivity(TouchKeyboardSettingsActivity::class.java).setup()
        val activity = controller.get()
        assertTrue(TouchKeyboardSettingsStore.save(activity, TouchKeyboardSettings(layout = ScreenKeyboardLayout.TEN_KEY)))
        val modes = TenKeyReadingModes(kana = true, number = true)
        assertTrue(TenKeyReadingModeStore.save(activity, modes))
        activity.findViewById<android.widget.Button>(R.id.settings_defaults).performClick()
        val resetDialog = ShadowDialog.getLatestDialog() as androidx.appcompat.app.AlertDialog
        resetDialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        val state = Bundle()
        controller.saveInstanceState(state).pause().stop().destroy()
        val recreated = Robolectric.buildActivity(TouchKeyboardSettingsActivity::class.java)
            .create(state).start().resume().visible().get()
        val page = recreated.supportFragmentManager.findFragmentById(R.id.settings_content) as TouchKeyboardSettingsFragment
        assertNull(page.findPreference<SwitchPreferenceCompat>("auto_start_reading"))
        // 設定画面を開いた後のキーボード側の変更も保存対象に含めません。
        val current = modes.copy(alpha = true)
        assertTrue(TenKeyReadingModeStore.save(recreated, current))
        recreated.findViewById<android.widget.Button>(R.id.settings_save).performClick()
        assertEquals(current, TenKeyReadingModeStore.read(recreated))
        assertEquals(TouchKeyboardSettings(), TouchKeyboardSettingsStore.read(recreated))
    }

    @Test fun `配列と操作の下書きを保存すると次回向け設定として保持する`() {
        val activity = start()
        val page = activity.supportFragmentManager.findFragmentById(R.id.settings_content)
            as TouchKeyboardSettingsFragment
        val layout = page.findPreference<ListPreference>("layout")!!
        val operation = page.findPreference<ListPreference>("operation")!!
        assertEquals("QWERTY", layout.summary.toString())
        assertEquals("QWERTY", layout.value)
        assertTrue(layout.callChangeListener("TEN_KEY"))
        assertTrue(operation.callChangeListener("HOLD"))
        assertEquals("10キー", layout.summary.toString())
        assertEquals("ホールドで境界", operation.summary.toString())
        assertFalse(activity.isFinishing)

        activity.findViewById<android.widget.Button>(R.id.settings_save).performClick()

        assertTrue(activity.isFinishing)
        assertEquals(TouchKeyboardSettings(ScreenKeyboardLayout.TEN_KEY, TenKeyOperation.HOLD),
            TouchKeyboardSettingsStore.read(activity))
    }

    @Test fun `未保存変更から戻ると保存破棄キャンセルを確認する`() {
        val activity = start()
        val page = activity.supportFragmentManager.findFragmentById(R.id.settings_content)
            as TouchKeyboardSettingsFragment
        assertTrue(page.findPreference<ListPreference>("layout")!!.callChangeListener("TEN_KEY"))

        activity.requestPageClose()
        val dialog = ShadowDialog.getLatestDialog() as androidx.appcompat.app.AlertDialog
        assertNotNull(dialog)
        assertEquals("保存して閉じる", dialog.getButton(AlertDialog.BUTTON_POSITIVE).text.toString())
        assertEquals("破棄", dialog.getButton(AlertDialog.BUTTON_NEGATIVE).text.toString())
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
        Shadows.shadowOf(Looper.getMainLooper()).idle()

        assertTrue(activity.isFinishing)
        assertEquals(TouchKeyboardSettings(), TouchKeyboardSettingsStore.read(activity))
    }

    @Test fun `10キー形状の下書きは保存され画面再生成後も維持する`() {
        RuntimeEnvironment.getApplication().getSharedPreferences("settings", 0).edit().clear().commit()
        val controller = Robolectric.buildActivity(TouchKeyboardSettingsActivity::class.java).setup()
        val activity = controller.get()
        val page = activity.supportFragmentManager.findFragmentById(R.id.settings_content)
            as TouchKeyboardSettingsFragment
        assertTrue(page.findPreference<ListPreference>("layout")!!.callChangeListener("TEN_KEY"))
        val height = page.findPreference<SeekBarPreference>("height")!!
        assertTrue(height.isVisible)
        assertTrue(height.callChangeListener(320))
        assertTrue(page.findPreference<SeekBarPreference>("width")!!.callChangeListener(80))
        assertTrue(page.findPreference<SeekBarPreference>("position")!!.callChangeListener(20))
        assertTrue(page.findPreference<SeekBarPreference>("bottom")!!.callChangeListener(64))

        val savedState = Bundle()
        controller.saveInstanceState(savedState).pause().stop().destroy()
        val recreated = Robolectric.buildActivity(TouchKeyboardSettingsActivity::class.java)
            .create(savedState).start().resume().visible().get()
        val recreatedPage = recreated.supportFragmentManager.findFragmentById(R.id.settings_content)
            as TouchKeyboardSettingsFragment
        assertEquals(320, recreatedPage.findPreference<SeekBarPreference>("height")!!.value)
        assertEquals(80, recreatedPage.findPreference<SeekBarPreference>("width")!!.value)
        assertEquals(20, recreatedPage.findPreference<SeekBarPreference>("position")!!.value)
        assertEquals(64, recreatedPage.findPreference<SeekBarPreference>("bottom")!!.value)
        recreated.findViewById<android.widget.Button>(R.id.settings_save).performClick()
        assertEquals(TouchKeyboardSettings(layout = ScreenKeyboardLayout.TEN_KEY,
            tenKeyHeightDp = 320, tenKeyWidthPercent = 80, tenKeyPositionPercent = 20, tenKeyBottomPaddingDp = 64),
            TouchKeyboardSettingsStore.read(recreated))
    }

    @Test fun `画面を開いた後のIME形状調整は形状を編集せずに保存しても残る`() {
        val activity = start()
        val page = activity.supportFragmentManager.findFragmentById(R.id.settings_content)
            as TouchKeyboardSettingsFragment
        assertTrue(page.findPreference<ListPreference>("layout")!!.callChangeListener("TEN_KEY"))
        assertTrue(TouchKeyboardSettingsStore.saveGeometry(activity, 300, 75, 80, 80))
        activity.findViewById<android.widget.Button>(R.id.settings_save).performClick()
        val actual = TouchKeyboardSettingsStore.read(activity)
        assertEquals(300, actual.tenKeyHeightDp)
        assertEquals(75, actual.tenKeyWidthPercent)
        assertEquals(80, actual.tenKeyPositionPercent)
        assertEquals(80, actual.tenKeyBottomPaddingDp)
    }

    @Test fun `標準に戻す操作は画面を開いた後のIME形状調整も既定値へ戻す`() {
        val activity = start()
        assertTrue(TouchKeyboardSettingsStore.saveGeometry(activity, 300, 75, 80, 80))

        activity.findViewById<android.widget.Button>(R.id.settings_defaults).performClick()
        val resetDialog = ShadowDialog.getLatestDialog() as androidx.appcompat.app.AlertDialog
        assertEquals("標準に戻す", resetDialog.getButton(AlertDialog.BUTTON_POSITIVE).text.toString())
        resetDialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        val page = activity.supportFragmentManager.findFragmentById(R.id.settings_content)
            as TouchKeyboardSettingsFragment
        assertEquals(256, page.findPreference<SeekBarPreference>("height")!!.value)
        assertEquals(100, page.findPreference<SeekBarPreference>("width")!!.value)
        assertEquals(50, page.findPreference<SeekBarPreference>("position")!!.value)
        assertEquals(0, page.findPreference<SeekBarPreference>("bottom")!!.value)
        activity.findViewById<android.widget.Button>(R.id.settings_save).performClick()

        assertEquals(TouchKeyboardSettings(), TouchKeyboardSettingsStore.read(activity))
    }
}
