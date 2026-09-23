package se.haya.skk

import android.text.InputType
import android.os.Binder
import android.view.View
import android.view.InputDevice
import android.view.KeyEvent
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implements
import org.robolectric.annotation.Implementation
import org.robolectric.shadows.ShadowInputDevice
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 35])
class KeyboardVisibilityServiceTest {
    @Implements(InputDevice::class)
    class ConnectedPhysicalKeyboard : ShadowInputDevice() {
        companion object {
            @JvmStatic @Implementation fun getDeviceIds(): IntArray = intArrayOf(1)
            @JvmStatic @Implementation fun getDevice(id: Int): InputDevice? =
                if (id == 1) ShadowInputDevice.makeInputDeviceNamed("試験用キーボード").also {
                    ReflectionHelpers.setField(it, "mSources", InputDevice.SOURCE_KEYBOARD)
                    ReflectionHelpers.setField(it, "mKeyboardType", InputDevice.KEYBOARD_TYPE_ALPHABETIC)
                } else null
        }
    }

    @Test @Config(shadows = [ConnectedPhysicalKeyboard::class])
    fun `戻るの押下中は物理ホストを保持し離した時に閉じる`() {
        val controller = Robolectric.buildService(SkkInputMethodService::class.java).create()
        val service = controller.get()
        val state = KeyEvent.DispatcherState()
        try {
            ReflectionHelpers.setField(service, "physicalHostAllowed", true)
            ReflectionHelpers.setField(service, "physicalHostRequested", true)
            assertTrue(KeyEvent(1, 1, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK, 0)
                .dispatch(service, state, service))
            assertTrue(ReflectionHelpers.getField<Boolean>(service, "physicalHostRequested"))
            assertTrue(KeyEvent(1, 2, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK, 0)
                .dispatch(service, state, service))
            assertFalse(ReflectionHelpers.getField<Boolean>(service, "physicalHostRequested"))
            assertFalse(ReflectionHelpers.getField<Boolean>(service, "physicalHostAllowed"))
        } finally {
            controller.destroy()
        }
    }

    @Test @Config(shadows = [ConnectedPhysicalKeyboard::class])
    fun `取り消された戻るキーは物理ホストを閉じない`() {
        val controller = Robolectric.buildService(SkkInputMethodService::class.java).create()
        val service = controller.get()
        val state = KeyEvent.DispatcherState()
        try {
            ReflectionHelpers.setField(service, "physicalHostAllowed", true)
            ReflectionHelpers.setField(service, "physicalHostRequested", true)
            KeyEvent(1, 1, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK, 0)
                .dispatch(service, state, service)
            val up = KeyEvent(1, 2, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK, 0)
            KeyEvent.changeFlags(up, KeyEvent.FLAG_CANCELED).dispatch(service, state, service)
            assertTrue(ReflectionHelpers.getField<Boolean>(service, "physicalHostRequested"))
        } finally {
            controller.destroy()
        }
    }

    @Test @Config(sdk = [35]) fun `窓の準備時に失敗した物理ホストを再接続する`() {
        val controller = Robolectric.buildService(SkkInputMethodService::class.java).create()
        val service = controller.get()
        val host = RecordingPhysicalHost(service) { it == 2 }
        var token: Binder? = null
        try {
            ReflectionHelpers.setField(service, "physicalHostAllowed", true)
            ReflectionHelpers.setField(service, "physicalHostVisibility", { true })
            ReflectionHelpers.setField(service, "physicalPopupHostFactory", { host })
            ReflectionHelpers.setField(service, "physicalHostWindowToken", { token })

            service.onWindowShown() // トークンなし: addView を呼ばない。
            assertEquals(0, host.showCalls)
            assertFalse(ReflectionHelpers.getField<Boolean>(service, "physicalHostRequested"))

            token = Binder()
            service.onWindowShown() // addView 失敗: 次の窓準備まで未接続のまま。
            assertEquals(1, host.showCalls)
            assertFalse(ReflectionHelpers.getField<Boolean>(service, "physicalHostRequested"))

            service.onWindowShown() // 窓準備後の再試行が成功する。
            assertEquals(2, host.showCalls)
            assertTrue(ReflectionHelpers.getField<Boolean>(service, "physicalHostRequested"))

            ReflectionHelpers.setField(service, "physicalHostAllowed", false)
            ReflectionHelpers.setField(service, "physicalHostRequested", false)
            service.onWindowShown()
            assertEquals(2, host.showCalls)
        } finally {
            controller.destroy()
        }
    }

    @Test fun `画面入力の表示要求は有効な入力欄だけを受け入れる`() {
        val connection = Connection()
        assertTrue(acceptsTouchShowRequest(editor(InputType.TYPE_CLASS_TEXT), connection, true))
        assertTrue(acceptsTouchShowRequest(editor(InputType.TYPE_CLASS_NUMBER or
            InputType.TYPE_NUMBER_VARIATION_PASSWORD), connection, true))
        assertTrue(acceptsTouchShowRequest(editor(InputType.TYPE_CLASS_TEXT or
            InputType.TYPE_TEXT_VARIATION_PASSWORD), connection, true))
        assertFalse(acceptsTouchShowRequest(editor(InputType.TYPE_NULL), connection, true))
        assertFalse(acceptsTouchShowRequest(null, connection, true))
        assertFalse(acceptsTouchShowRequest(editor(InputType.TYPE_CLASS_TEXT), null, true))
        assertFalse(acceptsTouchShowRequest(editor(InputType.TYPE_CLASS_TEXT), connection, false))
    }

    @Test fun `非同期のセッション開始前でもAndroidの有効な表示要求を受け入れる`() {
        val preferences = RuntimeEnvironment.getApplication()
            .getSharedPreferences("settings", 0)
        preferences.edit().putBoolean("hide_touch_keyboard_with_hardware", false).commit()
        val controller = Robolectric.buildService(SkkInputMethodService::class.java).create()
        val service = controller.get()
        try {
            val connection = Connection()
            ReflectionHelpers.setField(service, "mInputConnection", connection)
            ReflectionHelpers.setField(service, "mStartedInputConnection", connection)
            ReflectionHelpers.setField(service, "mInputEditorInfo",
                editor(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD))
            assertTrue(service.onShowInputRequested(0, false))

            ReflectionHelpers.setField(service, "mInputEditorInfo", editor(InputType.TYPE_NULL))
            assertFalse(service.onShowInputRequested(0, false))
        } finally {
            controller.destroy()
            preferences.edit().remove("hide_touch_keyboard_with_hardware").commit()
        }
    }

    @Test fun `物理表示の候補ホストは測定用表示を初期化して高さを持たない`() {
        val controller = Robolectric.buildService(SkkInputMethodService::class.java).create()
        val service = controller.get()
        try {
            val host = service.onCreateCandidatesView()
            assertNotNull(ReflectionHelpers.getField<CandidateStatusView?>(service, "candidateStatusView"))
            host.measure(View.MeasureSpec.makeMeasureSpec(320, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(640, View.MeasureSpec.AT_MOST))
            assertEquals(0, host.measuredHeight)
            assertFalse(host.isClickable)
            assertFalse(host.isFocusable)
        } finally {
            controller.destroy()
        }
    }

    @Test fun `閉じた物理ホストは受動更新では許可されず実入力だけが再開できる`() {
        val controller = Robolectric.buildService(SkkInputMethodService::class.java).create()
        val service = controller.get()
        try {
            ReflectionHelpers.setField(service, "physicalHostAllowed", true)
            ReflectionHelpers.setField(service, "physicalHostRequested", true)
            service.onWindowHidden()
            assertFalse(ReflectionHelpers.getField<Boolean>(service, "physicalHostAllowed"))
            assertFalse(ReflectionHelpers.getField<Boolean>(service, "physicalHostRequested"))

            service.onUpdateSelection(0, 0, 0, 0, -1, -1)
            assertFalse(ReflectionHelpers.getField<Boolean>(service, "physicalHostAllowed"))
            assertTrue(shouldRestorePhysicalHostForKey(touchCharacters = false,
                active = true, protectedInput = false))
            assertFalse(shouldRestorePhysicalHostForKey(touchCharacters = true,
                active = true, protectedInput = false))
            assertFalse(shouldRestorePhysicalHostForKey(touchCharacters = false,
                active = true, protectedInput = true))
        } finally {
            controller.destroy()
        }
    }

    @Test @Config(sdk = [35]) fun `予測型戻る操作は物理ホストを閉じて再表示を許可しない`() {
        val controller = Robolectric.buildService(SkkInputMethodService::class.java).create()
        val service = controller.get()
        try {
            ReflectionHelpers.setField(service, "physicalHostAllowed", true)
            ReflectionHelpers.setField(service, "physicalHostRequested", true)

            service.handlePhysicalHostBack()

            assertFalse(ReflectionHelpers.getField<Boolean>(service, "physicalHostAllowed"))
            assertFalse(ReflectionHelpers.getField<Boolean>(service, "physicalHostRequested"))
        } finally {
            controller.destroy()
        }
    }

    @Test fun `同じ入力欄の再開始は閉じた物理ホストを再許可しない`() {
        assertFalse(physicalHostAllowedForStartedInput(restarting = true,
            previouslyAllowed = false))
        assertTrue(physicalHostAllowedForStartedInput(restarting = true,
            previouslyAllowed = true))
        assertTrue(physicalHostAllowedForStartedInput(restarting = false,
            previouslyAllowed = false))
    }

    private fun editor(type: Int) = EditorInfo().apply { inputType = type }

    private class Connection : BaseInputConnection(
        View(RuntimeEnvironment.getApplication()), true,
    )

    private class RecordingPhysicalHost(
        context: android.content.Context,
        private val result: (Int) -> Boolean,
    ) : PhysicalPopupHostPort {
        override val view = View(context)
        var showCalls = 0
        override fun show(imeToken: android.os.IBinder): Boolean = result(++showCalls)
        override fun dismiss() = Unit
    }
}
