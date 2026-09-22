package se.haya.skk.input

import android.content.Context
import android.text.Selection
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.ExtractedText
import android.view.inputmethod.ExtractedTextRequest
import se.haya.skk.core.BasicSkkAction
import se.haya.skk.core.InputMode
import se.haya.skk.core.InputPhase
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 35])
class EditorSessionTest {
    private class Connection : BaseInputConnection(View(RuntimeEnvironment.getApplication()), true) {
        var commits = 0
        var selections = 0
        var lastSelectionStart = -1
        var lastSelectionEnd = -1
        var rejectComposing = false
        var rejectCommit = false
        var rejectDelete = false
        var deletions = 0
        var afterMutation: (() -> Unit)? = null
        var composingCalls = 0
        val editorActions = mutableListOf<Int>()
        init { Selection.setSelection(editable, 0) }
        override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
            commits++
            return (!rejectCommit && super.commitText(text, newCursorPosition)).also { afterMutation?.invoke() }
        }
        override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
            deletions++
            return (!rejectDelete && super.deleteSurroundingText(beforeLength, afterLength)).also { afterMutation?.invoke() }
        }
        override fun setComposingText(text: CharSequence?, newCursorPosition: Int): Boolean {
            composingCalls++
            return !rejectComposing && super.setComposingText(text, newCursorPosition)
        }
        override fun setSelection(start: Int, end: Int): Boolean {
            selections++
            lastSelectionStart = start
            lastSelectionEnd = end
            return super.setSelection(start, end)
        }
        override fun performEditorAction(editorAction: Int): Boolean {
            editorActions += editorAction
            return true
        }
    }
    @Test fun `Cmは通常Enterと同じ確定設定と入力先アクションを使う`() {
        for (confirmOnly in listOf(true, false)) {
            for (emacsEnter in listOf(true, false)) {
                val connection = Connection()
                val current = EditorSession(1, connection, false, true, 0, 0,
                    emacsEnabled = true, touchEditorAction = EditorInfo.IME_ACTION_GO,
                    confirmOnlyEnter = confirmOnly)
                current.type("A")
                assertTrue(if (emacsEnter) current.handle(BasicSkkAction.Edit(
                    se.haya.skk.core.editing.EditCommand.NEWLINE)) else current.handleHardwareEnter())
                assertEquals("あ", connection.editable.toString())
                assertEquals(if (confirmOnly) emptyList<Int>() else listOf(EditorInfo.IME_ACTION_GO),
                    connection.editorActions)
                assertEquals(InputPhase.IDLE, current.engine.state.phase)
            }
        }
    }

    @Test fun `インラインBackspaceは候補を確定してから末尾一書記素を削除する`() {
        for (suffix in listOf("👩‍👩‍👧‍👦", "😀", "a\u0301")) {
            val connection = Connection()
            val dictionary = se.haya.skk.core.BasicSkkDictionary {
                listOf(se.haya.skk.core.DictionaryCandidate("字$suffix"))
            }
            val current = EditorSession(1, connection, false, true, 0, 0, dictionary = dictionary)
            current.type("A")
            current.handle(BasicSkkAction.ConvertNext)
            assertNotNull(current.view.candidate)
            connection.afterMutation = {
                val cursor = Selection.getSelectionStart(connection.editable)
                current.onSelection(cursor, cursor, -1, -1)
            }
            current.handle(BasicSkkAction.Backspace)
            assertEquals("字", connection.editable.toString())
            assertEquals(InputPhase.IDLE, current.engine.state.phase)
            assertEquals(1, connection.commits)
            assertEquals(1, connection.deletions)
        }
    }

    @Test fun `インラインBackspaceは確定失敗時に削除せず削除失敗を再送しない`() {
        for (rejectCommit in listOf(true, false)) {
            val connection = Connection()
            val dictionary = se.haya.skk.core.BasicSkkDictionary {
                listOf(se.haya.skk.core.DictionaryCandidate("候補"))
            }
            val current = EditorSession(1, connection, false, true, 0, 0, dictionary = dictionary)
            current.type("A")
            current.handle(BasicSkkAction.ConvertNext)
            connection.rejectCommit = rejectCommit
            connection.rejectDelete = !rejectCommit
            assertTrue(current.handle(BasicSkkAction.Backspace))
            assertTrue(current.failed)
            assertEquals(if (rejectCommit) 0 else 1, connection.deletions)
            assertEquals("候補", connection.editable.toString())
        }
    }

    private class HandoffConnection : BaseInputConnection(View(RuntimeEnvironment.getApplication()), true) {
        var windowStart = 0
        var partialStart = -1
        var acceptRegion = true
        var region: Pair<Int, Int>? = null
        var extractionAvailable = true
        var extractionCalls = 0
        init { Selection.setSelection(editable, 0) }
        override fun getExtractedText(request: ExtractedTextRequest?, flags: Int): ExtractedText? {
            extractionCalls++
            if (!extractionAvailable) return null
            val start = windowStart.coerceIn(0, editable!!.length)
            return ExtractedText().apply {
                text = editable!!.subSequence(start, editable!!.length)
                startOffset = start
                partialStartOffset = partialStart
                partialEndOffset = if (partialStart < 0) -1 else editable!!.length
                selectionStart = Selection.getSelectionStart(editable) - start
                selectionEnd = Selection.getSelectionEnd(editable) - start
            }
        }
        override fun setComposingRegion(start: Int, end: Int): Boolean {
            region = start to end
            return acceptRegion && super.setComposingRegion(start, end)
        }
    }
    private class QueueExecutor : java.util.concurrent.Executor {
        private val tasks = ArrayDeque<Runnable>()
        val size: Int get() = tasks.size
        override fun execute(command: Runnable) { tasks.addLast(command) }
        fun runAll() { while (tasks.isNotEmpty()) tasks.removeFirst().run() }
    }
    private fun session(connection: Connection, generation: Long = 1,
        editorAction: Int = EditorInfo.IME_ACTION_NONE, confirmOnlyEnter: Boolean = false) =
        EditorSession(generation, connection, false, true, 0, 0,
            touchEditorAction = editorAction, confirmOnlyEnter = confirmOnlyEnter)
    private fun EditorSession.type(text: String) {
        text.forEach { assertTrue(handle(BasicSkkAction.Text(it.toString()))) }
    }

    @Test fun conversionEnterCommitsOnceAndNextEnterPasses() {
        val connection = Connection()
        val session = session(connection)
        session.type("Nihon ")
        assertEquals("日本", connection.editable.toString())
        assertEquals(0, connection.commits)
        assertTrue(session.handle(BasicSkkAction.Enter))
        assertEquals("日本", connection.editable.toString())
        assertEquals(1, connection.commits)
        assertFalse(session.handle(BasicSkkAction.Enter))
        assertEquals(1, connection.commits)
        assertEquals(-1, BaseInputConnection.getComposingSpanStart(connection.editable!!))
    }

    @Test fun `物理Enterは通常候補を確定して入力欄の改行またはアクションも実行する`() {
        val multiline = Connection()
        val newlineSession = session(multiline)
        newlineSession.type("Nihon ")
        assertTrue(newlineSession.handleHardwareEnter())
        assertEquals("日本\n", multiline.editable.toString())

        val action = Connection()
        val actionSession = session(action, editorAction = EditorInfo.IME_ACTION_SEND)
        actionSession.type("Nihon ")
        assertTrue(actionSession.handleHardwareEnter())
        assertEquals("日本", action.editable.toString())
        assertEquals(listOf(EditorInfo.IME_ACTION_SEND), action.editorActions)
    }

    @Test fun `画面右下キーは通常候補を確定して入力欄のアクションを続けない`() {
        val connection = Connection()
        val current = session(connection, editorAction = EditorInfo.IME_ACTION_SEND)
        current.setTouchCandidatePresentation(true)
        current.type("Nihon ")
        assertEquals("にほん", connection.editable.toString())
        assertTrue(current.handleTouchPrimary())
        assertEquals("日本", connection.editable.toString())
        assertTrue(connection.editorActions.isEmpty())
        assertTrue(current.handleTouchPrimary())
        assertEquals(listOf(EditorInfo.IME_ACTION_SEND), connection.editorActions)
    }

    @Test fun `画面右下キーは待機中のnだけを確定し次の押下で入力欄アクションを実行する`() {
        val connection = Connection()
        val current = session(connection, editorAction = EditorInfo.IME_ACTION_SEND)
        current.setTouchCandidatePresentation(true)
        current.type("n")

        assertTrue(current.handleTouchPrimary())
        assertEquals("ん", connection.editable.toString())
        assertTrue(connection.editorActions.isEmpty())
        assertTrue(current.handleTouchPrimary())
        assertEquals(listOf(EditorInfo.IME_ACTION_SEND), connection.editorActions)
    }

    @Test fun `予測結果が未判定の読みは画面右下キーで推測確定しない`() {
        val connection = Connection()
        val unsupported = se.haya.skk.core.BasicSkkDictionary {
            emptyList<se.haya.skk.core.DictionaryCandidate>()
        }
        val current = EditorSession(1, connection, false, true, 0, 0, unsupported,
            touchEditorAction = EditorInfo.IME_ACTION_SEND)
        current.setTouchCandidatePresentation(true)
        current.type("Ka")

        assertNotNull(current.view.prediction?.failure)
        assertTrue(current.handleTouchPrimary())
        assertEquals("か", connection.editable.toString())
        assertEquals(InputPhase.READING, current.engine.state.phase)
        assertTrue(connection.editorActions.isEmpty())
    }

    @Test fun `画面Backspaceは通常候補を読みへ戻して次の押下で一文字削除する`() {
        val connection = Connection()
        val current = session(connection)
        current.setTouchCandidatePresentation(true)
        current.type("Nihon ")

        assertTrue(current.handleTouchBackspace())
        assertEquals("にほn", connection.editable.toString())
        assertEquals(InputPhase.READING, current.engine.state.phase)
        assertTrue(current.handleTouchBackspace())
        assertEquals("にほ", connection.editable.toString())
    }

    @Test fun `画面候補中の文字はページ先頭を確定してから候補ラベルにせず一度処理する`() {
        val connection = Connection()
        val dictionary = se.haya.skk.core.BasicSkkDictionary {
            (1..5).map { index -> se.haya.skk.core.DictionaryCandidate("候補$index") }
        }
        val current = EditorSession(1, connection, false, true, 0, 0, dictionary,
            candidatePageCapacityProvider = { _, _ -> 2 })
        current.setTouchCandidatePresentation(true)
        current.type("Ka ")
        assertTrue(current.handleTouchText(" "))
        assertEquals(2, current.view.candidate?.pageStart)

        assertTrue(current.handleTouchText("a"))
        assertEquals("候補3あ", connection.editable.toString())
        assertNull(current.view.candidate)
    }

    @Test fun `旧ページ幅だけを指定したセッションは選択開始時の幅を全ページで固定する`() {
        val connection = Connection()
        val dictionary = se.haya.skk.core.BasicSkkDictionary {
            (1..9).map { index -> se.haya.skk.core.DictionaryCandidate("候補$index") }
        }
        var width = 2
        val current = EditorSession(
            1, connection, false, true, 0, 0, dictionary,
            candidateDisplayConfig = se.haya.skk.core.CandidateDisplayConfig(
                labels = "1234567", fixedPageSize = 2, inlineCandidateCount = 3,
            ),
            candidatePageSizeProvider = { width },
        )
        current.type("Ka ")
        repeat(3) { current.handle(BasicSkkAction.Text(" ")) }
        assertEquals(listOf("候補4", "候補5"), current.view.candidate?.menu?.map { it.candidate.text })

        width = 7
        current.handle(BasicSkkAction.Text(" "))
        assertEquals(listOf("候補6", "候補7"), current.view.candidate?.menu?.map { it.candidate.text })

        current.handle(BasicSkkAction.Enter)
        current.type("Ka ")
        repeat(3) { current.handle(BasicSkkAction.Text(" ")) }
        assertEquals(6, current.view.candidate?.menu?.size)
    }

    @Test fun `画面候補中の小文字xは前ページで先頭ページでは読みへ戻る`() {
        val connection = Connection()
        val dictionary = se.haya.skk.core.BasicSkkDictionary {
            (1..5).map { index -> se.haya.skk.core.DictionaryCandidate("候補$index") }
        }
        val current = EditorSession(1, connection, false, true, 0, 0, dictionary,
            candidatePageCapacityProvider = { _, _ -> 2 })
        current.setTouchCandidatePresentation(true)
        current.type("Ka ")
        current.handleTouchText(" ")
        assertEquals(2, current.view.candidate?.pageStart)
        current.handleTouchText("x")
        assertEquals(0, current.view.candidate?.pageStart)
        current.handleTouchText("x")
        assertEquals(InputPhase.READING, current.engine.state.phase)
        assertEquals("か", connection.editable.toString())
    }

    @Test fun `可変ページ最終面のSpaceは現在の登録階層で直ちに単語登録へ進む`() {
        val connection = Connection()
        val dictionary = se.haya.skk.core.BasicSkkDictionary {
            (1..3).map { index -> se.haya.skk.core.DictionaryCandidate("候補$index") }
        }
        val current = EditorSession(
            1, connection, false, true, 0, 0, dictionary,
            registrationSaver = { _, _ -> },
            candidatePageCapacityProvider = { _, start -> if (start == 0) 1 else 2 },
        )
        current.setTouchCandidatePresentation(true)
        current.type("Ka ")
        current.handleTouchText(" ")
        assertEquals(1, current.view.candidate?.pageStart)
        assertEquals(false, current.view.candidate?.canNextPage)

        // 三角は最終面で無操作のまま、Spaceだけが登録を開始します。
        current.handleTouch(BasicSkkAction.NextCandidatePage)
        assertEquals(1, current.view.candidate?.pageStart)
        assertEquals(0, current.engine.state.registrationDepth)
        current.handleTouchText(" ")
        assertEquals(1, current.engine.state.registrationDepth)
        assertEquals("か", connection.editable.toString())

        current.type("Ko ")
        current.handleTouchText(" ")
        assertEquals(1, current.view.registration?.innerCandidate?.pageStart)
        current.handleTouch(BasicSkkAction.NextCandidatePage)
        assertEquals(1, current.view.registration?.innerCandidate?.pageStart)
        assertEquals(1, current.engine.state.registrationDepth)
        current.handleTouchText(" ")
        assertEquals(2, current.engine.state.registrationDepth)

        // 子登録の取消は、開始前の最終ページをそのまま復元します。
        current.cancelTouchRegistration()
        assertEquals(1, current.engine.state.registrationDepth)
        assertEquals(1, current.view.registration?.innerCandidate?.pageStart)
    }

    @Test fun `物理表示と画面表示の切替は候補ページと読みを同じセッションに保持する`() {
        val connection = Connection()
        val dictionary = se.haya.skk.core.BasicSkkDictionary {
            (1..5).map { index -> se.haya.skk.core.DictionaryCandidate("候補$index") }
        }
        val current = EditorSession(1, connection, false, true, 0, 0, dictionary,
            candidatePageCapacityProvider = { _, _ -> 2 })
        current.setTouchCandidatePresentation(true)
        current.type("Ka ")
        current.handleTouchText(" ")
        assertEquals(2, current.view.candidate?.pageStart)
        assertEquals("か", current.displayedComposition)

        current.setTouchCandidatePresentation(false)
        assertEquals("候補3", current.displayedComposition)
        current.setTouchCandidatePresentation(true)
        assertEquals(2, current.view.candidate?.pageStart)
        assertEquals("か", current.displayedComposition)
    }

    @Test fun `物理表示と画面表示の切替は再帰登録の階層と内側の読みを保持する`() {
        val connection = Connection()
        val current = EditorSession(
            1, connection, false, true, 0, 0,
            se.haya.skk.core.BasicSkkDictionary { emptyList() },
            registrationSaver = { _, _ -> },
        )
        current.type("Michi Ko Ka")
        assertEquals(2, current.view.registration?.depth)
        assertEquals("か", current.view.registration?.innerComposing)

        current.setTouchCandidatePresentation(true)
        current.setTouchCandidatePresentation(false)
        current.setTouchCandidatePresentation(true)

        assertEquals(2, current.view.registration?.depth)
        assertEquals("か", current.view.registration?.innerComposing)
        assertEquals("みち", connection.editable.toString())
    }

    @Test fun `構成変更後の新接続は所有範囲の本文と選択が完全一致する場合だけ再接続する`() {
        val old = HandoffConnection().apply {
            editable!!.append("prefix")
            Selection.setSelection(editable, editable!!.length)
            windowStart = 3
        }
        val current = EditorSession(1, old, false, true, 6, 6)
        current.setTouchCandidatePresentation(true)
        current.type("Ka")
        old.extractionAvailable = false
        val checkpoint = current.configurationCheckpoint()!!
        assertTrue(current.matchesConfigurationSpanLoss(checkpoint, 7, 7, -1, -1))

        val replacement = HandoffConnection().apply {
            editable!!.append(old.editable!!)
            Selection.setSelection(editable, 7)
            windowStart = 3
        }
        assertTrue(current.rebindAfterConfiguration(checkpoint, replacement))
        assertEquals(6 to 7, replacement.region)
        assertEquals("prefixか", replacement.editable.toString())
        assertEquals(InputPhase.READING, current.engine.state.phase)
        assertEquals("か", current.displayedComposition)
        assertNotNull(current.configurationCheckpoint())
        assertFalse(current.matchesConfigurationSpanLoss(checkpoint, 7, 7, -1, -1))
    }

    @Test fun `構成変更用の検証済み状態は古い接続が無効になっても使え新しい取得失敗で破棄する`() {
        val connection = HandoffConnection()
        val current = EditorSession(1, connection, false, true, 0, 0)
        current.type("Ka")
        current.onSelection(1, 1, 0, 1)
        connection.extractionAvailable = false
        assertNotNull(current.configurationCheckpoint())

        current.type("n")
        assertNull(current.configurationCheckpoint())
    }

    @Test fun `保護入力では構成変更用の本文を取得しない`() {
        val connection = HandoffConnection()
        val current = EditorSession(1, connection, true, false, 0, 0)

        assertFalse(current.handle(BasicSkkAction.StartReading))
        assertTrue(current.handleTouch(BasicSkkAction.Text("か", interpretCommands = false)))
        assertNull(current.configurationCheckpoint())
        assertEquals(0, connection.extractionCalls)
    }

    @Test fun `構成変更の本文差分と部分取得と範囲設定拒否は再接続しない`() {
        fun fixture(): Triple<EditorSession, HandoffConnection, EditorSession.ConfigurationCheckpoint> {
            val old = HandoffConnection().apply {
                editable!!.append("prefix")
                Selection.setSelection(editable, editable!!.length)
            }
            val current = EditorSession(1, old, false, true, 6, 6)
            current.type("Ka")
            return Triple(current, old, current.configurationCheckpoint()!!)
        }

        fixture().let { (current, old, checkpoint) ->
            val changed = HandoffConnection().apply {
                editable!!.append(old.editable!!.toString().replace('か', 'き'))
                Selection.setSelection(editable, 7)
            }
            assertFalse(current.rebindAfterConfiguration(checkpoint, changed))
            assertNull(changed.region)
        }
        fixture().let { (current, old, checkpoint) ->
            val changedOutsideOwnedRange = HandoffConnection().apply {
                editable!!.append(old.editable!!.toString().replaceFirst('p', 'P'))
                Selection.setSelection(editable, 7)
            }
            assertFalse(current.rebindAfterConfiguration(checkpoint, changedOutsideOwnedRange))
            assertNull(changedOutsideOwnedRange.region)
        }
        fixture().let { (current, old, checkpoint) ->
            val partial = HandoffConnection().apply {
                editable!!.append(old.editable!!)
                Selection.setSelection(editable, 7)
                partialStart = 0
            }
            assertFalse(current.rebindAfterConfiguration(checkpoint, partial))
            assertNull(partial.region)
        }
        fixture().let { (current, old, checkpoint) ->
            val rejected = HandoffConnection().apply {
                editable!!.append(old.editable!!)
                Selection.setSelection(editable, 7)
                acceptRegion = false
            }
            assertFalse(current.rebindAfterConfiguration(checkpoint, rejected))
            assertEquals(6 to 7, rejected.region)
        }
    }

    @Test fun `画面モード選択はページ先頭の確定成功後だけ切り替える`() {
        fun selecting(connection: Connection): EditorSession {
            val dictionary = se.haya.skk.core.BasicSkkDictionary {
                (1..4).map { index -> se.haya.skk.core.DictionaryCandidate("候補$index") }
            }
            return EditorSession(1, connection, false, true, 0, 0, dictionary,
                candidatePageCapacityProvider = { _, _ -> 2 }).also {
                it.setTouchCandidatePresentation(true)
                it.type("Ka ")
                it.handleTouchText(" ")
            }
        }

        val accepted = Connection()
        val acceptedSession = selecting(accepted)
        assertTrue(acceptedSession.handleTouchSetInputMode(InputMode.DIRECT))
        assertEquals("候補3", accepted.editable.toString())
        assertEquals(InputMode.DIRECT, acceptedSession.engine.state.mode)

        val rejected = Connection().apply { rejectCommit = true }
        val rejectedSession = selecting(rejected)
        assertFalse(rejectedSession.handleTouchSetInputMode(InputMode.DIRECT))
        assertEquals(InputMode.HIRAGANA, rejectedSession.engine.state.mode)
        assertTrue(rejectedSession.failed)
    }

    @Test fun `候補確定を入力先が拒否した場合は後続の画面文字を処理しない`() {
        val connection = Connection().apply { rejectCommit = true }
        val dictionary = se.haya.skk.core.BasicSkkDictionary {
            listOf(se.haya.skk.core.DictionaryCandidate("候補"))
        }
        val current = EditorSession(1, connection, false, true, 0, 0, dictionary)
        current.setTouchCandidatePresentation(true)
        current.type("Ka ")

        assertFalse(current.handleTouchText("a"))
        assertTrue(current.failed)
        assertFalse(connection.editable.toString().contains("あ"))
    }

    @Test fun `画面予測の右下キーは先頭を確定して入力欄アクションを続けない`() {
        val connection = Connection()
        val target = se.haya.skk.core.PredictionHistoryTarget("か", "蚊", null, "蚊")
        val dictionary = object : se.haya.skk.core.BasicSkkDictionary {
            override fun lookup(query: se.haya.skk.core.DictionaryQuery) =
                emptyList<se.haya.skk.core.DictionaryCandidate>()
            override fun predict(query: se.haya.skk.core.PredictionQuery) =
                se.haya.skk.core.PredictionSearchResult.Ready(listOf(
                    se.haya.skk.core.PredictionCandidate(
                        se.haya.skk.core.DictionaryCandidate("蚊"), "蚊", target,
                    ),
                ), false)
        }
        val current = EditorSession(1, connection, false, true, 0, 0, dictionary,
            touchEditorAction = EditorInfo.IME_ACTION_SEND,
            dictionaryExecutor = java.util.concurrent.Executor { it.run() })
        current.setTouchCandidatePresentation(true)
        current.type("Ka")
        assertEquals("か", connection.editable.toString())
        assertEquals("蚊", current.view.prediction?.items?.single()?.committedText)

        assertTrue(current.handleTouchPrimary())
        assertEquals("蚊", connection.editable.toString())
        assertTrue(connection.editorActions.isEmpty())
    }

    @Test fun `予測の非同期読込中も読みを直ちに表示し完了時は本文を再入力しない`() {
        val connection = Connection()
        val background = QueueExecutor()
        val callbacks = QueueExecutor()
        val target = se.haya.skk.core.PredictionHistoryTarget("か", "蚊", null, "蚊")
        val dictionary = object : se.haya.skk.core.BasicSkkDictionary {
            override fun lookup(query: se.haya.skk.core.DictionaryQuery) =
                emptyList<se.haya.skk.core.DictionaryCandidate>()
            override fun predict(query: se.haya.skk.core.PredictionQuery) =
                se.haya.skk.core.PredictionSearchResult.Ready(listOf(
                    se.haya.skk.core.PredictionCandidate(
                        se.haya.skk.core.DictionaryCandidate("蚊"), "蚊", target,
                    ),
                ), false)
        }
        val current = EditorSession(1, connection, false, true, 0, 0, dictionary,
            callbackExecutor = callbacks, dictionaryExecutor = background)
        current.setTouchCandidatePresentation(true)
        current.type("Ka")

        assertEquals("か", connection.editable.toString())
        assertEquals(se.haya.skk.core.PredictionSearchFailure.PENDING,
            current.view.prediction?.failure)
        assertEquals(1, background.size)
        val composingCalls = connection.composingCalls

        background.runAll()
        callbacks.runAll()
        assertEquals("か", connection.editable.toString())
        assertEquals(composingCalls, connection.composingCalls)
        assertEquals("蚊", current.view.prediction?.items?.single()?.committedText)
    }

    @Test fun `画面モード選択は予測候補でなく入力中の読みを確定する`() {
        val connection = Connection()
        val target = se.haya.skk.core.PredictionHistoryTarget("か", "蚊", null, "蚊")
        val dictionary = object : se.haya.skk.core.BasicSkkDictionary {
            override fun lookup(query: se.haya.skk.core.DictionaryQuery) =
                emptyList<se.haya.skk.core.DictionaryCandidate>()
            override fun predict(query: se.haya.skk.core.PredictionQuery) =
                se.haya.skk.core.PredictionSearchResult.Ready(listOf(
                    se.haya.skk.core.PredictionCandidate(
                        se.haya.skk.core.DictionaryCandidate("蚊"), "蚊", target,
                    ),
                ), false)
        }
        val current = EditorSession(1, connection, false, true, 0, 0, dictionary,
            dictionaryExecutor = java.util.concurrent.Executor { it.run() })
        current.setTouchCandidatePresentation(true)
        current.type("Ka")

        assertTrue(current.handleTouchSetInputMode(InputMode.DIRECT))
        assertEquals("か", connection.editable.toString())
        assertEquals(InputMode.DIRECT, current.engine.state.mode)
    }

    @Test fun `確定のみ設定とCjは候補確定を入力欄へ続けない`() {
        val configured = Connection()
        val configuredSession = session(configured, confirmOnlyEnter = true)
        configuredSession.type("Nihon ")
        assertTrue(configuredSession.handleHardwareEnter())
        assertEquals("日本", configured.editable.toString())

        val ctrlJ = Connection()
        val ctrlJSession = session(ctrlJ)
        ctrlJSession.type("Nihon ")
        assertTrue(ctrlJSession.handle(BasicSkkAction.Kana))
        assertEquals("日本", ctrlJ.editable.toString())
    }

    @Test fun `物理Enterは通常読みと残余ローマ字も確定して改行する`() {
        for ((typed, expected) in listOf("Nihon" to "にほん\n", "n" to "ん\n")) {
            val connection = Connection()
            val current = session(connection)
            current.type(typed)
            assertTrue(current.handleHardwareEnter())
            assertEquals(expected, connection.editable.toString())
        }
    }

    @Test fun ownSelectionNotificationsDoNotCancelComposition() {
        val connection = Connection()
        val session = session(connection)
        session.type("Ni")
        session.onSelection(1, 1, 0, 1)
        assertEquals("に", session.displayedComposition)
        session.type("hon ")
        session.onSelection(2, 2, 0, 2)
        assertEquals("日本", session.displayedComposition)
    }

    @Test fun delayedOlderSelectionNotificationDoesNotRewindOptimisticCompositionPosition() {
        val connection = Connection()
        val session = session(connection)
        session.type("Nihon ")
        assertTrue(session.handle(BasicSkkAction.Enter))
        session.type("Nihon")
        assertEquals("日本にほn", connection.editable.toString())

        session.onSelection(1, 1, 0, 1)

        assertTrue(session.handle(BasicSkkAction.Left))
        assertEquals(4, connection.lastSelectionStart)
        assertEquals(4, connection.lastSelectionEnd)
    }

    @Test fun cursorMovePreservesExistingTextAndDoesNotReplaceItLater() {
        val connection = Connection()
        val session = session(connection)
        session.type("Nihon ")
        Selection.setSelection(connection.editable, 0)
        session.onSelection(0, 0, 0, 2)
        assertFalse(session.hasComposition)
        assertEquals("日本", connection.editable.toString())
        session.type("a")
        assertEquals("あ日本", connection.editable.toString())
    }

    @Test fun oldSessionCannotWriteToEitherEditorAfterSwitch() {
        val first = Connection()
        val old = session(first)
        old.type("Ni")
        old.close()
        val second = Connection()
        val current = session(second, 2)
        assertFalse(old.acceptsResult(1, "に"))
        assertFalse(current.acceptsResult(1, ""))
        assertFalse(old.handle(BasicSkkAction.Enter))
        current.type("a")
        assertEquals("に", first.editable.toString())
        assertEquals("あ", second.editable.toString())
    }

    @Test fun cancelCandidateRestoresReadingThenRemovesOnlyComposition() {
        val connection = Connection()
        val session = session(connection)
        session.type("aNihon ")
        session.handle(BasicSkkAction.Cancel)
        assertEquals("あにほn", connection.editable.toString())
        session.handle(BasicSkkAction.Cancel)
        assertEquals("あ", connection.editable.toString())
    }

    @Test fun rejectedEditDoesNotResendKeyOrContinueWithStaleState() {
        val connection = Connection().apply { rejectComposing = true }
        val session = session(connection)
        assertTrue(session.handle(BasicSkkAction.Text("N")))
        assertTrue(session.failed)
        assertFalse(session.handle(BasicSkkAction.Text("i")))
        assertEquals("", connection.editable.toString())
    }

    @Test fun protectedEditorDoesNotReceiveAnyImeMutation() {
        val connection = Connection()
        val session = EditorSession(1, connection, true, false, 0, 0)
        assertFalse(session.handle(BasicSkkAction.Kana))
        assertFalse(session.handle(BasicSkkAction.Text("a")))
        assertEquals(0, connection.commits)
        assertEquals("", connection.editable.toString())
    }

    @Test fun protectedTouchUsesCapturedConnectionAndFrozenEditorActionWithoutSkk() {
        val captured = Connection()
        val session = EditorSession(1, captured, true, false, 0, 0,
            touchEditorAction = EditorInfo.IME_ACTION_SEND, touchEditorMultiline = false)

        assertTrue(session.handleTouch(BasicSkkAction.Text("secret")))
        assertTrue(session.handleTouch(BasicSkkAction.Backspace))
        assertTrue(session.handleTouch(BasicSkkAction.Enter))

        assertEquals("secre", captured.editable.toString())
        assertEquals(listOf(EditorInfo.IME_ACTION_SEND), captured.editorActions)
        assertFalse(session.hasComposition)
    }

    /** N03・N04: 確定拒否後は同じキーを再配送せず、接続が回復しても旧セッションを再利用しません。 */
    @Test fun rejectedCommitPreservesDisplayedTextAndStopsSession() {
        val connection = Connection()
        val session = session(connection)
        session.type("Nihon ")
        connection.rejectCommit = true
        assertTrue(session.handle(BasicSkkAction.Enter))
        assertTrue(session.failed)
        assertEquals(1, connection.commits)
        assertEquals("日本", connection.editable.toString())
        assertEquals(-1, BaseInputConnection.getComposingSpanStart(connection.editable!!))
        assertFalse(session.acceptsResult(1, ""))
        connection.rejectCommit = false
        assertFalse(session.handle(BasicSkkAction.Enter))
        assertFalse(session.handle(BasicSkkAction.Text("a")))
        assertEquals(1, connection.commits)
        assertEquals("日本", connection.editable.toString())
    }

    @Test fun `候補確定を拒否された物理と画面のEnterは入力欄アクションを実行しない`() {
        for (touch in listOf(false, true)) {
            val connection = Connection()
            val session = session(connection, editorAction = EditorInfo.IME_ACTION_SEND)
            session.type("Nihon ")
            connection.rejectCommit = true

            assertTrue(if (touch) session.handleTouch(BasicSkkAction.Enter)
                else session.handleHardwareEnter())

            assertTrue(session.failed)
            assertTrue(connection.editorActions.isEmpty())
            assertEquals(1, connection.commits)
        }
    }

    /** I10: 通常のモード切替・変換・取消規則より、保護入力の迂回を優先します。 */
    @Test fun protectedSessionPassesAllSkkActionsWithoutChangingExistingText() {
        val connection = Connection()
        connection.editable!!.append("existing")
        Selection.setSelection(connection.editable, 8)
        val session = EditorSession(1, connection, true, false, 8, 8)
        val actions = listOf(BasicSkkAction.Kana, BasicSkkAction.Text("N"), BasicSkkAction.Text(" "),
            BasicSkkAction.Enter, BasicSkkAction.Cancel, BasicSkkAction.Backspace)
        actions.forEach { assertFalse(session.handle(it)) }
        assertEquals("existing", connection.editable.toString())
        assertEquals(0, connection.commits)
        assertFalse(session.hasComposition)
    }

    /** I10・K19 の変換側: 学習禁止だけでは通常欄の変換を禁止しません。保存抑止は辞書導入時に別途検証します。 */
    @Test fun noLearningSessionStillAllowsConversion() {
        val connection = Connection()
        val session = EditorSession(1, connection, false, false, 0, 0)
        assertFalse(session.learningAllowed)
        session.type("Nihon ")
        assertEquals("日本", connection.editable.toString())
        assertTrue(session.handle(BasicSkkAction.Enter))
        assertEquals(1, connection.commits)
        assertEquals("日本", connection.editable.toString())
    }

    @Test fun backspacePendingRomanDoesNotDeleteCommittedPrefix() {
        val connection = Connection()
        val session = session(connection)
        session.type("ak")
        assertTrue(session.handle(BasicSkkAction.Backspace))
        assertEquals("あ", connection.editable.toString())
        assertFalse(session.handle(BasicSkkAction.Backspace))
    }

    @Test fun ordinaryTypingAfterCandidateDoesNotDuplicateCandidate() {
        val connection = Connection()
        val session = session(connection)
        session.type("Nihon a")
        assertEquals("日本あ", connection.editable.toString())
        assertFalse(session.hasComposition)
    }

    @Test fun unknownInitialSelectionStillAllowsCancelBeforeNotification() {
        val connection = Connection()
        val session = EditorSession(1, connection, false, true, -1, -1)
        session.type("Ni")
        assertTrue(session.handle(BasicSkkAction.Cancel))
        assertEquals("", connection.editable.toString())
    }

    @Test fun unknownInitialSelectionCanBeEstablishedByFirstCompositionNotification() {
        val connection = Connection()
        val session = EditorSession(1, connection, false, true, -1, -1)
        session.type("Ni")
        session.onSelection(1, 1, 0, 1)
        assertEquals("に", session.displayedComposition)
        session.type("hon ")
        assertEquals("日本", connection.editable.toString())
    }

    @Test fun unknownInitialSelectionDoesNotGuessInternalCursorPosition() {
        val connection = Connection()
        val session = EditorSession(1, connection, false, true, -1, -1)
        session.type("Nihon")
        assertTrue(session.handle(BasicSkkAction.Left))
        assertEquals(0, connection.selections)
        session.onSelection(3, 3, 0, 3)
        assertTrue(session.handle(BasicSkkAction.Left))
        assertEquals(1, connection.selections)
        assertEquals("にほん", connection.editable.toString())
    }

    @Test fun modesTerminalAndHalfwidthCommitWithoutEditorAction() {
        val connection = Connection()
        val session = session(connection)
        session.type("q")
        session.type("n")
        assertTrue(session.handle(BasicSkkAction.Enter))
        assertEquals("ン", connection.editable.toString())
        assertEquals(InputMode.KATAKANA, session.engine.state.mode)
        assertTrue(session.handle(BasicSkkAction.Halfwidth))
        session.type("n")
        assertTrue(session.handle(BasicSkkAction.Kana))
        assertEquals("ンﾝ", connection.editable.toString())
        assertEquals(InputMode.HIRAGANA, session.engine.state.mode)
    }

    @Test fun okuriAbbrevAndSuffixUseOnlyLimitedInMemoryDictionary() {
        val connection = Connection()
        val session = session(connection)
        session.type("KaKu")
        assertEquals("書く", connection.editable.toString())
        assertTrue(session.handle(BasicSkkAction.Enter))
        session.type("/API ")
        assertEquals("書くエーピーアイ", connection.editable.toString())
        assertTrue(session.handle(BasicSkkAction.Enter))
        session.type("Dai>")
        assertEquals("書くエーピーアイ第", connection.editable.toString())
        session.type(">kai ")
        assertEquals("書くエーピーアイ第回", connection.editable.toString())
    }

    @Test fun candidateMenuShowsLabelsAndAnnotationsButCommitsOnlyText() {
        val connection = Connection()
        val session = session(connection)
        session.type("Tesuto   ")
        val candidate = requireNotNull(session.view.candidate)
        assertEquals("候補3", candidate.selected.text)
        assertEquals("注釈3", candidate.selected.annotation)
        assertEquals('a', candidate.menu.first().label)
        assertEquals("候補3", candidate.menu.first().candidate.text)
        assertEquals("注釈3", candidate.menu.first().candidate.annotation)
        session.type("a")
        assertEquals("候補3", connection.editable.toString())
        assertFalse(connection.editable.toString().contains("注釈"))
    }

    @Test fun internalCursorEditDoesNotTouchCommittedPrefixAndQConvertsWholeReading() {
        val connection = Connection()
        val session = session(connection)
        session.type("aNihon ")
        assertTrue(session.handle(BasicSkkAction.Cancel))
        assertTrue(session.handle(BasicSkkAction.Left))
        assertTrue(session.handle(BasicSkkAction.Left))
        session.type("a")
        assertTrue(session.handle(BasicSkkAction.Delete))
        session.type("q")
        assertEquals("あニアン", connection.editable.toString())
    }
}
