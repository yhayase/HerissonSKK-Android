package se.haya.skk

import android.os.IBinder
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputBinding

/** 構成変更直後の同一入力欄だけに再接続を許す、短寿命の識別情報です。 */
internal data class ConfigurationRestartIdentity private constructor(
    private val bindingToken: IBinder,
    private val uid: Int,
    private val pid: Int,
    private val windowToken: IBinder,
    private val editor: EditorFingerprint,
    private val capturedAt: Long,
) {
    fun matches(info: EditorInfo?, binding: InputBinding?, window: IBinder?, now: Long): Boolean =
        now >= capturedAt && now - capturedAt <= MAX_AGE_MS &&
            binding?.connectionToken == bindingToken && binding.uid == uid && binding.pid == pid &&
            window == windowToken && info?.let(EditorFingerprint::from) == editor

    private data class EditorFingerprint(
        val packageName: String?, val fieldId: Int, val fieldName: String?,
        val inputType: Int, val imeOptions: Int, val privateImeOptions: String?,
        val actionId: Int, val actionLabel: String?, val hint: String?,
    ) {
        companion object {
            fun from(info: EditorInfo) = EditorFingerprint(info.packageName, info.fieldId, info.fieldName,
                info.inputType, info.imeOptions, info.privateImeOptions, info.actionId,
                info.actionLabel?.toString(), info.hintText?.toString())
        }
    }

    companion object {
        const val MAX_AGE_MS = 1_000L
        fun capture(info: EditorInfo?, binding: InputBinding?, window: IBinder?, now: Long): ConfigurationRestartIdentity? {
            if (info == null || binding?.connectionToken == null || window == null) return null
            return ConfigurationRestartIdentity(binding.connectionToken, binding.uid, binding.pid,
                window, EditorFingerprint.from(info), now)
        }
    }
}
