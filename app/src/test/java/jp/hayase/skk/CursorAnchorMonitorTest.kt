package jp.hayase.skk

import android.view.View
import android.view.inputmethod.BaseInputConnection
import java.util.concurrent.Executor
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 35])
class CursorAnchorMonitorTest {
    private class Queue : Executor {
        val tasks = ArrayDeque<Runnable>()
        override fun execute(command: Runnable) { tasks.add(command) }
        fun drain() { while (tasks.isNotEmpty()) tasks.removeFirst().run() }
    }
    private class Connection(val call: (Int) -> Boolean) : BaseInputConnection(
        View(RuntimeEnvironment.getApplication()), true,
    ) {
        override fun requestCursorUpdates(cursorUpdateMode: Int) = call(cursorUpdateMode)
    }

    @Test fun `開始と停止は呼出元で待たず待機中の要求を最新の一件へまとめる`() {
        val worker = Queue()
        val callbacks = Queue()
        val monitor = CursorAnchorMonitor(worker, callbacks)
        val requests = mutableListOf<Int>()
        val connection = Connection { requests += it; true }
        repeat(1000) { monitor.start(connection) {} }
        assertTrue(requests.isEmpty())
        assertEquals(1, worker.tasks.size)
        worker.drain()
        assertEquals(listOf(3), requests)
        monitor.stop()
        assertEquals(listOf(3), requests)
        worker.drain()
        assertEquals(listOf(3, 0), requests)
    }

    @Test fun `開始応答中に入力先が変われば旧監視を止めてから新監視を開始する`() {
        val worker = Queue()
        val callbacks = Queue()
        val monitor = CursorAnchorMonitor(worker, callbacks)
        val requests = mutableListOf<String>()
        val completions = mutableListOf<String>()
        val next = Connection { requests += "次:$it"; true }
        val old = Connection {
            requests += "前:$it"
            if (it != 0) monitor.start(next) { completions += "次:$it" }
            true
        }
        monitor.start(old) { completions += "前:$it" }
        worker.drain()
        callbacks.drain()
        assertEquals(listOf("前:3", "前:0", "次:3"), requests)
        assertEquals(listOf("次:true"), completions)
    }

    @Test fun `開始応答中の終了は監視を解除し遅延結果を通知しない`() {
        val worker = Queue()
        val callbacks = Queue()
        val monitor = CursorAnchorMonitor(worker, callbacks)
        val requests = mutableListOf<Int>()
        val connection = Connection {
            requests += it
            if (it != 0) monitor.stop()
            true
        }
        monitor.start(connection) { fail("終了済みの監視結果です") }
        worker.drain()
        callbacks.drain()
        assertEquals(listOf(3, 0), requests)
    }

    @Test fun `開始拒否時に解除要求を重ねず終了後の通知も破棄する`() {
        val worker = Queue()
        val callbacks = Queue()
        val monitor = CursorAnchorMonitor(worker, callbacks)
        val requests = mutableListOf<Int>()
        val connection = Connection { requests += it; false }
        monitor.start(connection) { fail("終了済みの監視結果です") }
        worker.drain()
        monitor.stop()
        worker.drain()
        callbacks.drain()
        assertEquals(listOf(3), requests)
    }
}
