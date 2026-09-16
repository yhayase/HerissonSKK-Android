package jp.hayase.skk

import android.graphics.Typeface
import android.text.Selection
import android.text.Spanned
import android.text.InputType
import android.text.style.StyleSpan
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.widget.TextView
import java.io.File
import java.util.concurrent.Executor
import jp.hayase.skk.core.BasicSkkAction
import jp.hayase.skk.dictionary.BuiltinDictionary
import jp.hayase.skk.dictionary.DictionaryManager
import jp.hayase.skk.dictionary.SQLiteDictionaryRepository
import jp.hayase.skk.core.dictionary.SkkDictionaryCodec
import jp.hayase.skk.input.EditorSession
import jp.hayase.skk.settings.CustomizationStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 35])
class CompletionServiceTest {
    @Test fun `動的補完設定は入力欄ごとに固定し保護欄では候補表示を隠す`() {
        val context = RuntimeEnvironment.getApplication()
        val preferences = context.getSharedPreferences("settings", 0)
        // Robolectric の IME ウィンドウにはトークンがないため、本文表示は TextView を直接更新します。
        preferences.edit().clear().putBoolean("show_status", false)
            .putBoolean("dynamic_completion", false).commit()
        val databaseName = "completion-service-${System.nanoTime()}.db"
        val repository = SQLiteDictionaryRepository(context, databaseName)
        repository.replacePersonal(SkkDictionaryCodec.parseText("にほん /日本/\n"), 0)
        val direct = Executor { it.run() }
        val manager = DictionaryManager(
            repository,
            direct,
            direct,
            fallbackSystems = listOf(BuiltinDictionary.source),
        ).also { it.loadAsync() }
        val customizationPath = File(context.cacheDir, "completion-customization-${System.nanoTime()}.json")
        val customization = CustomizationStore(customizationPath, direct, direct).also { it.loadAsync() }
        val controller = Robolectric.buildService(SkkInputMethodService::class.java).create()
        val service = controller.get()
        ReflectionHelpers.setField(service, "dictionaries", manager)
        ReflectionHelpers.setField(service, "customization", customization)
        val status = service.onCreateCandidatesView() as TextView

        try {
            attach(service, Connection())
            service.onStartInput(editorInfo(InputType.TYPE_CLASS_TEXT), false)
            preferences.edit().putBoolean("dynamic_completion", true).commit()
            val oldSession = session(service)
            oldSession.handle(BasicSkkAction.Text("Ni"))
            updateStatus(service)
            assertNull(oldSession.view.completion)
            assertFalse(status.text.contains("補完候補"))

            attach(service, Connection())
            service.onStartInput(editorInfo(InputType.TYPE_CLASS_TEXT), false)
            val newSession = session(service)
            newSession.handle(BasicSkkAction.Text("Ni"))
            updateStatus(service)
            assertEquals("ほん", newSession.view.completion?.suffix)
            assertTrue(status.text.contains("補完候補: に【ほん】"))
            assertTrue(status.text.contains("Right: 受諾 / Tab: 通常補完"))
            val styled = status.text as Spanned
            val suffixStart = styled.indexOf("ほん")
            assertTrue(suffixStart >= 0)
            assertTrue(styled.getSpans(suffixStart, suffixStart + 2, StyleSpan::class.java)
                .any { it.style == Typeface.BOLD_ITALIC })

            preferences.edit().putBoolean("show_status", true).commit()
            attach(service, Connection())
            service.onStartInput(editorInfo(
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD,
            ), false)
            assertTrue(session(service).protectedInput)
            assertEquals("", status.text.toString())
            assertFalse(service.onShowInputRequested(0, false))
        } finally {
            controller.destroy()
            customization.close()
            customizationPath.delete()
            File(customizationPath.path + ".bak").delete()
            File(customizationPath.path + ".new").delete()
            manager.close()
            context.deleteDatabase(databaseName)
            preferences.edit().clear().commit()
        }
    }

    private fun editorInfo(type: Int) = EditorInfo().apply {
        inputType = type
        initialSelStart = 0
        initialSelEnd = 0
    }

    private fun attach(service: SkkInputMethodService, connection: Connection) {
        ReflectionHelpers.setField(service, "mInputConnection", connection)
        ReflectionHelpers.setField(service, "mStartedInputConnection", connection)
    }

    private fun session(service: SkkInputMethodService): EditorSession {
        val value = ReflectionHelpers.getField<EditorSession?>(service, "session")
        assertNotNull(value)
        return checkNotNull(value)
    }

    private fun updateStatus(service: SkkInputMethodService) {
        ReflectionHelpers.callInstanceMethod<Unit>(service, "updateStatus")
    }

    private class Connection : BaseInputConnection(
        View(RuntimeEnvironment.getApplication()),
        true,
    ) {
        init {
            Selection.setSelection(editable, 0)
        }
    }
}
