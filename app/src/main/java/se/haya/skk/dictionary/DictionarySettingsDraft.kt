package se.haya.skk.dictionary

import java.util.UUID
import se.haya.skk.core.dictionary.SkkDictionaryDocument

/** 設定画面の未確定操作です。文書の内容は確定まで保存済み辞書へ触れません。 */
sealed interface DictionarySettingsEdit {
    data class ImportSystem(val id: String, val name: String, val document: SkkDictionaryDocument,
        val expectedGeneration: Long?, val originUrl: String? = null) : DictionarySettingsEdit
    data class RemoveSystem(val id: String, val expectedGeneration: Long) : DictionarySettingsEdit
    data class ReplacePersonal(val document: SkkDictionaryDocument, val expectedGeneration: Long) : DictionarySettingsEdit
    data class MergePersonal(val document: SkkDictionaryDocument, val expectedGeneration: Long) : DictionarySettingsEdit
    data class SetEnabled(val id: String, val enabled: Boolean) : DictionarySettingsEdit
    data class SetOrder(val ids: List<String>) : DictionarySettingsEdit
}

/** 画面再生成後も manager に保持される一時編集です。 */
class DictionarySettingsDraft internal constructor(initial: List<DictionarySourceInfo>) {
    val id: String = UUID.randomUUID().toString()
    private val original = initial.toList()
    internal val baselineSystems: List<DictionarySourceInfo> = original.filter { it.kind == DictionarySourceKind.SYSTEM }
    private val edits = mutableListOf<DictionarySettingsEdit>()
    private var stagedSources = initial.toList()
    val sources: List<DictionarySourceInfo> get() = stagedSources.toList()
    val hasChanges: Boolean get() = edits.isNotEmpty()

    internal fun snapshot(): List<DictionarySettingsEdit> =
        // 再取り込みで追加操作の位置が変わっても、作成前に有効状態を変更しません。
        edits.filterNot { it is DictionarySettingsEdit.SetEnabled || it is DictionarySettingsEdit.SetOrder } +
            edits.filterIsInstance<DictionarySettingsEdit.SetEnabled>() +
            edits.filterIsInstance<DictionarySettingsEdit.SetOrder>()

    fun stageImport(id: String, name: String, document: SkkDictionaryDocument,
        expectedGeneration: Long?, originUrl: String? = null) {
        require(id.isNotBlank() && id != SQLiteDictionaryRepository.PERSONAL_SOURCE_ID)
        require(name.isNotBlank())
        val prior = stagedSources.firstOrNull { it.id == id }
        require(prior == null || prior.kind == DictionarySourceKind.SYSTEM)
        val originalSource = original.firstOrNull { it.id == id }
        if (originalSource != null) require(expectedGeneration == originalSource.generation)
        requireDocumentCapacity(document, edits.filterNot { it is DictionarySettingsEdit.ImportSystem && it.id == id })
        edits.removeAll { it is DictionarySettingsEdit.RemoveSystem && it.id == id }
        // 同じソースの連続更新は最後の文書だけを保持し、保持メモリーを増やしません。
        edits.removeAll { it is DictionarySettingsEdit.ImportSystem && it.id == id }
        edits += DictionarySettingsEdit.ImportSystem(id, name, document, originalSource?.generation, originUrl)
        stagedSources = if (prior == null) stagedSources + DictionarySourceInfo(
            id, name, DictionarySourceKind.SYSTEM, 0, true, stagedSources.count { it.kind == DictionarySourceKind.SYSTEM }, originUrl,
        ) else stagedSources.map { if (it.id == id) it.copy(name = name, originUrl = originUrl) else it }
        updateOrder()
    }

    fun stageRemove(source: DictionarySourceInfo) {
        require(source.kind == DictionarySourceKind.SYSTEM && stagedSources.any { it.id == source.id })
        val originalSource = original.firstOrNull { it.id == source.id }
        edits.removeAll { it is DictionarySettingsEdit.ImportSystem && it.id == source.id ||
            it is DictionarySettingsEdit.SetEnabled && it.id == source.id ||
            it is DictionarySettingsEdit.RemoveSystem && it.id == source.id }
        if (originalSource != null) edits += DictionarySettingsEdit.RemoveSystem(source.id, originalSource.generation)
        stagedSources = stagedSources.filterNot { it.id == source.id }
        updateOrder()
    }

    fun stageEnabled(id: String, enabled: Boolean) {
        require(stagedSources.any { it.id == id && it.kind == DictionarySourceKind.SYSTEM })
        edits.removeAll { it is DictionarySettingsEdit.SetEnabled && it.id == id }
        if (original.firstOrNull { it.id == id }?.enabled != enabled) {
            edits += DictionarySettingsEdit.SetEnabled(id, enabled)
        }
        stagedSources = stagedSources.map { if (it.id == id) it.copy(enabled = enabled) else it }
    }

    fun stageOrder(ids: List<String>) {
        require(ids.distinct().size == ids.size)
        require(ids.toSet() == stagedSources.filter { it.kind == DictionarySourceKind.SYSTEM }.map { it.id }.toSet())
        stagedSources = stagedSources.filter { it.kind == DictionarySourceKind.PERSONAL } +
            ids.mapIndexed { index, id -> stagedSources.first { it.id == id }.copy(order = index) }
        updateOrder()
    }

    private fun updateOrder() {
        edits.removeAll { it is DictionarySettingsEdit.SetOrder }
        val ids = stagedSources.filter { it.kind == DictionarySourceKind.SYSTEM }.sortedBy { it.order }.map { it.id }
        if (ids != original.filter { it.kind == DictionarySourceKind.SYSTEM }.sortedBy { it.order }.map { it.id }) {
            edits += DictionarySettingsEdit.SetOrder(ids)
        }
    }

    fun stagePersonal(document: SkkDictionaryDocument, expectedGeneration: Long, merge: Boolean) {
        requireDocumentCapacity(document, if (merge) edits else edits.filterNot {
            it is DictionarySettingsEdit.ReplacePersonal || it is DictionarySettingsEdit.MergePersonal
        })
        if (merge) edits += DictionarySettingsEdit.MergePersonal(document, expectedGeneration)
        else {
            edits.removeAll { it is DictionarySettingsEdit.ReplacePersonal || it is DictionarySettingsEdit.MergePersonal }
            edits += DictionarySettingsEdit.ReplacePersonal(document, expectedGeneration)
        }
    }

    private fun requireDocumentCapacity(document: SkkDictionaryDocument, retained: List<DictionarySettingsEdit>) {
        val characters = retained.sumOf { edit -> when (edit) {
            is DictionarySettingsEdit.ImportSystem -> edit.document.characterCount()
            is DictionarySettingsEdit.ReplacePersonal -> edit.document.characterCount()
            is DictionarySettingsEdit.MergePersonal -> edit.document.characterCount()
            else -> 0L
        } } + document.characterCount()
        require(characters <= MAX_DOCUMENT_CHARACTERS) { "一時保存できる辞書の容量を超えました" }
    }

    private fun SkkDictionaryDocument.characterCount(): Long = entries.sumOf { entry ->
        entry.key.length.toLong() + entry.candidates.sumOf { candidate ->
            candidate.text.length.toLong() + (candidate.annotation?.length ?: 0) +
                (candidate.okuriCondition?.length ?: 0)
        }
    }

    private companion object { const val MAX_DOCUMENT_CHARACTERS = 32_000_000L }
}
