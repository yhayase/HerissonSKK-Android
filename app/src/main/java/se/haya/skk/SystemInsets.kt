package se.haya.skk

import android.app.Activity
import android.os.Build
import android.view.WindowInsets
import android.view.View
import com.google.android.material.color.MaterialColors

@Suppress("DEPRECATION")
internal fun Activity.applySystemInsets() {
    val background = MaterialColors.getColor(this, android.R.attr.colorBackground, android.graphics.Color.BLACK)
    // 設定テーマはバーと透明なバーの背面を colorSurface に統一しています。
    val lightBars = MaterialColors.isColorLight(MaterialColors.getColor(
        this,
        com.google.android.material.R.attr.colorSurface,
        background,
    ))
    val lightMask = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
    if (Build.VERSION.SDK_INT < 30) {
        window.decorView.systemUiVisibility = if (lightBars) {
            window.decorView.systemUiVisibility or lightMask
        } else {
            window.decorView.systemUiVisibility and lightMask.inv()
        }
    } else {
        val appearanceMask = android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
            android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
        window.insetsController?.setSystemBarsAppearance(
            if (lightBars) appearanceMask else 0,
            appearanceMask,
        )
    }
    // 旧 API は decor と adjustResize が inset を反映するため、重ねて余白を加えません。
    if (Build.VERSION.SDK_INT < 30) return
    window.decorView.setOnApplyWindowInsetsListener { view, insets ->
        val bars = insets.getInsets(WindowInsets.Type.systemBars())
        // Android 15 の edge-to-edge 強制時は IME に隠れる領域も入力欄から除きます。
        val bottom = if (Build.VERSION.SDK_INT >= 35)
            maxOf(bars.bottom, insets.getInsets(WindowInsets.Type.ime()).bottom)
        else bars.bottom
        view.setPadding(bars.left, bars.top, bars.right, bottom)
        insets
    }
}
