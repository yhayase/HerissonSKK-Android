package se.haya.skk

import android.text.InputType
import android.text.Selection
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import java.io.File
import java.util.concurrent.Executor
import se.haya.skk.core.BasicSkkAction
import se.haya.skk.core.DictionaryQuery
import se.haya.skk.core.dictionary.*
import se.haya.skk.dictionary.BuiltinDictionary
import se.haya.skk.dictionary.DictionaryManager
import se.haya.skk.dictionary.SQLiteDictionaryRepository
import se.haya.skk.input.EditorSession
import se.haya.skk.settings.CustomizationStore
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

    @Test fun `確定と学習は改行 個人注釈と宣言した区分を保持する`() {
        val source = SkkDictionarySource("preservation-service", 0, listOf(SkkDictionaryEntry(
            "にほん", listOf(SkkDictionaryCandidate("独立確定一行目\n独立確定二行目", "独立確定注釈\r追記", annotationIsPersonal = true)),
            SkkDictionarySection.OKURI_ARI,
        )))
        withService(listOf(source)) { service, manager, name ->
            val session = start(service)
            session.handle(BasicSkkAction.Text("Nihon "))
            session.handle(BasicSkkAction.Enter)
            assertNull(session.notice)
            val connection: BaseInputConnection = ReflectionHelpers.getField(service, "mInputConnection")
            assertEquals("独立確定一行目\n独立確定二行目", connection.editable.toString())
            service.onFinishInput()
            manager.close()
            SQLiteDictionaryRepository(RuntimeEnvironment.getApplication(), name).use { repository ->
                val entry = SkkDictionaryCodec.parse(repository.exportPersonal()).entries.single()
                assertEquals(SkkDictionarySection.OKURI_ARI, entry.section)
                assertEquals("独立確定一行目\n独立確定二行目", entry.candidates.single().text)
                assertEquals("独立確定注釈\r追記", entry.candidates.single().annotation)
                assertTrue(entry.candidates.single().annotationIsPersonal)
            }
        }
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

    private fun manager(name: String, systems: List<SkkDictionarySource> = listOf(BuiltinDictionary.source)): DictionaryManager {
        val direct = Executor { it.run() }
        return DictionaryManager(SQLiteDictionaryRepository(RuntimeEnvironment.getApplication(), name),
            direct, direct, fallbackSystems = systems, deferReads = false).also { it.loadAsync() }
    }

    private fun withService(systems: List<SkkDictionarySource> = listOf(BuiltinDictionary.source), test: (SkkInputMethodService, DictionaryManager, String) -> Unit) {
        val context = RuntimeEnvironment.getApplication()
        val name = "learning-persistence-${System.nanoTime()}.db"
        val manager = manager(name, systems)
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
