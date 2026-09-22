package se.haya.skk

import android.os.Bundle
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceFragmentCompat

/** 完全バックアップの明示操作を、Android 標準の設定項目として表示します。 */
class CompleteDictionaryBackupFragment : PreferenceFragmentCompat() {
    private val host: CompleteDictionaryBackupActivity
        get() = requireActivity() as CompleteDictionaryBackupActivity

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        val screen = preferenceManager.createPreferenceScreen(requireContext())
        preferenceScreen = screen

        val backupCategory = PreferenceCategory(requireContext()).apply {
            title = "辞書全体の保存と復元"
            isIconSpaceReserved = false
        }
        screen.addPreference(backupCategory)
        backupCategory.addPreference(action(
            KEY_EXPORT,
            "完全辞書バックアップを保存",
            "個人辞書、追加辞書、有効状態、優先順、非表示指定を保存します。",
            host::exportBackup,
        ))
        backupCategory.addPreference(action(
            KEY_RESTORE,
            "完全辞書バックアップから復元",
            "内容を検証し、確認後に現在の辞書全体を置き換えます。",
            host::restoreBackup,
        ))

        val applyCategory = PreferenceCategory(requireContext()).apply {
            title = "入力への反映"
            isIconSpaceReserved = false
        }
        screen.addPreference(applyCategory)
        applyCategory.addPreference(action(
            KEY_REAPPLY,
            "保存済みの辞書を入力へ再反映",
            "復元は保存済みでも入力へ反映できなかった場合に使います。",
            host::reapplySavedDictionaries,
        ))
        setControlsEnabled(!host.isPageBusy())
    }

    fun setControlsEnabled(enabled: Boolean) {
        preferenceScreen?.isEnabled = enabled
    }

    private fun action(keyText: String, titleText: String, summaryText: String, action: () -> Unit) =
        Preference(requireContext()).apply {
            key = keyText
            title = titleText
            summary = summaryText
            isIconSpaceReserved = false
            isPersistent = false
            setOnPreferenceClickListener { action(); true }
        }

    companion object {
        internal const val KEY_EXPORT = "complete_backup_export"
        internal const val KEY_RESTORE = "complete_backup_restore"
        internal const val KEY_REAPPLY = "complete_backup_reapply"
    }
}
