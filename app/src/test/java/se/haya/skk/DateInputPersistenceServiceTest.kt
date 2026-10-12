package se.haya.skk

import android.text.InputType
import android.text.Selection
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.concurrent.Executor
import se.haya.skk.core.BasicSkkAction
import se.haya.skk.core.DictionaryQuery
import se.haya.skk.core.date.DateExpression
import se.haya.skk.core.date.DateFormat
import se.haya.skk.core.dictionary.SkkDictionaryCodec
import se.haya.skk.core.dictionary.SkkDictionaryCandidate
import se.haya.skk.core.dictionary.SkkDictionaryDocument
import se.haya.skk.core.dictionary.SkkDictionaryEncoding
import se.haya.skk.core.dictionary.SkkDictionaryEntry
import se.haya.skk.dictionary.DictionaryManager
import se.haya.skk.dictionary.PersonalWriteResult
import se.haya.skk.dictionary.CompleteBackupResult
import se.haya.skk.dictionary.CompleteBackupExport
import se.haya.skk.dictionary.ValidatedDictionaryBackup
import se.haya.skk.dictionary.SQLiteDictionaryRepository
import se.haya.skk.input.EditorSession
import se.haya.skk.settings.CustomizationStore
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 30])
class DateInputPersistenceServiceTest {
    private class MutableClock(var current: Instant) : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId): Clock = Clock.fixed(current, zone)
        override fun instant(): Instant = current
    }

    @Test fun `組み込み日付の抑止を完全バックアップで復元して翌日の残る二形式を展開する`() {
        val context = RuntimeEnvironment.getApplication()
        val sourceName = "date-backup-source-${System.nanoTime()}.db"
        val targetName = "date-backup-target-${System.nanoTime()}.db"
        val direct = Executor { it.run() }
        fun manager(name: String, day: String) = DictionaryManager(SQLiteDictionaryRepository(context, name), direct, direct,
            deferReads = false,
            dateClock = Clock.fixed(Instant.parse("${day}T00:00:00Z"), ZoneOffset.UTC),
            dateZoneProvider = { ZoneOffset.UTC }, includeBuiltinDates = true).also { it.loadAsync() }
        val sourceManager = manager(sourceName, "2026-10-10")
        try {
            val iso = sourceManager.lookup(DictionaryQuery("today")).single { it.text == "2026-10-10" }
            assertEquals("builtin-date-formats", iso.selection!!.origins.single().dictionaryId)
            var deleted: PersonalWriteResult? = null
            sourceManager.deleteSelection(iso.selection!!, true) { deleted = it }
            assertEquals(PersonalWriteResult.Applied, deleted)
            var exported: CompleteBackupResult<CompleteBackupExport>? = null
            sourceManager.exportCompleteBackup(context, "date-fixture") { exported = it }
            val bytes = (exported as CompleteBackupResult.Applied).value.use { handle ->
                handle.openInput().use { it.readBytes() }
            }
            ValidatedDictionaryBackup.prepare(context, bytes.inputStream()).use { staged ->
                SQLiteDictionaryRepository(context, targetName).use { target ->
                    target.restoreComplete(staged, target.dictionaryRevision())
                    val dates = target.lookup("today").systems.single()
                    assertEquals("builtin-date-formats", dates.id)
                    assertTrue(dates.generation > 0)
                    assertEquals(3, dates.candidates("today").size)
                    assertTrue(dates.candidates("today").all { it.dateExpression && DateExpression.parse(it.text) != null })
                    assertTrue(target.listCandidateSuppressions().suppressions.single().key.dateExpression)
                }
            }
            manager(targetName, "2026-10-11").use { restored ->
                val candidates = restored.lookup(DictionaryQuery("today"))
                assertEquals(listOf("令和８年１０月１１日(日)", "2026年10月11日(日)"), candidates.map { it.text })
                assertTrue(candidates.all { candidate ->
                    candidate.selection!!.origins.single().let { it.dictionaryId == "builtin-date-formats" && it.generation > 0 }
                })
            }
        } finally {
            sourceManager.close()
            context.deleteDatabase(sourceName)
            context.deleteDatabase(targetName)
        }
    }

    @Test fun `日付形式の全由来を削除すると組み込み候補も再起動後に復活しない`() {
        val context = RuntimeEnvironment.getApplication()
        val name = "date-suppression-${System.nanoTime()}.db"
        val direct = Executor { it.run() }
        fun manager() = DictionaryManager(SQLiteDictionaryRepository(context, name), direct, direct,
            deferReads = false,
            dateClock = Clock.fixed(Instant.parse("2026-10-10T00:00:00Z"), ZoneOffset.UTC),
            dateZoneProvider = { ZoneOffset.UTC }, includeBuiltinDates = true).also { it.loadAsync() }
        SQLiteDictionaryRepository(context, name).use { repository ->
            repository.importSystem("date-system", "日付試験", SkkDictionaryDocument(listOf(SkkDictionaryEntry("today", listOf(
                SkkDictionaryCandidate(DateExpression().canonicalSource(), dateExpression = true),
            ))), SkkDictionaryEncoding.UTF8))
        }
        var dictionaries = manager()
        try {
            val first = dictionaries.lookup(DictionaryQuery("today")).first()
            assertEquals("令和８年１０月１０日(土)", first.text)
            assertEquals(setOf("date-system", "builtin-date-formats"), first.selection!!.origins.map { it.dictionaryId }.toSet())
            var result: PersonalWriteResult? = null
            dictionaries.deleteSelection(first.selection!!, true) { result = it }
            assertEquals(PersonalWriteResult.Applied, result)
            assertEquals(listOf("2026年10月10日(土)", "2026-10-10"), dictionaries.lookup(DictionaryQuery("today")).map { it.text })
            dictionaries.close()
            dictionaries = manager()
            assertEquals(listOf("2026年10月10日(土)", "2026-10-10"), dictionaries.lookup(DictionaryQuery("today")).map { it.text })
        } finally {
            dictionaries.close()
            context.deleteDatabase(name)
        }
    }

    @Test fun `通常とスナップショットの公開経路で日付式の削除由来を保持する`() {
        val context = RuntimeEnvironment.getApplication()
        val direct = Executor { it.run() }
        for (fixture in listOf(false, true)) {
            val name = "date-manager-${System.nanoTime()}.db"
            val repository = SQLiteDictionaryRepository(context, name)
            val source = DateExpression(format = DateFormat.ISO).canonicalSource()
            val literal = SkkDictionaryCandidate(source)
            repository.replacePersonal(SkkDictionaryDocument(listOf(SkkDictionaryEntry("datefixture", listOf(
                literal, literal.copy(dateExpression = true),
            ))), SkkDictionaryEncoding.UTF8), 0)
            val manager = DictionaryManager(repository, direct, direct,
                loadSnapshot = if (fixture) repository::loadSnapshot else null,
                deferReads = false,
                dateClock = Clock.fixed(Instant.parse("2026-10-10T00:00:00Z"), ZoneOffset.UTC),
                dateZoneProvider = { ZoneOffset.UTC }, includeBuiltinDates = true).also { it.loadAsync() }
            try {
                val selected = manager.lookup(DictionaryQuery("datefixture")).single { it.dateExpression }
                assertEquals("2026-10-10", selected.text)
                assertTrue(selected.selection!!.origins.single().dateExpression)
                var result: PersonalWriteResult? = null
                manager.deleteSelection(selected.selection!!, true) { result = it }
                assertEquals(PersonalWriteResult.Applied, result)
                assertEquals(listOf(source), manager.lookup(DictionaryQuery("datefixture")).map { it.text })
            } finally {
                manager.close()
                context.deleteDatabase(name)
            }
        }
    }

    @Test fun `アットの日付形式を学習し再起動と日付変更後も元の式を展開する`() {
        val context = RuntimeEnvironment.getApplication()
        val name = "date-service-${System.nanoTime()}.db"
        val clock = MutableClock(Instant.parse("2026-10-10T00:00:00Z"))
        val direct = Executor { it.run() }
        fun manager() = DictionaryManager(SQLiteDictionaryRepository(context, name), direct, direct,
            deferReads = false, dateClock = clock, dateZoneProvider = { ZoneOffset.UTC }, includeBuiltinDates = true).also { it.loadAsync() }
        var dictionaries = manager()
        val path = File(context.cacheDir, "$name.json")
        val custom = CustomizationStore(path, direct, direct).also { it.loadAsync() }
        val controller = Robolectric.buildService(SkkInputMethodService::class.java).create()
        val service = controller.get()
        ReflectionHelpers.setField(service, "dictionaries", dictionaries)
        ReflectionHelpers.setField(service, "customization", custom)
        try {
            val session = start(service)
            session.handle(BasicSkkAction.Text("@"))
            var found = false
            repeat(3) {
                if (session.view.candidate?.selected?.text == "2026-10-10") found = true
                if (!found) session.handle(BasicSkkAction.ConvertNext)
            }
            assertTrue("ISO形式の日付候補を選択できます", found)
            session.handle(BasicSkkAction.Enter)
            assertNull(session.notice)
            val connection: BaseInputConnection = ReflectionHelpers.getField(service, "mInputConnection")
            assertEquals("2026-10-10", connection.editable.toString())
            service.onFinishInput()
            dictionaries.close()

            SQLiteDictionaryRepository(context, name).use { repository ->
                val candidate = SkkDictionaryCodec.parse(repository.exportPersonal()).entries.single().candidates.single()
                assertTrue(candidate.dateExpression)
                assertEquals(DateExpression(format = DateFormat.ISO).canonicalSource(), candidate.text)
            }
            clock.current = Instant.parse("2026-10-11T00:00:00Z")
            dictionaries = manager()
            assertEquals("2026-10-11", dictionaries.lookup(DictionaryQuery("today")).first().text)
            ReflectionHelpers.setField(service, "dictionaries", dictionaries)
            val restarted = start(service)
            restarted.handle(BasicSkkAction.Text("@"))
            assertEquals("2026-10-11", restarted.view.candidate?.selected?.text)
            assertEquals(3, restarted.view.candidate?.total)
            service.onFinishInput()
        } finally {
            controller.destroy()
            dictionaries.close()
            custom.close()
            context.deleteDatabase(name)
            listOf(path, File(path.path + ".bak"), File(path.path + ".new")).forEach { it.delete() }
        }
    }

    private fun start(service: SkkInputMethodService): EditorSession {
        val connection = object : BaseInputConnection(View(RuntimeEnvironment.getApplication()), true) {
            init { Selection.setSelection(editable, 0) }
        }
        ReflectionHelpers.setField(service, "mInputConnection", connection)
        ReflectionHelpers.setField(service, "mStartedInputConnection", connection)
        service.onStartInput(EditorInfo().apply {
            inputType = InputType.TYPE_CLASS_TEXT
            initialSelStart = 0
            initialSelEnd = 0
        }, false)
        return ReflectionHelpers.getField(service, "session")
    }
}
