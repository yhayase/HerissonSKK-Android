package jp.hayase.skk.input

import android.os.Build
import android.os.SystemClock
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InputConnection
import jp.hayase.skk.core.editing.EditCommand
import jp.hayase.skk.core.editing.EditPlan
import jp.hayase.skk.core.editing.EditResult
import jp.hayase.skk.core.editing.EditSnapshot
import jp.hayase.skk.core.editing.Editing
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
    enum class Outcome { APPLIED, NO_CHANGE, UNSUPPORTED, STALE, FAILED_OR_UNKNOWN, BUSY }
    enum class Reason { INACTIVE, UNAVAILABLE, INVALID_SNAPSHOT, INSUFFICIENT_CONTEXT, API_REJECTED, TIMEOUT, EXCEPTION, POSTCHECK_MISMATCH }
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
) : AutoCloseable {
    private val active = AtomicBoolean(true)
    private val failed = AtomicBoolean(false)
    private val busy = AtomicBoolean(false)
    @Volatile private var pending: Operation? = null
    private var verticalGoal: Int? = null
    private var verticalState: EditorEditState? = null

    private class Operation(
        val initial: EditorEditState,
        val deadline: Long,
        val callback: (EditorEditResult) -> Unit,
    ) {
        val delivered = AtomicBoolean(false)
        val callbackDelivered = AtomicBoolean(false)
        @Volatile var completion: EditorEditResult? = null
        @Volatile var timer: EditCancellation? = null
    }

    /** 認識済みキーは結果によらず呼び出し元で消費し、再配送しません。 */
    fun submit(command: EditCommand, callback: (EditorEditResult) -> Unit) {
        val initial = state()
        if (!usable(initial)) {
            deliver(callback, result(EditorEditResult.Outcome.STALE, EditorEditResult.Reason.INACTIVE))
            return
        }
        if (!busy.compareAndSet(false, true)) {
            deliver(callback, result(EditorEditResult.Outcome.BUSY))
            return
        }
        val operation = Operation(initial, clock() + TIMEOUT_MILLIS, callback)
        pending = operation
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

    override fun close() {
        active.set(false)
        pending?.let {
            it.timer?.cancel()
            finish(it, result(EditorEditResult.Outcome.STALE, EditorEditResult.Reason.INACTIVE), immediately = true)
        }
    }

    private fun execute(operation: Operation, command: EditCommand) {
        if (!check(operation)) return
        val first = read() ?: return unsupported(operation, EditorEditResult.Reason.UNAVAILABLE)
        if (!matchesSelection(first, operation.initial)) return unsupported(operation, EditorEditResult.Reason.INVALID_SNAPSHOT)
        val prepared = prepare(first, command, operation.initial)
            ?: return unsupported(operation, EditorEditResult.Reason.INSUFFICIENT_CONTEXT)
        if (!check(operation)) return
        // 同じ取得経路を使い、フォールバックによる別形式の混在を避けます。
        val second = read(first.source) ?: return unsupported(operation, EditorEditResult.Reason.UNAVAILABLE)
        if (first != second) {
            finish(operation, result(EditorEditResult.Outcome.STALE))
            verticalGoal = null
            return
        }
        if (!check(operation)) return
        val plan = prepared.plan
        val target = prepared.offset + plan.cursor
        if (!plan.changed) {
            verticalGoal = plan.preferredColumn
            verticalState = operation.initial
            finish(operation, EditorEditResult(EditorEditResult.Outcome.NO_CHANGE,
                expectedSelectionStart = target, expectedSelectionEnd = target))
            return
        }
        if (!beforeRequest(operation.initial, target)) {
            finish(operation, result(EditorEditResult.Outcome.STALE))
            return
        }
        if (!check(operation)) return
        val removed = plan.deletedRange
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
            val begin = prepared.offset - first.offset + removed.start
            val end = prepared.offset - first.offset + removed.end
            first.text.removeRange(begin, end)
        }
        val begin = first.offset.toLong() - after.offset
        val end = begin + expectedText.length
        val contentMatches = begin >= 0 && end <= after.text.length &&
            after.text.regionMatches(begin.toInt(), expectedText, 0, expectedText.length)
        if (!contentMatches || after.absoluteStart != target || after.absoluteEnd != target) {
            return fail(operation, EditorEditResult.Reason.POSTCHECK_MISMATCH)
        }
        verticalGoal = plan.preferredColumn
        verticalState = state().copy(selectionStart = target, selectionEnd = target)
        finish(operation, EditorEditResult(EditorEditResult.Outcome.APPLIED,
            expectedSelectionStart = target, expectedSelectionEnd = target))
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
        if (Build.VERSION.SDK_INT >= 31 && (source == Source.SURROUNDING || source == null)) {
            val value = connection.getSurroundingText(CONTEXT, CONTEXT, 0)
            if (value != null) return window(value.text, value.offset, value.selectionStart, value.selectionEnd, Source.SURROUNDING)
            return null
        }
        val request = ExtractedTextRequest().apply {
            hintMaxChars = Editing.EXTERNAL_LIMIT
            hintMaxLines = 0
        }
        val value = connection.getExtractedText(request, 0) ?: return null
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

    private fun prepare(window: Window, command: EditCommand, initial: EditorEditState): Prepared? {
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
        if (command != EditCommand.HOME &&
            (snapshot.selectionStart == text.length || snapshot.selectionEnd == text.length || plan.cursor == text.length)) return null
        val removed = plan.deletedRange
        if (removed != null && (removed.end == text.length || plan.cursor != removed.start)) return null
        if (removed == null && command == EditCommand.KILL_LINE && snapshot.selectionStart == snapshot.selectionEnd) return null
        val absoluteOffset = window.offset + start
        if (absoluteOffset > 0 && snapshot.selectionStart == snapshot.selectionEnd && snapshot.selectionEnd == 0 &&
            command in listOf(EditCommand.LEFT, EditCommand.BACKSPACE, EditCommand.WORD_BACKWARD, EditCommand.UP)) return null
        if (command == EditCommand.DOWN && !text.substring(snapshot.selectionEnd).any { it in "\r\n\u2028\u2029" }) return null
        if (absoluteOffset > 0 && command == EditCommand.UP && !text.substring(0, snapshot.selectionEnd).any { it in "\r\n\u2028\u2029" }) return null
        return Prepared(absoluteOffset, plan)
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
        if (failure) {
            failed.set(true)
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
