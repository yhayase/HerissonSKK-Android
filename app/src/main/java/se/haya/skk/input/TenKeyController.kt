package se.haya.skk.input

import kotlin.math.abs
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

/** 確定した方向領域から出るたびに修飾を切り替え、出力文字の変更またはキー幅相当の移動で境界指定を取り消します。 */
internal class TenKeyGesture(private val threshold: Float, private val operation: TenKeyOperation, private val touchSlop: Float = threshold,
    private val hysteresis: Float = 0f,
    private val keyWidth: Float = threshold,
    private val keyHeight: Float = threshold,
    private val output: (TenKeyController.Direction, TenKeyController.Direction?) -> String? = { direction, modifier -> "$direction/$modifier" },
) {
    private var x = 0f
    private var y = 0f
    private var pauseX = 0f
    private var pauseY = 0f
    var motionRevision = 0
        private set
    private var lastX = 0f
    private var lastY = 0f
    private var holdX = 0f
    private var holdY = 0f
    private var heldOutput: String? = null
    private var direction = TenKeyController.Direction.CENTER
    private var confirmedRegion = TenKeyController.Direction.CENTER
    var active = false
        private set
    var modifier: TenKeyController.Direction? = null
        private set
    var boundary = false
        private set
    fun start(x: Float, y: Float) { this.x = x; this.y = y; pauseX = x; pauseY = y; motionRevision = 0; direction = TenKeyController.Direction.CENTER; active = true; modifier = null; boundary = false; lastX = x; lastY = y; confirmedRegion = TenKeyController.Direction.CENTER }
    fun hold(): Boolean {
        if (!active || boundary || operation == TenKeyOperation.FLICK) return false
        holdX = lastX; holdY = lastY
        heldOutput = output(direction, modifier)
        boundary = true
        return true
    }
    fun move(x: Float, y: Float): TenKeyController.Direction {
        if (active && !boundary && (x - pauseX) * (x - pauseX) + (y - pauseY) * (y - pauseY) > touchSlop * touchSlop) {
            pauseX = x; pauseY = y; motionRevision++
        }
        val dx = x - this.x; val dy = y - this.y
        direction = if (abs(dx) < threshold && abs(dy) < threshold) TenKeyController.Direction.CENTER
            else if (abs(dx) > abs(dy)) { if (dx < 0) TenKeyController.Direction.LEFT else TenKeyController.Direction.RIGHT }
            else { if (dy < 0) TenKeyController.Direction.UP else TenKeyController.Direction.DOWN }
        if (active && operation == TenKeyOperation.HOLD_AND_RETURN) {
            // 観測した線分上の内部を順に通過させ、サンプルの分割数に依存させません。
            TenKeyController.Direction.entries.mapNotNull { region ->
                regionEntry(region, lastX - this.x, lastY - this.y, dx, dy)?.let { it to region }
            }.sortedBy { it.first }.forEach { (_, region) ->
                if (region != confirmedRegion) {
                    val previous = confirmedRegion
                    if (previous != TenKeyController.Direction.CENTER) {
                        modifier = if (previous == TenKeyController.Direction.DOWN || modifier == previous) null else previous
                    }
                    confirmedRegion = region
                }
            }
        }
        if (active && boundary) {
            val normalizedX = (x - holdX) / keyWidth.coerceAtLeast(1f)
            val normalizedY = (y - holdY) / keyHeight.coerceAtLeast(1f)
            if (output(direction, modifier) != heldOutput || normalizedX * normalizedX + normalizedY * normalizedY >= 1f) {
                boundary = false
                pauseX = x; pauseY = y
                motionRevision++
            }
        }
        lastX = x; lastY = y
        return direction
    }
    /** 各境界からヒステリシス幅だけ内側へ入る最初の位置です。 */
    private fun regionEntry(region: TenKeyController.Direction, fromX: Float, fromY: Float, toX: Float, toY: Float): Double? {
        if (fromX == toX && fromY == toY) return null
        var enter = 0.0
        var exit = 1.0
        // 幅が正なら境界からちょうど指定距離の点も含めます。幅0の互換動作では接点を除外します。
        val inclusive = hysteresis > 0f
        fun intersects() = if (inclusive) enter <= exit else enter < exit
        fun clip(a: Double, b: Double, limit: Double): Boolean {
            val from = a * fromX + b * fromY - limit
            val delta = a * (toX.toDouble() - fromX) + b * (toY.toDouble() - fromY)
            if (delta == 0.0) return if (inclusive) from >= 0.0 else from > 0.0
            val crossing = -from / delta
            if (delta > 0) enter = maxOf(enter, crossing) else exit = minOf(exit, crossing)
            return intersects()
        }
        val h = hysteresis.toDouble()
        val t = threshold.toDouble()
        val inside = if (region == TenKeyController.Direction.CENTER) {
            clip(1.0, 0.0, h - t) && clip(-1.0, 0.0, h - t) &&
                clip(0.0, 1.0, h - t) && clip(0.0, -1.0, h - t)
        } else {
            val (a, b) = when (region) {
                TenKeyController.Direction.LEFT -> -1.0 to 0.0
                TenKeyController.Direction.RIGHT -> 1.0 to 0.0
                TenKeyController.Direction.UP -> 0.0 to -1.0
                else -> 0.0 to 1.0
            }
            val diagonal = h * kotlin.math.sqrt(2.0)
            clip(a, b, t + h) && clip(a + b, b - a, diagonal) && clip(a - b, b + a, diagonal)
        }
        return if (inside && intersects()) enter else null
    }
    fun cancel() { active = false }
}
