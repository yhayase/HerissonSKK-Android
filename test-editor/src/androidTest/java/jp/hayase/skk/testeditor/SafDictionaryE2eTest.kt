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
import android.widget.Switch
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
        val systemFixture = "$DOWNLOADS/$id-system.utf8.txt"
        val recovery = "$DOWNLOADS/$id-recovery.json"
        val systemSourceName = fileName(systemFixture)
        val deletionReading = "zz" + id.filter(Char::isLetterOrDigit).takeLast(12).map { character ->
            if (character.isDigit()) 'g' + (character - '0') else character
        }.joinToString("")
        val deletionCandidate = "削除検証候補" + id.takeLast(8)
        val fixtureBytes = """
            てすと /統合候補;E2E注釈/
            に /二/
            にほん /日本;第一注釈/二本;第二注釈/
            na# /第#0;数値注釈/
            nb# /#1/
            nc# /#2/
            nd# /#3/
            ne# /地域:#4;外側注釈/
            nf# /#5/
            ng# /#8/
            nh# /#9/
            314 /北区;内側注釈/
        """.trimIndent().plus("\n").toByteArray(StandardCharsets.UTF_8)
        var backupBytes: ByteArray? = null
        var recoveryWritten = false
        var systemImported = false
        var suppressionCreated = false
        var suppressionRestored = false
        var systemRemoved = false
        var originalDynamicCompletion: Boolean? = null
        var dynamicSettingTouched = false
        var dynamicSettingRestored = false
        var bodyFailure: Throwable? = null

        try {
            writeFile(fixture, fixtureBytes)
            writeFile(systemFixture, "$deletionReading /$deletionCandidate/\n".toByteArray(StandardCharsets.UTF_8))
            openMainSettings()
            originalDynamicCompletion = dynamicCompletionEnabled()
            openDictionarySettings()

            exportPersonal(fileName(backup))
            backupBytes = readFile(backup)
            assertTrue("バックアップを書き出せません", backupBytes.isNotEmpty())
            writeFile(recovery, recoveryJson(
                id, backup, fixture, learned, exported, restored,
                systemFixture, systemSourceName, deletionReading, deletionCandidate,
                checkNotNull(originalDynamicCompletion),
            ).toByteArray(StandardCharsets.UTF_8))
            recoveryWritten = true

            openMainSettings()
            // 操作後の表示確認に失敗しても元の設定へ戻すため、クリック前に復元対象として記録します。
            dynamicSettingTouched = true
            setDynamicCompletion(true)
            openDictionarySettings()
            replacePersonal(fileName(fixture))
            // この呼出し中に永続化して後続の UI 検証だけが失敗しても、finally で必ず探索します。
            systemImported = true
            addSystemDictionary(systemSourceName)
            applyDictionaryChanges()

            val noLearningIntent = android.content.Intent(instrumentation.targetContext, InputTestActivity::class.java)
                .putExtra(InputTestActivity.EXTRA_SUPPRESS_LEARNING, true)
            ActivityScenario.launch<InputTestActivity>(noLearningIntent).use { scenario ->
                val editor = scenario.editorStartingWith("複数行 A")
                scenario.onActivity { editor.requestFocus() }
                awaitIme()
                key(KeyEvent.KEYCODE_J, KeyEvent.META_CTRL_ON)
                type("Ni")
                awaitTextVisible("補完候補: に【ほん】")
                key(KeyEvent.KEYCODE_SPACE)
                key(KeyEvent.KEYCODE_ENTER)
                awaitText(editor, "二")
            }

            // 新しい入力欄は設定を読み直し、Right で受諾した場合だけ補完後の読みを変換します。
            ActivityScenario.launch<InputTestActivity>(noLearningIntent).use { scenario ->
                val editor = scenario.editorStartingWith("複数行 A")
                scenario.onActivity { editor.requestFocus() }
                awaitIme()
                key(KeyEvent.KEYCODE_J, KeyEvent.META_CTRL_ON)
                type("Ni")
                awaitTextVisible("補完候補: に【ほん】")
                key(KeyEvent.KEYCODE_DPAD_RIGHT)
                key(KeyEvent.KEYCODE_SPACE)
                key(KeyEvent.KEYCODE_ENTER)
                awaitText(editor, "日本")
            }

            ActivityScenario.launch(InputTestActivity::class.java).use { scenario ->
                val editor = scenario.editorStartingWith("複数行 A")
                scenario.onActivity { editor.requestFocus() }
                awaitIme()
                key(KeyEvent.KEYCODE_J, KeyEvent.META_CTRL_ON)
                type("Tesuto ")
                key(KeyEvent.KEYCODE_ENTER)
                awaitText(editor, "統合候補")
            }

            // 同じ入力欄の setText に伴う選択通知が遅れて次の打鍵へ混ざらないよう、新しい入力欄で検証します。
            ActivityScenario.launch(InputTestActivity::class.java).use { scenario ->
                val editor = scenario.editorStartingWith("複数行 A")
                scenario.onActivity { editor.requestFocus() }
                awaitIme()
                key(KeyEvent.KEYCODE_J, KeyEvent.META_CTRL_ON)
                type("Nihon")
                key(KeyEvent.KEYCODE_SPACE)
                key(KeyEvent.KEYCODE_SPACE)
                key(KeyEvent.KEYCODE_ENTER)
                awaitText(editor, "二本")
                // 保存完了待ちや別セッションを挟まず、直後の変換でも選択結果を優先します。
                type("Nihon ")
                awaitText(editor, "二本二本")
                key(KeyEvent.KEYCODE_ENTER)
                awaitText(editor, "二本二本")
            }

            ActivityScenario.launch(InputTestActivity::class.java).use { scenario ->
                val registrationEditor = scenario.editorStartingWith("複数行 B")
                scenario.onActivity { registrationEditor.requestFocus() }
                awaitIme()
                key(KeyEvent.KEYCODE_J, KeyEvent.META_CTRL_ON)
                type("Touroku ")
                type("tango")
                key(KeyEvent.KEYCODE_ENTER)
                awaitText(registrationEditor, "たんご")
            }

            ActivityScenario.launch(InputTestActivity::class.java).use { scenario ->
                val editor = scenario.editorStartingWith("複数行 A")
                scenario.onActivity { editor.requestFocus() }
                awaitIme()
                key(KeyEvent.KEYCODE_J, KeyEvent.META_CTRL_ON)
                type("/$deletionReading ")
                awaitText(editor, deletionCandidate)

                key(KeyEvent.KEYCODE_X, KeyEvent.META_SHIFT_ON)
                awaitTextVisible("削除確認: $deletionReading → $deletionCandidate")
                type("n")
                awaitText(editor, deletionCandidate)
                awaitTextNotVisible("削除確認: $deletionReading → $deletionCandidate")

                key(KeyEvent.KEYCODE_X, KeyEvent.META_SHIFT_ON)
                awaitTextVisible("削除確認: $deletionReading → $deletionCandidate")
                // y の保存完了直後に表示検証が失敗しても、復元対象を見失わないよう先に記録します。
                suppressionCreated = true
                type("y")
                awaitText(editor, deletionReading)
                awaitTextNotVisible("単語登録")
            }

            openDictionarySettings()
            assertTrue(
                "削除したシステム候補の非表示指定が一覧にありません",
                restoreSuppressionIfPresent(systemSourceName, deletionReading, deletionCandidate),
            )
            suppressionRestored = true
            awaitTextVisible("非表示の指定を解除しました")

            val deletionVerificationIntent = android.content.Intent(
                instrumentation.targetContext, InputTestActivity::class.java,
            ).putExtra(InputTestActivity.EXTRA_SUPPRESS_LEARNING, true)
            ActivityScenario.launch<InputTestActivity>(deletionVerificationIntent).use { scenario ->
                val editor = scenario.editorStartingWith("複数行 A")
                scenario.onActivity { editor.requestFocus() }
                awaitIme()
                key(KeyEvent.KEYCODE_J, KeyEvent.META_CTRL_ON)
                type("/$deletionReading ")
                awaitText(editor, deletionCandidate)
                key(KeyEvent.KEYCODE_ENTER)
                awaitText(editor, deletionCandidate)
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

            ActivityScenario.launch<InputTestActivity>(noLearningIntent).use { scenario ->
                val editor = scenario.editorStartingWith("複数行 A")
                scenario.onActivity { editor.requestFocus() }
                awaitIme()
                key(KeyEvent.KEYCODE_J, KeyEvent.META_CTRL_ON)
                type("Nihon")
                key(KeyEvent.KEYCODE_SPACE)
                key(KeyEvent.KEYCODE_SPACE)
                key(KeyEvent.KEYCODE_ENTER)
                awaitText(editor, "日本")
            }

            ActivityScenario.launch(InputTestActivity::class.java).use { scenario ->
                val editor = scenario.editorStartingWith("複数行 A")
                val numericCases = listOf(
                    "na12" to "第12", "nb2048" to "２０４８", "nc2048" to "二〇四八",
                    "nd2048" to "二千四十八", "ne314" to "地域:北区", "nf10000" to "壱萬",
                    "ng7654321" to "7,654,321", "nh73" to "７三",
                )
                scenario.onActivity { editor.requestFocus() }
                awaitIme()
                var expectedText = ""
                // アプリ側 setText の選択通知と次の打鍵を競合させず、連続変換を実際に検査します。
                for ((reading, expected) in numericCases) {
                    key(KeyEvent.KEYCODE_J, KeyEvent.META_CTRL_ON)
                    type("/$reading ")
                    key(KeyEvent.KEYCODE_ENTER)
                    expectedText += expected
                    awaitText(editor, expectedText)
                }
            }

            openDictionarySettings()
            exportPersonal(fileName(exported))
            val exportedText = readFile(exported).toString(StandardCharsets.UTF_8)
            assertTrue("SAF 書き出しに見出し語がありません", exportedText.contains("てすと /統合候補;E2E注釈/"))
            assertTrue("物理キー登録が個人辞書へ保存されません", exportedText.contains("とうろく /たんご/"))
            assertTrue("学習候補または注釈が SAF 書き出しで失われました", exportedText.contains("にほん /二本;第二注釈/日本;第一注釈/"))
            assertTrue("数値学習で元テンプレートが失われました", exportedText.contains("na# /第#0;数値注釈/"))
            assertTrue("数値の展開済み本文を別見出しへ学習しました", !exportedText.lineSequence().any { it.startsWith("na12 ") })
            assertTrue("再検索で外側テンプレートの注釈が失われました", exportedText.contains("ne# /地域:#4;外側注釈/"))
            assertTrue("システム候補の削除確認で個人辞書へ登録または学習しました",
                exportedText.lineSequence().none { it.startsWith("$deletionReading ") || it.contains(deletionCandidate) })
        } catch (failure: Throwable) {
            bodyFailure = failure
            throw failure
        } finally {
            if (recoveryWritten) {
                val dictionaryRestoreFailure = runCatching {
                    openDictionarySettings()
                    if (!suppressionRestored) {
                        val restoredSuppression = restoreSuppressionIfPresent(
                            systemSourceName, deletionReading, deletionCandidate,
                        )
                        if (suppressionCreated && !restoredSuppression) {
                            throw AssertionError("作成した非表示指定を復元できません")
                        }
                        suppressionRestored = true
                    }
                    if (!systemRemoved) {
                        val removedSystem = removeSystemIfPresent(systemSourceName)
                        if (systemImported && !removedSystem) {
                            throw AssertionError("追加した試験辞書を削除できません")
                        }
                        systemRemoved = true
                    }
                    replacePersonal(fileName(backup))
                    applyDictionaryChanges()
                    openDictionarySettings()
                    exportPersonal(fileName(restored))
                    assertArrayEquals("個人辞書を開始時の内容へ戻せません", checkNotNull(backupBytes), readFile(restored))
                }.exceptionOrNull()
                val settingRestoreFailure = runCatching {
                    if (dynamicSettingTouched) {
                        openMainSettings()
                        setDynamicCompletion(checkNotNull(originalDynamicCompletion))
                        dynamicSettingRestored = true
                    }
                }.exceptionOrNull()
                val restoreFailures = listOfNotNull(dictionaryRestoreFailure, settingRestoreFailure)
                if (restoreFailures.isEmpty()) {
                    removeFiles(backup, fixture, learned, exported, restored, systemFixture, recovery)
                } else {
                    val recoveryError = AssertionError(
                        "辞書状態の復元に失敗しました。復旧情報を残しました: $recovery " +
                            "(systemImported=$systemImported, suppressionCreated=$suppressionCreated, " +
                            "suppressionRestored=$suppressionRestored, systemRemoved=$systemRemoved, " +
                            "dynamicSettingRestored=$dynamicSettingRestored)",
                        restoreFailures.first(),
                    )
                    restoreFailures.drop(1).forEach(recoveryError::addSuppressed)
                    if (bodyFailure == null) throw recoveryError else bodyFailure!!.addSuppressed(recoveryError)
                }
            } else {
                removeFiles(backup, fixture, learned, exported, restored, systemFixture, recovery)
            }
        }
    }

    private fun openDictionarySettings() {
        openMainSettings()
        clickText("辞書を管理する")
        awaitTextVisible("辞書を管理できます。")
    }

    private fun openMainSettings() {
        // 前回失敗した SAF picker がタスク先頭に残っていても、設定画面まで確実に戻します。
        shell("am start -W -f 0x14000000 -n $SKK_PACKAGE/.SettingsActivity")
        awaitTextVisible("SKK の設定")
    }

    private fun dynamicCompletionEnabled(): Boolean = dynamicCompletionSwitch().isChecked

    private fun setDynamicCompletion(enabled: Boolean) {
        val current = dynamicCompletionSwitch()
        if (current.isChecked != enabled) {
            assertTrue("動的補完の設定を変更できません", clickNodeOrParent(current))
        }
        awaitNode("動的補完の設定が反映されません: $enabled") { root ->
            root.takeIf { it.packageName?.toString() == SKK_PACKAGE }
                ?.findAccessibilityNodeInfosByText(DYNAMIC_COMPLETION_LABEL)
                ?.firstOrNull { it.className?.toString() == Switch::class.java.name && it.isChecked == enabled }
        }
    }

    private fun dynamicCompletionSwitch(): AccessibilityNodeInfo {
        val deadline = SystemClock.uptimeMillis() + UI_TIMEOUT_MS
        while (SystemClock.uptimeMillis() < deadline) {
            findNodeNow { root ->
                root.takeIf { it.packageName?.toString() == SKK_PACKAGE }
                    ?.findAccessibilityNodeInfosByText(DYNAMIC_COMPLETION_LABEL)
                    ?.firstOrNull {
                        it.className?.toString() == Switch::class.java.name && it.isCheckable && it.isVisibleToUser
                    }
            }?.let { return it }
            // 小さい API 26 画面でも、公開設定の三つ目のスイッチを実際に表示してから操作します。
            device.swipe(device.displayWidth / 2, device.displayHeight * 4 / 5,
                device.displayWidth / 2, device.displayHeight / 5, 16)
            device.waitForIdle()
        }
        throw AssertionError("動的補完の設定がありません")
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
        awaitTextVisible("変更を一時保存しました。画面を閉じるときに適用します。")
    }

    private fun addSystemDictionary(fileName: String) {
        clickText("追加辞書を読み込む")
        selectDownloads()
        awaitFileAndOpen(fileName)
        awaitDocumentsUiClosed()
        awaitTextVisible("適用前の確認")
        clickText("適用する")
        awaitTextVisible("変更を一時保存しました。画面を閉じるときに適用します。")
        if (findScopedRowWithScroll(fileName, detail = null) == null) {
            throw AssertionError("追加辞書が一覧へ現れません: $fileName")
        }
    }

    private fun applyDictionaryChanges() {
        clickText("変更を適用して閉じる")
        awaitTextVisible("SKK の設定")
    }

    /** 一意な由来・読み・候補がそろう行だけを復元します。存在しなければ何も変更しません。 */
    private fun restoreSuppressionIfPresent(sourceName: String, reading: String, candidate: String): Boolean {
        val detail = "読み: $reading\n候補: $candidate\n送り: なし"
        val row = findScopedRowWithScroll(sourceName, detail) ?: return false
        val restore = descendants(row).firstOrNull {
            it.text?.toString() == "再表示する" && it.isEnabled && it.isClickable
        } ?: throw AssertionError("対象の非表示候補に再表示ボタンがありません: $sourceName / $reading")
        assertTrue("対象の非表示候補を選べません", restore.performAction(AccessibilityNodeInfo.ACTION_CLICK))
        awaitTextVisible("候補を再表示する")
        awaitTextVisible(sourceName)
        awaitTextVisible("読み: $reading")
        awaitTextVisible("候補: $candidate")
        clickAppDialogPositive("再表示する")
        awaitTextVisible("非表示の指定を解除しました")
        return true
    }

    /** 一意なファイル名の追加辞書行だけを削除します。存在しなければ何も変更しません。 */
    private fun removeSystemIfPresent(sourceName: String): Boolean {
        val row = findScopedRowWithScroll(sourceName, detail = null) ?: return false
        val remove = descendants(row).firstOrNull {
            it.text?.toString() == "削除" && it.isEnabled && it.isClickable
        } ?: throw AssertionError("対象の追加辞書に削除ボタンがありません: $sourceName")
        assertTrue("対象の追加辞書を選べません", remove.performAction(AccessibilityNodeInfo.ACTION_CLICK))
        awaitTextVisible("追加辞書の削除")
        awaitTextVisible("「$sourceName」を削除します。個人辞書の登録・学習内容は削除されません。")
        clickAppDialogPositive("削除")
        awaitExactAppTextAbsent(sourceName, "追加辞書を削除できません: $sourceName")
        return true
    }

    private fun findScopedRowWithScroll(
        sourceName: String,
        detail: String?,
    ): AccessibilityNodeInfo? {
        // 前の操作が抑止一覧までスクロールしていても、毎回先頭から一方向に探索します。
        repeat(4) {
            device.swipe(device.displayWidth / 2, device.displayHeight / 5,
                device.displayWidth / 2, device.displayHeight * 4 / 5, 16)
            device.waitForIdle()
        }
        val deadline = SystemClock.uptimeMillis() + UI_TIMEOUT_MS
        while (SystemClock.uptimeMillis() < deadline) {
            automation.windows.asSequence().mapNotNull { it.root }
                .filter { it.packageName?.toString() == SKK_PACKAGE }
                .forEach { root ->
                    val anchor = descendants(root).firstOrNull { node ->
                        if (detail != null) node.text?.toString() == detail
                        else isSourceLabel(node, sourceName)
                    }
                    var row = anchor
                    while (row != null) {
                        val rowNodes = descendants(row)
                        val hasScopedAction = if (detail == null) {
                            rowNodes.any { it.text?.toString() == "削除" }
                        } else {
                            rowNodes.any { it.text?.toString() == detail } &&
                                rowNodes.any { it.text?.toString() == "再表示する" }
                        }
                        if (rowNodes.any { isSourceLabel(it, sourceName) } && hasScopedAction) return row
                        row = row.parent
                    }
                }
            device.swipe(device.displayWidth / 2, device.displayHeight * 4 / 5,
                device.displayWidth / 2, device.displayHeight / 5, 16)
            device.waitForIdle()
        }
        return null
    }

    private fun isSourceLabel(node: AccessibilityNodeInfo, sourceName: String): Boolean {
        val text = node.text?.toString()
        return text == sourceName || (
            node.className?.toString() == Switch::class.java.name &&
                (text == "$sourceName ON" || text == "$sourceName OFF")
            )
    }

    private fun clickAppDialogPositive(label: String) {
        val button = awaitNode("確認ダイアログの操作ボタンがありません: $label") { root ->
            root.takeIf { it.packageName?.toString() == SKK_PACKAGE }
                ?.findAccessibilityNodeInfosByViewId("android:id/button1")
                ?.firstOrNull { it.text?.toString() == label && it.isEnabled && it.isClickable }
        }
        assertTrue("確認ダイアログを操作できません: $label",
            button.performAction(AccessibilityNodeInfo.ACTION_CLICK))
    }

    private fun awaitExactAppTextAbsent(text: String, message: String) {
        val deadline = SystemClock.uptimeMillis() + UI_TIMEOUT_MS
        while (SystemClock.uptimeMillis() < deadline) {
            val exists = automation.windows.asSequence().mapNotNull { it.root }
                .filter { it.packageName?.toString() == SKK_PACKAGE }
                .flatMap { descendants(it).asSequence() }
                .any { isSourceLabel(it, text) }
            if (!exists) return
            SystemClock.sleep(POLL_MS)
        }
        throw AssertionError(message)
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
            .any { it.text?.toString() == "FILES IN DOWNLOADS" } ||
            // API26 の Downloads は breadcrumb ではなく toolbar の直下に表示されます。
            DOCUMENTS_UI_PACKAGES.any { packageName ->
                root.findAccessibilityNodeInfosByViewId("$packageName:id/toolbar").any { toolbar ->
                    descendants(toolbar).any { it.isVisibleToUser && it.text?.toString() in labels }
                }
            }
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

    private fun awaitTextNotVisible(text: String) {
        val deadline = SystemClock.uptimeMillis() + UI_TIMEOUT_MS
        while (SystemClock.uptimeMillis() < deadline) {
            val visible = automation.windows.asSequence().mapNotNull { it.root }
                .flatMap { it.findAccessibilityNodeInfosByText(text).asSequence() }
                .any { it.text?.toString()?.contains(text) == true }
            if (!visible) return
            SystemClock.sleep(POLL_MS)
        }
        throw AssertionError("表示が消えません: $text")
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
        systemFixture: String,
        systemSourceName: String,
        deletionReading: String,
        deletionCandidate: String,
        originalDynamicCompletion: Boolean,
    ) = """{"test":"$id","backup":"$backup","fixture":"$fixture","learned":"$learned","exported":"$exported","restored":"$restored","systemFixture":"$systemFixture","systemSourceName":"$systemSourceName","deletionReading":"$deletionReading","deletionCandidate":"$deletionCandidate","originalDynamicCompletion":$originalDynamicCompletion}""" + "\n"

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
        const val DYNAMIC_COMPLETION_LABEL = "個人辞書の動的補完（次の入力欄から適用）"
        val DOCUMENTS_UI_PACKAGES = setOf("com.google.android.documentsui", "com.android.documentsui")
        const val DOWNLOADS = "/sdcard/Download"
        const val UI_TIMEOUT_MS = 10_000L
        const val POLL_MS = 25L
    }
}
