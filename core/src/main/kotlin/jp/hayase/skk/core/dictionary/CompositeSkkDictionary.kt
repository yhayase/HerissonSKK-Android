package jp.hayase.skk.core.dictionary

import java.util.Collections
import jp.hayase.skk.core.BasicSkkDictionary
import jp.hayase.skk.core.DictionaryCandidate
import jp.hayase.skk.core.DictionaryQuery

/** 公開済み辞書の読み取り専用世代です。構築後は呼出側のリスト変更に影響されません。 */
class SkkDictionarySource(
    val id: String,
    val generation: Long,
    entries: List<SkkDictionaryEntry>,
    val enabled: Boolean = true,
) {
    private val index = entries.associate { it.key to it.candidates }

    init {
        require(id.isNotBlank()) { "辞書IDは空にできません" }
        require(generation >= 0) { "辞書世代は0以上にします" }
        require(index.size == entries.size) { "辞書の見出し語が重複しています" }
    }

    internal fun candidates(key: String): List<SkkDictionaryCandidate> = index[key].orEmpty()
}

data class CandidateOrigin(
    val dictionaryId: String,
    val generation: Long,
    val personal: Boolean,
    val candidate: SkkDictionaryCandidate,
)

/** 個人領域で非表示にしたシステム候補です。注釈や辞書更新世代は同一性に含めません。 */
data class SuppressedDictionaryCandidate(
    val sourceId: String,
    val entryKey: String,
    val text: String,
    val okuriCondition: String? = null,
)

class ResolvedDictionaryCandidate internal constructor(
    val candidate: SkkDictionaryCandidate,
    origins: List<CandidateOrigin>,
) {
    val origins: List<CandidateOrigin> = Collections.unmodifiableList(ArrayList(origins))
}

/**
 * K04/K12 の検索順位を確定する、I/O のない辞書スナップショットです。
 * 個人辞書、システム辞書の指定順を優先し、送り条件は各辞書内だけで優先します。
 * 学習・削除・永続化はこの読み取り層では行いません。
 */
class CompositeSkkDictionary(
    personal: SkkDictionarySource? = null,
    systems: List<SkkDictionarySource> = emptyList(),
    suppressions: Collection<SuppressedDictionaryCandidate> = emptyList(),
) : BasicSkkDictionary {
    private val personalGeneration = personal?.generation
    private val sources = listOfNotNull(personal?.let { it to true }) + systems.map { it to false }
    private val suppressions = suppressions.toSet()

    init {
        require(sources.map { it.first.id }.distinct().size == sources.size) { "辞書IDが重複しています" }
    }

    override fun lookup(query: DictionaryQuery): List<DictionaryCandidate> = resolve(query).map { resolved ->
        DictionaryCandidate(
            text = resolved.candidate.text,
            annotation = resolved.candidate.annotation,
            okuriCondition = resolved.candidate.okuriCondition,
            selection = personalGeneration?.let { generation ->
                CandidateSelection(
                    personalGeneration = generation,
                    origins = resolved.origins.map { origin ->
                        SelectedCandidateOrigin(
                            dictionaryId = origin.dictionaryId,
                            generation = origin.generation,
                            personal = origin.personal,
                            entryKey = query.readingKey,
                            text = origin.candidate.text,
                            okuriCondition = origin.candidate.okuriCondition,
                        )
                    },
                )
            },
        )
    }

    fun resolve(query: DictionaryQuery): List<ResolvedDictionaryCandidate> {
        val byText = linkedMapOf<String, MutableList<CandidateOrigin>>()
        for ((source, personal) in sources) {
            if (!source.enabled) continue
            val candidates = source.candidates(query.readingKey).filterNot { candidate ->
                !personal && SuppressedDictionaryCandidate(
                    source.id, query.readingKey, candidate.text, candidate.okuriCondition,
                ) in suppressions
            }
            val ordered = if (query.okuri == null || query.abbrev) candidates else candidates.sortedBy {
                when (it.okuriCondition) {
                    query.okuri -> 0
                    null -> 1
                    else -> 2
                }
            }
            for (candidate in ordered) {
                byText.getOrPut(candidate.text) { mutableListOf() }
                    .add(CandidateOrigin(source.id, source.generation, personal, candidate))
            }
        }
        return byText.values.map { origins ->
            val first = origins.first().candidate
            val annotation = origins.firstNotNullOfOrNull { it.candidate.annotation?.takeIf(String::isNotEmpty) }
            ResolvedDictionaryCandidate(first.copy(annotation = annotation), origins)
        }
    }
}
