package se.haya.skk

import android.app.AlertDialog
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Looper
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import java.util.ArrayDeque
import java.util.concurrent.Executor
import se.haya.skk.dictionary.DictionaryManager
import se.haya.skk.dictionary.DictionarySettingsDraft
import se.haya.skk.dictionary.SQLiteDictionaryRepository
import se.haya.skk.dictionary.network.NetworkDictionaryCatalog
import se.haya.skk.dictionary.network.NetworkDictionaryDownload
import se.haya.skk.core.dictionary.SkkDictionaryEncoding
import org.junit.After
import org.junit.Before
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    private lateinit var repository: SQLiteDictionaryRepository
    private lateinit var manager: DictionaryManager
    private lateinit var database: String

    @Before fun setUpManager() {
        val context = org.robolectric.RuntimeEnvironment.getApplication()
        database = "dictionary-network-ui-${System.nanoTime()}.db"
        repository = SQLiteDictionaryRepository(context, database)
        val direct = Executor { it.run() }
        manager = DictionaryManager(repository, direct, direct, deferReads = false)
        manager.loadAsync()
        DictionarySettingsActivity.managerFactoryForTest = { manager }
    }

    @After fun resetActivityFactories() {
        DictionarySettingsActivity.fileExecutorFactoryForTest = null
        DictionarySettingsActivity.networkDownloadForTest = null
        DictionarySettingsActivity.networkUiExecutorForTest = null
        DictionarySettingsActivity.managerFactoryForTest = null
        manager.close()
        org.robolectric.RuntimeEnvironment.getApplication().deleteDatabase(database)
    }

    @Test fun `旧初期 S 辞書指定で画面を開いても取得を開始しない`() {
        var downloads = 0
        DictionarySettingsActivity.networkDownloadForTest = { _, _ ->
            downloads++
            error("画面を開いただけで取得してはいけません")
        }
        val controller = Robolectric.buildActivity(DictionarySettingsActivity::class.java,
            Intent().putExtra(DictionarySettingsActivity.EXTRA_INITIAL_S_DICTIONARY, true)).setup()
        try {
            assertEquals(0, downloads)
        } finally { controller.destroy() }
    }

    @Test fun `復元した picker 待機中は辞書追加を無効化し取消後に戻す`() {
        val saved = Bundle().apply {
            putString("dictionary.picker.operation", "EXPORT")
        }
        val controller = Robolectric.buildActivity(DictionarySettingsActivity::class.java).setup(saved)
        try {
            val activity = controller.get()
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            val add = checkNotNull(findByDescription(activity.window.decorView, "辞書を追加"))
            assertFalse(add.isEnabled)

            ReflectionHelpers.callInstanceMethod<Unit>(activity, "onActivityResult",
                ReflectionHelpers.ClassParameter.from(Int::class.javaPrimitiveType, 101),
                ReflectionHelpers.ClassParameter.from(Int::class.javaPrimitiveType, Activity.RESULT_CANCELED),
                ReflectionHelpers.ClassParameter.from(Intent::class.java, null))
            Shadows.shadowOf(Looper.getMainLooper()).idle()

            assertTrue(add.isEnabled)
        } finally { controller.destroy() }
    }

    @Test fun `ファイル更新は対象と完全一致する永続読取権限だけを受理する`() {
        val target = Uri.parse("content://documents/tree/source")

        assertTrue(hasMatchingPersistedReadGrant(target, listOf(target to true)))
        assertFalse(hasMatchingPersistedReadGrant(target, listOf(target to false)))
        assertFalse(hasMatchingPersistedReadGrant(
            target,
            listOf(Uri.parse("content://documents/tree/other") to true),
        ))
    }

    @Test fun `ドラッグハンドルをクリックして辞書を下へ移動できる`() {
        repository.importSystem("a", "辞書A", se.haya.skk.core.dictionary.SkkDictionaryCodec.parseText("かな /甲/"))
        repository.importSystem("b", "辞書B", se.haya.skk.core.dictionary.SkkDictionaryCodec.parseText("かな /乙/"))
        val controller = Robolectric.buildActivity(DictionarySettingsActivity::class.java).setup()
        try {
            val activity = controller.get()
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            val handle = findByDescription(activity.window.decorView, "辞書Aの優先順位を変更")
            checkNotNull(handle).performClick()
            val dialog = latestMaterialDialog()
            val item = dialog.listView.adapter.getView(0, null, dialog.listView)
            dialog.listView.performItemClick(item, 0, 0)
            Shadows.shadowOf(Looper.getMainLooper()).idle()

            assertEquals(
                listOf("辞書Bの優先順位を変更", "辞書Aの優先順位を変更"),
                descriptionsEndingWith(activity.window.decorView, "の優先順位を変更"),
            )
            checkNotNull(findByDescription(activity.window.decorView, "辞書Aの優先順位を変更")).performClick()
            val moveUp = latestMaterialDialog()
            val moveUpItem = moveUp.listView.adapter.getView(0, null, moveUp.listView)
            moveUp.listView.performItemClick(moveUpItem, 0, 0)
            Shadows.shadowOf(Looper.getMainLooper()).idle()

            val draft = ReflectionHelpers.getField<DictionarySettingsDraft>(activity, "draft")
            assertEquals(listOf("a", "b"), draft.sources.filter { it.kind.name == "SYSTEM" }.map { it.id })
            assertEquals(
                listOf("辞書Aの優先順位を変更", "辞書Bの優先順位を変更"),
                descriptionsEndingWith(activity.window.decorView, "の優先順位を変更"),
            )
        } finally { controller.destroy() }
    }

    @Test fun `選んだ文字コードは画面再作成後も保持する`() {
        var controller = Robolectric.buildActivity(DictionarySettingsActivity::class.java).setup()
        controller.get().setEncoding(SkkDictionaryEncoding.EUC_JP)

        controller = controller.recreate()

        assertEquals(SkkDictionaryEncoding.EUC_JP, controller.get().selectedEncoding())
        controller.destroy()
    }

    @Test fun `ネットワーク取得失敗後は URL 追加を再試行できる`() {
        DictionarySettingsActivity.fileExecutorFactoryForTest = { Executor { it.run() } }
        DictionarySettingsActivity.networkUiExecutorForTest = Executor { it.run() }
        val controller = Robolectric.buildActivity(DictionarySettingsActivity::class.java).setup()
        try {
            val activity = controller.get()
            openUrlDialog(activity, "http://example.com/dict")
            Shadows.shadowOf(Looper.getMainLooper()).idle()

            assertTrue(status(activity).contains("HTTPS"))
        } finally { controller.destroy() }
    }

    @Test fun `取消したネットワーク取得の遅延完了はプレビューを表示しない`() {
        val pending = ArrayDeque<Runnable>()
        DictionarySettingsActivity.fileExecutorFactoryForTest = { Executor { pending.addLast(it) } }
        DictionarySettingsActivity.networkUiExecutorForTest = Executor { it.run() }
        DictionarySettingsActivity.networkDownloadForTest = { url, _ ->
            NetworkDictionaryDownload("かな /仮名/\n".toByteArray(), url)
        }
        val controller = Robolectric.buildActivity(DictionarySettingsActivity::class.java).setup()
        try {
            val activity = controller.get()
            activity.addFromUrl("https://example.com/dict")
            activity.cancelNetworkImport()
            pending.removeFirst().run()
            Shadows.shadowOf(Looper.getMainLooper()).idle()

            assertTrue(status(activity).contains("中止しました"))
            assertFalse(ReflectionHelpers.getField<DictionarySettingsDraft>(activity, "draft").hasChanges)
            assertEquals(null, org.robolectric.shadows.ShadowDialog.getLatestDialog())
        } finally { controller.destroy() }
    }

    @Test fun `画面破棄後のネットワーク取得完了は保存も下書き変更もしない`() {
        val pending = ArrayDeque<Runnable>()
        DictionarySettingsActivity.fileExecutorFactoryForTest = { Executor { pending.addLast(it) } }
        DictionarySettingsActivity.networkUiExecutorForTest = Executor { it.run() }
        DictionarySettingsActivity.networkDownloadForTest = { url, _ ->
            NetworkDictionaryDownload("かな /仮名/\n".toByteArray(), url)
        }
        val controller = Robolectric.buildActivity(DictionarySettingsActivity::class.java).setup()
        val activity = controller.get()
        activity.addFromUrl("https://example.com/dict")
        controller.destroy()
        pending.removeFirst().run()
        Shadows.shadowOf(Looper.getMainLooper()).idle()

        assertTrue(repository.listSources().none { it.kind.name == "SYSTEM" })
    }

    @Test fun `カタログ辞書のプレビュー後に破棄すると保存済み辞書を変更しない`() {
        DictionarySettingsActivity.fileExecutorFactoryForTest = { Executor { it.run() } }
        DictionarySettingsActivity.networkUiExecutorForTest = Executor { it.run() }
        DictionarySettingsActivity.networkDownloadForTest = { url, _ ->
            NetworkDictionaryDownload("かな /仮名/\n".toByteArray(), url)
        }
        val controller = Robolectric.buildActivity(DictionarySettingsActivity::class.java).setup()
        try {
            val activity = controller.get()
            stageCatalogImport(activity)
            assertTrue(ReflectionHelpers.getField<DictionarySettingsDraft>(activity, "draft").hasChanges)
            assertTrue(repository.listSources().none { it.id == "official-skk-S" })

            activity.requestPageClose()
            latestMaterialDialog()
                .getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
            assertTrue(repository.listSources().none { it.id == "official-skk-S" })
        } finally { controller.destroy() }
    }

    @Test fun `カタログ辞書はプレビューと適用の後にだけ保存される`() {
        DictionarySettingsActivity.fileExecutorFactoryForTest = { Executor { it.run() } }
        DictionarySettingsActivity.networkUiExecutorForTest = Executor { it.run() }
        DictionarySettingsActivity.networkDownloadForTest = { url, _ ->
            NetworkDictionaryDownload("かな /仮名/\n".toByteArray(), url)
        }
        val controller = Robolectric.buildActivity(DictionarySettingsActivity::class.java).setup()
        try {
            val activity = controller.get()
            stageCatalogImport(activity)
            activity.findViewById<Button>(R.id.settings_save).performClick()
            Shadows.shadowOf(Looper.getMainLooper()).idle()

            assertEquals(NetworkDictionaryCatalog.find("S")!!.url,
                repository.listSources().single { it.id == "official-skk-S" }.originUrl)
        } finally { controller.destroy() }
    }

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
                    se.haya.skk.dictionary.DictionaryManagerStatus::class.java,
                    se.haya.skk.dictionary.DictionaryManagerStatus.Ready(),
                ),
            )
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            assertTrue(status(activity).contains("42 行目"))
            val dialog = latestMaterialDialog()
            assertTrue(dialog.isShowing)
            assertTrue(checkNotNull(dialog.findViewById<TextView>(android.R.id.message)).text.contains("42 行目"))
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
                    se.haya.skk.dictionary.DictionaryManagerWriteResult::class.java,
                    se.haya.skk.dictionary.DictionaryManagerWriteResult.SavedButNotApplied(Unit)))
            org.junit.Assert.assertFalse(activity.isFinishing)
            val dialog = latestMaterialDialog()
            assertTrue(dialog.isShowing)
            assertTrue(checkNotNull(dialog.findViewById<TextView>(android.R.id.message)).text.contains("保存しました"))
            dialog.dismiss()
            controller = controller.recreate()
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            assertTrue(status(controller.get()).contains("反映できません"))
        } finally { controller.destroy() }
    }

    private fun status(activity: DictionarySettingsActivity): String =
        activity.findViewById<TextView>(R.id.settings_status).text.toString()

    private fun stageCatalogImport(activity: DictionarySettingsActivity) {
        activity.confirmCatalogInstall(checkNotNull(NetworkDictionaryCatalog.find("S")))
        latestMaterialDialog()
            .getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        Shadows.shadowOf(Looper.getMainLooper()).runToEndOfTasks()
        val preview = latestMaterialDialog()
        assertTrue(checkNotNull(preview.findViewById<TextView>(android.R.id.message)).text.contains("操作:"))
        preview
            .getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        Shadows.shadowOf(Looper.getMainLooper()).idle()
    }

    private fun button(activity: DictionarySettingsActivity, text: String): Button =
        checkNotNull(findButton(activity.window.decorView, text))

    private fun openUrlDialog(activity: DictionarySettingsActivity, url: String) {
        activity.showUrlImportDialog()
        val dialog = latestMaterialDialog()
        checkNotNull(findEditText(dialog.window!!.decorView)).setText(url)
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
    }

    private fun findEditText(view: android.view.View): EditText? = when (view) {
        is EditText -> view
        is android.view.ViewGroup -> (0 until view.childCount).firstNotNullOfOrNull { findEditText(view.getChildAt(it)) }
        else -> null
    }

    private fun latestMaterialDialog(): androidx.appcompat.app.AlertDialog =
        checkNotNull(org.robolectric.shadows.ShadowDialog.getLatestDialog() as? androidx.appcompat.app.AlertDialog)

    private fun findButton(view: android.view.View, text: String): Button? = when {
        view is Button && view.text.toString() == text -> view
        view is android.view.ViewGroup -> (0 until view.childCount).firstNotNullOfOrNull { index ->
            findButton(view.getChildAt(index), text)
        }
        else -> null
    }

    private fun findByDescription(view: android.view.View, description: String): android.view.View? = when {
        view.contentDescription?.toString() == description -> view
        view is android.view.ViewGroup -> (0 until view.childCount).firstNotNullOfOrNull { index ->
            findByDescription(view.getChildAt(index), description)
        }
        else -> null
    }

    private fun descriptionsEndingWith(view: android.view.View, suffix: String): List<String> = buildList {
        view.contentDescription?.toString()?.takeIf { it.endsWith(suffix) }?.let(::add)
        if (view is android.view.ViewGroup) (0 until view.childCount).forEach { index ->
            addAll(descriptionsEndingWith(view.getChildAt(index), suffix))
        }
    }
}
