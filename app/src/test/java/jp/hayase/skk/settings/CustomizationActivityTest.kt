package jp.hayase.skk.settings

import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import android.os.Looper
import java.io.IOException
import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import jp.hayase.skk.CustomizationSettingsActivity
import jp.hayase.skk.core.keys.KeyGesture
import jp.hayase.skk.core.keys.SkkCommand
import jp.hayase.skk.core.romaji.RomajiRule
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 35])
class CustomizationActivityTest {
    private var store: CustomizationStore? = null
    private var controller: ActivityController<CustomizationSettingsActivity>? = null

    @After fun cleanup() {
        controller?.destroy()
        store?.close()
        CustomizationSettingsActivity.storeFactoryForTest = null
        CustomizationSettingsActivity.validationFactoryForTest = null
    }

    @Test fun `未適用の規則編集は別規則の選択で上書きしない`() {
        val serial = ManualExecutor()
        val activity = start(serial, MemoryFile())
        val settings = CustomizationSettings(
            0, CustomizationProfile.CUSTOM, listOf(RomajiRule("ka", "か"), RomajiRule("ki", "き")),
        )
        set(activity, "loaded", settings)
        set(activity, "rules", settings.customRules.toMutableList())
        value<Spinner>(activity, "profile").setSelection(1)
        call(activity, "refreshRules", 0)
        val input = value<EditText>(activity, "input")
        input.setText("ku")

        value<Spinner>(activity, "ruleList").setSelection(1)
        Shadows.shadowOf(Looper.getMainLooper()).idle()

        assertEquals(0, value<Spinner>(activity, "ruleList").selectedItemPosition)
        assertEquals("ku", input.text.toString())
        assertTrue(value<TextView>(activity, "status").text.contains("追加"))
    }

    @Test fun `回転中に待機した保存は再作成後に耐久世代へ再照合して成功表示する`() {
        val serial = ManualExecutor()
        val activity = start(serial, MemoryFile())
        value<Spinner>(activity, "period").setSelection(1)
        call(activity, "saveDraft")

        controller = checkNotNull(controller).recreate()
        val recreated = controller!!.get()
        serial.runAll()

        assertEquals(1L, checkNotNull(store).snapshot.generation)
        assertEquals(checkNotNull(store).snapshot, value<CustomizationSettings>(recreated, "loaded"))
        assertTrue(value<TextView>(recreated, "status").text.contains("保存しました"))
    }

    @Test fun `回転中に待機した標準化は再作成後に耐久世代へ再照合して成功表示する`() {
        val serial = ManualExecutor()
        val activity = start(serial, MemoryFile())
        call(activity, "resetToDefaults")

        controller = checkNotNull(controller).recreate()
        val recreated = controller!!.get()
        serial.runAll()

        assertEquals(1L, checkNotNull(store).snapshot.generation)
        assertEquals(CustomizationSettings.defaults(1), value<CustomizationSettings>(recreated, "loaded"))
        assertTrue(value<TextView>(recreated, "status").text.contains("標準に戻しました"))
    }

    @Test fun `回転中の保存失敗は下書きを残して成功表示しない`() {
        val serial = ManualExecutor()
        val file = MemoryFile().apply { failWrite = true }
        val activity = start(serial, file)
        value<Spinner>(activity, "period").setSelection(1)
        call(activity, "saveDraft")

        controller = checkNotNull(controller).recreate()
        val recreated = controller!!.get()
        serial.runAll()

        assertEquals(0L, checkNotNull(store).snapshot.generation)
        assertEquals(1, value<Spinner>(recreated, "period").selectedItemPosition)
        assertTrue(value<TextView>(recreated, "status").text.contains("確認できません"))
    }

    @Test fun `AZIK と Emacs とキー割当を保存し次の設定スナップショットへ渡す`() {
        val serial = ManualExecutor()
        val activity = start(serial, MemoryFile())
        value<Spinner>(activity, "profile").setSelection(2)
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        value<android.widget.Switch>(activity, "emacsEnabled").isChecked = true
        bindings(activity).getValue(SkkCommand.KANA).setText("C-u")

        call(activity, "saveDraft")
        serial.runAll()

        val saved = checkNotNull(store).snapshot
        assertEquals(CustomizationProfile.AZIK, saved.profile)
        assertTrue(saved.customRules.isEmpty())
        assertTrue(saved.emacsEnabled)
        assertEquals(KeyGesture("[", ignoreShift = true), saved.keyBindings.bindings.getValue(SkkCommand.TOGGLE_KANA))
        assertEquals(KeyGesture("u", ctrl = true), saved.keyBindings.bindings.getValue(SkkCommand.KANA))
    }

    @Test fun `プロファイル変更は既定のかな種別切替だけを AZIK の既定へ移す`() {
        val serial = ManualExecutor()
        val activity = start(serial, MemoryFile())
        val toggle = bindings(activity).getValue(SkkCommand.TOGGLE_KANA)

        value<Spinner>(activity, "profile").setSelection(2)
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        assertEquals("U-[", toggle.text.toString())

        toggle.setText("U-z")
        value<Spinner>(activity, "profile").setSelection(0)
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        assertEquals("U-z", toggle.text.toString())
    }

    @Test fun `回転は未保存の AZIK Emacs とキー入力を保持する`() {
        val serial = ManualExecutor()
        val activity = start(serial, MemoryFile())
        value<Spinner>(activity, "profile").setSelection(2)
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        value<android.widget.Switch>(activity, "emacsEnabled").isChecked = true
        bindings(activity).getValue(SkkCommand.CANCEL).setText("C-x")

        controller = checkNotNull(controller).recreate()
        val recreated = controller!!.get()

        assertEquals(2, value<Spinner>(recreated, "profile").selectedItemPosition)
        assertTrue(value<android.widget.Switch>(recreated, "emacsEnabled").isChecked)
        assertEquals("C-x", bindings(recreated).getValue(SkkCommand.CANCEL).text.toString())
    }

    private fun start(serial: ManualExecutor, file: MemoryFile): CustomizationSettingsActivity {
        store = CustomizationStore(file, serial, Executor { it.run() })
        CustomizationSettingsActivity.storeFactoryForTest = { checkNotNull(store) }
        CustomizationSettingsActivity.validationFactoryForTest = { DirectExecutorService() }
        controller = Robolectric.buildActivity(CustomizationSettingsActivity::class.java).setup()
        serial.runAll()
        return controller!!.get()
    }

    private fun call(target: Any, name: String, vararg parameters: Any?) {
        val types = parameters.map { value ->
            if (value is Int) Int::class.javaPrimitiveType!! else value!!::class.java
        }.toTypedArray()
        target.javaClass.getDeclaredMethod(name, *types).apply { isAccessible = true }.invoke(target, *parameters)
    }

    private fun set(target: Any, name: String, value: Any?) {
        target.javaClass.getDeclaredField(name).apply { isAccessible = true }.set(target, value)
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> value(target: Any, name: String): T =
        target.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(target) as T

    @Suppress("UNCHECKED_CAST")
    private fun bindings(target: Any): Map<SkkCommand, EditText> =
        target.javaClass.getDeclaredField("bindingFields").apply { isAccessible = true }.get(target) as Map<SkkCommand, EditText>

    private class ManualExecutor : Executor {
        private val tasks = ArrayDeque<Runnable>()
        override fun execute(command: Runnable) { tasks += command }
        fun runAll() { while (tasks.isNotEmpty()) tasks.removeFirst().run() }
    }

    private class DirectExecutorService : AbstractExecutorService() {
        private var shutdown = false
        override fun execute(command: Runnable) { if (!shutdown) command.run() }
        override fun shutdown() { shutdown = true }
        override fun shutdownNow(): MutableList<Runnable> { shutdown = true; return mutableListOf() }
        override fun isShutdown(): Boolean = shutdown
        override fun isTerminated(): Boolean = shutdown
        override fun awaitTermination(timeout: Long, unit: TimeUnit): Boolean = shutdown
    }

    private class MemoryFile : CustomizationFileAccess {
        var bytes: ByteArray? = null
        var failWrite = false
        override fun exists(): Boolean = bytes != null
        override fun read(maxBytes: Int): ByteArray = checkNotNull(bytes).copyOf()
        override fun write(bytes: ByteArray) {
            if (failWrite) throw IOException("write failed")
            this.bytes = bytes.copyOf()
        }
    }
}
