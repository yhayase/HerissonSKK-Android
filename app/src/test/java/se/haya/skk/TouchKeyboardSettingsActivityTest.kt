package se.haya.skk

import android.app.AlertDialog
import android.content.Intent
import android.view.View
import android.os.Bundle
import android.os.Looper
import androidx.preference.Preference
import androidx.preference.SeekBarPreference
import androidx.preference.SwitchPreferenceCompat
import androidx.preference.ListPreference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog
import se.haya.skk.settings.ScreenKeyboardLayout
import se.haya.skk.settings.TenKeyOperation
import se.haya.skk.settings.TenKeyReadingModeStore
import se.haya.skk.settings.TenKeyReadingModes
import se.haya.skk.settings.TouchKeyboardSettings
import se.haya.skk.settings.TouchKeyboardSettingsStore

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 35])
class TouchKeyboardSettingsActivityTest {
    private fun start(page: String? = null, initial: TouchKeyboardSettings? = null): TouchKeyboardSettingsActivity {
        val app = RuntimeEnvironment.getApplication()
        app.getSharedPreferences("settings", 0).edit().clear().commit()
        if (initial != null) assertTrue(TouchKeyboardSettingsStore.save(app, initial))
        val intent = Intent(app, TouchKeyboardSettingsActivity::class.java)
        if (page != null) intent.putExtra(TouchKeyboardSettingsActivity.EXTRA_PAGE, page)
        return Robolectric.buildActivity(TouchKeyboardSettingsActivity::class.java, intent).setup().get()
    }

    private fun fragment(activity: TouchKeyboardSettingsActivity) =
        activity.supportFragmentManager.findFragmentById(R.id.settings_content) as TouchKeyboardSettingsFragment

    @Test fun `標準の画面は三つの編集先を示し配列や保存操作を持たない`() {
        val activity = start()
        val page = activity.supportFragmentManager.findFragmentById(R.id.settings_content) as TouchKeyboardSettingsHubFragment
        assertEquals(3, page.preferenceScreen.preferenceCount)
        assertEquals("10キーの操作", page.preferenceScreen.getPreference(0).title)
        assertEquals("10キーの大きさと位置", page.preferenceScreen.getPreference(1).title)
        assertEquals("QWERTYの大きさと位置", page.preferenceScreen.getPreference(2).title)
        assertNull(page.findPreference<Preference>("layout"))
        assertNull(page.findPreference<Preference>("settings_save"))
        assertNull(page.findPreference<Preference>("settings_defaults"))
        assertEquals(View.GONE, activity.findViewById<View>(R.id.settings_actions).visibility)
    }

    @Test fun `QWERTY選択中でも操作と形状の各ページを開いて編集でき配列は維持する`() {
        val activity = start()
        assertTrue(TouchKeyboardSettingsStore.saveLayout(activity, ScreenKeyboardLayout.QWERTY))
        val operation = start(TouchKeyboardSettingsActivity.PAGE_OPERATION)
        val opPage = fragment(operation)
        assertNotNull(opPage.findPreference<ListPreference>("operation"))
        assertNotNull(opPage.findPreference<SeekBarPreference>("hold"))
        assertNotNull(opPage.findPreference<SwitchPreferenceCompat>("vibrate"))
        opPage.findPreference<ListPreference>("operation")!!.callChangeListener("HOLD")
        operation.findViewById<android.widget.Button>(R.id.settings_save).performClick()
        assertEquals(ScreenKeyboardLayout.QWERTY, TouchKeyboardSettingsStore.read(operation).layout)
        assertEquals(TenKeyOperation.HOLD, TouchKeyboardSettingsStore.read(operation).operation)

        val geometry = start(TouchKeyboardSettingsActivity.PAGE_GEOMETRY)
        val geometryPage = fragment(geometry)
        for (key in listOf("height", "width", "position", "bottom"))
            assertNotNull(geometryPage.findPreference<SeekBarPreference>(key))
        geometryPage.findPreference<SeekBarPreference>("height")!!.callChangeListener(320)
        geometry.findViewById<android.widget.Button>(R.id.settings_save).performClick()
        assertEquals(ScreenKeyboardLayout.QWERTY, TouchKeyboardSettingsStore.read(geometry).layout)
        assertEquals(320, TouchKeyboardSettingsStore.read(geometry).tenKeyHeightDp)
    }

    @Test fun `QWERTY形状の下書き保存は分割と間隔および10キー設定を維持する`() {
        val initial = TouchKeyboardSettings(tenKeyHeightDp = 300, qwertySplit = true, qwertyGapPercent = 40)
        val activity = start(TouchKeyboardSettingsActivity.PAGE_QWERTY_GEOMETRY, initial)
        val page = fragment(activity)
        assertEquals(320, page.findPreference<SeekBarPreference>("height")!!.max)
        page.findPreference<SeekBarPreference>("height")!!.callChangeListener(240)
        page.findPreference<SeekBarPreference>("width")!!.callChangeListener(75)
        page.findPreference<SeekBarPreference>("position")!!.callChangeListener(100)
        page.findPreference<SeekBarPreference>("bottom")!!.callChangeListener(48)
        assertEquals(initial, TouchKeyboardSettingsStore.read(activity))
        assertTrue(TouchKeyboardSettingsStore.saveGeometry(activity, 320, 70, 10, 64))
        activity.findViewById<android.widget.Button>(R.id.settings_save).performClick()
        assertEquals(initial.copy(tenKeyHeightDp = 320, tenKeyWidthPercent = 70,
            tenKeyPositionPercent = 10, tenKeyBottomPaddingDp = 64, qwertyHeightDp = 240,
            qwertyWidthPercent = 75, qwertyPositionPercent = 100, qwertyBottomPaddingDp = 48),
            TouchKeyboardSettingsStore.read(activity))
    }

    @Test fun `QWERTY形状の標準化は間隔と分割および10キー形状を維持する`() {
        val initial = TouchKeyboardSettings(tenKeyHeightDp = 300, qwertySplit = true, qwertyGapPercent = 40,
            qwertyHeightDp = 240, qwertyWidthPercent = 75, qwertyPositionPercent = 100, qwertyBottomPaddingDp = 48)
        val activity = start(TouchKeyboardSettingsActivity.PAGE_QWERTY_GEOMETRY, initial)
        activity.findViewById<android.widget.Button>(R.id.settings_defaults).performClick()
        (ShadowDialog.getLatestDialog() as androidx.appcompat.app.AlertDialog)
            .getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        activity.findViewById<android.widget.Button>(R.id.settings_save).performClick()
        assertEquals(initial.copy(qwertyHeightDp = 200, qwertyWidthPercent = 100,
            qwertyPositionPercent = 50, qwertyBottomPaddingDp = 0), TouchKeyboardSettingsStore.read(activity))
    }

    @Test fun `操作ページ保存は後から変更された形状を保つ`() {
        val activity = start(TouchKeyboardSettingsActivity.PAGE_OPERATION)
        fragment(activity).findPreference<ListPreference>("operation")!!.callChangeListener("HOLD")
        assertTrue(TouchKeyboardSettingsStore.saveGeometry(activity, 300, 75, 80, 64))
        activity.findViewById<android.widget.Button>(R.id.settings_save).performClick()
        val saved = TouchKeyboardSettingsStore.read(activity)
        assertEquals(TenKeyOperation.HOLD, saved.operation)
        assertEquals(300, saved.tenKeyHeightDp)
        assertEquals(75, saved.tenKeyWidthPercent)
        assertEquals(80, saved.tenKeyPositionPercent)
        assertEquals(64, saved.tenKeyBottomPaddingDp)
    }

    @Test fun `形状ページ保存は形状と配列の同時変更を保ち操作を上書きしない`() {
        val activity = start(TouchKeyboardSettingsActivity.PAGE_GEOMETRY)
        assertTrue(TouchKeyboardSettingsStore.save(activity, TouchKeyboardSettings(
            layout = ScreenKeyboardLayout.TEN_KEY, operation = TenKeyOperation.HOLD)))
        assertTrue(TouchKeyboardSettingsStore.saveGeometry(activity, 300, 75, 80, 64))
        activity.findViewById<android.widget.Button>(R.id.settings_save).performClick()
        val saved = TouchKeyboardSettingsStore.read(activity)
        assertEquals(ScreenKeyboardLayout.TEN_KEY, saved.layout)
        assertEquals(TenKeyOperation.HOLD, saved.operation)
        assertEquals(300, saved.tenKeyHeightDp)
        assertEquals(75, saved.tenKeyWidthPercent)
        assertEquals(80, saved.tenKeyPositionPercent)
        assertEquals(64, saved.tenKeyBottomPaddingDp)
    }

    @Test fun `各ページの下書きは回転後に維持し保存して戻る`() {
        val controller = Robolectric.buildActivity(TouchKeyboardSettingsActivity::class.java,
            Intent(RuntimeEnvironment.getApplication(), TouchKeyboardSettingsActivity::class.java)
                .putExtra(TouchKeyboardSettingsActivity.EXTRA_PAGE, TouchKeyboardSettingsActivity.PAGE_GEOMETRY)).setup()
        val activity = controller.get()
        fragment(activity).findPreference<SeekBarPreference>("width")!!.callChangeListener(80)
        val state = Bundle()
        controller.saveInstanceState(state).pause().stop().destroy()
        val recreated = Robolectric.buildActivity(TouchKeyboardSettingsActivity::class.java,
            Intent(RuntimeEnvironment.getApplication(), TouchKeyboardSettingsActivity::class.java)
                .putExtra(TouchKeyboardSettingsActivity.EXTRA_PAGE, TouchKeyboardSettingsActivity.PAGE_GEOMETRY))
            .create(state).start().resume().visible().get()
        assertEquals(80, fragment(recreated).findPreference<SeekBarPreference>("width")!!.value)
        recreated.findViewById<android.widget.Button>(R.id.settings_save).performClick()
        assertTrue(recreated.isFinishing)
        assertEquals(80, TouchKeyboardSettingsStore.read(recreated).tenKeyWidthPercent)
    }

    @Test fun `QWERTY形状の下書きは回転後に維持し保存して戻る`() {
        val controller = Robolectric.buildActivity(TouchKeyboardSettingsActivity::class.java,
            Intent(RuntimeEnvironment.getApplication(), TouchKeyboardSettingsActivity::class.java)
                .putExtra(TouchKeyboardSettingsActivity.EXTRA_PAGE, TouchKeyboardSettingsActivity.PAGE_QWERTY_GEOMETRY)).setup()
        val activity = controller.get()
        fragment(activity).findPreference<SeekBarPreference>("width")!!.callChangeListener(80)
        val state = Bundle()
        controller.saveInstanceState(state).pause().stop().destroy()
        val recreated = Robolectric.buildActivity(TouchKeyboardSettingsActivity::class.java,
            Intent(RuntimeEnvironment.getApplication(), TouchKeyboardSettingsActivity::class.java)
                .putExtra(TouchKeyboardSettingsActivity.EXTRA_PAGE, TouchKeyboardSettingsActivity.PAGE_QWERTY_GEOMETRY))
            .create(state).start().resume().visible().get()
        assertEquals(80, fragment(recreated).findPreference<SeekBarPreference>("width")!!.value)
        recreated.findViewById<android.widget.Button>(R.id.settings_save).performClick()
        assertTrue(recreated.isFinishing)
        assertEquals(80, TouchKeyboardSettingsStore.read(recreated).qwertyWidthPercent)
    }

    @Test fun `未保存変更から戻ると破棄を確認し設定を変更しない`() {
        val activity = start(TouchKeyboardSettingsActivity.PAGE_OPERATION)
        fragment(activity).findPreference<ListPreference>("operation")!!.callChangeListener("HOLD")
        activity.requestPageClose()
        val dialog = ShadowDialog.getLatestDialog() as androidx.appcompat.app.AlertDialog
        assertNotNull(dialog)
        assertEquals("破棄", dialog.getButton(AlertDialog.BUTTON_NEGATIVE).text.toString())
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        assertTrue(activity.isFinishing)
        assertEquals(TouchKeyboardSettings(), TouchKeyboardSettingsStore.read(activity))
    }

    @Test fun `操作ページの標準化は配列と形状を維持する`() {
        val activity = start(TouchKeyboardSettingsActivity.PAGE_OPERATION, TouchKeyboardSettings(
            layout = ScreenKeyboardLayout.TEN_KEY, tenKeyHeightDp = 300, operation = TenKeyOperation.HOLD))
        activity.findViewById<android.widget.Button>(R.id.settings_defaults).performClick()
        (ShadowDialog.getLatestDialog() as androidx.appcompat.app.AlertDialog)
            .getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        activity.findViewById<android.widget.Button>(R.id.settings_save).performClick()
        val saved = TouchKeyboardSettingsStore.read(activity)
        assertEquals(ScreenKeyboardLayout.TEN_KEY, saved.layout)
        assertEquals(300, saved.tenKeyHeightDp)
        assertEquals(TouchKeyboardSettings().operation, saved.operation)
    }

    @Test fun `形状ページ標準化は配列と操作および読みモードを維持する`() {
        val activity = start(TouchKeyboardSettingsActivity.PAGE_GEOMETRY, TouchKeyboardSettings(
            layout = ScreenKeyboardLayout.TEN_KEY, tenKeyHeightDp = 300, operation = TenKeyOperation.HOLD))
        val modes = TenKeyReadingModes(kana = true, number = true)
        assertTrue(TenKeyReadingModeStore.save(activity, modes))
        activity.findViewById<android.widget.Button>(R.id.settings_defaults).performClick()
        (ShadowDialog.getLatestDialog() as androidx.appcompat.app.AlertDialog)
            .getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        activity.findViewById<android.widget.Button>(R.id.settings_save).performClick()
        val saved = TouchKeyboardSettingsStore.read(activity)
        assertEquals(ScreenKeyboardLayout.TEN_KEY, saved.layout)
        assertEquals(TenKeyOperation.HOLD, saved.operation)
        assertEquals(256, saved.tenKeyHeightDp)
        assertEquals(modes, TenKeyReadingModeStore.read(activity))
    }

    @Test fun `ホールド時間は10ms単位に丸め保存までは既存値を変えない`() {
        val activity = start(TouchKeyboardSettingsActivity.PAGE_OPERATION)
        val hold = fragment(activity).findPreference<SeekBarPreference>("hold")!!
        assertEquals(350, hold.value)
        assertFalse(hold.callChangeListener(354))
        assertEquals(350, hold.value)
        assertFalse(hold.callChangeListener(355))
        assertEquals(360, hold.value)
        assertEquals(350, TouchKeyboardSettingsStore.read(activity).holdMillis)
        activity.findViewById<android.widget.Button>(R.id.settings_save).performClick()
        assertEquals(360, TouchKeyboardSettingsStore.read(activity).holdMillis)
    }

    @Test fun `常時読み入力状態は設定画面の変更や保存でも維持する`() {
        val activity = start(TouchKeyboardSettingsActivity.PAGE_OPERATION)
        val modes = TenKeyReadingModes(kana = true, number = true)
        assertTrue(TenKeyReadingModeStore.save(activity, modes))
        fragment(activity).findPreference<ListPreference>("operation")!!.callChangeListener("HOLD")
        activity.findViewById<android.widget.Button>(R.id.settings_save).performClick()
        assertEquals(modes, TenKeyReadingModeStore.read(activity))
    }
}
