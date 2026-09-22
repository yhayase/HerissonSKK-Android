package se.haya.skk

import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import androidx.preference.CheckBoxPreference
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceFragmentCompat
import java.io.Closeable
import se.haya.skk.dictionary.network.NetworkDictionaryCatalog

/** 初期設定を通常の設定一覧と分離します。 */
class SetupActivity : SettingsPageActivity() {
    internal val selected = linkedSetOf<String>()
    private lateinit var installer: InitialDictionaryInstaller
    private var subscription: Closeable? = null
    internal var completing = false
        private set
    private var completionTarget = emptySet<String>()
    private var current = InitialDictionaryInstaller.State()
    private var followInstalledGeneral = false

    override fun onCreate(savedInstanceState: Bundle?) {
        followInstalledGeneral = savedInstanceState?.getBoolean("followInstalledGeneral") ?: true
        selected.addAll(savedInstanceState?.getStringArrayList("selected") ?: emptyList())
        if (selected.isEmpty()) selected += "S"
        completing = savedInstanceState?.getBoolean("completing") ?: false
        completionTarget = savedInstanceState?.getStringArrayList("target")?.toSet() ?: emptySet()
        super.onCreate(savedInstanceState)
        installSettingsPage("初期設定", SetupFragment())
        setPageActions(::completeSetup)
        findViewById<Button>(R.id.settings_save).text = "初期設定を完了"
        installer = installerFactoryForTest?.invoke(this) ?: InitialDictionaryInstaller.get(this)
        // 新しいプロセスの空状態を購読する前に、保存済みの完了対象を再開します。
        if (completing) installer.install(completionTarget)
        else installer.ensureBasic()
        subscription = installer.observe {
            current = it
            if (followInstalledGeneral && it.loaded) {
                selected.removeAll(setOf("S", "L"))
                selected += if ("L" in it.installed) "L" else "S"
                followInstalledGeneral = false
            }
            renderPageState(false, false, it.error ?: when {
                it.busy -> "辞書を準備しています…"
                !it.loaded -> "辞書一覧を読み込んでいます…"
                else -> null
            })
            if (completing && !it.busy) {
                if (it.error == null && completionTarget.all(it.installed::contains)) finishSetup()
                else completing = false
            }
            (supportFragmentManager.findFragmentById(R.id.settings_content) as? SetupFragment)?.refresh(it)
        }
    }

    internal fun selectGeneral(key: String) {
        followInstalledGeneral = false
        selected.remove(if (key == "S") "L" else "S")
        selected += key
    }

    internal fun completeSetup() {
        if (completing || !current.loaded) return
        followInstalledGeneral = false
        completionTarget = normalizeInitialDictionaryKeys(selected).toSet()
        completing = true
        (supportFragmentManager.findFragmentById(R.id.settings_content) as? SetupFragment)?.refresh(current)
        installer.install(completionTarget)
    }

    internal fun retry() {
        if (!current.loaded) installer.ensureBasic()
        else installer.install(
            if (completionTarget.isEmpty()) normalizeInitialDictionaryKeys(selected).toSet() else completionTarget,
        )
    }
    internal fun finishSetup() {
        getSharedPreferences("setup", MODE_PRIVATE).edit().putBoolean("completed", true).apply()
        finish()
    }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putStringArrayList("selected", ArrayList(selected))
        outState.putBoolean("completing", completing)
        outState.putStringArrayList("target", ArrayList(completionTarget))
        outState.putBoolean("followInstalledGeneral", followInstalledGeneral)
        super.onSaveInstanceState(outState)
    }
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) (supportFragmentManager.findFragmentById(R.id.settings_content) as? SetupFragment)
            ?.refreshImeState()
    }
    override fun onDestroy() { subscription?.close(); super.onDestroy() }
    companion object {
        internal var installerFactoryForTest: ((SetupActivity) -> InitialDictionaryInstaller)? = null
    }
}

class SetupFragment : PreferenceFragmentCompat() {
    private lateinit var enableIme: Preference
    private lateinit var selectIme: Preference
    private lateinit var general: ListPreference
    private lateinit var retry: Preference
    private lateinit var priority: Preference
    private val additionalChoices = mutableListOf<CheckBoxPreference>()

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        val context = requireContext()
        val activity = requireActivity() as SetupActivity
        additionalChoices.clear()
        preferenceScreen = preferenceManager.createPreferenceScreen(context)

        val input = PreferenceCategory(context).apply {
            title = "1. 入力方法を設定する"
            isIconSpaceReserved = false
            preferenceScreen.addPreference(this)
        }
        enableIme = Preference(context).apply {
            title = "入力方法を有効にする"
            isIconSpaceReserved = false
            setOnPreferenceClickListener {
                startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
                true
            }
            input.addPreference(this)
        }
        selectIme = Preference(context).apply {
            title = "入力方法を選ぶ"
            isIconSpaceReserved = false
            setOnPreferenceClickListener {
                context.getSystemService(InputMethodManager::class.java).showInputMethodPicker()
                true
            }
            input.addPreference(this)
        }

        val dictionaries = PreferenceCategory(context).apply {
            title = "2. 辞書を選ぶ"
            isIconSpaceReserved = false
            preferenceScreen.addPreference(this)
        }
        general = ListPreference(context).apply {
            key = "setup_general_dictionary"
            title = "一般辞書"
            dialogTitle = title
            isPersistent = false
            isIconSpaceReserved = false
            entries = listOf("S", "L").map { requireNotNull(NetworkDictionaryCatalog.find(it)).displayName }.toTypedArray()
            entryValues = arrayOf("S", "L")
            value = if ("L" in activity.selected) "L" else "S"
            summaryProvider = ListPreference.SimpleSummaryProvider.getInstance()
            setOnPreferenceChangeListener { _, value ->
                if (activity.completing) false else {
                    activity.selectGeneral(value as String)
                    updatePriority()
                    true
                }
            }
            dictionaries.addPreference(this)
        }
        NetworkDictionaryCatalog.entries.filter { it.key != "S" && it.key != "L" }.forEach { entry ->
            dictionaries.addPreference(CheckBoxPreference(context).apply {
                key = entry.key
                title = entry.displayName
                summary = entry.description
                isPersistent = false
                isIconSpaceReserved = false
                isChecked = entry.key in activity.selected
                setOnPreferenceChangeListener { _, value ->
                    if (activity.completing) false else {
                        if (value as Boolean) activity.selected += entry.key else activity.selected -= entry.key
                        updatePriority()
                        true
                    }
                }
                additionalChoices += this
            })
        }
        priority = Preference(context).apply {
            title = "辞書の優先順位"
            isSelectable = false
            isIconSpaceReserved = false
            dictionaries.addPreference(this)
        }
        retry = Preference(context).apply {
            title = "辞書の取得を再試行"
            isIconSpaceReserved = false
            isVisible = false
            setOnPreferenceClickListener { activity.retry(); true }
            dictionaries.addPreference(this)
        }
        preferenceScreen.addPreference(Preference(context).apply {
            title = "あとで設定する"
            summary = "辞書は設定画面から後で変更できます"
            isIconSpaceReserved = false
            setOnPreferenceClickListener { activity.finishSetup(); true }
        })
        updatePriority()
    }

    override fun onResume() {
        super.onResume()
        refreshImeState()
    }

    internal fun refresh(state: InitialDictionaryInstaller.State) {
        retry.isVisible = state.error != null
        retry.title = if (state.loaded) "辞書の取得を再試行" else "辞書一覧の読み込みを再試行"
        retry.isEnabled = !state.busy
        val activity = requireActivity() as SetupActivity
        activity.findViewById<Button>(R.id.settings_save).isEnabled = state.loaded && !state.busy && !activity.completing
        general.isEnabled = !activity.completing
        general.value = if ("L" in activity.selected) "L" else "S"
        additionalChoices.forEach {
            it.isEnabled = !activity.completing
            it.isChecked = it.key in activity.selected
        }
        updatePriority()
    }

    internal fun refreshImeState() {
        if (!::enableIme.isInitialized) return
        val context = requireContext()
        val service = ComponentName(context, SkkInputMethodService::class.java)
        val manager = context.getSystemService(InputMethodManager::class.java)
        val enabled = manager.enabledInputMethodList.any {
            ComponentName(it.serviceInfo.packageName, it.serviceInfo.name) == service
        }
        val selected = ComponentName.unflattenFromString(
            Settings.Secure.getString(context.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD).orEmpty(),
        ) == service
        enableIme.summary = if (enabled) "有効です" else "無効です"
        selectIme.summary = if (selected) "選択されています" else "選択されていません"
    }

    private fun updatePriority() {
        if (!::priority.isInitialized) return
        val activity = requireActivity() as SetupActivity
        priority.summary = normalizeInitialDictionaryKeys(activity.selected)
            .mapNotNull { NetworkDictionaryCatalog.find(it)?.displayName }
            .joinToString(" → ")
    }
}

internal fun normalizeInitialDictionaryKeys(keys: Collection<String>): List<String> {
    val selected = keys.toSet().let { if ("L" in it) it - "S" else it }
    return NetworkDictionaryCatalog.entries.map { it.key }.filter { it in selected }
}
