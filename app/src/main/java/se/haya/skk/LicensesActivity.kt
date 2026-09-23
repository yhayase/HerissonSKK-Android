package se.haya.skk

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.TypedValue
import android.view.ViewGroup
import android.widget.ScrollView
import android.widget.TextView
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat

/** アプリ本体と同梱する依存ライブラリのライセンスを端末内で表示します。 */
class LicensesActivity : SettingsPageActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val item = intent.getStringExtra(EXTRA_ITEM)?.let(LicenseItem::fromId)
        if (item == null) {
            installSettingsPage("ライセンス", LicensesFragment())
        } else {
            installSettingsPage(item.title(this), licenseTextView(this, item))
        }
    }

    private fun licenseTextView(context: Context, item: LicenseItem): ScrollView {
        val text = TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTextIsSelectable(true)
            setLineSpacing(0f, 1.2f)
            setPadding(dp(context, 20), dp(context, 16), dp(context, 20), dp(context, 24))
            text = loadLicenseText(this@LicensesActivity.javaClass.classLoader, item.resourcePath)
                ?: "ライセンス本文を読み込めませんでした。"
        }
        return ScrollView(context).apply {
            isFillViewport = true
            addView(text, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
    }

    companion object {
        const val EXTRA_ITEM = "se.haya.skk.LICENSE_ITEM"

        internal fun intentFor(context: Context, item: LicenseItem): Intent =
            Intent(context, LicensesActivity::class.java).putExtra(EXTRA_ITEM, item.id)
    }
}

class LicensesFragment : PreferenceFragmentCompat() {
    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        val context = requireContext()
        preferenceScreen = preferenceManager.createPreferenceScreen(context)
        LicenseItem.entries.forEach { item ->
            preferenceScreen.addPreference(Preference(context).apply {
                key = item.id
                title = item.title(context)
                summary = item.summary
                isIconSpaceReserved = false
                setOnPreferenceClickListener {
                    startActivity(LicensesActivity.intentFor(context, item))
                    true
                }
            })
        }
    }
}

internal enum class LicenseItem(
    val id: String,
    val title: String,
    val summary: String,
    val resourcePath: String,
) {
    HERISSON_SKK("herisson-skk", "HerissonSKK", "MIT License", "META-INF/HerissonSKK-MIT.txt"),
    LICENSE_SCOPE("license-scope", "ライセンスの適用範囲", "同梱する本文と対象ファイル", "META-INF/license-scope.txt"),
    THIRD_PARTY_NOTICES("third-party-notices", "第三者ライセンス通知", "同梱ライブラリの通知", "META-INF/third-party-notices.txt"),
    APACHE_2_0("apache-2.0", "Apache License 2.0", "Apache-2.0", "META-INF/Apache-2.0.txt"),
    ICU("icu", "ICU License", "Unicode と ICU のライセンス", "META-INF/icu-LICENSE.txt");

    companion object {
        fun fromId(id: String): LicenseItem? = entries.firstOrNull { it.id == id }
    }

    fun title(context: Context): String = if (this == HERISSON_SKK) context.getString(R.string.app_name) else title
}

/** APK に同梱したライセンス本文をネットワークを使わずに読み込みます。 */
internal fun loadLicenseText(classLoader: ClassLoader, resourcePath: String): String? =
    classLoader.getResourceAsStream(resourcePath)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }

private fun dp(context: Context, value: Int): Int = TypedValue.applyDimension(
    TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), context.resources.displayMetrics,
).toInt()
