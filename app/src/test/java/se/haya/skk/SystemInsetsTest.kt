package se.haya.skk

import android.app.Activity
import android.graphics.Insets
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.view.View
import android.view.WindowInsets
import com.google.android.material.color.MaterialColors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 35])
class SystemInsetsTest {
    @Test @Config(sdk = [35]) fun `画面端まで描く設定画面はIMEの高さを避け閉じたら余白を戻す`() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = controller.get()
        activity.applySystemInsets()
        val decor = activity.window.decorView
        val bars = WindowInsets.Builder()
            .setInsets(WindowInsets.Type.systemBars(), Insets.of(0, 24, 0, 24))
        decor.dispatchApplyWindowInsets(bars
            .setInsets(WindowInsets.Type.ime(), Insets.of(0, 0, 0, 259)).build())
        assertEquals(259, decor.paddingBottom)
        assertEquals(24, decor.paddingTop)
        decor.dispatchApplyWindowInsets(bars
            .setInsets(WindowInsets.Type.ime(), Insets.NONE).build())
        assertEquals(24, decor.paddingBottom)
        controller.pause().stop().destroy()
    }

    @Test @Config(qualifiers = "notnight")
    fun `明るいMaterial背景ではシステムバーに暗いアイコンを使う`() {
        val controller = themedActivity()
        val activity = controller.get()
        activity.window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN

        activity.applySystemInsets()

        assertBarBackgroundAndThemeContrast(activity, true)
        assertEquals(lightSystemBarMask(), systemBarAppearance(activity))
        assertTrue(activity.window.decorView.systemUiVisibility and View.SYSTEM_UI_FLAG_FULLSCREEN != 0)
        controller.pause().stop().destroy()
    }

    @Test @Config(qualifiers = "night")
    fun `暗いMaterial背景ではシステムバーの暗いアイコン指定を解除する`() {
        val controller = themedActivity()
        val activity = controller.get()
        activity.window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR

        activity.applySystemInsets()

        assertBarBackgroundAndThemeContrast(activity, false)
        assertEquals(0, systemBarAppearance(activity))
        assertTrue(activity.window.decorView.systemUiVisibility and View.SYSTEM_UI_FLAG_FULLSCREEN != 0)
        controller.pause().stop().destroy()
    }

    private fun themedActivity(): org.robolectric.android.controller.ActivityController<Activity> =
        Robolectric.buildActivity(Activity::class.java).also {
            it.get().setTheme(R.style.Theme_Skk_Settings)
            it.setup()
        }

    @Suppress("DEPRECATION")
    private fun assertBarBackgroundAndThemeContrast(activity: Activity, light: Boolean) {
        val surface = MaterialColors.getColor(activity,
            com.google.android.material.R.attr.colorSurface, "SystemInsetsTest")
        val attributes = activity.obtainStyledAttributes(intArrayOf(
            android.R.attr.windowBackground,
            android.R.attr.statusBarColor,
            android.R.attr.navigationBarColor,
            android.R.attr.windowLightStatusBar,
            android.R.attr.windowLightNavigationBar,
        ))
        try {
            // 透明なバーの背面と、ダイアログが継承する色・アイコン指定も検証します。
            assertEquals(surface, (attributes.getDrawable(0) as ColorDrawable).color)
            assertEquals(surface, attributes.getColor(1, 0))
            assertEquals(surface, attributes.getColor(2, 0))
            assertEquals(light, attributes.getBoolean(3, !light))
            // ナビゲーションバーのテーマ属性は API 27 以降です。API 26 は実際のフラグを検証します。
            if (Build.VERSION.SDK_INT >= 27) {
                assertEquals(light, attributes.getBoolean(4, !light))
            }
            assertEquals(light, MaterialColors.isColorLight(surface))
            if (Build.VERSION.SDK_INT < 35) {
                assertEquals(surface, activity.window.statusBarColor)
                assertEquals(surface, activity.window.navigationBarColor)
            }
        } finally {
            attributes.recycle()
        }
    }

    private fun lightSystemBarMask(): Int = if (Build.VERSION.SDK_INT < 30) {
        View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
    } else {
        android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
            android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
    }

    private fun systemBarAppearance(activity: Activity): Int = if (Build.VERSION.SDK_INT < 30) {
        activity.window.decorView.systemUiVisibility and lightSystemBarMask()
    } else {
        checkNotNull(activity.window.insetsController).systemBarsAppearance and lightSystemBarMask()
    }
}
