package jp.hayase.skk.testeditor

import androidx.test.ext.junit.runners.AndroidJUnit4
import jp.hayase.skk.core.EditableBuffer
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/** JVM と同じ ICU データを APK に含め、Android の実行環境でも読み込めることを確認します。 */
@RunWith(AndroidJUnit4::class)
class CoreUnicodeTest {
    @Test fun clustersAreDeletedAsUnitsOnAndroid() {
        for (cluster in listOf("か\u3099", "👩🏽‍💻", "🇯🇵", "\u0915\u094D\u0937")) {
            val buffer = EditableBuffer("前$cluster")
            buffer.backspace()
            assertEquals("前", buffer.text)
        }
    }

    @Test fun deletionAndCrlfNavigationKeepValidCursorOnAndroid() {
        val buffer = EditableBuffer("\u1100x\u1161", 2)
        buffer.backspace()
        assertEquals(2, buffer.cursor)
        buffer.moveLeft()
        assertEquals(0, buffer.cursor)
        val lines = EditableBuffer("a\r\nb", 0)
        lines.moveEnd()
        assertEquals(1, lines.cursor)
        lines.moveRight()
        assertEquals(3, lines.cursor)
    }
}
