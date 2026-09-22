package se.haya.skk

import android.content.Context
import android.graphics.text.LineBreaker
import android.text.Layout
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.ListView
import android.widget.TextView

/** 極端な長文だけは有限サイズの行を再利用し、全文を一度にレイアウトしません。 */
internal class LongCandidateBodyView(
    context: Context,
    private val source: String,
    onTap: () -> Unit,
    onDetail: () -> Unit,
) : ListView(context) {
    init {
        isFocusable = false
        divider = null
        setPadding(0, 0, 0, 0)
        adapter = object : BaseAdapter() {
            override fun getCount() = (source.length + CHUNK_SIZE - 1) / CHUNK_SIZE
            override fun getItem(position: Int) = chunk(source, position)
            override fun getItemId(position: Int) = position.toLong()
            override fun getView(position: Int, recycled: View?, parent: ViewGroup): View =
                ((recycled as? TextView) ?: TextView(context).apply {
                    textSize = 18f
                    setTextColor(0xff202124.toInt())
                    isFocusable = false
                    maxLines = Int.MAX_VALUE
                    ellipsize = null
                    breakStrategy = LineBreaker.BREAK_STRATEGY_SIMPLE
                    hyphenationFrequency = Layout.HYPHENATION_FREQUENCY_NONE
                }).apply { text = getItem(position) }
        }
        setOnItemClickListener { _, _, _, _ -> onTap() }
        setOnItemLongClickListener { _, _, _, _ -> onDetail(); true }
    }

    companion object {
        internal const val CHUNK_SIZE = 2048
        internal fun chunk(source: String, index: Int): String {
            var start = (index * CHUNK_SIZE).coerceAtMost(source.length)
            var end = ((index + 1) * CHUNK_SIZE).coerceAtMost(source.length)
            if (start > 0 && start < source.length && Character.isLowSurrogate(source[start]) &&
                Character.isHighSurrogate(source[start - 1])) start++
            if (end > 0 && end < source.length && Character.isHighSurrogate(source[end - 1]) &&
                Character.isLowSurrogate(source[end])) end++
            return source.substring(start, end)
        }
    }
}
