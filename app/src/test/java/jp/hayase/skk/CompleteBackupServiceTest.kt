package jp.hayase.skk

import android.text.InputType
import android.text.Selection
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.Executor
import jp.hayase.skk.core.BasicSkkAction
import jp.hayase.skk.core.DictionaryQuery
import jp.hayase.skk.core.dictionary.SkkDictionaryCodec
import jp.hayase.skk.dictionary.CompleteBackupResult
import jp.hayase.skk.dictionary.DictionaryManager
import jp.hayase.skk.dictionary.DictionaryManagerSubscription
import jp.hayase.skk.dictionary.PreparedDictionaryRestore
import jp.hayase.skk.dictionary.SQLiteDictionaryRepository
import jp.hayase.skk.input.EditorSession
import jp.hayase.skk.settings.CustomizationStore
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 35])
class CompleteBackupServiceTest {
    @Test fun `復元通知は表示本文を再挿入せず削除確認を終え新しい入力の保存を許可する`() {
        withService { service, manager, repository, connection, restore ->
            val current = session(service)
            current.handle(BasicSkkAction.Text("Kana "))
            current.handle(BasicSkkAction.Text("X"))
            assertNotNull(current.view.deletion)
            val displayed = connection.editable.toString()
            val writes = connection.textWrites
            restore()
            assertEquals(displayed, connection.editable.toString())
            assertEquals(writes, connection.textWrites)
            assertNull(current.view.deletion)
            assertNull(current.view.candidate)
            assertNull(current.view.registration)
            assertTrue(current.active)
            val before = repository.dictionaryRevision()
            current.handle(BasicSkkAction.Text("Kana "))
            assertEquals("復元候補", current.view.candidate?.selected?.text)
            current.handle(BasicSkkAction.Enter)
            assertTrue(repository.dictionaryRevision() > before)
            assertEquals(listOf("復元候補"), manager.lookup(DictionaryQuery("かな")).map { it.text })
        }
    }

    @Test fun `復元通知は登録本文の古い保存確認も終了する`() {
        withService { service, _, repository, connection, restore ->
            val current = session(service)
            current.handle(BasicSkkAction.Text("Mikan "))
            current.handle(BasicSkkAction.Text("蜜柑"))
            assertNotNull(current.view.registration)
            val displayed = connection.editable.toString()
            restore()
            assertNull(current.view.registration)
            assertEquals(displayed, connection.editable.toString())
            assertTrue(repository.lookup("みかん").asComposite().lookup(DictionaryQuery("みかん")).isEmpty())
        }
    }

    private fun withService(block: (SkkInputMethodService, DictionaryManager, SQLiteDictionaryRepository,
        Connection, () -> Unit) -> Unit) {
        val context = RuntimeEnvironment.getApplication()
        val preferences = context.getSharedPreferences("settings", 0)
        val hadStatus = preferences.contains("show_status")
        val oldStatus = preferences.getBoolean("show_status", true)
        preferences.edit().putBoolean("show_status", false).commit()
        val name = "complete-service-${System.nanoTime()}.db"
        val repository = SQLiteDictionaryRepository(context, name)
        repository.replacePersonal(SkkDictionaryCodec.parseText("かな /復元候補/"))
        val bytes = ByteArrayOutputStream().also { repository.writeCompleteBackup(it, emptyList(), "test") }.toByteArray()
        repository.replacePersonal(SkkDictionaryCodec.parseText("かな /元候補/"))
        val direct = Executor { it.run() }
        val manager = DictionaryManager(repository, direct, direct, deferReads = false).also { it.loadAsync() }
        val customizationPath = File(context.cacheDir, "complete-service-${System.nanoTime()}.json")
        val customization = CustomizationStore(customizationPath, direct, direct).also { it.loadAsync() }
        val controller = Robolectric.buildService(SkkInputMethodService::class.java).create()
        val service = controller.get()
        ReflectionHelpers.getField<DictionaryManagerSubscription>(service, "dictionarySubscription").close()
        ReflectionHelpers.setField(service, "dictionaries", manager)
        ReflectionHelpers.setField(service, "customization", customization)
        val subscription = manager.observe {
            ReflectionHelpers.callInstanceMethod<Unit>(service, "onDictionaryStatusChanged")
        }
        ReflectionHelpers.setField(service, "dictionarySubscription", subscription)
        val connection = Connection()
        ReflectionHelpers.setField(service, "mInputConnection", connection)
        ReflectionHelpers.setField(service, "mStartedInputConnection", connection)
        service.onStartInput(EditorInfo().apply {
            inputType = InputType.TYPE_CLASS_TEXT
            initialSelStart = 0
            initialSelEnd = 0
        }, false)
        try {
            block(service, manager, repository, connection) {
                var prepared: PreparedDictionaryRestore? = null
                manager.prepareCompleteRestore(context, { bytes.inputStream() }) {
                    prepared = (it as CompleteBackupResult.Applied).value
                }
                manager.restoreComplete(checkNotNull(prepared)) { assertTrue(it is CompleteBackupResult.Applied) }
            }
        } finally {
            controller.destroy()
            customization.close()
            listOf(customizationPath, File(customizationPath.path + ".bak"),
                File(customizationPath.path + ".new")).forEach { it.delete() }
            manager.close()
            context.deleteDatabase(name)
            preferences.edit().also {
                if (hadStatus) it.putBoolean("show_status", oldStatus) else it.remove("show_status")
            }.commit()
        }
    }

    private fun session(service: SkkInputMethodService): EditorSession =
        ReflectionHelpers.getField(service, "session")

    private class Connection : BaseInputConnection(View(RuntimeEnvironment.getApplication()), true) {
        var textWrites = 0
        init { Selection.setSelection(editable, 0) }
        override fun setComposingText(text: CharSequence?, newCursorPosition: Int): Boolean {
            textWrites++
            return super.setComposingText(text, newCursorPosition)
        }
        override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
            textWrites++
            return super.commitText(text, newCursorPosition)
        }
    }
}
