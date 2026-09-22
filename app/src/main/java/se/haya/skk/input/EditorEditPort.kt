package se.haya.skk.input

import android.os.Build
import android.os.SystemClock
import android.view.KeyEvent
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InputConnection
import se.haya.skk.core.editing.EditCommand
import se.haya.skk.core.editing.EditPlan
import se.haya.skk.core.editing.EditResult
import se.haya.skk.core.editing.EditSnapshot
import se.haya.skk.core.editing.Editing
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 呼び出し元は AtomicReference 等で一貫した状態を別スレッドへ公開します。
 * 自分の編集要求に一致する選択通知では revision を維持し、それ以外の入力・変更では増やします。
 */
data class EditorEditState(
    val active: Boolean,
    val generation: Long,
    val selectionStart: Int,
    val selectionEnd: Int,
    val revision: Long,
    val protectedInput: Boolean = false,
    val hasComposition: Boolean = false,
)

data class EditorEditResult(
    val outcome: Outcome,
    val reason: Reason? = null,
    val expectedSelectionStart: Int? = null,
    val expectedSelectionEnd: Int? = null,
) {
    enum class Outcome { APPLIED, NO_CHANGE, NATIVE_ISSUED, UNSUPPORTED, STALE, FAILED_OR_UNKNOWN, BUSY }
    enum class Reason { INACTIVE, UNAVAILABLE, INVALID_SNAPSHOT, INSUFFICIENT_CONTEXT, API_REJECTED, TIMEOUT, EXCEPTION, POSTCHECK_MISMATCH, END_OF_BUFFER }
}

/** 遅延処理の取消は本文・接続を保持しないタイマーにも利用します。 */
fun interface EditCancellation { fun cancel() }
fun interface EditScheduler { fun schedule(delayMillis: Long, task: Runnable): EditCancellation }

/**
 * 接続を固定して、取得・照合・単一変更・事後確認を直列実行します。
 * InputConnection は比較付き原子更新ではないため、最後の照合後の入力先変更は防げません。
 */
class EditorEditPort(
    private val connection: InputConnection,
    private val generation: Long,
    private val state: () -> EditorEditState,
    private val callbackExecutor: Executor,
    private val workerExecutor: Executor = workers,
    private val scheduler: EditScheduler = timers,
    private val clock: () -> Long = SystemClock::uptimeMillis,
    private val beforeRequest: (EditorEditState, Int) -> Boolean = { _, _ -> true },
    private val copyKilledText: (String) -> Boolean = { true },
    private val ownsKilledText: (String) -> Boolean = { true },
) : AutoCloseable {
    private data class KillChain(val text: String, val revision: Long, val cursor: Int, val epoch: Long)
    private val killChain = java.util.concurrent.atomic.AtomicReference<KillChain?>(null)
    private val killEpoch = java.util.concurrent.atomic.AtomicLong()
    fun breakKillChain() { killEpoch.incrementAndGet(); killChain.set(null) }
    fun expectNativeEnter() { nativeNavigation.set(true); nativeSelectionPending.set(true) }

    private val active = AtomicBoolean(true)
    private val failed = AtomicBoolean(false)
    private val busy = AtomicBoolean(false)
    private val nativeNavigation = AtomicBoolean(false)
    val isNativeNavigation: Boolean get() = nativeNavigation.get()
    private val nativeSelectionPending = AtomicBoolean(false)
    private val nativeSelectionWaiter = java.util.concurrent.atomic.AtomicReference<Runnable?>(null)

    /** セッションがネイティブ移動の選択通知を反映した後に呼びます。 */
    fun acknowledgeNativeSelection() {
        nativeSelectionPending.set(false)
        nativeSelectionWaiter.getAndSet(null)?.run()
    }
    private val contextRequest = AtomicBoolean(false)
    val isContextRequestPending: Boolean get() = contextRequest.get()
    fun finishContextRequest() { contextRequest.set(false) }

    fun resetNativeNavigation() {
        nativeNavigation.set(false)
        nativeSelectionPending.set(false)
        breakKillChain()
        finishContextRequest()
    }
    @Volatile private var pending: Operation? = null
    private var verticalGoal: Int? = null
    private var snapshotUnavailable = false
    private var verticalState: EditorEditState? = null

    private class Operation(
        @Volatile var initial: EditorEditState,
        val killEpoch: Long,
        @Volatile var deadline: Long,
        val callback: (EditorEditResult) -> Unit,
    ) {
        var started = false
        var nativeWaiter: Runnable? = null
        val delivered = AtomicBoolean(false)
        val callbackDelivered = AtomicBoolean(false)
        @Volatile var completion: EditorEditResult? = null
        @Volatile var timer: EditCancellation? = null
    }

    /** 認識済みキーは結果によらず呼び出し元で消費し、再配送しません。 */
    fun submit(command: EditCommand, callback: (EditorEditResult) -> Unit) {
        if (command != EditCommand.KILL_LINE) breakKillChain()
        val initial = state()
        if (!usable(initial)) {
            deliver(callback, result(EditorEditResult.Outcome.STALE, EditorEditResult.Reason.INACTIVE))
            return
        }
        if (!busy.compareAndSet(false, true)) {
            deliver(callback, result(EditorEditResult.Outcome.BUSY))
            return
        }
        val operation = Operation(initial, killEpoch.get(), clock() + TIMEOUT_MILLIS, callback)
        pending = operation
        if (nativeSelectionPending.get() && command == EditCommand.KILL_LINE) {
            // 直前の標準キーより選択通知が遅れる入力先では、座標確定まで変更を発行しません。
            // 端で動かなかったキーは通知されないため、最大 500 ms 後に通常の厳密照合へ進みます。
            // 期限後も取得座標とセッション座標が違えば拒否し、推測した位置では削除しません。
            try {
                synchronized(operation) {
                    val waiter = Runnable { startOperation(operation, command, afterNativeWait = true) }
                    operation.nativeWaiter = waiter
                    nativeSelectionWaiter.set(waiter)
                    operation.timer = scheduler.schedule(TIMEOUT_MILLIS, waiter)
                }
                if (!nativeSelectionPending.get()) acknowledgeNativeSelection()
            } catch (_: Exception) {
                operation.nativeWaiter?.let { nativeSelectionWaiter.compareAndSet(it, null) }
                if (pending === operation) pending = null
                busy.set(false)
                fail(operation, EditorEditResult.Reason.EXCEPTION, immediately = true)
            }
        } else startOperation(operation, command, afterNativeWait = false)
    }

    private fun startOperation(operation: Operation, command: EditCommand, afterNativeWait: Boolean) {
        synchronized(operation) {
            if (operation.started || operation.delivered.get()) return
            operation.started = true
            operation.timer?.cancel()
            operation.nativeWaiter?.let { nativeSelectionWaiter.compareAndSet(it, null) }
            if (afterNativeWait) {
                val current = state()
                if (!usable(current) || current.revision != operation.initial.revision) {
                    if (pending === operation) pending = null
                    busy.set(false)
                    finish(operation, result(EditorEditResult.Outcome.STALE), immediately = true)
                    return
                }
                // 取得を始める前に、同じ版のネイティブ選択通知で確定した座標を採用します。
                operation.initial = current
                nativeSelectionPending.set(false)
                operation.deadline = clock() + TIMEOUT_MILLIS
            }
            try {
                operation.timer = scheduler.schedule(TIMEOUT_MILLIS, Runnable {
                    fail(operation, EditorEditResult.Reason.TIMEOUT, immediately = true)
                })
                workerExecutor.execute {
                    try {
                        if (!operation.delivered.get()) execute(operation, command)
                    } catch (_: Exception) {
                        fail(operation, EditorEditResult.Reason.EXCEPTION)
                    } finally {
                        operation.timer?.cancel()
                        if (pending === operation) pending = null
                        busy.set(false)
                        // 通常完了はワーカーの所有権を解放してから通知し、次の要求を受け入れます。
                        deliverCompletion(operation)
                    }
                }
            } catch (_: RejectedExecutionException) {
                operation.timer?.cancel()
                pending = null
                busy.set(false)
                finish(operation, result(EditorEditResult.Outcome.BUSY), immediately = true)
            } catch (_: Exception) {
                operation.timer?.cancel()
                pending = null
                busy.set(false)
                fail(operation, EditorEditResult.Reason.EXCEPTION, immediately = true)
            }
        }
    }

    override fun close() {
        active.set(false)
        breakKillChain()
        nativeSelectionWaiter.set(null)
        pending?.let {
            it.timer?.cancel()
            finish(it, result(EditorEditResult.Outcome.STALE, EditorEditResult.Reason.INACTIVE), immediately = true)
        }
    }

    private fun execute(operation: Operation, command: EditCommand) {
        if (nativeNavigation.get() && command == EditCommand.NEWLINE) {
            return issueUnverifiedCommand(operation, command)
        }
        if (nativeNavigation.get() && command in NATIVE_SEQUENCE_COMMANDS) {
            return issueNative(operation, command)
        }
        // 通常編集は新しい取得・選択位置の照合から再開します。
        // ただし標準キーの適用と取得を原子的に順序付ける API はありません。
        nativeNavigation.set(false)
        if (!check(operation)) return
        if (command in setOf(EditCommand.PASTE, EditCommand.UNDO)) {
            return issueContextCommand(operation, command)
        }
        val first = read() ?: return if (snapshotUnavailable) {
            if (!check(operation)) return
            if (command in setOf(EditCommand.NEWLINE, EditCommand.CUT, EditCommand.COPY)) issueUnverifiedCommand(operation, command)
            else issueNative(operation, command)
        } else unsupported(operation, EditorEditResult.Reason.INVALID_SNAPSHOT)
        if (first.offset == -1 || !matchesSelection(first, operation.initial)) {
            // Android の既定実装は相対選択だけを返し、絶対 offset を -1 にします。
            // また選択通知が取得結果より遅れる入力先もあります。絶対位置を推測せず、
            // 有効なスナップショットが二回一致した場合だけ入力先へ範囲決定を委ねます。
            val relative = EditSnapshot(first.text, first.start, first.end, "editor-$generation", operation.initial.revision)
            if (Editing.plan(relative, EditCommand.HOME, maxLength = Editing.EXTERNAL_LIMIT) !is EditResult.Ready) {
                return unsupported(operation, EditorEditResult.Reason.INVALID_SNAPSHOT)
            }
            return executeNative(operation, command, first)
        }
        if (command in listOf(EditCommand.PAGE_DOWN, EditCommand.PAGE_UP,
                EditCommand.BUFFER_START, EditCommand.BUFFER_END)) {
            return executeNative(operation, command, first)
        }
        if (command in listOf(EditCommand.CUT, EditCommand.COPY, EditCommand.NEWLINE)) {
            return executeClipboardOrNewline(operation, command, first)
        }
        val documentEndKnown = command == EditCommand.KILL_LINE && reachesDocumentEnd(first)
        val ready = prepare(first, command, operation.initial, documentEndKnown)
            ?: return if (canUseNativeMovement(first, command, operation.initial)) {
                executeNative(operation, command, first)
            } else unsupported(operation, EditorEditResult.Reason.INSUFFICIENT_CONTEXT)
        if (!check(operation)) return
        // 同じ取得経路を使い、フォールバックによる別形式の混在を避けます。
        val second = read(first.source) ?: return unsupported(operation, EditorEditResult.Reason.UNAVAILABLE)
        if (first != second) {
            finish(operation, result(EditorEditResult.Outcome.STALE))
            verticalGoal = null
            return
        }
        if (documentEndKnown && !reachesDocumentEnd(second)) {
            return finish(operation, result(EditorEditResult.Outcome.STALE))
        }
        if (!check(operation)) return
        val plan = ready.plan
        val target = ready.offset + plan.cursor
        if (!plan.changed) {
            verticalGoal = plan.preferredColumn
            verticalState = operation.initial
            finish(operation, EditorEditResult(EditorEditResult.Outcome.NO_CHANGE,
                reason = if (command == EditCommand.KILL_LINE && documentEndKnown) EditorEditResult.Reason.END_OF_BUFFER else null,
                expectedSelectionStart = target, expectedSelectionEnd = target))
            return
        }
        if (!beforeRequest(operation.initial, target)) {
            finish(operation, result(EditorEditResult.Outcome.STALE))
            return
        }
        if (!check(operation)) return
        val removed = plan.deletedRange
        val previousKill = killChain.get()
        val killedText = if (command == EditCommand.KILL_LINE && removed != null) {
            val previous = previousKill?.takeIf { it.epoch == operation.killEpoch &&
                it.revision == operation.initial.revision && it.cursor == operation.initial.selectionStart &&
                operation.initial.selectionStart == operation.initial.selectionEnd }
            val ownsPrevious = previous != null && ownsKilledText(previous.text)
            // クリップボード照合中にも終了・選択変更・期限切れが起こるため、書込み前に再検証します。
            if (!check(operation)) return
            val prefix = if (ownsPrevious && killEpoch.get() == operation.killEpoch &&
                killChain.get() === previous) previous!!.text else ""
            prefix + plan.original.text.substring(removed.start, removed.end)
        } else null
        if (killedText != null && !copyKilledText(killedText)) {
            return fail(operation, EditorEditResult.Reason.API_REJECTED)
        }
        if (!check(operation)) return
        val accepted = if (removed == null) {
            connection.setSelection(target, target)
        } else if (operation.initial.selectionStart != operation.initial.selectionEnd) {
            connection.commitText("", 1)
        } else {
            val cursor = plan.original.selectionEnd
            connection.deleteSurroundingText(cursor - removed.start, removed.end - cursor)
        }
        if (!accepted) return fail(operation, EditorEditResult.Reason.API_REJECTED)
        if (!checkAfterRequest(operation, target)) return
        val after = read(first.source) ?: return fail(operation, EditorEditResult.Reason.POSTCHECK_MISMATCH)
        if (!checkAfterRequest(operation, target)) return
        val expectedText = if (removed == null) first.text else {
            val begin = ready.offset - first.offset + removed.start
            val end = ready.offset - first.offset + removed.end
            first.text.removeRange(begin, end)
        }
        val contentMatches = matchesOverlappingText(first.offset, expectedText, after, target)
        if (!contentMatches || after.absoluteStart != target || after.absoluteEnd != target) {
            return fail(operation, EditorEditResult.Reason.POSTCHECK_MISMATCH)
        }
        if (killedText != null && checkAfterRequest(operation, target) && killEpoch.get() == operation.killEpoch) {
            killChain.compareAndSet(previousKill, KillChain(killedText, operation.initial.revision, target, operation.killEpoch))
        }
        verticalGoal = plan.preferredColumn
        verticalState = state().copy(selectionStart = target, selectionEnd = target)
        finish(operation, EditorEditResult(EditorEditResult.Outcome.APPLIED,
            expectedSelectionStart = target, expectedSelectionEnd = target))
    }

    private fun canUseNativeMovement(window: Window, command: EditCommand, initial: EditorEditState): Boolean {
        if (window.start != window.end || command !in NATIVE_SEQUENCE_COMMANDS) return false
        // 不正な UTF-16 やクラスタ途中の位置を、取得窓不足と取り違えません。
        val snapshot = EditSnapshot(window.text, window.start, window.end, "editor-$generation", initial.revision)
        val plan = (Editing.plan(snapshot, command, maxLength = Editing.EXTERNAL_LIMIT) as? EditResult.Ready)?.plan
            ?: return false
        if (window.text.endsWith('\r') && (window.start == window.text.length ||
                window.end == window.text.length || plan.cursor == window.text.length)) return false
        // 削除後のクラスタ結合で位置補正が必要な計画は従来どおり拒否します。
        val removed = plan.deletedRange
        return removed == null || plan.cursor == removed.start
    }

    private fun executeNative(operation: Operation, command: EditCommand, first: Window) {
        if (!check(operation)) return
        val second = read(first.source) ?: return unsupported(operation, EditorEditResult.Reason.UNAVAILABLE)
        if (first != second) return finish(operation, result(EditorEditResult.Outcome.STALE))
        if (!check(operation)) return
        if (command in setOf(EditCommand.NEWLINE, EditCommand.CUT, EditCommand.COPY)) {
            issueUnverifiedCommand(operation, command, first.start != first.end)
        } else issueNative(operation, command)
    }

    private fun issueNative(operation: Operation, command: EditCommand) {
        if (!checkNative(operation)) return
        // 本文が取得できない場合や境界を証明できない場合は、入力先自身に
        // 標準キーの範囲決定を任せます。変更 API の発行後にはこの経路へ戻りません。
        val (code, meta) = when (command) {
            EditCommand.LEFT -> KeyEvent.KEYCODE_DPAD_LEFT to 0
            EditCommand.RIGHT -> KeyEvent.KEYCODE_DPAD_RIGHT to 0
            EditCommand.UP -> KeyEvent.KEYCODE_DPAD_UP to 0
            EditCommand.DOWN -> KeyEvent.KEYCODE_DPAD_DOWN to 0
            EditCommand.HOME -> KeyEvent.KEYCODE_MOVE_HOME to 0
            EditCommand.END -> KeyEvent.KEYCODE_MOVE_END to 0
            EditCommand.BACKSPACE -> KeyEvent.KEYCODE_DEL to 0
            EditCommand.DELETE -> KeyEvent.KEYCODE_FORWARD_DEL to 0
            EditCommand.PAGE_DOWN -> KeyEvent.KEYCODE_PAGE_DOWN to 0
            EditCommand.PAGE_UP -> KeyEvent.KEYCODE_PAGE_UP to 0
            EditCommand.BUFFER_START -> KeyEvent.KEYCODE_MOVE_HOME to KeyEvent.META_CTRL_ON
            EditCommand.BUFFER_END -> KeyEvent.KEYCODE_MOVE_END to KeyEvent.META_CTRL_ON
            else -> return unsupported(operation, EditorEditResult.Reason.INSUFFICIENT_CONTEXT)
        }
        if (command == EditCommand.BUFFER_START || command == EditCommand.BUFFER_END) {
            // 「<」「>」入力用の物理 Shift が移動キーの範囲選択に残るのを防ぎます。
            connection.clearMetaKeyStates(KeyEvent.META_SHIFT_MASK)
            if (!checkNative(operation)) return
        }
        val now = clock()
        val down = KeyEvent(now, now, KeyEvent.ACTION_DOWN, code, 0, meta)
        val up = KeyEvent(now, now, KeyEvent.ACTION_UP, code, 0, meta)
        nativeNavigation.set(command in NATIVE_SEQUENCE_COMMANDS)
        nativeSelectionPending.set(command in NATIVE_SEQUENCE_COMMANDS)
        if (!connection.sendKeyEvent(down)) return fail(operation, EditorEditResult.Reason.API_REJECTED)
        // 下向きイベントが既に適用された可能性があるので、失敗時も再送しません。
        connection.sendKeyEvent(up)
        verticalGoal = null
        // キーの受付は適用完了を保証せず、直後の取得も完了確認にはなりません。
        // 以後のネイティブ移動・削除も同じ接続へ順に送り、選択位置は入力先の通知だけで更新します。
        finish(operation, EditorEditResult(EditorEditResult.Outcome.NATIVE_ISSUED))
    }

    private fun issueUnverifiedCommand(operation: Operation, command: EditCommand,
        hasSelection: Boolean = operation.initial.selectionStart >= 0 &&
            operation.initial.selectionEnd >= 0 && operation.initial.selectionStart != operation.initial.selectionEnd) {
        if (command == EditCommand.NEWLINE) {
            if (!checkNative(operation)) return
            // 改行後の通知も遅れて届くため、入力先が範囲を決める編集の列として扱います。
            // 次の移動・削除は古い取得結果から計画せず、同じ接続へ順に送ります。
            nativeNavigation.set(true)
        } else if (!check(operation)) return
        if (command != EditCommand.NEWLINE && !hasSelection) {
            return finish(operation, EditorEditResult(EditorEditResult.Outcome.NO_CHANGE))
        }
        // ENTER は送信・検索に変わるため、取得不能でも改行文字だけを一度送ります。
        val accepted = when (command) {
            EditCommand.CUT -> connection.performContextMenuAction(android.R.id.cut)
            EditCommand.COPY -> connection.performContextMenuAction(android.R.id.copy)
            else -> connection.commitText("\n", 1)
        }
        if (!accepted) return fail(operation, EditorEditResult.Reason.API_REJECTED)
        verticalGoal = null
        finish(operation, EditorEditResult(EditorEditResult.Outcome.NATIVE_ISSUED))
    }

    private fun issueContextCommand(operation: Operation, command: EditCommand) {
        if (!check(operation)) return
        val id = when (command) {
            EditCommand.PASTE -> android.R.id.paste
            EditCommand.UNDO -> android.R.id.undo
            else -> return unsupported(operation, EditorEditResult.Reason.INSUFFICIENT_CONTEXT)
        }
        // 結果の座標が不明な操作では、要求中の通知をセッション側で保留します。
        contextRequest.set(true)
        if (!connection.performContextMenuAction(id)) {
            return fail(operation, EditorEditResult.Reason.API_REJECTED)
        }
        verticalGoal = null
        // 同じ接続で事後取得できた座標だけを照合に使います。取得はユーザー操作と
        // 原子的ではないため、取得不能・不一致では待機操作を継続せず、再送もしません。
        val after = read()
        if (!check(operation)) return
        val known = after?.takeIf { it.offset >= 0 && it.start == it.end }
        if (known != null && (known.absoluteStart != operation.initial.selectionStart ||
                known.absoluteEnd != operation.initial.selectionEnd) &&
            !beforeRequest(operation.initial, known.absoluteStart)) {
            return finish(operation, result(EditorEditResult.Outcome.STALE))
        }
        finish(operation, EditorEditResult(EditorEditResult.Outcome.NATIVE_ISSUED,
            expectedSelectionStart = known?.absoluteStart, expectedSelectionEnd = known?.absoluteEnd))
    }

    private fun executeClipboardOrNewline(operation: Operation, command: EditCommand, first: Window) {
        val initial = operation.initial
        val selection = first.start != first.end
        if (command != EditCommand.NEWLINE && !selection) {
            finish(operation, EditorEditResult(EditorEditResult.Outcome.NO_CHANGE,
                expectedSelectionStart = initial.selectionStart, expectedSelectionEnd = initial.selectionEnd))
            return
        }
        // 選択範囲の操作は入力先自身の選択を使い、取得窓の文字境界を削除計画に変換しません。
        if (!check(operation)) return
        val second = read(first.source) ?: return unsupported(operation, EditorEditResult.Reason.UNAVAILABLE)
        if (first != second) return finish(operation, result(EditorEditResult.Outcome.STALE))
        if (!check(operation)) return
        val low = minOf(first.start, first.end)
        val high = maxOf(first.start, first.end)
        val expected = when (command) {
            EditCommand.CUT -> first.text.removeRange(low, high)
            EditCommand.COPY -> first.text
            else -> first.text.replaceRange(low, high, "\n")
        }
        val target = if (command == EditCommand.COPY) initial.selectionEnd else
            first.offset + low + if (command == EditCommand.NEWLINE) 1 else 0
        if (command != EditCommand.COPY && !beforeRequest(initial, target)) {
            return finish(operation, result(EditorEditResult.Outcome.STALE))
        }
        if (!check(operation)) return
        val accepted = when (command) {
            EditCommand.CUT -> connection.performContextMenuAction(android.R.id.cut)
            EditCommand.COPY -> connection.performContextMenuAction(android.R.id.copy)
            else -> connection.commitText("\n", 1)
        }
        if (!accepted) return fail(operation, EditorEditResult.Reason.API_REJECTED)
        if (command == EditCommand.COPY) { if (!check(operation)) return }
        else if (!checkAfterRequest(operation, target)) return
        val after = read(first.source) ?: return fail(operation, EditorEditResult.Reason.POSTCHECK_MISMATCH)
        if (command == EditCommand.COPY) { if (!check(operation)) return }
        else if (!checkAfterRequest(operation, target)) return
        if (!matchesOverlappingText(first.offset, expected, after, target,
                requirePreviousCharacter = command == EditCommand.NEWLINE) ||
            after.absoluteStart != (if (command == EditCommand.COPY) initial.selectionStart else target) ||
            after.absoluteEnd != target) {
            return fail(operation, EditorEditResult.Reason.POSTCHECK_MISMATCH)
        }
        verticalGoal = null
        finish(operation, EditorEditResult(EditorEditResult.Outcome.APPLIED,
            expectedSelectionStart = if (command == EditCommand.COPY) initial.selectionStart else target,
            expectedSelectionEnd = target))
    }

    private fun matchesOverlappingText(expectedOffset: Int, expected: String, after: Window,
        target: Int, requirePreviousCharacter: Boolean = false): Boolean {
        if (expected.isEmpty()) return after.text.isEmpty() && after.offset == expectedOffset &&
            target == expectedOffset
        val start = maxOf(expectedOffset.toLong(), after.offset.toLong())
        val end = minOf(expectedOffset.toLong() + expected.length,
            after.offset.toLong() + after.text.length)
        if (start >= end || target.toLong() !in start..end ||
            requirePreviousCharacter && target.toLong() - 1 !in start until end) return false
        return after.text.regionMatches((start - after.offset).toInt(), expected,
            (start - expectedOffset).toInt(), (end - start).toInt())
    }

    private fun usable(current: EditorEditState): Boolean = active.get() && !failed.get() &&
        current.active && current.generation == generation && !current.protectedInput && !current.hasComposition

    private fun check(operation: Operation): Boolean {
        if (operation.delivered.get()) return false
        if (clock() >= operation.deadline) {
            fail(operation, EditorEditResult.Reason.TIMEOUT)
            return false
        }
        val current = state()
        if (!usable(current) || current != operation.initial) {
            verticalGoal = null
            finish(operation, result(EditorEditResult.Outcome.STALE))
            return false
        }
        return true
    }

    private fun checkNative(operation: Operation): Boolean {
        if (operation.delivered.get()) return false
        if (clock() >= operation.deadline) {
            fail(operation, EditorEditResult.Reason.TIMEOUT)
            return false
        }
        val current = state()
        if (!usable(current) || current.revision != operation.initial.revision) {
            finish(operation, result(EditorEditResult.Outcome.STALE))
            return false
        }
        return true
    }

    private fun checkAfterRequest(operation: Operation, target: Int): Boolean {
        if (operation.delivered.get()) return false
        if (clock() >= operation.deadline) {
            fail(operation, EditorEditResult.Reason.TIMEOUT)
            return false
        }
        val current = state()
        if (!usable(current)) {
            if (current.active && current.generation == generation) {
                fail(operation, EditorEditResult.Reason.POSTCHECK_MISMATCH)
            } else {
                finish(operation, result(EditorEditResult.Outcome.STALE))
            }
            verticalGoal = null
            return false
        }
        if (current.revision != operation.initial.revision ||
            !((current.selectionStart == target && current.selectionEnd == target) || current == operation.initial)) {
            fail(operation, EditorEditResult.Reason.POSTCHECK_MISMATCH)
            return false
        }
        return true
    }

    private enum class Source { SURROUNDING, EXTRACTED }
    private data class Window(val text: String, val offset: Int, val start: Int, val end: Int, val source: Source) {
        val absoluteStart: Int get() = offset + start
        val absoluteEnd: Int get() = offset + end
    }
    private data class Prepared(val offset: Int, val plan: EditPlan)

    private fun read(source: Source? = null): Window? {
        snapshotUnavailable = false
        if (Build.VERSION.SDK_INT >= 31 && (source == Source.SURROUNDING || source == null)) {
            val value = connection.getSurroundingText(CONTEXT, CONTEXT, 0)
            if (value != null) {
                if (value.offset != -1) return window(value.text, value.offset, value.selectionStart, value.selectionEnd, Source.SURROUNDING)
                val relative = window(value.text, 0, value.selectionStart, value.selectionEnd, Source.SURROUNDING)
                    ?.copy(offset = -1) ?: return null
                if (source == null) {
                    val extracted = read(Source.EXTRACTED)
                    if (extracted != null) return extracted
                    if (!snapshotUnavailable) return null
                }
                snapshotUnavailable = false
                return relative
            }
            if (source == Source.SURROUNDING) return null
        }
        val request = ExtractedTextRequest().apply {
            hintMaxChars = Editing.EXTERNAL_LIMIT
            hintMaxLines = 0
        }
        val value = connection.getExtractedText(request, 0) ?: run {
            snapshotUnavailable = true
            return null
        }
        if (value.partialStartOffset != -1) return null
        return window(value.text, value.startOffset, value.selectionStart, value.selectionEnd, Source.EXTRACTED)
    }

    private fun window(value: CharSequence?, offset: Int, start: Int, end: Int, source: Source): Window? {
        if (value == null || value.length > Editing.EXTERNAL_LIMIT || offset < 0 ||
            start !in 0..value.length || end !in 0..value.length ||
            offset.toLong() + value.length > Int.MAX_VALUE) return null
        return Window(value.toString(), offset, start, end, source)
    }

    private fun matchesSelection(window: Window, current: EditorEditState): Boolean =
        window.absoluteStart == current.selectionStart && window.absoluteEnd == current.selectionEnd

    private fun prepare(window: Window, command: EditCommand, initial: EditorEditState,
        documentEndKnown: Boolean = false): Prepared? {
        val lower = minOf(window.start, window.end)
        var start = 0
        if (window.offset != 0) {
            start = -1
            var index = 0
            while (index < lower) {
                when (window.text[index]) {
                    '\r' -> {
                        if (index + 1 >= window.text.length) break
                        val after = index + if (window.text[index + 1] == '\n') 2 else 1
                        if (after <= lower && start < 0) start = after
                        index = after
                        continue
                    }
                    '\n' -> if (index > 0 && start < 0) start = index + 1
                    '\u2028', '\u2029' -> if (start < 0) start = index + 1
                }
                index++
            }
            if (start < 0) return null
        }
        val text = window.text.substring(start)
        val snapshot = EditSnapshot(text, window.start - start, window.end - start,
            "editor-$generation", initial.revision)
        val preferred = if (verticalState == initial) verticalGoal else null
        val plan = (Editing.plan(snapshot, command, preferred, Editing.EXTERNAL_LIMIT) as? EditResult.Ready)?.plan ?: return null
        // 末尾の CR は、未取得の LF と一つの改行を作る可能性があります。
        if (text.endsWith('\r') && (snapshot.selectionStart == text.length ||
                snapshot.selectionEnd == text.length || plan.cursor == text.length)) return null
        // 窓末尾は文書末尾とは限りません。HOME の移動先だけは既知の行頭で証明できます。
        if (command != EditCommand.HOME && !(command == EditCommand.KILL_LINE && documentEndKnown) &&
            (snapshot.selectionStart == text.length || snapshot.selectionEnd == text.length || plan.cursor == text.length)) return null
        val removed = plan.deletedRange
        if (removed != null && ((removed.end == text.length && !documentEndKnown) || plan.cursor != removed.start)) return null
        if (removed == null && command == EditCommand.KILL_LINE &&
            snapshot.selectionStart == snapshot.selectionEnd && !documentEndKnown) return null
        val absoluteOffset = window.offset + start
        if (absoluteOffset > 0 && snapshot.selectionStart == snapshot.selectionEnd && snapshot.selectionEnd == 0 &&
            command in listOf(EditCommand.LEFT, EditCommand.BACKSPACE, EditCommand.WORD_BACKWARD, EditCommand.UP)) return null
        if (command == EditCommand.DOWN && !text.substring(snapshot.selectionEnd).any { it in "\r\n\u2028\u2029" }) return null
        if (absoluteOffset > 0 && command == EditCommand.UP && !text.substring(0, snapshot.selectionEnd).any { it in "\r\n\u2028\u2029" }) return null
        return Prepared(absoluteOffset, plan)
    }

    /** 既知の窓末尾より一文字多く要求し、現在位置から文書末尾まで取得できた場合だけ真にします。 */
    private fun reachesDocumentEnd(window: Window): Boolean {
        if (window.start != window.end || window.end !in 0..window.text.length) return false
        val knownAfter = window.text.length - window.end
        if (knownAfter >= CONTEXT) return false
        val after = connection.getTextAfterCursor(knownAfter + 1, 0)?.toString() ?: return false
        if (after.length != knownAfter || after != window.text.substring(window.end)) return false
        // getTextAfterCursor は入力先が要求数より短く切る場合があります。差分でない完全な
        // ExtractedText も同じ末尾を示したときだけ、窓末尾を文書末尾として扱います。
        val extracted = read(Source.EXTRACTED) ?: return false
        return extracted.offset == 0 && extracted.absoluteStart == window.absoluteStart &&
            extracted.absoluteEnd == window.absoluteEnd && extracted.text.length == window.offset + window.text.length &&
            extracted.text.regionMatches(window.offset, window.text, 0, window.text.length)
    }

    private fun unsupported(operation: Operation, reason: EditorEditResult.Reason) {
        verticalGoal = null
        finish(operation, result(EditorEditResult.Outcome.UNSUPPORTED, reason))
    }

    private fun fail(operation: Operation, reason: EditorEditResult.Reason, immediately: Boolean = false) {
        finish(operation, result(EditorEditResult.Outcome.FAILED_OR_UNKNOWN, reason), failure = true, immediately = immediately)
    }

    private fun finish(operation: Operation, value: EditorEditResult, failure: Boolean = false, immediately: Boolean = false) {
        if (!operation.delivered.compareAndSet(false, true)) return
        if (value.outcome !in setOf(EditorEditResult.Outcome.APPLIED, EditorEditResult.Outcome.NO_CHANGE)) breakKillChain()
        if (failure) {
            // 非同期の入力先では受付直後の取得が古い場合があります。
            // 同じ操作は再送せず、次の明示的な要求で改めて取得・照合します。
            if (value.reason !in setOf(EditorEditResult.Reason.POSTCHECK_MISMATCH, EditorEditResult.Reason.API_REJECTED)) failed.set(true)
            verticalGoal = null
        }
        operation.timer?.cancel()
        operation.completion = value
        if (immediately) deliverCompletion(operation)
    }

    private fun deliverCompletion(operation: Operation) {
        val completion = operation.completion ?: return
        if (operation.callbackDelivered.compareAndSet(false, true)) {
            deliver(operation.callback, completion)
        }
    }

    private fun deliver(callback: (EditorEditResult) -> Unit, value: EditorEditResult) {
        callbackExecutor.execute { callback(value) }
    }

    private fun result(outcome: EditorEditResult.Outcome, reason: EditorEditResult.Reason? = null) = EditorEditResult(outcome, reason)

    companion object {
        private val NATIVE_SEQUENCE_COMMANDS = setOf(EditCommand.LEFT, EditCommand.RIGHT,
            EditCommand.BACKSPACE, EditCommand.DELETE,
            EditCommand.UP, EditCommand.DOWN, EditCommand.HOME, EditCommand.END,
            EditCommand.PAGE_UP, EditCommand.PAGE_DOWN, EditCommand.BUFFER_START, EditCommand.BUFFER_END)
        private const val CONTEXT = 2_048
        private const val TIMEOUT_MILLIS = 500L
        // セッションごとにスレッドを増やさず、停止した接続の後ろに無制限に積みません。
        private val workers: Executor = ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
            ArrayBlockingQueue(1), { runnable -> Thread(runnable, "skk-editor-edit").apply { isDaemon = true } },
            ThreadPoolExecutor.AbortPolicy())
        private val timerExecutor = Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "skk-editor-timeout").apply { isDaemon = true }
        }
        private val timers = EditScheduler { delay, task ->
            val future = timerExecutor.schedule(task, delay, TimeUnit.MILLISECONDS)
            EditCancellation { future.cancel(false) }
        }
    }
}
