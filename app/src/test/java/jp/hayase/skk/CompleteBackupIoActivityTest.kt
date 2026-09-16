package jp.hayase.skk

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Looper
import android.widget.Button
import android.widget.TextView
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.util.concurrent.Executor
import jp.hayase.skk.dictionary.DictionaryManager
import jp.hayase.skk.dictionary.SQLiteDictionaryRepository
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

/** ContentResolver のストリーム境界へ障害を注入します。実プロバイダーの障害試験とは区別します。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 35])
class CompleteBackupIoActivityTest {
    private lateinit var repository: SQLiteDictionaryRepository
    private lateinit var manager: DictionaryManager
    private lateinit var controller: ActivityController<CompleteDictionaryBackupActivity>
    private lateinit var database: String
    private val context get() = RuntimeEnvironment.getApplication()
    private val activity get() = controller.get()
    private val uri = Uri.parse("content://backup-io-test/fixture")

    @Before fun setup() {
        database = "backup-io-${System.nanoTime()}.db"
        repository = SQLiteDictionaryRepository(context, database)
        val direct = Executor { it.run() }
        manager = DictionaryManager(repository, direct, direct).also { it.loadAsync() }
        CompleteDictionaryBackupActivity.managerFactoryForTest = { manager }
        controller = Robolectric.buildActivity(CompleteDictionaryBackupActivity::class.java).setup()
    }

    @After fun cleanup() {
        controller.destroy()
        CompleteDictionaryBackupActivity.managerFactoryForTest = null
        manager.close()
        context.deleteDatabase(database)
    }

    @Test fun `出力の書込失敗もclose失敗も成功表示にしない`() {
        for (failDuringWrite in listOf(true, false)) {
            val revision = repository.dictionaryRevision()
            var closed = false
            val stream = object : OutputStream() {
                override fun write(value: Int) { if (failDuringWrite) throw IOException("注入した書込失敗") }
                override fun close() {
                    closed = true
                    if (!failDuringWrite) throw IOException("注入した終了失敗")
                }
            }
            Shadows.shadowOf(activity.contentResolver).registerOutputStream(uri, stream)
            choose("exportButton")
            awaitStatus("保存に失敗しました")
            assertTrue(closed)
            assertFalse(status().contains("保存しました"))
            assertEquals(revision, repository.dictionaryRevision())
        }
    }

    @Test fun `復元元の読取失敗とclose失敗では確認画面へ進まない`() {
        val bytes = backup()
        for (failDuringRead in listOf(true, false)) {
            val revision = repository.dictionaryRevision()
            var closed = false
            val stream = object : ByteArrayInputStream(bytes) {
                override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                    if (failDuringRead) throw IOException("注入した読取失敗")
                    return super.read(buffer, offset, length)
                }
                override fun close() {
                    closed = true
                    if (!failDuringRead) throw IOException("注入した終了失敗")
                    super.close()
                }
            }
            Shadows.shadowOf(activity.contentResolver).registerInputStream(uri, stream)
            choose("restoreButton")
            assertTrue(status().contains("読み書きに失敗"))
            assertTrue(closed)
            assertEquals(revision, repository.dictionaryRevision())
            assertNull(ReflectionHelpers.getField<Any?>(activity, "prepared"))
        }
    }

    @Test fun `途中で終わる入力は全件検証に失敗し既存辞書を保持する`() {
        val revision = repository.dictionaryRevision()
        val bytes = backup()
        Shadows.shadowOf(activity.contentResolver).registerInputStream(uri, ByteArrayInputStream(bytes.copyOf(bytes.size / 2)))
        choose("restoreButton")
        assertTrue(status().contains("形式または内容が不正"))
        assertEquals(revision, repository.dictionaryRevision())
        assertNull(ReflectionHelpers.getField<Any?>(activity, "prepared"))
    }

    @Test fun `短い読取が繰り返されても全体が揃えば確認まで進み取消は無変更`() {
        val revision = repository.dictionaryRevision()
        val stream = object : ByteArrayInputStream(backup()) {
            override fun read(buffer: ByteArray, offset: Int, length: Int) = super.read(buffer, offset, minOf(length, 1))
        }
        Shadows.shadowOf(activity.contentResolver).registerInputStream(uri, stream)
        choose("restoreButton")
        assertNotNull(ReflectionHelpers.getField<Any?>(activity, "prepared"))
        assertEquals(revision, repository.dictionaryRevision())
        org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog()
            .getButton(android.app.AlertDialog.BUTTON_NEGATIVE).performClick()
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        assertEquals(revision, repository.dictionaryRevision())
        assertTrue(status().contains("復元を取り消しました"))
    }

    private fun choose(button: String) {
        ReflectionHelpers.getField<Button>(activity, button).performClick()
        val request = Shadows.shadowOf(activity).nextStartedActivityForResult.requestCode
        ReflectionHelpers.callInstanceMethod<Unit>(activity, "onActivityResult",
            ReflectionHelpers.ClassParameter.from(Int::class.javaPrimitiveType, request),
            ReflectionHelpers.ClassParameter.from(Int::class.javaPrimitiveType, Activity.RESULT_OK),
            ReflectionHelpers.ClassParameter.from(Intent::class.java, Intent().setData(uri)))
    }

    private fun backup() = ByteArrayOutputStream().also { repository.writeCompleteBackup(it, emptyList(), "test") }.toByteArray()
    private fun status() = ReflectionHelpers.getField<TextView>(activity, "status").text.toString()
    private fun awaitStatus(expected: String) {
        val end = System.nanoTime() + 5_000_000_000L
        while (System.nanoTime() < end) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            if (status().contains(expected)) return
            Thread.sleep(10)
        }
        fail("状態が更新されません: $expected / ${status()}")
    }
}
