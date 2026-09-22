package se.haya.skk

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.text.LineBreaker
import android.text.Layout
import android.text.SpannableString
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.graphics.Typeface
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewConfiguration
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.widget.AppCompatTextView
import kotlin.math.abs

/** フォーカスや入力接続を持たず、IME 内の登録バッファだけを描画します。 */
internal class RegistrationEditorView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : LinearLayout(context, attrs) {
    val titleView = TextView(context).apply {
        id = R.id.registration_title
        textSize = 18f
        setTextColor(0xff202124.toInt())
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
    }
    val parentView = TextView(context).apply {
        id = R.id.registration_parent
        textSize = 13f
        setTextColor(0xff5f6368.toInt())
        visibility = View.GONE
    }
    val editorView = RegistrationTextView(context).apply {
        id = R.id.registration_editor
        textSize = 19f
        setTextColor(0xff202124.toInt())
        setPadding(dp(8), dp(8), dp(8), dp(8))
        minHeight = dp(48)
        isSingleLine = false
        setHorizontallyScrolling(false)
        maxLines = Int.MAX_VALUE
        ellipsize = null
        breakStrategy = LineBreaker.BREAK_STRATEGY_SIMPLE
        hyphenationFrequency = Layout.HYPHENATION_FREQUENCY_NONE
        isFocusable = false
        isFocusableInTouchMode = false
        setTextIsSelectable(false)
        setBackgroundColor(0xffffffff.toInt())
    }
    val registerButton = Button(context).apply {
        id = R.id.registration_register
        text = "登録"
        isFocusable = false
    }
    val cancelButton = Button(context).apply {
        id = R.id.registration_cancel
        text = "取消"
        isFocusable = false
    }
    private val modeView = TextView(context).apply {
        textSize = 13f
        setPadding(dp(3), 0, dp(3), 0)
        setBackgroundColor(0xffe8eaed.toInt())
        setTextColor(0xff202124.toInt())
        visibility = View.GONE
        isFocusable = false
    }
    private val editorFrame = FrameLayout(context).apply {
        addView(editorView, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        addView(modeView, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }
    private val editorScroll = BoundedEditorScrollView(context).apply {
        isFillViewport = false
        isFocusable = false
        addView(editorFrame, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }
    val controlsView = LinearLayout(context).apply {
        orientation = HORIZONTAL
        gravity = Gravity.END
        visibility = View.GONE
        addView(registerButton)
        addView(cancelButton)
    }
    private var presentation: RegistrationPresentation? = null
    private var onCaret: ((Int) -> Unit)? = null
    private var touchDownX = 0f
    private var touchDownY = 0f
    private var touchMoved = false
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop

    init {
        orientation = VERTICAL
        addView(titleView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(parentView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(editorScroll, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        editorView.setOnTouchListener { _, event -> handleCaretTouch(event) }
    }

    fun setCallbacks(onRegister: () -> Unit, onCancel: () -> Unit, onCaret: (Int) -> Unit) {
        registerButton.setOnClickListener { onRegister() }
        cancelButton.setOnClickListener { onCancel() }
        this.onCaret = onCaret
    }

    fun show(value: RegistrationPresentation) {
        presentation = value
        titleView.text = "単語登録：${value.titleReading}"
        parentView.text = value.parentReading?.let { "親：$it" }.orEmpty()
        parentView.visibility = if (value.parentReading == null) View.GONE else View.VISIBLE
        val styled = SpannableString(value.text)
        if (value.composingStart != null && value.composingEnd != null &&
            value.composingStart < value.composingEnd) {
            styled.setSpan(BackgroundColorSpan(0x223f51b5), value.composingStart,
                value.composingEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        if (value.completionStart != null && value.completionEnd != null &&
            value.completionStart < value.completionEnd) {
            styled.setSpan(ForegroundColorSpan(0xff80868b.toInt()), value.completionStart,
                value.completionEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            styled.setSpan(StyleSpan(Typeface.ITALIC), value.completionStart,
                value.completionEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        editorView.text = styled
        editorView.cursor = value.cursor
        editorView.isClickable = value.canEdit
        registerButton.text = if (value.saving) "登録中…" else "登録"
        registerButton.isEnabled = value.canRegister && !value.saving
        cancelButton.isEnabled = !value.saving
        controlsView.visibility = View.VISIBLE
        post {
            positionMode()
            val caret = editorView.caretRect()
            editorScroll.requestChildRectangleOnScreen(editorView, Rect(caret), true)
        }
    }

    fun showTransientMode(mode: String, visible: Boolean) {
        modeView.text = mode
        modeView.visibility = if (visible) View.VISIBLE else View.GONE
        if (visible) post(::positionMode)
    }

    private fun positionMode() {
        if (modeView.visibility != View.VISIBLE) return
        val caret = editorView.caretRect()
        modeView.measure(MeasureSpec.makeMeasureSpec(editorFrame.width, MeasureSpec.AT_MOST),
            MeasureSpec.makeMeasureSpec(editorFrame.height, MeasureSpec.AT_MOST))
        modeView.x = (caret.right + dp(3)).coerceAtMost(
            (editorFrame.width - modeView.measuredWidth).coerceAtLeast(0)).toFloat()
        modeView.y = (caret.top - modeView.measuredHeight).coerceAtLeast(0).toFloat()
    }

    private fun handleCaretTouch(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                touchDownX = event.x
                touchDownY = event.y
                touchMoved = false
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (abs(event.x - touchDownX) > touchSlop || abs(event.y - touchDownY) > touchSlop) {
                    touchMoved = true
                }
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                touchMoved = false
                return true
            }
            MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_POINTER_UP -> {
                touchMoved = true
                return true
            }
            MotionEvent.ACTION_UP -> {
                val moved = touchMoved || abs(event.x - touchDownX) > touchSlop ||
                    abs(event.y - touchDownY) > touchSlop
                touchMoved = false
                if (moved) return true
            }
            else -> return true
        }
        val value = presentation ?: return true
        if (!value.canEdit || value.composingStart != null) return true
        val layout = editorView.layout ?: return true
        val line = layout.getLineForVertical((event.y - editorView.totalPaddingTop + editorView.scrollY)
            .toInt().coerceAtLeast(0))
        val offset = layout.getOffsetForHorizontal(line,
            event.x - editorView.totalPaddingLeft + editorView.scrollX).coerceIn(0, value.text.length)
        onCaret?.invoke(offset)
        return true
    }

    private fun dp(value: Int): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics).toInt()
}

/** 本文を省略せず保持し、候補と操作が離れない高さだけを画面へ出します。 */
private class BoundedEditorScrollView(context: Context) : ScrollView(context) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val screen = resources.configuration.screenHeightDp.takeIf { it > 0 }
            ?.let { (it * resources.displayMetrics.density).toInt() }
            ?: resources.displayMetrics.heightPixels
        val cap = minOf(dp(160), (screen * .25f).toInt()).coerceAtLeast(dp(72))
        super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(cap, MeasureSpec.AT_MOST))
    }

    private fun dp(value: Int): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics).toInt()
}

/** TextView のレイアウトを使い、IME が所有するカーソルだけを細線で描画します。 */
internal class RegistrationTextView(context: Context) : AppCompatTextView(context) {
    var cursor: Int = 0
        set(value) {
            field = value.coerceIn(0, text?.length ?: 0)
            invalidate()
        }
    private val cursorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xff1a73e8.toInt()
        strokeWidth = resources.displayMetrics.density.coerceAtLeast(1f)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val layout = layout ?: return
        val offset = cursor.coerceIn(0, text.length)
        val line = layout.getLineForOffset(offset)
        val x = totalPaddingLeft + layout.getPrimaryHorizontal(offset) - scrollX
        val top = totalPaddingTop + layout.getLineTop(line) - scrollY
        val bottom = totalPaddingTop + layout.getLineBottom(line) - scrollY
        canvas.drawLine(x, top.toFloat(), x, bottom.toFloat(), cursorPaint)
    }

    fun caretRect(): Rect {
        val layout = layout ?: return Rect()
        val offset = cursor.coerceIn(0, text.length)
        val line = layout.getLineForOffset(offset)
        val x = (totalPaddingLeft + layout.getPrimaryHorizontal(offset) - scrollX).toInt()
        return Rect(x, totalPaddingTop + layout.getLineTop(line) - scrollY,
            x + cursorPaint.strokeWidth.toInt().coerceAtLeast(1),
            totalPaddingTop + layout.getLineBottom(line) - scrollY)
    }
}
