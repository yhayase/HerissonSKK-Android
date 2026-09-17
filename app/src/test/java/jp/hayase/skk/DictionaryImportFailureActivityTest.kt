package jp.hayase.skk

import android.os.Looper
import android.widget.TextView
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 35])
class DictionaryImportFailureActivityTest {
    @Test fun `読み込み失敗は状態更新と画面再作成後も表示される`() {
        var controller = Robolectric.buildActivity(DictionarySettingsActivity::class.java).setup()
        try {
            val activity = controller.get()
            ReflectionHelpers.callInstanceMethod<Unit>(
                activity, "showImportFailure",
                ReflectionHelpers.ClassParameter.from(String::class.java, "42 行目の形式が不正です"),
            )
            ReflectionHelpers.callInstanceMethod<Unit>(
                activity, "handleManagerStatus",
                ReflectionHelpers.ClassParameter.from(
                    jp.hayase.skk.dictionary.DictionaryManagerStatus::class.java,
                    jp.hayase.skk.dictionary.DictionaryManagerStatus.Ready(),
                ),
            )
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            assertTrue(status(activity).contains("42 行目"))
            val dialog = org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog()
            assertTrue(dialog.isShowing)
            assertTrue(dialog.findViewById<TextView>(android.R.id.message).text.contains("42 行目"))
            dialog.dismiss()

            controller = controller.recreate()
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            assertTrue(status(controller.get()).contains("42 行目"))
        } finally {
            controller.destroy()
        }
    }

    @Test fun `解析中の画面再作成は再選択を案内する`() {
        var controller = Robolectric.buildActivity(DictionarySettingsActivity::class.java).setup()
        try {
            ReflectionHelpers.setField(controller.get(), "importInProgress", true)
            controller = controller.recreate()
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            assertTrue(status(controller.get()).contains("もう一度選んでください"))
        } finally {
            controller.destroy()
        }
    }

    @Test fun `一括保存後の公開失敗は閉じずに通知し画面再作成でも残す`() {
        var controller = Robolectric.buildActivity(DictionarySettingsActivity::class.java).setup()
        try {
            val activity = controller.get()
            ReflectionHelpers.callInstanceMethod<Unit>(activity, "onDraftApplied",
                ReflectionHelpers.ClassParameter.from(
                    jp.hayase.skk.dictionary.DictionaryManagerWriteResult::class.java,
                    jp.hayase.skk.dictionary.DictionaryManagerWriteResult.SavedButNotApplied(Unit)))
            org.junit.Assert.assertFalse(activity.isFinishing)
            val dialog = org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog()
            assertTrue(dialog.isShowing)
            assertTrue(dialog.findViewById<TextView>(android.R.id.message).text.contains("保存しました"))
            dialog.dismiss()
            controller = controller.recreate()
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            assertTrue(status(controller.get()).contains("反映できません"))
        } finally { controller.destroy() }
    }

    private fun status(activity: DictionarySettingsActivity): String =
        ReflectionHelpers.getField<TextView>(activity, "statusView").text.toString()
}
