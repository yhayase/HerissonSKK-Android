package se.haya.skk

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import se.haya.skk.core.dictionary.SkkDictionaryCodec
import se.haya.skk.dictionary.DictionaryManager
import se.haya.skk.dictionary.DictionaryManagerStatus
import se.haya.skk.dictionary.DictionarySettingsDraft
import se.haya.skk.dictionary.DictionarySourceKind
import se.haya.skk.dictionary.SQLiteDictionaryRepository

/** 専用DBで実際のドラッグ、確認、保存を検証し、利用中の辞書には触れません。 */
@RunWith(AndroidJUnit4::class)
class DictionarySettingsUiTest {
    @Test fun multirowDragKeepsPreferenceAdapterAndDraftOrderInSync() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val device = UiDevice.getInstance(instrumentation)
        val database = "dictionary-ui-multirow-${UUID.randomUUID()}.db"
        val repository = SQLiteDictionaryRepository(context, database)
        val executor = Executors.newSingleThreadExecutor()
        val handler = Handler(Looper.getMainLooper())
        val manager = DictionaryManager(
            repository,
            executor,
            java.util.concurrent.Executor { handler.post(it) },
            ownedExecutor = executor,
        )
        var scenario: ActivityScenario<DictionarySettingsActivity>? = null
        try {
            repository.importSystem("first", "試験辞書A", SkkDictionaryCodec.parseText("かな /甲/\n"))
            repository.importSystem("second", "試験辞書B", SkkDictionaryCodec.parseText("かな /乙/\n"))
            repository.importSystem("third", "試験辞書C", SkkDictionaryCodec.parseText("かな /丙/\n"))
            val original = repository.listSources()
                .filter { it.kind == DictionarySourceKind.SYSTEM }
                .sortedBy { it.order }
                .map { it.id }
            assertEquals(3, original.size)
            val names = mapOf("first" to "試験辞書A", "second" to "試験辞書B", "third" to "試験辞書C")
            val loaded = CountDownLatch(1)
            manager.loadAsync { assertTrue(it is DictionaryManagerStatus.Ready); loaded.countDown() }
            assertTrue(loaded.await(15, TimeUnit.SECONDS))
            DictionarySettingsActivity.managerFactoryForTest = { manager }
            scenario = ActivityScenario.launch(DictionarySettingsActivity::class.java)
            val active = scenario

            fun control(id: String) = requireNotNull(device.wait(Until.findObject(
                By.desc("${names.getValue(id)}の優先順位を変更"),
            ), 5000))
            fun awaitVisibleAndDraftOrder(expected: List<String>) {
                val deadline = SystemClock.uptimeMillis() + 5000
                var visible = emptyList<String>()
                var draft = emptyList<String>()
                do {
                    active.onActivity { activity ->
                        visible = activity.visibleSources().map { it.id }
                        val field = DictionarySettingsActivity::class.java.getDeclaredField("draft").apply {
                            isAccessible = true
                        }
                        draft = (field.get(activity) as DictionarySettingsDraft).sources
                            .filter { it.kind == DictionarySourceKind.SYSTEM }
                            .sortedBy { it.order }
                            .map { it.id }
                    }
                    if (visible == expected && draft == expected) break
                    SystemClock.sleep(50)
                } while (SystemClock.uptimeMillis() < deadline)
                assertEquals("画面用の並び順", expected, visible)
                assertEquals("保存前の下書きの並び順", expected, draft)
                device.waitForIdle()
                assertEquals(
                    "Preference 行の表示順",
                    expected,
                    expected.sortedBy { control(it).visibleCenter.y },
                )
            }

            awaitVisibleAndDraftOrder(original)
            val first = control(original[0]).visibleCenter
            val third = control(original[2]).visibleCenter
            assertTrue(device.drag(first.x, first.y, third.x, third.y + 12, 40))
            val movedToBottom = listOf(original[1], original[2], original[0])
            awaitVisibleAndDraftOrder(movedToBottom)

            val bottom = control(original[0]).visibleCenter
            val top = control(original[1]).visibleCenter
            assertTrue(device.drag(bottom.x, bottom.y, top.x, top.y - 12, 40))
            awaitVisibleAndDraftOrder(original)

            assertEquals(
                "保存前に永続化済みの順序を変更しません",
                original,
                repository.listSources().filter { it.kind == DictionarySourceKind.SYSTEM }
                    .sortedBy { it.order }.map { it.id },
            )
        } finally {
            scenario?.close()
            DictionarySettingsActivity.managerFactoryForTest = null
            manager.close()
            context.deleteDatabase(database)
        }
    }

    @Test fun dragRotationAccessibleMoveDeleteAndAddKeepDraftContract() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val device = UiDevice.getInstance(instrumentation)
        val database = "dictionary-ui-${UUID.randomUUID()}.db"
        val repository = SQLiteDictionaryRepository(context, database)
        val executor = Executors.newSingleThreadExecutor()
        val handler = Handler(Looper.getMainLooper())
        val manager = DictionaryManager(repository, executor, java.util.concurrent.Executor { handler.post(it) }, ownedExecutor = executor)
        var scenario: ActivityScenario<DictionarySettingsActivity>? = null
        try {
            repository.importSystem("first", "試験辞書A", SkkDictionaryCodec.parseText("かな /甲/\n"))
            repository.importSystem("second", "試験辞書B", SkkDictionaryCodec.parseText("かな /乙/\n"))
            val original = repository.listSources().filter { it.kind == DictionarySourceKind.SYSTEM }.map { it.id }
            val loaded = CountDownLatch(1)
            manager.loadAsync { assertTrue(it is DictionaryManagerStatus.Ready); loaded.countDown() }
            assertTrue(loaded.await(15, TimeUnit.SECONDS))
            DictionarySettingsActivity.managerFactoryForTest = { manager }
            scenario = ActivityScenario.launch(DictionarySettingsActivity::class.java)
            val active = scenario
            fun awaitOrder(order: List<String>) {
                val deadline = SystemClock.uptimeMillis() + 5000
                var actual = emptyList<String>()
                do {
                    active.onActivity { actual = it.visibleSources().map { source -> source.id } }
                    if (actual == order) return
                    SystemClock.sleep(50)
                } while (SystemClock.uptimeMillis() < deadline)
                assertEquals(order, actual)
            }
            fun control(description: String) = requireNotNull(device.wait(Until.findObject(By.desc(description)), 5000))
            fun text(value: String) = requireNotNull(device.wait(Until.findObject(By.text(value)), 5000))
            awaitOrder(original)
            val topName = if (original.first() == "first") "試験辞書A" else "試験辞書B"
            val bottomName = if (original.last() == "first") "試験辞書A" else "試験辞書B"
            val top = control("${topName}の優先順位を変更").visibleCenter
            val bottom = control("${bottomName}の優先順位を変更").visibleCenter
            assertTrue(device.drag(top.x, top.y, bottom.x, bottom.y + 12, 30))
            awaitOrder(original.reversed())
            assertEquals(original, repository.listSources().filter { it.kind == DictionarySourceKind.SYSTEM }.map { it.id })
            active.recreate()
            awaitOrder(original.reversed())
            // ACTION_CLICKを使い、タッチドラッグ以外の並べ替え経路も検査します。
            active.onActivity { activity ->
                fun click(view: android.view.View): Boolean {
                    if (view.contentDescription == "${bottomName}の優先順位を変更") return view.performClick()
                    if (view is android.view.ViewGroup) for (i in 0 until view.childCount) if (click(view.getChildAt(i))) return true
                    return false
                }
                assertTrue(click(activity.window.decorView))
            }
            text("下へ移動").click()
            awaitOrder(original)
            device.waitForIdle()
            assertTrue("移動メニューの結果が表示順に反映されません",
                control("${topName}の優先順位を変更").visibleCenter.y <
                    control("${bottomName}の優先順位を変更").visibleCenter.y)
            assertTrue(device.takeScreenshot(java.io.File(context.getExternalFilesDir(null), "dictionary-settings-ui.png")))
            control("${topName}を削除").click()
            requireNotNull(device.wait(Until.findObject(By.res("android:id/button2")), 5000)).click()
            awaitOrder(original)
            control("辞書を追加").click()
            text("SKK-JISYO.L")
            requireNotNull(device.wait(Until.findObject(By.res("android:id/button2")), 5000)).click()
            control("${topName}を削除").click()
            text("追加辞書の削除")
            requireNotNull(device.wait(Until.findObject(By.res("android:id/button1")), 5000)).click()
            awaitOrder(original.drop(1))
            assertEquals(original, repository.listSources().filter { it.kind == DictionarySourceKind.SYSTEM }.map { it.id })
            text("保存").click()
            assertTrue(device.wait(Until.gone(By.text("辞書の管理")), 5000))
            assertEquals(original.drop(1), repository.listSources().filter { it.kind == DictionarySourceKind.SYSTEM }.map { it.id })
        } finally {
            scenario?.close()
            DictionarySettingsActivity.managerFactoryForTest = null
            manager.close()
            context.deleteDatabase(database)
        }
    }
}
