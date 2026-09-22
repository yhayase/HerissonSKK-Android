package se.haya.skk

import android.text.InputType
import android.view.View
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
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 35])
class KeyboardVisibilityServiceTest {
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
}
