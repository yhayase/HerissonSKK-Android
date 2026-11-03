package se.haya.skk

import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.WindowInsets
import android.util.DisplayMetrics
import android.os.IBinder
import android.widget.FrameLayout
import android.widget.LinearLayout
import se.haya.skk.input.KeyboardColors

/** 入力接続を維持したまま、文字キーより上の入力先を覆う登録専用の子窓です。 */
internal class TouchRegistrationModal(context: Context, onCandidate: (CandidateTapTarget) -> Unit) {
    private val windows = context.getSystemService(WindowManager::class.java)
    val content = CandidateStatusView(context).apply {
        modalPresentation = true
        setOnCandidateTapListener(onCandidate)
    }
    val footer = content.registrationEditor.controlsView
    val panel = ModalPanel(context, content, footer).apply { clipToOutline = true }
    val root = FrameLayout(context).apply {
        contentDescription = "単語登録モーダル"
        isClickable = true
        isFocusable = false
        setBackgroundColor(0x66000000)
        addView(panel, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
    }
    private var attachedBox: Rect? = null
    private var attachedToken: android.os.IBinder? = null
    val isShowing: Boolean get() = attachedBox != null

    init {
        (footer.parent as? ViewGroup)?.removeView(footer)
        // フッターはスクロール本文と独立して、文字キーのすぐ上に残します。
        panel.addView(footer)
        footer.contentDescription = "単語登録の操作"
        footer.removeAllViews()
        footer.gravity = Gravity.CENTER_VERTICAL
        val editor = content.registrationEditor
        listOf(editor.cancelButton, editor.registerButton).forEach { button ->
            button.minHeight = dp(48)
            button.minimumHeight = dp(48)
            button.textSize = 17f
            footer.addView(button, LinearLayout.LayoutParams(0, dp(52), 1f))
        }
        footer.setPadding(dp(16), dp(8), dp(16), dp(8))
    }

    fun show(parent: View, candidateStrip: View, presentation: CandidateStatusPresentation, mode: String) {
        val token = parent.windowToken ?: return
        val location = IntArray(2).also(candidateStrip::getLocationOnScreen)
        // 候補の予約高はそのまま保ち、その下端までを覆って文字キーへ操作欄を寄せます。
        val box = bounds(viewport(parent), location[1] + candidateStrip.height)
        if (box.isEmpty) { dismiss(); return }
        configureAvailableHeight(box.height())
        content.show(presentation)
        listOf(content.registrationEditor.registerButton, content.registrationEditor.cancelButton).forEach {
            it.alpha = if (it.isEnabled) 1f else 0.38f
        }
        content.showRegistrationMode(mode, true)
        val colors = KeyboardColors.resolve(parent.context)
        panel.background = GradientDrawable().apply {
            setColor(colors.panelBackground)
            cornerRadii = floatArrayOf(dp(20).toFloat(), dp(20).toFloat(), dp(20).toFloat(), dp(20).toFloat(), 0f, 0f, 0f, 0f)
        }
        content.setBackgroundColor(android.graphics.Color.TRANSPARENT)
        val params = layoutParams(box, token)
        try {
            if (attachedToken != token || Build.VERSION.SDK_INT == 26 && attachedBox?.let {
                    it.left != box.left || it.top != box.top } == true) dismiss()
            if (isShowing) windows.updateViewLayout(root, params) else windows.addView(root, params)
            attachedBox = Rect(box)
            attachedToken = token
        } catch (_: WindowManager.BadTokenException) { dismiss() }
    }

    internal fun configureAvailableHeight(height: Int) {
        val compact = height < dp(240)
        panel.setPadding(0, if (compact) 0 else dp(12), 0, 0)
        footer.setPadding(dp(16), if (compact) 0 else dp(8), dp(16), if (compact) 0 else dp(8))
        listOf(content.registrationEditor.cancelButton, content.registrationEditor.registerButton).forEach {
            it.layoutParams = (it.layoutParams as LinearLayout.LayoutParams).apply {
                this.height = dp(if (compact) 48 else 52)
            }
        }
        content.setCompactRegistration(compact)
    }

    fun dismiss() {
        content.closeDetail()
        val wasShowing = isShowing
        attachedBox = null
        attachedToken = null
        if (wasShowing) windows.removeViewImmediate(root)
    }

    private fun dp(value: Int) = (value * root.resources.displayMetrics.density).toInt()

    companion object {
        private fun viewport(parent: View): Rect {
            val metrics = DisplayMetrics().apply { setTo(parent.resources.displayMetrics) }
            @Suppress("DEPRECATION")
            parent.display?.getRealMetrics(metrics)
            val display = Rect(0, 0, metrics.widthPixels, metrics.heightPixels)
            val visible = Rect().also(parent::getWindowVisibleDisplayFrame)
            val windowInsets = parent.rootWindowInsets
            val insets = if (Build.VERSION.SDK_INT >= 30 && windowInsets != null) {
                windowInsets.getInsetsIgnoringVisibility(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                    .let { Rect(it.left, it.top, it.right, it.bottom) }
            } else {
                @Suppress("DEPRECATION")
                Rect(windowInsets?.stableInsetLeft ?: 0, windowInsets?.stableInsetTop ?: 0,
                    windowInsets?.stableInsetRight ?: 0, windowInsets?.stableInsetBottom ?: 0).apply {
                    if (Build.VERSION.SDK_INT >= 28) windowInsets?.displayCutout?.let {
                        left = maxOf(left, it.safeInsetLeft)
                        top = maxOf(top, it.safeInsetTop)
                        right = maxOf(right, it.safeInsetRight)
                        bottom = maxOf(bottom, it.safeInsetBottom)
                    }
                }
            }
            return safeViewport(display, visible, insets)
        }

        internal fun safeViewport(display: Rect, visible: Rect, insets: Rect): Rect {
            val safe = Rect(display.left + insets.left, display.top + insets.top,
                display.right - insets.right, display.bottom - insets.bottom)
            if (!visible.isEmpty && !safe.intersect(visible)) safe.setEmpty()
            return safe
        }

        internal fun layoutParams(box: Rect, token: IBinder): WindowManager.LayoutParams =
            WindowManager.LayoutParams(box.width(), box.height(),
                WindowManager.LayoutParams.TYPE_APPLICATION_ATTACHED_DIALOG,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT).apply {
                this.token = token
                gravity = Gravity.TOP or Gravity.LEFT
                x = box.left
                y = box.top
                title = "SKK 単語登録"
                // 安全領域は自分で絶対座標へ反映し、窓管理側による切り欠き分の再移動を防ぎます。
                if (Build.VERSION.SDK_INT >= 30) {
                    setFitInsetsTypes(0)
                    layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                } else if (Build.VERSION.SDK_INT >= 28) {
                    layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                }
            }

        internal fun bounds(viewport: Rect, keyboardTop: Int): Rect = Rect(viewport.left, viewport.top,
            viewport.right, keyboardTop.coerceIn(viewport.top, viewport.bottom))
    }
}

/** 操作欄を先に測り、残った実寸だけを本文へ割り当てます。 */
internal class ModalPanel(context: Context, private val body: View, private val footer: View) : ViewGroup(context) {
    init {
        isClickable = true
        setPadding(0, (12 * resources.displayMetrics.density).toInt(), 0, 0)
        addView(body)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val available = MeasureSpec.getSize(heightMeasureSpec)
        val childWidth = MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY)
        footer.measure(childWidth, MeasureSpec.makeMeasureSpec((available - paddingTop).coerceAtLeast(0), MeasureSpec.AT_MOST))
        val bodySpace = (available - paddingTop - footer.measuredHeight).coerceAtLeast(0)
        body.measure(childWidth, MeasureSpec.makeMeasureSpec(bodySpace, MeasureSpec.AT_MOST))
        setMeasuredDimension(width, (paddingTop + body.measuredHeight + footer.measuredHeight).coerceAtMost(available))
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val footerTop = (height - footer.measuredHeight).coerceAtLeast(0)
        body.layout(0, paddingTop, width, footerTop)
        footer.layout(0, footerTop, width, height)
    }
}
