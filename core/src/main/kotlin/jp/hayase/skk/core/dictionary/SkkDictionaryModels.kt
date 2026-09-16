package jp.hayase.skk.core.dictionary

import java.util.Collections

enum class SkkDictionaryEncoding {
    AUTO,
    UTF8,
    EUC_JP,
}

data class SkkDictionaryCandidate(
    val text: String,
    val annotation: String? = null,
    val okuriCondition: String? = null,
)

class SkkDictionaryEntry(
    val key: String,
    candidates: List<SkkDictionaryCandidate>,
) {
    val candidates: List<SkkDictionaryCandidate> =
        Collections.unmodifiableList(ArrayList(candidates))

    override fun equals(other: Any?): Boolean =
        other is SkkDictionaryEntry && key == other.key && candidates == other.candidates

    override fun hashCode(): Int = 31 * key.hashCode() + candidates.hashCode()

    override fun toString(): String = "SkkDictionaryEntry(key=$key, candidates=$candidates)"
}

data class SkkDictionaryDiagnostics(
    val duplicateCandidateCount: Int = 0,
    val annotationConflictCount: Int = 0,
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
    }
