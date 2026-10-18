package se.haya.skk.input

import se.haya.skk.R

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.os.Vibrator
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.Button
import android.widget.LinearLayout
import android.widget.FrameLayout
import android.graphics.Rect
import android.widget.PopupWindow
import android.widget.TextView
import se.haya.skk.core.BasicSkkAction
import se.haya.skk.settings.TouchKeyboardSettings
import se.haya.skk.settings.TenKeyOperation

/** かな・英字・数字を直接送る10キー面です。 */
internal class TenKeyKeyboardView(context: Context, private val settings: TouchKeyboardSettings,
    internal val controller: TenKeyController = TenKeyController(),
    private val send: (TouchKeyboardCommand) -> Unit) : LinearLayout(context) {
    internal var onAdjustGeometry: (() -> Unit)? = null
    private val cancellations = mutableListOf<() -> Unit>()
    private var generation = 0
    private var blockedPointers = false
    internal var guide: PopupWindow? = null
        private set
    private var guideCells: List<FlickGuideCell> = emptyList()
    internal var guideBounds: Rect? = null
        private set
    private var palette: CharacterPalette? = null
    private var paletteRevision = 0
    private val keyboardColors = KeyboardColors.resolve(context)
    private var spaceButton: Button? = null
    private var spaceConverts = false
    internal var pendingGuide: String? = null
        private set
    private var skkEnabled = true
    private var readingConversionEnabled = false
    fun updateReadingConversion(enabled: Boolean) {
        if (readingConversionEnabled != enabled) cancelTouches()
        readingConversionEnabled = enabled
    }
    private var readingButton: Button? = null
    fun setSkkEnabled(enabled: Boolean) {
        if (skkEnabled != enabled) cancelTouches()
        skkEnabled = enabled
        readingButton?.isEnabled = enabled
    }
    private var primary: Button? = null
    private var primaryLabel = "⏎"
    private var primaryEnabled = true
    init { orientation = VERTICAL; isMotionEventSplittingEnabled = false; rebuild() }
    fun cancelTouches() { paletteRevision++; cancellations.toList().forEach { it() }; dismissGuide(); palette?.dismiss(); palette = null }
    fun dispose() { cancelTouches(); generation++ }
    fun updatePrimaryAction(label: String, enabled: Boolean) {
        primaryLabel = label; primaryEnabled = enabled
        primary?.apply { text = label; contentDescription = label; setTextColor(keyboardColors.text); isEnabled = enabled }
    }
    fun updateSpaceAction(convert: Boolean) {
        spaceConverts = convert
        spaceButton?.apply { text = if (convert) "変換" else "␣"; contentDescription = if (convert) "変換" else "空白" }
    }
    override fun onDetachedFromWindow() { cancelTouches(); super.onDetachedFromWindow() }
    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) blockedPointers = false
        if (event.actionMasked == MotionEvent.ACTION_POINTER_DOWN || event.pointerCount > 1) {
            blockedPointers = true; cancelTouches()
        }
        if (blockedPointers) {
            if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) blockedPointers = false
            return true
        }
        return super.dispatchTouchEvent(event)
    }
    private fun action(action: BasicSkkAction) = send(TouchKeyboardCommand.Skk(action))
    private fun rebuild() {
        cancelTouches(); generation++; removeAllViews(); cancellations.clear()
        val groups = controller.rows()
        for (rowIndex in 0..3) {
            val row = LinearLayout(context).apply { orientation = HORIZONTAL; isMotionEventSplittingEnabled = false }
            row.addView(when (rowIndex) {
                0 -> key(when(controller.face) { TenKeyController.Face.KANA -> "ABC"; TenKeyController.Face.ALPHA -> "123"; else -> "かな" }) { controller.cycleFace(); rebuild() }
                1 -> kindKey()
                2 -> key("記号") { showSymbols(it) }
                else -> key("", alternative = true) { send(TouchKeyboardCommand.SwitchIme) }.apply {
                    (this as AlternativeFunctionButton).centeredIcon = context.getDrawable(R.drawable.ic_globe_white)
                    contentDescription = "IME切替"
                    setOnLongClickListener { cancelTouches(); onAdjustGeometry?.invoke(); true }
                }
            }, params())
            for (column in 0..2) {
                val index = rowIndex * 3 + column
                row.addView(when {
                    controller.face == TenKeyController.Face.KANA && index == 9 -> key("小゛゜") { action(BasicSkkAction.CycleTouchKana) }
                    controller.face == TenKeyController.Face.ALPHA && index == 9 -> key(if (controller.uppercase) "A/a" else "a/A") { controller.uppercase = !controller.uppercase; rebuild() }
                    else -> characterKey(groups[index])
                }, params())
            }
            row.addView(when(rowIndex) {
                0 -> backspaceKey()
                1 -> key(if (spaceConverts) "変換" else "␣") { send(TouchKeyboardCommand.SpaceOrConvert(controller.face == TenKeyController.Face.ALPHA && controller.wideAlpha)) }.also { spaceButton = it; it.contentDescription = if (spaceConverts) "変換" else "空白" }
                2 -> key("⇧") { if (skkEnabled) action(BasicSkkAction.StartTouchReading(controller.kanaMode)) }.also { readingButton = it; it.contentDescription = "読み開始・送り境界"; it.isEnabled = skkEnabled }
                else -> key(primaryLabel) { action(BasicSkkAction.Enter) }.also { primary = it; it.isEnabled = primaryEnabled }
            }, params())
            addView(row, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
        }
    }
    private fun kindKey(): Button {
        val label = when(controller.face) { TenKeyController.Face.KANA -> if (controller.katakana) "あ" else "ア"; TenKeyController.Face.ALPHA -> if (controller.wideAlpha) "A" else "全Ａ"; else -> if (controller.wideNumber) "1" else "全１" }
        return gestureKey(label, null, true)
    }
    private fun characterKey(group: List<String>): Button = gestureKey(
        if (controller.face == TenKeyController.Face.ALPHA && group[0].first().isLetter()) group.joinToString("").let { if (controller.uppercase) it.uppercase() else it }
        else if (controller.face != TenKeyController.Face.KANA) group.first()
        else controller.output(group, TenKeyController.Direction.CENTER, null).orEmpty(), group, false)

    @android.annotation.SuppressLint("ClickableViewAccessibility")
    private fun gestureKey(label: String, group: List<String>?, kind: Boolean): Button {
        val capturedGeneration = generation
        val button = key(label, function = kind) {
            if (kind) { controller.toggleKind(); rebuild() }
            else group?.let { emit(it, TenKeyController.Direction.CENTER, null, false) }
        }
        var gesture: TenKeyGesture? = null
        var direction = TenKeyController.Direction.CENTER
        var hapticSent = false
        val hold = Runnable {
            val active = gesture
            if (capturedGeneration == generation && skkEnabled && !kind && active?.hold() == true) {
                if (!hapticSent && settings.vibrateOnBoundary && (context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator)?.hasVibrator() == true) button.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                hapticSent = true
                showGuide(button, group, direction, active)
            }
        }
        val delayedGuide = Runnable { gesture?.takeIf { it.active }?.let { showGuide(button, group, direction, it) } }
        fun clear() { button.removeCallbacks(hold); button.removeCallbacks(delayedGuide); gesture?.cancel(); gesture = null; button.isPressed = false; dismissGuide() }
        cancellations += ::clear
        fun sample(event: MotionEvent, reschedule: Boolean) {
            val active = gesture ?: return
            val revision = active.motionRevision
            for (index in 0 until event.historySize) direction = active.move(event.getHistoricalX(index), event.getHistoricalY(index))
            direction = active.move(event.x, event.y)
            if (revision != active.motionRevision) {
                button.removeCallbacks(hold)
                hapticSent = false
                if (reschedule && !kind) button.postDelayed(hold, settings.holdMillis.toLong())
            }
        }
        button.setOnTouchListener { _, event ->
            if (capturedGeneration != generation) return@setOnTouchListener true
            when(event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    // 修飾の領域切替には、画面描画用の予測座標ではなく実際の移動を使います。
                    if (!kind && settings.operation == TenKeyOperation.HOLD_AND_RETURN) button.requestUnbufferedDispatch(event)
                    clear()
                    gesture = TenKeyGesture(maxOf(ViewConfiguration.get(context).scaledTouchSlop.toFloat(), minOf(button.width, button.height) * .2f), if(kind) TenKeyOperation.FLICK else settings.operation, ViewConfiguration.get(context).scaledTouchSlop.toFloat(), 2f * resources.displayMetrics.density).also { it.start(event.x, event.y) }
                    direction = TenKeyController.Direction.CENTER; button.isPressed = true; hapticSent = false
                    if (!kind) button.postDelayed(hold, settings.holdMillis.toLong())
                    button.postDelayed(delayedGuide, 200)
                }
                MotionEvent.ACTION_MOVE -> gesture?.let {
                    sample(event, true)
                    if (direction != TenKeyController.Direction.CENTER || pendingGuide != null) showGuide(button, group, direction, it)
                }
                MotionEvent.ACTION_UP -> gesture?.takeIf { it.active }?.let {
                    sample(event, false)
                    if (kind) when {
                        direction == TenKeyController.Direction.CENTER -> button.performClick()
                        skkEnabled && readingConversionEnabled && controller.face == TenKeyController.Face.KANA && direction == TenKeyController.Direction.UP -> action(BasicSkkAction.ConvertTouchReading())
                        skkEnabled && readingConversionEnabled && controller.face == TenKeyController.Face.KANA && direction == TenKeyController.Direction.RIGHT -> action(BasicSkkAction.ConvertTouchReading(true))
                    } else group?.let { row -> emit(row, direction, it.modifier, it.boundary) }
                    clear()
                }
                MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_POINTER_DOWN -> clear()
            }
            true
        }
        return button
    }
    private fun emit(group: List<String>, direction: TenKeyController.Direction, modifier: TenKeyController.Direction?, boundary: Boolean) {
        controller.output(group, direction, modifier)?.let {
            action(BasicSkkAction.TouchText(it, skkEnabled && (boundary || controller.nextBoundary), controller.kanaMode))
            controller.nextBoundary = false
        }
    }
    private fun showGuide(anchor: Button, group: List<String>?, direction: TenKeyController.Direction, gesture: TenKeyGesture) {
        val options = group?.let { controller.modifiedRow(it, gesture.modifier) }
            ?: if (controller.face == TenKeyController.Face.KANA && skkEnabled && readingConversionEnabled) listOf(anchor.text.toString(), "", "カナ", "ｶﾅ", "")
            else listOf(anchor.text.toString(), "", "", "", "")
        val chosen = options.getOrNull(direction.ordinal).orEmpty()
        fun modifierSymbol(candidate: TenKeyController.Direction?): String? {
            if (group == null || controller.modifierLabel(group, candidate) == null) return null
            return when (candidate) {
                TenKeyController.Direction.LEFT -> "゛"
                TenKeyController.Direction.RIGHT -> when (controller.face) {
                    TenKeyController.Face.KANA -> if (group == TenKeyController.KANA[5]) "゜" else "小"
                    TenKeyController.Face.ALPHA -> if (controller.uppercase) "a" else "A"
                    else -> null
                }
                TenKeyController.Direction.UP -> controller.output(group, TenKeyController.Direction.CENTER, candidate)
                else -> null
            }
        }
        fun returnHint(candidate: TenKeyController.Direction): String? =
            if (settings.operation == TenKeyOperation.HOLD_AND_RETURN) modifierSymbol(candidate) else null
        val hints = TenKeyController.Direction.entries.map { candidate ->
            if (candidate == TenKeyController.Direction.CENTER) modifierSymbol(gesture.modifier) else returnHint(candidate)
        }
        val labels = TenKeyController.Direction.entries.map { candidate ->
            if (candidate == TenKeyController.Direction.CENTER) chosen else options.getOrNull(candidate.ordinal).orEmpty()
        }
        pendingGuide = (if (gesture.boundary) "境界 " else "") + labels.mapIndexed { index, value ->
            value + hints[index]?.let { "\n$it" }.orEmpty()
        }.joinToString(" ")
        if (!anchor.isAttachedToWindow) return
        val visible = Rect().also(anchor::getWindowVisibleDisplayFrame)
        if (visible.isEmpty) visible.set(0, 0, resources.displayMetrics.widthPixels, resources.displayMetrics.heightPixels)
        fun column(candidate: TenKeyController.Direction) = when (candidate) { TenKeyController.Direction.LEFT -> 0; TenKeyController.Direction.RIGHT -> 2; else -> 1 }
        fun row(candidate: TenKeyController.Direction) = when (candidate) { TenKeyController.Direction.UP -> 0; TenKeyController.Direction.DOWN -> 2; else -> 1 }
        val assigned = TenKeyController.Direction.entries.filter { it == TenKeyController.Direction.CENTER || options.getOrNull(it.ordinal).orEmpty().isNotEmpty() }
        val minColumn = assigned.minOf(::column)
        val maxColumn = assigned.maxOf(::column)
        val minRow = assigned.minOf(::row)
        val maxRow = assigned.maxOf(::row)
        val position = IntArray(2).also(anchor::getLocationOnScreen)
        // 空の方向は領域を確保せず、元キーの中心を保ったまま画面内に収めます。
        fun cellSize(sourceSize: Int, sourceStart: Int, visibleStart: Int, visibleEnd: Int, minimum: Int, maximum: Int): Int {
            val center = sourceStart + sourceSize / 2f
            val available = minOf((center - visibleStart) / (1 - minimum + .5f), (visibleEnd - center) / (maximum - 1 + .5f)).toInt()
            var size = minOf(maxOf(sourceSize, dp(48)), available).coerceAtLeast(1)
            // 中心座標が半ピクセルずれないよう、元キーと幅の偶奇を合わせます。
            if (size > 1 && size % 2 != sourceSize % 2) size--
            return size
        }
        val cellWidth = cellSize(anchor.width, position[0], visible.left, visible.right, minColumn, maxColumn)
        val cellHeight = cellSize(anchor.height, position[1], visible.top, visible.bottom, minRow, maxRow)
        val popupWidth = cellWidth * (maxColumn - minColumn + 1)
        val popupHeight = cellHeight * (maxRow - minRow + 1)
        val popup = guide ?: PopupWindow(context).apply {
            val content = FrameLayout(context)
            guideCells = TenKeyController.Direction.entries.map { candidate ->
                FlickGuideCell(context).apply {
                    gravity = Gravity.CENTER; textSize = 18f
                    setTextColor(keyboardColors.text); stateListAnimator = null
                    content.addView(this, FrameLayout.LayoutParams(cellWidth, cellHeight))
                    tag = candidate
                }
            }
            contentView = content; isFocusable = false; isTouchable = false; isOutsideTouchable = false
            isClippingEnabled = false; animationStyle = 0; enterTransition = null; exitTransition = null
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            guide = this
        }
        guideCells.forEachIndexed { index, cell ->
            val candidate = TenKeyController.Direction.entries[index]
            cell.visibility = if (candidate in assigned) View.VISIBLE else View.INVISIBLE
            cell.layoutParams = FrameLayout.LayoutParams(cellWidth, cellHeight).apply { leftMargin = (column(candidate) - minColumn) * cellWidth; topMargin = (row(candidate) - minRow) * cellHeight }
            val label = labels[index]
            cell.text = label
            cell.hintSymbol = hints[index]
            cell.returnDirection = candidate.takeIf { it != TenKeyController.Direction.CENTER && hints[index] != null }
            cell.modifierActive = hints[index] != null && (candidate == TenKeyController.Direction.CENTER || gesture.modifier == candidate)
            cell.invalidate()
            val description = when {
                candidate == TenKeyController.Direction.CENTER -> group?.let { controller.modifierLabel(it, gesture.modifier) }?.let { "$label $it 選択中" } ?: label
                group != null && hints[index] != null -> {
                    val effect = if (gesture.modifier == candidate) "解除" else controller.modifierLabel(group, candidate)
                    "$label " + if (direction == candidate) "戻すと$effect" else "往復で$effect"
                }
                group == null && candidate == TenKeyController.Direction.UP && label == "カナ" -> "カナで確定"
                group == null && candidate == TenKeyController.Direction.RIGHT && label == "ｶﾅ" -> "半角カナで確定"
                else -> label
            }
            cell.contentDescription = (if (candidate == TenKeyController.Direction.CENTER && gesture.boundary) "境界 " else "") + description
            cell.background = GradientDrawable().apply {
                setColor(if (candidate == TenKeyController.Direction.CENTER) keyboardColors.selectedBackground else keyboardColors.panelBackground)
                setStroke(dp(if (candidate == TenKeyController.Direction.CENTER && gesture.boundary) 3 else 1), if (gesture.boundary && candidate == TenKeyController.Direction.CENTER) keyboardColors.pressed else keyboardColors.border)
            }
        }
        val left = position[0] + (anchor.width - cellWidth) / 2 - (1 - minColumn) * cellWidth
        val top = position[1] + (anchor.height - cellHeight) / 2 - (1 - minRow) * cellHeight
        guideBounds = Rect(left, top, left + popupWidth, top + popupHeight)
        // IME子窓の原点を重ねないよう、画面上の目標位置を元キーからの相対位置へ直します。
        val offsetX = left - position[0]
        val offsetY = top - position[1] - anchor.height
        if (popup.isShowing) popup.update(anchor, offsetX, offsetY, popupWidth, popupHeight)
        else { popup.width = popupWidth; popup.height = popupHeight; popup.showAsDropDown(anchor, offsetX, offsetY, Gravity.LEFT) }
    }
    private fun dismissGuide() { guide?.dismiss(); guide = null; guideCells = emptyList(); guideBounds = null; pendingGuide = null }
    private fun showSymbols(anchor: View) {
        cancelTouches()
        val capturedGeneration = generation
        val capturedPaletteRevision = paletteRevision
        palette = CharacterPalette(context) { text ->
            if (capturedGeneration == generation && capturedPaletteRevision == paletteRevision) action(BasicSkkAction.TouchText(text, kanaMode = controller.kanaMode))
        }.also { it.show(anchor) }
    }
    private fun backspaceKey(): Button {
        val capturedGeneration = generation
        return key("⌫") { action(BasicSkkAction.Backspace) }.also { button ->
            cancellations += attachBackspaceRepeat(button, { capturedGeneration == generation }) { action(BasicSkkAction.Backspace) }
        }
    }
    private fun key(label: String, function: Boolean = true, alternative: Boolean = false, click: (Button) -> Unit): Button {
        val currentGeneration = generation
        return (if (alternative) AlternativeFunctionButton(context) else Button(context)).apply {
            text = label; contentDescription = label; setTextColor(keyboardColors.text); textSize = if (label.length > 3) 12f else 16f
            gravity = Gravity.CENTER; includeFontPadding = false
            maxLines = 2; isAllCaps = false; minWidth = 0; minHeight = 0; setPadding(0,0,0,0)
            background = StateListDrawable().apply {
                fun face(color: Int) = GradientDrawable().apply { setColor(color); setStroke(1, keyboardColors.border) }
                addState(intArrayOf(android.R.attr.state_pressed), face(keyboardColors.pressed))
                addState(intArrayOf(), face(if (function) keyboardColors.functionBackground else keyboardColors.characterBackground))
            }
            stateListAnimator = null
            setOnClickListener { if (currentGeneration == generation) click(this) }
        }
    }
    private fun params() = LayoutParams(0, LayoutParams.MATCH_PARENT, 1f)
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}

/** 主文字の中心を動かさず、修飾と中央へ戻る方向を下側へ控えめに描きます。 */
@android.annotation.SuppressLint("AppCompatCustomView")
internal class FlickGuideCell(context: Context) : TextView(context) {
    internal var hintSymbol: String? = null
    internal var returnDirection: TenKeyController.Direction? = null
    internal var modifierActive = false
    internal val hintTextScale = .55f
    internal val hintAlpha = 150
    private val hintPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val markPath = Path()

    init { includeFontPadding = false }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val symbol = hintSymbol ?: return
        val density = resources.displayMetrics.density
        hintPaint.color = currentTextColor
        hintPaint.alpha = hintAlpha
        hintPaint.textSize = textSize * hintTextScale
        hintPaint.strokeWidth = 1.2f * density
        hintPaint.strokeCap = Paint.Cap.ROUND
        hintPaint.strokeJoin = Paint.Join.ROUND
        val arrowSize = hintPaint.textSize * .75f
        val gap = 3f * density
        val symbolWidth = hintPaint.measureText(symbol)
        val left = (width - arrowSize - gap - symbolWidth) / 2f
        val baseline = height - 3f * density - hintPaint.fontMetrics.descent
        val centerY = baseline + (hintPaint.fontMetrics.ascent + hintPaint.fontMetrics.descent) / 2f
        hintPaint.style = Paint.Style.FILL
        canvas.drawText(symbol, left + arrowSize + gap, baseline, hintPaint)
        hintPaint.style = Paint.Style.STROKE
        val half = arrowSize / 2f
        val centerX = left + half
        markPath.reset()
        val returning = returnDirection
        if (returning != null) {
            // 左・右・上・下の各セルから中央へ向かう矢印です。
            val rotation = when (returning) {
                TenKeyController.Direction.LEFT -> 0f
                TenKeyController.Direction.UP -> 90f
                TenKeyController.Direction.RIGHT -> 180f
                TenKeyController.Direction.DOWN -> 270f
                else -> 0f
            }
            canvas.save()
            canvas.rotate(rotation, centerX, centerY)
            markPath.moveTo(centerX - half, centerY)
            markPath.lineTo(centerX + half, centerY)
            markPath.moveTo(centerX, centerY - half)
            markPath.lineTo(centerX + half, centerY)
            markPath.lineTo(centerX, centerY + half)
            canvas.drawPath(markPath, hintPaint)
            canvas.restore()
            if (modifierActive) {
                val symbolLeft = left + arrowSize + gap
                canvas.drawLine(symbolLeft, centerY, symbolLeft + symbolWidth, centerY, hintPaint)
            }
        } else if (modifierActive) {
            markPath.moveTo(left, centerY)
            markPath.lineTo(left + arrowSize * .35f, centerY + arrowSize * .35f)
            markPath.lineTo(left + arrowSize, centerY - arrowSize * .35f)
            canvas.drawPath(markPath, hintPaint)
        }
    }
}
