package jp.hayase.skk

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.UiAutomation
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.EditText
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import jp.hayase.skk.core.DictionaryQuery
import jp.hayase.skk.core.dictionary.SkkDictionaryCodec
import jp.hayase.skk.dictionary.CandidateOriginKind
import jp.hayase.skk.dictionary.CandidateSuppressionSnapshot
import jp.hayase.skk.dictionary.DeleteCandidateRequest
import jp.hayase.skk.dictionary.DictionaryManager
import jp.hayase.skk.dictionary.DictionaryManagerStatus
import jp.hayase.skk.dictionary.DictionaryManagerWriteResult
import jp.hayase.skk.dictionary.DictionarySourceInfo
import jp.hayase.skk.dictionary.SQLiteDictionaryRepository
import jp.hayase.skk.dictionary.StoredCandidateOriginRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** 専用エミュレーターの実 DocumentsUI を通して完全バックアップを往復します。 */
@RunWith(AndroidJUnit4::class)
class CompleteBackupSafE2eTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val device = UiDevice.getInstance(instrumentation)
    private val automation: UiAutomation = instrumentation.uiAutomation.apply {
        serviceInfo = serviceInfo.apply {
            flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
        }
    }

    @Test
    fun completeBackupRoundTripCancelAndMalformedInputKeepAtomicState() {
        val id = "skk-complete-backup-e2e-${UUID.randomUUID()}"
        val databaseName = "$id.db"
        val backupPath = "$DOWNLOADS/$id.skkbackup"
        val malformedPath = "$DOWNLOADS/$id-malformed.skkbackup"
        val repository = SQLiteDictionaryRepository(context, databaseName)
        val serial = Executors.newSingleThreadExecutor()
        val mainHandler = Handler(Looper.getMainLooper())
        val manager = DictionaryManager(
            repository = repository,
            serialExecutor = serial,
            callbackExecutor = java.util.concurrent.Executor { mainHandler.post(it) },
            ownedExecutor = serial,
        )
        var scenario: ActivityScenario<CompleteDictionaryBackupActivity>? = null

        try {
            seedBackupState(repository)
            loadManager(manager)
            CompleteDictionaryBackupActivity.managerFactoryForTest = { manager }
            scenario = ActivityScenario.launch(CompleteDictionaryBackupActivity::class.java)
            awaitAppText("保存または復元を選択してください。")

            clickText("完全辞書バックアップを保存")
            selectDownloads()
            setFileName(fileName(backupPath))
            clickDocumentsViewId("android:id/button1", "DocumentsUI の保存ボタンがありません")
            awaitDocumentsUiClosed()
            awaitAppText("完全辞書バックアップを保存しました。")
            assertTrue("完全バックアップが作成されません", readFile(backupPath).isNotEmpty())

            mutateAfterExport(repository)
            loadManager(manager)
            assertEquals(listOf("変更後"), manager.readBlocking { manager.lookup(DictionaryQuery("かな")) }.map { it.text })

            restoreFrom(fileName(backupPath), confirm = true)
            awaitAppText("辞書全体を復元し、入力へ反映しました。")
            assertRestoredState(manager)

            val restoredRevision = repository.dictionaryRevision()
            val restoredCandidates = manager.readBlocking { manager.lookup(DictionaryQuery("かな")) }.map { it.text }

            clickText("完全辞書バックアップから復元")
            awaitDocumentsNode("DocumentsUI が開きません") { it }
            device.pressBack()
            awaitDocumentsUiClosed()
            awaitAppText("ファイルの選択を取り消しました。辞書は変更していません。")
            assertEquals(restoredRevision, repository.dictionaryRevision())

            restoreFrom(fileName(backupPath), confirm = false)
            awaitAppText("復元を取り消しました。辞書は変更していません。")
            assertEquals(restoredRevision, repository.dictionaryRevision())
            assertEquals(restoredCandidates, manager.readBlocking { manager.lookup(DictionaryQuery("かな")) }.map { it.text })

            writeFile(malformedPath, "{\"type\":\"header\"}\n".toByteArray(StandardCharsets.UTF_8))
            clickText("完全辞書バックアップから復元")
            selectDownloads()
            openFile(fileName(malformedPath))
            awaitDocumentsUiClosed()
            awaitAppText("バックアップの形式または内容が不正です。 辞書は変更していません。")
            assertEquals(restoredRevision, repository.dictionaryRevision())
            assertEquals(restoredCandidates, manager.readBlocking { manager.lookup(DictionaryQuery("かな")) }.map { it.text })
        } finally {
            var cleanupFailure: Throwable? = null
            fun cleanup(action: () -> Unit) {
                try { action() } catch (failure: Throwable) {
                    if (cleanupFailure == null) cleanupFailure = failure else cleanupFailure!!.addSuppressed(failure)
                }
            }
            cleanup { scenario?.close() }
            CompleteDictionaryBackupActivity.managerFactoryForTest = null
            cleanup { manager.close() }
            cleanup {
                serial.shutdown()
                assertTrue("辞書管理器を終了できません", serial.awaitTermination(10, TimeUnit.SECONDS))
            }
            cleanup { context.deleteDatabase(databaseName) }
            cleanup { removeFiles(backupPath, malformedPath) }
            cleanupFailure?.let { throw it }
        }
    }

    private fun seedBackupState(repository: SQLiteDictionaryRepository) {
        repository.replacePersonal(SkkDictionaryCodec.parseText("かな /個人元;注釈/\n"))
        val first = repository.importSystem(
            "system-first", "第一辞書",
            SkkDictionaryCodec.parseText("じゅん /第一/\nかくす /非表示候補/\n"),
        )
        repository.importSystem(
            "system-priority", "優先辞書",
            SkkDictionaryCodec.parseText("じゅん /優先/\n"),
        )
        repository.importSystem(
            "system-disabled", "無効辞書",
            SkkDictionaryCodec.parseText("むこう /無効候補/\n"),
        )
        repository.setSystemOrder(listOf("system-priority", "system-first", "system-disabled"))
        repository.setSourceEnabled("system-disabled", false)
        val personal = repository.listSources().single { it.id == "personal" }
        repository.deleteCandidate(
            DeleteCandidateRequest(
                personal.generation,
                listOf(
                    StoredCandidateOriginRef(
                        sourceId = first.id,
                        sourceGeneration = first.generation,
                        kind = CandidateOriginKind.STORED_SYSTEM,
                        entryKey = "かくす",
                        templateText = "非表示候補",
                        okuriCondition = null,
                    ),
                ),
            ),
            approvedImmutableSources = emptyMap(),
        )
    }

    private fun mutateAfterExport(repository: SQLiteDictionaryRepository) {
        repository.replacePersonal(SkkDictionaryCodec.parseText("かな /変更後/\n"))
        repository.listCandidateSuppressions().let { snapshot ->
            snapshot.suppressions.forEach {
                repository.restoreCandidateSuppression(it.key, repository.listCandidateSuppressions().personalGeneration)
            }
        }
        repository.listSources().filter { it.id != "personal" }.forEach {
            repository.removeSystem(it.id, it.generation)
        }
        repository.importSystem("replacement", "置換後辞書", SkkDictionaryCodec.parseText("じゅん /置換後/\n"))
    }

    private fun assertRestoredState(manager: DictionaryManager) {
        assertEquals(listOf("個人元"), manager.readBlocking { manager.lookup(DictionaryQuery("かな")) }.map { it.text })
        assertEquals(listOf("優先", "第一"), manager.readBlocking { manager.lookup(DictionaryQuery("じゅん")) }.map { it.text })
        assertTrue("抑止した候補が復活しています", manager.readBlocking { manager.lookup(DictionaryQuery("かくす")) }.isEmpty())
        assertTrue("無効辞書の候補が検索結果へ現れています", manager.readBlocking { manager.lookup(DictionaryQuery("むこう")) }.isEmpty())

        val sources = managerSources(manager)
        assertEquals(
            listOf("personal", "system-priority", "system-first", "system-disabled"),
            sources.map { it.id },
        )
        assertFalse(sources.single { it.id == "system-disabled" }.enabled)
        val suppressions = managerSuppressions(manager)
        assertEquals(1, suppressions.suppressions.size)
        assertEquals("system-first", suppressions.suppressions.single().key.sourceId)
        assertEquals("非表示候補", suppressions.suppressions.single().key.templateText)
    }

    private fun loadManager(manager: DictionaryManager) {
        val latch = CountDownLatch(1)
        var status: DictionaryManagerStatus? = null
        manager.loadAsync { status = it; latch.countDown() }
        assertTrue("辞書管理器の読込が完了しません", latch.await(CALLBACK_TIMEOUT_SECONDS, TimeUnit.SECONDS))
        assertTrue("辞書管理器の読込に失敗しました: $status", status is DictionaryManagerStatus.Ready)
    }

    private fun managerSources(manager: DictionaryManager): List<DictionarySourceInfo> {
        val latch = CountDownLatch(1)
        var result: DictionaryManagerWriteResult<List<DictionarySourceInfo>>? = null
        manager.listSources { result = it; latch.countDown() }
        assertTrue("辞書一覧の取得が完了しません", latch.await(CALLBACK_TIMEOUT_SECONDS, TimeUnit.SECONDS))
        return (result as DictionaryManagerWriteResult.Applied).value
    }

    private fun managerSuppressions(manager: DictionaryManager): CandidateSuppressionSnapshot {
        val latch = CountDownLatch(1)
        var result: DictionaryManagerWriteResult<CandidateSuppressionSnapshot>? = null
        manager.listSuppressions { result = it; latch.countDown() }
        assertTrue("非表示指定の取得が完了しません", latch.await(CALLBACK_TIMEOUT_SECONDS, TimeUnit.SECONDS))
        return (result as DictionaryManagerWriteResult.Applied).value
    }

    private fun restoreFrom(fileName: String, confirm: Boolean) {
        clickText("完全辞書バックアップから復元")
        selectDownloads()
        openFile(fileName)
        awaitDocumentsUiClosed()
        awaitAppText("現在の辞書全体を置き換えます")
        if (confirm) {
            clickText("辞書全体を置き換える")
        } else {
            clickAppDialogButton("android:id/button2", "確認画面のキャンセルボタンがありません")
        }
    }

    private fun selectDownloads() {
        val initial = awaitDocumentsNode("DocumentsUI が開きません") { it }
        if (isDownloadsDirectory(initial)) return
        assertTrue(
            "DocumentsUI のストレージ一覧を開けません",
            clickNow("ストレージを表示", "Show roots", "ナビゲーション ドロワーを開く"),
        )
        clickAnyText("ダウンロード", "Downloads")
        awaitDocumentsNode("Downloads へ移動できません") { root -> root.takeIf(::isDownloadsDirectory) }
    }

    private fun setFileName(fileName: String) {
        val edit = awaitDocumentsNode("DocumentsUI の保存名欄がありません") { root ->
            root.findAccessibilityNodeInfosByViewId("android:id/title").firstOrNull {
                it.className?.toString() == EditText::class.java.name
            }
        }
        assertTrue(
            "DocumentsUI の保存名を設定できません",
            edit.performAction(
                AccessibilityNodeInfo.ACTION_SET_TEXT,
                Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, fileName)
                },
            ),
        )
        awaitDocumentsNode("DocumentsUI の保存名が更新されません") { root ->
            root.findAccessibilityNodeInfosByViewId("android:id/title").firstOrNull {
                it.className?.toString() == EditText::class.java.name && it.text?.toString() == fileName
            }
        }
    }

    private fun openFile(fileName: String) {
        sequenceOf("List view", "リスト表示")
            .mapNotNull { device.findObject(By.desc(it)) }
            .firstOrNull()
            ?.let { it.click(); device.waitForIdle() }
        val file = device.wait(Until.findObject(By.res("android", "title").text(fileName)), UI_TIMEOUT_MS)
            ?: throw AssertionError("Downloads にファイルが表示されません: $fileName")
        file.click()
        device.waitForIdle()
        findNodeNow { root ->
            sequenceOf("開く", "Open", "OPEN")
                .flatMap { root.findAccessibilityNodeInfosByText(it).asSequence() }
                .firstOrNull { it.isEnabled && it.text?.toString() in setOf("開く", "Open", "OPEN") }
        }?.let { assertTrue("ファイルを開けません", it.performAction(AccessibilityNodeInfo.ACTION_CLICK)) }
    }

    private fun isDownloadsDirectory(root: AccessibilityNodeInfo): Boolean {
        val labels = setOf("ダウンロード", "Downloads")
        return descendants(root).any { node ->
            node.text?.toString() in labels && node.viewIdResourceName?.endsWith(":id/breadcrumb_text") == true
        } || root.findAccessibilityNodeInfosByText("FILES IN DOWNLOADS").any {
            it.text?.toString() == "FILES IN DOWNLOADS"
        } || DOCUMENTS_UI_PACKAGES.any { packageName ->
            root.findAccessibilityNodeInfosByViewId("$packageName:id/toolbar").any { toolbar ->
                descendants(toolbar).any { it.isVisibleToUser && it.text?.toString() in labels }
            }
        }
    }

    private fun clickText(text: String) = clickAnyText(text)

    private fun clickAnyText(vararg labels: String) {
        val deadline = SystemClock.uptimeMillis() + UI_TIMEOUT_MS
        while (SystemClock.uptimeMillis() < deadline) {
            automation.windows.asSequence().mapNotNull { it.root }.forEach { root ->
                labels.asSequence().flatMap { root.findAccessibilityNodeInfosByText(it).asSequence() }
                    .filter { it.isEnabled && (it.text?.toString() in labels || it.contentDescription?.toString() in labels) }
                    .forEach { if (clickNodeOrParent(it)) return }
            }
            SystemClock.sleep(POLL_MS)
        }
        throw AssertionError("操作できません: ${labels.joinToString()}")
    }

    private fun clickNow(vararg labels: String): Boolean {
        automation.windows.asSequence().mapNotNull { it.root }.forEach { root ->
            labels.asSequence().flatMap { root.findAccessibilityNodeInfosByText(it).asSequence() }
                .filter { it.isEnabled && (it.text?.toString() in labels || it.contentDescription?.toString() in labels) }
                .forEach { if (clickNodeOrParent(it)) return true }
        }
        return false
    }

    private fun clickNodeOrParent(node: AccessibilityNodeInfo): Boolean {
        var current: AccessibilityNodeInfo? = node
        while (current != null) {
            if (current.isEnabled && current.isClickable && current.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
            current = current.parent
        }
        return node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }

    private fun clickDocumentsViewId(id: String, message: String) {
        val node = awaitDocumentsNode(message) { root ->
            root.findAccessibilityNodeInfosByViewId(id).firstOrNull { it.isEnabled && it.isClickable }
        }
        assertTrue(message, node.performAction(AccessibilityNodeInfo.ACTION_CLICK))
    }

    private fun clickAppDialogButton(id: String, message: String) {
        val node = awaitNode(message) { root ->
            root.takeIf { it.packageName?.toString() == SKK_PACKAGE }
                ?.findAccessibilityNodeInfosByViewId(id)
                ?.firstOrNull { it.isEnabled && it.isClickable }
        }
        assertTrue(message, node.performAction(AccessibilityNodeInfo.ACTION_CLICK))
    }

    private fun awaitAppText(text: String) {
        awaitNode("表示が更新されません: $text") { root ->
            root.takeIf { it.packageName?.toString() == SKK_PACKAGE }
                ?.findAccessibilityNodeInfosByText(text)
                ?.firstOrNull { it.text?.toString()?.contains(text) == true }
        }
    }

    private fun awaitDocumentsUiClosed() {
        val deadline = SystemClock.uptimeMillis() + UI_TIMEOUT_MS
        while (SystemClock.uptimeMillis() < deadline) {
            if (automation.windows.none { isDocumentsUi(it.root) }) return
            SystemClock.sleep(POLL_MS)
        }
        throw AssertionError("DocumentsUI が結果を返しません")
    }

    private fun awaitDocumentsNode(
        message: String,
        find: (AccessibilityNodeInfo) -> AccessibilityNodeInfo?,
    ): AccessibilityNodeInfo = awaitNode(message) { root -> root.takeIf(::isDocumentsUi)?.let(find) }

    private fun isDocumentsUi(root: AccessibilityNodeInfo?): Boolean =
        root?.packageName?.toString() in DOCUMENTS_UI_PACKAGES

    private fun awaitNode(
        message: String,
        find: (AccessibilityNodeInfo) -> AccessibilityNodeInfo?,
    ): AccessibilityNodeInfo {
        val deadline = SystemClock.uptimeMillis() + UI_TIMEOUT_MS
        while (SystemClock.uptimeMillis() < deadline) {
            automation.windows.asSequence().mapNotNull { it.root }.mapNotNull(find).firstOrNull()?.let { return it }
            SystemClock.sleep(POLL_MS)
        }
        throw AssertionError(message + "\n" + automation.windows.joinToString { window ->
            "package=${window.root?.packageName}, nodes=${descendants(window.root).map { node ->
                "${node.viewIdResourceName}:${node.text}:editable=${node.isEditable}"
            }.take(16)}"
        })
    }

    private fun findNodeNow(find: (AccessibilityNodeInfo) -> AccessibilityNodeInfo?): AccessibilityNodeInfo? =
        automation.windows.asSequence().mapNotNull { it.root }.mapNotNull(find).firstOrNull()

    private fun writeFile(path: String, bytes: ByteArray) {
        shell("printf %s ${Base64.getEncoder().encodeToString(bytes)} | base64 -d > $path")
    }

    private fun readFile(path: String): ByteArray = Base64.getMimeDecoder().decode(shell("base64 $path"))

    private fun removeFiles(vararg paths: String) {
        paths.forEach { path ->
            check(path.startsWith("$DOWNLOADS/skk-complete-backup-e2e-"))
            shell("rm -f -- $path")
        }
    }

    /** UiAutomation の空白分割を避け、終了状態を確認してから結果を返します。 */
    private fun shell(command: String): String {
        val marker = "__SKK_COMPLETE_BACKUP_EXIT__="
        val wrapped = "{ $command; } 2>&1; status=\$?; printf \"\\n$marker%s\\n\" \"\$status\""
        val encoded = Base64.getEncoder().encodeToString(wrapped.toByteArray(StandardCharsets.UTF_8))
        val raw = ParcelFileDescriptor.AutoCloseInputStream(
            automation.executeShellCommand("sh -c printf\${IFS}%s\${IFS}$encoded|base64\${IFS}-d|sh"),
        ).bufferedReader().use { it.readText() }
        val markerIndex = raw.lastIndexOf(marker)
        check(markerIndex >= 0) { "シェルの終了状態を取得できません: $command\n$raw" }
        val status = raw.substring(markerIndex + marker.length).lineSequence().firstOrNull().orEmpty()
        check(status == "0") { "シェル操作に失敗しました(status=$status): $command\n${raw.take(markerIndex)}" }
        return raw.substring(0, markerIndex)
    }

    private fun fileName(path: String): String = path.substringAfterLast('/')

    private fun descendants(node: AccessibilityNodeInfo?): List<AccessibilityNodeInfo> = when (node) {
        null -> emptyList()
        else -> listOf(node) + (0 until node.childCount).flatMap { descendants(node.getChild(it)) }
    }

    private companion object {
        const val SKK_PACKAGE = "jp.hayase.skk"
        val DOCUMENTS_UI_PACKAGES = setOf("com.google.android.documentsui", "com.android.documentsui")
        const val DOWNLOADS = "/sdcard/Download"
        const val UI_TIMEOUT_MS = 20_000L
        const val POLL_MS = 40L
        const val CALLBACK_TIMEOUT_SECONDS = 20L
    }
}
