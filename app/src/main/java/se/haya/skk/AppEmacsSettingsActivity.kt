package se.haya.skk

import android.content.Intent
import android.os.Bundle
import java.io.Closeable
import java.util.Locale
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import se.haya.skk.settings.AppEmacsEditingRuntime
import se.haya.skk.settings.AppEmacsEditingSaveStatus
import se.haya.skk.settings.AppEmacsEditingSettings
import se.haya.skk.settings.AppEmacsEditingState
import se.haya.skk.settings.AppEmacsEditingStore
import se.haya.skk.settings.CustomizationRuntime
import se.haya.skk.settings.CustomizationSettings
import se.haya.skk.settings.CustomizationStore
import se.haya.skk.settings.EmacsEditingMode

/** 起動可能なアプリを一覧にし、各行から継承または編集モードを直ちに保存します。 */
class AppEmacsSettingsActivity : SettingsPageActivity() {
    private lateinit var store: AppEmacsEditingStore
    private lateinit var pageFragment: AppEmacsSettingsFragment
    private var subscription: Closeable? = null
    private var globalSettings: CustomizationSettings? = null
    private var apps: List<AppChoice> = emptyList()
    private var state: AppEmacsEditingState? = null
    private var searchQuery = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = storeFactoryForTest?.invoke(this) ?: AppEmacsEditingRuntime.get(this)
        searchQuery = savedInstanceState?.getString(STATE_SEARCH).orEmpty()
        val requestedPage = AppEmacsSettingsFragment()
        installSettingsPage("アプリ別 Emacs 編集", requestedPage)
        pageFragment = supportFragmentManager.findFragmentById(R.id.settings_content)
            as? AppEmacsSettingsFragment ?: requestedPage
        renderPageState(false, true, "全体設定を読み込んでいます")
        subscription = store.observe(::render)
        (customizationStoreFactoryForTest?.invoke(this) ?: CustomizationRuntime.get(this)).loadAsync { result ->
            if (!isFinishing && !isDestroyed) {
                globalSettings = result.settings
                store.migrateLegacy(result.settings.internalEmacsEnabled)
                state?.let(::render)
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString(STATE_SEARCH, searchQuery)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        subscription?.close()
        subscription = null
        super.onDestroy()
    }

    private fun render(next: AppEmacsEditingState) {
        state = next
        apps = appChoices(launcherAppsProviderForTest?.invoke(this) ?: launcherApps(), next.visibleSettings)
        if (::pageFragment.isInitialized) pageFragment.refresh()
        val pending = next.status == AppEmacsEditingSaveStatus.PENDING || globalSettings == null
        val message = when (next.status) {
            AppEmacsEditingSaveStatus.IDLE -> if (apps.isEmpty()) "起動できるアプリがありません" else
                "変更は選択したときに保存され、次の入力欄から反映します。"
            AppEmacsEditingSaveStatus.PENDING -> "保存しています"
            AppEmacsEditingSaveStatus.SAVED -> "保存しました。次の入力欄から反映します。"
            AppEmacsEditingSaveStatus.FAILED -> "保存できませんでした。前回保存した設定を表示しています。"
        }
        renderPageState(false, pending, message)
    }

    internal fun visibleApps(): List<AppChoice> {
        val query = searchQuery.trim().lowercase(Locale.ROOT)
        return if (query.isBlank()) apps else apps.filter {
            query in it.label.lowercase(Locale.ROOT) || query in it.packageName.lowercase(Locale.ROOT)
        }
    }

    internal fun allAppCount(): Int = apps.size
    internal fun query(): String = searchQuery
    internal fun currentState(): AppEmacsEditingState? = state
    internal fun globalMode(): EmacsEditingMode? = globalSettings?.emacsEditingMode

    internal fun setSearchQuery(value: String) {
        searchQuery = value.trim()
        pageFragment.refresh()
    }

    internal fun chooseOverride(app: AppChoice) {
        val current = state ?: return
        val global = globalSettings ?: return
        val selected = when (current.visibleSettings.overrideFor(app.packageName)) {
            EmacsEditingMode.DISABLED -> 1
            EmacsEditingMode.IME_ONLY -> 2
            EmacsEditingMode.IME_AND_APP -> 3
            null -> 0
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(app.label)
            .setSingleChoiceItems(
                arrayOf("全体設定に従う", "無効", "IME 内のみ", "IME とアプリ"),
                selected,
            ) { dialog, which ->
                val override = when (which) {
                    1 -> EmacsEditingMode.DISABLED
                    2 -> EmacsEditingMode.IME_ONLY
                    3 -> EmacsEditingMode.IME_AND_APP
                    else -> null
                }
                if (override != null && runCatching { global.withEmacsEditingMode(override) }.isFailure) {
                    dialog.dismiss()
                    MaterialAlertDialogBuilder(this)
                        .setTitle("有効にできません")
                        .setMessage("編集キーが他の操作と重複しています。キー操作の設定を確認してください。")
                        .setPositiveButton(android.R.string.ok, null)
                        .show()
                } else {
                    store.requestOverride(app.packageName, override)
                    dialog.dismiss()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    internal fun retryFailedSave() {
        val current = store.snapshot()
        current.failedPackageName?.let { store.requestOverride(it, current.failedOverride) }
    }

    private fun launcherApps(): List<AppChoice> = packageManager.queryIntentActivities(
        Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0,
    ).mapNotNull { resolve ->
        val packageName = resolve.activityInfo?.packageName ?: return@mapNotNull null
        if (!AppEmacsEditingSettings.isValidPackageName(packageName)) return@mapNotNull null
        val label = resolve.loadLabel(packageManager)?.toString()?.takeIf(String::isNotBlank) ?: packageName
        AppChoice(packageName, label, true)
    }

    companion object {
        private const val STATE_SEARCH = "app_emacs_search"
        internal var storeFactoryForTest: ((AppEmacsSettingsActivity) -> AppEmacsEditingStore)? = null
        internal var customizationStoreFactoryForTest: ((AppEmacsSettingsActivity) -> CustomizationStore)? = null
        internal var launcherAppsProviderForTest: ((AppEmacsSettingsActivity) -> List<AppChoice>)? = null
    }
}

internal data class AppChoice(val packageName: String, val label: String, val installed: Boolean) {
    val displayName: String get() = if (installed) label else "$packageName（現在は一覧にありません）"
}

internal fun appChoices(installed: List<AppChoice>, settings: AppEmacsEditingSettings): List<AppChoice> {
    val installedByPackage = installed.filter { AppEmacsEditingSettings.isValidPackageName(it.packageName) }
        .associateBy { it.packageName }
    return (installedByPackage.values + settings.overriddenPackages
        .filterNot(installedByPackage::containsKey)
        .map { AppChoice(it, it, false) })
        .sortedWith(compareBy<AppChoice> { it.label.lowercase(Locale.ROOT) }.thenBy { it.packageName })
}
