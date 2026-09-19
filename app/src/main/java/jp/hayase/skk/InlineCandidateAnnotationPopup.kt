package jp.hayase.skk

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.os.Build
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.CursorAnchorInfo
import android.widget.TextView
import kotlin.math.roundToInt

/** 注釈は IME の子窓だけに描画し、入力先の本文には送信しません。 */
internal class InlineCandidateAnnotationPopup(context: Context) {
    private val windows = context.getSystemService(WindowManager::class.java)
    private val density = context.resources.displayMetrics.density
    private val label = TextView(context).apply {
        id = R.id.inline_candidate_annotation
        textSize = 12f
        setTextColor(Color.rgb(96, 96, 96))
        setBackgroundColor(Color.rgb(250, 250, 250))
        setPadding((4 * density).roundToInt(), 0, (4 * density).roundToInt(), 0)
        setSingleLine(true)
        ellipsize = TextUtils.TruncateAt.END
        isFocusable = false
        isClickable = false
        setTextIsSelectable(false)
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
    }
    private var attached = false

    fun dismiss() {
        if (attached) {
            windows.removeViewImmediate(label)
            attached = false
        }
    }

    fun show(parent: View, status: View?, info: CursorAnchorInfo, composition: String, annotation: String) {
        val token = parent.windowToken ?: return dismiss()
        val viewport = Rect().also(parent::getWindowVisibleDisplayFrame)
        // IME の状態欄とシステムバーを避け、現在の画面内に収めます。
        if (status?.isShown == true) {
            val location = IntArray(2)
            status.getLocationOnScreen(location)
            if (location[1] > viewport.top) viewport.bottom = minOf(viewport.bottom, location[1])
        }
        val anchor = anchorBounds(info, composition, viewport) ?: return dismiss()
        val preview = CandidateTextBounds.preview(annotation, 32)
        label.text = preview.text + if (preview.truncated &&
            preview.text != CandidateTextBounds.LONG_GRAPHEME_PLACEHOLDER) "…" else ""
        val maximumWidth = minOf((320 * density).roundToInt(), viewport.width())
        if (maximumWidth <= 0) return dismiss()
        label.measure(View.MeasureSpec.makeMeasureSpec(maximumWidth, View.MeasureSpec.AT_MOST),
            View.MeasureSpec.makeMeasureSpec(viewport.height(), View.MeasureSpec.AT_MOST))
        val placement = place(anchor, viewport, label.measuredWidth, label.measuredHeight,
            (4 * density).roundToInt()) ?: return dismiss()
        val parameters = WindowManager.LayoutParams(placement.width(), placement.height(),
            WindowManager.LayoutParams.TYPE_APPLICATION_ATTACHED_DIALOG,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                // 注釈を読み上げ対象に含め、窓の外のタッチは入力先へ渡します。
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT).apply {
            this.token = token
            gravity = Gravity.TOP or Gravity.LEFT
            x = placement.left
            y = placement.top
            title = "候補注釈"
            if (Build.VERSION.SDK_INT >= 30) setFitInsetsTypes(0)
        }
        try {
            if (attached) windows.updateViewLayout(label, parameters)
            else {
                windows.addView(label, parameters)
                attached = true
            }
        } catch (_: WindowManager.BadTokenException) {
            // フォーカス切替で窓のトークンが失効した場合は表示を省略します。
            dismiss()
        }
    }

    companion object {
        internal fun anchorBounds(info: CursorAnchorInfo, composition: String, viewport: Rect): RectF? {
            val actual = info.composingText?.toString() ?: return null
            // 任意の変換記号を含む場合も本文との一致を確認し、古い候補の座標を使いません。
            if (actual != composition && actual != "▼$composition") return null
            if (info.composingTextStart < 0 || info.selectionStart != info.selectionEnd ||
                info.selectionEnd != info.composingTextStart + actual.length) return null
            val flags = info.insertionMarkerFlags
            if (flags and CursorAnchorInfo.FLAG_HAS_VISIBLE_REGION == 0 ||
                flags and CursorAnchorInfo.FLAG_HAS_INVISIBLE_REGION != 0) return null
            val x = info.insertionMarkerHorizontal
            val top = info.insertionMarkerTop
            val bottom = info.insertionMarkerBottom
            if (!x.isFinite() || !top.isFinite() || !bottom.isFinite() || bottom <= top) return null
            val anchor = RectF(x, top, x, bottom)
            info.matrix.mapRect(anchor)
            if (!anchor.left.isFinite() || !anchor.top.isFinite() || !anchor.right.isFinite() ||
                !anchor.bottom.isFinite() || anchor.left < viewport.left || anchor.right > viewport.right ||
                anchor.top < viewport.top || anchor.bottom > viewport.bottom) return null
            if (Build.VERSION.SDK_INT >= 33) {
                info.editorBoundsInfo?.editorBounds?.let { editor ->
                    val screenEditor = RectF(editor)
                    info.matrix.mapRect(screenEditor)
                    if (!screenEditor.contains(anchor.left, anchor.top) ||
                        !screenEditor.contains(anchor.right, anchor.bottom)) return null
                }
            }
            return anchor
        }

        internal fun place(anchor: RectF, viewport: Rect, width: Int, height: Int, gap: Int): Rect? {
            if (width <= 0 || height <= 0 || width > viewport.width() || height > viewport.height()) return null
            val beside = anchor.right.roundToInt() + gap
            val x: Int
            val y: Int
            if (beside + width <= viewport.right) {
                x = beside
                y = ((anchor.top + anchor.bottom - height) / 2).roundToInt()
                    .coerceIn(viewport.top, viewport.bottom - height)
            } else {
                x = anchor.left.roundToInt().coerceIn(viewport.left, viewport.right - width)
                y = when {
                    anchor.bottom + gap + height <= viewport.bottom -> anchor.bottom.roundToInt() + gap
                    anchor.top - gap - height >= viewport.top -> anchor.top.roundToInt() - gap - height
                    else -> return null
                }
            }
            return Rect(x, y, x + width, y + height)
        }
    }
}
