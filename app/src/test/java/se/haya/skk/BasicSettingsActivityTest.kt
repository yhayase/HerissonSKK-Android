package se.haya.skk

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.util.TypedValue
import android.view.LayoutInflater
import android.widget.Button
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.materialswitch.MaterialSwitch
import androidx.preference.Preference
import androidx.preference.SwitchPreferenceCompat
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import android.os.Looper
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog
import se.haya.skk.settings.BasicSetting
import se.haya.skk.settings.BasicSettingsStore
import java.util.concurrent.Executor

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 35])
class BasicSettingsActivityTest {
    private val tasks = ArrayDeque<Runnable>()
    private lateinit var store: BasicSettingsStore
    @Before fun prepare() {
        val preferences = RuntimeEnvironment.getApplication().getSharedPreferences("basic-ui-test", Context.MODE_PRIVATE)
        preferences.edit().clear().commit()
        store = BasicSettingsStore(preferences, Executor { tasks += it }, Executor { it.run() }, {})
        BasicSettingsActivity.storeFactoryForTest = { store }
    }
    @After fun cleanup() { BasicSettingsActivity.storeFactoryForTest = null }

    @Test fun `変更を保存するまでは公開値が変わらず保存成功で戻る`() {
        val controller = activity().setup()
        val page = controller.get()
        change(page, false)
        assertTrue(store.snapshot().getValue(BasicSetting.HIDE_TOUCH_WITH_HARDWARE).savedValue)
        page.findViewById<Button>(R.id.settings_save).performClick()
        assertFalse(page.isFinishing)
        tasks.removeFirst().run()
        assertTrue(page.isFinishing)
        assertFalse(store.snapshot().getValue(BasicSetting.HIDE_TOUCH_WITH_HARDWARE).savedValue)
        controller.pause().stop().destroy()
    }

    @Test fun `未保存で戻ると保存と破棄とキャンセルを選べる`() {
        val controller = activity().setup()
        val page = controller.get()
        change(page, false)
        page.requestPageClose()
        val dialog = ShadowDialog.getLatestDialog() as androidx.appcompat.app.AlertDialog
        assertEquals("保存して閉じる", dialog.getButton(AlertDialog.BUTTON_POSITIVE).text.toString())
        assertEquals("破棄", dialog.getButton(AlertDialog.BUTTON_NEGATIVE).text.toString())
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL).performClick()
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        assertFalse(page.isFinishing)
        page.requestPageClose()
        (ShadowDialog.getLatestDialog() as androidx.appcompat.app.AlertDialog)
            .getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        assertTrue(store.snapshot().getValue(BasicSetting.HIDE_TOUCH_WITH_HARDWARE).savedValue)
        assertTrue(page.isFinishing)
        controller.pause().stop().destroy()
    }

    @Test fun `回転しても未保存の下書きを保持する`() {
        val controller = activity().setup()
        change(controller.get(), false)
        controller.recreate()
        assertEquals(false, controller.get().draft[BasicSetting.HIDE_TOUCH_WITH_HARDWARE])
        assertTrue(store.snapshot().getValue(BasicSetting.HIDE_TOUCH_WITH_HARDWARE).savedValue)
        controller.pause().stop().destroy()
    }

    @Test fun `保存中の回転後も完了を受けて閉じる`() {
        val controller = activity().setup()
        change(controller.get(), false)
        controller.get().findViewById<Button>(R.id.settings_save).performClick()
        controller.recreate()
        tasks.removeFirst().run()
        assertTrue(controller.get().isFinishing)
        assertFalse(store.snapshot().getValue(BasicSetting.HIDE_TOUCH_WITH_HARDWARE).savedValue)
        controller.pause().stop().destroy()
    }

    @Test fun `物理キーボード中に隠す対象を画面の文字キーとして表示する`() {
        val controller = activity().setup()
        val fragment = controller.get().supportFragmentManager
            .findFragmentById(R.id.settings_content) as BasicSettingsFragment
        val row = fragment.findPreference<Preference>(BasicSetting.HIDE_TOUCH_WITH_HARDWARE.preferenceKey)!!

        assertEquals("物理キーボード接続中は画面の文字キーを隠す", row.title)
        assertEquals("候補とモードはポップアップで表示します", row.summary)
        controller.pause().stop().destroy()
    }

    @Test fun `設定画面はMaterial3のテーマとスイッチを使う`() {
        val controller = activity().setup()
        val page = controller.get()
        val fragment = page.supportFragmentManager
            .findFragmentById(R.id.settings_content) as BasicSettingsFragment
        val row = fragment.findPreference<SwitchPreferenceCompat>(
            BasicSetting.HIDE_TOUCH_WITH_HARDWARE.preferenceKey,
        )!!
        val material3 = TypedValue()

        assertTrue(page.theme.resolveAttribute(com.google.android.material.R.attr.isMaterial3Theme, material3, true))
        assertEquals(TypedValue.TYPE_INT_BOOLEAN, material3.type)
        assertTrue(material3.data != 0)
        val toolbar = page.findViewById<MaterialToolbar>(R.id.settings_top_bar)
        assertNotNull(toolbar.navigationIcon)
        assertEquals("戻る", toolbar.navigationContentDescription)
        assertEquals(R.layout.preference_widget_material_switch, row.widgetLayoutResource)

        val switch = LayoutInflater.from(page).inflate(row.widgetLayoutResource, null) as MaterialSwitch
        assertEquals(androidx.preference.R.id.switchWidget, switch.id)
        val colors = checkNotNull(switch.trackTintList)
        val checked = colors.getColorForState(
            intArrayOf(android.R.attr.state_enabled, android.R.attr.state_checked),
            colors.defaultColor,
        )
        val unchecked = colors.getColorForState(
            intArrayOf(android.R.attr.state_enabled, -android.R.attr.state_checked),
            colors.defaultColor,
        )
        assertNotEquals(unchecked, checked)
        controller.pause().stop().destroy()
    }

    private fun activity() = Robolectric.buildActivity(BasicSettingsActivity::class.java,
        Intent(RuntimeEnvironment.getApplication(), BasicSettingsActivity::class.java)
            .putExtra(BasicSettingsActivity.EXTRA_PAGE, "display"))
    private fun change(activity: BasicSettingsActivity, value: Boolean) {
        val fragment = activity.supportFragmentManager.findFragmentById(R.id.settings_content) as BasicSettingsFragment
        val row = fragment.findPreference<SwitchPreferenceCompat>(BasicSetting.HIDE_TOUCH_WITH_HARDWARE.preferenceKey)!!
        assertTrue(row.callChangeListener(value))
        row.isChecked = value
    }
}
