package se.haya.skk

import android.os.Bundle
import android.text.SpannableString
import android.text.style.ForegroundColorSpan
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.SearchView
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceFragmentCompat
import se.haya.skk.settings.EmacsEditingMode

class AppEmacsSettingsFragment : PreferenceFragmentCompat() {
    private val host: AppEmacsSettingsActivity get() = requireActivity() as AppEmacsSettingsActivity
    private lateinit var searchView: SearchView

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, state: Bundle?): View {
        val list = super.onCreateView(inflater, container, state)
        return LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            searchView = SearchView(context).apply {
                queryHint = "アプリ名またはパッケージ名で検索"
                isIconified = false
                setQuery(host.query(), false)
                setOnQueryTextListener(object : SearchView.OnQueryTextListener {
                    override fun onQueryTextSubmit(query: String?) = true
                    override fun onQueryTextChange(query: String?): Boolean {
                        host.setSearchQuery(query.orEmpty())
                        return true
                    }
                })
            }
            addView(searchView, LinearLayout.LayoutParams(-1, -2))
            addView(list, LinearLayout.LayoutParams(-1, 0, 1f))
        }
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        refresh()
    }

    fun refresh() {
        if (!isAdded) return
        val screen = preferenceManager.createPreferenceScreen(requireContext())
        preferenceScreen = screen
        val category = PreferenceCategory(requireContext()).apply { title = "アプリ" }
        screen.addPreference(category)
        val state = host.currentState()
        val global = host.globalMode()
        host.visibleApps().forEach { app ->
            val override = state?.visibleSettings?.overrideFor(app.packageName)
            val effective = global?.let { override ?: it }
            val effectiveLabel = when (effective) {
                EmacsEditingMode.DISABLED -> "無効"
                EmacsEditingMode.IME_ONLY -> "IME 内のみ"
                EmacsEditingMode.IME_AND_APP -> "IME とアプリ"
                null -> "確認中"
            }
            category.addPreference(Preference(requireContext()).apply {
                title = app.displayName
                summary = currentStateSummary(effectiveLabel, override, app.packageName)
                isPersistent = false
                setOnPreferenceClickListener { host.chooseOverride(app); true }
            })
        }
        if (host.visibleApps().isEmpty()) {
            category.addPreference(Preference(requireContext()).apply {
                title = if (host.allAppCount() == 0) "起動できるアプリがありません" else "一致するアプリはありません"
                isSelectable = false
            })
        }
        if (state?.status == se.haya.skk.settings.AppEmacsEditingSaveStatus.FAILED) {
            screen.addPreference(Preference(requireContext()).apply {
                title = "保存を再試行"
                summary = "直前に失敗した変更をもう一度保存します。"
                setOnPreferenceClickListener { host.retryFailedSave(); true }
            })
        }
    }

    private fun currentStateSummary(effective: String, override: EmacsEditingMode?, packageName: String): CharSequence {
        val current = "現在: $effective"
        return SpannableString(buildString {
            append(current)
            append("\n")
            append(when (override) {
                EmacsEditingMode.DISABLED -> "無効"
                EmacsEditingMode.IME_ONLY -> "IME 内のみ"
                EmacsEditingMode.IME_AND_APP -> "IME とアプリ"
                null -> "全体設定に従う"
            })
            append("\n").append(packageName)
        }).apply {
            setSpan(
                ForegroundColorSpan(primaryTextColor()),
                current.length - effective.length,
                current.length,
                SpannableString.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
        }
    }

    private fun primaryTextColor(): Int {
        val attributes = requireContext().obtainStyledAttributes(intArrayOf(android.R.attr.textColorPrimary))
        return try {
            attributes.getColorStateList(0)?.defaultColor ?: 0
        } finally {
            attributes.recycle()
        }
    }

    internal fun searchViewForTest(): SearchView = searchView
}
