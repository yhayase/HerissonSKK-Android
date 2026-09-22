package se.haya.skk.settings

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.os.Looper
import android.widget.EditText
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.Preference
import java.io.IOException
import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.UUID
import org.junit.After
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
import org.robolectric.shadows.ShadowDialog
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import se.haya.skk.CustomizationPageFragment
import se.haya.skk.CustomizationDraft
import se.haya.skk.CustomizationSettingsActivity
import se.haya.skk.KeyBindingPreferenceDialog
import se.haya.skk.R
import se.haya.skk.core.CandidateDisplayConfig
import se.haya.skk.core.PunctuationConfig
import se.haya.skk.core.keys.SkkCommand

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 35])
class CustomizationActivityTest {
    private var store: CustomizationStore? = null
    private var controller: ActivityController<CustomizationSettingsActivity>? = null

    @After fun cleanup() {
        controller?.destroy()
        store?.close()
        CustomizationSettingsActivity.storeFactoryForTest = null
        CustomizationSettingsActivity.appEmacsStoreFactoryForTest = null
        CustomizationSettingsActivity.validationFactoryForTest = null
    }

    @Test fun `句読点ページは一つの目的だけを表示して保存後に閉じる`() {
        val serial = ManualExecutor()
        val activity = start(CustomizationSettingsActivity.PAGE_PUNCTUATION, serial)
        val fragment = fragment(activity)

        assertNotNull(fragment.findPreference<ListPreference>("period"))
        assertNull(fragment.findPreference<ListPreference>("romaji_profile"))
        assertNull(fragment.findPreference<ListPreference>("page_mode"))

        fragment.findPreference<ListPreference>("period")!!.callChangeListener("．")
        activity.findViewById<android.widget.Button>(R.id.settings_save).performClick()
        serial.runAll()

        assertEquals("．", checkNotNull(store).snapshot.punctuation.period)
        assertTrue(activity.isFinishing)
    }

    @Test fun `競合後は触っていない最新値と自分の変更を両方残す`() {
        val original = CustomizationSettings(0, punctuation = PunctuationConfig(period = "。", comma = "、"))
        val local = CustomizationDraft.from(original).apply {
            punctuation = punctuation.copy(comma = "，")
        }
        val latest = original.replaced(
            generation = 1,
            punctuation = original.punctuation.copy(period = "．"),
            candidateDisplay = original.candidateDisplay.copy(inlineCandidateCount = 4),
        )

        val rebased = local.rebaseOnto(latest)

        assertEquals(0, rebased.conflictCount)
        assertEquals(1, rebased.localWins.base.generation)
        assertEquals("．", rebased.localWins.punctuation.period)
        assertEquals("，", rebased.localWins.punctuation.comma)
        assertEquals(4, rebased.localWins.candidateDisplay.inlineCandidateCount)
    }

    @Test fun `同じ項目の競合は自分の値と最新値を明示選択できる`() {
        val original = CustomizationSettings(0, punctuation = PunctuationConfig(period = "。"))
        val local = CustomizationDraft.from(original).apply {
            punctuation = punctuation.copy(period = "．")
        }
        val latest = original.replaced(generation = 1, punctuation = original.punctuation.copy(period = "."))

        val rebased = local.rebaseOnto(latest)

        assertEquals(1, rebased.conflictCount)
        assertEquals("．", rebased.localWins.punctuation.period)
        assertEquals(".", rebased.remoteWins.punctuation.period)
        assertEquals(1, rebased.localWins.base.generation)
        assertEquals(1, rebased.remoteWins.base.generation)
    }

    @Test fun `同じ項目の競合で自分の変更を選ぶと最新世代へ保存できる`() {
        val serial = ManualExecutor()
        val activity = start(CustomizationSettingsActivity.PAGE_PUNCTUATION, serial)
        fragment(activity).findPreference<ListPreference>("period")!!.callChangeListener("．")
        var externalSaved = false
        checkNotNull(store).save(
            checkNotNull(store).snapshot.replaced(punctuation = PunctuationConfig(period = ".")),
            expectedGeneration = 0,
        ) { externalSaved = it is CustomizationWriteResult.Applied }
        serial.runAll()
        assertTrue(externalSaved)

        activity.findViewById<android.widget.Button>(R.id.settings_save).performClick()
        serial.runAll()
        val dialog = ShadowDialog.getLatestDialog() as androidx.appcompat.app.AlertDialog
        assertEquals("自分の変更を残す", dialog.getButton(AlertDialog.BUTTON_POSITIVE).text.toString())
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        activity.findViewById<android.widget.Button>(R.id.settings_save).performClick()
        serial.runAll()

        assertEquals(2, checkNotNull(store).snapshot.generation)
        assertEquals("．", checkNotNull(store).snapshot.punctuation.period)
        assertTrue(activity.isFinishing)
    }

    @Test fun `候補ページの標準化は他ページの設定を変更しない`() {
        val serial = ManualExecutor()
        val initial = CustomizationSettings(
            0,
            punctuation = PunctuationConfig(period = "."),
            candidateDisplay = CandidateDisplayConfig(labels = "1234567", fixedPageSize = 3),
            emacsEnabled = true,
        )
        val activity = start(CustomizationSettingsActivity.PAGE_CANDIDATES, serial, initial)

        activity.resetDraftToDefaults()
        activity.findViewById<android.widget.Button>(R.id.settings_save).performClick()
        serial.runAll()

        val saved = checkNotNull(store).snapshot
        assertEquals(CandidateDisplayConfig(), saved.candidateDisplay)
        assertEquals(".", saved.punctuation.period)
        assertTrue(saved.emacsEnabled)
    }

    @Test fun `回転後も選択ページと未保存の下書きを保持する`() {
        val serial = ManualExecutor()
        val activity = start(CustomizationSettingsActivity.PAGE_PUNCTUATION, serial)
        fragment(activity).findPreference<ListPreference>("comma")!!.callChangeListener("，")

        controller = checkNotNull(controller).recreate()
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        val recreated = controller!!.get()

        assertEquals("，", fragment(recreated).findPreference<ListPreference>("comma")!!.value)
        assertTrue(recreated.findViewById<android.widget.Button>(R.id.settings_save).isEnabled)
        recreated.findViewById<android.widget.Button>(R.id.settings_save).performClick()
        serial.runAll()
        assertEquals("，", checkNotNull(store).snapshot.punctuation.comma)
    }

    @Test fun `回転後も未保存のEmacs適用範囲を保持する`() {
        val serial = ManualExecutor()
        val activity = start(CustomizationSettingsActivity.PAGE_KEYS, serial)
        fragment(activity).findPreference<ListPreference>("emacs_editing_mode")!!.callChangeListener("IME_ONLY")

        controller = checkNotNull(controller).recreate()
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        val recreated = controller!!.get()

        assertEquals("IME_ONLY", fragment(recreated).findPreference<ListPreference>("emacs_editing_mode")!!.value)
        recreated.findViewById<android.widget.Button>(R.id.settings_save).performClick()
        serial.runAll()
        assertEquals(EmacsEditingMode.IME_ONLY, checkNotNull(store).snapshot.emacsEditingMode)
    }

    @Test fun `保存失敗は画面と下書きを残す`() {
        val serial = ManualExecutor()
        val file = MemoryFile().apply { failWrite = true }
        val activity = start(CustomizationSettingsActivity.PAGE_PUNCTUATION, serial, file = file)
        fragment(activity).findPreference<ListPreference>("period")!!.callChangeListener("．")

        activity.findViewById<android.widget.Button>(R.id.settings_save).performClick()
        serial.runAll()

        assertFalse(activity.isFinishing)
        assertEquals("．", fragment(activity).findPreference<ListPreference>("period")!!.value)
        assertTrue(activity.findViewById<android.widget.TextView>(R.id.settings_status).text.contains("保存できません"))
    }

    @Test fun `ローマ字ページは規則を検索できカテゴリスピナーを持たない`() {
        val serial = ManualExecutor()
        val custom = CustomizationSettings(
            0,
            profile = CustomizationProfile.CUSTOM,
            customRules = listOf(
                se.haya.skk.core.romaji.RomajiRule("ka", "か"),
                se.haya.skk.core.romaji.RomajiRule("shi", "し"),
            ),
        )
        val activity = start(CustomizationSettingsActivity.PAGE_ROMAJI, serial, custom)
        val page = fragment(activity)

        assertNotNull(page.findPreference<EditTextPreference>("rule_search"))
        assertNull(page.findPreference<Preference>("sectionSelector"))
        page.findPreference<EditTextPreference>("rule_search")!!.callChangeListener("shi")

        val titles = page.preferenceScreen.flatten().mapNotNull { it.title?.toString() }
        assertTrue("shi → し" in titles)
        assertFalse("ka → か" in titles)
    }

    @Test fun `Emacs適用範囲を選択して保存する`() {
        val serial = ManualExecutor()
        val activity = start(CustomizationSettingsActivity.PAGE_KEYS, serial)
        val page = fragment(activity)

        page.findPreference<ListPreference>("emacs_editing_mode")!!.callChangeListener("IME_ONLY")
        activity.findViewById<android.widget.Button>(R.id.settings_save).performClick()
        serial.runAll()

        assertFalse(checkNotNull(store).snapshot.emacsEnabled)
        assertTrue(checkNotNull(store).snapshot.internalEmacsEnabled)
        assertEquals(EmacsEditingMode.IME_ONLY, checkNotNull(store).snapshot.emacsEditingMode)
    }

    @Test fun `Emacs設定は三つの適用範囲を一つの選択肢で表示する`() {
        val activity = start(CustomizationSettingsActivity.PAGE_KEYS, ManualExecutor())
        val page = fragment(activity)
        val emacs = page.findPreference<ListPreference>("emacs_editing_mode")!!

        assertEquals("Emacs キーバインド", emacs.title)
        assertEquals(listOf("無効", "IME 内のみ", "IME とアプリ"), emacs.entries.map { it.toString() })
        assertEquals("無効", emacs.summary)
        assertNull(page.findPreference<Preference>("emacs_enabled"))
        assertNull(page.findPreference<Preference>("internal_emacs_enabled"))
    }

    @Test fun `旧アプリ別設定の移行失敗中は全体設定を保存できず再試行後の再起動でも旧実効値を保つ`() {
        val serial = ManualExecutor()
        val initial = CustomizationSettings(0, emacsEnabled = true, internalEmacsEnabled = true)
        val file = MemoryFile()
        store = CustomizationStore(file, serial, Executor { it.run() })
        store!!.loadAsync()
        serial.runAll()
        store!!.save(initial, 0) {}
        serial.runAll()

        val preferences = RuntimeEnvironment.getApplication().getSharedPreferences(
            "app-emacs-global-migration-${UUID.randomUUID()}", Context.MODE_PRIVATE)
        preferences.edit().putStringSet("emacs_disabled_packages", setOf("com.example.editor")).commit()
        val migrationWorker = ManualExecutor()
        var failMigration = true
        val appStore = AppEmacsEditingStore(preferences, migrationWorker, Executor { it.run() }) { editor ->
            editor.apply()
            !failMigration
        }
        CustomizationSettingsActivity.storeFactoryForTest = { checkNotNull(store) }
        CustomizationSettingsActivity.appEmacsStoreFactoryForTest = { appStore }
        CustomizationSettingsActivity.validationFactoryForTest = { DirectExecutorService() }
        val intent = Intent().putExtra(CustomizationSettingsActivity.EXTRA_SECTION,
            CustomizationSettingsActivity.PAGE_KEYS)
        controller = Robolectric.buildActivity(CustomizationSettingsActivity::class.java, intent).setup()
        serial.runAll()

        val activity = controller!!.get()
        assertFalse(activity.findViewById<android.widget.Button>(R.id.settings_save).isEnabled)
        activity.findViewById<android.widget.Button>(R.id.settings_save).performClick()
        assertTrue(checkNotNull(store).snapshot.emacsEnabled)
        assertTrue(checkNotNull(store).snapshot.internalEmacsEnabled)

        migrationWorker.runAll()
        assertEquals(0, preferences.getInt("emacs_mode_format_version", 0))
        val retry = ShadowDialog.getLatestDialog() as androidx.appcompat.app.AlertDialog
        failMigration = false
        retry.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, migrationWorker.size)
        migrationWorker.runAll()
        Shadows.shadowOf(Looper.getMainLooper()).idle()

        assertNotNull(activity.currentDraft())
        activity.updateDraft { emacsEditingMode = EmacsEditingMode.IME_AND_APP }
        activity.findViewById<android.widget.Button>(R.id.settings_save).performClick()
        serial.runAll()
        assertFalse(checkNotNull(store).snapshot.internalEmacsEnabled)
        assertEquals(EmacsEditingMode.IME_ONLY,
            AppEmacsEditingStore(preferences, Executor { it.run() }, Executor { it.run() })
                .emacsEditingMode("com.example.editor", EmacsEditingMode.DISABLED))
    }

    @Test fun `キー割当行は現在の割り当てを標準の入力設定の概要で表示する`() {
        val activity = start(CustomizationSettingsActivity.PAGE_KEYS, ManualExecutor())
        val row = fragment(activity).findPreference<EditTextPreference>("key_binding_${SkkCommand.KANA.name}")!!

        assertEquals(SkkCommand.KANA.title, row.title)
        assertEquals(SkkCommand.KANA.title, row.dialogTitle)
        assertEquals("例: C-j（Ctrl+j）。この操作にはキーが必要です。", row.dialogMessage)
        assertEquals("C-j", row.summary.toString())
        assertFalse(activity.updateKeyBinding(SkkCommand.KANA, ""))
        assertEquals("C-j", row.summary.toString())
    }

    @Test fun `キー割当の標準入力ダイアログは不正値を保留し回転後も下書きへ反映する`() {
        val activity = start(CustomizationSettingsActivity.PAGE_KEYS, ManualExecutor())
        val row = fragment(activity).findPreference<EditTextPreference>("key_binding_${SkkCommand.KANA.name}")!!
        row.performClick()
        val dialog = keyBindingDialog(activity)
        val input = dialog.findViewById<EditText>(android.R.id.edit)!!
        input.setText("")
        assertFalse(dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled)

        controller = checkNotNull(controller).recreate()
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        val restoredDialog = keyBindingDialog(controller!!.get())
        val restoredInput = restoredDialog.findViewById<EditText>(android.R.id.edit)!!
        assertEquals("", restoredInput.text.toString())
        restoredInput.setText("C-k")
        assertTrue(restoredDialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled)
        restoredDialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()

        val restored = controller!!.get()
        settleDialogs(restored)
        assertEquals("C-k", fragment(restored)
            .findPreference<EditTextPreference>("key_binding_${SkkCommand.KANA.name}")!!.summary.toString())

        val optional = fragment(restored)
            .findPreference<EditTextPreference>("key_binding_${SkkCommand.QUOTE_NEXT.name}")!!
        optional.performClick()
        val optionalDialog = keyBindingDialog(restored)
        val optionalInput = optionalDialog.findViewById<EditText>(android.R.id.edit)!!
        optionalInput.setText("")
        assertTrue(optionalDialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled)
        optionalDialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        settleDialogs(restored)
        assertEquals("未割当", optional.summary.toString())
    }

    private fun start(
        page: Int,
        serial: ManualExecutor,
        initial: CustomizationSettings = CustomizationSettings.defaults(),
        file: MemoryFile = MemoryFile(),
    ): CustomizationSettingsActivity {
        store = CustomizationStore(file, serial, Executor { it.run() })
        store!!.loadAsync()
        serial.runAll()
        if (initial != CustomizationSettings.defaults()) {
            var seeded = false
            store!!.save(initial, 0) { seeded = it is CustomizationWriteResult.Applied }
            serial.runAll()
            check(seeded)
        }
        CustomizationSettingsActivity.storeFactoryForTest = { checkNotNull(store) }
        CustomizationSettingsActivity.validationFactoryForTest = { DirectExecutorService() }
        val intent = Intent().putExtra(CustomizationSettingsActivity.EXTRA_SECTION, page)
        controller = Robolectric.buildActivity(CustomizationSettingsActivity::class.java, intent).setup()
        serial.runAll()
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        return controller!!.get()
    }

    private fun fragment(activity: CustomizationSettingsActivity): CustomizationPageFragment =
        activity.supportFragmentManager.findFragmentById(R.id.settings_content) as CustomizationPageFragment

    private fun keyBindingDialog(activity: CustomizationSettingsActivity): androidx.appcompat.app.AlertDialog {
        val manager = activity.supportFragmentManager
        settleDialogs(activity)
        val fragment = manager.findFragmentByTag("key_binding_dialog") as? KeyBindingPreferenceDialog
        return requireNotNull(fragment?.dialog as? androidx.appcompat.app.AlertDialog)
    }

    private fun settleDialogs(activity: CustomizationSettingsActivity) {
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        activity.supportFragmentManager.executePendingTransactions()
        Shadows.shadowOf(Looper.getMainLooper()).idle()
    }

    private fun CustomizationSettings.replaced(
        generation: Long = this.generation,
        punctuation: PunctuationConfig = this.punctuation,
        candidateDisplay: CandidateDisplayConfig = this.candidateDisplay,
    ) = CustomizationSettings(
        generation, profile, customRules, punctuation, candidateDisplay, emacsEnabled, keyBindings,
        internalEmacsEnabled,
    )

    private fun androidx.preference.PreferenceGroup.flatten(): List<Preference> = buildList {
        for (index in 0 until preferenceCount) {
            val item = getPreference(index)
            add(item)
            if (item is androidx.preference.PreferenceGroup) addAll(item.flatten())
        }
    }

    private class ManualExecutor : Executor {
        private val tasks = ArrayDeque<Runnable>()
        val size: Int get() = tasks.size
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

    private class MemoryFile(var bytes: ByteArray? = null) : CustomizationFileAccess {
        var failWrite = false
        override fun exists(): Boolean = bytes != null
        override fun read(maxBytes: Int): ByteArray = checkNotNull(bytes).copyOf()
        override fun write(bytes: ByteArray) {
            if (failWrite) throw IOException("write failed")
            this.bytes = bytes.copyOf()
        }
    }
}
