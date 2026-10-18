package se.haya.skk.input

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color

internal data class KeyboardColors(
    val characterBackground: Int,
    val functionBackground: Int,
    val text: Int,
    val border: Int,
    val pressed: Int,
    val panelBackground: Int,
    val selectedBackground: Int,
) {
    companion object {
        fun resolve(context: Context): KeyboardColors {
            val night = context.resources.configuration.uiMode and
                Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
            return if (night) KeyboardColors(
                characterBackground = 0xff42464c.toInt(),
                functionBackground = 0xff292d33.toInt(),
                text = 0xffeeeeee.toInt(),
                border = 0xff747b85.toInt(),
                pressed = 0xff596575.toInt(),
                panelBackground = 0xff202328.toInt(),
                selectedBackground = 0xff475d78.toInt(),
            ) else KeyboardColors(
                characterBackground = Color.WHITE,
                functionBackground = 0xffe3e6eb.toInt(),
                text = Color.BLACK,
                border = 0xff9ba1aa.toInt(),
                pressed = 0xffcbd8e8.toInt(),
                panelBackground = 0xfff2f3f5.toInt(),
                selectedBackground = 0xffc7d8ed.toInt(),
            )
        }
    }
}
