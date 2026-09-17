package jp.hayase.skk.testeditor

import android.app.Activity
import android.os.Build
import android.view.WindowInsets
import android.view.View

@Suppress("DEPRECATION")
internal fun Activity.applySystemInsets() {
    if (Build.VERSION.SDK_INT < 30) {
        window.decorView.systemUiVisibility = window.decorView.systemUiVisibility or
            View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
    } else {
        window.insetsController?.setSystemBarsAppearance(
            android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
                android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS,
            android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
                android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS)
    }
    window.decorView.setOnApplyWindowInsetsListener { view, insets ->
        if (Build.VERSION.SDK_INT >= 30) {
            val bars = insets.getInsets(WindowInsets.Type.systemBars())
            // Android 15 の edge-to-edge 強制時だけ、アプリ側でも IME inset を消費します。
            val bottom = if (Build.VERSION.SDK_INT >= 35)
                maxOf(bars.bottom, insets.getInsets(WindowInsets.Type.ime()).bottom)
            else bars.bottom
            view.setPadding(bars.left, bars.top, bars.right, bottom)
        } else {
            view.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop,
                insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
        }
        insets
    }
}
