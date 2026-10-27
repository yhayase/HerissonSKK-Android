package se.haya.skk.dictionary

import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.os.CancellationSignal
import android.os.OperationCanceledException
import se.haya.skk.core.PredictionCancellation

/** 中止通知をカーソルの寿命へ結び、読み終えた SQL のリスナーを必ず解除します。 */
internal inline fun <T> predictionRead(
    database: SQLiteDatabase,
    sql: String,
    args: Array<String>,
    block: (Cursor) -> T,
): T {
    val token = PredictionCancellation.current
    token?.throwIfCancelled()
    val signal = CancellationSignal()
    val listener = token?.onCancel(signal::cancel)
    try {
        return database.rawQuery(sql, args, signal).use { cursor ->
            token?.throwIfCancelled()
            block(cursor).also { token?.throwIfCancelled() }
        }
    } catch (cancelled: OperationCanceledException) {
        token?.throwIfCancelled()
        throw cancelled
    } finally {
        listener?.close()
    }
}
