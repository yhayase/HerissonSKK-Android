package se.haya.skk.settings

import android.content.Context
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.Switch
import android.widget.TextView
import java.util.UUID
import java.util.concurrent.Executor
import se.haya.skk.BasicSettingsActivity
import se.haya.skk.BasicSettingsFragment
import se.haya.skk.R
import android.content.Intent
import androidx.preference.SwitchPreferenceCompat
import se.haya.skk.dictionary.PersonalDataPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 35])
class BasicSettingsStoreTest {
    private val context = RuntimeEnvironment.getApplication()
    private var activity: ActivityController<BasicSettingsActivity>? = null

    @After fun cleanup() {
        activity?.destroy()
        BasicSettingsActivity.storeFactoryForTest = null
    }

    @Test fun `保存値がない場合はEnter確定のみを無効として読み込む`() {
        val store = BasicSettingsStore(preferences(), Executor { it.run() }, Executor { it.run() }, {})
        val state = store.snapshot().getValue(BasicSetting.CONFIRM_ONLY_ENTER)
        assertFalse(state.savedValue)
        assertFalse(state.visibleValue)
    }

    @Test fun `保存禁止は直ちに待機許可を失効し失敗後もオフで表示する`() {
        val prefs = preferences().apply { edit().putBoolean("save_personal_data", true).commit() }
        val worker = ManualExecutor()
        val policy = PersonalDataPolicy()
        val permit = requireNotNull(policy.request(true))
        val store = BasicSettingsStore(prefs, worker, Executor { it.run() }, policy::setAllowed,
            { editor -> editor.apply(); false })

        store.request(BasicSetting.SAVE_PERSONAL_DATA, false)
        assertFalse(policy.accepts(permit))
        assertEquals(BasicSaveStatus.PENDING, store.snapshot().getValue(BasicSetting.SAVE_PERSONAL_DATA).status)
        assertTrue("commit はワーカーまで実行しません", prefs.getBoolean("save_personal_data", true))
        worker.runAll()

        assertTrue("失敗した変更をメモリー上にも残しません", prefs.getBoolean("save_personal_data", false))
        val state = store.snapshot().getValue(BasicSetting.SAVE_PERSONAL_DATA)
        assertEquals(BasicSaveStatus.FAILED, state.status)
        assertTrue(state.savedValue)
        assertFalse(state.visibleValue)
        assertEquals(false, state.failedValue)
        assertFalse(policy.request(true) != null)
    }

    @Test fun `保存許可はcommit成功まで有効化せず失敗でも有効化しない`() {
        val prefs = preferences().apply { edit().putBoolean("save_personal_data", false).commit() }
        val worker = ManualExecutor()
        val policy = PersonalDataPolicy(initiallyAllowed = false)
        var firstPolicyRead: Boolean? = null
        val store = BasicSettingsStore(prefs, worker, Executor { it.run() }, { allowed ->
            if (firstPolicyRead == null) firstPolicyRead = prefs.getBoolean("save_personal_data", true)
            policy.setAllowed(allowed)
        }, { editor -> editor.apply(); false })

        store.request(BasicSetting.SAVE_PERSONAL_DATA, true)
        assertEquals(false, firstPolicyRead)
        assertEquals(null, policy.request(true))
        worker.runAll()

        assertEquals(false, prefs.getBoolean("save_personal_data", true))
        assertEquals(BasicSaveStatus.FAILED, store.snapshot().getValue(BasicSetting.SAVE_PERSONAL_DATA).status)
        assertEquals(null, policy.request(true))
    }

    @Test fun `保留中enableの古い完了は後続disableを再許可しない`() {
        val prefs = preferences().apply { edit().putBoolean("save_personal_data", false).commit() }
        val worker = ManualExecutor()
        val callback = ManualExecutor()
        val policy = PersonalDataPolicy(initiallyAllowed = false)
        val store = BasicSettingsStore(prefs, worker, callback, policy::setAllowed)

        store.request(BasicSetting.SAVE_PERSONAL_DATA, true)
        worker.runNext()
        store.request(BasicSetting.SAVE_PERSONAL_DATA, false)
        callback.runNext()
        assertEquals(null, policy.request(true))
        assertEquals(BasicSaveStatus.PENDING, store.snapshot().getValue(BasicSetting.SAVE_PERSONAL_DATA).status)
        worker.runAll()
        callback.runAll()

        assertEquals(false, prefs.getBoolean("save_personal_data", true))
        assertEquals(BasicSaveStatus.SAVED, store.snapshot().getValue(BasicSetting.SAVE_PERSONAL_DATA).status)
        assertEquals(null, policy.request(true))
    }

    @Test fun `失敗時は存在しなかったキーを存在しない状態へ戻す`() {
        val prefs = preferences()
        val worker = ManualExecutor()
        val store = BasicSettingsStore(prefs, worker, Executor { it.run() }, {},
            { editor -> editor.apply(); false })
        assertFalse(prefs.contains("show_status"))

        store.request(BasicSetting.SHOW_STATUS, false)
        worker.runAll()

        assertFalse(prefs.contains("show_status"))
        assertEquals(BasicSaveStatus.FAILED, store.snapshot().getValue(BasicSetting.SHOW_STATUS).status)
        assertTrue(store.snapshot().getValue(BasicSetting.SHOW_STATUS).visibleValue)
    }

    @Test fun `別設定の保存成功は個人保存の失敗警告と停止を消さない`() {
        val prefs = preferences().apply { edit().putBoolean("save_personal_data", true).commit() }
        val worker = ManualExecutor()
        val policy = PersonalDataPolicy()
        var writes = 0
        val store = BasicSettingsStore(prefs, worker, Executor { it.run() }, policy::setAllowed,
            { editor ->
                writes++
                if (writes <= 2) { editor.apply(); false } else editor.commit()
            })

        store.request(BasicSetting.SAVE_PERSONAL_DATA, false)
        worker.runAll()
        store.request(BasicSetting.SHOW_STATUS, false)
        worker.runAll()

        val personal = store.snapshot().getValue(BasicSetting.SAVE_PERSONAL_DATA)
        assertEquals(BasicSaveStatus.FAILED, personal.status)
        assertFalse(personal.visibleValue)
        assertEquals(false, personal.failedValue)
        assertEquals(null, policy.request(true))
        assertEquals(BasicSaveStatus.SAVED, store.snapshot().getValue(BasicSetting.SHOW_STATUS).status)
        assertEquals(false, prefs.getBoolean("show_status", true))
    }

    @Test fun `補完だけの一括保存は個人データ保存の許可を変更しない`() {
        val prefs = preferences().apply { edit().putBoolean("save_personal_data", true).commit() }
        val worker = ManualExecutor()
        val policyChanges = mutableListOf<Boolean>()
        val store = BasicSettingsStore(prefs, worker, Executor { it.run() }, { policyChanges += it })

        store.saveBatch(mapOf(BasicSetting.SAVE_PERSONAL_DATA to true,
            BasicSetting.DYNAMIC_COMPLETION to true)) { assertTrue(it) }
        worker.runAll()

        assertEquals(emptyList<Boolean>(), policyChanges)
        assertEquals(BasicSaveStatus.IDLE, store.snapshot().getValue(BasicSetting.SAVE_PERSONAL_DATA).status)
        assertTrue(store.snapshot().getValue(BasicSetting.SAVE_PERSONAL_DATA).visibleValue)
        assertTrue(prefs.getBoolean("save_personal_data", false))
        assertTrue(prefs.getBoolean("dynamic_completion", false))
    }

    @Test fun `補完だけの一括保存失敗は個人データ保存を停止しない`() {
        val prefs = preferences().apply { edit().putBoolean("save_personal_data", true).commit() }
        val worker = ManualExecutor()
        val policyChanges = mutableListOf<Boolean>()
        val store = BasicSettingsStore(prefs, worker, Executor { it.run() }, { policyChanges += it },
            { editor -> editor.apply(); false })

        store.saveBatch(mapOf(BasicSetting.SAVE_PERSONAL_DATA to true,
            BasicSetting.DYNAMIC_COMPLETION to true)) { assertFalse(it) }
        worker.runAll()

        assertEquals(emptyList<Boolean>(), policyChanges)
        val personal = store.snapshot().getValue(BasicSetting.SAVE_PERSONAL_DATA)
        assertEquals(BasicSaveStatus.IDLE, personal.status)
        assertTrue(personal.savedValue)
        assertTrue(personal.visibleValue)
        assertTrue(prefs.getBoolean("save_personal_data", false))
    }

    @Test fun `回転中の保存失敗は停止中と未保存を表示し再試行できる`() {
        val prefs = preferences()
        val worker = ManualExecutor()
        val policy = PersonalDataPolicy()
        var fail = true
        val store = BasicSettingsStore(prefs, worker, Executor { it.run() }, policy::setAllowed,
            { editor -> if (fail) { editor.apply(); false } else editor.commit() })
        BasicSettingsActivity.storeFactoryForTest = { store }
        activity = Robolectric.buildActivity(BasicSettingsActivity::class.java, Intent(context, BasicSettingsActivity::class.java)
            .putExtra(BasicSettingsActivity.EXTRA_PAGE, "learning")).setup()
        val original = activity!!.get()
        switch(original, BasicSetting.SAVE_PERSONAL_DATA).callChangeListener(false)
        switch(original, BasicSetting.SAVE_PERSONAL_DATA).isChecked = false
        retry(original, BasicSetting.SAVE_PERSONAL_DATA).performClick()
        activity = activity!!.recreate()
        val recreated = activity!!.get()
        assertFalse(switch(recreated, BasicSetting.SAVE_PERSONAL_DATA).isEnabled)
        assertTrue(status(recreated, BasicSetting.SAVE_PERSONAL_DATA).text.contains("保存しています"))

        worker.runAll()
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        assertFalse(switch(recreated, BasicSetting.SAVE_PERSONAL_DATA).isChecked)
        assertTrue(status(recreated, BasicSetting.SAVE_PERSONAL_DATA).text.contains("保存できません"))
        assertEquals(View.VISIBLE, retry(recreated, BasicSetting.SAVE_PERSONAL_DATA).visibility)
        assertEquals(null, policy.request(true))

        fail = false
        retry(recreated, BasicSetting.SAVE_PERSONAL_DATA).performClick()
        worker.runAll()
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        assertEquals(false, prefs.getBoolean("save_personal_data", true))
        assertTrue(recreated.isFinishing)
        assertEquals(null, policy.request(true))
    }

    private fun preferences() = context.getSharedPreferences("basic-settings-${UUID.randomUUID()}", Context.MODE_PRIVATE)

    private fun switch(activity: BasicSettingsActivity, key: BasicSetting): SwitchPreferenceCompat =
        (activity.supportFragmentManager.findFragmentById(R.id.settings_content) as BasicSettingsFragment)
            .findPreference<SwitchPreferenceCompat>(key.preferenceKey)!!

    private fun status(activity: BasicSettingsActivity, key: BasicSetting): TextView =
        activity.findViewById(R.id.settings_status)

    private fun retry(activity: BasicSettingsActivity, key: BasicSetting): Button =
        activity.findViewById(R.id.settings_save)

    private class ManualExecutor : Executor {
        private val tasks = ArrayDeque<Runnable>()
        override fun execute(command: Runnable) { tasks += command }
        fun runNext() { tasks.removeFirst().run() }
        fun runAll() { while (tasks.isNotEmpty()) runNext() }
    }
}
