package se.haya.skk

import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.CursorAnchorInfo
import android.widget.TextView

/** IME の予約領域と独立した子窓です。入力先のフォーカスと窓外のタッチを奪いません。 */
internal class PhysicalInputPopup(context: Context, onCandidate: (CandidateTapTarget) -> Unit) {
    private val windows = context.getSystemService(WindowManager::class.java)
    val content = CandidateStatusView(context).apply {
        physicalPresentation = true
        setOnCandidateTapListener(onCandidate)
    }
    private val mode = TextView(context).apply {
        textSize = 20f
        setPadding(8, 4, 8, 4)
        setBackgroundColor(0xfff1f3f4.toInt())
        isFocusable = false
    }
    private val handler = Handler(Looper.getMainLooper())
    private val attached = mutableMapOf<View, Rect>()
    private val hideMode = Runnable { remove(mode) }
    private var lastMode: String? = null
    private var currentParent: View? = null
    private var currentAnchor: CursorAnchorInfo? = null
    init {
        content.onContentSizeChanged = { currentParent?.let { layoutContent(it, currentAnchor) } }
    }

    fun dismiss() {
        handler.removeCallbacks(hideMode)
        attached.keys.toList().forEach(::remove)
        lastMode = null
        currentParent = null
        currentAnchor = null
    }

    fun show(parent: View, presentation: CandidateStatusPresentation, modeText: String,
             info: CursorAnchorInfo?, showContent: Boolean) {
        val viewport = viewport(parent)
        val anchor = anchor(info, viewport)
        currentParent = parent
        currentAnchor = info
        if (showContent) {
            content.show(presentation)
            content.showRegistrationMode(modeText, presentation.expandedStatus)
            layoutContent(parent, info)
        } else remove(content)
        if (presentation.expandedStatus) {
            remove(mode)
            lastMode = modeText
            return
        }
        if (modeText != lastMode || mode in attached) {
            val changed = modeText != lastMode
            lastMode = modeText
            mode.text = modeText
            mode.measure(View.MeasureSpec.makeMeasureSpec(viewport.width(), View.MeasureSpec.AT_MOST),
                View.MeasureSpec.makeMeasureSpec(viewport.height(), View.MeasureSpec.AT_MOST))
            val box = if (anchor == null) Rect(viewport.right - mode.measuredWidth,
                viewport.bottom - mode.measuredHeight, viewport.right, viewport.bottom)
            else place(anchor, viewport, mode.measuredWidth, mode.measuredHeight)
            attach(parent, mode, box)
            if (changed) {
                handler.removeCallbacks(hideMode)
                handler.postDelayed(hideMode, 800)
            }
        }
    }

    private fun layoutContent(parent: View, info: CursorAnchorInfo?) {
        val viewport = viewport(parent)
        val width = minOf(viewport.width(),
            (CandidateStatusView.PHYSICAL_POPUP_WIDTH_DP * parent.resources.displayMetrics.density).toInt())
        content.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(viewport.height(), View.MeasureSpec.AT_MOST))
        attach(parent, content, place(anchor(info, viewport), viewport, width, content.measuredHeight))
    }

    private fun attach(parent: View, view: View, box: Rect) {
        val token = parent.windowToken ?: return
        if (box.width() <= 0 || box.height() <= 0) return
        val params = WindowManager.LayoutParams(box.width(), box.height(),
            WindowManager.LayoutParams.TYPE_APPLICATION_ATTACHED_DIALOG,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                (if (view === mode) WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE else 0),
            PixelFormat.TRANSLUCENT).apply {
            this.token = token
            gravity = Gravity.TOP or Gravity.LEFT
            x = box.left
            y = box.top
            title = "SKK 入力ポップアップ"
            if (Build.VERSION.SDK_INT >= 30) setFitInsetsTypes(0)
        }
        try {
            val previous = attached[view]
            // Android 8.0 は窓の移動後も古い座標をアクセシビリティへ返すため、移動時だけ窓を付け直します。
            if (Build.VERSION.SDK_INT == 26 && previous != null &&
                (previous.left != box.left || previous.top != box.top)) remove(view)
            if (view in attached) windows.updateViewLayout(view, params)
            else windows.addView(view, params)
            attached[view] = Rect(box)
        } catch (_: WindowManager.BadTokenException) {
            remove(view)
        }
    }

    private fun remove(view: View) {
        if (attached.remove(view) != null) windows.removeViewImmediate(view)
    }

    companion object {
        internal fun viewport(parent: View): Rect {
            val metrics = parent.resources.displayMetrics
            val visible = Rect().also(parent::getWindowVisibleDisplayFrame)
            return Rect(0, visible.top.coerceAtLeast(0), metrics.widthPixels,
                metrics.heightPixels - (if (Build.VERSION.SDK_INT >= 30)
                    parent.rootWindowInsets?.getInsets(android.view.WindowInsets.Type.systemBars())?.bottom ?: 0
                else @Suppress("DEPRECATION") (parent.rootWindowInsets?.stableInsetBottom ?: 0)))
        }

        internal fun anchor(info: CursorAnchorInfo?, viewport: Rect): RectF? {
            if (info == null || info.selectionStart != info.selectionEnd || info.selectionStart < 0) return null
            val flags = info.insertionMarkerFlags
            if (flags and CursorAnchorInfo.FLAG_HAS_VISIBLE_REGION == 0 ||
                flags and CursorAnchorInfo.FLAG_HAS_INVISIBLE_REGION != 0) return null
            val result = RectF(info.insertionMarkerHorizontal, info.insertionMarkerTop,
                info.insertionMarkerHorizontal, info.insertionMarkerBottom)
            info.matrix.mapRect(result)
            if (!result.left.isFinite() || !result.top.isFinite() || !result.bottom.isFinite() ||
                result.bottom <= result.top || result.left < viewport.left || result.left > viewport.right ||
                result.top < viewport.top || result.bottom > viewport.bottom) return null
            return result
        }

        /** 下、上、重なりの順で選びます。座標なしでも左上へ必ず表示します。 */
        internal fun place(anchor: RectF?, viewport: Rect, width: Int, height: Int): Rect {
            val w = width.coerceIn(0, viewport.width().coerceAtLeast(0))
            val h = height.coerceIn(0, viewport.height().coerceAtLeast(0))
            val x = (anchor?.left?.toInt() ?: viewport.left).coerceIn(viewport.left, viewport.right - w)
            val y = when {
                anchor == null -> viewport.top
                anchor.bottom + h <= viewport.bottom -> anchor.bottom.toInt()
                anchor.top - h >= viewport.top -> anchor.top.toInt() - h
                else -> anchor.top.toInt().coerceIn(viewport.top, viewport.bottom - h)
            }
            return Rect(x, y, x + w, y + h)
        }
    }
}
