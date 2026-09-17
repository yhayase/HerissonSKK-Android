package jp.hayase.skk

import java.io.ByteArrayInputStream
import jp.hayase.skk.dictionary.DictionaryFreshness
import jp.hayase.skk.dictionary.DictionaryManagerStatus
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class DictionarySettingsActivityTest {
    @Test
    fun `取り込みピッカー状態を再生成後に一度だけ消費する`() {
        val original = DictionaryPickerState().apply { begin(DictionaryPickerOperation.IMPORT) }

        val restored = DictionaryPickerState.restore(original.savedValue())

        assertEquals(DictionaryPickerOperation.IMPORT, restored.pending)
        assertTrue(restored.isPending)
        assertTrue(restored.consume(DictionaryPickerOperation.IMPORT))
        assertFalse(restored.isPending)
        assertFalse(restored.consume(DictionaryPickerOperation.IMPORT))
    }

    @Test
    fun `書き出しピッカー状態は別の結果で解除しない`() {
        val restored = DictionaryPickerState.restore(
            DictionaryPickerState().apply { begin(DictionaryPickerOperation.EXPORT) }.savedValue(),
        )

        assertFalse(restored.consume(DictionaryPickerOperation.IMPORT))
        assertEquals(DictionaryPickerOperation.EXPORT, restored.pending)
        assertTrue(restored.consume(DictionaryPickerOperation.EXPORT))
    }

    @Test
    fun `上限以内のストリームだけを読み込む`() {
        val bytes = ByteArray(32) { it.toByte() }

        assertArrayEquals(bytes, readBounded(ByteArrayInputStream(bytes), 32))
    }

    @Test
    fun `上限を一バイトでも超えるストリームを拒否する`() {
        val bytes = ByteArray(33) { it.toByte() }

        assertThrows(DictionaryFileTooLargeException::class.java) {
            readBounded(ByteArrayInputStream(bytes), 32)
        }
    }

    @Test
    fun `処理中の辞書だけを処理中として表示する`() {
        val operation = DictionarySourceOperation.Processing("updating")

        assertEquals(
            DictionarySourceRowStatus.PROCESSING,
            sourceRowStatus("updating", DictionarySourceAvailability.AVAILABLE, operation),
        )
        assertEquals(
            DictionarySourceRowStatus.AVAILABLE,
            sourceRowStatus("older", DictionarySourceAvailability.AVAILABLE, operation),
        )
    }

    @Test
    fun `失敗した辞書だけを失敗表示し別の辞書の利用可能状態を保つ`() {
        val operation = DictionarySourceOperation.Failed("broken")

        assertEquals(
            DictionarySourceRowStatus.FAILED,
            sourceRowStatus("broken", DictionarySourceAvailability.AVAILABLE, operation),
        )
        assertEquals(
            DictionarySourceRowStatus.AVAILABLE,
            sourceRowStatus("older", DictionarySourceAvailability.AVAILABLE, operation),
        )
        assertEquals(
            DictionarySourceRowStatus.UNKNOWN,
            sourceRowStatus("older", DictionarySourceAvailability.UNKNOWN, operation),
        )
    }

    @Test
    fun `古い世代しか公開されていない準備完了状態では辞書を利用可能と表示しない`() {
        assertEquals(
            DictionarySourceAvailability.UNKNOWN,
            sourceAvailabilityForStatus(DictionaryManagerStatus.Ready(DictionaryFreshness.STALE)),
        )
    }
}
