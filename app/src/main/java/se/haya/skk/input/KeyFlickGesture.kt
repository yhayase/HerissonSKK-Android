package se.haya.skk.input

/** 接触中の共通認識をQWERTYのタップ・上下出力へ対応させます。 */
internal class KeyFlickGesture(metrics: FlickMetrics) {
    private val recognition = FlickGesture(metrics)
    fun start(x: Float, y: Float) = recognition.start(x, y)
    val isActive: Boolean get() = recognition.active
    val isCancelled: Boolean get() = recognition.cancelled
    private fun mapped(region: FlickGesture.Region?) = when (region) {
        FlickGesture.Region.CENTER -> TouchKeyboardController.Flick.TAP
        FlickGesture.Region.UP -> TouchKeyboardController.Flick.UP
        FlickGesture.Region.DOWN -> TouchKeyboardController.Flick.DOWN
        else -> null
    }
    fun direction(x: Float, y: Float): TouchKeyboardController.Flick? {
        recognition.move(x, y)
        return mapped(recognition.region)
    }
    fun finish(x: Float, y: Float): TouchKeyboardController.Flick? = mapped(recognition.finish(x, y))
    fun cancel() = recognition.cancel()
}
