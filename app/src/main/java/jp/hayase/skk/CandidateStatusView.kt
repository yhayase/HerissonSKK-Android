package jp.hayase.skk

import android.content.Context
import android.graphics.Typeface
import android.graphics.text.LineBreaker
import android.text.Layout
import android.text.SpannableString
import android.text.TextUtils
import android.text.Spanned
import android.text.style.StyleSpan
import android.util.AttributeSet
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.ibm.icu.text.BreakIterator
import com.ibm.icu.util.ULocale
import kotlin.math.max

internal data class CandidateDetailSection(val heading: String, val value: String)
internal data class CandidateMenuItem(val label: Char, val text: String, val annotation: String? = null)

internal data class CandidateStatusPresentation(
    val text: CharSequence,
    val detailIdentity: CandidateDetailIdentity? = null,
    val detailSections: List<CandidateDetailSection> = emptyList(),
    val menuItems: List<CandidateMenuItem> = emptyList(),
    val expandedStatus: Boolean = false,
    /** 一覧で選択中の候補。null はインライン候補など一覧外の状態です。 */
    val selectedMenuIndex: Int? = null,
    /** 書記素数で切り詰めた選択中候補または注釈があることを示します。 */
    val selectedPreviewTruncated: Boolean = false,
)

/** 巨大な文字列を比較せず、選択中の候補が変わったことを検出するための参照識別子です。 */
internal class CandidateDetailIdentity(
    val index: Int,
    val candidate: String,
    val annotation: String?,
) {
    fun sameAs(other: CandidateDetailIdentity?): Boolean = other != null && index == other.index &&
        candidate === other.candidate && annotation === other.annotation
}

internal data class BoundedText(val text: String, val truncated: Boolean)

internal data class DetailPage(
    val text: String,
    val nextOffset: Int,
    val splitLongGrapheme: Boolean,
)

/** 候補表示で巨大な辞書本文を複製・レイアウトしないための境界処理です。 */
internal object CandidateTextBounds {
    const val NORMAL_CLUSTERS = 96
    const val NORMAL_SCAN_UTF16 = 4096
    const val DETAIL_TARGET_UTF16 = 2048
    const val DETAIL_SCAN_UTF16 = 8192
    const val LONG_GRAPHEME_PLACEHOLDER = "［非常に長い一文字です。全文表示で確認できます］"

    fun preview(value: String, clusters: Int = NORMAL_CLUSTERS): BoundedText {
        if (value.isEmpty()) return BoundedText("", false)
        val scanEnd = codePointBoundaryAtMost(value, 0, NORMAL_SCAN_UTF16)
        if (scanEnd == value.length) {
            val iterator = BreakIterator.getCharacterInstance(ULocale.ROOT).apply { setText(value) }
            var end = iterator.first()
            repeat(clusters) {
                val next = iterator.next()
                if (next == BreakIterator.DONE) return BoundedText(value, false)
                end = next
            }
            return BoundedText(value.substring(0, end), end < value.length)
        }

        val sample = value.substring(0, scanEnd)
        val iterator = BreakIterator.getCharacterInstance(ULocale.ROOT).apply { setText(sample) }
        var end = iterator.first()
        var count = 0
        while (count < clusters) {
            val next = iterator.next()
            // sample の末尾は人工的な境界なので、元文字列が続く場合は採用しません。
            if (next == BreakIterator.DONE || next == scanEnd) break
            end = next
            count++
        }
        if (end == 0) return BoundedText(LONG_GRAPHEME_PLACEHOLDER, true)
        return BoundedText(value.substring(0, end), true)
    }

    /** カーソル直前だけを有界走査します。窓の先頭をまたぐ一書記素は表示しません。 */
    fun previewBeforeCursor(value: String, cursor: Int, clusters: Int): BoundedText {
        require(cursor in 0..value.length)
        if (cursor == 0) return BoundedText("", false)
        var scanStart = (cursor - NORMAL_SCAN_UTF16).coerceAtLeast(0)
        if (scanStart > 0 && Character.isLowSurrogate(value[scanStart]) &&
            Character.isHighSurrogate(value[scanStart - 1])) scanStart++
        val sample = value.substring(scanStart, cursor)
        val iterator = BreakIterator.getCharacterInstance(ULocale.ROOT).apply { setText(sample) }
        val boundaries = ArrayDeque<Int>()
        boundaries.addLast(0)
        if (scanStart > 0) {
            // 最初の境界までは、窓の前から続く書記素の一部かもしれません。
            val first = iterator.next()
            if (first == BreakIterator.DONE || first == sample.length) {
                return BoundedText(LONG_GRAPHEME_PLACEHOLDER, true)
            }
            boundaries.clear()
            boundaries.addLast(first)
        }
        while (true) {
            val next = iterator.next()
            if (next == BreakIterator.DONE) break
            boundaries.addLast(next)
            while (boundaries.size > clusters + 1) boundaries.removeFirst()
        }
        if (boundaries.size < 2 || boundaries.last() != sample.length) {
            return BoundedText(LONG_GRAPHEME_PLACEHOLDER, true)
        }
        val start = boundaries.first()
        return BoundedText(sample.substring(start), scanStart + start > 0)
    }

    fun page(value: String, start: Int): DetailPage {
        require(start in 0..value.length)
        if (start == value.length) return DetailPage("", start, false)
        val target = codePointBoundaryAtMost(value, start, DETAIL_TARGET_UTF16)
        val scanEnd = codePointBoundaryAtMost(value, start, DETAIL_SCAN_UTF16)
        val sample = value.substring(start, scanEnd)
        val iterator = BreakIterator.getCharacterInstance(ULocale.ROOT).apply { setText(sample) }
        var boundary = iterator.first()
        var preferred = 0
        var firstReal = 0
        while (true) {
            val next = iterator.next()
            if (next == BreakIterator.DONE) break
            val absolute = start + next
            val artificialEnd = absolute == scanEnd && scanEnd < value.length
            if (!artificialEnd) {
                if (firstReal == 0) firstReal = absolute
                if (absolute <= target) preferred = absolute
            }
            boundary = next
        }
        val end = when {
            preferred > start -> preferred
            firstReal > start -> firstReal
            else -> max(start + 1, target).coerceAtMost(value.length)
                .let { codePointBoundaryAtMost(value, start, it - start) }
                .coerceAtLeast(nextCodePointBoundary(value, start))
        }
        val split = preferred <= start && firstReal <= start
        return DetailPage(value.substring(start, end), end, split)
    }

    private fun codePointBoundaryAtMost(value: String, start: Int, count: Int): Int {
        var end = (start + count).coerceAtMost(value.length)
        if (end in 1 until value.length && Character.isHighSurrogate(value[end - 1]) &&
            Character.isLowSurrogate(value[end])) end--
        return end
    }

    private fun nextCodePointBoundary(value: String, start: Int): Int =
        start + Character.charCount(value.codePointAt(start))
}

/** 物理キーボード用の状態表示です。一覧・登録・全文は画面高の40%まで広げます。 */
internal class CandidateStatusView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : ScrollView(context, attrs) {
    val statusTextView = TextView(context).apply {
        id = R.id.input_status
        textSize = 18f
        setTextColor(0xff202124.toInt())
        isFocusable = false
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        breakStrategy = LineBreaker.BREAK_STRATEGY_SIMPLE
        hyphenationFrequency = Layout.HYPHENATION_FREQUENCY_NONE
    }
    private val detailButton = Button(context).apply {
        id = R.id.candidate_full_detail
        text = "候補の全文"
        isFocusable = false
        visibility = View.GONE
    }
    private val detailText = TextView(context).apply {
        id = R.id.candidate_detail_text
        textSize = 17f
        setTextColor(0xff202124.toInt())
        setTextIsSelectable(false)
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        breakStrategy = LineBreaker.BREAK_STRATEGY_SIMPLE
        hyphenationFrequency = Layout.HYPHENATION_FREQUENCY_NONE
    }
    private val previous = Button(context).apply {
        id = R.id.candidate_detail_previous
        text = "前へ"
        isFocusable = false
    }
    private val next = Button(context).apply {
        id = R.id.candidate_detail_next
        text = "次へ"
        isFocusable = false
    }
    private val close = Button(context).apply {
        id = R.id.candidate_detail_close
        text = "閉じる"
        isFocusable = false
    }
    private val detailControls = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_HORIZONTAL
        val buttonParams = LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        addView(previous, buttonParams)
        addView(next, LinearLayout.LayoutParams(buttonParams))
        addView(close, LinearLayout.LayoutParams(buttonParams))
    }
    private val detailContainer = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        visibility = View.GONE
        addView(detailControls, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        addView(detailText, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }
    private val header = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.TOP
        addView(statusTextView, LinearLayout.LayoutParams(0,
            ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        addView(detailButton, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }
    private val menuContainer = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        visibility = View.GONE
    }
    private val content = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        addView(menuContainer, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        addView(header, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        addView(detailContainer, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    private var expandedStatus = false
    private var renderedMenuItems: List<CandidateMenuItem> = emptyList()
    private var renderedMenuColumns = 1
    private var renderedCandidateTexts: List<TextView> = emptyList()
    private var renderedAnnotationTexts: List<TextView?> = emptyList()
    private var identity: CandidateDetailIdentity? = null
    private var sections: List<CandidateDetailSection> = emptyList()
    private var selectedCandidateText: TextView? = null
    private var selectedAnnotationText: TextView? = null
    private var selectedPreviewTruncated = false
    private val history = mutableListOf<Pair<Int, Int>>()
    private var historyIndex = 0

    init {
        id = R.id.candidate_status_container
        isFillViewport = false
        isFocusable = false
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        setBackgroundColor(0xfff1f3f4.toInt())
        val horizontal = dp(16)
        val vertical = dp(8)
        content.setPadding(horizontal, vertical, horizontal, vertical)
        addView(content, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        detailButton.setOnClickListener { openDetail() }
        previous.setOnClickListener { previousPage() }
        next.setOnClickListener { nextPage() }
        close.setOnClickListener { closeDetail() }
    }

    fun show(presentation: CandidateStatusPresentation) {
        expandedStatus = presentation.expandedStatus
        statusTextView.text = presentation.text
        val columns = menuColumns().coerceAtMost(presentation.menuItems.size.coerceAtLeast(1))
        if (renderedMenuItems != presentation.menuItems || renderedMenuColumns != columns) {
            renderedMenuItems = presentation.menuItems.toList()
            renderedMenuColumns = columns
            menuContainer.removeAllViews()
            val candidateTexts = mutableListOf<TextView>()
            val annotationTexts = mutableListOf<TextView?>()
            presentation.menuItems.chunked(columns).forEach { items ->
                val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
                items.forEach { item ->
                    val tile = LinearLayout(context).apply {
                        orientation = LinearLayout.VERTICAL
                        setPadding(dp(4), dp(2), dp(4), dp(2))
                        isFocusable = false
                        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
                        contentDescription = buildString {
                            append(item.label).append(": ").append(item.text)
                            item.annotation?.let { append("、注釈: ").append(it) }
                        }
                    }
                    val candidateText = TextView(context).apply {
                        text = "${item.label}: ${item.text}"
                        textSize = 18f
                        setTextColor(0xff202124.toInt())
                        maxLines = 1
                        ellipsize = TextUtils.TruncateAt.END
                        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                    }
                    tile.addView(candidateText, LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                    val annotationText = item.annotation?.let { annotation ->
                        TextView(context).apply {
                            text = annotation
                            textSize = 13f
                            setTextColor(0xff5f6368.toInt())
                            maxLines = 1
                            ellipsize = TextUtils.TruncateAt.END
                            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                        }.also { text -> tile.addView(text, LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                        }
                    }
                    candidateTexts += candidateText
                    annotationTexts += annotationText
                    row.addView(tile, LinearLayout.LayoutParams(0,
                        ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                }
                menuContainer.addView(row, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            }
            renderedCandidateTexts = candidateTexts
            renderedAnnotationTexts = annotationTexts
            menuContainer.visibility = if (presentation.menuItems.isEmpty()) View.GONE else View.VISIBLE
            // API 26 では子 View の入替えだけでは読み上げ用の古い候補が残るため、一覧全体の変更を通知します。
            post {
                if (!context.getSystemService(AccessibilityManager::class.java).isEnabled) return@post
                val event = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED).apply {
                    contentChangeTypes = AccessibilityEvent.CONTENT_CHANGE_TYPE_SUBTREE
                    setSource(this@CandidateStatusView)
                    packageName = context.packageName
                    className = CandidateStatusView::class.java.name
                }
                sendAccessibilityEventUnchecked(event)
            }
        }
        val newSections = presentation.detailSections.filter { it.value.isNotEmpty() }
        val sameIdentity = presentation.detailIdentity?.sameAs(identity) ?: (identity == null)
        val changed = !sameIdentity || !sameSectionReferences(sections, newSections)
        if (changed) closeDetail()
        identity = presentation.detailIdentity
        sections = newSections
        selectedPreviewTruncated = presentation.selectedPreviewTruncated
        selectedCandidateText = presentation.selectedMenuIndex?.let { renderedCandidateTexts.getOrNull(it) }
        selectedAnnotationText = presentation.selectedMenuIndex?.let { renderedAnnotationTexts.getOrNull(it) }
        refreshDetailButton()
        if (sections.isEmpty()) closeDetail()
        if (changed) scrollTo(0, 0)
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        // TextView の省略数は実際に幅を割り当てた後だけ正確に判定できます。
        refreshDetailButton()
    }

    fun handleDetailPaging(event: KeyEvent): Boolean {
        if (!isDetailOpen || event.isShiftPressed || event.isCtrlPressed || event.isAltPressed ||
            event.isMetaPressed) return false
        return when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> { previousPage(); true }
            KeyEvent.KEYCODE_DPAD_RIGHT -> { nextPage(); true }
            else -> false
        }
    }

    val isDetailOpen: Boolean get() = detailContainer.visibility == View.VISIBLE

    fun closeDetail() {
        detailContainer.visibility = View.GONE
        history.clear()
        historyIndex = 0
    }

    /** 選択開始時に幅と高さから、一覧をスクロールせず表示できる最大件数を決めます。 */
    fun visibleMenuCapacity(): Int {
        val scale = resources.configuration.fontScale.coerceAtLeast(1f)
        val heightDp = resources.configuration.screenHeightDp.takeIf { it > 0 }
            ?: (resources.displayMetrics.heightPixels / resources.displayMetrics.density).toInt()
        val menuHeightDp = heightDp * .4f - 16f - 48f
        val rows = (menuHeightDp / (48f * scale)).toInt().coerceIn(1, 2)
        return menuColumns() * rows
    }

    private fun menuColumns(): Int {
        val scale = resources.configuration.fontScale.coerceAtLeast(1f)
        return (availableContentWidthDp() / (140f * scale)).toInt().coerceAtLeast(1)
    }

    fun availableContentWidthDp(): Float {
        val pixels = measuredWidth.takeIf { it > 0 }?.minus(content.paddingLeft + content.paddingRight)
            ?: ((resources.configuration.screenWidthDp * resources.displayMetrics.density).toInt() -
                content.paddingLeft - content.paddingRight)
        return pixels.coerceAtLeast(1) / resources.displayMetrics.density
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val windowHeight = (resources.configuration.screenHeightDp * resources.displayMetrics.density)
            .toInt().takeIf { it > 0 } ?: resources.displayMetrics.heightPixels
        val cap = if (isDetailOpen || expandedStatus || menuContainer.visibility == View.VISIBLE) (windowHeight * 0.4f).toInt().coerceAtLeast(dp(48))
            else minOf(dp(96), (windowHeight * 0.25f).toInt().coerceAtLeast(dp(48)))
        val available = MeasureSpec.getSize(heightMeasureSpec).takeIf {
            MeasureSpec.getMode(heightMeasureSpec) != MeasureSpec.UNSPECIFIED && it > 0
        } ?: cap
        val boundedHeight = MeasureSpec.makeMeasureSpec(minOf(available, cap),
            if (isDetailOpen || expandedStatus || menuContainer.visibility == View.VISIBLE) MeasureSpec.AT_MOST else MeasureSpec.EXACTLY)
        super.onMeasure(widthMeasureSpec, boundedHeight)
    }

    private fun openDetail() {
        if (sections.isEmpty() || detailButton.visibility != View.VISIBLE) return
        history.clear()
        history += 0 to 0
        historyIndex = 0
        detailContainer.visibility = View.VISIBLE
        renderPage()
    }

    private fun nextPage() {
        if (!isDetailOpen) return
        if (historyIndex + 1 < history.size) {
            historyIndex++
            renderPage()
            return
        }
        val (sectionIndex, offset) = history[historyIndex]
        val section = sections[sectionIndex]
        val page = CandidateTextBounds.page(section.value, offset)
        val nextLocation = when {
            page.nextOffset < section.value.length -> sectionIndex to page.nextOffset
            sectionIndex + 1 < sections.size -> sectionIndex + 1 to 0
            else -> null
        } ?: return
        history += nextLocation
        historyIndex++
        renderPage()
    }

    private fun previousPage() {
        if (!isDetailOpen || historyIndex == 0) return
        historyIndex--
        renderPage()
    }

    private fun renderPage() {
        val (sectionIndex, offset) = history[historyIndex]
        val section = sections[sectionIndex]
        val page = CandidateTextBounds.page(section.value, offset)
        val suffix = if (page.splitLongGrapheme) resources.getString(R.string.candidate_detail_split_notice) else ""
        detailText.text = resources.getString(R.string.candidate_detail_page, section.heading,
            offset + 1, page.nextOffset, section.value.length, page.text, suffix)
        previous.isEnabled = historyIndex > 0
        next.isEnabled = page.nextOffset < section.value.length || sectionIndex + 1 < sections.size ||
            historyIndex + 1 < history.size
        post { smoothScrollTo(0, detailContainer.top) }
    }

    private fun sameSectionReferences(
        old: List<CandidateDetailSection>,
        new: List<CandidateDetailSection>,
    ): Boolean = old.size == new.size && old.indices.all { index ->
        old[index].heading == new[index].heading && old[index].value === new[index].value
    }

    private fun refreshDetailButton() {
        val layoutTruncated = listOfNotNull(selectedCandidateText, selectedAnnotationText)
            .any { text ->
                val layout = text.layout ?: return@any false
                (0 until layout.lineCount).any { line -> layout.getEllipsisCount(line) > 0 }
            }
        val visible = sections.isNotEmpty() && selectedCandidateText != null &&
            (selectedPreviewTruncated || layoutTruncated)
        val targetVisibility = if (visible) View.VISIBLE else View.GONE
        if (detailButton.visibility == targetVisibility) return
        detailButton.visibility = targetVisibility
        if (!visible) closeDetail()
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}

internal fun styledCompletionText(text: String, suffixStart: Int, suffixLength: Int): CharSequence =
    SpannableString(text).apply {
        if (suffixStart >= 0 && suffixLength > 0 && suffixStart + suffixLength <= length) {
            setSpan(StyleSpan(Typeface.BOLD_ITALIC), suffixStart, suffixStart + suffixLength,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }
