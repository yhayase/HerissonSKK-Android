package se.haya.skk

import android.os.Bundle
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SwitchPreferenceCompat
import se.haya.skk.settings.BasicSetting
import se.haya.skk.settings.BasicSettingsRuntime
import se.haya.skk.settings.BasicSettingsStore

/** 一画面の基本設定を下書きとして編集します。 */
class BasicSettingsActivity : SettingsPageActivity() {
    private lateinit var store: BasicSettingsStore
    private lateinit var state: DraftState
    internal val draft get() = state.draft
    internal val keys get() = state.original.keys.toList()
    private lateinit var page: String
    private val renderListener: () -> Unit = { renderDraft() }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Fragmentの復元はsuper内で始まるため、参照される下書きを先に用意します。
        store = storeFactoryForTest?.invoke(this) ?: BasicSettingsRuntime.get(this)
        page = intent.getStringExtra(EXTRA_PAGE) ?: "display"
        val pageKeys = when (page) {
            "input" -> listOf(BasicSetting.CONFIRM_ONLY_ENTER)
            "learning" -> listOf(BasicSetting.SAVE_PERSONAL_DATA, BasicSetting.DYNAMIC_COMPLETION)
            else -> listOf(BasicSetting.HIDE_TOUCH_WITH_HARDWARE, BasicSetting.SHOW_STATUS)
        }
        state = lastCustomNonConfigurationInstance as? DraftState ?: run {
            val original = pageKeys.associateWith { key -> savedInstanceState?.getBoolean("original.${key.name}",
                store.snapshot().getValue(key).savedValue) ?: store.snapshot().getValue(key).savedValue }
            DraftState(original, pageKeys.associateWith { key -> savedInstanceState?.getBoolean(key.name,
                original.getValue(key)) ?: original.getValue(key) }.toMutableMap())
        }
        super.onCreate(savedInstanceState)
        installSettingsPage(when (page) {
            "input" -> "確定と改行"
            "learning" -> "学習と補完"
            else -> "画面キーボードとモード表示"
        }, BasicSettingsFragment())
        setPageActions(::saveAndClose, ::resetDraftToDefaults)
        state.changed = renderListener
        renderDraft()
    }

    internal fun changed() = renderDraft()

    private fun renderDraft() {
        if (state.result == true) { finish(); return }
        (supportFragmentManager.findFragmentById(R.id.settings_content) as? BasicSettingsFragment)
            ?.preferenceScreen?.isEnabled = !state.saving
        renderPageState(draft != state.original, state.saving, when {
            state.saving -> "保存しています…"
            state.result == false -> if (BasicSetting.SAVE_PERSONAL_DATA in keys &&
                store.snapshot().getValue(BasicSetting.SAVE_PERSONAL_DATA).status == se.haya.skk.settings.BasicSaveStatus.FAILED)
                "保存できませんでした。学習は停止中です。再試行してください。"
                else "保存できませんでした。もう一度保存してください。"
            else -> null
        })
    }

    override fun saveAndClose() {
        if (state.saving) return
        val retained = state
        retained.saving = true
        retained.result = null
        renderDraft()
        val changes = draft.filter { (key, value) -> value != state.original.getValue(key) }
        store.saveBatch(changes) { success ->
            // Activityを捕捉せず、再作成後の画面へ同じ保存結果を渡します。
            retained.saving = false
            retained.result = success
            retained.changed?.invoke()
        }
    }

    override fun resetDraftToDefaults() {
        keys.forEach { draft[it] = it.defaultValue }
        (supportFragmentManager.findFragmentById(R.id.settings_content) as? BasicSettingsFragment)?.refresh()
        renderDraft()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        draft.forEach { (key, value) -> outState.putBoolean(key.name, value) }
        state.original.forEach { (key, value) -> outState.putBoolean("original.${key.name}", value) }
        super.onSaveInstanceState(outState)
    }

    override fun onRetainCustomNonConfigurationInstance(): Any = state
    override fun onDestroy() {
        if (state.changed === renderListener) state.changed = null
        super.onDestroy()
    }

    private class DraftState(val original: Map<BasicSetting, Boolean>, val draft: MutableMap<BasicSetting, Boolean>) {
        var saving = false
        var result: Boolean? = null
        var changed: (() -> Unit)? = null
    }

    companion object {
        const val EXTRA_PAGE = "se.haya.skk.BASIC_SETTINGS_PAGE"
        internal var storeFactoryForTest: ((BasicSettingsActivity) -> BasicSettingsStore)? = null
    }
}

class BasicSettingsFragment : PreferenceFragmentCompat() {
    private val rows = linkedMapOf<BasicSetting, SwitchPreferenceCompat>()
    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        val activity = requireActivity() as BasicSettingsActivity
        preferenceScreen = preferenceManager.createPreferenceScreen(requireContext())
        activity.keys.forEach { key ->
            val labels = when (key) {
                BasicSetting.CONFIRM_ONLY_ENTER -> "Enterで確定だけ行う" to "オフの場合、確定後に改行します"
                BasicSetting.SAVE_PERSONAL_DATA -> "変換結果を学習する" to "登録した語と候補の使用履歴を保存します"
                BasicSetting.DYNAMIC_COMPLETION -> "見出し語の補完" to "入力中の読みから補完候補を表示します"
                BasicSetting.HIDE_TOUCH_WITH_HARDWARE -> "物理キーボード接続中は画面の文字キーを隠す" to "候補とモードはポップアップで表示します"
                BasicSetting.SHOW_STATUS -> "入力モードを表示する" to "あ・ア・Aなどのモードを表示します"
            }
            rows[key] = SwitchPreferenceCompat(requireContext()).apply {
                widgetLayoutResource = R.layout.preference_widget_material_switch
                this.key = key.preferenceKey
                title = labels.first
                isSingleLineTitle = false
                summary = labels.second
                isPersistent = false
                isIconSpaceReserved = false
                setDefaultValue(activity.draft.getValue(key))
                isChecked = activity.draft.getValue(key)
                setOnPreferenceChangeListener { _, value ->
                    activity.draft[key] = value as Boolean
                    activity.changed()
                    true
                }
                preferenceScreen.addPreference(this)
            }
        }
    }
    internal fun refresh() {
        val activity = requireActivity() as BasicSettingsActivity
        rows.forEach { (key, row) -> row.isChecked = activity.draft.getValue(key) }
    }
}
