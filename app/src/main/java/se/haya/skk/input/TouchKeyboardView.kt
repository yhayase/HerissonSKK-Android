package se.haya.skk.input

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowInsets
import android.widget.Button
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView
import se.haya.skk.CandidateStatusView
import se.haya.skk.core.BasicSkkAction
import se.haya.skk.core.InputMode

/** 下部の候補領域と4段の文字面を持ち、物理側の補助行は作りません。 */
internal class TouchKeyboardView(
    context: Context,
    private val onCommand: (TouchKeyboardCommand) -> Unit,
) : LinearLayout(context) {
    val candidateStatusView = CandidateStatusView(context)
    private val controller = TouchKeyboardController()
    private val characterArea = LinearLayout(context).apply {
        orientation = VERTICAL
        isMotionEventSplittingEnabled = true
    }
    private val characterButtons = mutableListOf<Pair<Button, String>>()
    private val cancelGestures = mutableListOf<() -> Unit>()
    private val refreshGuides = mutableListOf<() -> Unit>()
    private var shiftButton: Button? = null
    private var modeButton: Button? = null
    private var primaryButton: Button? = null
    private var inputMode: InputMode? = null
    private var modeEnabled = true
    private var primaryLabel = "↵"
    private var primaryEnabled = true
    private var bottomInset = 0
    private var charactersVisible = true
    private var guide: PopupWindow? = null
    private var guideOwner: View? = null
    internal var visibleFlickGuide: FlickGuide? = null
        private set
    private var modeMenu: PopupWindow? = null
    /** テストと表示更新で共有する、現在選択中のフリック候補です。 */
    internal var activeFlickGuide: TouchKeyboardController.Flick? = null
        private set

    init {
        orientation = VERTICAL
        isMotionEventSplittingEnabled = true
        setBackgroundColor(0xffe8eaed.toInt())
        setOnApplyWindowInsetsListener { _, insets ->
            bottomInset = if (Build.VERSION.SDK_INT >= 30) {
                insets.getInsets(WindowInsets.Type.navigationBars()).bottom
            } else {
                @Suppress("DEPRECATION")
                insets.systemWindowInsetBottom
            }
            applyBottomInset()
            insets
        }
        addView(candidateStatusView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(characterArea, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        rebuildKeys()
    }

    fun setCharacterAreaVisible(visible: Boolean) {
        if (charactersVisible && !visible) cancelTouches()
        charactersVisible = visible
        characterArea.visibility = if (visible) View.VISIBLE else View.GONE
        applyBottomInset()
    }

    private fun applyBottomInset() = setPadding(0, 0, 0, if (charactersVisible) bottomInset else 0)

    fun configurationChanged() { rebuildKeys() }

    fun resetForEditorSession() {
        cancelTouches()
        controller.reset()
        rebuildKeys()
    }

    fun updateMode(mode: InputMode?, enabled: Boolean = true) {
        inputMode = mode
        modeEnabled = enabled
        modeButton?.apply {
            text = modeLabel(mode)
            contentDescription = "入力モード ${modeLabel(mode)}"
            isEnabled = enabled
        }
        if (!enabled) { modeMenu?.dismiss(); modeMenu = null }
    }

    fun updatePrimaryAction(label: String, enabled: Boolean = true) {
        primaryLabel = label
        primaryEnabled = enabled
        primaryButton?.apply { text = label; contentDescription = label; isEnabled = enabled }
    }

    override fun onDetachedFromWindow() {
        cancelTouches()
        super.onDetachedFromWindow()
    }

    private fun cancelTouches() {
        cancelGestures.forEach { it() }
        controller.cancelPointers()
        guide?.dismiss(); guide = null; guideOwner = null; visibleFlickGuide = null
        activeFlickGuide = null
        modeMenu?.dismiss(); modeMenu = null
        refreshShift()
    }

    private fun rebuildKeys() {
        cancelTouches()
        characterArea.removeAllViews()
        characterButtons.clear()
        cancelGestures.clear()
        refreshGuides.clear()
        shiftButton = null
        if (controller.page == TouchKeyboardController.Page.LETTERS) {
            TouchKeyboardController.LETTER_ROWS.forEachIndexed { index, letters ->
                val row = newRow()
                if (index == 1) row.setPadding(dp(12), 0, dp(12), 0)
                if (index == 2) row.addView(makeShiftKey().also { shiftButton = it }, keyParams())
                letters.forEach { row.addView(textKey(it.toString()), keyParams()) }
                if (index == 2) {
                    row.addView(textKey("-"), keyParams())
                    row.addView(commandKey("⌫") { send(BasicSkkAction.Backspace) }, keyParams())
                }
                characterArea.addView(row)
            }
        } else {
            val rows = if (controller.page == TouchKeyboardController.Page.SYMBOLS_1)
                TouchKeyboardController.SYMBOL_ROWS_1 else TouchKeyboardController.SYMBOL_ROWS_2
            rows.forEachIndexed { index, values ->
                val row = newRow()
                if (index == 2) row.addView(commandKey(
                    if (controller.page == TouchKeyboardController.Page.SYMBOLS_1) "記号2" else "記号1",
                ) { controller.toggleSymbolPage(); rebuildKeys() }, keyParams(1.3f))
                values.forEach { row.addView(textKey(it), keyParams()) }
                if (index == 2) row.addView(commandKey("⌫") { send(BasicSkkAction.Backspace) }, keyParams(1.3f))
                characterArea.addView(row)
            }
        }
        val bottom = newRow()
        bottom.addView(commandKey(if (controller.page == TouchKeyboardController.Page.LETTERS) "123" else "ABC") {
            controller.togglePage(); rebuildKeys()
        }, keyParams(1.1f))
        modeButton = commandKey(modeLabel(inputMode)) {
            val target = if (inputMode == InputMode.DIRECT || inputMode == InputMode.FULLWIDTH)
                InputMode.HIRAGANA else InputMode.DIRECT
            send(BasicSkkAction.SetInputMode(target))
        }.apply { setOnLongClickListener { showModeMenu(this); true } }
        bottom.addView(modeButton, keyParams(1.1f))
        bottom.addView(commandKey("🌐") { onCommand(TouchKeyboardCommand.SwitchIme) }.apply {
            contentDescription = "IME切替"
        }, keyParams())
        bottom.addView(commandKey("Space") { onCommand(TouchKeyboardCommand.SpaceOrConvert) }, keyParams(3f))
        bottom.addView(textKey(","), keyParams())
        bottom.addView(textKey("."), keyParams())
        primaryButton = commandKey(primaryLabel) { send(BasicSkkAction.Enter) }
        bottom.addView(primaryButton, keyParams(1.5f))
        characterArea.addView(bottom)
        refreshShift()
        updateMode(inputMode, modeEnabled)
        updatePrimaryAction(primaryLabel, primaryEnabled)
    }

    private fun refreshShift() {
        shiftButton?.isSelected = controller.shifted
        shiftButton?.text = if (controller.shifted) "⇧●" else "⇧"
        shiftButton?.contentDescription = if (controller.shifted) "Shift 有効" else "Shift"
        characterButtons.forEach { (button, value) ->
            button.text = if (controller.shifted && controller.page == TouchKeyboardController.Page.LETTERS)
                value.uppercase() else value
            button.contentDescription = button.text
        }
        refreshGuides.forEach { it() }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun makeShiftKey(): Button = commandKey("⇧") { controller.toggleShift(); refreshShift() }.apply {
        var pointer = -1
        cancelGestures += { pointer = -1; isPressed = false }
        setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    pointer = event.getPointerId(0)
                    controller.pressShift(); isPressed = true; refreshShift()
                }
                MotionEvent.ACTION_UP -> {
                    if (pointer == event.getPointerId(event.actionIndex)) controller.releaseShift()
                    pointer = -1; isPressed = false; refreshShift()
                }
                MotionEvent.ACTION_CANCEL -> {
                    controller.releaseShift(cancelled = true)
                    pointer = -1; isPressed = false; refreshShift()
                }
            }
            true
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun textKey(value: String): Button {
        var capturedShift: Boolean? = null
        val button = FlickHintButton(context).apply {
            configureFlickHints(value, controller.up(value), controller.down(value))
            configureKey(value)
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX,
                minOf(24f * resources.displayMetrics.scaledDensity, dp(28).toFloat()))
            setOnClickListener {
            onCommand(controller.text(value, shiftAtPress = capturedShift ?: controller.captureShift()))
            capturedShift = null
            refreshShift()
            }
        }
        characterButtons += button to value
        val gesture = KeyFlickGesture(maxOf(ViewConfiguration.get(context).scaledTouchSlop.toFloat(), keyHeight() * .22f))
        var pointer = -1
        var guideWasShown = false
        var guideCenter = value
        val showGuide = Runnable {
            if (pointer >= 0 && gesture.isActive && (guideOwner == null || guideOwner === button)) {
                guideWasShown = true
                showGuide(button, value, guideCenter, TouchKeyboardController.Flick.TAP)
            }
        }
        fun clear() {
            button.removeCallbacks(showGuide)
            if (guideOwner === button || (guideOwner == null && guideWasShown)) {
                guide?.dismiss(); guide = null; guideOwner = null; visibleFlickGuide = null
                activeFlickGuide = null
            }
            gesture.cancel(); pointer = -1; capturedShift = null; guideWasShown = false; button.isPressed = false
        }
        cancelGestures += ::clear
        refreshGuides += {
            if (pointer >= 0) {
                guideCenter = controller.previewText(value, TouchKeyboardController.Flick.TAP, capturedShift ?: false)
                if (guideWasShown && guideOwner === button) {
                    showGuide(button, value, guideCenter, activeFlickGuide)
                }
            }
        }
        button.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    pointer = event.getPointerId(0)
                    capturedShift = controller.captureShift()
                    guideCenter = controller.previewText(value, TouchKeyboardController.Flick.TAP, capturedShift ?: false)
                    gesture.start(event.x, event.y)
                    button.isPressed = true
                    button.postDelayed(showGuide, GUIDE_DELAY_MS)
                }
                MotionEvent.ACTION_MOVE -> {
                    val index = event.findPointerIndex(pointer)
                    if (index < 0) clear()
                    else {
                        val direction = gesture.direction(event.getX(index), event.getY(index))
                        if (direction != null) {
                            button.removeCallbacks(showGuide)
                            when (direction) {
                                TouchKeyboardController.Flick.UP,
                                TouchKeyboardController.Flick.DOWN -> {
                                    guideWasShown = true
                                    showGuide(button, value, guideCenter, direction)
                                }
                                TouchKeyboardController.Flick.TAP -> if (guideWasShown)
                                    showGuide(button, value, guideCenter, direction)
                            }
                        } else if (guideWasShown) {
                            showGuide(button, value, guideCenter, null)
                        }
                    }
                }
                MotionEvent.ACTION_UP -> {
                    val index = event.findPointerIndex(pointer)
                    if (index >= 0) {
                        val direction = gesture.finish(event.getX(index), event.getY(index))
                        when {
                            direction == TouchKeyboardController.Flick.TAP -> button.performClick()
                            direction == TouchKeyboardController.Flick.UP && controller.up(value) != null ||
                                direction == TouchKeyboardController.Flick.DOWN && controller.down(value) != null -> {
                                onCommand(controller.text(value, checkNotNull(direction), capturedShift ?: false))
                                refreshShift()
                            }
                        }
                    }
                    clear()
                }
                MotionEvent.ACTION_CANCEL -> clear()
            }
            true
        }
        return button
    }

    private fun showGuide(anchor: View, value: String, center: String, selection: TouchKeyboardController.Flick?) {
        activeFlickGuide = selection
        if (!anchor.isAttachedToWindow) return
        val up = controller.up(value)
        val down = controller.down(value)
        visibleFlickGuide?.takeIf { guideOwner === anchor && it.matches(center, up, down) }?.let {
            it.update(selection)
            return
        }
        guide?.dismiss()
        guideOwner = anchor
        val content = FlickGuide(context, center, up, down, selection)
        val width = dp(104)
        val height = dp(132)
        guide = PopupWindow(content, width, height, false).apply {
            isTouchable = false
            isFocusable = false
            isOutsideTouchable = false
            // 古いAndroidでもIME窓の上端でガイドが切れないようにします。
            isClippingEnabled = false
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            elevation = dp(6).toFloat()
        }
        visibleFlickGuide = content
        // IMEの子窓へ画面座標を渡すと原点が二重に加算されるため、キーからの相対位置を使います。
        guide?.showAsDropDown(anchor, (anchor.width - width) / 2,
            -anchor.height - height - dp(4), Gravity.START)
    }

    private fun showModeMenu(anchor: View) {
        if (!modeEnabled || !anchor.isAttachedToWindow) return
        modeMenu?.dismiss()
        val choices = LinearLayout(context).apply { orientation = VERTICAL }
        val popup = PopupWindow(choices, dp(180), LayoutParams.WRAP_CONTENT, false).apply {
            setBackgroundDrawable(ColorDrawable(Color.WHITE))
            isOutsideTouchable = true
            elevation = dp(4).toFloat()
        }
        listOf(InputMode.HIRAGANA to "ひらがな", InputMode.KATAKANA to "カタカナ",
            InputMode.HALFWIDTH to "半角カナ", InputMode.DIRECT to "直接英数",
            InputMode.FULLWIDTH to "全角英数").forEach { (mode, label) ->
            choices.addView(commandKey(label) {
                popup.dismiss(); modeMenu = null
                if (modeEnabled) send(BasicSkkAction.SetInputMode(mode))
            }, LayoutParams(LayoutParams.MATCH_PARENT, dp(44)))
        }
        modeMenu = popup
        popup.showAsDropDown(anchor, 0, -anchor.height - dp(220))
    }

    private fun send(action: BasicSkkAction) = onCommand(TouchKeyboardCommand.Skk(action))
    private fun newRow() = LinearLayout(context).apply {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER
        isMotionEventSplittingEnabled = true
    }
    private fun commandKey(label: String, action: () -> Unit) = Button(context).apply {
        configureKey(label)
        setOnClickListener { action() }
    }
    private fun Button.configureKey(label: String) {
        text = label
        textSize = if (resources.configuration.fontScale >= 1.5f) 13f else 16f
        gravity = Gravity.CENTER
        minWidth = 0; minHeight = 0
        setPadding(dp(1), 0, dp(1), 0)
        isAllCaps = false
        typeface = Typeface.DEFAULT
        contentDescription = label
    }
    private fun keyParams(weight: Float = 1f) = LayoutParams(0, keyHeight(), weight).apply {
        setMargins(dp(1), dp(1), dp(1), dp(1))
    }
    private fun keyHeight() = dp(if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) 38 else 46)
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun modeLabel(mode: InputMode?) = when (mode) {
        InputMode.DIRECT -> "A"
        InputMode.FULLWIDTH -> "Ａ"
        InputMode.KATAKANA -> "ア"
        InputMode.HALFWIDTH -> "ｱ"
        else -> "あ"
    }
    /** キー上の補助表示は本文を変えず、上下の候補だけを右隅に描きます。 */
    // IMEはAppCompatのActivityではないため、OSのButtonテーマと描画を維持します。
    @android.annotation.SuppressLint("AppCompatCustomView")
    internal class FlickHintButton(context: Context) : Button(context) {
        private var upHint: String? = null
        private var downHint: String? = null
        internal val displayedUpHint: String? get() = upHint
        internal val displayedDownHint: String? get() = downHint
        private val hintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xff6b7078.toInt()
            textAlign = Paint.Align.LEFT
        }

        internal data class GlyphLayout(
            val text: String,
            val anchorX: Float,
            val baseline: Float,
            val textSize: Float,
            val bounds: RectF,
        )

        fun configureFlickHints(value: String, up: String?, down: String?) {
            upHint = up?.takeUnless { value.length == 1 && value[0] in 'a'..'z' && it == value.uppercase() }
            downHint = down
        }

        override fun onDraw(canvas: android.graphics.Canvas) {
            val center = centerLabelLayout() ?: return
            val originalSize = paint.textSize
            val originalAlign = paint.textAlign
            paint.color = currentTextColor
            paint.textAlign = Paint.Align.LEFT
            paint.textSize = center.textSize
            canvas.drawText(center.text, center.anchorX, center.baseline, paint)
            paint.textSize = originalSize
            paint.textAlign = originalAlign

            hintLayouts(center.bounds).forEach { hint ->
                hintPaint.textSize = hint.textSize
                canvas.drawText(hint.text, hint.anchorX, hint.baseline, hintPaint)
            }
        }

        /** 背景の見える面で、実際に描く中心文字と重ならない右上下に配置します。 */
        internal fun hintLayouts(): List<GlyphLayout> =
            centerLabelLayout()?.let { hintLayouts(it.bounds) }.orEmpty()

        private fun hintLayouts(center: RectF): List<GlyphLayout> {
            val face = visibleFaceBounds()
            if (face.width() <= 0f || face.height() <= 0f) return emptyList()
            val inset = hintInset(face)
            fun layout(text: String, upper: Boolean): GlyphLayout {
                val maximum = minOf(resources.displayMetrics.scaledDensity * 10f, dp(10))
                var size = maximum
                while (size >= MIN_TEXT_SIZE_PX) {
                    val candidate = hintLayout(text, face, inset, upper, size)
                    if (face.contains(candidate.bounds) && !RectF.intersects(candidate.bounds, center)) {
                        return candidate
                    }
                    size -= .5f
                }
                // 正の寸法を持つキーでは候補を消さず、描画可能な右隅へ最小サイズで残します。
                return hintLayout(text, face, 0f, upper, MIN_TEXT_SIZE_PX)
            }
            return buildList {
                upHint?.let { add(layout(it, true)) }
                downHint?.let { add(layout(it, false)) }
            }
        }

        internal fun centerLabelLayout(): GlyphLayout? {
            val face = visibleFaceBounds()
            val label = text.toString()
            if (face.width() <= 0f || face.height() <= 0f || label.isEmpty()) return null
            val inset = hintInset(face)
            val content = RectF(face).apply { inset(dp(1), dp(1)) }
            val hintSize = minOf(resources.displayMetrics.scaledDensity * 10f, dp(10))
            val hints = buildList {
                upHint?.let { add(hintLayout(it, face, inset, true, hintSize).bounds) }
                downHint?.let { add(hintLayout(it, face, inset, false, hintSize).bounds) }
            }
            var size = paint.textSize
            while (size >= MIN_TEXT_SIZE_PX) {
                val glyph = glyphBounds(paint, label, size)
                // 句読点を中央の点にせず、英字と同じベースラインに置きます。
                val reference = glyphBounds(paint, "Hgj", size)
                val baseline = face.centerY() - (reference.top + reference.bottom) / 2f - dp(2)
                val centeredX = face.centerX() - (glyph.left + glyph.right) / 2f
                val minimumX = content.left - glyph.left
                // 補助文字と縦方向に重なる場合だけ、主文字を少し左へ寄せます。
                var anchorX = centeredX
                val bounds = RectF(anchorX + glyph.left, baseline + glyph.top,
                    anchorX + glyph.right, baseline + glyph.bottom)
                hints.filter { it.top < bounds.bottom && it.bottom > bounds.top }.forEach {
                    anchorX = minOf(anchorX, it.left - dp(1) - glyph.right)
                }
                anchorX = maxOf(minimumX, anchorX)
                bounds.offset(anchorX - centeredX, 0f)
                if (content.contains(bounds) && hints.none { RectF.intersects(it, bounds) }) {
                    return GlyphLayout(label, anchorX, baseline, size, bounds)
                }
                size -= .5f
            }
            return null
        }

        private fun hintInset(face: RectF) = minOf(dp(4), face.width() / 5f, face.height() / 5f)

        internal fun visibleFaceBounds(): RectF {
            if (width <= 0 || height <= 0) return RectF()
            val drawablePadding = Rect()
            background?.getPadding(drawablePadding)
            val maximumInset = minOf(dp(4), minOf(width, height) / 4f)
            fun limited(value: Int) = minOf(value.toFloat(), maximumInset)
            return RectF(
                maxOf(paddingLeft.toFloat(), limited(drawablePadding.left)),
                maxOf(paddingTop.toFloat(), limited(drawablePadding.top)),
                width - maxOf(paddingRight.toFloat(), limited(drawablePadding.right)),
                height - maxOf(paddingBottom.toFloat(), limited(drawablePadding.bottom)),
            )
        }

        private fun hintLayout(value: String, face: RectF, inset: Float, upper: Boolean,
            size: Float): GlyphLayout {
            val glyph = glyphBounds(hintPaint, value, size)
            val anchorX = face.right - inset - glyph.right
            val baseline = if (upper) face.top + inset - glyph.top else face.bottom - inset - glyph.bottom
            return GlyphLayout(value, anchorX, baseline, size,
                RectF(anchorX + glyph.left, baseline + glyph.top,
                    anchorX + glyph.right, baseline + glyph.bottom))
        }

        private fun glyphBounds(target: Paint, value: String, size: Float) = Rect().also {
            val originalSize = target.textSize
            target.textSize = size
            target.getTextBounds(value, 0, value.length, it)
            target.textSize = originalSize
        }

        private fun dp(value: Int) = value * resources.displayMetrics.density

        private companion object { const val MIN_TEXT_SIZE_PX = 1f }
    }

    /** 上下と中心を独立した面として示し、選択中の面だけを強調します。 */
    internal class FlickGuide(
        context: Context,
        value: String,
        up: String?,
        down: String?,
        selection: TouchKeyboardController.Flick?,
    ) : LinearLayout(context) {
        private val outputs = listOf(up, value, down)
        private val marks = listOf("↑", "・", "↓")
        private val options = mutableListOf<TextView>()

        init {
            orientation = VERTICAL
            setPadding(dp(4), dp(4), dp(4), dp(4))
            outputs.zip(marks).forEach { (output, mark) ->
                option(mark, output).also { options += it; addView(it, guideParams()) }
            }
            update(selection)
        }

        fun matches(value: String, up: String?, down: String?) = outputs == listOf(up, value, down)

        fun update(selection: TouchKeyboardController.Flick?) {
            val selectedIndex = when (selection) {
                TouchKeyboardController.Flick.UP -> 0
                TouchKeyboardController.Flick.TAP -> 1
                TouchKeyboardController.Flick.DOWN -> 2
                null -> -1
            }
            options.forEachIndexed { index, option -> style(option, outputs[index], index == selectedIndex) }
        }

        private fun option(mark: String, output: String?): TextView = TextView(context).apply {
            text = if (output == null) "" else "$mark  $output"
            textSize = 16f
            gravity = Gravity.CENTER
        }

        private fun style(option: TextView, output: String?, selected: Boolean) = option.apply {
            setTextColor(if (selected && output != null) Color.WHITE else 0xff4d535b.toInt())
            alpha = if (output == null) .25f else 1f
            background = GradientDrawable().apply {
                cornerRadius = dp(6).toFloat()
                setColor(if (selected && output != null) 0xff355f91.toInt() else 0xfff5f6f7.toInt())
            }
        }

        private fun guideParams() = LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f).apply {
            setMargins(0, dp(1), 0, dp(1))
        }
        private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    }

    private companion object { const val GUIDE_DELAY_MS = 200L }
}
