package se.haya.skk

import se.haya.skk.core.RegistrationView
import se.haya.skk.core.DynamicCompletionView

/** IME が所有する登録バッファを、一つの読み取り専用編集面として描画する値です。 */
internal data class RegistrationPresentation(
    val titleReading: String,
    val parentReading: String?,
    val text: String,
    val cursor: Int,
    val composingStart: Int?,
    val composingEnd: Int?,
    val completionStart: Int?,
    val completionEnd: Int?,
    val canEdit: Boolean,
    val canRegister: Boolean,
    val saving: Boolean,
) {
    init {
        require(cursor in 0..text.length)
        require((composingStart == null) == (composingEnd == null))
        require((completionStart == null) == (completionEnd == null))
        if (composingStart != null && composingEnd != null) {
            require(composingStart in 0..composingEnd && composingEnd <= text.length)
        }
        if (completionStart != null && completionEnd != null) {
            require(completionStart in 0..completionEnd && completionEnd <= text.length)
            require(cursor == completionStart)
        }
    }

    companion object {
        fun from(
            view: RegistrationView,
            touch: Boolean,
            completion: DynamicCompletionView? = null,
        ): RegistrationPresentation {
            val bodyCursor = view.cursor.coerceIn(0, view.body.length)
            val candidate = view.innerCandidate
            val composing = view.innerComposing
            val marker = when {
                candidate != null -> "▼"
                composing != null && view.innerCursor != null -> "▽"
                else -> ""
            }
            val visibleInner = when {
                touch -> composing.orEmpty()
                candidate != null -> candidate.committedText
                else -> composing.orEmpty()
            }
            val visibleCursor = when {
                candidate != null && !touch -> visibleInner.length
                else -> view.innerCursor?.coerceIn(0, visibleInner.length) ?: visibleInner.length
            }
            val ghost = completion?.suffix.orEmpty().takeIf {
                !touch && candidate == null && visibleCursor == visibleInner.length
            }.orEmpty()
            val insertion = marker + visibleInner.substring(0, visibleCursor) + ghost +
                visibleInner.substring(visibleCursor)
            val start = bodyCursor.takeIf { marker.isNotEmpty() || visibleInner.isNotEmpty() }
            val end = start?.plus(marker.length + visibleInner.length)
            val completionStart = (bodyCursor + marker.length + visibleCursor).takeIf { ghost.isNotEmpty() }
            val completionEnd = completionStart?.plus(ghost.length)
            val displayCursor = bodyCursor + marker.length + visibleCursor
            val clean = candidate == null && composing == null
            return RegistrationPresentation(
                titleReading = view.readingKey,
                parentReading = view.hierarchy.getOrNull(view.hierarchy.lastIndex - 1)?.readingKey,
                text = view.body.substring(0, bodyCursor) + insertion + view.body.substring(bodyCursor),
                cursor = displayCursor,
                composingStart = start,
                composingEnd = end,
                completionStart = completionStart,
                completionEnd = completionEnd,
                canEdit = clean && !view.saving,
                canRegister = clean && !view.saving && view.body.isNotEmpty(),
                saving = view.saving,
            )
        }
    }
}
