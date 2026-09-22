package se.haya.skk.input

import android.text.Selection
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.BaseInputConnection
import java.util.concurrent.Executor
import se.haya.skk.core.*
import se.haya.skk.core.dictionary.DeferredDictionaryReadException
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 35])
class DeferredDictionarySessionTest {
    private class Tasks : Executor {
        val tasks = ArrayDeque<Runnable>()
        override fun execute(command: Runnable) { tasks.addLast(command) }
        fun run() { while (tasks.isNotEmpty()) tasks.removeFirst().run() }
    }
    private class Connection : BaseInputConnection(View(RuntimeEnvironment.getApplication()), true) {
        var commits = 0
        val keys = mutableListOf<KeyEvent>()
        init { Selection.setSelection(editable, 0) }
        override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
            commits++
            return super.commitText(text, newCursorPosition)
        }
        override fun sendKeyEvent(event: KeyEvent): Boolean { keys += event; return true }
    }
    private class Fixture(candidateMenu: Boolean = false, candidateCount: Int = 2) {
        val worker = Tasks()
        val callbacks = Tasks()
        val connection = Connection()
        var loads = 0
        var ready = false
        val session = EditorSession(7, connection, false, true, 0, 0,
            dictionary = BasicSkkDictionary {
                if (!ready) throw DeferredDictionaryReadException { loads++; ready = true }
                listOf(DictionaryCandidate("日本"), DictionaryCandidate("二本")) +
                    (3..candidateCount).map { DictionaryCandidate("候補$it") }
            }, candidateDisplayConfig = CandidateDisplayConfig(inlineCandidateCount = if (candidateMenu) 0 else 2),
            candidatePageCapacityProvider = { _, _ -> 2 },
            callbackExecutor = callbacks, dictionaryExecutor = worker)
        fun cold() {
            session.handle(BasicSkkAction.Text("Nihon"))
            assertTrue(session.handle(BasicSkkAction.ConvertNext))
            assertTrue(session.dictionaryReadPending)
            assertEquals(0, loads)
        }
        fun complete() { worker.run(); callbacks.run() }
    }

    @Test fun `遅い検索を実行せずキーを返し到着後の入力順と確定回数を保つ`() {
        val f = Fixture()
        f.cold()
        f.session.handle(BasicSkkAction.ConvertNext)
        f.session.handle(BasicSkkAction.Enter)
        f.session.handle(BasicSkkAction.Text("ka"))
        assertEquals(0, f.connection.commits)
        assertEquals("にほn", f.connection.editable.toString())
        f.complete()
        assertEquals("二本か", f.connection.editable.toString())
        assertEquals(2, f.connection.commits)
        assertEquals(1, f.loads)
        assertFalse(f.session.dictionaryReadPending)
    }

    @Test fun `検索待ちの画面文字は候補ラベルにせず到着後の画面状態で再評価する`() {
        val f = Fixture()
        f.session.setTouchCandidatePresentation(true)
        f.cold()

        assertTrue(f.session.handleTouchText("a"))
        assertEquals("にほn", f.connection.editable.toString())
        f.complete()

        assertEquals("日本あ", f.connection.editable.toString())
        assertEquals(2, f.connection.commits)
        assertFalse(f.session.dictionaryReadPending)
    }

    @Test fun `検索待ちの画面操作は候補到着後のページとモード確定規則で再評価する`() {
        val paging = Fixture(candidateCount = 5)
        paging.session.setTouchCandidatePresentation(true)
        paging.cold()
        assertTrue(paging.session.handleTouchSpaceOrConvert())
        paging.complete()
        assertEquals(2, paging.session.view.candidate?.pageStart)
        assertEquals(0, paging.connection.commits)

        val mode = Fixture()
        mode.session.setTouchCandidatePresentation(true)
        mode.cold()
        assertTrue(mode.session.handleTouchSetInputMode(InputMode.DIRECT))
        mode.complete()
        assertEquals("日本", mode.connection.editable.toString())
        assertEquals(InputMode.DIRECT, mode.session.engine.state.mode)
        assertEquals(1, mode.connection.commits)
    }

    @Test fun `取消は待機キーと遅い検索結果を破棄する`() {
        val f = Fixture()
        f.cold()
        f.session.handle(BasicSkkAction.Enter)
        f.session.handle(BasicSkkAction.Cancel)
        val text = f.connection.editable.toString()
        f.complete()
        assertEquals(text, f.connection.editable.toString())
        assertEquals(0, f.connection.commits)
        assertNull(f.session.view.candidate)
    }

    @Test fun `入力先終了後に届いた検索結果は旧接続へ確定しない`() {
        val f = Fixture()
        f.cold()
        f.session.handle(BasicSkkAction.Enter)
        f.session.close()
        val text = f.connection.editable.toString()
        f.complete()
        assertEquals(text, f.connection.editable.toString())
        assertEquals(0, f.connection.commits)
    }

    @Test fun `自分の選択通知は検索を維持し外部移動は無効にする`() {
        val f = Fixture()
        f.cold()
        f.session.onSelection(3, 3, 0, 3)
        assertTrue(f.session.dictionaryReadPending)
        f.session.onSelection(0, 0, -1, -1)
        assertFalse(f.session.dictionaryReadPending)
        f.complete()
        assertNull(f.session.view.candidate)
    }

    @Test fun `入力保持後の古いコールバックが新しい検索を完了しない`() {
        val f = Fixture()
        f.cold()
        f.session.preserveText()
        f.session.handle(BasicSkkAction.Text("Nihon "))
        assertEquals(2, f.worker.tasks.size)
        f.worker.tasks.removeFirst().run()
        f.callbacks.run()
        assertTrue(f.session.dictionaryReadPending)
        f.complete()
        assertEquals("日本", f.session.view.candidate?.committedText)
    }

    @Test fun `再生中の素通しキーで後続の待機入力を落とさない`() {
        val f = Fixture()
        f.cold()
        f.session.handle(BasicSkkAction.Enter)
        f.session.deferKey { f.session.replayUnhandledKey(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER)) }
        f.session.handle(BasicSkkAction.Text("ka"))
        f.complete()
        assertEquals("日本か", f.connection.editable.toString())
        assertEquals(listOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP), f.connection.keys.map { it.action })
    }

    private fun serviceKey(session: EditorSession, code: Int, service: se.haya.skk.SkkInputMethodService): Boolean {
        val method = service.javaClass.getDeclaredMethod("handleConfiguredKey", EditorSession::class.java,
            KeyEvent::class.java, Boolean::class.javaPrimitiveType)
        method.isAccessible = true
        return method.invoke(service, session, KeyEvent(1, 1, KeyEvent.ACTION_DOWN, code, 0, 0,
            android.view.KeyCharacterMap.VIRTUAL_KEYBOARD, 0), false) as Boolean
    }

    private fun service(): se.haya.skk.SkkInputMethodService {
        val service = se.haya.skk.SkkInputMethodService()
        org.robolectric.util.ReflectionHelpers.setField(service, "sessionCustomization",
            se.haya.skk.settings.CustomizationSettings(0))
        return service
    }

    @Test fun `候補到着前のラベルを到着後の候補メニューで解釈する`() {
        val f = Fixture(candidateMenu = true)
        val service = service()
        f.cold()
        assertTrue(serviceKey(f.session, KeyEvent.KEYCODE_S, service))
        assertTrue(serviceKey(f.session, KeyEvent.KEYCODE_K, service))
        assertTrue(serviceKey(f.session, KeyEvent.KEYCODE_A, service))
        f.complete()
        assertEquals("二本か", f.connection.editable.toString())
    }

    @Test fun `素通しへ変わるEnterの後もサービスがキーを順に再生する`() {
        val f = Fixture()
        val service = service()
        f.cold()
        serviceKey(f.session, KeyEvent.KEYCODE_ENTER, service)
        serviceKey(f.session, KeyEvent.KEYCODE_ENTER, service)
        serviceKey(f.session, KeyEvent.KEYCODE_K, service)
        serviceKey(f.session, KeyEvent.KEYCODE_A, service)
        f.complete()
        assertEquals("日本\nか", f.connection.editable.toString())
        assertEquals(2, f.connection.keys.size)
    }

    @Test fun `検索待ちの物理Enterは候補到着後に確定と改行を続ける`() {
        val f = Fixture()
        f.cold()
        assertTrue(f.session.handleHardwareEnter())
        assertTrue(f.session.dictionaryReadPending)
        f.complete()
        assertEquals("日本\n", f.connection.editable.toString())
        assertEquals(2, f.connection.commits)
    }

    @Test fun `多数の受理済み入力を欠落させず到着順に再生する`() {
        val f = Fixture()
        f.cold()
        f.session.handle(BasicSkkAction.Enter)
        val text = (0 until 100).joinToString("") { (it % 10).toString() }
        text.forEach { f.session.handle(BasicSkkAction.Text(it.toString(), false)) }
        f.complete()
        assertEquals("日本" + text, f.connection.editable.toString())
    }
    @Test fun `検索待ち入力の上限では先着順を保ち超過を通知する`() {
        val f = Fixture()
        f.cold()
        val replayed = mutableListOf<Int>()
        repeat(1100) { index ->
            assertTrue(f.session.deferKey { replayed += index; true })
        }
        assertEquals("辞書の検索待ち入力が上限に達しました。追加の入力を受け付けられません", f.session.notice)
        f.complete()
        assertEquals((0 until 1024).toList(), replayed)
        assertFalse(f.session.dictionaryReadPending)
        assertTrue(f.session.handle(BasicSkkAction.Enter))
        assertEquals("日本", f.connection.editable.toString())
    }

    @Test fun `上限まで待機していても終了後の検索結果は入力を再生しない`() {
        val f = Fixture()
        f.cold()
        var replayed = 0
        repeat(1100) { assertTrue(f.session.deferKey { replayed++; true }) }
        f.session.close()
        f.complete()
        assertEquals(0, replayed)
        assertFalse(f.session.dictionaryReadPending)
    }

}
