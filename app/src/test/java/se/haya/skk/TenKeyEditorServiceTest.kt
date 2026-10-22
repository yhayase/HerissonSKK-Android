package se.haya.skk

import android.content.res.Configuration
import android.os.Binder
import android.text.InputType
import android.text.Selection
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.ExtractedText
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InputBinding
import android.widget.Button
import java.io.File
import java.util.concurrent.Executor
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import se.haya.skk.input.EditorSession
import se.haya.skk.core.BasicSkkAction
import se.haya.skk.input.TouchKeyboardView
import se.haya.skk.settings.CustomizationStore

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 35])
class TenKeyEditorServiceTest {
    @Test fun `生成済みと遅延生成の入力面でパスワードと数値欄の初期面を選ぶ`() {
        val types = listOf(
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD,
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD,
            InputType.TYPE_CLASS_NUMBER,
            InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD,
            InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_SIGNED,
            InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL,
            InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL or InputType.TYPE_NUMBER_FLAG_SIGNED,
        )
        for (type in types) for (createFirst in listOf(true, false)) withService { service ->
            var host = if (createFirst) service.onCreateInputView() else null
            start(service, type)
            if (host == null) host = service.onCreateInputView()
            val labels = buttons(checkNotNull(host)).map { it.text.toString() }
            if (type and InputType.TYPE_MASK_CLASS == InputType.TYPE_CLASS_NUMBER) {
                assertTrue(labels.containsAll(listOf("1", "0", "かな")))
                assertFalse(labels.contains("１"))
            } else {
                assertTrue(labels.containsAll(listOf("abc", "a/A", "123")))
                assertTrue(labels.contains("全Ａ"))
            }
        }
    }

    @Test fun `特殊欄の手動切替は再描画とビュー再生成で維持し通常欄の選択を復元する`() = withService { service ->
        var host = service.onCreateInputView()
        start(service, InputType.TYPE_CLASS_TEXT)
        key(host, "ABC").performClick()
        key(host, "a/A").performClick()
        assertNotNull(key(host, "DEF"))
        start(service, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        assertNotNull(key(host, "abc"))
        key(host, "123").performClick()
        assertNotNull(key(host, "1"))
        (descendants(host).filterIsInstance<TouchKeyboardView>().single()).configurationChanged()
        assertNotNull(key(host, "1"))
        host = service.onCreateInputView()
        assertNotNull(key(host, "1"))
        start(service, InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD)
        key(host, "かな").performClick()
        start(service, InputType.TYPE_CLASS_TEXT)
        assertNotNull(key(host, "DEF"))
        assertNotNull(key(host, "A/a"))
    }

    @Test fun `構成変更後の同一セッション再開は手動選択を初期化しない`() = withService { service ->
        val connection = Connection()
        val info = editorInfo(InputType.TYPE_CLASS_TEXT)
        attach(service, connection, info)
        ReflectionHelpers.setField(service, "mInputBinding", InputBinding(connection, Binder(), 1000, 2000))
        service.window.window!!.attributes = service.window.window!!.attributes.apply { token = Binder() }
        service.onStartInput(info, false)
        val host = service.onCreateInputView()
        key(host, "ABC").performClick()
        key(host, "123").performClick()
        val session = ReflectionHelpers.getField<EditorSession>(service, "session")
        session.handle(BasicSkkAction.Text("Ka"))
        assertNotNull(session.configurationCheckpoint())
        service.onConfigurationChanged(Configuration(service.resources.configuration))
        assertNotNull(ReflectionHelpers.getField<Any?>(service, "configurationRestart"))
        service.onStartInput(info, true)
        assertSame(session, ReflectionHelpers.getField<EditorSession>(service, "session"))
        assertNotNull(key(service.onCreateInputView(), "1"))
        val password = editorInfo(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        attach(service, connection, password)
        service.onStartInput(password, false)
        assertNotNull(key(service.onCreateInputView(), "abc"))
    }

    @Test fun `未変換文字のない保護欄と数値欄も構成変更で手動選択を維持する`() {
        val types = listOf(
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD,
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD,
            InputType.TYPE_CLASS_NUMBER,
            InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD,
        )
        for (type in types) withService { service ->
            val info = editorInfo(type)
            attach(service, Connection(), info)
            service.onStartInput(info, false)
            val host = service.onCreateInputView()
            val numeric = type and InputType.TYPE_MASK_CLASS == InputType.TYPE_CLASS_NUMBER
            key(host, if (numeric) "かな" else "123").performClick()
            val session = ReflectionHelpers.getField<EditorSession>(service, "session")
            assertNull(session.configurationCheckpoint())
            service.onConfigurationChanged(Configuration(service.resources.configuration))
            service.onStartInput(info, true)
            assertNotSame(session, ReflectionHelpers.getField<EditorSession>(service, "session"))
            assertNotNull(key(service.onCreateInputView(), if (numeric) "あ" else "1"))
            service.onStartInput(info, false)
            assertNotNull(key(service.onCreateInputView(), if (numeric) "1" else "abc"))
        }
    }

    @Test fun `再開時でも入力種別が変われば新しい欄の初期面を選ぶ`() = withService { service ->
        start(service, InputType.TYPE_CLASS_NUMBER)
        val host = service.onCreateInputView()
        key(host, "かな").performClick()
        val password = editorInfo(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        attach(service, Connection(), password)
        service.onStartInput(password, true)
        assertNotNull(key(service.onCreateInputView(), "abc"))
        val number = editorInfo(InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL)
        attach(service, Connection(), number)
        service.onStartInput(number, true)
        assertNotNull(key(service.onCreateInputView(), "1"))
        val ordinary = editorInfo(InputType.TYPE_CLASS_TEXT)
        attach(service, Connection(), ordinary)
        service.onStartInput(ordinary, true)
        assertNotNull(key(service.onCreateInputView(), "あ"))
    }

    private fun withService(block: (SkkInputMethodService) -> Unit) {
        val context = RuntimeEnvironment.getApplication()
        val preferences = context.getSharedPreferences("settings", 0)
        preferences.edit().clear().putBoolean("show_status", false)
            .putString("touch_keyboard_layout", "TEN_KEY").commit()
        val path = File(context.cacheDir, "ten-key-editor-${System.nanoTime()}.json")
        val direct = Executor { it.run() }
        val customization = CustomizationStore(path, direct, direct).also { it.loadAsync() }
        val controller = Robolectric.buildService(SkkInputMethodService::class.java).create()
        ReflectionHelpers.setField(controller.get(), "customization", customization)
        try { block(controller.get()) } finally {
            controller.destroy()
            customization.close()
            path.delete()
            preferences.edit().clear().commit()
        }
    }

    private fun start(service: SkkInputMethodService, type: Int) {
        val info = editorInfo(type)
        attach(service, Connection(), info)
        service.onStartInput(info, false)
        assertNotNull(ReflectionHelpers.getField<EditorSession?>(service, "session"))
    }
    private fun editorInfo(type: Int) = EditorInfo().apply {
        inputType = type
        packageName = "example.tenkey"
        initialSelStart = 0
        initialSelEnd = 0
    }
    private fun attach(service: SkkInputMethodService, connection: Connection, info: EditorInfo) {
        ReflectionHelpers.setField(service, "mInputConnection", connection)
        ReflectionHelpers.setField(service, "mStartedInputConnection", connection)
        ReflectionHelpers.setField(service, "mInputEditorInfo", info)
    }
    private fun descendants(view: View): List<View> = listOf(view) +
        if (view is ViewGroup) (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()
    private fun buttons(view: View) = descendants(view).filterIsInstance<Button>()
    private fun key(view: View, label: String) = buttons(view).first { it.text.toString() == label }
    private class Connection : BaseInputConnection(View(RuntimeEnvironment.getApplication()), true) {
        init { Selection.setSelection(editable, 0) }
        override fun getExtractedText(request: ExtractedTextRequest?, flags: Int): ExtractedText =
            ExtractedText().apply {
                text = editable.toString()
                startOffset = 0
                partialStartOffset = -1
                selectionStart = Selection.getSelectionStart(editable)
                selectionEnd = Selection.getSelectionEnd(editable)
            }
    }
}
