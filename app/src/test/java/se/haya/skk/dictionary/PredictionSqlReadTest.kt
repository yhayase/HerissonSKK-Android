package se.haya.skk.dictionary

import android.database.sqlite.SQLiteDatabase
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import se.haya.skk.core.PredictionCancellation
import se.haya.skk.core.PredictionCancelledException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class PredictionSqlReadTest {
    @Test fun `開いたSQLカーソルを中止して同じ実行スレッドを再利用できる`() {
        val database = SQLiteDatabase.create(null)
        val worker = Executors.newSingleThreadExecutor()
        val cancellation = PredictionCancellation()
        val cursorOpened = CountDownLatch(1)
        try {
            val result = worker.submit<Long> {
                cancellation.run {
                    predictionRead(database,
                        """WITH RECURSIVE numbers(n) AS (
                             SELECT 1 UNION ALL SELECT n+1 FROM numbers WHERE n<1000000000
                           ) SELECT sum(n) FROM numbers""".trimIndent(), emptyArray(),
                    ) { cursor ->
                        cursorOpened.countDown()
                        check(cursor.moveToFirst())
                        cursor.getLong(0)
                    }
                }
            }
            assertTrue("SQLカーソルが開かれませんでした", cursorOpened.await(5, TimeUnit.SECONDS))
            cancellation.cancel()
            val failure = assertThrows(ExecutionException::class.java) { result.get(5, TimeUnit.SECONDS) }
            assertTrue(failure.cause is PredictionCancelledException)

            // 中止したスコープと割り込み状態を次の検索へ持ち越しません。
            val next = worker.submit<Long> {
                assertTrue(!Thread.currentThread().isInterrupted)
                predictionRead(database, "SELECT 42", emptyArray()) { cursor ->
                    check(cursor.moveToFirst())
                    cursor.getLong(0)
                }
            }
            assertEquals(42L, next.get(5, TimeUnit.SECONDS))
        } finally {
            cancellation.cancel()
            worker.shutdownNow()
            worker.awaitTermination(5, TimeUnit.SECONDS)
            database.close()
        }
    }
}
