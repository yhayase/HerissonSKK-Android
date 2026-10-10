package se.haya.skk.core.dictionary

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import org.junit.Assert.*
import org.junit.Test

class CompleteDictionaryBackupPreservationTest {
    private val codec = CompleteDictionaryBackupCodec()
    private fun records(candidates: List<BackupRecord.Candidate>, usages: List<BackupRecord.Usage> = emptyList(), version: Int = 4): List<BackupRecord> =
        listOf(
            BackupRecord.Header(COMPLETE_DICTIONARY_BACKUP_FORMAT, version, "preservation-fixture"),
            BackupRecord.Source("personal", "独自保存辞書", BackupSourceKind.PERSONAL, 0, true, 0),
        ) + candidates + BackupRecord.SourceVersion("personal", 0) + usages + BackupRecord.End(1, candidates.size, 0, 1, usages.size)

    private fun roundTrip(records: List<BackupRecord>): List<BackupRecord> {
        val output = ByteArrayOutputStream()
        codec.write(records.asSequence(), output)
        val result = mutableListOf<BackupRecord>()
        codec.validate(ByteArrayInputStream(output.toByteArray()), result::add)
        return result
    }

    @Test fun `第4版は区分 順位 個人注釈 改行と使用履歴を保持する`() {
        val original = records(listOf(
            BackupRecord.Candidate("personal", "afixture", 0, "独自甲\n独自乙\r", "独自注\r\n", null, SkkDictionarySection.OKURI_NASI, Long.MAX_VALUE, true),
            BackupRecord.Candidate("personal", "zfixture", 0, "独自先頭", null, null, SkkDictionarySection.OKURI_ARI, 2),
        ), listOf(BackupRecord.Usage("afixture", "独自甲\n独自乙\r", null, "独自確定\n\r", 1)))
        assertEquals(original, roundTrip(original))
    }

    @Test fun `同じ見出しの区分順位不一致と別見出しの順位重複を拒否する`() {
        val first = BackupRecord.Candidate("personal", "afixture", 0, "独自一", null, null, entryOrder = 7)
        val invalid = listOf(
            listOf(first, first.copy(ordinal = 1, text = "独自二", entryOrder = 8)),
            listOf(first, first.copy(ordinal = 1, text = "独自二", section = SkkDictionarySection.OKURI_ARI)),
            listOf(first, first.copy(entryKey = "zfixture", text = "独自別見出し")),
            listOf(first.copy(entryOrder = -1)),
            listOf(first.copy(annotationIsPersonal = true)),
        )
        invalid.forEach { candidates ->
            assertThrows(CompleteDictionaryBackupException::class.java) {
                codec.write(records(candidates).asSequence(), ByteArrayOutputStream())
            }
        }
    }

    @Test fun `第1から3版の区分と順位は決定的な既定値にする`() {
        for (version in 1..3) {
            val result = roundTrip(records(listOf(
                BackupRecord.Candidate("personal", "afixture", 0, "独自旧一", null, null),
                BackupRecord.Candidate("personal", "zfixture", 0, "独自旧二", "独自旧注", null),
            ), version = version)).filterIsInstance<BackupRecord.Candidate>()
            assertEquals(listOf(0L, 1L), result.map { it.entryOrder })
            assertTrue(result.all { it.section == SkkDictionarySection.OKURI_NASI && !it.annotationIsPersonal })
        }
    }

    @Test fun `第4版の新フィールドは欠落 型違い 未知区分を拒否する`() {
        val original = records(listOf(BackupRecord.Candidate("personal", "afixture", 0, "独自構文", null, null)))
        val output = ByteArrayOutputStream()
        codec.write(original.asSequence(), output)
        val text = output.toString("UTF-8")
        assertTrue(text.contains("\"annotationIsPersonal\":false"))
        val variants = listOf(
            text.replace(",\"annotationIsPersonal\":false", "") to CompleteDictionaryBackupError.MISSING_FIELD,
            text.replace("\"entryOrder\":0", "\"entryOrder\":false") to CompleteDictionaryBackupError.WRONG_TYPE,
            text.replace("\"section\":\"okuri-nasi\"", "\"section\":\"unknown\"") to CompleteDictionaryBackupError.INVALID_VALUE,
        )
        variants.forEach { (source, expected) ->
            val error = assertThrows(CompleteDictionaryBackupException::class.java) {
                codec.validate(ByteArrayInputStream(source.toByteArray()), {})
            }
            assertEquals(expected, error.error)
        }
    }
}
