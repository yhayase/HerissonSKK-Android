package se.haya.skk

import android.content.Context
import androidx.preference.Preference
import androidx.preference.PreferenceGroup
import java.io.File
import java.util.UUID
import java.util.concurrent.Executor
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog
import se.haya.skk.settings.AppEmacsEditingSaveStatus
import se.haya.skk.settings.AppEmacsEditingSettings
import se.haya.skk.settings.AppEmacsEditingStore
import se.haya.skk.settings.CustomizationStore
import se.haya.skk.settings.EmacsEditingMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 35])
class AppEmacsSettingsActivityTest {
    private var customization: CustomizationStore? = null
    private var customizationPath: File? = null
    private var controller: ActivityController<AppEmacsSettingsActivity>? = null
    private var store: AppEmacsEditingStore? = null

    @Before fun prepareCustomization() {
        val path = File(RuntimeEnvironment.getApplication().cacheDir, "app-emacs-ui-${UUID.randomUUID()}.json")
        customizationPath = path
        customization = CustomizationStore(path, Executor { it.run() }, Executor { it.run() }).also { it.loadAsync() }
        AppEmacsSettingsActivity.customizationStoreFactoryForTest = { checkNotNull(customization) }
    }

    @After fun cleanup() {
        controller?.destroy()
        AppEmacsSettingsActivity.storeFactoryForTest = null
        AppEmacsSettingsActivity.launcherAppsProviderForTest = null
        AppEmacsSettingsActivity.customizationStoreFactoryForTest = null
        customization?.close()
        customizationPath?.delete()
    }

    @Test fun `アプリ一覧に実効値と上書き状態を概要で表示する`() {
        val activity = start(ManualExecutor(), twoApps())
        val editor = rows(activity).first { it.title.toString() == "エディター" }

        assertTrue(editor.summary.toString().contains("現在: 無効"))
        assertTrue(editor.summary.toString().contains("全体設定に従う"))
        assertTrue(editor.summary.toString().contains("com.example.editor"))
    }

    @Test fun `行の選択から上書きを直ちに保存する`() {
        val worker = ManualExecutor()
        val activity = start(worker, twoApps())

        activity.chooseOverride(AppChoice("com.example.editor", "エディター", true))
        val dialog = ShadowDialog.getLatestDialog() as androidx.appcompat.app.AlertDialog
        dialog.listView.performItemClick(dialog.listView.getChildAt(3), 3, 3)
        assertEquals(1, worker.size)
        worker.runAll()

        assertEquals(EmacsEditingMode.IME_AND_APP,
            checkNotNull(store).emacsEditingMode("com.example.editor", EmacsEditingMode.DISABLED))
        assertEquals(AppEmacsEditingSaveStatus.SAVED, checkNotNull(store).snapshot().status)
    }

    @Test fun `ダイアログでIME内のみを選ぶとモード全体を上書きする`() {
        val worker = ManualExecutor()
        val activity = start(worker, twoApps())

        activity.chooseOverride(AppChoice("com.example.editor", "エディター", true))
        val dialog = ShadowDialog.getLatestDialog() as androidx.appcompat.app.AlertDialog
        assertEquals(listOf("全体設定に従う", "無効", "IME 内のみ", "IME とアプリ"),
            (0 until dialog.listView.count).map { dialog.listView.adapter.getItem(it).toString() })
        dialog.listView.performItemClick(dialog.listView.getChildAt(2), 2, 2)
        worker.runAll()

        assertEquals(EmacsEditingMode.IME_ONLY,
            checkNotNull(store).emacsEditingMode("com.example.editor", EmacsEditingMode.IME_AND_APP))
        val editor = rows(activity).first { it.title.toString() == "エディター" }
        assertTrue(editor.summary.toString().contains("現在: IME 内のみ"))
    }

    @Test fun `アプリが多い場合は名前とパッケージ名で検索する`() {
        val choices = (0 until 10).map { AppChoice("com.example.app$it", "アプリ$it", true) }
        val activity = start(ManualExecutor(), choices)
        val fragment = fragment(activity)

        val search = fragment.searchViewForTest()
        assertEquals("アプリ名またはパッケージ名で検索", search.queryHint)
        search.setQuery("app7", false)

        assertEquals(listOf("com.example.app7"), activity.visibleApps().map(AppChoice::packageName))

        search.setQuery("アプリ3", false)
        assertEquals(listOf("com.example.app3"), activity.visibleApps().map(AppChoice::packageName))
    }

    @Test fun `回転後も絞り込んだアプリを同じパッケージへ保存できる`() {
        val worker = ManualExecutor()
        val choices = (0 until 10).map { AppChoice("com.example.app$it", "アプリ$it", true) }
        val activity = start(worker, choices)
        val search = fragment(activity).searchViewForTest()
        search.requestFocus()
        search.setQuery("app7", false)
        assertTrue(search.hasFocus())
        assertTrue(search === fragment(activity).searchViewForTest())

        controller!!.recreate()
        val restored = controller!!.get()
        assertEquals("app7", restored.query())
        assertEquals("app7", fragment(restored).searchViewForTest().query.toString())
        assertEquals(listOf("com.example.app7"), restored.visibleApps().map(AppChoice::packageName))

        restored.chooseOverride(restored.visibleApps().single())
        val dialog = ShadowDialog.getLatestDialog() as androidx.appcompat.app.AlertDialog
        dialog.listView.performItemClick(dialog.listView.getChildAt(1), 1, 1)
        worker.runAll()

        assertEquals(EmacsEditingMode.DISABLED,
            checkNotNull(store).emacsEditingMode("com.example.app7", EmacsEditingMode.IME_AND_APP))
    }

    @Test fun `未インストールの保存済みアプリも一覧から解除できる`() {
        val prefs = preferences().apply {
            edit().putStringSet("emacs_disabled_packages", setOf("com.example.removed")).commit()
        }
        store = AppEmacsEditingStore(prefs, ManualExecutor(), Executor { it.run() })
        val activity = launch(listOf(AppChoice("com.example.editor", "エディター", true)))

        val removed = rows(activity).first { it.title.toString().contains("現在は一覧にありません") }
        assertTrue(removed.summary.toString().contains("無効"))
    }

    @Test fun `不正な起動一覧のパッケージは表示対象にしない`() {
        val choices = appChoices(listOf(
            AppChoice("not a package", "不正", true),
            AppChoice("com.example.editor", "エディター", true),
        ), AppEmacsEditingSettings.defaults())

        assertEquals(listOf("com.example.editor"), choices.map(AppChoice::packageName))
    }

    private fun start(worker: ManualExecutor, choices: List<AppChoice>): AppEmacsSettingsActivity {
        store = AppEmacsEditingStore(migratedPreferences(), worker, Executor { it.run() })
        return launch(choices)
    }

    private fun launch(choices: List<AppChoice>): AppEmacsSettingsActivity {
        AppEmacsSettingsActivity.storeFactoryForTest = { checkNotNull(store) }
        AppEmacsSettingsActivity.launcherAppsProviderForTest = { choices }
        controller = Robolectric.buildActivity(AppEmacsSettingsActivity::class.java).setup()
        return controller!!.get()
    }

    private fun fragment(activity: AppEmacsSettingsActivity): AppEmacsSettingsFragment =
        activity.supportFragmentManager.findFragmentById(R.id.settings_content) as AppEmacsSettingsFragment

    private fun rows(activity: AppEmacsSettingsActivity): List<Preference> =
        fragment(activity).preferenceScreen.flatten()

    private fun PreferenceGroup.flatten(): List<Preference> = buildList {
        for (index in 0 until preferenceCount) {
            val item = getPreference(index)
            add(item)
            if (item is PreferenceGroup) addAll(item.flatten())
        }
    }

    private fun twoApps() = listOf(
        AppChoice("com.example.editor", "エディター", true),
        AppChoice("com.example.notes", "メモ", true),
    )

    private fun preferences() = RuntimeEnvironment.getApplication()
        .getSharedPreferences("app-emacs-activity-${UUID.randomUUID()}", Context.MODE_PRIVATE)

    private fun migratedPreferences() = preferences().apply {
        edit().putInt("emacs_mode_format_version", 1).commit()
    }

    private class ManualExecutor : Executor {
        private val tasks = ArrayDeque<Runnable>()
        val size get() = tasks.size
        override fun execute(command: Runnable) { tasks += command }
        fun runAll() { while (tasks.isNotEmpty()) tasks.removeFirst().run() }
    }
}
