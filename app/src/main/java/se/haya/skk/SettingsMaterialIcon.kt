package se.haya.skk

import android.content.Context
import android.content.res.ColorStateList
import com.mikepenz.iconics.IconicsDrawable
import com.mikepenz.iconics.IconicsSize
import com.mikepenz.iconics.typeface.library.googlematerial.GoogleMaterial
import com.mikepenz.iconics.utils.sizeX
import com.mikepenz.iconics.utils.sizeY

internal fun settingsMaterialIcon(
    context: Context,
    icon: GoogleMaterial.Icon,
    tint: ColorStateList? = null,
): IconicsDrawable = IconicsDrawable(context, icon).apply {
    sizeX = IconicsSize.dp(24)
    sizeY = IconicsSize.dp(24)
    if (tint != null) colorList = tint
}

internal fun settingsThemeColor(context: Context, attribute: Int): ColorStateList? =
    context.obtainStyledAttributes(intArrayOf(attribute)).let { values ->
        try {
            values.getColorStateList(0)
        } finally {
            values.recycle()
        }
    }
