package jp.hayase.skk.testeditor

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.UiAutomation
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.EditText
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.UUID
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * K13 の SAF 経路を専用エミュレーターだけで検証します。
 *
 * 失敗時は Downloads の recovery JSON と backup を残します。通常の接続試験には含めません。
 */
@RunWith(AndroidJUnit4::class)
class SafDictionaryE2eTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val device = UiDevice.getInstance(instrumentation)
    private val automation: UiAutomation = instrumentation.uiAutomation.apply {
        serviceInfo = serviceInfo.apply {
            flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
        }
    }

    @Test fun replacePersonalThroughSafPublishesToImeAndExportsThenRestoresBackup() {
        val id = "skk-saf-e2e-${UUID.randomUUID()}"
        // text/plain の CREATE_DOCUMENT が拡張子を補わない名前を使い、shell 側の検証名と一致させます。
        val backup = "$DOWNLOADS/$id-backup.utf8.txt"
        val fixture = "$DOWNLOADS/$id-fixture.utf8.txt"
        val learned = "$DOWNLOADS/$id-learned.utf8.txt"
        val exported = "$DOWNLOADS/$id-exported.utf8.txt"
        val restored = "$DOWNLOADS/$id-restored.utf8.txt"
        val recovery = "$DOWNLOADS/$id-recovery.json"
        val fixtureBytes = """
            てすと /統合候補;E2E注釈/
            にほん /日本;第一注釈/二本;第二注釈/
        """.trimIndent().plus("\n").toByteArray(StandardCharsets.UTF_8)
        var backupBytes: ByteArray? = null
        var recoveryWritten = false
        var bodyFailure: Throwable? = null

        try {
            writeFile(fixture, fixtureBytes)
            openDictionarySettings()

            exportPersonal(fileName(backup))
            backupBytes = readFile(backup)
            assertTrue("バックアップを書き出せません", backupBytes.isNotEmpty())
            writeFile(recovery, recoveryJson(id, backup, fixture, learned, exported, restored).toByteArray(StandardCharsets.UTF_8))
            recoveryWritten = true

            replacePersonal(fileName(fixture))
            ActivityScenario.launch(InputTestActivity::class.java).use { scenario ->
                val editor = scenario.editorStartingWith("複数行 A")
                scenario.onActivity { editor.requestFocus() }
                awaitIme()
                key(KeyEvent.KEYCODE_J, KeyEvent.META_CTRL_ON)
                type("Tesuto ")
                key(KeyEvent.KEYCODE_ENTER)
                awaitText(editor, "統合候補")

                scenario.onActivity {
                    editor.setText("")
                    editor.requestFocus()
                }
                awaitIme()
                key(KeyEvent.KEYCODE_J, KeyEvent.META_CTRL_ON)
                type("Nihon")
                key(KeyEvent.KEYCODE_SPACE)
                key(KeyEvent.KEYCODE_SPACE)
                key(KeyEvent.KEYCODE_ENTER)
                awaitText(editor, "二本")

                val registrationEditor = scenario.editorStartingWith("複数行 B")
                scenario.onActivity { registrationEditor.requestFocus() }
                awaitIme()
                key(KeyEvent.KEYCODE_J, KeyEvent.META_CTRL_ON)
                type("Touroku ")
                type("tango")
                key(KeyEvent.KEYCODE_ENTER)
                awaitText(registrationEditor, "たんご")
            }

            openDictionarySettings()
            exportPersonal(fileName(learned))
            val learnedText = readFile(learned).toString(StandardCharsets.UTF_8)
            assertTrue("選択候補が先頭へ学習されません", learnedText.contains("にほん /二本;第二注釈/日本;第一注釈/"))

            ActivityScenario.launch(InputTestActivity::class.java).use { scenario ->
                val editor = scenario.editorStartingWith("複数行 A")
                scenario.onActivity { editor.requestFocus() }
                awaitIme()
                key(KeyEvent.KEYCODE_J, KeyEvent.META_CTRL_ON)
                type("Nihon ")
                key(KeyEvent.KEYCODE_ENTER)
                awaitText(editor, "二本")
            }

            openDictionarySettings()
            exportPersonal(fileName(exported))
            val exportedText = readFile(exported).toString(StandardCharsets.UTF_8)
            assertTrue("SAF 書き出しに見出し語がありません", exportedText.contains("てすと /統合候補;E2E注釈/"))
            assertTrue("物理キー登録が個人辞書へ保存されません", exportedText.contains("とうろく /たんご/"))
            assertTrue("学習候補または注釈が SAF 書き出しで失われました", exportedText.contains("にほん /二本;第二注釈/日本;第一注釈/"))
        } catch (failure: Throwable) {
            bodyFailure = failure
            throw failure
        } finally {
            if (recoveryWritten) {
                val restoreFailure = runCatching {
                    openDictionarySettings()
                    replacePersonal(fileName(backup))
                    exportPersonal(fileName(restored))
                    assertArrayEquals("個人辞書を開始時の内容へ戻せません", checkNotNull(backupBytes), readFile(restored))
                }.exceptionOrNull()
                if (restoreFailure == null) {
                    removeFiles(backup, fixture, learned, exported, restored, recovery)
                } else {
                    val recoveryError = AssertionError(
                        "個人辞書の復元に失敗しました。復旧情報を残しました: $recovery",
                        restoreFailure,
                    )
                    if (bodyFailure == null) throw recoveryError else bodyFailure!!.addSuppressed(recoveryError)
                }
            } else {
                removeFiles(backup, fixture, learned, exported, restored, recovery)
            }
        }
    }

    private fun openDictionarySettings() {
        // 前回失敗した SAF picker がタスク先頭に残っていても、設定画面まで確実に戻します。
        shell("am start -W -f 0x14000000 -n $SKK_PACKAGE/.SettingsActivity")
        awaitTextVisible("SKK の設定")
        clickText("辞書を管理する")
        awaitTextVisible("辞書を管理できます。")
    }

    private fun exportPersonal(fileName: String) {
        clickText("個人辞書を書き出す")
        selectDownloads()
        awaitEditableFileName(fileName)
        clickDocumentsViewId("android:id/button1", "DocumentsUI の保存ボタンがありません")
        awaitDocumentsUiClosed()
        awaitTextVisible("個人辞書を書き出しました。")
    }

    private fun replacePersonal(fileName: String) {
        clickText("個人辞書をファイルの内容で置き換える")
        selectDownloads()
        awaitFileAndOpen(fileName)
        awaitDocumentsUiClosed()
        awaitTextVisible("適用前の確認")
        clickText("適用する")
        awaitTextVisible("辞書を適用しました。")
    }

    private fun awaitEditableFileName(fileName: String) {
        awaitDocumentsNode("DocumentsUI の保存名欄がありません") { root ->
            root.findAccessibilityNodeInfosByViewId("android:id/title").firstOrNull {
                it.className?.toString() == EditText::class.java.name
            }
        }.also { node ->
            assertTrue("DocumentsUI の保存名を設定できません", node.performAction(
                AccessibilityNodeInfo.ACTION_SET_TEXT,
                Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, fileName) },
            ))
        }
        awaitDocumentsNode("DocumentsUI の保存名が UUID fixture へ更新されません: $fileName") { root ->
            root.findAccessibilityNodeInfosByViewId("android:id/title").firstOrNull {
                it.className?.toString() == EditText::class.java.name && it.text?.toString() == fileName
            }
        }
    }

    private fun awaitFileAndOpen(fileName: String) {
        // Google 版 DocumentsUI は title/item_root を clickable=false として公開するため、
        // キャッシュ無効化と安定したジェスチャーを備える UIAutomator で完全一致の行を操作します。
        sequenceOf("List view", "リスト表示")
            .mapNotNull { device.findObject(By.desc(it)) }
            .firstOrNull()
            ?.let { toggle ->
                toggle.click()
                device.waitForIdle()
            }
        val file = device.wait(
            Until.findObject(By.res("android", "title").text(fileName)),
            UI_TIMEOUT_MS,
        ) ?: throw AssertionError("Downloads に fixture が表示されません: $fileName")
        file.click()
        device.waitForIdle()
        findNodeNow { root ->
            sequenceOf("開く", "Open", "OPEN").flatMap { root.findAccessibilityNodeInfosByText(it).asSequence() }
                .firstOrNull { it.isEnabled && it.text?.toString() in setOf("開く", "Open", "OPEN") }
        }?.let { assertTrue("fixture を開けません", it.performAction(AccessibilityNodeInfo.ACTION_CLICK)) }
    }

    private fun selectDownloads() {
        val initial = awaitDocumentsNode("DocumentsUI が開きません") { it }
        if (isDownloadsDirectory(initial)) return
        assertTrue(
            "DocumentsUI のストレージ一覧を開けません",
            clickNow("ストレージを表示", "Show roots", "ナビゲーション ドロワーを開く"),
        )
        clickAnyText("ダウンロード", "Downloads")
        awaitDocumentsNode("Downloads へ移動できません") { root -> root.takeIf { isDownloadsDirectory(it) } }
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

    private fun isDownloadsDirectory(root: AccessibilityNodeInfo): Boolean {
        val labels = setOf("ダウンロード", "Downloads")
        return descendants(root).any { node ->
            node.text?.toString() in labels && node.viewIdResourceName?.endsWith(":id/breadcrumb_text") == true
        } || root.findAccessibilityNodeInfosByText("FILES IN DOWNLOADS")
            .any { it.text?.toString() == "FILES IN DOWNLOADS" }
    }

    private fun clickNodeOrParent(node: AccessibilityNodeInfo): Boolean {
        var current: AccessibilityNodeInfo? = node
        while (current != null) {
            if (current.isEnabled && current.isClickable &&
                current.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
            current = current.parent
        }
        return node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }

    private fun awaitTextVisible(text: String) {
        awaitNode("表示が更新されません: $text") { root ->
            root.takeIf { it.packageName?.toString() == SKK_PACKAGE }
                ?.findAccessibilityNodeInfosByText(text)?.firstOrNull()
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
    ): AccessibilityNodeInfo = awaitNode(message) { root ->
        root.takeIf(::isDocumentsUi)?.let(find)
    }

    private fun isDocumentsUi(root: AccessibilityNodeInfo?): Boolean =
        root?.packageName?.toString() in DOCUMENTS_UI_PACKAGES

    private fun clickDocumentsViewId(id: String, message: String) {
        val node = awaitDocumentsNode(message) { root ->
            root.findAccessibilityNodeInfosByViewId(id).firstOrNull { it.isEnabled && it.isClickable }
        }
        assertTrue(message, node.performAction(AccessibilityNodeInfo.ACTION_CLICK))
    }

    private fun awaitNode(message: String, find: (AccessibilityNodeInfo) -> AccessibilityNodeInfo?): AccessibilityNodeInfo {
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

    private fun awaitIme() {
        val deadline = SystemClock.uptimeMillis() + UI_TIMEOUT_MS
        while (SystemClock.uptimeMillis() < deadline) {
            if (automation.windows.any { window ->
                    val root = window.root
                    root?.packageName?.toString() == SKK_PACKAGE &&
                        root.findAccessibilityNodeInfosByViewId("$SKK_PACKAGE:id/input_status").isNotEmpty()
                }) return
            SystemClock.sleep(POLL_MS)
        }
        throw AssertionError("SKK IME の準備が完了しません")
    }

    private fun ActivityScenario<InputTestActivity>.editorStartingWith(hint: String): EditText {
        lateinit var editor: EditText
        onActivity { activity ->
            editor = descendants(activity.window.decorView).filterIsInstance<EditText>().first {
                it.hint.toString().startsWith(hint)
            }
        }
        return editor
    }

    private fun type(value: String) {
        val events = KeyCharacterMap.load(KeyCharacterMap.VIRTUAL_KEYBOARD).getEvents(value.toCharArray())!!
        events.forEach { instrumentation.sendKeySync(it) }
        instrumentation.waitForIdleSync()
    }

    private fun key(code: Int, meta: Int = 0) {
        val now = SystemClock.uptimeMillis()
        instrumentation.sendKeySync(KeyEvent(now, now, KeyEvent.ACTION_DOWN, code, 0, meta,
            KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0, InputDevice.SOURCE_KEYBOARD))
        instrumentation.sendKeySync(KeyEvent(now, SystemClock.uptimeMillis(), KeyEvent.ACTION_UP, code, 0, meta,
            KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0, InputDevice.SOURCE_KEYBOARD))
        instrumentation.waitForIdleSync()
    }

    private fun awaitText(view: TextView, expected: String) {
        val deadline = SystemClock.uptimeMillis() + UI_TIMEOUT_MS
        while (SystemClock.uptimeMillis() < deadline && text(view) != expected) SystemClock.sleep(POLL_MS)
        assertTrue("期待した変換結果ではありません: ${text(view)}", text(view) == expected)
    }

    private fun text(view: TextView): String {
        var value = ""
        instrumentation.runOnMainSync { value = view.text.toString() }
        return value
    }

    private fun writeFile(path: String, bytes: ByteArray) {
        shell("printf %s ${Base64.getEncoder().encodeToString(bytes)} | base64 -d > $path")
        assertArrayEquals("fixture を作成できません: $path", bytes, readFile(path))
    }

    private fun readFile(path: String): ByteArray = Base64.getMimeDecoder().decode(shell("base64 $path"))

    private fun removeFiles(vararg paths: String) {
        paths.forEach { path ->
            check(path.startsWith("$DOWNLOADS/skk-saf-e2e-"))
            shell("rm -f -- $path")
            check(!fileExists(path)) { "テスト用ファイルを削除できません: $path" }
        }
    }

    private fun fileExists(path: String): Boolean = shell("if test -e $path; then echo yes; fi").trim() == "yes"

    /** UiAutomation の空白分割を避け、復号したスクリプトを終了状態付きで sh へ渡します。 */
    private fun shell(command: String): String {
        val marker = "__SKK_SAF_EXIT__="
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

    private fun recoveryJson(
        id: String,
        backup: String,
        fixture: String,
        learned: String,
        exported: String,
        restored: String,
    ) = """{"test":"$id","backup":"$backup","fixture":"$fixture","learned":"$learned","exported":"$exported","restored":"$restored"}""" + "\n"

    private fun fileName(path: String): String = path.substringAfterLast('/')

    private fun descendants(view: View?): List<View> = when (view) {
        null -> emptyList()
        is ViewGroup -> listOf(view) + (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) }
        else -> listOf(view)
    }

    private fun descendants(node: AccessibilityNodeInfo?): List<AccessibilityNodeInfo> = when (node) {
        null -> emptyList()
        else -> listOf(node) + (0 until node.childCount).flatMap { descendants(node.getChild(it)) }
    }

    private companion object {
        const val SKK_PACKAGE = "jp.hayase.skk"
        val DOCUMENTS_UI_PACKAGES = setOf("com.google.android.documentsui", "com.android.documentsui")
        const val DOWNLOADS = "/sdcard/Download"
        const val UI_TIMEOUT_MS = 10_000L
        const val POLL_MS = 25L
    }
}
