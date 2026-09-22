package se.haya.skk.settings

import android.content.Context
import java.util.UUID
import java.util.concurrent.Executor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 35])
class AppEmacsEditingStoreTest {
    @Test fun `三つの全体設定に対して継承と三つの上書きがモード全体を置き換える`() {
        val packageName = "com.example.editor"
        EmacsEditingMode.entries.forEach { global ->
            assertEquals(global, AppEmacsEditingSettings.defaults().emacsEditingMode(packageName, global))
            EmacsEditingMode.entries.forEach { override ->
                assertEquals(override, AppEmacsEditingSettings.defaults().withOverride(packageName, override)
                    .emacsEditingMode(packageName, global))
            }
        }
        assertEquals(EmacsEditingMode.IME_AND_APP,
            AppEmacsEditingSettings.defaults().withOverride(packageName, EmacsEditingMode.DISABLED)
                .withOverride(packageName, null).emacsEditingMode(packageName, EmacsEditingMode.IME_AND_APP))
    }

    @Test fun `不正な入力元パッケージ名は全体設定を継承し保存指定は拒否する`() {
        val settings = AppEmacsEditingSettings.defaults()
            .withOverride("com.example.editor", EmacsEditingMode.IME_ONLY)
        assertEquals(EmacsEditingMode.DISABLED,
            settings.emacsEditingMode("not a package", EmacsEditingMode.DISABLED))
        assertEquals(EmacsEditingMode.IME_AND_APP,
            settings.emacsEditingMode(null, EmacsEditingMode.IME_AND_APP))
        assertThrows(IllegalArgumentException::class.java) {
            settings.withOverride("not a package", EmacsEditingMode.DISABLED)
        }
    }

    @Test fun `三つのアプリ別モードと継承削除を永続化する`() {
        val prefs = migratedPreferences()
        val worker = ManualExecutor()
        val store = AppEmacsEditingStore(prefs, worker, direct)

        store.requestOverride("com.example.all", EmacsEditingMode.IME_AND_APP)
        worker.runAll()
        store.requestOverride("com.example.ime", EmacsEditingMode.IME_ONLY)
        worker.runAll()
        store.requestOverride("com.example.off", EmacsEditingMode.DISABLED)
        worker.runAll()
        store.requestOverride("com.example.all", null)
        worker.runAll()

        val reopened = AppEmacsEditingStore(prefs, direct, direct)
        assertEquals(EmacsEditingMode.DISABLED,
            reopened.emacsEditingMode("com.example.all", EmacsEditingMode.DISABLED))
        assertEquals(EmacsEditingMode.IME_ONLY,
            reopened.emacsEditingMode("com.example.ime", EmacsEditingMode.IME_AND_APP))
        assertEquals(EmacsEditingMode.DISABLED,
            reopened.emacsEditingMode("com.example.off", EmacsEditingMode.IME_AND_APP))
        assertEquals(AppEmacsEditingSaveStatus.SAVED, store.snapshot().status)
    }

    @Test fun `旧無効指定は旧内部編集が無効なら無効へ移す`() {
        val prefs = legacyPreferences()
        val worker = ManualExecutor()
        val store = AppEmacsEditingStore(prefs, worker, direct)

        store.migrateLegacy(internalEnabled = false)

        assertEquals(EmacsEditingMode.IME_AND_APP,
            store.emacsEditingMode("com.example.enabled", EmacsEditingMode.DISABLED))
        assertEquals(EmacsEditingMode.DISABLED,
            store.emacsEditingMode("com.example.disabled", EmacsEditingMode.IME_AND_APP))
        worker.runAll()
        assertEquals(1, prefs.getInt("emacs_mode_format_version", 0))
    }

    @Test fun `旧無効指定は旧内部編集が有効ならIME内のみへ移す`() {
        val prefs = legacyPreferences()
        val worker = ManualExecutor()
        val store = AppEmacsEditingStore(prefs, worker, direct)

        store.migrateLegacy(internalEnabled = true)

        assertEquals(EmacsEditingMode.IME_AND_APP,
            store.emacsEditingMode("com.example.enabled", EmacsEditingMode.DISABLED))
        assertEquals(EmacsEditingMode.IME_ONLY,
            store.emacsEditingMode("com.example.disabled", EmacsEditingMode.DISABLED))
        worker.runAll()
        val reopened = AppEmacsEditingStore(prefs, direct, direct)
        assertEquals(EmacsEditingMode.IME_ONLY,
            reopened.emacsEditingMode("com.example.disabled", EmacsEditingMode.DISABLED))
    }

    @Test fun `移行保存失敗時も旧値を解釈でき次回に再試行する`() {
        val prefs = legacyPreferences()
        val worker = ManualExecutor()
        var fail = true
        val store = AppEmacsEditingStore(prefs, worker, direct) { editor ->
            editor.apply()
            !fail
        }

        store.migrateLegacy(internalEnabled = true)
        worker.runAll()
        assertEquals(0, prefs.getInt("emacs_mode_format_version", 0))
        assertEquals(setOf("com.example.disabled"),
            prefs.getStringSet("emacs_disabled_packages", emptySet()))
        assertEquals(EmacsEditingMode.IME_ONLY,
            store.emacsEditingMode("com.example.disabled", EmacsEditingMode.DISABLED))

        fail = false
        store.migrateLegacy(internalEnabled = false)
        worker.runAll()
        assertEquals(1, prefs.getInt("emacs_mode_format_version", 0))
        assertEquals(EmacsEditingMode.IME_ONLY,
            store.emacsEditingMode("com.example.disabled", EmacsEditingMode.DISABLED))
    }

    @Test fun `移行保存中の再呼び出しは最初の旧全体設定で一度だけ保存して両方へ完了通知する`() {
        val prefs = legacyPreferences()
        val worker = ManualExecutor()
        val results = mutableListOf<String>()
        val store = AppEmacsEditingStore(prefs, worker, direct)

        store.migrateLegacy(internalEnabled = true) { results += "first:$it" }
        store.migrateLegacy(internalEnabled = false) { results += "second:$it" }

        assertEquals(1, worker.size)
        assertEquals(EmacsEditingMode.IME_ONLY,
            store.emacsEditingMode("com.example.disabled", EmacsEditingMode.DISABLED))
        worker.runAll()
        assertEquals(listOf("first:true", "second:true"), results)
        assertEquals(EmacsEditingMode.IME_ONLY,
            AppEmacsEditingStore(prefs, direct, direct)
                .emacsEditingMode("com.example.disabled", EmacsEditingMode.DISABLED))
    }

    @Test fun `保存失敗時は直前の三択へ復元して再試行情報を残す`() {
        val prefs = migratedPreferences().apply {
            edit().putStringSet("emacs_mode_ime_and_app_packages", setOf("com.example.old")).commit()
        }
        val worker = ManualExecutor()
        val store = AppEmacsEditingStore(prefs, worker, direct) { editor -> editor.apply(); false }

        store.requestOverride("com.example.new", EmacsEditingMode.IME_ONLY)
        assertEquals(EmacsEditingMode.IME_AND_APP,
            store.emacsEditingMode("com.example.new", EmacsEditingMode.IME_AND_APP))
        assertEquals(EmacsEditingMode.IME_ONLY, store.snapshot().visibleSettings.overrideFor("com.example.new"))
        worker.runAll()

        val state = store.snapshot()
        assertEquals(AppEmacsEditingSaveStatus.FAILED, state.status)
        assertEquals("com.example.new", state.failedPackageName)
        assertEquals(EmacsEditingMode.IME_ONLY, state.failedOverride)
        assertEquals(EmacsEditingMode.IME_AND_APP,
            store.emacsEditingMode("com.example.old", EmacsEditingMode.DISABLED))
        assertEquals(null, state.savedSettings.overrideFor("com.example.new"))
    }

    @Test fun `永続化済みの不正値と重複値を無視する`() {
        val prefs = migratedPreferences().apply {
            edit().putStringSet("emacs_mode_ime_and_app_packages", setOf("invalid package", "com.example.ok"))
                .putStringSet("emacs_mode_ime_only_packages", setOf("com.example.ok", "com.example.ime"))
                .putStringSet("emacs_mode_disabled_packages", setOf("com.example.ime", "com.example.off")).commit()
        }
        val store = AppEmacsEditingStore(prefs, direct, direct)
        assertEquals(EmacsEditingMode.IME_AND_APP, store.snapshot().savedSettings.overrideFor("com.example.ok"))
        assertEquals(EmacsEditingMode.IME_ONLY, store.snapshot().savedSettings.overrideFor("com.example.ime"))
        assertEquals(EmacsEditingMode.DISABLED, store.snapshot().savedSettings.overrideFor("com.example.off"))
        assertEquals(null, store.snapshot().savedSettings.overrideFor("invalid package"))
    }

    private val direct = Executor { it.run() }

    private fun preferences() = RuntimeEnvironment.getApplication()
        .getSharedPreferences("app-emacs-editing-${UUID.randomUUID()}", Context.MODE_PRIVATE)

    private fun migratedPreferences() = preferences().apply {
        edit().putInt("emacs_mode_format_version", 1).commit()
    }

    private fun legacyPreferences() = preferences().apply {
        edit().putStringSet("emacs_enabled_packages", setOf("com.example.enabled"))
            .putStringSet("emacs_disabled_packages", setOf("com.example.disabled")).commit()
    }

    private class ManualExecutor : Executor {
        private val tasks = ArrayDeque<Runnable>()
        val size: Int get() = tasks.size
        override fun execute(command: Runnable) { tasks += command }
        fun runAll() { while (tasks.isNotEmpty()) tasks.removeFirst().run() }
    }
}
