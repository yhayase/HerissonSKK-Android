package se.haya.skk

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceFragmentCompat

/** 通常設定は目的別の入口だけを示し、初期設定とは分離します。 */
class SettingsActivity : SettingsPageActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        installSettingsPage("設定", SettingsIndexFragment())
        if (savedInstanceState == null && intent.action == Intent.ACTION_MAIN &&
            !getSharedPreferences("setup", MODE_PRIVATE).getBoolean("completed", false)) {
            startActivity(Intent(this, SetupActivity::class.java))
        }
    }

    internal fun openPhysicalKeyboardLayoutSettings() {
        when (dispatchPhysicalKeyboardLayoutSettings(
            Intent(Settings.ACTION_HARD_KEYBOARD_SETTINGS).resolveActivity(packageManager) != null,
        ) { action -> startActivity(Intent(action)) }) {
            PhysicalKeyboardLayoutSettingsDispatch.INPUT_METHOD_FALLBACK -> Toast.makeText(this,
                R.string.physical_keyboard_layout_settings_fallback, Toast.LENGTH_LONG).show()
            PhysicalKeyboardLayoutSettingsDispatch.UNAVAILABLE -> Toast.makeText(this,
                R.string.physical_keyboard_layout_settings_unavailable, Toast.LENGTH_LONG).show()
            PhysicalKeyboardLayoutSettingsDispatch.HARD_KEYBOARD -> Unit
        }
    }

    internal fun openPrivacyPolicy() {
        if (dispatchPrivacyPolicy { intent -> startActivity(intent) } == PrivacyPolicyDispatch.UNAVAILABLE) {
            Toast.makeText(this, "プライバシーポリシーを開けるアプリがありません。", Toast.LENGTH_LONG).show()
        }
    }

}

class SettingsIndexFragment : PreferenceFragmentCompat() {
    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        val context = requireContext()
        preferenceScreen = preferenceManager.createPreferenceScreen(context)
        fun category(title: String) = PreferenceCategory(context).apply {
            this.title = title
            isIconSpaceReserved = false
            preferenceScreen.addPreference(this)
        }
        fun row(group: PreferenceCategory, title: String, action: () -> Unit) {
            group.addPreference(Preference(context).apply {
                this.title = title
                isIconSpaceReserved = false
                setOnPreferenceClickListener { action(); true }
            })
        }
        fun customize(section: Int) = startActivity(Intent(context, CustomizationSettingsActivity::class.java)
            .putExtra(CustomizationSettingsActivity.EXTRA_SECTION, section))
        fun basic(page: String) = startActivity(Intent(context, BasicSettingsActivity::class.java)
            .putExtra(BasicSettingsActivity.EXTRA_PAGE, page))
        val input = category("入力")
        row(input, "ローマ字の打ち方") { customize(0) }
        row(input, "句読点と記号") { customize(1) }
        row(input, "キー操作とEmacs編集") { customize(3) }
        row(input, "確定と改行") { basic("input") }
        row(input, "アプリごとの編集キー") { startActivity(Intent(context, AppEmacsSettingsActivity::class.java)) }
        row(input, "物理キーボードの配列") { (requireActivity() as SettingsActivity).openPhysicalKeyboardLayoutSettings() }
        val display = category("表示")
        row(display, "画面キーボードとモード表示") { basic("display") }
        row(display, "候補の表示と選び方") { customize(2) }
        val dictionaries = category("辞書")
        row(dictionaries, "辞書の管理") { startActivity(Intent(context, DictionarySettingsActivity::class.java)) }
        row(dictionaries, "学習と補完") { basic("learning") }
        row(dictionaries, "バックアップと復元") { startActivity(Intent(context, CompleteDictionaryBackupActivity::class.java)) }
        val other = category("その他")
        row(other, "プライバシーポリシー") { (requireActivity() as SettingsActivity).openPrivacyPolicy() }
        row(other, "ライセンス") { startActivity(Intent(context, LicensesActivity::class.java)) }
        row(other, "初期設定") { startActivity(Intent(context, SetupActivity::class.java)) }
    }
}
internal enum class PhysicalKeyboardLayoutSettingsDispatch {
    HARD_KEYBOARD,
    INPUT_METHOD_FALLBACK,
    UNAVAILABLE,
}

internal enum class PrivacyPolicyDispatch {
    OPENED,
    UNAVAILABLE,
}

internal const val PRIVACY_POLICY_URL = "https://yhayase.github.io/HerissonSKK-Android/privacy-policy.html"

internal fun privacyPolicyIntent(): Intent = Intent(Intent.ACTION_VIEW, Uri.parse(PRIVACY_POLICY_URL))

internal fun dispatchPrivacyPolicy(startActivity: (Intent) -> Unit): PrivacyPolicyDispatch = try {
    startActivity(privacyPolicyIntent())
    PrivacyPolicyDispatch.OPENED
} catch (_: ActivityNotFoundException) {
    PrivacyPolicyDispatch.UNAVAILABLE
} catch (_: SecurityException) {
    PrivacyPolicyDispatch.UNAVAILABLE
}

internal fun dispatchPhysicalKeyboardLayoutSettings(
    hardKeyboardSettingsAvailable: Boolean,
    startSettings: (String) -> Unit,
): PhysicalKeyboardLayoutSettingsDispatch {
    if (hardKeyboardSettingsAvailable) {
        try {
            startSettings(Settings.ACTION_HARD_KEYBOARD_SETTINGS)
            return PhysicalKeyboardLayoutSettingsDispatch.HARD_KEYBOARD
        } catch (_: ActivityNotFoundException) {
            // フォールバックの設定画面を試します。
        } catch (_: SecurityException) {
            // フォールバックの設定画面を試します。
        }
    }
    return try {
        startSettings(Settings.ACTION_INPUT_METHOD_SETTINGS)
        PhysicalKeyboardLayoutSettingsDispatch.INPUT_METHOD_FALLBACK
    } catch (_: ActivityNotFoundException) {
        PhysicalKeyboardLayoutSettingsDispatch.UNAVAILABLE
    } catch (_: SecurityException) {
        PhysicalKeyboardLayoutSettingsDispatch.UNAVAILABLE
    }
}
