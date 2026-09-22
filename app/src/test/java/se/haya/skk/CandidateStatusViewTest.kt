package se.haya.skk

import android.view.KeyEvent
import android.view.View
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager
import android.widget.TextView
import android.widget.Button
import android.os.Looper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 35])
class CandidateStatusViewTest {
    @Test fun `通常表示は書記素境界で制限し巨大な一文字をレイアウトしない`() {
        val family = "👩‍💻"
        val source = family.repeat(120) + "末尾"
        val preview = CandidateTextBounds.preview(source)
        assertTrue(preview.truncated)
        assertEquals(family.repeat(CandidateTextBounds.NORMAL_CLUSTERS), preview.text)
        assertValidUtf16(preview.text)

        val giant = "a" + "\u0301".repeat(CandidateTextBounds.NORMAL_SCAN_UTF16 * 2)
        val giantPreview = CandidateTextBounds.preview(giant)
        assertTrue(giantPreview.truncated)
        assertEquals(CandidateTextBounds.LONG_GRAPHEME_PLACEHOLDER, giantPreview.text)
        assertTrue(giantPreview.text.length < 64)

        val oneMiB = "候".repeat(1_048_576)
        val oneMiBPreview = CandidateTextBounds.preview(oneMiB)
        assertEquals(CandidateTextBounds.NORMAL_CLUSTERS, oneMiBPreview.text.length)
        assertTrue(oneMiBPreview.truncated)
        assertTrue(CandidateTextBounds.page(oneMiB, 0).text.length <=
            CandidateTextBounds.DETAIL_SCAN_UTF16)

        // Unicode 15.1 で追加された ZWJ 列も、端末 OS の Unicode 版に依存せず一文字として扱います。
        val headShakingHorizontally = "🙂‍↔️"
        val recent = CandidateTextBounds.preview(headShakingHorizontally.repeat(120))
        assertEquals(headShakingHorizontally.repeat(CandidateTextBounds.NORMAL_CLUSTERS), recent.text)
    }

    @Test fun `全文ページは通常書記素を保ち巨大書記素だけ明示してコードポイント境界で分割する`() {
        val ordinary = ("候補👩‍💻注釈e\u0301").repeat(900)
        val ordinaryPages = pages(ordinary)
        assertEquals(ordinary, ordinaryPages.joinToString("") { it.text })
        assertTrue(ordinaryPages.none { it.splitLongGrapheme })
        ordinaryPages.forEach { assertValidUtf16(it.text) }

        val giant = "x" + "\u0301".repeat(CandidateTextBounds.DETAIL_SCAN_UTF16 * 2) + "終"
        val giantPages = pages(giant)
        assertEquals(giant, giantPages.joinToString("") { it.text })
        assertTrue(giantPages.any { it.splitLongGrapheme })
        giantPages.forEach { assertValidUtf16(it.text) }
    }

    @Test fun `カーソル直前の巨大書記素は全文を通常表示へ展開しない`() {
        val giant = "a" + "\u0301".repeat(65_535)
        val preview = CandidateTextBounds.previewBeforeCursor(giant, giant.length, 48)
        assertEquals(CandidateTextBounds.LONG_GRAPHEME_PLACEHOLDER, preview.text)
        assertTrue(preview.truncated)

        val ordinary = "先頭" + "👩🏽‍💻".repeat(70)
        val tail = CandidateTextBounds.previewBeforeCursor(ordinary, ordinary.length, 48)
        assertEquals("👩🏽‍💻".repeat(48), tail.text)
        assertTrue(tail.truncated)
    }

    @Test fun `候補変更で全文を閉じ未修飾左右だけがページ操作になる`() {
        val context = RuntimeEnvironment.getApplication()
        val view = CandidateStatusView(context)
        val first = "候補".repeat(3000)
        val identity = CandidateDetailIdentity(0, first, null)
        view.show(detailPresentation("状態", identity,
            listOf(CandidateDetailSection("候補本文", first))))
        candidateTile(view).performLongClick()
        assertTrue(view.isDetailOpen)
        val initial = view.findViewById<TextView>(R.id.candidate_detail_text).text.toString()

        assertTrue(view.handleDetailPaging(key(KeyEvent.KEYCODE_DPAD_RIGHT)))
        val advanced = view.findViewById<TextView>(R.id.candidate_detail_text).text.toString()
        assertTrue(initial != advanced)
        assertFalse(view.handleDetailPaging(key(KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.META_CTRL_ON)))
        assertEquals(advanced, view.findViewById<TextView>(R.id.candidate_detail_text).text.toString())

        view.show(detailPresentation("次", CandidateDetailIdentity(1, "別候補", null),
            listOf(CandidateDetailSection("候補本文", "別候補"))))
        assertFalse(view.isDetailOpen)
    }

    @Test fun `同じ候補識別子でも全文参照が変われば古いページ位置を破棄する`() {
        val view = CandidateStatusView(RuntimeEnvironment.getApplication())
        val selected = "候補"
        val identity = CandidateDetailIdentity(0, selected, null)
        val long = "本文".repeat(4000)
        view.show(detailPresentation("状態", identity,
            listOf(CandidateDetailSection("候補本文", long))))
        candidateTile(view).performLongClick()
        repeat(3) { assertTrue(view.handleDetailPaging(key(KeyEvent.KEYCODE_DPAD_RIGHT))) }
        assertTrue(view.isDetailOpen)

        val short = "短い"
        view.show(detailPresentation("状態", identity,
            listOf(CandidateDetailSection("候補本文", short))))
        assertFalse(view.isDetailOpen)
        candidateTile(view).performLongClick()
        assertTrue(view.findViewById<TextView>(R.id.candidate_detail_text).text.toString().contains(short))
        assertTrue(view.handleDetailPaging(key(KeyEvent.KEYCODE_DPAD_RIGHT)))
    }

    @Test fun `候補本文が長くても一覧展開ボタンを出さない`() {
        val view = CandidateStatusView(RuntimeEnvironment.getApplication())
        view.show(detailPresentation("", CandidateDetailIdentity(0, "長い本文", null),
            listOf(CandidateDetailSection("注釈", "長い注釈".repeat(100)))))
        layout(view, 480)
        assertEquals(View.GONE, view.findViewById<View>(R.id.candidate_full_detail).visibility)
        assertFalse(view.isDetailOpen)
        candidateTile(view).performLongClick()
        assertTrue(view.isDetailOpen)
    }

    @Test fun `インライン候補では全文ボタンを表示しない`() {
        val view = CandidateStatusView(RuntimeEnvironment.getApplication())
        val candidate = "実測幅で省略される候補本文".repeat(40)
        view.show(CandidateStatusPresentation(
            text = "状態",
            detailIdentity = CandidateDetailIdentity(0, candidate, null),
            detailSections = listOf(CandidateDetailSection("候補本文", candidate)),
            selectedPreviewTruncated = true,
        ))
        assertEquals(View.GONE, view.findViewById<View>(R.id.candidate_full_detail).visibility)
    }

    @Test fun `狭い実測幅と大きい文字でも候補領域全体を画面高の四割以内にする`() {
        val context = RuntimeEnvironment.getApplication()
        val previousFontScale = context.resources.configuration.fontScale
        context.resources.configuration.fontScale = 2f
        val view = CandidateStatusView(context)
        view.show(detailPresentation("長い状態\n".repeat(200),
            CandidateDetailIdentity(0, "候補".repeat(2000), "注釈"), listOf(
                CandidateDetailSection("候補本文", "候補".repeat(2000)),
                CandidateDetailSection("注釈", "注釈"),
            )))
        candidateTile(view).performLongClick()
        val density = context.resources.displayMetrics.density
        val width = (240 * density).toInt()
        val height = (640 * density).toInt()
        view.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.AT_MOST),
        )
        assertTrue(view.availableContentWidthDp() in 1f..208f)
        assertTrue(view.measuredHeight <= (context.resources.configuration.screenHeightDp * density * .4f).toInt())
        val innerWidth = width - (32 * density).toInt()
        for (id in listOf(R.id.candidate_detail_previous, R.id.candidate_detail_next,
            R.id.candidate_detail_close)) {
            val button = view.findViewById<Button>(id)
            assertTrue("操作ボタンが狭幅の内側を超えています: ${button.measuredWidth} > $innerWidth",
                button.measuredWidth <= innerWidth)
        }
        view.layout(0, 0, view.measuredWidth, view.measuredHeight)
        view.scrollTo(0, 100)
        view.show(detailPresentation("次の候補", CandidateDetailIdentity(1, "別", null),
            listOf(CandidateDetailSection("候補本文", "別"))))
        assertEquals(0, view.scrollY)
        context.resources.configuration.fontScale = previousFontScale
    }

    @Test fun `全文表示は通常表示より拡張しても画面高の四割以内に収まる`() {
        val context = RuntimeEnvironment.getApplication()
        val view = CandidateStatusView(context)
        val width = View.MeasureSpec.makeMeasureSpec(480, View.MeasureSpec.EXACTLY)
        val height = View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.AT_MOST)
        view.show(CandidateStatusPresentation("ひらがな",
            menuItems = listOf(CandidateMenuItem('a', "候補"))))
        view.measure(width, height)
        val normalHeight = view.measuredHeight

        view.show(detailPresentation("候補\n".repeat(100),
            CandidateDetailIdentity(0, "候補", null),
            listOf(CandidateDetailSection("候補本文", "本文".repeat(1000)))))
        view.measure(width, height)
        assertTrue(view.measuredHeight >= normalHeight)
        assertEquals(View.GONE, view.findViewById<View>(R.id.candidate_full_detail).visibility)

        candidateTile(view).performLongClick()
        view.measure(width, height)
        assertTrue(view.measuredHeight > normalHeight)
        assertTrue(view.measuredHeight <= (context.resources.configuration.screenHeightDp *
            context.resources.displayMetrics.density * .4f).toInt())
    }

    @Test @Config(qualifiers = "w640dp-h800dp")
    fun `画面候補は一行で物理候補は縦に並ぶ`() {
        val view = CandidateStatusView(RuntimeEnvironment.getApplication())
        val items = "asdf".mapIndexed { index, label -> CandidateMenuItem(label, "候補$index") }
        view.show(CandidateStatusPresentation("", menuItems = items))
        layout(view, 640)
        fun tiles() = descendants(view).filter { it.contentDescription?.toString()?.contains("候補") == true }
        assertEquals(1, tiles().map { it.top }.distinct().size)
        view.physicalPresentation = true
        view.show(CandidateStatusPresentation("", menuItems = items))
        layout(view, 640)
        val locations = tiles().map { tile -> android.graphics.Rect(0, 0, tile.width, tile.height).also {
            view.offsetDescendantRectToMyCoords(tile, it)
        }.top }
        assertEquals(4, locations.distinct().size)
    }

    @Test @Config(qualifiers = "w640dp-h320dp")
    fun `ページ件数は実際の候補幅から計算する`() {
        val view = CandidateStatusView(RuntimeEnvironment.getApplication())
        layout(view, 640)
        val short = view.pageCapacity(List(7) { "候補" }, 0)
        val long = view.pageCapacity(List(7) { "非常に長い候補".repeat(400) }, 0)
        assertTrue(short > long)
        assertEquals(1, long)
    }

    @Test
    @Config(qualifiers = "w640dp-h320dp")
    fun `注釈は候補より小さく淡く表示しラベルとともに読み上げる`() {
        val view = CandidateStatusView(RuntimeEnvironment.getApplication())
        view.show(CandidateStatusPresentation("状態", menuItems = listOf(
            CandidateMenuItem('a', "候補", "説明"))))
        view.measure(View.MeasureSpec.makeMeasureSpec(640, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(320, View.MeasureSpec.AT_MOST))
        view.layout(0, 0, view.measuredWidth, view.measuredHeight)
        val tile = descendants(view).first { it.contentDescription?.toString() == "候補、注釈: 説明" } as android.view.ViewGroup
        val candidate = tile.getChildAt(0) as TextView
        val annotation = tile.getChildAt(1) as TextView
        assertEquals("候補", candidate.text.toString())
        assertEquals("説明", annotation.text.toString())
        assertTrue(annotation.textSize < candidate.textSize)
        assertTrue(annotation.currentTextColor != candidate.currentTextColor)
        assertTrue(tile.contentDescription.contains("候補、注釈: 説明"))
        assertTrue(tile.right <= view.width)
    }

    @Test
    @Config(qualifiers = "w360dp-h800dp")
    fun `物理候補は本文位置を揃え注釈を右側一行に置き行を区切る`() {
        val view = CandidateStatusView(RuntimeEnvironment.getApplication()).apply {
            physicalPresentation = true
        }
        view.show(CandidateStatusPresentation("", menuItems = listOf(
            CandidateMenuItem('a', "注釈なしの候補本文"),
            CandidateMenuItem('s', "折り返しても省略しない長い候補本文".repeat(4), "非常に長い注釈".repeat(20)),
        )))
        layout(view, 360)
        val tiles = descendants(view).filterIsInstance<android.view.ViewGroup>()
            .filter { it.contentDescription?.toString()?.contains("候補本文") == true }
        assertEquals(2, tiles.size)
        val firstBody = tiles[0].getChildAt(1) as TextView
        val secondBody = tiles[1].getChildAt(1) as TextView
        val annotation = tiles[1].getChildAt(2) as TextView
        assertEquals(firstBody.left, secondBody.left)
        assertTrue(annotation.left >= secondBody.right)
        assertEquals(1, annotation.maxLines)
        assertEquals(android.text.TextUtils.TruncateAt.END, annotation.ellipsize)
        assertEquals(Int.MAX_VALUE, secondBody.maxLines)
        assertEquals(null, secondBody.ellipsize)
        val menu = tiles.first().parent.parent as android.widget.LinearLayout
        assertTrue(menu.dividerDrawable != null)
        assertEquals(android.widget.LinearLayout.SHOW_DIVIDER_MIDDLE, menu.showDividers)
    }

    @Test
    @Config(qualifiers = "w360dp-h800dp")
    fun `物理ページ件数はラベル上限と折り返し後の実高さから決める`() {
        val view = CandidateStatusView(RuntimeEnvironment.getApplication())
        val short = List(9) { "候補$it" to null }
        val long = List(9) { "長い候補本文".repeat(400) to "注釈" }
        val shortCapacity = view.physicalPageCapacity(short, 0, labelLimit = 7)
        assertTrue(shortCapacity in 3..7)
        assertTrue(view.physicalPageCapacity(long, 0, labelLimit = 7) in 1 until shortCapacity)
        assertEquals(2, view.physicalPageCapacity(short, 7, labelLimit = 7))
    }

    @Test
    @Config(qualifiers = "w360dp-h800dp")
    fun `物理ページは実際に収まる最大行まで使い登録見出し分を先に空ける`() {
        val context = RuntimeEnvironment.getApplication()
        val candidates = List(16) { "候補$it" to "注釈$it" }
        val probe = CandidateStatusView(context)
        val capacity = probe.physicalPageCapacity(candidates, 0, labelLimit = 16)
        fun laidOut(count: Int, presentation: CandidateStatusPresentation? = null): CandidateStatusView =
            CandidateStatusView(context).apply {
                physicalPresentation = true
                show(presentation ?: CandidateStatusPresentation("", menuItems = (0 until count).map {
                    CandidateMenuItem(('a'.code + it).toChar(), candidates[it].first, candidates[it].second)
                }))
                measure(View.MeasureSpec.makeMeasureSpec(360, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.AT_MOST))
                layout(0, 0, measuredWidth, measuredHeight)
            }
        assertFalse("算出した物理ページが表示高を超えています", laidOut(capacity).canScrollVertically(1))
        assertTrue("表示高に収まらない次の行までページへ含めています",
            laidOut(capacity + 1).canScrollVertically(1))

        val registration = CandidateStatusPresentation(
            "",
            expandedStatus = true,
            registration = RegistrationPresentation(
                titleReading = "てすと",
                parentReading = null,
                text = "本文",
                cursor = 2,
                composingStart = null,
                composingEnd = null,
                completionStart = null,
                completionEnd = null,
                canEdit = true,
                canRegister = true,
                saving = false,
            ),
        )
        val header = probe.physicalHeaderHeight(registration, 360)
        val registrationCapacity = probe.physicalPageCapacity(candidates, 0, 16,
            reservedHeaderHeightPx = header)
        val withRows = registration.copy(menuItems = (0 until registrationCapacity).map {
            CandidateMenuItem(('a'.code + it).toChar(), candidates[it].first, candidates[it].second)
        })
        assertFalse("登録見出しを含む物理ページが表示高を超えています",
            laidOut(registrationCapacity, withRows).canScrollVertically(1))
    }

    @Test fun `無効なページ三角は文字自体をグレー表示する`() {
        val view = CandidateStatusView(RuntimeEnvironment.getApplication())
        val disabled = CandidateStatusPresentation("", menuItems = listOf(CandidateMenuItem('a', "候補")),
            showPageControls = true, canPreviousPage = false, canNextPage = false)
        view.show(disabled)
        val previous = descendants(view).filterIsInstance<Button>()
            .single { it.contentDescription == "前の候補ページ" }
        previous.refreshDrawableState()
        val disabledColor = previous.currentTextColor
        view.show(disabled.copy(canPreviousPage = true))
        previous.refreshDrawableState()
        assertTrue(disabledColor != previous.currentTextColor)
        assertEquals(0xff9aa0a6.toInt(), disabledColor)
    }

    @Test fun `候補ページ更新は一覧全体の読み上げキャッシュを更新する`() {
        val activity = Robolectric.buildActivity(android.app.Activity::class.java).setup().get()
        shadowOf(activity.getSystemService(AccessibilityManager::class.java)).setEnabled(true)
        val view = CandidateStatusView(activity)
        activity.setContentView(view)
        val first = listOf(CandidateMenuItem('a', "候補3", "注釈3"))
        view.show(CandidateStatusPresentation("状態", menuItems = first))
        shadowOf(Looper.getMainLooper()).idle()
        val changes = mutableListOf<Int>()
        view.accessibilityDelegate = object : View.AccessibilityDelegate() {
            override fun sendAccessibilityEventUnchecked(host: View, event: AccessibilityEvent) {
                if (event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
                    changes += event.contentChangeTypes
                }
                super.sendAccessibilityEventUnchecked(host, event)
            }
        }
        view.show(CandidateStatusPresentation("次", menuItems = first))
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue("候補が同じなのに一覧を再通知しました", changes.isEmpty())
        view.show(CandidateStatusPresentation("次", menuItems = listOf(
            CandidateMenuItem('a', "候補5", "注釈5"))))
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue("変更した候補の読み上げキャッシュが更新されません",
            changes.any { it and AccessibilityEvent.CONTENT_CHANGE_TYPE_SUBTREE != 0 })
        assertTrue(descendants(view).any { it.contentDescription?.toString() == "候補5、注釈: 注釈5" })
    }

    @Test
    @Config(qualifiers = "w480dp-h800dp")
    fun `登録状態は通常帯より広げて本文と操作案内を表示する`() {
        val view = CandidateStatusView(RuntimeEnvironment.getApplication())
        val width = View.MeasureSpec.makeMeasureSpec(480, View.MeasureSpec.EXACTLY)
        val height = View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.AT_MOST)
        view.show(CandidateStatusPresentation("状態"))
        view.measure(width, height)
        val ordinary = view.measuredHeight
        view.show(CandidateStatusPresentation("", expandedStatus = true,
            registration = RegistrationPresentation("よみ", null, "本文▽変換中", 6,
                2, 6, null, null, canEdit = false, canRegister = false, saving = false)))
        view.measure(width, height)
        assertTrue(view.measuredHeight > ordinary)
        assertTrue(view.measuredHeight <= 320)
    }

    private fun pages(value: String): List<DetailPage> {
        val result = mutableListOf<DetailPage>()
        var offset = 0
        while (offset < value.length) {
            val page = CandidateTextBounds.page(value, offset)
            assertTrue(page.nextOffset > offset)
            result += page
            offset = page.nextOffset
        }
        return result
    }

    private fun detailPresentation(
        text: String,
        identity: CandidateDetailIdentity,
        sections: List<CandidateDetailSection>,
    ) = CandidateStatusPresentation(
        text = text,
        detailIdentity = identity,
        detailSections = sections,
        menuItems = listOf(CandidateMenuItem('a', "候補", sections.joinToString("\n") { it.value },
            CandidateTapTarget(1, 1, 0, identity, 0))),
        selectedMenuIndex = 0,
        selectedPreviewTruncated = true,
    )

    private fun descendants(view: View): List<View> = listOf(view) + if (view is android.view.ViewGroup)
        (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()

    private fun candidateTile(view: View) = descendants(view).first { it.isLongClickable }

    private fun key(code: Int, meta: Int = 0) = KeyEvent(0, 0, KeyEvent.ACTION_DOWN, code, 0, meta)

    private fun layout(view: CandidateStatusView, width: Int) {
        view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.AT_MOST))
        view.layout(0, 0, view.measuredWidth, view.measuredHeight)
    }

    private fun assertValidUtf16(value: String) {
        var index = 0
        while (index < value.length) {
            val char = value[index]
            if (Character.isHighSurrogate(char)) {
                assertTrue(index + 1 < value.length && Character.isLowSurrogate(value[index + 1]))
                index += 2
            } else {
                assertFalse(Character.isLowSurrogate(char))
                index++
            }
        }
    }
}
