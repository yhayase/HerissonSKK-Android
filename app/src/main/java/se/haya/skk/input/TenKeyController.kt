package se.haya.skk.input

import android.text.InputType
import se.haya.skk.core.InputMode
import se.haya.skk.settings.TenKeyOperation

/** 面の文字種はSKKの状態とは独立して保持します。 */
internal class TenKeyController {
    enum class Face { KANA, ALPHA, NUMBER }
    enum class Direction { CENTER, LEFT, UP, RIGHT, DOWN }
    var face = Face.KANA
        private set
    var katakana = false
    var wideAlpha = false
    var wideNumber = false
    var uppercase = false
    var nextBoundary = false
    private data class OrdinaryState(val face: Face, val katakana: Boolean, val wideAlpha: Boolean,
        val wideNumber: Boolean, val uppercase: Boolean)
    private var ordinaryState: OrdinaryState? = null
    private var lastInputType: Int? = null

    /** 入力欄の初期面だけを切り替え、通常欄で選んだ文字種は保護します。 */
    fun startEditorSession(inputType: Int, restarting: Boolean = false) {
        nextBoundary = false
        // 構成変更で編集セッションを作り直す場合も、この欄で選んだ面は維持します。
        if (restarting && lastInputType == inputType) return
        lastInputType = inputType
        val initialFace = when (inputType and InputType.TYPE_MASK_CLASS) {
            InputType.TYPE_CLASS_NUMBER -> Face.NUMBER
            InputType.TYPE_CLASS_TEXT -> when (inputType and InputType.TYPE_MASK_VARIATION) {
                InputType.TYPE_TEXT_VARIATION_PASSWORD,
                InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
                InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD -> Face.ALPHA
                else -> null
            }
            else -> null
        }
        if (initialFace != null) {
            if (ordinaryState == null) ordinaryState = OrdinaryState(face, katakana, wideAlpha, wideNumber, uppercase)
            face = initialFace
            wideAlpha = false
            wideNumber = false
            uppercase = false
        } else {
            ordinaryState?.let {
                face = it.face
                katakana = it.katakana
                wideAlpha = it.wideAlpha
                wideNumber = it.wideNumber
                uppercase = it.uppercase
            }
            ordinaryState = null
        }
    }
    val kanaMode get() = if (katakana) InputMode.KATAKANA else InputMode.HIRAGANA
    fun cycleFace() { face = Face.entries[(face.ordinal + 1) % 3] }
    fun toggleKind() { when (face) {
        Face.KANA -> katakana = !katakana
        Face.ALPHA -> wideAlpha = !wideAlpha
        Face.NUMBER -> wideNumber = !wideNumber
    } }
    fun rows(): List<List<String>> = when (face) {
        Face.KANA -> KANA
        Face.ALPHA -> ALPHA
        Face.NUMBER -> NUMBER
    }
    fun output(row: List<String>, direction: Direction, modifier: Direction?): String? {
        if (modifier == Direction.UP && face != Face.NUMBER) {
            val number = when (face) {
                Face.KANA -> KANA.indexOf(row).takeIf { it in 0..8 }?.plus(1)?.toString()
                    ?: if (row == KANA[10]) "0" else null
                Face.ALPHA -> ALPHA.indexOf(row).takeIf { it in 0..8 }?.plus(1)?.toString()
                    ?: if (row == ALPHA[10]) "0" else null
                else -> null
            }
            if (number != null) return width(number, wideNumber)
        }
        val original = row.getOrNull(direction.ordinal)?.takeIf { it.isNotEmpty() } ?: return null
        return when (face) {
            Face.KANA -> {
                val transformed = when (modifier) {
                    Direction.LEFT -> voiced(original)
                    Direction.RIGHT -> if (row == KANA[5]) handa(original) else small(original)
                    else -> original
                }
                if (katakana) transformed.map { if (it in 'ぁ'..'ゖ') it + 0x60 else it }.joinToString("") else transformed
            }
            Face.ALPHA -> width(if (uppercase xor (modifier == Direction.RIGHT)) original.uppercase() else original, wideAlpha)
            Face.NUMBER -> if (original.all { it.isDigit() }) width(original, wideNumber) else original
        }
    }
    fun modifiedRow(row: List<String>, modifier: Direction?) = Direction.entries.map { output(row, it, modifier).orEmpty() }
    /** この行で実際に出力が変わる修飾だけを案内します。 */
    fun modifierLabel(row: List<String>, modifier: Direction?): String? {
        if (modifier == null || modifiedRow(row, modifier) == modifiedRow(row, null)) return null
        return when (modifier) {
            Direction.LEFT -> if (face == Face.KANA) "濁点" else null
            Direction.RIGHT -> when (face) {
                Face.KANA -> if (row == KANA[5]) "半濁点" else "小文字"
                Face.ALPHA -> if (uppercase) "小文字" else "大文字"
                Face.NUMBER -> null
            }
            Direction.UP -> if (face != Face.NUMBER) "数字" else null
            else -> null
        }
    }
    private fun width(text: String, wide: Boolean) = if (!wide) text else text.map { if (it in '!'..'~') it + 0xfee0 else it }.joinToString("")
    private fun voiced(text: String) = transform(text, "うかきくけこさしすせそたちつてとはひふへほ", "ゔがぎぐげござじずぜぞだぢづでどばびぶべぼ")
    private fun handa(text: String) = transform(text, "はひふへほ", "ぱぴぷぺぽ")
    private fun small(text: String) = transform(text, "あいうえおつやゆよわかけ", "ぁぃぅぇぉっゃゅょゎゕゖ")
    private fun transform(text: String, from: String, to: String) = text.map { ch -> from.indexOf(ch).let { if (it < 0) ch else to[it] } }.joinToString("")
    companion object {
        val KANA = listOf("あいうえお", "かきくけこ", "さしすせそ", "たちつてと", "なにぬねの", "はひふへほ", "まみむめも", "や「ゆ」よ", "らりるれろ", "小゛゜", "わをんー", "、。？！").map { it.map(Char::toString) }
        val ALPHA = listOf(listOf("@", "#", "/", "&", "_")) + listOf("abc", "def", "ghi", "jkl", "mno", "pqrs", "tuv", "wxyz", "a/A", "'\"()", ".,?!").map { it.map(Char::toString) }
        val NUMBER = (1..9).map { listOf(it.toString()) } + listOf(listOf("-", "+", "/", "*", "="), listOf("0", "(", ")", "%", ","), listOf(".", ",", ":", ";", "!"))
    }
}

/** 領域遷移の修飾を適用した後、最終出力を使ってホールドを判定します。 */
internal class TenKeyGesture(
    private val metrics: FlickMetrics,
    private val operation: TenKeyOperation,
    private val keyWidth: Float = metrics.activationPx,
    private val keyHeight: Float = metrics.activationPx,
    private val output: (TenKeyController.Direction, TenKeyController.Direction?) -> String? = { direction, modifier -> "$direction/$modifier" },
) {
    private val recognition = FlickGesture(metrics)
    private var pauseX = 0f
    private var pauseY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var holdX = 0f
    private var holdY = 0f
    private var heldOutput: String? = null
    var motionRevision = 0
        private set
    val direction: TenKeyController.Direction? get() = recognition.region?.let { TenKeyController.Direction.valueOf(it.name) }
    val active: Boolean get() = recognition.active
    val cancelled: Boolean get() = recognition.cancelled
    var modifier: TenKeyController.Direction? = null
        private set
    var boundary = false
        private set
    private fun selectedOutput() = direction?.let { output(it, modifier) }?.takeIf { it.isNotEmpty() }
    fun start(x: Float, y: Float) {
        recognition.start(x, y)
        pauseX = x; pauseY = y; lastX = x; lastY = y
        motionRevision = 0; modifier = null; boundary = false; heldOutput = null
    }
    fun hold(): Boolean {
        if (!active || boundary || operation == TenKeyOperation.FLICK) return false
        val selected = selectedOutput() ?: return false
        holdX = lastX; holdY = lastY; heldOutput = selected; boundary = true
        return true
    }
    private fun restartPause(x: Float, y: Float) { pauseX = x; pauseY = y; motionRevision++ }
    private fun exceedsHoldDistance(x: Float, y: Float): Boolean {
        val dx = (x - holdX) / keyWidth.coerceAtLeast(1f)
        val dy = (y - holdY) / keyHeight.coerceAtLeast(1f)
        return dx * dx + dy * dy >= 1f
    }
    fun move(x: Float, y: Float): TenKeyController.Direction? {
        if (!active) return direction
        val previousOutput = selectedOutput()
        val transitions = recognition.move(x, y)
        if (cancelled) { boundary = false; motionRevision++; return null }
        val wasHeld = boundary
        transitions.forEach { transition ->
            if (operation == TenKeyOperation.HOLD_AND_RETURN && transition.to != null &&
                transition.from != null && transition.from != FlickGesture.Region.CENTER) {
                val exited = TenKeyController.Direction.valueOf(transition.from.name)
                modifier = if (exited == TenKeyController.Direction.DOWN || modifier == exited) null else exited
            }
            // 一つの確定領域への移動と修飾を合わせた出力で判定します。
            val transitionOutput = transition.to?.let {
                output(TenKeyController.Direction.valueOf(it.name), modifier)
            }?.takeIf { it.isNotEmpty() }
            if (boundary && transitionOutput != heldOutput) boundary = false
        }
        val finalOutput = selectedOutput()
        if (boundary && (finalOutput != heldOutput || exceedsHoldDistance(x, y))) boundary = false
        if (wasHeld && !boundary) {
            restartPause(x, y)
        } else if (!boundary) {
            val dx = x - pauseX; val dy = y - pauseY
            if (transitions.isNotEmpty() || finalOutput != previousOutput || dx * dx + dy * dy > metrics.slopPx * metrics.slopPx) restartPause(x, y)
        }
        lastX = x; lastY = y
        return direction
    }
    /** 離す座標では修飾も選択も変えず、遠い移動による解除だけを扱います。 */
    fun finish(x: Float, y: Float): TenKeyController.Direction? {
        val selected = recognition.finish(x, y)
        if (cancelled) boundary = false
        else if (boundary && exceedsHoldDistance(x, y)) { boundary = false; restartPause(x, y) }
        return selected?.let { TenKeyController.Direction.valueOf(it.name) }
    }
    fun cancel() { recognition.cancel(); boundary = false; motionRevision++ }
}
