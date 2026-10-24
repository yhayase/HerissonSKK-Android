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
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import se.haya.skk.settings.TenKeyReadingModes
import se.haya.skk.settings.TenKeyReadingModeStore
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
import se.haya.skk.settings.ScreenKeyboardLayout
import se.haya.skk.settings.TouchKeyboardSettings
import se.haya.skk.settings.TenKeyOperation

/** かな・英字・数字を直接送る10キー面です。 */
internal class TenKeyKeyboardView(context: Context, private val settings: TouchKeyboardSettings,
    internal val controller: TenKeyController = TenKeyController(),
    private val send: (TouchKeyboardCommand) -> Unit) : LinearLayout(context) {
    internal var onSwitchLayout: (() -> Unit)? = null
    private var layoutSwitchKey: LayoutSwitchKey? = null
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
    private var readingModes = TenKeyReadingModeStore.read(context)
    private var skkEnabled = true
    private var readingConversionEnabled = false
    fun updateReadingConversion(enabled: Boolean) {
        if (readingConversionEnabled != enabled) cancelTouches()
        readingConversionEnabled = enabled
    }
    private var readingButton: Button? = null
    private var shiftPending = false
    fun updateShiftPending(pending: Boolean) {
        shiftPending = pending
        updateReadingIndicator()
    }
    fun setSkkEnabled(enabled: Boolean) {
        if (skkEnabled != enabled) cancelTouches()
        skkEnabled = enabled
        readingButton?.isEnabled = enabled
        updateReadingIndicator()
    }
    private var primary: Button? = null
    private var primaryLabel = "⏎"
    private var primaryEnabled = true
    init { orientation = VERTICAL; isMotionEventSplittingEnabled = false; rebuild() }
    fun cancelTouches() { layoutSwitchKey?.cancelInteraction(); paletteRevision++; cancellations.toList().forEach { it() }; dismissGuide(); palette?.dismiss(); palette = null }
    fun dispose() { cancelTouches(); layoutSwitchKey?.dispose(); generation++ }
    fun updatePrimaryAction(label: String, enabled: Boolean) {
        primaryLabel = label; primaryEnabled = enabled
        primary?.apply { text = label; updateSymbolWeight(); contentDescription = label; setTextColor(keyboardColors.text); isEnabled = enabled }
    }
    fun updateSpaceAction(convert: Boolean) {
        spaceConverts = convert
        spaceButton?.apply { text = if (convert) "変換" else "␣"; updateSymbolWeight(); contentDescription = if (convert) "変換" else "空白" }
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
        cancelTouches(); layoutSwitchKey?.dispose(); generation++; removeAllViews(); cancellations.clear()
        val groups = controller.rows()
        for (rowIndex in 0..3) {
            val row = LinearLayout(context).apply { orientation = HORIZONTAL; isMotionEventSplittingEnabled = false }
            row.addView(when (rowIndex) {
                0 -> key(when(controller.face) { TenKeyController.Face.KANA -> "ABC"; TenKeyController.Face.ALPHA -> "123"; else -> "かな" }) { controller.cycleFace(); rebuild() }
                1 -> kindKey()
                2 -> key("記号") { showSymbols(it) }
                else -> {
                    val currentGeneration = generation
                    val button = LayoutSwitchKey(context, ScreenKeyboardLayout.QWERTY,
                        onSwitchLayout = { onSwitchLayout?.invoke() },
                        onSwitchIme = { send(TouchKeyboardCommand.SwitchIme) },
                        onOpenSettings = { send(TouchKeyboardCommand.OpenSettings) },
                        onAdjustGeometry = { cancelTouches(); onAdjustGeometry?.invoke() },
                        isCurrent = { currentGeneration == generation },
                    )
                    layoutSwitchKey = button
                    val description = button.contentDescription
                    key("", supplied = button) {}.apply { contentDescription = description }
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
                2 -> readingKey()
                else -> key(primaryLabel) { action(BasicSkkAction.Enter) }.also { primary = it; it.isEnabled = primaryEnabled }
            }, params())
            addView(row, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
        }
    }
    private fun autoReading(face: TenKeyController.Face): Boolean = when (face) {
        TenKeyController.Face.KANA -> readingModes.kana
        TenKeyController.Face.ALPHA -> readingModes.alpha
        TenKeyController.Face.NUMBER -> readingModes.number
    }
    private fun toggleReadingMode(): Boolean {
        if (!skkEnabled) return false
        val next = when (controller.face) {
            TenKeyController.Face.KANA -> readingModes.copy(kana = !readingModes.kana)
            TenKeyController.Face.ALPHA -> readingModes.copy(alpha = !readingModes.alpha)
            TenKeyController.Face.NUMBER -> readingModes.copy(number = !readingModes.number)
        }
        if (!TenKeyReadingModeStore.save(context, next)) return false
        readingModes = next
        updateReadingIndicator()
        return true
    }
    private fun updateReadingIndicator() {
        val button = readingButton as? ReadingModeButton ?: return
        val enabled = autoReading(controller.face)
        button.isSelected = enabled && skkEnabled
        button.showAuto = enabled && skkEnabled
        button.showShift = shiftPending && skkEnabled
        val face = when (controller.face) {
            TenKeyController.Face.KANA -> "かな面"
            TenKeyController.Face.ALPHA -> "英字面"
            TenKeyController.Face.NUMBER -> "数字面"
        }
        ViewCompat.setStateDescription(button, if (skkEnabled) "$face、自動読み開始${if (enabled) "オン" else "オフ"}、シフト${if (shiftPending) "オン" else "オフ"}、タップで読み開始・送り境界、長押しで自動読み開始切替" else "$face、読み操作無効")
    }
    @android.annotation.SuppressLint("ClickableViewAccessibility")
    private fun readingKey(): Button {
        val capturedGeneration = generation
        val button = key("⇧") {
            if (skkEnabled) action(BasicSkkAction.StartTouchReading(controller.kanaMode))
        }
        readingButton = button
        button.contentDescription = "読み開始・送り境界、長押しで自動読み開始切替"
        button.isEnabled = skkEnabled
        updateReadingIndicator()
        button.accessibilityDelegate = object : View.AccessibilityDelegate() {
            override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfo) {
                super.onInitializeAccessibilityNodeInfo(host, info)
                AccessibilityNodeInfoCompat.wrap(info).stateDescription = ViewCompat.getStateDescription(host)
                if (skkEnabled) info.addAction(AccessibilityNodeInfo.AccessibilityAction(R.id.toggle_ten_key_reading, "この面の自動読み開始を${if (autoReading(controller.face)) "オフ" else "オン"}にする"))
            }
            override fun performAccessibilityAction(host: View, action: Int, args: Bundle?): Boolean =
                if (action == R.id.toggle_ten_key_reading) {
                    if (capturedGeneration != generation || !toggleReadingMode()) false
                    else { cancelPendingShiftAfterAutoToggle(); true }
                }
                else super.performAccessibilityAction(host, action, args)
        }
        var active = false
        var startX = 0f
        var startY = 0f
        var moved = false
        var longPressed = false
        val longPress = Runnable {
            if (active && !moved && capturedGeneration == generation && skkEnabled) {
                longPressed = true
                button.performLongClick()
            }
        }
        fun clear() { button.removeCallbacks(longPress); active = false; moved = false; button.isPressed = false }
        cancellations += { clear() }
        fun sample(x: Float, y: Float) {
            val dx = x - startX
            val dy = y - startY
            val slop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
            if (kotlin.math.hypot(dx, dy) > slop) { moved = true; button.removeCallbacks(longPress) }
        }
        button.setOnLongClickListener {
            if (capturedGeneration != generation || !skkEnabled || !toggleReadingMode()) false
            else { cancelPendingShiftAfterAutoToggle(); true }
        }
        button.setOnTouchListener { _, event ->
            if (capturedGeneration != generation || !skkEnabled || event.pointerCount > 1) { clear(); return@setOnTouchListener true }
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> { clear(); active = true; longPressed = false; startX = event.x; startY = event.y; button.isPressed = true; button.postDelayed(longPress, ViewConfiguration.getLongPressTimeout().toLong()) }
                MotionEvent.ACTION_MOVE -> if (active) {
                    for (index in 0 until event.historySize) sample(event.getHistoricalX(index), event.getHistoricalY(index))
                    sample(event.x, event.y)
                }
                MotionEvent.ACTION_UP -> if (active) {
                    for (index in 0 until event.historySize) sample(event.getHistoricalX(index), event.getHistoricalY(index))
                    sample(event.x, event.y)
                    val tap = !moved
                    clear()
                    if (tap && !longPressed) button.performClick()
                }
                MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_POINTER_DOWN -> clear()
            }
            true
        }
        return button
    }
    private fun cancelPendingShiftAfterAutoToggle() {
        if (shiftPending) action(BasicSkkAction.CancelTouchShift)
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
        fun vibrate(feedback: Int) {
            if (settings.vibrateOnBoundary && (context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator)?.hasVibrator() == true) button.performHapticFeedback(feedback)
        }
        val secondCancelPulse = Runnable { if (capturedGeneration == generation && skkEnabled) vibrate(HapticFeedbackConstants.KEYBOARD_TAP) }
        val hold = Runnable {
            val active = gesture
            if (capturedGeneration == generation && skkEnabled && !kind && active?.hold() == true) {
                vibrate(HapticFeedbackConstants.LONG_PRESS)
                showGuide(button, group, direction, active)
            }
        }
        val delayedGuide = Runnable { gesture?.takeIf { it.active }?.let { showGuide(button, group, direction, it) } }
        fun clear(keepCancelPulse: Boolean = false) { if (!keepCancelPulse) button.removeCallbacks(secondCancelPulse); button.removeCallbacks(hold); button.removeCallbacks(delayedGuide); gesture?.cancel(); gesture = null; button.isPressed = false; dismissGuide() }
        cancellations += { clear() }
        fun sample(event: MotionEvent, reschedule: Boolean) {
            val active = gesture ?: return
            val revision = active.motionRevision
            fun move(x: Float, y: Float) {
                val hadBoundary = active.boundary
                direction = active.move(x, y)
                if (hadBoundary && !active.boundary) {
                    button.removeCallbacks(secondCancelPulse)
                    vibrate(HapticFeedbackConstants.KEYBOARD_TAP)
                    button.postDelayed(secondCancelPulse, 60)
                }
            }
            for (index in 0 until event.historySize) move(event.getHistoricalX(index), event.getHistoricalY(index))
            move(event.x, event.y)
            if (revision != active.motionRevision) {
                button.removeCallbacks(hold)
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
                    gesture = TenKeyGesture(maxOf(ViewConfiguration.get(context).scaledTouchSlop.toFloat(), minOf(button.width, button.height) * .2f), if(kind) TenKeyOperation.FLICK else settings.operation, ViewConfiguration.get(context).scaledTouchSlop.toFloat(), 2f * resources.displayMetrics.density, button.width.toFloat(), button.height.toFloat(),
                        { selected, modifier -> group?.let { controller.output(it, selected, modifier) } }).also { it.start(event.x, event.y) }
                    direction = TenKeyController.Direction.CENTER; button.isPressed = true
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
                    clear(keepCancelPulse = true)
                }
                MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_POINTER_DOWN -> clear()
            }
            true
        }
        return button
    }
    private fun emit(group: List<String>, direction: TenKeyController.Direction, modifier: TenKeyController.Direction?, boundary: Boolean) {
        controller.output(group, direction, modifier)?.let {
            action(BasicSkkAction.TouchText(it, skkEnabled && (boundary || controller.nextBoundary), controller.kanaMode,
                autoStartReading = autoReading(controller.face) && skkEnabled))
            controller.nextBoundary = false
        }
    }
    private fun showGuide(anchor: Button, group: List<String>?, direction: TenKeyController.Direction, gesture: TenKeyGesture?) {
        val options = group?.let { controller.modifiedRow(it, gesture?.modifier) }
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
            if (candidate == TenKeyController.Direction.CENTER) modifierSymbol(gesture?.modifier) else returnHint(candidate)
        }
        val labels = TenKeyController.Direction.entries.map { candidate ->
            if (candidate == TenKeyController.Direction.CENTER) chosen else options.getOrNull(candidate.ordinal).orEmpty()
        }
        pendingGuide = (if ((gesture?.boundary == true)) "境界 " else "") + labels.mapIndexed { index, value ->
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
            val content = FlickGuideLayout(context)
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
            cell.modifierActive = hints[index] != null && (candidate == TenKeyController.Direction.CENTER || gesture?.modifier == candidate)
            cell.invalidate()
            val description = when {
                candidate == TenKeyController.Direction.CENTER -> group?.let { controller.modifierLabel(it, gesture?.modifier) }?.let { "$label $it 選択中" } ?: label
                group != null && hints[index] != null -> {
                    val effect = if (gesture?.modifier == candidate) "解除" else controller.modifierLabel(group, candidate)
                    "$label " + if (direction == candidate) "戻すと$effect" else "往復で$effect"
                }
                group == null && candidate == TenKeyController.Direction.UP && label == "カナ" -> "カナで確定"
                group == null && candidate == TenKeyController.Direction.RIGHT && label == "ｶﾅ" -> "半角カナで確定"
                else -> label
            }
            cell.contentDescription = (if (candidate == TenKeyController.Direction.CENTER && (gesture?.boundary == true)) "境界 " else "") + description
            cell.background = GradientDrawable().apply {
                setColor(if (candidate == TenKeyController.Direction.CENTER) keyboardColors.selectedBackground else keyboardColors.panelBackground)
                setStroke(dp(1), keyboardColors.border)
            }
        }
        (popup.contentView as FlickGuideLayout).apply {
            outlineCells = assigned.map { (column(it) - minColumn) to (row(it) - minRow) }.toSet()
            outlineCellWidth = cellWidth.toFloat(); outlineCellHeight = cellHeight.toFloat()
            outlineColor = keyboardColors.text
            boundary = (gesture?.boundary == true)
            invalidate()
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
        val originFace = controller.face
        val originKanaMode = controller.kanaMode
        palette = CharacterPalette(context) { text ->
            if (capturedGeneration == generation && capturedPaletteRevision == paletteRevision) action(BasicSkkAction.TouchText(text, kanaMode = originKanaMode,
                autoStartReading = autoReading(originFace) && skkEnabled))
        }.also { it.show(anchor) }
    }
    private fun backspaceKey(): Button {
        val capturedGeneration = generation
        return key("⌫") { action(BasicSkkAction.Backspace) }.also { button ->
            cancellations += attachBackspaceRepeat(button, { capturedGeneration == generation }) { action(BasicSkkAction.Backspace) }
        }
    }
    private fun key(label: String, function: Boolean = true, alternative: Boolean = false, supplied: Button? = null, click: (Button) -> Unit): Button {
        val currentGeneration = generation
        return (supplied ?: if (label == "⇧") ReadingModeButton(context) else if (alternative) AlternativeFunctionButton(context) else Button(context)).apply {
            text = label; contentDescription = label; setTextColor(keyboardColors.text); textSize = if (label.length > 3) 12f else 16f
            updateSymbolWeight()
            gravity = Gravity.CENTER; includeFontPadding = false
            maxLines = 2; isAllCaps = false; minWidth = 0; minHeight = 0; setPadding(0,0,0,0)
            background = StateListDrawable().apply {
                fun face(color: Int) = GradientDrawable().apply { setColor(color); setStroke(1, keyboardColors.border) }
                addState(intArrayOf(android.R.attr.state_pressed), face(keyboardColors.pressed))
                addState(intArrayOf(android.R.attr.state_selected), GradientDrawable().apply { setColor(keyboardColors.selectedBackground); setStroke(dp(2), keyboardColors.text) })
                addState(intArrayOf(), face(if (function) keyboardColors.functionBackground else keyboardColors.characterBackground))
            }
            stateListAnimator = null
            if (supplied == null) setOnClickListener { if (currentGeneration == generation) click(this) }
        }
    }
    private fun Button.updateSymbolWeight() {
        // 記号のフォールバックフォントも、他のキートップに近い線幅へ揃えます。
        paint.isFakeBoldText = text.toString() in setOf("⌫", "␣", "⇧", "⏎")
        invalidate()
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

/** 隣り合うセルの共有辺を除き、十字形の外周だけを強調します。 */
internal class FlickGuideLayout(context: Context) : FrameLayout(context) {
    internal var outlineCells = emptySet<Pair<Int, Int>>()
    internal var outlineCellWidth = 0f
    internal var outlineCellHeight = 0f
    internal var outlineColor = Color.BLACK
    internal var boundary = false
    private val outlinePath = Path()
    private val outlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeJoin = Paint.Join.ROUND }
    internal fun outlineEdges(): List<List<Float>> = buildList {
        val w = outlineCellWidth; val h = outlineCellHeight
        outlineCells.forEach { (x, y) ->
            if ((x to (y - 1)) !in outlineCells) add(listOf(x * w, y * h, (x + 1) * w, y * h))
            if (((x + 1) to y) !in outlineCells) add(listOf((x + 1) * w, y * h, (x + 1) * w, (y + 1) * h))
            if ((x to (y + 1)) !in outlineCells) add(listOf((x + 1) * w, (y + 1) * h, x * w, (y + 1) * h))
            if (((x - 1) to y) !in outlineCells) add(listOf(x * w, (y + 1) * h, x * w, y * h))
        }
    }
    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        if (!boundary) return
        outlinePaint.color = outlineColor
        outlinePaint.strokeWidth = 4f * resources.displayMetrics.density
        // 窓の端でも太線が欠けないよう、外周の端点だけ半線幅内側へ寄せます。
        val inset = outlinePaint.strokeWidth / 2
        val edges = outlineEdges().toMutableList()
        outlinePath.reset()
        fun drawX(value: Float) = value.coerceIn(minOf(inset, width / 2f), maxOf(width - inset, width / 2f))
        fun drawY(value: Float) = value.coerceIn(minOf(inset, height / 2f), maxOf(height - inset, height / 2f))
        while (edges.isNotEmpty()) {
            val first = edges.removeAt(0)
            outlinePath.moveTo(drawX(first[0]), drawY(first[1]))
            var endX = first[2]; var endY = first[3]
            outlinePath.lineTo(drawX(endX), drawY(endY))
            while (true) {
                val next = edges.indexOfFirst { it[0] == endX && it[1] == endY }
                if (next < 0) break
                val edge = edges.removeAt(next)
                endX = edge[2]; endY = edge[3]
                outlinePath.lineTo(drawX(endX), drawY(endY))
            }
            outlinePath.close()
        }
        canvas.drawPath(outlinePath, outlinePaint)
    }
}

/** 中央の矢印はシフト中に塗りつぶし、下側のランプで自動読み開始を示します。 */
@android.annotation.SuppressLint("AppCompatCustomView")
internal class ReadingModeButton(context: Context) : Button(context) {
    internal var showAuto = false
        set(value) { field = value; invalidate() }
    internal var showShift = false
        set(value) { field = value; invalidate() }
    private val symbolPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeJoin = Paint.Join.ROUND }
    private val arrowPath = Path()
    internal fun arrowBounds(): android.graphics.RectF {
        val size = minOf(textSize * 1.3f, width * .5f, height * .5f)
        return android.graphics.RectF((width - size) / 2f, (height - size) / 2f,
            (width + size) / 2f, (height + size) / 2f)
    }
    internal fun lampBounds(): android.graphics.RectF {
        val density = resources.displayMetrics.density
        val radius = minOf(3.5f * density, height * .07f)
        val cx = width - 9f * density
        val cy = height - 9f * density
        return android.graphics.RectF(cx - radius, cy - radius, cx + radius, cy + radius)
    }
    override fun onDraw(canvas: Canvas) {
        // フォントごとの矢印形状の差を避け、輪郭と塗りつぶしに同じパスを使います。
        val bounds = arrowBounds()
        val size = bounds.width()
        arrowPath.reset()
        arrowPath.moveTo(bounds.centerX(), bounds.top)
        arrowPath.lineTo(bounds.right, bounds.top + size * .48f)
        arrowPath.lineTo(bounds.centerX() + size * .22f, bounds.top + size * .48f)
        arrowPath.lineTo(bounds.centerX() + size * .22f, bounds.bottom)
        arrowPath.lineTo(bounds.centerX() - size * .22f, bounds.bottom)
        arrowPath.lineTo(bounds.centerX() - size * .22f, bounds.top + size * .48f)
        arrowPath.lineTo(bounds.left, bounds.top + size * .48f)
        arrowPath.close()
        symbolPaint.color = currentTextColor
        symbolPaint.alpha = if (isEnabled) 255 else 100
        symbolPaint.strokeWidth = 1.5f * resources.displayMetrics.density
        symbolPaint.style = if (showShift) Paint.Style.FILL_AND_STROKE else Paint.Style.STROKE
        canvas.drawPath(arrowPath, symbolPaint)
        val hintPaint = symbolPaint
        hintPaint.color = currentTextColor
        hintPaint.alpha = 130
        hintPaint.style = Paint.Style.FILL
        hintPaint.textAlign = Paint.Align.CENTER
        hintPaint.textSize = minOf(10f * resources.displayMetrics.scaledDensity, height * .2f)
        val hintMetrics = hintPaint.fontMetrics
        val hintBaseline = height - 2f * resources.displayMetrics.density - hintMetrics.descent
        canvas.drawText("…", width / 2f, hintBaseline, hintPaint)
        val lamp = lampBounds()
        val density = resources.displayMetrics.density
        symbolPaint.style = Paint.Style.FILL
        symbolPaint.alpha = if (isEnabled) 255 else 100
        symbolPaint.color = Color.rgb(24, 34, 28)
        canvas.drawOval(android.graphics.RectF(lamp.left - density, lamp.top - density,
            lamp.right + density, lamp.bottom + density), symbolPaint)
        symbolPaint.color = if (showAuto && isEnabled) Color.rgb(74, 210, 126) else Color.rgb(105, 119, 110)
        symbolPaint.alpha = if (isEnabled && showAuto) 255 else 145
        canvas.drawOval(lamp, symbolPaint)
        if (isEnabled && showAuto) {
            symbolPaint.color = Color.WHITE
            symbolPaint.alpha = 105
            val highlight = density * .65f
            canvas.drawOval(android.graphics.RectF(lamp.left + density, lamp.top + density,
                lamp.left + density + highlight, lamp.top + density + highlight), symbolPaint)
        }
    }
}
