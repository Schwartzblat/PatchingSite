package site.androidmod.store.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * The site runs on `system-ui` at weight 400 with tightened headings, so this keeps
 * the platform font and only adjusts weight and tracking. Material's defaults letter-space
 * headlines apart at +0sp to +0.25sp, which reads loose next to the website's headings.
 */
private val Default = Typography()

val PapTypography = Typography(
    headlineLarge = Default.headlineLarge.tighten(FontWeight.Bold, (-0.5).sp),
    headlineMedium = Default.headlineMedium.tighten(FontWeight.Bold, (-0.4).sp),
    headlineSmall = Default.headlineSmall.tighten(FontWeight.SemiBold, (-0.3).sp),
    titleLarge = Default.titleLarge.tighten(FontWeight.SemiBold, (-0.2).sp),
    titleMedium = Default.titleMedium.tighten(FontWeight.SemiBold, 0.sp),
    titleSmall = Default.titleSmall.tighten(FontWeight.SemiBold, 0.sp),
    labelLarge = Default.labelLarge.tighten(FontWeight.SemiBold, 0.sp),
)

private fun TextStyle.tighten(weight: FontWeight, tracking: androidx.compose.ui.unit.TextUnit) =
    copy(fontWeight = weight, letterSpacing = tracking)
