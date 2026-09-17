package jp.hayase.skk

import android.content.Context
import android.content.Intent
import android.text.InputType
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 35])
class ServiceConfigurationTest {
    @Test fun passwordVariantsAreProtected() {
        for (variation in listOf(InputType.TYPE_TEXT_VARIATION_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD, InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD)) {
            assertTrue(SkkInputMethodService.isPassword(InputType.TYPE_CLASS_TEXT or variation))
        }
        assertTrue(SkkInputMethodService.isPassword(InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD))
        assertFalse(SkkInputMethodService.isPassword(InputType.TYPE_CLASS_TEXT))
        for (variation in listOf(InputType.TYPE_TEXT_VARIATION_URI,
            InputType.TYPE_TEXT_VARIATION_WEB_EDIT_TEXT, InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS)) {
            assertFalse(SkkInputMethodService.isPassword(InputType.TYPE_CLASS_TEXT or variation or
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS))
        }
    }

    @Test fun imeRequiresSystemBindingPermissionAndHasNoNetworkPermission() {
        val context = RuntimeEnvironment.getApplication()
        val service = context.packageManager.queryIntentServices(Intent("android.view.InputMethod").setPackage(context.packageName), 0).single().serviceInfo
        assertEquals("android.permission.BIND_INPUT_METHOD", service.permission)
        val permissions = context.packageManager.getPackageInfo(context.packageName, android.content.pm.PackageManager.GET_PERMISSIONS).requestedPermissions
        assertFalse(permissions?.contains("android.permission.INTERNET") == true)
    }

    @Test fun setupScreenCanOpen() {
        Robolectric.buildActivity(SettingsActivity::class.java).setup().use { }
    }
}
