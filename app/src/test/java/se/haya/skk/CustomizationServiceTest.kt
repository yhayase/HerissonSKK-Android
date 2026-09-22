package se.haya.skk

import android.text.Selection
import android.text.InputType
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import java.io.File
import java.util.concurrent.Executor
import se.haya.skk.core.BasicSkkAction
import se.haya.skk.input.EditorSession
import se.haya.skk.settings.CustomizationStore
import se.haya.skk.settings.EmacsEditingMode
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
    @Test fun `アプリ別有効化の後で全体のキーが競合しても新しい入力を開始できる`() {
        val context = RuntimeEnvironment.getApplication()
        val direct = Executor { it.run() }
        val prefs = context.getSharedPreferences("app-emacs-conflict-service-test", 0)
        prefs.edit().clear().commit()
        val overrides = se.haya.skk.settings.AppEmacsEditingStore(prefs, direct, direct)
        overrides.requestOverride("example.conflict", EmacsEditingMode.IME_AND_APP)
        val path = File(context.cacheDir, "app-emacs-conflict-${System.nanoTime()}.json")
        val customization = CustomizationStore(path, direct, direct).also { it.loadAsync() }
        val bindings = se.haya.skk.core.keys.KeyBindings(se.haya.skk.core.keys.KeyBindings.defaults +
            (se.haya.skk.core.keys.SkkCommand.EDIT_HOME to se.haya.skk.core.keys.KeyBindings.defaults.getValue(se.haya.skk.core.keys.SkkCommand.CANCEL)))
        customization.save(se.haya.skk.settings.CustomizationSettings(0, keyBindings = bindings), 0) { }
        val controller = Robolectric.buildService(SkkInputMethodService::class.java).create()
        val service = controller.get()
        ReflectionHelpers.setField(service, "customization", customization)
        ReflectionHelpers.setField(service, "appEmacsSettings", overrides)
        try {
            val connection = Connection()
            attach(service, connection)
            service.onStartInput(editorInfo(InputType.TYPE_CLASS_TEXT).apply { packageName = "example.conflict" }, false)
            assertEquals(false, ReflectionHelpers.getField<se.haya.skk.settings.CustomizationSettings>(service, "sessionCustomization").emacsEnabled)
            assertTrue(ReflectionHelpers.getField<String>(service, "appEmacsNotice").contains("重複"))
            session(service).handle(BasicSkkAction.Text("ka"))
            assertEquals("か", connection.editable.toString())
        } finally {
            controller.destroy()
            customization.close()
            path.delete()
            prefs.edit().clear().commit()
        }
    }

    @Test fun `保存保留中に開始した入力欄は失敗後も保存済み編集設定を使う`() {
        val context = RuntimeEnvironment.getApplication()
        val direct = Executor { it.run() }
        val pending = java.util.ArrayDeque<Runnable>()
        var fail = true
        val prefs = context.getSharedPreferences("app-emacs-pending-service-test", 0)
        prefs.edit().clear().commit()
        val overrides = se.haya.skk.settings.AppEmacsEditingStore(prefs, Executor { pending.add(it) }, direct,
            { editor -> if (fail) { editor.apply(); false } else editor.commit() })
        val path = File(context.cacheDir, "app-emacs-pending-${System.nanoTime()}.json")
        val customization = CustomizationStore(path, direct, direct).also { it.loadAsync() }
        val controller = Robolectric.buildService(SkkInputMethodService::class.java).create()
        val service = controller.get()
        ReflectionHelpers.setField(service, "customization", customization)
        ReflectionHelpers.setField(service, "appEmacsSettings", overrides)
        fun start() {
            attach(service, Connection())
            service.onStartInput(editorInfo(InputType.TYPE_CLASS_TEXT).apply { packageName = "example.pending" }, false)
        }
        fun enabled() = ReflectionHelpers.getField<se.haya.skk.settings.CustomizationSettings>(service, "sessionCustomization").emacsEnabled
        try {
            overrides.requestOverride("example.pending", EmacsEditingMode.IME_AND_APP)
            start()
            assertEquals(false, enabled())
            pending.removeFirst().run()
            assertEquals(false, enabled())
            fail = false
            overrides.requestOverride("example.pending", EmacsEditingMode.IME_AND_APP)
            pending.removeFirst().run()
            assertEquals(false, enabled())
            start()
            assertEquals(true, enabled())
        } finally {
            controller.destroy()
            customization.close()
            path.delete()
            prefs.edit().clear().commit()
        }
    }

    @Test fun `アプリ別編集設定は次の入力欄で配送と編集へ一緒に反映する`() {
        val context = RuntimeEnvironment.getApplication()
        val direct = Executor { it.run() }
        val prefs = context.getSharedPreferences("app-emacs-service-test", 0)
        prefs.edit().clear().commit()
        val overrides = se.haya.skk.settings.AppEmacsEditingStore(prefs, direct, direct)
        val path = File(context.cacheDir, "app-emacs-service-${System.nanoTime()}.json")
        val customization = CustomizationStore(path, direct, direct).also { it.loadAsync() }
        val controller = Robolectric.buildService(SkkInputMethodService::class.java).create()
        val service = controller.get()
        ReflectionHelpers.setField(service, "customization", customization)
        ReflectionHelpers.setField(service, "appEmacsSettings", overrides)
        fun start(packageId: String) {
            attach(service, Connection())
            service.onStartInput(editorInfo(InputType.TYPE_CLASS_TEXT).apply { packageName = packageId }, false)
        }
        fun assertEnabled(expected: Boolean) {
            val config = ReflectionHelpers.getField<se.haya.skk.settings.CustomizationSettings>(service, "sessionCustomization")
            assertEquals(expected, config.emacsEnabled)
            session(service).handle(BasicSkkAction.Text("Nihon"))
            assertEquals(expected, session(service).handle(BasicSkkAction.Edit(se.haya.skk.core.editing.EditCommand.LEFT)))
        }
        try {
            overrides.requestOverride("example.enabled", EmacsEditingMode.IME_AND_APP)
            start("example.enabled")
            overrides.requestOverride("example.enabled", EmacsEditingMode.DISABLED)
            assertEnabled(true)
            start("example.enabled")
            assertEnabled(false)
            start("example.other")
            assertEnabled(false)
        } finally {
            controller.destroy()
            customization.close()
            path.delete()
            prefs.edit().clear().commit()
        }
    }

    @Test fun `アプリ別の三択は全体のIME内編集も含めて置き換える`() {
        val context = RuntimeEnvironment.getApplication()
        val direct = Executor { it.run() }
        val prefs = context.getSharedPreferences("app-emacs-mode-service-test", 0)
        prefs.edit().clear().commit()
        val overrides = se.haya.skk.settings.AppEmacsEditingStore(prefs, direct, direct)
        overrides.migrateLegacy(false)
        val path = File(context.cacheDir, "app-emacs-mode-${System.nanoTime()}.json")
        val customization = CustomizationStore(path, direct, direct).also { it.loadAsync() }
        val controller = Robolectric.buildService(SkkInputMethodService::class.java).create()
        val service = controller.get()
        ReflectionHelpers.setField(service, "customization", customization)
        ReflectionHelpers.setField(service, "appEmacsSettings", overrides)
        try {
            for (global in EmacsEditingMode.entries) {
                val current = customization.snapshot
                customization.save(current.withEmacsEditingMode(global), current.generation) { }
                for (override in listOf(null) + EmacsEditingMode.entries) {
                    overrides.requestOverride("example.modes", override)
                    attach(service, Connection())
                    service.onStartInput(editorInfo(InputType.TYPE_CLASS_TEXT).apply { packageName = "example.modes" }, false)
                    val config = ReflectionHelpers.getField<se.haya.skk.settings.CustomizationSettings>(service, "sessionCustomization")
                    val expected = override ?: global
                    assertEquals(expected, config.emacsEditingMode)
                    assertEquals(expected == EmacsEditingMode.IME_AND_APP, config.emacsEnabled)
                    session(service).handle(BasicSkkAction.Text("Ka"))
                    assertEquals(expected != EmacsEditingMode.DISABLED,
                        session(service).handle(BasicSkkAction.Edit(se.haya.skk.core.editing.EditCommand.LEFT)))
                    assertTrue(session(service).handle(BasicSkkAction.Kana))
                }
            }
        } finally {
            controller.destroy()
            customization.close()
            path.delete()
            prefs.edit().clear().commit()
        }
    }

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
            customization.save(se.haya.skk.settings.CustomizationSettings(0,
                se.haya.skk.settings.CustomizationProfile.CUSTOM,
                listOf(se.haya.skk.core.romaji.RomajiRule("ka", "か゚")),
                se.haya.skk.core.PunctuationConfig(period = "．")), 0) {
                assertTrue(it is se.haya.skk.settings.CustomizationWriteResult.Applied)
            }
            session(service).handle(BasicSkkAction.Text("ka."))
            assertEquals("か。", first.editable.toString())
            val second = Connection()
            attach(service, second)
            service.onStartInput(editorInfo(InputType.TYPE_CLASS_TEXT), false)
            session(service).handle(BasicSkkAction.Text("ka."))
            assertEquals("か゚．", second.editable.toString())
            customization.reset(1) {
                assertTrue(it is se.haya.skk.settings.CustomizationWriteResult.Applied)
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
