package jp.hayase.skk.core.dictionary

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class CompleteDictionaryBackupCodecTest {
    private val codec = CompleteDictionaryBackupCodec()

    @Test
    fun `全レコードを特殊文字と null を保って往復する`() {
        val records = listOf(
            BackupRecord.Header(COMPLETE_DICTIONARY_BACKUP_FORMAT, 1, "1.0 😀"),
            BackupRecord.Source("personal", "個人辞書", BackupSourceKind.PERSONAL, 3, true, 0),
            BackupRecord.Source("system-a", "システム\"辞書", BackupSourceKind.SYSTEM, 8, false, 0),
            BackupRecord.Candidate("personal", "おおk", 0, "大/多", "注釈😀", "く"),
            BackupRecord.Candidate("personal", "おおk", 1, "多い", null, null),
            BackupRecord.Candidate("system-a", "かな", 0, "仮名", null, null),
            BackupRecord.Suppression("deleted", "おおk", "以前の候補", "く"),
            BackupRecord.SourceVersion("deleted", 12),
            BackupRecord.SourceVersion("personal", 3),
            BackupRecord.SourceVersion("system-a", 9),
            BackupRecord.End(2, 3, 1, 3),
        )
        val output = ByteArrayOutputStream()

        val written = codec.write(records.asSequence(), output)
        val consumed = mutableListOf<BackupRecord>()
        val validated = codec.validate(ByteArrayInputStream(output.toByteArray()), consumed::add)

        assertEquals(records, consumed)
        assertEquals(written, validated)
        assertEquals(BackupSummary("1.0 😀", 2, 3, 1, 3), validated)
        assertTrue(output.toString(StandardCharsets.UTF_8.name()).endsWith("\n"))
        assertFalse(output.toByteArray().take(3) == listOf(0xef.toByte(), 0xbb.toByte(), 0xbf.toByte()))
    }

    @Test
    fun `source だけの空辞書を受理する`() {
        val records = listOf(
            BackupRecord.Header(COMPLETE_DICTIONARY_BACKUP_FORMAT, 1, "test"),
            BackupRecord.Source("personal", "個人", BackupSourceKind.PERSONAL, 0, true, 0),
            BackupRecord.SourceVersion("personal", 0),
            BackupRecord.End(1, 0, 0, 1),
        )

        val summary = codec.write(records.asSequence(), ByteArrayOutputStream())

        assertEquals(0, summary.candidateCount)
    }

    @Test
    fun `未知フィールド 重複フィールド 型違い 未知版を分類して拒否する`() {
        val invalid = listOf(
            "{\"type\":\"header\",\"format\":\"$COMPLETE_DICTIONARY_BACKUP_FORMAT\",\"version\":1,\"producerVersion\":\"x\",\"extra\":0}\n" to CompleteDictionaryBackupError.UNKNOWN_FIELD,
            "{\"type\":\"header\",\"type\":\"header\",\"format\":\"$COMPLETE_DICTIONARY_BACKUP_FORMAT\",\"version\":1,\"producerVersion\":\"x\"}\n" to CompleteDictionaryBackupError.DUPLICATE_FIELD,
            "{\"type\":\"header\",\"format\":\"$COMPLETE_DICTIONARY_BACKUP_FORMAT\",\"version\":1.0,\"producerVersion\":\"x\"}\n" to CompleteDictionaryBackupError.INVALID_JSON,
            "{\"type\":\"header\",\"format\":\"$COMPLETE_DICTIONARY_BACKUP_FORMAT\",\"version\":2,\"producerVersion\":\"x\"}\n" to CompleteDictionaryBackupError.UNSUPPORTED_VERSION,
        )

        invalid.forEach { (text, expected) ->
            val exception = assertThrows(CompleteDictionaryBackupException::class.java) {
                codec.validate(ByteArrayInputStream(text.toByteArray()), {})
            }
            assertEquals(expected, exception.error)
            assertEquals(1, exception.recordNumber)
        }
    }

    @Test
    fun `JSON の数値と Unicode escape は ASCII 字句だけを受理する`() {
        val invalid = listOf(
            "{\"type\":\"header\",\"format\":\"$COMPLETE_DICTIONARY_BACKUP_FORMAT\",\"version\":1١,\"producerVersion\":\"x\"}\n",
            "{\"type\":\"header\",\"format\":\"$COMPLETE_DICTIONARY_BACKUP_FORMAT\",\"version\":1,\"producerVersion\":\"\\u00６1\"}\n",
        )

        invalid.forEach { text ->
            val exception = assertThrows(CompleteDictionaryBackupException::class.java) {
                codec.validate(ByteArrayInputStream(text.toByteArray(StandardCharsets.UTF_8)), {})
            }
            assertEquals(CompleteDictionaryBackupError.INVALID_JSON, exception.error)
        }
    }

    @Test
    fun `単一オブジェクトのフィールド数とフィールド名を小さく制限する`() {
        val tooManyFields = buildString {
            append("{\"type\":\"header\"")
            repeat(8) { append(",\"x").append(it).append("\":null") }
            append("}\n")
        }
        val longFieldName = "{\"type\":\"header\",\"thisFieldNameIsTooLong\":null}\n"

        listOf(tooManyFields, longFieldName).forEach { text ->
            val exception = assertThrows(CompleteDictionaryBackupException::class.java) {
                codec.validate(ByteArrayInputStream(text.toByteArray(StandardCharsets.UTF_8)), {})
            }
            assertEquals(CompleteDictionaryBackupError.UNKNOWN_FIELD, exception.error)
        }
    }

    @Test
    fun `不正 UTF-8 BOM CRLF 最終 LF 欠落を拒否する`() {
        val validHeader = "{\"type\":\"header\",\"format\":\"$COMPLETE_DICTIONARY_BACKUP_FORMAT\",\"version\":1,\"producerVersion\":\"x\"}"
        val invalid = listOf(
            byteArrayOf(0xff.toByte(), '\n'.code.toByte()) to CompleteDictionaryBackupError.INVALID_UTF8,
            byteArrayOf(0xef.toByte(), 0xbb.toByte(), 0xbf.toByte()) + "$validHeader\n".toByteArray() to CompleteDictionaryBackupError.INVALID_JSON,
            "$validHeader\r\n".toByteArray() to CompleteDictionaryBackupError.INVALID_JSON,
            validHeader.toByteArray() to CompleteDictionaryBackupError.INCOMPLETE,
        )

        invalid.forEach { (bytes, expected) ->
            val exception = assertThrows(CompleteDictionaryBackupException::class.java) {
                codec.validate(ByteArrayInputStream(bytes), {})
            }
            assertEquals(expected, exception.error)
        }
    }

    @Test
    fun `候補順と終端件数と台帳網羅を検証する`() {
        fun assertFailure(records: List<BackupRecord>, expected: CompleteDictionaryBackupError) {
            val exception = assertThrows(CompleteDictionaryBackupException::class.java) {
                codec.write(records.asSequence(), ByteArrayOutputStream())
            }
            assertEquals(expected, exception.error)
        }
        val prefix = listOf(
            BackupRecord.Header(COMPLETE_DICTIONARY_BACKUP_FORMAT, 1, "test"),
            BackupRecord.Source("personal", "個人", BackupSourceKind.PERSONAL, 2, true, 0),
        )
        assertFailure(
            prefix + BackupRecord.Candidate("personal", "かな", 1, "仮名", null, null),
            CompleteDictionaryBackupError.INVALID_RECORD_ORDER,
        )
        assertFailure(
            prefix + BackupRecord.SourceVersion("personal", 1),
            CompleteDictionaryBackupError.INVALID_VALUE,
        )
        assertFailure(
            prefix + BackupRecord.End(1, 0, 0, 0),
            CompleteDictionaryBackupError.INVALID_VALUE,
        )
        assertFailure(
            prefix + BackupRecord.SourceVersion("personal", 2) + BackupRecord.End(1, 1, 0, 1),
            CompleteDictionaryBackupError.COUNT_MISMATCH,
        )
    }

    @Test
    fun `候補同一性の重複は staging consumer が全件検証する`() {
        val records = listOf(
            BackupRecord.Header(COMPLETE_DICTIONARY_BACKUP_FORMAT, 1, "test"),
            BackupRecord.Source("personal", "個人", BackupSourceKind.PERSONAL, 0, true, 0),
            BackupRecord.Candidate("personal", "かな", 0, "仮名", null, null),
            BackupRecord.Candidate("personal", "かな", 1, "仮名", "別注釈", null),
            BackupRecord.SourceVersion("personal", 0),
            BackupRecord.End(1, 2, 0, 1),
        )
        val output = ByteArrayOutputStream()
        codec.write(records.asSequence(), output)
        val identities = mutableSetOf<List<String?>>()

        assertThrows(IllegalStateException::class.java) {
            codec.validate(ByteArrayInputStream(output.toByteArray())) { record ->
                if (record is BackupRecord.Candidate) {
                    check(identities.add(listOf(record.sourceId, record.entryKey, record.text, record.okuriCondition)))
                }
            }
        }
    }

    @Test
    fun `設定した各容量境界の一致を受理し直後を拒否する`() {
        val boundaryCodec = CompleteDictionaryBackupCodec(
            CompleteDictionaryBackupLimits(
                maxFileBytes = 10_000,
                maxLineBytes = 1_000,
                maxStringChars = 64,
                maxIdentifierChars = 8,
                maxSourceCount = 1,
                maxCandidateCount = 1,
                maxSuppressionCount = 0,
                maxSourceVersionCount = 1,
            ),
        )
        val atBoundary = listOf(
            BackupRecord.Header(COMPLETE_DICTIONARY_BACKUP_FORMAT, 1, "12345678"),
            BackupRecord.Source("personal", "12345678", BackupSourceKind.PERSONAL, 0, true, 0),
            BackupRecord.Candidate("personal", "かな", 0, "x".repeat(64), null, null),
            BackupRecord.SourceVersion("personal", 0),
            BackupRecord.End(1, 1, 0, 1),
        )
        boundaryCodec.write(atBoundary.asSequence(), ByteArrayOutputStream())

        val over = atBoundary.toMutableList().apply {
            this[2] = BackupRecord.Candidate("personal", "かな", 0, "x".repeat(65), null, null)
        }
        val exception = assertThrows(CompleteDictionaryBackupException::class.java) {
            boundaryCodec.write(over.asSequence(), ByteArrayOutputStream())
        }
        assertEquals(CompleteDictionaryBackupError.LIMIT_EXCEEDED, exception.error)
    }
}
