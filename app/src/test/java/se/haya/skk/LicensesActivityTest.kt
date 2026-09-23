package se.haya.skk

import android.content.Intent
import android.widget.TextView
import androidx.preference.Preference
import androidx.preference.PreferenceGroup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 35])
class LicensesActivityTest {
    @Test fun `設定のその他ではプライバシーポリシーをライセンスの前に開ける`() {
        val activity = Robolectric.buildActivity(SettingsActivity::class.java).setup().get()
        val fragment = activity.supportFragmentManager.findFragmentById(R.id.settings_content) as SettingsIndexFragment
        val other = (0 until fragment.preferenceScreen.preferenceCount)
            .map { fragment.preferenceScreen.getPreference(it) }
            .first { it.title == "その他" } as PreferenceGroup
        val rows = other.flatten()

        assertEquals(listOf("プライバシーポリシー", "ライセンス", "初期設定"), rows.map { it.title.toString() })
        assertEquals(null, shadowOf(activity).nextStartedActivity)
        rows.first().performClick()

        val next = shadowOf(activity).nextStartedActivity
        assertEquals(Intent.ACTION_VIEW, next.action)
        assertEquals(PRIVACY_POLICY_URL, next.dataString)
    }

    @Test fun `ライセンス一覧は本体と同梱する通知の本文へ遷移する`() {
        val activity = Robolectric.buildActivity(LicensesActivity::class.java).setup().get()
        val rows = preferences(activity)

        assertEquals(
            listOf(activity.getString(R.string.app_name), "アプリアイコンの利用条件", "外部辞書について", "GNU General Public License v2.0", "ライセンスの適用範囲", "第三者ライセンス通知", "Apache License 2.0", "ICU License"),
            rows.map { it.title.toString() },
        )
        rows.first { it.key == "third-party-notices" }.performClick()

        val next = shadowOf(activity).nextStartedActivity
        assertNotNull(next)
        assertEquals(LicensesActivity::class.java.name, next.component?.className)
        assertEquals("third-party-notices", next.getStringExtra(LicensesActivity.EXTRA_ITEM))
    }

    @Test fun `すべてのライセンス本文を同梱リソースからオフラインで表示する`() {
        LicenseItem.entries.forEach { item ->
            val expected = checkNotNull(loadLicenseText(requireNotNull(LicensesActivity::class.java.classLoader), item.resourcePath))
            val intent = Intent(Intent.ACTION_VIEW).putExtra(LicensesActivity.EXTRA_ITEM, item.id)

            Robolectric.buildActivity(LicensesActivity::class.java, intent).setup().use { controller ->
                assertEquals(expected, findText(controller.get()))
            }
        }
    }

    @Test fun `外部辞書の通知には取得元と辞書ごとの条件を示す`() {
        val text = checkNotNull(loadLicenseText(
            requireNotNull(LicensesActivity::class.java.classLoader),
            LicenseItem.EXTERNAL_DICTIONARIES.resourcePath,
        ))

        assert(text.contains("https://skk-dev.github.io/dict/"))
        assert(text.contains("GPL-2.0-or-later"))
        assert(text.contains("Public Domain"))
        assert(text.contains("本アプリの開発者は、ここに挙げる辞書データを配布していません。"))
    }

    private fun preferences(activity: LicensesActivity): List<Preference> {
        val fragment = activity.supportFragmentManager.findFragmentById(R.id.settings_content) as LicensesFragment
        return fragment.preferenceScreen.flatten()
    }

    private fun findText(activity: LicensesActivity): String {
        fun textFrom(view: android.view.View): String? = (view as? TextView)?.text?.toString()
        fun visit(view: android.view.View): String? {
            textFrom(view)?.takeIf { it.isNotEmpty() }?.let { return it }
            if (view is android.view.ViewGroup) {
                for (index in 0 until view.childCount) visit(view.getChildAt(index))?.let { return it }
            }
            return null
        }
        return visit(activity.findViewById(R.id.settings_content)) ?: ""
    }

    private fun PreferenceGroup.flatten(): List<Preference> = buildList {
        for (index in 0 until preferenceCount) {
            val item = getPreference(index)
            add(item)
            if (item is PreferenceGroup) addAll(item.flatten())
        }
    }
}
