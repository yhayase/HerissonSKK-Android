package se.haya.skk

import android.text.Selection
import android.text.InputType
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.widget.TextView
import java.io.File
import java.util.concurrent.Executor
import se.haya.skk.core.BasicSkkAction
import se.haya.skk.core.CandidateView
import se.haya.skk.core.DictionaryCandidate
import se.haya.skk.core.DynamicCompletionView
import se.haya.skk.core.LabeledCandidate
import se.haya.skk.core.RegistrationLevelView
import se.haya.skk.core.RegistrationView
import se.haya.skk.core.dictionary.SkkDictionaryCandidate
import se.haya.skk.core.dictionary.SkkDictionaryDocument
import se.haya.skk.core.dictionary.SkkDictionaryEncoding
import se.haya.skk.core.dictionary.SkkDictionaryEntry
import se.haya.skk.dictionary.DictionaryManager
import se.haya.skk.dictionary.SQLiteDictionaryRepository
import se.haya.skk.input.EditorSession
import se.haya.skk.settings.CustomizationStore
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
    @Test fun `物理表示はインライン中のタッチページを候補一覧に流用しない`() {
        val first = DictionaryCandidate("候補1")
        val touchPage = listOf(LabeledCandidate('a', first, first.text, 0))
        val inline = CandidateView(first, first.text, 0, 3, menu = emptyList(), pageItems = touchPage)
        assertTrue(candidateItemsForPresentation(inline, touch = false).isEmpty())
        assertEquals(touchPage, candidateItemsForPresentation(inline, touch = true))

        val third = DictionaryCandidate("候補3")
        val physicalPage = listOf(LabeledCandidate('a', third, third.text, 2))
        val menu = inline.copy(selected = third, committedText = third.text, index = 2, menu = physicalPage)
        assertEquals(physicalPage, candidateItemsForPresentation(menu, touch = false))
        assertTrue(candidateItemsForPresentation(menu, touch = false).none { it.index < 2 })
    }

    @Test fun `登録の物理インライン候補は本文カーソルへ一つの編集面として入る`() {
        val candidate = DictionaryCandidate("変換中")
        val touchPage = listOf(LabeledCandidate('a', candidate, candidate.text, 0))
        val inline = CandidateView(candidate, candidate.text, 0, 2, menu = emptyList(), pageItems = touchPage)
        val registration = RegistrationView(2, "内側", "本文", 1, "よみ", 2, inline,
            saving = false, hierarchy = listOf(
                RegistrationLevelView(1, "外側", false),
                RegistrationLevelView(2, "内側", true),
            ))

        val physical = RegistrationPresentation.from(registration, touch = false)
        assertEquals("本▼変換中文", physical.text)
        assertEquals(5, physical.cursor)
        assertEquals(1, physical.composingStart)
        assertEquals(5, physical.composingEnd)
        assertEquals("内側", physical.titleReading)
        assertEquals("外側", physical.parentReading)
        assertTrue(!physical.canEdit)
        assertTrue(!physical.canRegister)
        assertTrue(candidateItemsForPresentation(inline, touch = false).isEmpty())
    }

    @Test fun `登録のタッチインライン入力は読みだけを本文カーソルへ入れる`() {
        val candidate = DictionaryCandidate("変換候補")
        val registration = RegistrationView(1, "よみ", "本文", 1, "へんかん", 2,
            CandidateView(candidate, candidate.text, 0, 1), saving = false,
            hierarchy = listOf(RegistrationLevelView(1, "よみ", true)))

        val touch = RegistrationPresentation.from(registration, touch = true)
        assertEquals("本▼へんかん文", touch.text)
        assertEquals(4, touch.cursor)
        assertEquals(1, touch.composingStart)
        assertEquals(6, touch.composingEnd)
        assertTrue(!touch.canEdit)
        assertTrue(!touch.canRegister)
    }

    @Test fun `空の登録内読みは保存可能と表示しない`() {
        val registration = RegistrationView(1, "よみ", "本文", 1, "", 0, null,
            saving = false, hierarchy = listOf(RegistrationLevelView(1, "よみ", true)))

        val presentation = RegistrationPresentation.from(registration, touch = false)
        assertEquals("本▽文", presentation.text)
        assertEquals(2, presentation.cursor)
        assertEquals(1, presentation.composingStart)
        assertEquals(2, presentation.composingEnd)
        assertTrue(!presentation.canEdit)
        assertTrue(!presentation.canRegister)
    }

    @Test fun `物理登録の補完候補は本文と同じ編集面へカーソル後として入る`() {
        val registration = RegistrationView(1, "よみ", "本文", 1, "よみ", 2, null,
            saving = false, hierarchy = listOf(RegistrationLevelView(1, "よみ", true)))

        val physical = RegistrationPresentation.from(registration, touch = false,
            completion = DynamicCompletionView("よみ", "かん"))
        assertEquals("本▽よみかん文", physical.text)
        assertEquals(4, physical.cursor)
        assertEquals(4, physical.completionStart)
        assertEquals(6, physical.completionEnd)

        val touch = RegistrationPresentation.from(registration, touch = true,
            completion = DynamicCompletionView("よみ", "かん"))
        assertEquals("本▽よみ文", touch.text)
        assertEquals(null, touch.completionStart)
        assertEquals(null, touch.completionEnd)
    }

    @Test fun `サービス終了は物理ポップアップとモード消去タイマーを破棄する`() {
        val controller = Robolectric.buildService(SkkInputMethodService::class.java).create()
        val service = controller.get()
        val popup = PhysicalInputPopup(service) { }
        var timerFired = false
        ReflectionHelpers.setField(popup, "hideMode", Runnable { timerFired = true })
        popup.show(View(service), CandidateStatusPresentation(""), "あ", null, false)
        ReflectionHelpers.setField(service, "physicalPopup", popup)
        assertTrue(ReflectionHelpers.getField<View?>(popup, "currentParent") != null)
        controller.destroy()
        assertTrue(ReflectionHelpers.getField<PhysicalInputPopup?>(service, "physicalPopup") == null)
        assertTrue(ReflectionHelpers.getField<View?>(popup, "currentParent") == null)
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper())
            .idleFor(java.time.Duration.ofMillis(801))
        assertTrue(!timerFired)
    }

    @Test fun `表示方式だけの変更は同じセッションと登録スタックを維持する`() {
        val controller = Robolectric.buildService(SkkInputMethodService::class.java).create()
        val service = controller.get()
        val connection = Connection()
        val session = EditorSession(7, connection, false, false, 0, 0,
            registrationSaver = { _, _ -> })
        try {
            attach(service, connection)
            ReflectionHelpers.setField(service, "session", session)
            service.onCreateInputView()
            fun presentation(touch: Boolean) {
                ReflectionHelpers.callInstanceMethod<Unit>(service, "applyPresentationMode",
                    ReflectionHelpers.ClassParameter.from(Boolean::class.javaPrimitiveType, touch))
                assertTrue(ReflectionHelpers.getField<EditorSession>(service, "session") === session)
                assertTrue(session.active)
            }
            session.handle(BasicSkkAction.Text("Mitorokupamyu"))
            val reading = session.view.composing
            ReflectionHelpers.setField(service, "annotationConnection", connection)
            ReflectionHelpers.setField(service, "cursorMonitorGeneration", session.generation)
            presentation(true)
            assertTrue(ReflectionHelpers.getField<InputConnection?>(service, "annotationConnection") == null)
            assertTrue(ReflectionHelpers.getField<Long?>(service, "cursorMonitorGeneration") == null)
            presentation(false)
            assertEquals(reading, session.view.composing)
            session.handle(BasicSkkAction.Text(" Goropazu "))
            assertEquals(2, session.view.registration?.depth)
            val registration = session.view.registration
            presentation(true); presentation(false)
            assertEquals(registration, session.view.registration)
        } finally { controller.destroy() }
    }

    @Test fun `同じ入力状態の選択通知は押下中の候補タイルを交換しない`() {
        val controller = Robolectric.buildService(SkkInputMethodService::class.java).create()
        val service = controller.get()
        val root = service.onCreateInputView()
        val activity = Robolectric.buildActivity(android.app.Activity::class.java).setup()
        activity.get().setContentView(root)
        val connection = Connection()
        val session = EditorSession(7, connection, false, false, 0, 0)
        try {
            attach(service, connection)
            ReflectionHelpers.setField(service, "session", session)
            session.setTouchCandidatePresentation(true)
            session.handle(BasicSkkAction.Text("Tesuto "))
            ReflectionHelpers.callInstanceMethod<Unit>(service, "updateStatus")
            root.measure(View.MeasureSpec.makeMeasureSpec(320, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(640, View.MeasureSpec.AT_MOST))
            root.layout(0, 0, root.measuredWidth, root.measuredHeight)
            val tile = candidateTile(root, "候補1")
            assertTrue("実際に接続されたビューへ押下します", tile.isAttachedToWindow)
            val downTime = android.os.SystemClock.uptimeMillis()
            fun pointer(action: Int) {
                val event = android.view.MotionEvent.obtain(downTime, android.os.SystemClock.uptimeMillis(),
                    action, tile.width / 2f, tile.height / 2f, 0)
                try { assertTrue(tile.dispatchTouchEvent(event)) } finally { event.recycle() }
            }
            pointer(android.view.MotionEvent.ACTION_DOWN)
            val selection = session.expectedCursorSelection
            val composing = session.expectedCursorComposition!!
            service.onUpdateSelection(selection.first, selection.second, selection.first, selection.second,
                composing.first, composing.first + composing.second.length)
            assertTrue("無害な通知で押下先を交換しません", candidateTile(root, "候補1") === tile)
            pointer(android.view.MotionEvent.ACTION_UP)
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            assertEquals("候補1", connection.editable.toString())
            assertTrue(!session.hasComposition)
        } finally {
            activity.get().setContentView(View(activity.get()))
            controller.destroy()
            activity.pause().stop().destroy()
        }
    }

    @Test fun `ページ変更前の候補タイルは新しい候補を確定できない`() {
        val controller = Robolectric.buildService(SkkInputMethodService::class.java).create()
        val service = controller.get()
        val root = service.onCreateInputView()
        val connection = Connection()
        val session = EditorSession(7, connection, false, false, 0, 0,
            candidatePageCapacityProvider = { _, _ -> 1 })
        try {
            attach(service, connection)
            ReflectionHelpers.setField(service, "session", session)
            session.setTouchCandidatePresentation(true)
            session.handle(BasicSkkAction.Text("Tesuto "))
            ReflectionHelpers.callInstanceMethod<Unit>(service, "updateStatus")
            val old = candidateTile(root, "候補1")
            session.handleTouch(BasicSkkAction.NextCandidatePage)
            ReflectionHelpers.callInstanceMethod<Unit>(service, "updateStatus")
            old.performClick()
            assertTrue(session.hasComposition)
            assertEquals("てすと", connection.editable.toString())
            candidateTile(root, "候補2").performClick()
            assertEquals("候補2", connection.editable.toString())
            assertTrue(!session.hasComposition)
        } finally { controller.destroy() }
    }

    @Test fun `新しい候補描画はサービス世代変更後も選べ古いタイルは失効する`() {
        val controller = Robolectric.buildService(SkkInputMethodService::class.java).create()
        val service = controller.get()
        val root = service.onCreateInputView()
        val connection = Connection()
        val session = EditorSession(7, connection, false, false, 0, 0)
        try {
            attach(service, connection)
            ReflectionHelpers.setField(service, "session", session)
            session.handle(BasicSkkAction.Text("Tesuto   "))
            ReflectionHelpers.callInstanceMethod<Unit>(service, "updateStatus")
            val staleTile = candidateTile(root, "候補1")

            ReflectionHelpers.setField(service, "generation", 99L)
            ReflectionHelpers.callInstanceMethod<Unit>(service, "updateStatus")
            staleTile.performClick()
            assertEquals("候補3", connection.editable.toString())
            assertTrue(session.hasComposition)

            candidateTile(root, "候補1").performClick()
            assertEquals("候補1", connection.editable.toString())
            assertTrue(!session.hasComposition)
        } finally {
            controller.destroy()
        }
    }

    @Test fun `画面候補の表示と入力終了はカーソル座標への問い合わせに依存しない`() {
        val controller = Robolectric.buildService(SkkInputMethodService::class.java).create()
        val service = controller.get()
        val connection = Connection()
        val session = EditorSession(1, connection, false, true, 0, 0)
        val requests = ArrayDeque<Runnable>()
        ReflectionHelpers.setField(service, "annotationMonitor", CursorAnchorMonitor(
            Executor { requests.add(it) }, Executor { it.run() }))
        fun dispatchRequests() { while (requests.isNotEmpty()) requests.removeFirst().run() }
        try {
            attach(service, connection)
            ReflectionHelpers.setField(service, "session", session)
            session.handle(BasicSkkAction.Text("Tesuto "))
            ReflectionHelpers.callInstanceMethod<Unit>(service, "updateInlineAnnotation")
            assertTrue(connection.cursorRequests.isEmpty())
            dispatchRequests()
            assertTrue(connection.cursorRequests.isEmpty())
            session.handle(BasicSkkAction.Text(" "))
            ReflectionHelpers.callInstanceMethod<Unit>(service, "updateInlineAnnotation")
            dispatchRequests()
            assertTrue(connection.cursorRequests.isEmpty())
            session.handle(BasicSkkAction.Text(" "))
            ReflectionHelpers.callInstanceMethod<Unit>(service, "updateInlineAnnotation")
            dispatchRequests()
            assertTrue(connection.cursorRequests.isEmpty())
            session.handle(BasicSkkAction.Cancel)
            session.handle(BasicSkkAction.Text(" "))
            ReflectionHelpers.callInstanceMethod<Unit>(service, "updateInlineAnnotation")
            service.onFinishInput()
            dispatchRequests()
            assertTrue(connection.cursorRequests.isEmpty())

            val unsupported = Connection().apply { supportsCursor = false }
            val next = EditorSession(2, unsupported, false, true, 0, 0)
            attach(service, unsupported)
            ReflectionHelpers.setField(service, "session", next)
            next.handle(BasicSkkAction.Text("Tesuto "))
            ReflectionHelpers.callInstanceMethod<Unit>(service, "updateInlineAnnotation")
            dispatchRequests()
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
        val manager = DictionaryManager(repository, direct, direct, deferReads = false).also { it.loadAsync() }
        val customizationPath = File(context.cacheDir, "candidate-status-${System.nanoTime()}.json")
        val customization = CustomizationStore(customizationPath, direct, direct).also { it.loadAsync() }
        val controller = Robolectric.buildService(SkkInputMethodService::class.java).create()
        val service = controller.get()
        ReflectionHelpers.setField(service, "dictionaries", manager)
        ReflectionHelpers.setField(service, "customization", customization)
        val inputView = service.onCreateInputView()
        val surface = inputView.findViewById<CandidateStatusView>(R.id.candidate_status_container)
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
            assertEquals(View.GONE, surface.findViewById<View>(R.id.candidate_full_detail).visibility)
            descendants(surface).first { it.isLongClickable }.performLongClick()
            val detail = surface.findViewById<TextView>(R.id.candidate_detail_text)
            val firstPage = detail.text.toString()
            assertTrue(firstPage.isNotEmpty())

            preferences.edit().putBoolean("show_status", true).commit()

            val right = key(KeyEvent.KEYCODE_DPAD_RIGHT, 100)
            assertTrue(service.onKeyDown(right.keyCode, right))
            assertTrue(service.onKeyUp(right.keyCode, KeyEvent.changeAction(right, KeyEvent.ACTION_UP)))
            assertNotEquals(firstPage, detail.text.toString())
            assertEquals(2, session.view.candidate?.index)

            val enter = key(KeyEvent.KEYCODE_ENTER, 200)
            assertTrue(service.onKeyDown(enter.keyCode, enter))
            assertTrue(service.onKeyUp(enter.keyCode, KeyEvent.changeAction(enter, KeyEvent.ACTION_UP)))
            assertEquals(longCandidate + "2", connection.editable.toString())
            assertTrue(!surface.isDetailOpen)
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

    private fun candidateTile(root: View, prefix: String): View = descendants(root).first {
        it.isClickable && it.contentDescription?.toString()?.startsWith(prefix) == true
    }

    private fun descendants(view: View): List<View> = listOf(view) +
        if (view is ViewGroup) (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()

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
