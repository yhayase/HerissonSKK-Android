package jp.hayase.skk.dictionary

import android.content.Context
import java.util.UUID
import java.util.concurrent.Executor
import jp.hayase.skk.core.BasicSkkAction
import jp.hayase.skk.core.BasicSkkEffect
import jp.hayase.skk.core.BasicSkkEngine
import jp.hayase.skk.core.DictionaryQuery
import jp.hayase.skk.core.RegistrationPolicy
import jp.hayase.skk.core.RegistrationSaveCompletion
import jp.hayase.skk.core.RegistrationSaveOutcome
import jp.hayase.skk.core.dictionary.DictionaryUnavailableException
import jp.hayase.skk.core.dictionary.DictionaryUnavailableReason
import jp.hayase.skk.core.dictionary.SkkDictionaryCodec
import jp.hayase.skk.core.dictionary.SkkDictionaryDocument
import jp.hayase.skk.core.dictionary.SkkDictionaryCandidate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class DictionaryManagerTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private val databases = mutableListOf<String>()

    @After fun removeDatabases() {
        databases.forEach(context::deleteDatabase)
    }

    @Test fun `学習禁止の入力は保存キューへ入らず既存辞書を変更しない`() {
        val repository = SQLiteDictionaryRepository(context, databaseName())
        repository.replacePersonal(document("かな /既存/"), 0)
        val serial = ManualExecutor()
        val manager = DictionaryManager(repository, serial, Executor { it.run() })
        var result: PersonalWriteResult? = null
        manager.savePersonalCandidate("かな", SkkDictionaryCandidate("保存禁止"), false) { result = it }
        assertEquals(0, serial.size)
        assertEquals(PersonalWriteResult.Failed(PersonalWriteFailure.POLICY_REJECTED), result)
        assertEquals(1L, repository.listSources().first().generation)
        assertEquals(listOf("既存"), repository.lookup("かな").asComposite().lookup(DictionaryQuery("かな")).map { it.text })
        manager.close(); serial.runAll()
    }

    @Test fun `待機中に保存禁止にした要求は再有効化しても実行しない`() {
        val repository = SQLiteDictionaryRepository(context, databaseName())
        val serial = ManualExecutor()
        val manager = DictionaryManager(repository, serial, Executor { it.run() })
        val results = mutableListOf<PersonalWriteResult>()
        manager.savePersonalCandidate("かな", SkkDictionaryCandidate("保存禁止"), true, results::add)
        manager.personalDataPolicy.setAllowed(false)
        manager.personalDataPolicy.setAllowed(true)
        serial.runAll()
        assertEquals(listOf(PersonalWriteResult.Failed(PersonalWriteFailure.POLICY_REJECTED)), results)
        assertEquals(0L, repository.listSources().first().generation)
        assertTrue(repository.lookup("かな").asComposite().lookup(DictionaryQuery("かな")).isEmpty())
        manager.close(); serial.runAll()
    }

    @Test fun `連続登録を最新辞書へ重ね公開失敗は保存済みとして区別する`() {
        val repository = SQLiteDictionaryRepository(context, databaseName())
        val serial = ManualExecutor()
        var failReload = false
        val manager = DictionaryManager(repository, serial, Executor { it.run() },
            loadSnapshot = { if (failReload) error("試験用の公開失敗") else repository.loadSnapshot() })
        val results = mutableListOf<PersonalWriteResult>()
        manager.savePersonalCandidate("かな", SkkDictionaryCandidate("一番"), true, results::add)
        manager.savePersonalCandidate("かな", SkkDictionaryCandidate("二番"), true, results::add)
        serial.runAll()
        assertEquals(listOf(PersonalWriteResult.Applied, PersonalWriteResult.Applied), results)
        assertEquals(listOf("二番", "一番"), manager.lookup(DictionaryQuery("かな")).map { it.text })
        failReload = true
        manager.savePersonalCandidate("かな", SkkDictionaryCandidate("三番"), true, results::add)
        serial.runAll()
        assertEquals(PersonalWriteResult.SavedButNotApplied, results.last())
        assertEquals(listOf("二番", "一番"), manager.lookup(DictionaryQuery("かな")).map { it.text })
        assertEquals(listOf("三番", "二番", "一番"), repository.lookup("かな").asComposite().lookup(DictionaryQuery("かな")).map { it.text })
        assertEquals(3L, repository.listSources().first().generation)
        manager.close(); serial.runAll()
    }

    @Test fun `起動中の検索は空辞書にせずI O完了後だけ公開する`() {
        val repository = SQLiteDictionaryRepository(context, databaseName())
        repository.replacePersonal(document("かな /公開候補/"), 0)
        val serial = ManualExecutor()
        var loads = 0
        val manager = DictionaryManager(
            repository, serial, Executor { it.run() },
            loadSnapshot = { loads++; repository.loadSnapshot() },
        )

        val unavailable = assertThrows(DictionaryUnavailableException::class.java) {
            manager.lookup(DictionaryQuery("かな"))
        }
        assertEquals(DictionaryUnavailableReason.INITIALIZING, unavailable.reason)
        assertEquals(0, loads)

        manager.loadAsync()
        assertEquals(0, loads)
        serial.runNext()

        assertEquals(1, loads)
        assertEquals(listOf("公開候補"), manager.lookup(DictionaryQuery("かな")).map { it.text })
        manager.close(); serial.runAll()
    }

    @Test fun `公開済みスナップショットの数値キーを同じ世代で展開する`() {
        val repository = SQLiteDictionaryRepository(context, databaseName())
        repository.replacePersonal(document("だい# /第#3/"), 0)
        val serial = ManualExecutor()
        val manager = DictionaryManager(repository, serial, Executor { it.run() })
        manager.loadAsync(); serial.runNext()
        assertEquals(listOf("第十二"), manager.lookup(DictionaryQuery("だい12")).map { it.text })
        manager.close(); serial.runAll()
    }

    @Test fun `数値登録の正規化と四番展開を一つの公開済み世代へ委譲する`() {
        val repository = SQLiteDictionaryRepository(context, databaseName())
        repository.replacePersonal(document("だい# /地域:#4/\n314 /北#3区/\nかな /通常/"), 0)
        val serial = ManualExecutor()
        val manager = DictionaryManager(repository, serial, Executor { it.run() })
        manager.loadAsync(); serial.runNext()

        val original = DictionaryQuery("だい314", okuri = "る", abbrev = true)
        assertEquals(DictionaryQuery("だい#", "る", true), manager.registrationQuery(original))
        assertEquals("地域:北#3区", manager.prepareRegistration(original, "地域:#4").committedStem)
        assertEquals(DictionaryQuery("かな"), manager.registrationQuery(DictionaryQuery("かな")))
        assertEquals("字面", manager.prepareRegistration(DictionaryQuery("かな"), "字面").committedStem)

        repository.replacePersonal(document("だい# /地域:#4/\n314 /中央区/"), 1)
        assertEquals("地域:北#3区", manager.prepareRegistration(original, "地域:#4").committedStem)
        manager.close(); serial.runAll()
    }

    @Test fun `数値登録は保存要求時の公開世代で展開し以後の再読込では再計算しない`() {
        val repository = SQLiteDictionaryRepository(context, databaseName())
        repository.replacePersonal(document("314 /旧/"), 0)
        val serial = ManualExecutor()
        val manager = DictionaryManager(repository, serial, Executor { it.run() })
        manager.loadAsync(); serial.runNext()
        val engine = BasicSkkEngine(manager, RegistrationPolicy(enabled = true))
        engine.dispatch(BasicSkkAction.Text("Dai314 "))
        engine.dispatch(BasicSkkAction.Text("地域:#4"))

        repository.replacePersonal(document("314 /要求時/"), 1)
        manager.loadAsync(); serial.runNext()
        val saving = engine.dispatch(BasicSkkAction.Enter)
        val request = (saving.effects.single() as BasicSkkEffect.SaveRegistration).request
        assertEquals("地域:要求時", request.committedText)

        repository.replacePersonal(document("314 /完了時/"), 2)
        manager.loadAsync(); serial.runNext()
        val completed = engine.completeRegistration(
            RegistrationSaveCompletion(request.token, RegistrationSaveOutcome.Applied),
        )
        assertEquals("地域:要求時", completed.commit)
        manager.close(); serial.runAll()
    }

    @Test fun `保留中の再読込は古いスナップショットを検索可能なまま保つ`() {
        val repository = SQLiteDictionaryRepository(context, databaseName())
        repository.replacePersonal(document("かな /旧/"), 0)
        val serial = ManualExecutor()
        val manager = DictionaryManager(repository, serial, Executor { it.run() })
        manager.loadAsync(); serial.runNext()

        manager.loadAsync()

        assertEquals(DictionaryManagerStatus.Ready(DictionaryFreshness.REFRESHING), manager.status)
        assertEquals(listOf("旧"), manager.lookup(DictionaryQuery("かな")).map { it.text })
        repository.replacePersonal(document("かな /新/"), 1)
        serial.runNext()
        assertEquals(listOf("新"), manager.lookup(DictionaryQuery("かな")).map { it.text })
        manager.close(); serial.runAll()
    }

    @Test fun `初回読込失敗は空結果にせず失敗理由を公開する`() {
        val repository = SQLiteDictionaryRepository(context, databaseName())
        val serial = ManualExecutor()
        val manager = DictionaryManager(
            repository, serial, Executor { it.run() },
            loadSnapshot = { error("読込不能") },
        )

        manager.loadAsync(); serial.runNext()

        assertEquals(DictionaryManagerStatus.Unavailable(DictionaryUnavailableReason.FAILED), manager.status)
        val unavailable = assertThrows(DictionaryUnavailableException::class.java) {
            manager.lookup(DictionaryQuery("かな"))
        }
        assertEquals(DictionaryUnavailableReason.FAILED, unavailable.reason)
        manager.close(); serial.runAll()
    }

    @Test fun `保存成功後の再読込失敗は旧公開世代を残して別結果で通知する`() {
        val repository = SQLiteDictionaryRepository(context, databaseName())
        repository.replacePersonal(document("かな /旧/"), 0)
        val serial = ManualExecutor()
        val callbacks = ManualExecutor()
        var loadCount = 0
        val manager = DictionaryManager(
            repository, serial, callbacks,
            loadSnapshot = {
                loadCount++
                if (loadCount == 1) repository.loadSnapshot() else error("再読込失敗")
            },
        )
        manager.loadAsync(); serial.runNext()
        assertEquals(listOf("旧"), manager.lookup(DictionaryQuery("かな")).map { it.text })

        var result: DictionaryManagerWriteResult<DictionarySourceInfo>? = null
        manager.replacePersonal(document("かな /保存済み/"), 1) { result = it }
        serial.runNext()

        assertTrue(result == null)
        assertEquals(DictionaryManagerStatus.Ready(DictionaryFreshness.STALE), manager.status)
        assertEquals(listOf("旧"), manager.lookup(DictionaryQuery("かな")).map { it.text })
        assertEquals(listOf("保存済み"), repository.lookup("かな").asComposite()
            .lookup(DictionaryQuery("かな")).map { it.text })
        callbacks.runNext()
        assertTrue(result is DictionaryManagerWriteResult.SavedButNotApplied)
        manager.close(); serial.runAll()
    }

    @Test fun `書込失敗は公開済み世代と保存済み内容を変えずcallback executorで通知する`() {
        val repository = SQLiteDictionaryRepository(context, databaseName())
        repository.replacePersonal(document("かな /旧/"), 0)
        val serial = ManualExecutor()
        val callbacks = ManualExecutor()
        val manager = DictionaryManager(repository, serial, callbacks)
        manager.loadAsync(); serial.runNext()

        var result: DictionaryManagerWriteResult<DictionarySourceInfo>? = null
        manager.replacePersonal(document("かな /失敗/"), 0) { result = it }
        serial.runNext()

        assertTrue(result == null)
        assertEquals(listOf("旧"), manager.lookup(DictionaryQuery("かな")).map { it.text })
        assertEquals(listOf("旧"), repository.lookup("かな").asComposite()
            .lookup(DictionaryQuery("かな")).map { it.text })
        callbacks.runNext()
        assertEquals(DictionaryManagerWriteResult.Failed, result)
        manager.close(); serial.runAll()
    }

    @Test fun `システム辞書削除はバックグラウンド保存後に新しい検索世代を公開する`() {
        val repository = SQLiteDictionaryRepository(context, databaseName())
        repository.importSystem("system", "辞書", document("かな /削除候補/"))
        val serial = ManualExecutor()
        val callbacks = ManualExecutor()
        val manager = DictionaryManager(repository, serial, callbacks)
        manager.loadAsync(); serial.runNext(); callbacks.runAll()
        assertEquals(listOf("削除候補"), manager.lookup(DictionaryQuery("かな")).map { it.text })
        var result: DictionaryManagerWriteResult<DictionarySourceInfo>? = null

        manager.removeSystem("system", 1) { result = it }

        assertEquals(listOf("削除候補"), manager.lookup(DictionaryQuery("かな")).map { it.text })
        serial.runNext()
        assertTrue(manager.lookup(DictionaryQuery("かな")).isEmpty())
        assertTrue(result == null)
        callbacks.runNext()
        assertTrue(result is DictionaryManagerWriteResult.Applied)
        assertEquals("system", (result as DictionaryManagerWriteResult.Applied).value.id)
        manager.close(); serial.runAll()
    }

    @Test fun `状態購読はcallback executorで行い解除後は通知しない`() {
        val repository = SQLiteDictionaryRepository(context, databaseName())
        val serial = ManualExecutor()
        val callbacks = ManualExecutor()
        val manager = DictionaryManager(repository, serial, callbacks)
        val states = mutableListOf<DictionaryManagerStatus>()
        val subscription = manager.observe(states::add)

        assertTrue(states.isEmpty())
        callbacks.runNext()
        assertEquals(listOf(DictionaryManagerStatus.Loading), states)
        subscription.close()
        manager.loadAsync(); serial.runNext()
        callbacks.runAll()

        assertEquals(listOf(DictionaryManagerStatus.Loading), states)
        manager.close(); serial.runAll()
    }

    @Test fun `close前に保留した読込と通知は公開も配送もせず検索を無効化する`() {
        val repository = SQLiteDictionaryRepository(context, databaseName())
        repository.replacePersonal(document("かな /公開してはいけない/"), 0)
        val serial = ManualExecutor()
        val callbacks = ManualExecutor()
        val manager = DictionaryManager(repository, serial, callbacks)
        val states = mutableListOf<DictionaryManagerStatus>()
        manager.observe(states::add)
        manager.loadAsync()

        manager.close()
        assertThrows(IllegalStateException::class.java) { manager.observe(states::add) }
        serial.runAll()
        callbacks.runAll()

        assertTrue(states.isEmpty())
        assertEquals(DictionaryManagerStatus.Unavailable(DictionaryUnavailableReason.FAILED), manager.status)
        assertThrows(DictionaryUnavailableException::class.java) {
            manager.lookup(DictionaryQuery("かな"))
        }
    }

    @Test fun `辞書順の要求値はキュー投入時に固定し一覧も非同期で返す`() {
        val repository = SQLiteDictionaryRepository(context, databaseName())
        repository.importSystem("a", "A", document("かな /A/"))
        repository.importSystem("b", "B", document("かな /B/"))
        val serial = ManualExecutor()
        val callbacks = ManualExecutor()
        val manager = DictionaryManager(repository, serial, callbacks)
        val requested = mutableListOf("b", "a")
        manager.setSystemOrder(requested) { }
        requested.reverse()
        var sources: DictionaryManagerWriteResult<List<DictionarySourceInfo>>? = null
        manager.listSources { sources = it }

        serial.runNext()
        serial.runNext()
        assertTrue(sources == null)
        callbacks.runAll()

        val listed = (sources as DictionaryManagerWriteResult.Applied).value
        assertEquals(listOf("personal", "b", "a"), listed.map { it.id })
        manager.close(); serial.runAll()
    }

    private fun document(text: String): SkkDictionaryDocument = SkkDictionaryCodec.parseText(text)

    private fun databaseName(): String = "dictionary-manager-${UUID.randomUUID()}.db".also(databases::add)

    private class ManualExecutor : Executor {
        private val tasks = ArrayDeque<Runnable>()
        val size: Int get() = tasks.size
        override fun execute(command: Runnable) { tasks.addLast(command) }
        fun runNext() { tasks.removeFirst().run() }
        fun runAll() { while (tasks.isNotEmpty()) runNext() }
    }
}
