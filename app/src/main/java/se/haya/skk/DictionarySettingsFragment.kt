package se.haya.skk

import android.content.Context
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageButton
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceGroup
import androidx.preference.PreferenceScreen
import androidx.preference.PreferenceViewHolder
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.mikepenz.iconics.typeface.library.googlematerial.GoogleMaterial
import se.haya.skk.core.dictionary.SkkDictionaryEncoding
import se.haya.skk.dictionary.DictionarySourceInfo

/** 辞書と関連操作を、Android 標準の設定項目として表示します。 */
class DictionarySettingsFragment : PreferenceFragmentCompat() {
    private val host: DictionarySettingsActivity
        get() = requireActivity() as DictionarySettingsActivity

    private val sourcePreferences = mutableListOf<SourcePreference>()
    private var dragHelper: ItemTouchHelper? = null
    private var addSourceButton: FloatingActionButton? = null
    private var sourceOrderChangedByDrag = false

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        rebuildPreferences()
    }

    override fun onCreateAdapter(preferenceScreen: PreferenceScreen): RecyclerView.Adapter<*> =
        DictionaryPreferenceAdapter(super.onCreateAdapter(preferenceScreen))

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, state: Bundle?): View {
        val preferences = super.onCreateView(inflater, container, state)
        return FrameLayout(requireContext()).apply {
            addView(preferences, FrameLayout.LayoutParams(-1, -1))
            addView(FloatingActionButton(context).apply {
                setImageDrawable(settingsMaterialIcon(context, GoogleMaterial.Icon.gmd_add))
                contentDescription = "辞書を追加"
                isEnabled = !host.isPageBusy()
                setOnClickListener { host.showAddSourceDialog() }
                addSourceButton = this
            }, FrameLayout.LayoutParams(dp(56), dp(56), Gravity.END or Gravity.BOTTOM).apply {
                marginEnd = dp(20)
                bottomMargin = dp(20)
            })
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        listView.apply {
            clipToPadding = false
            setPadding(paddingLeft, paddingTop, paddingRight, paddingBottom + dp(80))
        }
        dragHelper = ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN,
            0,
        ) {
            override fun isLongPressDragEnabled() = false

            override fun getMovementFlags(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
            ): Int = if (sourcePreference(viewHolder) != null && !host.isPageBusy()) {
                makeMovementFlags(ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0)
            } else {
                makeMovementFlags(0, 0)
            }

            override fun onMove(
                recyclerView: RecyclerView,
                source: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder,
            ): Boolean {
                val moving = sourcePreference(source) ?: return false
                val destination = sourcePreference(target) ?: return false
                val from = sourcePreferences.indexOf(moving)
                val to = sourcePreferences.indexOf(destination)
                if (from < 0 || to < 0 || from == to || host.isPageBusy()) return false

                val fromAdapterPosition = source.bindingAdapterPosition
                val toAdapterPosition = target.bindingAdapterPosition
                val adapter = recyclerView.adapter as? DictionaryPreferenceAdapter<*> ?: return false
                sourcePreferences.add(to, sourcePreferences.removeAt(from))
                // Preference#setOrder は階層全体の非同期再構築を予約し、複数行をまたぐ
                // ドラッグの途中で保持中の ViewHolder を交換してしまいます。ドラッグ中は
                // RecyclerView と下書きだけを同期し、標準 Preference 階層は clearView で再構築します。
                adapter.moveSource(fromAdapterPosition, toAdapterPosition)
                sourceOrderChangedByDrag = true
                host.moveSource(from, to)
                return true
            }

            override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
                super.clearView(recyclerView, viewHolder)
                if (sourceOrderChangedByDrag) {
                    sourceOrderChangedByDrag = false
                    refresh()
                }
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) = Unit
        }).also { it.attachToRecyclerView(listView) }
    }

    override fun onDestroyView() {
        dragHelper?.attachToRecyclerView(null)
        dragHelper = null
        addSourceButton = null
        super.onDestroyView()
    }

    override fun onResume() {
        super.onResume()
        updateAddSourceButton()
    }

    fun refresh() {
        if (!isAdded) return
        updateAddSourceButton()
        rebuildPreferences()
    }

    private fun updateAddSourceButton() {
        addSourceButton?.isEnabled = !host.isPageBusy()
    }

    private fun rebuildPreferences() {
        val context = requireContext()
        val screen = preferenceManager.createPreferenceScreen(context)
        sourcePreferences.clear()

        val sourcesCategory = category(screen, "現在の辞書")
        val sources = host.visibleSources()
        if (sources.isEmpty()) {
            sourcesCategory.addPreference(info("辞書はまだ追加されていません。右下の＋から追加できます。"))
        } else {
            sources.forEachIndexed { index, source ->
                SourcePreference(context, source, index).also {
                    it.order = index
                    sourcePreferences += it
                    sourcesCategory.addPreference(it)
                }
            }
            sourcesCategory.addPreference(info(
                "上ほど変換候補の優先順位が高くなります。ハンドルをドラッグして並べ替えます。",
            ).apply { order = sources.size })
        }

        val personalCategory = category(screen, "個人辞書")
        personalCategory.addPreference(action(
            "個人辞書をファイルから取り込む",
            "現在の登録・学習内容へ追加します。",
            host::mergePersonal,
        ))
        personalCategory.addPreference(action(
            "個人辞書をファイルで置き換える",
            "現在の登録・学習内容を置き換えます。",
            host::replacePersonal,
        ))
        personalCategory.addPreference(action(
            "個人辞書をファイルに保存",
            "SKKテキスト形式で保存します。",
            host::exportPersonal,
        ))

        val backupCategory = category(screen, "完全バックアップ")
        backupCategory.addPreference(action(
            "全辞書をバックアップ・復元",
            "辞書、優先順、非表示候補、学習履歴をまとめて扱います。",
            host::openCompleteBackup,
        ))

        val suppressionCategory = category(screen, "非表示にした候補")
        val snapshot = host.visibleSuppressions()
        when {
            snapshot == null -> suppressionCategory.addPreference(info("候補の状態を確認しています。"))
            snapshot.suppressions.isEmpty() -> suppressionCategory.addPreference(info("非表示の候補はありません。"))
            else -> snapshot.suppressions.forEach { suppression ->
                val source = sources.firstOrNull { it.id == suppression.key.sourceId }
                    ?.let(host::sourceDisplayName) ?: "現在利用できない辞書"
                suppressionCategory.addPreference(action(
                    suppression.key.templateText,
                    "$source　読み: ${suppression.key.entryKey}　送り: ${suppression.key.okuriCondition ?: "なし"}",
                ) { host.confirmRestoreSuppression(suppression, snapshot.personalGeneration, source) })
            }
        }

        val fileCategory = category(screen, "ファイルの読み込み")
        fileCategory.addPreference(ListPreference(context).apply {
            key = KEY_ENCODING
            title = "文字コード"
            dialogTitle = "文字コード"
            entries = SkkDictionaryEncoding.entries.map(::encodingLabel).toTypedArray()
            entryValues = SkkDictionaryEncoding.entries.map { it.name }.toTypedArray()
            value = host.selectedEncoding().name
            summaryProvider = ListPreference.SimpleSummaryProvider.getInstance()
            isIconSpaceReserved = false
            isPersistent = false
            isEnabled = !host.isPageBusy()
            setOnPreferenceChangeListener { _, newValue ->
                val encoding = runCatching { SkkDictionaryEncoding.valueOf(newValue as String) }.getOrNull()
                    ?: return@setOnPreferenceChangeListener false
                host.setEncoding(encoding)
                true
            }
        })
        fileCategory.addPreference(action(
            "配布元とライセンス",
            "公式辞書の情報を確認します。",
            host::openCatalogLicense,
        ))
        preferenceScreen = screen
    }

    private fun category(screen: PreferenceScreen, titleText: String) =
        PreferenceCategory(requireContext()).apply {
            title = titleText
            isIconSpaceReserved = false
            screen.addPreference(this)
        }

    private fun action(titleText: String, summaryText: String?, run: () -> Unit) =
        Preference(requireContext()).apply {
            title = titleText
            summary = summaryText
            isIconSpaceReserved = false
            isPersistent = false
            isEnabled = !host.isPageBusy()
            setOnPreferenceClickListener { run(); true }
        }

    private fun info(titleText: String) = Preference(requireContext()).apply {
        title = titleText
        isIconSpaceReserved = false
        isSelectable = false
    }

    private fun sourcePreference(holder: RecyclerView.ViewHolder): SourcePreference? =
        holder.itemView.getTag(R.id.dictionary_source_actions) as? SourcePreference

    /** 標準 Preference の生成・描画を維持し、ドラッグ中だけ表示位置を対応付けます。 */
    private class DictionaryPreferenceAdapter<VH : RecyclerView.ViewHolder>(
        private val delegate: RecyclerView.Adapter<VH>,
    ) : RecyclerView.Adapter<VH>(), PreferenceGroup.PreferencePositionCallback {
        private val positions = (0 until delegate.itemCount).toMutableList()
        private val observer = object : RecyclerView.AdapterDataObserver() {
            override fun onChanged() = resetPositions()
            override fun onItemRangeInserted(positionStart: Int, itemCount: Int) = resetPositions()
            override fun onItemRangeRemoved(positionStart: Int, itemCount: Int) = resetPositions()
            override fun onItemRangeMoved(fromPosition: Int, toPosition: Int, itemCount: Int) =
                resetPositions()

            override fun onItemRangeChanged(positionStart: Int, itemCount: Int) =
                onItemRangeChanged(positionStart, itemCount, null)

            override fun onItemRangeChanged(positionStart: Int, itemCount: Int, payload: Any?) {
                positions.forEachIndexed { displayed, original ->
                    if (original in positionStart until positionStart + itemCount) {
                        notifyItemChanged(displayed, payload)
                    }
                }
            }
        }

        init {
            setHasStableIds(delegate.hasStableIds())
        }

        private fun resetPositions() {
            positions.clear()
            positions.addAll(0 until delegate.itemCount)
            notifyDataSetChanged()
        }

        override fun onAttachedToRecyclerView(recyclerView: RecyclerView) {
            resetPositions()
            delegate.registerAdapterDataObserver(observer)
            delegate.onAttachedToRecyclerView(recyclerView)
        }

        override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
            delegate.unregisterAdapterDataObserver(observer)
            delegate.onDetachedFromRecyclerView(recyclerView)
        }

        override fun getItemCount() = positions.size
        override fun getItemId(position: Int) = delegate.getItemId(positions[position])
        override fun getItemViewType(position: Int) = delegate.getItemViewType(positions[position])
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
            delegate.onCreateViewHolder(parent, viewType)

        override fun onBindViewHolder(holder: VH, position: Int) {
            // bindViewHolder を呼ぶと保持元が delegate に変わるため、描画だけを委譲します。
            delegate.onBindViewHolder(holder, positions[position])
        }

        override fun onBindViewHolder(holder: VH, position: Int, payloads: MutableList<Any>) {
            delegate.onBindViewHolder(holder, positions[position], payloads)
        }

        override fun onViewRecycled(holder: VH) = delegate.onViewRecycled(holder)
        override fun onFailedToRecycleView(holder: VH) = delegate.onFailedToRecycleView(holder)
        override fun onViewAttachedToWindow(holder: VH) = delegate.onViewAttachedToWindow(holder)
        override fun onViewDetachedFromWindow(holder: VH) = delegate.onViewDetachedFromWindow(holder)

        override fun getPreferenceAdapterPosition(preference: Preference): Int = positions.indexOf(
            (delegate as? PreferenceGroup.PreferencePositionCallback)
                ?.getPreferenceAdapterPosition(preference) ?: RecyclerView.NO_POSITION,
        )

        override fun getPreferenceAdapterPosition(key: String): Int = positions.indexOf(
            (delegate as? PreferenceGroup.PreferencePositionCallback)
                ?.getPreferenceAdapterPosition(key) ?: RecyclerView.NO_POSITION,
        )

        fun moveSource(from: Int, to: Int) {
            positions.add(to, positions.removeAt(from))
            notifyItemMoved(from, to)
        }
    }

    private inner class SourcePreference(
        context: Context,
        val source: DictionarySourceInfo,
        initialIndex: Int,
    ) : Preference(context) {
        init {
            key = "dictionary_source_${source.id}"
            title = host.sourceDisplayName(source)
            summary = "優先順位 ${initialIndex + 1}　${host.visibleSourceStatus(source.id)}\n" +
                host.sourceUpdateSummary(source)
            icon = settingsMaterialIcon(
                context,
                GoogleMaterial.Icon.gmd_drag_indicator,
                settingsThemeColor(context, androidx.appcompat.R.attr.colorControlNormal),
            )
            widgetLayoutResource = R.layout.preference_widget_dictionary_source
            isIconSpaceReserved = true
            isPersistent = false
            isEnabled = !host.isPageBusy()
            setOnPreferenceClickListener {
                host.showMoveSourceDialog(source, sourcePreferences.indexOf(this))
                true
            }
        }

        override fun onBindViewHolder(holder: PreferenceViewHolder) {
            super.onBindViewHolder(holder)
            holder.itemView.setTag(R.id.dictionary_source_actions, this)

            (holder.findViewById(android.R.id.icon)?.parent as? View)?.apply {
                contentDescription = "${title}の優先順位を変更"
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    host.showMoveSourceDialog(source, sourcePreferences.indexOf(this@SourcePreference))
                }
                setOnTouchListener { _, event ->
                    if (event.actionMasked == MotionEvent.ACTION_DOWN && !host.isPageBusy()) {
                        dragHelper?.startDrag(holder)
                    }
                    true
                }
            }
            (holder.findViewById(R.id.dictionary_source_update) as ImageButton).apply {
                setImageDrawable(settingsMaterialIcon(context, GoogleMaterial.Icon.gmd_refresh))
                contentDescription = "${title}を更新"
                isEnabled = host.canUpdateSource(source) && !host.isPageBusy()
                setOnClickListener { host.updateSource(source) }
            }
            (holder.findViewById(R.id.dictionary_source_delete) as ImageButton).apply {
                setImageDrawable(settingsMaterialIcon(context, GoogleMaterial.Icon.gmd_delete_outline))
                contentDescription = "${title}を削除"
                isEnabled = !host.isPageBusy()
                setOnClickListener { host.confirmRemove(source) }
            }
        }
    }

    private fun encodingLabel(value: SkkDictionaryEncoding) = when (value) {
        SkkDictionaryEncoding.AUTO -> "自動判定（UTF-8、EUC-JP）"
        SkkDictionaryEncoding.UTF8 -> "UTF-8"
        SkkDictionaryEncoding.EUC_JP -> "EUC-JP"
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val KEY_ENCODING = "dictionary_encoding"
    }
}

internal fun dictionarySourceSummary(enabled: Boolean, index: Int, availability: String): String =
    "${if (enabled) "有効" else "無効"}　優先順位 ${index + 1}　$availability"
