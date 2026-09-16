package jp.hayase.skk

import android.text.Selection
import android.text.InputType
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import java.io.File
import java.util.concurrent.Executor
import jp.hayase.skk.core.BasicSkkAction
import jp.hayase.skk.input.EditorSession
import jp.hayase.skk.settings.CustomizationStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 35])
class CustomizationServiceTest {
    @Test fun `保存済みカスタム規則と句読点は入力欄の開始時だけ反映する`() {
        val context = RuntimeEnvironment.getApplication()
        val preferences = context.getSharedPreferences("settings", 0)
        val previousStatus = preferences.getBoolean("show_status", true)
        val hadStatus = preferences.contains("show_status")
        preferences.edit().putBoolean("show_status", false).commit()
        val direct = Executor { it.run() }
        val path = File(context.cacheDir, "custom-service-${System.nanoTime()}.json")
        val customization = CustomizationStore(path, direct, direct).also { it.loadAsync() }
        val controller = Robolectric.buildService(SkkInputMethodService::class.java).create()
        val service = controller.get()
        ReflectionHelpers.setField(service, "customization", customization)
        try {
            val first = Connection()
            attach(service, first)
            service.onStartInput(editorInfo(InputType.TYPE_CLASS_TEXT), false)
            customization.save(jp.hayase.skk.settings.CustomizationSettings(0,
                jp.hayase.skk.settings.CustomizationProfile.CUSTOM,
                listOf(jp.hayase.skk.core.romaji.RomajiRule("ka", "か゚")),
                jp.hayase.skk.core.PunctuationConfig(period = "．")), 0) {
                assertTrue(it is jp.hayase.skk.settings.CustomizationWriteResult.Applied)
            }
            session(service).handle(BasicSkkAction.Text("ka."))
            assertEquals("か。", first.editable.toString())
            val second = Connection()
            attach(service, second)
            service.onStartInput(editorInfo(InputType.TYPE_CLASS_TEXT), false)
            session(service).handle(BasicSkkAction.Text("ka."))
            assertEquals("か゚．", second.editable.toString())
            customization.reset(1) {
                assertTrue(it is jp.hayase.skk.settings.CustomizationWriteResult.Applied)
            }
            session(service).handle(BasicSkkAction.Text("ka."))
            assertEquals("か゚．か゚．", second.editable.toString())
            val third = Connection()
            attach(service, third)
            service.onStartInput(editorInfo(InputType.TYPE_CLASS_TEXT), false)
            session(service).handle(BasicSkkAction.Text("ka."))
            assertEquals("か。", third.editable.toString())
        } finally {
            controller.destroy()
            preferences.edit().also {
                if (hadStatus) it.putBoolean("show_status", previousStatus) else it.remove("show_status")
            }.commit()
            customization.close()
            listOf(path, File(path.path + ".bak"), File(path.path + ".new")).forEach { it.delete() }
        }
    }

    @Test fun `読み込み待ちの古い入力欄へセッションを作らない`() {
        val context = RuntimeEnvironment.getApplication()
        val preferences = context.getSharedPreferences("settings", 0)
        val previousStatus = preferences.getBoolean("show_status", true)
        val hadStatus = preferences.contains("show_status")
        preferences.edit().putBoolean("show_status", false).commit()
        val queue = java.util.ArrayDeque<Runnable>()
        val path = File(context.cacheDir, "custom-loading-${System.nanoTime()}.json")
        val customization = CustomizationStore(path, Executor { queue.add(it) }, Executor { it.run() })
        val controller = Robolectric.buildService(SkkInputMethodService::class.java).create()
        val service = controller.get()
        ReflectionHelpers.setField(service, "customization", customization)
        try {
            val first = Connection()
            attach(service, first)
            service.onStartInput(editorInfo(InputType.TYPE_CLASS_TEXT), false)
            assertNull(ReflectionHelpers.getField<EditorSession?>(service, "session"))
            val second = Connection()
            attach(service, second)
            service.onStartInput(editorInfo(InputType.TYPE_CLASS_TEXT), false)
            queue.removeFirst().run()
            assertNull(ReflectionHelpers.getField<EditorSession?>(service, "session"))
            queue.removeFirst().run()
            session(service).handle(BasicSkkAction.Text("ka"))
            assertEquals("", first.editable.toString())
            assertEquals("か", second.editable.toString())
        } finally {
            controller.destroy()
            preferences.edit().also {
                if (hadStatus) it.putBoolean("show_status", previousStatus) else it.remove("show_status")
            }.commit()
            customization.close()
            path.delete()
        }
    }

    @Test fun `サービス終了後の設定読込完了は接続を再作成しない`() {
        val queue = java.util.ArrayDeque<Runnable>()
        val path = File(RuntimeEnvironment.getApplication().cacheDir, "custom-destroy-${System.nanoTime()}.json")
        val store = CustomizationStore(path, Executor { queue.add(it) }, Executor { it.run() })
        val controller = Robolectric.buildService(SkkInputMethodService::class.java).create()
        val service = controller.get()
        ReflectionHelpers.setField(service, "customization", store)
        attach(service, Connection())
        service.onStartInput(editorInfo(InputType.TYPE_CLASS_TEXT), false)
        controller.destroy()
        try {
            queue.removeFirst().run()
            assertNull(ReflectionHelpers.getField<EditorSession?>(service, "session"))
        } finally {
            store.close()
            path.delete()
        }
    }

    private fun editorInfo(type: Int) = EditorInfo().apply {
        inputType = type
        initialSelStart = 0
        initialSelEnd = 0
    }

    private fun attach(service: SkkInputMethodService, connection: Connection) {
        ReflectionHelpers.setField(service, "mInputConnection", connection)
        ReflectionHelpers.setField(service, "mStartedInputConnection", connection)
    }

    private fun session(service: SkkInputMethodService): EditorSession {
        val value = ReflectionHelpers.getField<EditorSession?>(service, "session")
        assertNotNull(value)
        return checkNotNull(value)
    }

    private fun updateStatus(service: SkkInputMethodService) {
        ReflectionHelpers.callInstanceMethod<Unit>(service, "updateStatus")
    }

    private class Connection : BaseInputConnection(
        View(RuntimeEnvironment.getApplication()),
        true,
    ) {
        init {
            Selection.setSelection(editable, 0)
        }
    }
}
