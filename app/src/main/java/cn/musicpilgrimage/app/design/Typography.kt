package cn.musicpilgrimage.app.design

import androidx.compose.material3.Typography
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.*
import androidx.compose.ui.unit.sp
import cn.musicpilgrimage.app.R

// Android supplies locale-appropriate CJK and other glyphs outside Inter's coverage.
@OptIn(ExperimentalTextApi::class)
private val SwissFont = FontFamily(
    listOf(FontWeight.Normal, FontWeight.Medium, FontWeight.SemiBold, FontWeight.Bold).map { weight ->
        Font(R.font.inter_variable, weight, variationSettings = FontVariation.Settings(FontVariation.weight(weight.weight)))
    }
)

internal fun physicalTypography():Typography {
    val base=Typography()
    fun TextStyle.swiss()=copy(fontFamily=SwissFont,fontWeight=FontWeight.Normal,
        fontSynthesis=FontSynthesis.None,letterSpacing=0.sp)
    return Typography(
        displayLarge=base.displayLarge.swiss(),displayMedium=base.displayMedium.swiss(),displaySmall=base.displaySmall.swiss(),
        headlineLarge=base.headlineLarge.swiss(),headlineMedium=base.headlineMedium.swiss(),headlineSmall=base.headlineSmall.swiss(),
        titleLarge=base.titleLarge.swiss(),titleMedium=base.titleMedium.swiss(),titleSmall=base.titleSmall.swiss(),
        bodyLarge=base.bodyLarge.swiss(),bodyMedium=base.bodyMedium.swiss(),bodySmall=base.bodySmall.swiss(),
        labelLarge=base.labelLarge.swiss(),labelMedium=base.labelMedium.swiss(),labelSmall=base.labelSmall.swiss())
}
