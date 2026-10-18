package se.haya.skk.input

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.widget.Button
import android.view.Gravity

/** 主ラベルを保ち、長押しで別機能を選べるキーの下側に補助記号を描きます。 */
@android.annotation.SuppressLint("AppCompatCustomView")
internal class AlternativeFunctionButton(context: Context) : Button(context) {
    init { gravity = Gravity.CENTER; includeFontPadding = false }
    private val hintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    internal val alternativeHint = "…"
    internal var centeredIcon: Drawable? = null
        set(value) {
            field = value?.mutate()?.apply { setTint(currentTextColor) }
            requestLayout(); invalidate()
        }
    internal var showAlternativeHint = true
        set(value) { field = value; invalidate() }
    internal fun centeredIconBounds(): RectF {
        val icon = centeredIcon ?: return RectF()
        val iconWidth = minOf(icon.intrinsicWidth.toFloat(), width.toFloat())
        val iconHeight = minOf(icon.intrinsicHeight.toFloat(), height.toFloat())
        return RectF((width - iconWidth) / 2f, (height - iconHeight) / 2f,
            (width + iconWidth) / 2f, (height + iconHeight) / 2f)
    }
    internal fun alternativeHintBounds(): RectF {
        hintPaint.textSize = minOf(10f * resources.displayMetrics.scaledDensity, height * .2f)
        val metrics = hintPaint.fontMetrics
        val baseline = height - 2f * resources.displayMetrics.density - metrics.descent
        val halfWidth = hintPaint.measureText(alternativeHint) / 2f
        return RectF(width / 2f - halfWidth, baseline + metrics.ascent, width / 2f + halfWidth, baseline + metrics.descent)
    }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        centeredIcon?.let { icon ->
            icon.setTint(currentTextColor)
            val bounds = centeredIconBounds()
            icon.setBounds(bounds.left.toInt(), bounds.top.toInt(), bounds.right.toInt(), bounds.bottom.toInt())
            icon.draw(canvas)
        }
        if (!showAlternativeHint) return
        val bounds = alternativeHintBounds()
        hintPaint.color = currentTextColor
        hintPaint.alpha = 130
        canvas.drawText(alternativeHint, width / 2f, bounds.bottom - hintPaint.fontMetrics.descent, hintPaint)
    }
}
