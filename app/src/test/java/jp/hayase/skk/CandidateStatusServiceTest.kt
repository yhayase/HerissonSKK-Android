package jp.hayase.skk

import android.text.Selection
import android.text.InputType
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.widget.TextView
import java.io.File
import java.util.concurrent.Executor
import jp.hayase.skk.core.BasicSkkAction
import jp.hayase.skk.core.dictionary.SkkDictionaryCandidate
import jp.hayase.skk.core.dictionary.SkkDictionaryDocument
import jp.hayase.skk.core.dictionary.SkkDictionaryEncoding
import jp.hayase.skk.core.dictionary.SkkDictionaryEntry
import jp.hayase.skk.dictionary.DictionaryManager
import jp.hayase.skk.dictionary.SQLiteDictionaryRepository
import jp.hayase.skk.input.EditorSession
import jp.hayase.skk.settings.CustomizationStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
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
class CandidateStatusServiceTest {
    @Test fun `注釈の座標監視は候補メニューと入力終了で停止し未対応では省略する`() {
        val controller = Robolectric.buildService(SkkInputMethodService::class.java).create()
        val service = controller.get()
        val connection = Connection()
        val session = EditorSession(1, connection, false, true, 0, 0)
        try {
            attach(service, connection)
            ReflectionHelpers.setField(service, "session", session)
            session.handle(BasicSkkAction.Text("Tesuto "))
            ReflectionHelpers.callInstanceMethod<Unit>(service, "updateInlineAnnotation")
            assertEquals(listOf(InputConnection.CURSOR_UPDATE_IMMEDIATE or
                InputConnection.CURSOR_UPDATE_MONITOR), connection.cursorRequests)
            session.handle(BasicSkkAction.Text("  "))
            ReflectionHelpers.callInstanceMethod<Unit>(service, "updateInlineAnnotation")
            assertEquals(0, connection.cursorRequests.last())
            session.handle(BasicSkkAction.Cancel)
            session.handle(BasicSkkAction.Text(" "))
            ReflectionHelpers.callInstanceMethod<Unit>(service, "updateInlineAnnotation")
            service.onFinishInput()
            assertEquals(0, connection.cursorRequests.last())

            val unsupported = Connection().apply { supportsCursor = false }
            val next = EditorSession(2, unsupported, false, true, 0, 0)
            attach(service, unsupported)
            ReflectionHelpers.setField(service, "session", next)
            next.handle(BasicSkkAction.Text("Tesuto "))
            ReflectionHelpers.callInstanceMethod<Unit>(service, "updateInlineAnnotation")
            assertEquals(null, ReflectionHelpers.getField<Any?>(service, "annotationTarget"))
            assertEquals("候補1", unsupported.editable.toString())
        } finally {
            controller.destroy()
        }
    }

    @Test fun `全文表示の左右は候補を変えずEnterは一度だけ候補を確定する`() {
        val context = RuntimeEnvironment.getApplication()
        val preferences = context.getSharedPreferences("settings", 0)
        val hadStatus = preferences.contains("show_status")
        val previousStatus = preferences.getBoolean("show_status", true)
        preferences.edit().putBoolean("show_status", false).commit()
        val longCandidate = "長い候補👩‍💻".repeat(1800)
        val longAnnotation = "長い注釈e\u0301".repeat(1800)
        val databaseName = "candidate-status-${System.nanoTime()}.db"
        val repository = SQLiteDictionaryRepository(context, databaseName)
        repository.replacePersonal(SkkDictionaryDocument(listOf(
            SkkDictionaryEntry("にほん", List(5) { SkkDictionaryCandidate(longCandidate + it, longAnnotation) }),
        ), SkkDictionaryEncoding.UTF8), 0)
        val direct = Executor { it.run() }
        val manager = DictionaryManager(repository, direct, direct).also { it.loadAsync() }
        val customizationPath = File(context.cacheDir, "candidate-status-${System.nanoTime()}.json")
        val customization = CustomizationStore(customizationPath, direct, direct).also { it.loadAsync() }
        val controller = Robolectric.buildService(SkkInputMethodService::class.java).create()
        val service = controller.get()
        ReflectionHelpers.setField(service, "dictionaries", manager)
        ReflectionHelpers.setField(service, "customization", customization)
        val surface = service.onCreateInputView() as CandidateStatusView
        val connection = Connection()
        try {
            attach(service, connection)
            service.onStartInput(EditorInfo().apply {
                inputType = InputType.TYPE_CLASS_TEXT
                initialSelStart = 0
                initialSelEnd = 0
            }, false)
            val session = ReflectionHelpers.getField<EditorSession>(service, "session")
            session.handle(BasicSkkAction.Text("Nihon "))
            ReflectionHelpers.callInstanceMethod<Unit>(service, "updateStatus")
            assertEquals(longCandidate + "0", session.view.candidate?.selected?.text)
            assertEquals(View.GONE, surface.findViewById<View>(R.id.candidate_full_detail).visibility)
            assertTrue(!surface.statusTextView.text.contains("長い候補"))
            assertTrue(!surface.statusTextView.text.contains("長い注釈"))
            session.handle(BasicSkkAction.Text("  "))
            ReflectionHelpers.callInstanceMethod<Unit>(service, "updateStatus")
            assertTrue(surface.statusTextView.text.length < 2_000)
            surface.findViewById<View>(R.id.candidate_full_detail).performClick()
            val detail = surface.findViewById<TextView>(R.id.candidate_detail_text)
            val firstPage = detail.text.toString()
            assertTrue(firstPage.isNotEmpty())

            // 実運用では候補選択に入る前の render で表示済みです。トークンを持たない
            // Robolectric の窓では、その遷移だけを再現して以後の重複 show を避けます。
            preferences.edit().putBoolean("show_status", true).commit()
            ReflectionHelpers.setField(service, "requestedVisible", true)

            val right = key(KeyEvent.KEYCODE_DPAD_RIGHT, 100)
            assertTrue(service.onKeyDown(right.keyCode, right))
            assertTrue(service.onKeyUp(right.keyCode, KeyEvent.changeAction(right, KeyEvent.ACTION_UP)))
            assertNotEquals(firstPage, detail.text.toString())
            assertEquals(2, session.view.candidate?.index)

            val enter = key(KeyEvent.KEYCODE_ENTER, 200)
            assertTrue(service.onKeyDown(enter.keyCode, enter))
            assertTrue(service.onKeyUp(enter.keyCode, KeyEvent.changeAction(enter, KeyEvent.ACTION_UP)))
            assertEquals(longCandidate + "2", connection.editable.toString())
            assertTrue(surface.statusTextView.text.isNotEmpty())
            service.onFinishInput()
            assertEquals("", surface.statusTextView.text.toString())
        } finally {
            controller.destroy()
            customization.close()
            listOf(customizationPath, File(customizationPath.path + ".bak"),
                File(customizationPath.path + ".new")).forEach { it.delete() }
            manager.close()
            context.deleteDatabase(databaseName)
            preferences.edit().also {
                if (hadStatus) it.putBoolean("show_status", previousStatus) else it.remove("show_status")
            }.commit()
        }
    }

    private fun key(code: Int, time: Long) = KeyEvent(time, time, KeyEvent.ACTION_DOWN, code, 0)

    private fun attach(service: SkkInputMethodService, connection: Connection) {
        ReflectionHelpers.setField(service, "mInputConnection", connection)
        ReflectionHelpers.setField(service, "mStartedInputConnection", connection)
    }

    private class Connection : BaseInputConnection(
        View(RuntimeEnvironment.getApplication()), true,
    ) {
        var supportsCursor = true
        val cursorRequests = mutableListOf<Int>()
        init { Selection.setSelection(editable, 0) }
        override fun requestCursorUpdates(cursorUpdateMode: Int): Boolean {
            cursorRequests += cursorUpdateMode
            return supportsCursor
        }
    }
}
