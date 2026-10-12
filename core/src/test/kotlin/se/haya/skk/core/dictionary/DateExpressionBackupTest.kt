package se.haya.skk.core.dictionary

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import org.junit.Assert.*
import org.junit.Test

class DateExpressionBackupTest {
    private fun roundTrip(version: Int, dynamic: Boolean): List<BackupRecord> {
        val records = listOf(
            BackupRecord.Header(COMPLETE_DICTIONARY_BACKUP_FORMAT, version, "date-fixture"),
            BackupRecord.Source("personal", "日付試験", BackupSourceKind.PERSONAL, 0, true, 0),
            BackupRecord.Candidate("personal", "datefixture", 0, "(skk-current-date)", null, null),
            BackupRecord.Candidate("personal", "datefixture", 1, "(skk-current-date)", null, null, dateExpression = dynamic),
            BackupRecord.Suppression("system", "datefixture", "(skk-current-date)", null),
            BackupRecord.Suppression("system", "datefixture", "(skk-current-date)", null, dateExpression = dynamic),
            BackupRecord.SourceVersion("personal", 0),
            BackupRecord.Usage("datefixture", "(skk-current-date)", null, "日付", 1),
            BackupRecord.Usage("datefixture", "(skk-current-date)", null, "日付", 2, dateExpression = dynamic),
            BackupRecord.End(1, 2, 2, 1, 2),
        )
        val output = ByteArrayOutputStream()
        val codec = CompleteDictionaryBackupCodec()
        codec.write(records.asSequence(), output)
        return mutableListOf<BackupRecord>().also { codec.validate(ByteArrayInputStream(output.toByteArray()), it::add) }
    }

    @Test fun `第5版は同じ本文の式と文字列を候補 抑止 履歴で区別する`() {
        val records = roundTrip(5, true)
        assertEquals(listOf(false, true), records.filterIsInstance<BackupRecord.Candidate>().map { it.dateExpression })
        assertEquals(listOf(false, true), records.filterIsInstance<BackupRecord.Suppression>().map { it.dateExpression })
        assertEquals(listOf(false, true), records.filterIsInstance<BackupRecord.Usage>().map { it.dateExpression })
    }

    @Test fun `旧版への式保存を拒否する`() {
        assertThrows(CompleteDictionaryBackupException::class.java) { roundTrip(4, true) }
    }

    @Test fun `第4版の式に似た文字列はリテラルとして復元する`() {
        val original = listOf(
            BackupRecord.Header(COMPLETE_DICTIONARY_BACKUP_FORMAT, 4, "date-fixture"),
            BackupRecord.Source("personal", "旧版試験", BackupSourceKind.PERSONAL, 0, true, 0),
            BackupRecord.Candidate("personal", "datefixture", 0, "(skk-current-date)", null, null),
            BackupRecord.SourceVersion("personal", 0),
            BackupRecord.End(1, 1, 0, 1),
        )
        val codec = CompleteDictionaryBackupCodec()
        val output = ByteArrayOutputStream()
        codec.write(original.asSequence(), output)
        val result = mutableListOf<BackupRecord>()
        codec.validate(ByteArrayInputStream(output.toByteArray()), result::add)
        assertFalse(result.filterIsInstance<BackupRecord.Candidate>().single().dateExpression)
    }

    @Test fun `第5版のマーカー欠落 型違いと任意式を拒否する`() {
        val records = roundTrip(5, true)
        val codec = CompleteDictionaryBackupCodec()
        val output = ByteArrayOutputStream()
        codec.write(records.asSequence(), output)
        val text = output.toString("UTF-8")
        for (source in listOf(
            text.replace(",\"dateExpression\":true", ""),
            text.replace("\"dateExpression\":true", "\"dateExpression\":\"true\""),
            text.replace("(skk-current-date)", "(arbitrary-function)"),
        )) assertThrows(CompleteDictionaryBackupException::class.java) {
            codec.validate(source.byteInputStream(), {})
        }
    }
}
