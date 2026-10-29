package cn.musicpilgrimage.app.design

import androidx.compose.material3.Typography
import androidx.compose.ui.text.font.*
import androidx.compose.ui.unit.sp
import cn.musicpilgrimage.app.R

/** Retain the legacy resource for compatibility; every active interface uses a sans serif face. */
internal val PixelFont=FontFamily(Font(R.font.fusion_pixel,FontWeight.Normal))
internal val InstrumentFont=FontFamily.SansSerif
internal val PrintFont=FontFamily.SansSerif

internal fun physicalTypography():Typography {
    val base=Typography()
    fun androidx.compose.ui.text.TextStyle.pixel()=copy(fontFamily=FontFamily.SansSerif,fontWeight=FontWeight.Normal,
        fontSynthesis=FontSynthesis.None,letterSpacing=0.sp)
    return Typography(
        displayLarge=base.displayLarge.pixel(),displayMedium=base.displayMedium.pixel(),displaySmall=base.displaySmall.pixel(),
        headlineLarge=base.headlineLarge.pixel(),headlineMedium=base.headlineMedium.pixel(),headlineSmall=base.headlineSmall.pixel(),
        titleLarge=base.titleLarge.pixel(),titleMedium=base.titleMedium.pixel(),titleSmall=base.titleSmall.pixel(),
        bodyLarge=base.bodyLarge.pixel(),bodyMedium=base.bodyMedium.pixel(),bodySmall=base.bodySmall.pixel(),
        labelLarge=base.labelLarge.pixel(),labelMedium=base.labelMedium.pixel(),labelSmall=base.labelSmall.pixel())
}
