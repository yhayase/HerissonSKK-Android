package se.haya.skk

import android.os.Binder
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputBinding
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 35])
class ConfigurationRestartIdentityTest {
    private fun editor(id: Int = 7) = EditorInfo().apply {
        packageName = "se.haya.editor"; fieldId = id; inputType = 1; imeOptions = 6
    }
    @Test fun `同じ欄の新しい情報ラッパーだけを短時間受け付ける`() {
        val token = Binder()
        val window = Binder()
        val binding = InputBinding(null, token, 12, 34)
        val identity = ConfigurationRestartIdentity.capture(editor(), binding, window, 100)!!
        assertTrue(identity.matches(editor(), binding, window, 101))
        assertFalse(identity.matches(editor(8), binding, window, 101))
        assertFalse(identity.matches(editor(), InputBinding(null, Binder(), 12, 34), window, 101))
        assertFalse(identity.matches(editor(), InputBinding(null, token, 13, 34), window, 101))
        assertFalse(identity.matches(editor(), binding, Binder(), 101))
        assertFalse(identity.matches(editor(), binding, window, 1_101))
        assertFalse(identity.matches(editor(), binding, window, 99))
        assertFalse(identity.matches(editor().apply { inputType = 129 }, binding, window, 101))
    }
    @Test fun `接続または窓の識別情報がなければ保存しない`() {
        assertNull(ConfigurationRestartIdentity.capture(editor(), null, Binder(), 0))
        assertNull(ConfigurationRestartIdentity.capture(editor(), InputBinding(null, Binder(), 1, 1), null, 0))
    }
}
