package jp.hayase.skk

import android.text.InputType
import android.text.Selection
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import java.io.File
import java.util.concurrent.Executor
import jp.hayase.skk.core.BasicSkkAction
import jp.hayase.skk.core.DictionaryQuery
import jp.hayase.skk.dictionary.BuiltinDictionary
import jp.hayase.skk.dictionary.DictionaryManager
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
class CandidateLearningPersistenceServiceTest {
    @Test fun `二本を確定すると同じ入力欄と辞書再起動後の入力欄で先頭になる`() = withService { service, manager, name ->
        val first = start(service)
        selectSecondAndCommit(first)
        first.handle(BasicSkkAction.Text("Nihon "))
        assertEquals("二本", first.view.candidate?.selected?.text)
        service.onFinishInput()
        manager.close()

        // DB を開き直し、メモリー内の辞書や入力セッションに依存せず順位が残ることを確認します。
        val reopened = manager(name)
        try {
            assertEquals(listOf("二本", "日本"), reopened.lookup(DictionaryQuery("にほん")).map { it.text })
            ReflectionHelpers.setField(service, "dictionaries", reopened)
            val restarted = start(service)
            restarted.handle(BasicSkkAction.Text("Nihon "))
            assertEquals("二本", restarted.view.candidate?.selected?.text)
            service.onFinishInput()
        } finally {
            reopened.close()
        }
    }

    @Test fun `入力先の学習禁止フラグがある場合は確定しても順位と永続辞書を変更しない`() = withService { service, manager, name ->
        val session = start(service, EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING)
        selectSecondAndCommit(session)
        session.handle(BasicSkkAction.Text("Nihon "))
        assertEquals("日本", session.view.candidate?.selected?.text)
        service.onFinishInput()
        manager.close()
        val reopened = manager(name)
        try {
            assertEquals("日本", reopened.lookup(DictionaryQuery("にほん")).first().text)
        } finally {
            reopened.close()
        }
    }

    @Test fun `個人データ保存を無効にした場合は順位を変更しない`() = withService { service, manager, _ ->
        manager.personalDataPolicy.setAllowed(false)
        val session = start(service)
        selectSecondAndCommit(session)
        session.handle(BasicSkkAction.Text("Nihon "))
        assertEquals("日本", session.view.candidate?.selected?.text)
        assertNull(session.notice)
        service.onFinishInput()
    }

    private fun selectSecondAndCommit(session: EditorSession) {
        session.handle(BasicSkkAction.Text("Nihon  "))
        assertEquals("二本", session.view.candidate?.selected?.text)
        session.handle(BasicSkkAction.Enter)
        assertNull(session.notice)
    }

    private fun start(service: SkkInputMethodService, options: Int = 0): EditorSession {
        val connection = object : BaseInputConnection(View(RuntimeEnvironment.getApplication()), true) {
            init { Selection.setSelection(editable, 0) }
        }
        ReflectionHelpers.setField(service, "mInputConnection", connection)
        ReflectionHelpers.setField(service, "mStartedInputConnection", connection)
        service.onStartInput(EditorInfo().apply {
            inputType = InputType.TYPE_CLASS_TEXT
            imeOptions = options
            initialSelStart = 0
            initialSelEnd = 0
        }, false)
        return ReflectionHelpers.getField(service, "session")
    }

    private fun manager(name: String): DictionaryManager {
        val direct = Executor { it.run() }
        return DictionaryManager(SQLiteDictionaryRepository(RuntimeEnvironment.getApplication(), name),
            direct, direct, fallbackSystems = listOf(BuiltinDictionary.source), deferReads = false).also { it.loadAsync() }
    }

    private fun withService(test: (SkkInputMethodService, DictionaryManager, String) -> Unit) {
        val context = RuntimeEnvironment.getApplication()
        val name = "learning-persistence-${System.nanoTime()}.db"
        val manager = manager(name)
        val path = File(context.cacheDir, "$name.json")
        val direct = Executor { it.run() }
        val custom = CustomizationStore(path, direct, direct).also { it.loadAsync() }
        val controller = Robolectric.buildService(SkkInputMethodService::class.java).create()
        val service = controller.get()
        ReflectionHelpers.setField(service, "dictionaries", manager)
        ReflectionHelpers.setField(service, "customization", custom)
        try {
            test(service, manager, name)
        } finally {
            controller.destroy()
            manager.close()
            custom.close()
            context.deleteDatabase(name)
            listOf(path, File(path.path + ".bak"), File(path.path + ".new")).forEach { it.delete() }
        }
    }
}
