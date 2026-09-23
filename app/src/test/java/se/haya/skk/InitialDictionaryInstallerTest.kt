package se.haya.skk

import android.content.Intent
import android.widget.Button
import androidx.preference.CheckBoxPreference
import androidx.preference.ListPreference
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import se.haya.skk.core.DictionaryQuery
import se.haya.skk.core.dictionary.SkkDictionaryCodec
import se.haya.skk.dictionary.DictionaryManager
import se.haya.skk.dictionary.SQLiteDictionaryRepository
import java.util.concurrent.Executor

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 35])
class InitialDictionaryInstallerTest {
    private val work = ArrayDeque<Runnable>()
    private val direct = Executor { it.run() }
    private lateinit var manager: DictionaryManager
    private lateinit var repository: SQLiteDictionaryRepository
    private lateinit var database: android.database.sqlite.SQLiteDatabase
    private lateinit var installer: InitialDictionaryInstaller
    private var failPublication = false
    private var downloads = 0
    private var current = InitialDictionaryInstaller.State()

    @Before fun prepare() {
        repository = SQLiteDictionaryRepository(
            RuntimeEnvironment.getApplication(), "setup-${System.nanoTime()}.db",
            se.haya.skk.dictionary.DictionaryWriteFailpoint { },
            se.haya.skk.dictionary.DictionaryDatabaseConfigurator { database = it },
        )
        manager = DictionaryManager(repository, direct, direct, loadSnapshot = {
            if (failPublication) error("公開失敗") else repository.loadSnapshot()
        }, deferReads = false)
        manager.loadAsync()
        installer = InitialDictionaryInstaller(manager, Executor { work += it }, direct) {
            downloads++
            SkkDictionaryCodec.parseText("かな /仮名/")
        }
        installer.observe { current = it }
        SetupActivity.installerFactoryForTest = { installer }
    }
    @After fun cleanup() {
        SetupActivity.installerFactoryForTest = null
        manager.close()
    }

    @Test fun `S辞書は初回に自動準備し同じ進行中要求を重ねない`() {
        installer.ensureBasic()
        installer.ensureBasic()
        installer.install(setOf("S"))
        assertEquals(1, work.size)
        work.removeFirst().run()
        assertEquals(1, downloads)
        assertEquals(setOf("S"), current.installed)
        assertFalse(current.busy)
        assertEquals(listOf("仮名"), manager.lookup(DictionaryQuery("かな")).map { it.text })
        assertEquals(1, repository.listSources().count { it.id == "official-skk-S" })
    }

    @Test fun `保存済みでも公開失敗なら利用可能にせず再試行で公開する`() {
        installer.ensureBasic()
        failPublication = true
        work.removeFirst().run()
        assertTrue(current.installed.isEmpty())
        assertNotNull(current.error)
        assertEquals(1, repository.listSources().count { it.id == "official-skk-S" })
        installer.install(setOf("S"))
        assertTrue(current.installed.isEmpty())
        assertNotNull(current.error)
        failPublication = false
        installer.install(setOf("S"))
        assertEquals(setOf("S"), current.installed)
        assertNull(current.error)
        assertEquals(1, downloads)
        assertEquals(listOf("仮名"), manager.lookup(DictionaryQuery("かな")).map { it.text })
    }

    @Test fun `プロセス再生成後も完了対象の追加辞書を再開する`() {
        val first = Robolectric.buildActivity(SetupActivity::class.java).setup()
        work.removeFirst().run()
        first.get().selected.clear()
        first.get().selected += "L"
        first.get().completeSetup()
        val saved = android.os.Bundle()
        first.pause().saveInstanceState(saved).stop().destroy()
        // プロセス終了で失われた取得処理とコーディネーターを再現します。
        work.clear()
        installer = InitialDictionaryInstaller(manager, Executor { work += it }, direct) {
            downloads++
            SkkDictionaryCodec.parseText("かな /仮名/")
        }
        installer.observe { current = it }
        val restored = Robolectric.buildActivity(SetupActivity::class.java).setup(saved)
        assertTrue(restored.get().completing)
        assertEquals(1, work.size)
        work.removeFirst().run()
        assertEquals(setOf("L"), current.installed)
        assertTrue(restored.get().isFinishing)
        restored.pause().stop().destroy()
    }

    @Test fun `初期設定の完了処理中は追加辞書の選択を変えない`() {
        val controller = Robolectric.buildActivity(SetupActivity::class.java,
            Intent(RuntimeEnvironment.getApplication(), SetupActivity::class.java)).setup()
        work.removeFirst().run()
        val page = controller.get()
        val setup = page.supportFragmentManager.findFragmentById(R.id.settings_content) as SetupFragment
        val large = setup.findPreference<ListPreference>("setup_general_dictionary")!!
        assertTrue(large.callChangeListener("L"))
        assertEquals(setOf("L"), page.selected)
        page.completeSetup()
        val name = setup.findPreference<CheckBoxPreference>("jinmei")!!
        assertFalse(name.isEnabled)
        work.removeFirst().run()
        assertEquals(setOf("L"), current.installed)
        assertTrue(page.isFinishing)
        controller.pause().stop().destroy()
    }

    @Test fun `初期設定は一般辞書を単一選択で追加辞書をチェックボックスで選ぶ`() {
        val controller = Robolectric.buildActivity(SetupActivity::class.java).setup()
        try {
            val setup = controller.get().supportFragmentManager
                .findFragmentById(R.id.settings_content) as SetupFragment
            val general = setup.findPreference<ListPreference>("setup_general_dictionary")!!
            assertEquals("S", general.value)
            assertNotNull(setup.findPreference<CheckBoxPreference>("jinmei"))
            assertEquals("初期設定を完了", controller.get().findViewById<Button>(R.id.settings_save).text)

            assertTrue(general.callChangeListener("L"))
            assertEquals(setOf("L"), controller.get().selected)
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun `初期設定から外部辞書の通知を開ける`() {
        val controller = Robolectric.buildActivity(SetupActivity::class.java).setup()
        try {
            val setup = controller.get().supportFragmentManager
                .findFragmentById(R.id.settings_content) as SetupFragment

            setup.findPreference<androidx.preference.Preference>("setup_external_dictionary_notice")!!.performClick()

            val next = org.robolectric.Shadows.shadowOf(controller.get()).nextStartedActivity
            assertEquals(LicensesActivity::class.java.name, next.component?.className)
            assertEquals("external-dictionaries", next.getStringExtra(LicensesActivity.EXTRA_ITEM))
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun `追加辞書の準備に失敗したら選択を変更できる`() {
        val controller = Robolectric.buildActivity(SetupActivity::class.java).setup()
        work.removeFirst().run()
        val page = controller.get()
        page.selected.clear()
        page.selected += "L"
        page.completeSetup()
        failPublication = true
        work.removeFirst().run()
        assertFalse(page.completing)
        assertFalse(page.isFinishing)
        val setup = page.supportFragmentManager.findFragmentById(R.id.settings_content) as SetupFragment
        val name = setup.findPreference<CheckBoxPreference>("jinmei")!!
        assertTrue(name.isEnabled)
        assertTrue(name.callChangeListener(true))
        assertTrue("jinmei" in page.selected)
        controller.pause().stop().destroy()
    }

    @Test fun `Lを選ぶとSを除外してカタログ順に準備する`() {
        assertEquals(
            listOf("L", "jinmei", "geo"),
            normalizeInitialDictionaryKeys(linkedSetOf("geo", "S", "jinmei", "L")),
        )
    }

    @Test fun `保存済みSはLの保存成功と同じ変更で置き換える`() {
        installer.ensureBasic()
        work.removeFirst().run()
        assertEquals(listOf("official-skk-S"), repository.listSources().filter { it.id != "personal" }.map { it.id })

        installer.install(setOf("L", "S"))
        work.removeFirst().run()

        assertEquals(listOf("official-skk-L"), repository.listSources().filter { it.id != "personal" }.map { it.id })
        assertEquals(setOf("L"), current.installed)
    }

    @Test fun `保存済みLで初期設定を開き直してもSを取得せずLを選択する`() {
        repository.importSystem(
            "official-skk-L",
            "SKK-JISYO.L",
            SkkDictionaryCodec.parseText("かな /仮名/"),
            originUrl = requireNotNull(se.haya.skk.dictionary.network.NetworkDictionaryCatalog.find("L")).url,
        )

        val controller = Robolectric.buildActivity(SetupActivity::class.java).setup()
        try {
            val setup = controller.get().supportFragmentManager
                .findFragmentById(R.id.settings_content) as SetupFragment
            assertEquals("L", setup.findPreference<ListPreference>("setup_general_dictionary")!!.value)
            assertTrue(work.isEmpty())
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun `LからSへの明示的な切替でも一般辞書を重複させない`() {
        repository.importSystem(
            "official-skk-L",
            "SKK-JISYO.L",
            SkkDictionaryCodec.parseText("かな /大/"),
            originUrl = requireNotNull(se.haya.skk.dictionary.network.NetworkDictionaryCatalog.find("L")).url,
        )
        installer.install(setOf("S"))
        work.removeFirst().run()

        assertEquals(listOf("official-skk-S"), repository.listSources().filter { it.id != "personal" }.map { it.id })
        assertEquals(setOf("S"), current.installed)
    }

    @Test fun `同じプロセスで削除したSは再表示後の完了時に取得し直す`() {
        val first = Robolectric.buildActivity(SetupActivity::class.java).setup()
        work.removeFirst().run()
        first.pause().stop().destroy()
        val sources = repository.listSources()
        val draft = manager.createSettingsDraft(sources)
        draft.stageRemove(sources.single { it.id == "official-skk-S" })
        manager.applySettingsDraft(draft.id) { assertTrue(it is se.haya.skk.dictionary.DictionaryManagerWriteResult.Applied) }

        val reopened = Robolectric.buildActivity(SetupActivity::class.java).setup()
        try {
            assertTrue(current.installed.isEmpty())
            reopened.get().completeSetup()
            assertFalse(reopened.get().isFinishing)
            assertEquals(1, work.size)
            work.removeFirst().run()
            assertTrue(reopened.get().isFinishing)
            assertEquals(2, downloads)
            assertTrue(repository.listSources().any { it.id == "official-skk-S" })
        } finally { reopened.pause().stop().destroy() }
    }

    @Test fun `完了直前に削除したSもキャッシュで取得を省略しない`() {
        val controller = Robolectric.buildActivity(SetupActivity::class.java).setup()
        try {
            work.removeFirst().run()
            val sources = repository.listSources()
            val draft = manager.createSettingsDraft(sources)
            draft.stageRemove(sources.single { it.id == "official-skk-S" })
            manager.applySettingsDraft(draft.id) { assertTrue(it is se.haya.skk.dictionary.DictionaryManagerWriteResult.Applied) }
            controller.get().completeSetup()
            assertFalse(controller.get().isFinishing)
            assertEquals(1, work.size)
            work.removeFirst().run()
            assertTrue(controller.get().isFinishing)
            assertEquals(2, downloads)
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun `キャッシュにSがあっても再表示では保存済みLを選択する`() {
        installer.ensureBasic()
        work.removeFirst().run()
        val sources = repository.listSources()
        val draft = manager.createSettingsDraft(sources)
        draft.stageRemove(sources.single { it.id == "official-skk-S" })
        draft.stageImport("official-skk-L", "SKK-JISYO.L", SkkDictionaryCodec.parseText("かな /仮名/"), null, null)
        manager.applySettingsDraft(draft.id) { assertTrue(it is se.haya.skk.dictionary.DictionaryManagerWriteResult.Applied) }
        val controller = Robolectric.buildActivity(SetupActivity::class.java).setup()
        try {
            assertEquals(setOf("L"), controller.get().selected)
            assertEquals(setOf("L"), current.installed)
            assertTrue(work.isEmpty())
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun `一覧読込中の一般辞書の選択は保存済み状態で上書きしない`() {
        val callbacks = ArrayDeque<Runnable>()
        val delayedManager = DictionaryManager(repository, direct, Executor { callbacks += it }, deferReads = false)
        SetupActivity.installerFactoryForTest = {
            InitialDictionaryInstaller(delayedManager, Executor { work += it }, direct) {
                SkkDictionaryCodec.parseText("かな /仮名/")
            }
        }
        val controller = Robolectric.buildActivity(SetupActivity::class.java).setup()
        try {
            val setup = controller.get().supportFragmentManager
                .findFragmentById(R.id.settings_content) as SetupFragment
            assertTrue(setup.findPreference<ListPreference>("setup_general_dictionary")!!.callChangeListener("L"))
            assertEquals(setOf("L"), controller.get().selected)
            controller.get().completeSetup()
            assertFalse(controller.get().completing)
            assertFalse(controller.get().isFinishing)
            while (callbacks.isNotEmpty()) callbacks.removeFirst().run()
            assertEquals(setOf("L"), controller.get().selected)
            controller.get().completeSetup()
            while (callbacks.isNotEmpty() || work.isNotEmpty()) {
                while (callbacks.isNotEmpty()) callbacks.removeFirst().run()
                if (work.isNotEmpty()) work.removeFirst().run()
            }
            assertTrue(controller.get().isFinishing)
            assertEquals(listOf("official-skk-L"), repository.listSources().filter { it.id != "personal" }.map { it.id })
        } finally {
            controller.pause().stop().destroy()
            delayedManager.close()
        }
    }

    @Test fun `初回一覧の応答より先にLを要求しても後から自動Sを追加しない`() {
        val callbacks = ArrayDeque<Runnable>()
        val delayedManager = DictionaryManager(repository, direct, Executor { callbacks += it }, deferReads = false)
        val requested = mutableListOf<String>()
        val delayedInstaller = InitialDictionaryInstaller(delayedManager, Executor { work += it }, direct) { url ->
            requested += url
            SkkDictionaryCodec.parseText("かな /仮名/")
        }
        try {
            delayedInstaller.ensureBasic()
            delayedInstaller.install(setOf("L"))
            while (callbacks.isNotEmpty() || work.isNotEmpty()) {
                while (callbacks.isNotEmpty()) callbacks.removeFirst().run()
                if (work.isNotEmpty()) work.removeFirst().run()
            }
            assertEquals(listOf(requireNotNull(se.haya.skk.dictionary.network.NetworkDictionaryCatalog.find("L")).url), requested)
            assertEquals(listOf("official-skk-L"), repository.listSources().filter { it.id != "personal" }.map { it.id })
        } finally { delayedManager.close() }
    }

    @Test fun `一覧取得に失敗したら完了できず再試行で保存済みLを保持する`() {
        repository.importSystem("official-skk-L", "SKK-JISYO.L", SkkDictionaryCodec.parseText("かな /仮名/"))
        database.execSQL("ALTER TABLE dictionary_sources RENAME TO unavailable_sources")
        val controller = Robolectric.buildActivity(SetupActivity::class.java).setup()
        try {
            assertFalse(current.loaded)
            assertNotNull(current.error)
            val done = controller.get().findViewById<Button>(R.id.settings_save)
            assertFalse(done.isEnabled)
            controller.get().completeSetup()
            assertFalse(controller.get().completing)
            assertFalse(controller.get().isFinishing)
            assertTrue(work.isEmpty())
            database.execSQL("ALTER TABLE unavailable_sources RENAME TO dictionary_sources")
            controller.get().retry()
            assertTrue(current.loaded)
            assertNull(current.error)
            assertEquals(setOf("L"), controller.get().selected)
            assertTrue(done.isEnabled)
            assertTrue(work.isEmpty())
            assertEquals(listOf("official-skk-L"), repository.listSources().filter { it.id != "personal" }.map { it.id })
            controller.get().completeSetup()
            assertTrue(controller.get().isFinishing)
            assertEquals(0, downloads)
        } finally { controller.pause().stop().destroy() }
    }
}
