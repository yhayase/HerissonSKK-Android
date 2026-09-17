package jp.hayase.skk

import android.view.inputmethod.InputConnection
import java.util.concurrent.Executor

/** 座標通知の同期応答を待つ処理をキー配送から分離し、最新の監視要求だけを実行します。 */
internal class CursorAnchorMonitor(private val worker: Executor, private val callbacks: Executor) {
    private class Request(val connection: InputConnection, val completed: (Boolean) -> Unit)
    private val lock = Any()
    private var desired: Request? = null
    private var scheduled = false
    // 以下は一度に一つだけ動く drain 内から参照します。
    private var active: InputConnection? = null
    private var processed: Request? = null

    fun start(connection: InputConnection, completed: (Boolean) -> Unit) =
        change(Request(connection, completed))

    fun stop() = change(null)

    private fun change(request: Request?) {
        val launch = synchronized(lock) {
            if (desired === request) return
            desired = request
            if (scheduled) false else {
                scheduled = true
                true
            }
        }
        if (launch) worker.execute(::drain)
    }

    private fun drain() {
        while (true) {
            val request = synchronized(lock) { desired }
            if (active != null && active !== request?.connection) {
                update(checkNotNull(active), 0)
                active = null
            }
            if (request != null && request !== processed) {
                val accepted = active === request.connection || update(request.connection,
                    InputConnection.CURSOR_UPDATE_IMMEDIATE or InputConnection.CURSOR_UPDATE_MONITOR)
                if (accepted) active = request.connection
                callbacks.execute {
                    if (synchronized(lock) { desired === request }) request.completed(accepted)
                }
            }
            processed = request
            synchronized(lock) {
                if (desired === request) {
                    scheduled = false
                    return
                }
            }
        }
    }

    private fun update(connection: InputConnection, mode: Int): Boolean = try {
        connection.requestCursorUpdates(mode)
    } catch (_: RuntimeException) {
        // 接続終了・未対応時には注釈だけを省略し、本文のキー配送を継続します。
        false
    }
}
