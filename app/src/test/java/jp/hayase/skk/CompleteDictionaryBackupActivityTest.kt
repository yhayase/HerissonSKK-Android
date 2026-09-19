package jp.hayase.skk

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Looper
import android.widget.Button
import android.widget.TextView
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executor
import jp.hayase.skk.dictionary.CompleteBackupResult
import jp.hayase.skk.dictionary.DictionaryManager
import jp.hayase.skk.dictionary.PreparedDictionaryRestore
import jp.hayase.skk.dictionary.SQLiteDictionaryRepository
import jp.hayase.skk.core.dictionary.SkkDictionaryCodec
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 35])
class CompleteDictionaryBackupActivityTest {
    private lateinit var repository: SQLiteDictionaryRepository
    private lateinit var manager: DictionaryManager
    private var controller: ActivityController<CompleteDictionaryBackupActivity>? = null
    private val context get() = RuntimeEnvironment.getApplication()
    private lateinit var database: String

    @Before fun setup() {
        database = "backup-ui-${System.nanoTime()}.db"
        repository = SQLiteDictionaryRepository(context, database)
        val direct = Executor { it.run() }
        manager = DictionaryManager(repository, direct, direct, deferReads = false)
        manager.loadAsync()
        CompleteDictionaryBackupActivity.managerFactoryForTest = { manager }
    }

    @After fun cleanup() {
        controller?.destroy()
        CompleteDictionaryBackupActivity.managerFactoryForTest = null
        manager.close()
        context.deleteDatabase(database)
    }

    @Test fun `辞書管理画面から完全バックアップ画面へ移動できる`() {
        val navigation = Robolectric.buildActivity(DictionarySettingsActivity::class.java).setup()
        try {
            fun find(view: android.view.View): Button? {
                if (view is Button && view.text.toString() == "完全辞書バックアップ・復元") return view
                if (view is android.view.ViewGroup) {
                    for (index in 0 until view.childCount) find(view.getChildAt(index))?.let { return it }
                }
                return null
            }
            checkNotNull(find(navigation.get().window.decorView)).performClick()
            assertEquals(CompleteDictionaryBackupActivity::class.java.name,
                Shadows.shadowOf(navigation.get()).nextStartedActivity.component?.className)
        } finally { navigation.destroy() }
    }

    @Test fun `保存先の明示選択は作成 picker を使い取消では辞書を変更しない`() {
        val activity = start()
        val revision = repository.dictionaryRevision()
        button(activity, "exportButton").performClick()
        val launched = Shadows.shadowOf(activity).nextStartedActivityForResult
        assertEquals(Intent.ACTION_CREATE_DOCUMENT, launched.intent.action)
        assertEquals("application/octet-stream", launched.intent.type)
        assertEquals("skk-dictionaries.skkbackup", launched.intent.getStringExtra(Intent.EXTRA_TITLE))
        result(activity, launched.requestCode, Activity.RESULT_CANCELED, null)
        assertEquals(revision, repository.dictionaryRevision())
        assertTrue(button(activity, "restoreButton").isEnabled)
        assertTrue(status(activity).contains("変更していません"))
        // 消費済み picker の遅延結果は読み込みも復元も開始しません。
        result(activity, launched.requestCode, Activity.RESULT_OK, Intent().setData(Uri.parse("content://invalid/late")))
        assertEquals(revision, repository.dictionaryRevision())
        assertTrue(status(activity).contains("変更していません"))
    }

    @Test fun `picker 待機中の再作成後も取消結果を一度だけ処理する`() {
        val activity = start()
        button(activity, "restoreButton").performClick()
        val launched = Shadows.shadowOf(activity).nextStartedActivityForResult
        controller = controller!!.recreate()
        val recreated = controller!!.get()
        assertFalse(button(recreated, "restoreButton").isEnabled)
        result(recreated, launched.requestCode, Activity.RESULT_CANCELED, null)
        assertTrue(button(recreated, "restoreButton").isEnabled)
    }

    @Test fun `復元確認の取消は準備ファイルを閉じ元の辞書を保持する`() {
        val activity = start()
        val prepared = prepare()
        val revision = repository.dictionaryRevision()
        showPreview(activity, prepared)
        val dialog = org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog()
        dialog.getButton(android.app.AlertDialog.BUTTON_NEGATIVE).performClick()
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        assertEquals(revision, repository.dictionaryRevision())
        var result: Any? = null
        manager.restoreComplete(prepared) { result = it }
        assertTrue(result is CompleteBackupResult.Failed)
    }

    @Test fun `確認画面の再作成は未適用の復元を破棄し再適用しない`() {
        val activity = start()
        val prepared = prepare()
        val revision = repository.dictionaryRevision()
        showPreview(activity, prepared)
        controller = controller!!.recreate()
        assertEquals(revision, repository.dictionaryRevision())
        var result: Any? = null
        manager.restoreComplete(prepared) { result = it }
        assertTrue(result is CompleteBackupResult.Failed)
        assertTrue(button(controller!!.get(), "restoreButton").isEnabled)
    }

    @Test fun `置換の明示確認だけが一度復元し同じ画面操作を再適用しない`() {
        val activity = start()
        val prepared = prepare()
        val revision = repository.dictionaryRevision()
        showPreview(activity, prepared)
        val dialog = org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog()
        dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick()
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        assertEquals(revision + 1, repository.dictionaryRevision())
        assertTrue(status(activity).contains("復元し、入力へ反映"))
        ReflectionHelpers.callInstanceMethod<Unit>(activity, "applyRestore")
        assertEquals(revision + 1, repository.dictionaryRevision())
    }

    private fun start(): CompleteDictionaryBackupActivity {
        controller = Robolectric.buildActivity(CompleteDictionaryBackupActivity::class.java).setup()
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        return controller!!.get()
    }

    private fun prepare(): PreparedDictionaryRestore {
        repository.replacePersonal(SkkDictionaryCodec.parseText("かな /仮名/\n"))
        val bytes = ByteArrayOutputStream().also {
            repository.writeCompleteBackup(it, emptyList(), "test")
        }.toByteArray()
        var prepared: PreparedDictionaryRestore? = null
        manager.prepareCompleteRestore(context, { ByteArrayInputStream(bytes) }) {
            assertTrue(it is CompleteBackupResult.Applied)
            prepared = (it as CompleteBackupResult.Applied).value
        }
        return checkNotNull(prepared)
    }

    private fun showPreview(activity: CompleteDictionaryBackupActivity, prepared: PreparedDictionaryRestore) {
        ReflectionHelpers.callInstanceMethod<Unit>(activity, "setBusy", ReflectionHelpers.ClassParameter.from(Boolean::class.javaPrimitiveType, true))
        ReflectionHelpers.callInstanceMethod<Unit>(activity, "showPreview", ReflectionHelpers.ClassParameter.from(PreparedDictionaryRestore::class.java, prepared))
    }
    private fun button(activity: Activity, name: String): Button = ReflectionHelpers.getField(activity, name)
    private fun status(activity: Activity): String = ReflectionHelpers.getField<TextView>(activity, "status").text.toString()
    private fun result(activity: Activity, request: Int, code: Int, data: Intent?) {
        ReflectionHelpers.callInstanceMethod<Unit>(activity, "onActivityResult",
            ReflectionHelpers.ClassParameter.from(Int::class.javaPrimitiveType, request),
            ReflectionHelpers.ClassParameter.from(Int::class.javaPrimitiveType, code),
            ReflectionHelpers.ClassParameter.from(Intent::class.java, data))
    }
}
