package se.haya.skk.core.dictionary

import java.util.Collections

enum class SkkDictionaryEncoding {
    AUTO,
    UTF8,
    EUC_JP,
    EUC_JIS_2004,
}

enum class SkkDictionarySection(val serializedName: String) {
    OKURI_ARI("okuri-ari"),
    OKURI_NASI("okuri-nasi");

    companion object {
        fun infer(key: String): SkkDictionarySection =
            if (key.lastOrNull() in 'a'..'z' && key.any { it.code > 0x7f }) OKURI_ARI else OKURI_NASI
    }
}

data class SkkDictionaryCandidate(
    val text: String,
    val annotation: String? = null,
    val okuriCondition: String? = null,
    val annotationIsPersonal: Boolean = false,
    val dateExpression: Boolean = false,
)

class SkkDictionaryEntry(
    val key: String,
    candidates: List<SkkDictionaryCandidate>,
    val section: SkkDictionarySection = SkkDictionarySection.infer(key),
) {
    val candidates: List<SkkDictionaryCandidate> =
        Collections.unmodifiableList(ArrayList(candidates))

    override fun equals(other: Any?): Boolean =
        other is SkkDictionaryEntry && key == other.key && candidates == other.candidates && section == other.section

    override fun hashCode(): Int = 31 * (31 * key.hashCode() + candidates.hashCode()) + section.hashCode()

    override fun toString(): String = "SkkDictionaryEntry(key=$key, candidates=$candidates, section=$section)"
}

enum class SkkDictionaryImportPolicy {
    STRICT,
    SKIP_KNOWN_UNSUPPORTED,
}

enum class SkkDictionaryWarningReason {
    UNSUPPORTED_DATE_EXPRESSION,
    UNSUPPORTED_SUPPRESSION_EXPRESSION,
    EMPTY_HEADWORD,
}

data class SkkDictionaryWarning(val lineNumber: Int, val reason: SkkDictionaryWarningReason)

/** 本文を保持せず、件数と上限付きの行番号だけを公開します。 */
class SkkDictionaryWarnings(
    val skippedCandidateCount: Int = 0,
    val skippedEntryCount: Int = 0,
    counts: Map<SkkDictionaryWarningReason, Int> = emptyMap(),
    samples: List<SkkDictionaryWarning> = emptyList(),
) {
    val counts: Map<SkkDictionaryWarningReason, Int> = Collections.unmodifiableMap(java.util.EnumMap<SkkDictionaryWarningReason, Int>(SkkDictionaryWarningReason::class.java).apply { putAll(counts) })
    val samples: List<SkkDictionaryWarning> = Collections.unmodifiableList(ArrayList(samples))
    val count: Int get() = counts.values.sum()

    override fun equals(other: Any?): Boolean = other is SkkDictionaryWarnings &&
        skippedCandidateCount == other.skippedCandidateCount && skippedEntryCount == other.skippedEntryCount &&
        counts == other.counts && samples == other.samples
    override fun hashCode(): Int = listOf(skippedCandidateCount, skippedEntryCount, counts, samples).hashCode()
    override fun toString(): String = "SkkDictionaryWarnings(skippedCandidateCount=$skippedCandidateCount, skippedEntryCount=$skippedEntryCount, counts=$counts, samples=$samples)"
}

data class SkkDictionaryDiagnostics(
    val duplicateCandidateCount: Int = 0,
    val annotationConflictCount: Int = 0,
    val warnings: SkkDictionaryWarnings = SkkDictionaryWarnings(),
)

class SkkDictionaryDocument(
    entries: List<SkkDictionaryEntry>,
    val encoding: SkkDictionaryEncoding,
    val diagnostics: SkkDictionaryDiagnostics = SkkDictionaryDiagnostics(),
) {
    val entries: List<SkkDictionaryEntry> =
        Collections.unmodifiableList(ArrayList(entries))

    override fun equals(other: Any?): Boolean =
        other is SkkDictionaryDocument &&
            entries == other.entries &&
            encoding == other.encoding &&
            diagnostics == other.diagnostics

    override fun hashCode(): Int {
        var result = entries.hashCode()
        result = 31 * result + encoding.hashCode()
        result = 31 * result + diagnostics.hashCode()
        return result
    }

    override fun toString(): String =
        "SkkDictionaryDocument(entries=$entries, encoding=$encoding, diagnostics=$diagnostics)"
}

enum class SkkDictionaryError {
    FILE_TOO_LARGE,
    DECODING_FAILED,
    LINE_TOO_LONG,
    INVALID_ENTRY,
    INVALID_KEY,
    INVALID_CANDIDATE_LIST,
    INVALID_VALUE,
    INVALID_OKURI_BLOCK,
    UNSUPPORTED_EXPRESSION,
    NO_SUPPORTED_ENTRIES,
}

class SkkDictionaryFormatException(
    val lineNumber: Int,
    val error: SkkDictionaryError,
) : IllegalArgumentException("SKK 辞書の ${lineNumber} 行目を読み込めません（${error.label}）。")

private val SkkDictionaryError.label: String
    get() = when (this) {
        SkkDictionaryError.FILE_TOO_LARGE -> "ファイルが上限を超えています"
        SkkDictionaryError.DECODING_FAILED -> "文字コードが不正です"
        SkkDictionaryError.LINE_TOO_LONG -> "行が上限を超えています"
        SkkDictionaryError.INVALID_ENTRY -> "項目の形式が不正です"
        SkkDictionaryError.INVALID_KEY -> "見出し語が不正です"
        SkkDictionaryError.INVALID_CANDIDATE_LIST -> "候補列が不正です"
        SkkDictionaryError.INVALID_VALUE -> "候補または注釈が不正です"
        SkkDictionaryError.INVALID_OKURI_BLOCK -> "送り条件が不正です"
        SkkDictionaryError.UNSUPPORTED_EXPRESSION -> "非対応の式です"
        SkkDictionaryError.NO_SUPPORTED_ENTRIES -> "取り込める候補がありません"
    }
