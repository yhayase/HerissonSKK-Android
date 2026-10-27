package se.haya.skk.input

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.widget.Button

/** 送信・検索だけを文字色の単色アイコンで表示する主操作キーです。 */
@SuppressLint("AppCompatCustomView")
internal class PrimaryActionButton(context: Context) : Button(context) {
    internal var actionLabel: String = "⏎"
        private set
    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val path = Path()

    internal fun setActionLabel(label: String) {
        actionLabel = label
        contentDescription = label
        text = if (label == "送信" || label == "検索") "" else label
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (actionLabel != "送信" && actionLabel != "検索") return
        val density = resources.displayMetrics.density
        val size = 22f * density
        val cx = (paddingLeft + width - paddingRight) / 2f
        val cy = (paddingTop + height - paddingBottom) / 2f
        val left = cx - size / 2f
        val top = cy - size / 2f
        iconPaint.color = currentTextColor
        iconPaint.alpha = if (isEnabled) 255 else 100
        iconPaint.strokeWidth = 2f * density
        path.reset()
        if (actionLabel == "検索") {
            canvas.drawCircle(cx - size * .12f, cy - size * .12f, size * .27f, iconPaint)
            canvas.drawLine(cx + size * .08f, cy + size * .08f, cx + size * .34f, cy + size * .34f, iconPaint)
        } else {
            // 紙飛行機の外周と胴体を同じ線色で描きます。
            path.moveTo(left + size * .08f, top + size * .46f)
            path.lineTo(left + size * .91f, top + size * .08f)
            path.lineTo(left + size * .63f, top + size * .91f)
            path.lineTo(left + size * .45f, top + size * .57f)
            path.close()
            canvas.drawPath(path, iconPaint)
            canvas.drawLine(left + size * .45f, top + size * .57f, left + size * .91f, top + size * .08f, iconPaint)
        }
    }
}
