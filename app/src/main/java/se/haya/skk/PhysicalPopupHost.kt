package se.haya.skk

import android.content.Context
import android.graphics.PixelFormat
import android.os.IBinder
import android.view.Gravity
import android.view.View
import android.view.WindowManager

/**
 * Android 13 以降の物理候補用に、文字キーの表示要求と独立した窓を保持します。
 * この版以降では非タッチの IME 窓が主入力窓を置き換えず、候補専用表示の null トークン経路も避けられます。
 */
@android.annotation.TargetApi(33)
internal class PhysicalPopupHost(context: Context, onReady: () -> Unit) {
    private val windows = context.getSystemService(WindowManager::class.java)
    val view = View(context).apply {
        isFocusable = false
        isClickable = false
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> onReady() }
    }
    private var token: IBinder? = null

    fun show(imeToken: IBinder): Boolean {
        if (token === imeToken) return true
        dismiss()
        val params = WindowManager.LayoutParams(1, 1,
            WindowManager.LayoutParams.TYPE_INPUT_METHOD,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT).apply {
            token = imeToken
            gravity = Gravity.TOP or Gravity.LEFT
            title = "SKK 物理候補ホスト"
            setFitInsetsTypes(0)
        }
        return try {
            windows.addView(view, params)
            token = imeToken
            true
        } catch (_: WindowManager.BadTokenException) {
            false
        }
    }

    fun dismiss() {
        if (token != null) windows.removeViewImmediate(view)
        token = null
    }
}
