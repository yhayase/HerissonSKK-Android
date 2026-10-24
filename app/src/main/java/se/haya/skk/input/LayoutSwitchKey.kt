package se.haya.skk.input

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.PopupWindow
import se.haya.skk.R
import se.haya.skk.settings.ScreenKeyboardLayout
import kotlin.math.abs
import kotlin.math.max

/** タップと方向フリックを離した時点で確定する配列切り替えキーです。 */
internal class LayoutSwitchKey(
    context: Context,
    target: ScreenKeyboardLayout,
    private val onSwitchLayout: () -> Unit,
    private val onSwitchIme: () -> Unit,
    private val onAdjustGeometry: (() -> Unit)? = null,
    private val isCurrent: () -> Boolean = { true },
    private val onOpenSettings: () -> Unit = {},
) : AlternativeFunctionButton(context) {
    private enum class Selection { CENTER, RIGHT, SETTINGS, UP, NONE }
    internal val targetIconResource = if (target == ScreenKeyboardLayout.QWERTY)
        R.drawable.ic_keyboard_qwerty else R.drawable.ic_keyboard_ten_key
    private var tracking = false
    private var disposed = false
    private var pointer = -1
    private var originX = 0f
    private var originY = 0f
    private var moved = false
    private var selection = Selection.CENTER
    private var guide: PopupWindow? = null
    private var shownGuideCell: Int? = null
    internal val guideCellSize: Int
        get() = shownGuideCell ?: minOf(width, height, (64 * resources.displayMetrics.density).toInt()).coerceAtLeast(1)
    internal val isGuideShowing get() = guide?.isShowing == true
    internal var guideBounds: Rect? = null
        private set

    init {
        text = ""
        contentDescription = if (target == ScreenKeyboardLayout.QWERTY) "QWERTYへ切替" else "10キーへ切替"
        showAlternativeHint = false
        setTextColor(KeyboardColors.resolve(context).text)
        centeredIcon = context.getDrawable(targetIconResource)
        isClickable = true
        isLongClickable = false
    }

    private fun available() = !disposed && isEnabled && isCurrent()

    override fun performClick(): Boolean {
        if (!available()) return false
        super.performClick()
        onSwitchLayout()
        return true
    }

    override fun performLongClick() = false

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!available()) { cancelInteraction(); return false }
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                cancelInteraction()
                if (event.pointerCount != 1) return true
                tracking = true
                pointer = event.getPointerId(0)
                originX = event.x; originY = event.y
                selection = Selection.CENTER
                isPressed = true
                parent?.requestDisallowInterceptTouchEvent(true)
                showGuide()
            }
            MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_CANCEL -> cancelInteraction()
            MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP -> {
                if (!tracking) return true
                if (event.pointerCount != 1 || event.getPointerId(0) != pointer) {
                    cancelInteraction(); return true
                }
                for (sample in 0 until event.historySize) select(event.getHistoricalX(0, sample), event.getHistoricalY(0, sample))
                select(event.x, event.y)
                if (event.actionMasked == MotionEvent.ACTION_UP) {
                    val chosen = selection
                    val tap = !moved
                    cancelInteraction()
                    if (available()) when (chosen) {
                        Selection.CENTER -> if (tap) performClick()
                        Selection.RIGHT -> onSwitchIme()
                        Selection.SETTINGS -> onOpenSettings()
                        Selection.UP -> onAdjustGeometry?.invoke()
                        Selection.NONE -> Unit
                    }
                }
            }
        }
        return true
    }

    private fun select(x: Float, y: Float) {
        val dx = x - originX; val dy = y - originY
        val threshold = max(ViewConfiguration.get(context).scaledTouchSlop.toFloat(), minOf(width, height) * .2f)
        selection = if (max(abs(dx), abs(dy)) < threshold) Selection.CENTER else {
            moved = true
            if (abs(dx) >= abs(dy)) {
                if (dx > 0) {
                    val boundary = guideCellSize * 1.5f
                    val hysteresis = 2 * resources.displayMetrics.density
                    val settings = if (selection == Selection.SETTINGS) dx >= boundary - hysteresis
                        else dx >= boundary + hysteresis
                    if (settings) Selection.SETTINGS else Selection.RIGHT
                } else Selection.NONE
            } else if (dy < 0 && onAdjustGeometry != null) Selection.UP else Selection.NONE
        }
        guide?.contentView?.invalidate()
    }

    internal fun cancelInteraction() {
        tracking = false
        pointer = -1
        moved = false
        isPressed = false
        guide?.dismiss(); guide = null
        guideBounds = null
        shownGuideCell = null
        parent?.requestDisallowInterceptTouchEvent(false)
    }

    internal fun dispose() { disposed = true; cancelInteraction() }

    override fun setEnabled(enabled: Boolean) {
        super.setEnabled(enabled)
        if (!enabled) cancelInteraction()
    }

    override fun onDetachedFromWindow() { cancelInteraction(); super.onDetachedFromWindow() }

    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        info.isLongClickable = false
        info.removeAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_LONG_CLICK)
        info.addAction(AccessibilityNodeInfo.AccessibilityAction(R.id.layout_switch_ime, "入力方法を切り替え"))
        info.addAction(AccessibilityNodeInfo.AccessibilityAction(R.id.layout_switch_settings, "HerissonSKKの設定を開く"))
        if (onAdjustGeometry != null)
            info.addAction(AccessibilityNodeInfo.AccessibilityAction(R.id.layout_switch_geometry, "キーボードの位置とサイズを調整"))
    }

    override fun performAccessibilityAction(action: Int, arguments: Bundle?): Boolean {
        if (!available()) return false
        when (action) {
            R.id.layout_switch_ime -> { cancelInteraction(); onSwitchIme(); return true }
            R.id.layout_switch_settings -> { cancelInteraction(); onOpenSettings(); return true }
            R.id.layout_switch_geometry -> if (onAdjustGeometry != null) {
                cancelInteraction(); onAdjustGeometry.invoke(); return true
            }
        }
        return super.performAccessibilityAction(action, arguments)
    }

    private fun showGuide() {
        if (!isAttachedToWindow || width == 0 || height == 0) return
        val frame = Rect().also { getWindowVisibleDisplayFrame(it) }
        val rows = if (onAdjustGeometry != null) 2 else 1
        val cell = minOf(width, height, (64 * resources.displayMetrics.density).toInt(),
            (frame.width() / 3).coerceAtLeast(1), (frame.height() / rows).coerceAtLeast(1)).coerceAtLeast(1)
        val location = IntArray(2).also { getLocationOnScreen(it) }
        shownGuideCell = cell
        val popupWidth = cell * 3
        val popupHeight = cell * rows
        val x = (location[0] + width / 2 - cell / 2).coerceIn(frame.left, max(frame.left, frame.right - popupWidth))
        val y = (location[1] + height / 2 - popupHeight + cell / 2).coerceIn(frame.top, max(frame.top, frame.bottom - popupHeight))
        guideBounds = Rect(x, y, x + popupWidth, y + popupHeight)
        guide = PopupWindow(GuideView(context, cell), popupWidth, popupHeight, false).apply {
            isTouchable = false
            isOutsideTouchable = false
            isClippingEnabled = false
            animationStyle = 0
            enterTransition = null
            exitTransition = null
            inputMethodMode = PopupWindow.INPUT_METHOD_NOT_NEEDED
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            // IME子窓の原点を重ねず、画面上の目標位置を元キーからの相対位置に直します。
            showAsDropDown(this@LayoutSwitchKey, x - location[0], y - location[1] - this@LayoutSwitchKey.height, Gravity.LEFT)
        }
    }

    private inner class GuideView(context: Context, private val cell: Int) : View(context) {
        private val colors = KeyboardColors.resolve(context)
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val row = if (onAdjustGeometry != null) 1 else 0
            drawCell(canvas, 0, row, targetIconResource, Selection.CENTER)
            drawCell(canvas, 1, row, R.drawable.ic_globe_white, Selection.RIGHT)
            drawCell(canvas, 2, row, R.drawable.ic_keyboard_settings, Selection.SETTINGS)
            if (onAdjustGeometry != null) drawCell(canvas, 0, 0, R.drawable.ic_keyboard_resize, Selection.UP)
        }
        private fun drawCell(canvas: Canvas, column: Int, row: Int, iconResource: Int, value: Selection) {
            val left = column * cell.toFloat(); val top = row * cell.toFloat()
            paint.style = Paint.Style.FILL
            paint.color = if (selection == value) colors.selectedBackground else colors.functionBackground
            canvas.drawRect(left, top, left + cell, top + cell, paint)
            paint.style = Paint.Style.STROKE; paint.strokeWidth = resources.displayMetrics.density
            paint.color = colors.border
            canvas.drawRect(left, top, left + cell, top + cell, paint)
            val icon = context.getDrawable(iconResource)!!.mutate()
            icon.setTint(currentTextColor)
            val size = minOf((28 * resources.displayMetrics.density).toInt(), cell * 2 / 3)
            val x = (left + (cell - size) / 2).toInt(); val y = (top + (cell - size) / 2).toInt()
            icon.setBounds(x, y, x + size, y + size); icon.draw(canvas)
        }
    }
}
