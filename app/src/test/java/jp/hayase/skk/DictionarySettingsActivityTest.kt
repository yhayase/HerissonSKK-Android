package jp.hayase.skk

import java.io.ByteArrayInputStream
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

        assertThrows(IllegalArgumentException::class.java) {
            readBounded(ByteArrayInputStream(bytes), 32)
        }
    }
}
