package se.haya.skk.core.dictionary

const val COMPLETE_DICTIONARY_BACKUP_FORMAT: String = "skk-android-dictionary-backup"
const val COMPLETE_DICTIONARY_BACKUP_VERSION: Int = 3

enum class BackupSourceKind(val serializedName: String) {
    PERSONAL("personal"),
    SYSTEM("system"),
}

sealed interface BackupRecord {
    data class Header(
        val format: String,
        val version: Int,
        val producerVersion: String,
    ) : BackupRecord

    data class Source(
        val id: String,
        val name: String,
        val kind: BackupSourceKind,
        val generation: Long,
        val enabled: Boolean,
        val order: Int,
        val originUrl: String? = null,
    ) : BackupRecord

    data class Candidate(
        val sourceId: String,
        val entryKey: String,
        val ordinal: Int,
        val text: String,
        val annotation: String?,
        val okuriCondition: String?,
    ) : BackupRecord

    data class Suppression(
        val sourceId: String,
        val entryKey: String,
        val templateText: String,
        val okuriCondition: String?,
    ) : BackupRecord

    data class SourceVersion(
        val sourceId: String,
        val lastGeneration: Long,
    ) : BackupRecord

    data class Usage(
        val readingKey: String,
        val templateText: String,
        val okuriCondition: String?,
        val committedText: String,
        val lastUsedSequence: Long,
    ) : BackupRecord

    data class End(
        val sourceCount: Int,
        val candidateCount: Int,
        val suppressionCount: Int,
        val sourceVersionCount: Int,
        val usageCount: Int = 0,
    ) : BackupRecord
}

data class BackupSummary(
    val producerVersion: String,
    val sourceCount: Int,
    val candidateCount: Int,
    val suppressionCount: Int,
    val sourceVersionCount: Int,
    val usageCount: Int = 0,
)

data class CompleteDictionaryBackupLimits(
    val maxFileBytes: Long = 256L * 1024 * 1024,
    val maxLineBytes: Int = 8 * 1024 * 1024,
    val maxStringChars: Int = 1_048_576,
    val maxIdentifierChars: Int = 1_024,
    val maxSourceCount: Int = 1_024,
    val maxCandidateCount: Int = 1_000_000,
    val maxSuppressionCount: Int = 100_000,
    val maxSourceVersionCount: Int = 100_000,
    val maxUsageCount: Int = 100_000,
) {
    init {
        require(maxFileBytes > 0)
        require(maxLineBytes > 0)
        require(maxStringChars > 0)
        require(maxIdentifierChars > 0)
        require(maxSourceCount > 0)
        require(maxCandidateCount >= 0)
        require(maxSuppressionCount >= 0)
        require(maxSourceVersionCount >= 0)
        require(maxUsageCount >= 0)
    }
}

enum class CompleteDictionaryBackupError {
    INVALID_UTF8,
    INVALID_JSON,
    UNKNOWN_FIELD,
    DUPLICATE_FIELD,
    MISSING_FIELD,
    WRONG_TYPE,
    UNKNOWN_RECORD_TYPE,
    UNSUPPORTED_VERSION,
    INVALID_VALUE,
    INVALID_RECORD_ORDER,
    DUPLICATE_IDENTITY,
    COUNT_MISMATCH,
    LIMIT_EXCEEDED,
    INCOMPLETE,
}

class CompleteDictionaryBackupException(
    val recordNumber: Long,
    val error: CompleteDictionaryBackupError,
) : IllegalArgumentException("完全辞書バックアップのレコード $recordNumber が不正です（$error）。")
