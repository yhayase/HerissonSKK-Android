package se.haya.skk

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.util.concurrent.Executors
import se.haya.skk.dictionary.CompleteBackupFailure
import se.haya.skk.dictionary.CompleteBackupExport
import se.haya.skk.dictionary.CompleteBackupResult
import se.haya.skk.dictionary.DictionaryManager
import se.haya.skk.dictionary.DictionaryRuntime
import se.haya.skk.dictionary.PreparedDictionaryRestore

/** 辞書全体の保存と、全件検証後の明示的な置換だけを扱います。 */
class CompleteDictionaryBackupActivity : SettingsPageActivity() {
    private lateinit var manager: DictionaryManager
    private lateinit var pageFragment: CompleteDictionaryBackupFragment
    private val files = Executors.newSingleThreadExecutor()
    private var active = false
    private var busy = false
    private var statusText: CharSequence = "保存または復元を選択してください。"
    private var pendingPicker: Int? = null
    private var prepared: PreparedDictionaryRestore? = null
    private var preview: AlertDialog? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        active = true
        manager = managerFactoryForTest?.invoke() ?: DictionaryRuntime.get(this)
        pendingPicker = savedInstanceState?.getInt(STATE_PICKER)?.takeIf { it == EXPORT || it == RESTORE }
        statusText = if (savedInstanceState?.getBoolean(STATE_WORKING) == true && pendingPicker == null)
            "画面の再作成前の処理結果はここでは確認できません。復元の再適用はせず、保存済みの辞書を確認してください。未適用の確認は取り消しました。"
        else "保存または復元を選択してください。"
        val requestedPage = CompleteDictionaryBackupFragment()
        installSettingsPage("完全辞書バックアップ", requestedPage)
        pageFragment = supportFragmentManager.findFragmentById(R.id.settings_content)
            as? CompleteDictionaryBackupFragment ?: requestedPage
        setBusy(pendingPicker != null)
    }

    internal fun exportBackup() = launchPicker(EXPORT)
    internal fun restoreBackup() = launchPicker(RESTORE)

    internal fun reapplySavedDictionaries() {
        if (busy) return
        setBusy(true)
        manager.loadAsync { result ->
            if (active) {
                setBusy(false)
                showStatus(if (result == se.haya.skk.dictionary.DictionaryManagerStatus.Ready())
                    "保存済みの辞書を入力へ反映しました。" else "入力への反映に失敗しました。保存済みの辞書は保持しています。")
            }
        }
    }

    internal fun isPageBusy(): Boolean = busy

    private fun launchPicker(operation: Int) {
        if (busy) return
        pendingPicker = operation
        setBusy(true)
        val intent = Intent(if (operation == EXPORT) Intent.ACTION_CREATE_DOCUMENT else Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/octet-stream"
            if (operation == EXPORT) putExtra(Intent.EXTRA_TITLE, "skk-dictionaries.skkbackup")
        }
        try {
            startActivityForResult(intent, operation)
        } catch (_: android.content.ActivityNotFoundException) {
            pendingPicker = null
            setBusy(false)
            showStatus("ファイルを選択できるアプリがありません。")
        }
    }

    @Deprecated("基底 Activity では Activity Result API を利用できないため")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (pendingPicker != requestCode) return
        pendingPicker = null
        val uri = data?.data
        if (resultCode != RESULT_OK || uri == null) {
            setBusy(false)
            showStatus("ファイルの選択を取り消しました。辞書は変更していません。")
            return
        }
        when (requestCode) {
            EXPORT -> exportTo(uri)
            RESTORE -> prepareRestore(uri)
        }
    }

    private fun exportTo(uri: Uri) {
        showStatus("完全辞書バックアップを準備しています。")
        manager.exportCompleteBackup(applicationContext, packageManager.getPackageInfo(packageName, 0).versionName ?: "unknown") { result ->
            when (result) {
                is CompleteBackupResult.Applied -> writeExport(uri, result.value)
                is CompleteBackupResult.SavedButNotApplied -> writeExport(uri, result.value)
                is CompleteBackupResult.Failed -> if (active) {
                    setBusy(false)
                    showStatus(failureMessage(result.reason) + " 辞書は変更していません。")
                }
            }
        }
    }

    private fun writeExport(uri: Uri, exported: CompleteBackupExport) {
        if (!active) { exported.close(); return }
        files.execute {
            val result = runCatching {
                exported.use { backup ->
                    backup.openInput().use { input ->
                        contentResolver.openOutputStream(uri, "wt")?.use { output -> input.copyTo(output) }
                            ?: error("保存先を開けません")
                    }
                }
            }
            runOnUiThread {
                if (active) {
                    setBusy(false)
                    showStatus(if (result.isSuccess) "完全辞書バックアップを保存しました。"
                        else "保存に失敗しました。保存先に未完了のファイルが残っている場合があります。元の辞書は変更していません。")
                }
            }
        }
    }

    private fun prepareRestore(uri: Uri) {
        showStatus("バックアップ全体を検証しています。辞書はまだ変更していません。")
        manager.prepareCompleteRestore(applicationContext, {
            contentResolver.openInputStream(uri) ?: error("復元元を開けません")
        }) { result ->
            when (result) {
                is CompleteBackupResult.Applied -> showPreview(result.value)
                is CompleteBackupResult.SavedButNotApplied -> showPreview(result.value)
                is CompleteBackupResult.Failed -> if (active) {
                    setBusy(false)
                    showStatus(failureMessage(result.reason) + " 辞書は変更していません。")
                }
            }
        }
    }

    private fun showPreview(value: PreparedDictionaryRestore) {
        if (!active) { value.close(); return }
        prepared = value
        val summary = value.summary
        preview = MaterialAlertDialogBuilder(this)
            .setTitle("現在の辞書全体を置き換えます")
            .setMessage("辞書 ${summary.sourceCount} 件、候補 ${summary.candidateCount} 件、非表示指定 ${summary.suppressionCount} 件を復元します。現在の辞書と非表示指定は置き換わります。入力中の候補・登録・削除確認は終了します。")
            .setNegativeButton(android.R.string.cancel) { _, _ -> cancelPreview() }
            .setOnCancelListener { cancelPreview() }
            .setPositiveButton("辞書全体を置き換える") { _, _ -> applyRestore() }
            .show()
    }

    private fun cancelPreview() {
        prepared?.close()
        prepared = null
        preview = null
        setBusy(false)
        showStatus("復元を取り消しました。辞書は変更していません。")
    }

    private fun applyRestore() {
        val value = prepared ?: return
        prepared = null
        preview = null
        showStatus("辞書全体を復元しています。")
        manager.restoreComplete(value) { result ->
            if (active) {
                setBusy(false)
                showStatus(when (result) {
                    is CompleteBackupResult.Applied -> "辞書全体を復元し、入力へ反映しました。"
                    is CompleteBackupResult.SavedButNotApplied -> "復元は保存済みですが、入力への反映に失敗しました。「保存済みの辞書を入力へ再反映」を選択してください。復元の再適用は不要です。"
                    is CompleteBackupResult.Failed -> failureMessage(result.reason) + " 復元元を選択し直してください。"
                })
            }
        }
    }

    private fun failureMessage(reason: CompleteBackupFailure): String = when (reason) {
        CompleteBackupFailure.INVALID_FORMAT -> "バックアップの形式または内容が不正です。"
        CompleteBackupFailure.UNSUPPORTED_VERSION -> "このバックアップの形式の版には対応していません。"
        CompleteBackupFailure.LIMIT_EXCEEDED -> "バックアップが処理できる上限を超えています。"
        CompleteBackupFailure.CAPACITY -> "保存領域が不足しています。空き容量を確認してください。"
        CompleteBackupFailure.CONFLICT -> "確認後に辞書が変更されたか、復元の準備が終了しています。"
        CompleteBackupFailure.IO -> "バックアップの読み書きに失敗しました。ファイルとアクセス権を確認してください。"
    }

    private fun setBusy(value: Boolean) {
        busy = value
        renderShell()
    }

    private fun showStatus(message: String) {
        statusText = message
        renderShell()
        findViewById<TextView>(R.id.settings_status).announceForAccessibility(message)
    }

    private fun renderShell() {
        renderPageState(false, busy, statusText)
        if (::pageFragment.isInitialized) pageFragment.setControlsEnabled(!busy)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        pendingPicker?.let { outState.putInt(STATE_PICKER, it) }
        outState.putBoolean(STATE_WORKING, busy)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        active = false
        preview?.dismiss()
        preview = null
        prepared?.close()
        prepared = null
        // 受理済みの保存は close と結果確認まで完了させます。
        files.shutdown()
        super.onDestroy()
    }

    companion object {
        internal var managerFactoryForTest: (() -> DictionaryManager)? = null
        private const val EXPORT = 701
        private const val RESTORE = 702
        private const val STATE_PICKER = "complete_backup_picker"
        private const val STATE_WORKING = "complete_backup_working"
    }
}
