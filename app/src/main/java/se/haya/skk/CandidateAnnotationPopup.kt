package se.haya.skk

import android.content.Context
import android.graphics.Rect
import android.graphics.drawable.ColorDrawable
import android.graphics.text.LineBreaker
import android.text.Layout
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.BaseAdapter
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.PopupWindow
import android.widget.TextView
import se.haya.skk.input.KeyboardColors

/** 注釈を別ウィンドウで表示し、入力先と IME の高さを変えません。 */
internal class CandidateAnnotationPopup(
    private val context: Context,
    private val annotation: String,
    onDismiss: () -> Unit,
) {
    internal val body: ListView = ListView(context).apply {
        divider = null
        isVerticalScrollBarEnabled = true
        adapter = object : BaseAdapter() {
            override fun getCount() = (annotation.length + LongCandidateBodyView.CHUNK_SIZE - 1) /
                LongCandidateBodyView.CHUNK_SIZE
            override fun getItem(position: Int) = LongCandidateBodyView.chunk(annotation, position)
            override fun getItemId(position: Int) = position.toLong()
            override fun isEnabled(position: Int) = false
            override fun areAllItemsEnabled() = false
            override fun getView(position: Int, recycled: View?, parent: ViewGroup): View =
                ((recycled as? TextView) ?: annotationText()).apply { text = getItem(position) }
        }
    }
    internal val content: LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(12), dp(8), dp(12), dp(8))
        setBackgroundColor(KeyboardColors.resolve(context).panelBackground)
        addView(body, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
    }
    internal val window: PopupWindow = PopupWindow(content, 0, 0, false).apply {
        // 非フォーカス窓は API 26 以降で窓外の元のジェスチャーを背後へ通します。
        // 戻る操作は IME 側の注釈詳細処理で閉じます。
        inputMethodMode = PopupWindow.INPUT_METHOD_NOT_NEEDED
        softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING
        isOutsideTouchable = true
        setTouchInterceptor { _, event ->
            if (event.actionMasked == MotionEvent.ACTION_OUTSIDE) dismiss()
            false
        }
        setBackgroundDrawable(ColorDrawable(KeyboardColors.resolve(context).border))
        elevation = dp(6).toFloat()
        setOnDismissListener { onDismiss() }
    }
    val isShowing: Boolean get() = window.isShowing

    fun show(anchor: View) {
        if (anchor.windowToken == null) return
        val frame = Rect()
        anchor.getWindowVisibleDisplayFrame(frame)
        val metrics = context.resources.displayMetrics
        if (frame.isEmpty) frame.set(0, 0, metrics.widthPixels, metrics.heightPixels)
        val maxWidth = minOf(dp(420), frame.width()).coerceAtLeast(1)
        val sample = annotationText().apply { text = LongCandidateBodyView.chunk(annotation, 0) }
        val textWidth = Layout.getDesiredWidth(sample.text, sample.paint).toInt()
        val width = maxOf(dp(160), textWidth + dp(24))
            .coerceAtMost(maxWidth)
        val innerWidth = (width - dp(24)).coerceAtLeast(1)
        sample.measure(View.MeasureSpec.makeMeasureSpec(innerWidth, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        val cap = minOf((metrics.heightPixels * .35f).toInt(), frame.height())
        val height = minOf(cap, dp(16) +
            if (body.adapter.count > 1) cap else sample.measuredHeight)
        val location = IntArray(2)
        val locationInWindow = IntArray(2)
        anchor.getLocationOnScreen(location)
        anchor.getLocationInWindow(locationInWindow)
        val screenX = location[0].coerceIn(frame.left, (frame.right - width).coerceAtLeast(frame.left))
        val screenY = (location[1] - height).coerceIn(frame.top,
            (frame.bottom - height).coerceAtLeast(frame.top))
        window.width = width
        window.height = height
        // showAtLocation は親ウィンドウ基準なので、IME の画面上の原点を差し引きます。
        window.showAtLocation(anchor, Gravity.TOP or Gravity.START,
            screenX - (location[0] - locationInWindow[0]),
            screenY - (location[1] - locationInWindow[1]))
    }

    fun dismiss(): Unit = window.dismiss()

    private fun annotationText() = TextView(context).apply {
        textSize = 17f
        setTextColor(KeyboardColors.resolve(context).text)
        breakStrategy = LineBreaker.BREAK_STRATEGY_SIMPLE
        hyphenationFrequency = Layout.HYPHENATION_FREQUENCY_NONE
        // 一度にレイアウト・読み上げへ渡す本文を有限長に保ちます。
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
    }

    private fun dp(value: Int) = (value * context.resources.displayMetrics.density).toInt()
}
