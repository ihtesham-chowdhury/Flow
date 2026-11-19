package com.flowbangla.app.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.sp

// Font families. System Serif and SansSerif map cleanly to Noto Serif / Noto Sans Bengali.
val FlowSans = FontFamily.SansSerif
val FlowSerif = FontFamily.Serif
val FlowSansBn = FontFamily.SansSerif
val FlowSerifBn = FontFamily.Serif

// Bengali typography metrics: Never trim line box edges so matras & conjuncts never clip
val FlowBengaliLineHeightStyle = LineHeightStyle(
    alignment = LineHeightStyle.Alignment.Center,
    trim = LineHeightStyle.Trim.None
)

// Pick the family set based on the UI language. Pass isBangla from the
// language setting. Bangla gets taller line boxes (1.7x body line height).
fun flowTypography(isBangla: Boolean): Typography {
    val sans  = if (isBangla) FlowSansBn  else FlowSans
    val serif = if (isBangla) FlowSerifBn else FlowSerif
    val lhStyle = if (isBangla) FlowBengaliLineHeightStyle else LineHeightStyle.Default

    return Typography(
        displayMedium = TextStyle(
            fontFamily = serif,
            fontWeight = FontWeight.Normal,
            fontSize = 30.sp,
            lineHeight = if (isBangla) 44.sp else 38.sp,
            lineHeightStyle = lhStyle
        ),
        titleLarge = TextStyle(
            fontFamily = sans,
            fontWeight = FontWeight.Medium,
            fontSize = 20.sp,
            lineHeight = if (isBangla) 34.sp else 28.sp,
            lineHeightStyle = lhStyle
        ),
        bodyLarge = TextStyle(
            fontFamily = sans,
            fontWeight = FontWeight.Normal,
            fontSize = 16.sp,
            lineHeight = if (isBangla) 28.sp else 24.sp, // 1.75x line height for Bengali conjuncts
            lineHeightStyle = lhStyle
        ),
        bodySmall = TextStyle(
            fontFamily = sans,
            fontWeight = FontWeight.Normal,
            fontSize = 12.sp,
            lineHeight = if (isBangla) 21.sp else 18.sp,
            lineHeightStyle = lhStyle
        ),
        labelLarge = TextStyle(
            fontFamily = sans,
            fontWeight = FontWeight.Medium,
            fontSize = 14.sp,
            lineHeight = if (isBangla) 24.sp else 20.sp,
            lineHeightStyle = lhStyle
        )
    )
}

// Transcript style, used for History text and the live caption.
fun transcriptStyle(isBangla: Boolean) = TextStyle(
    fontFamily = if (isBangla) FlowSerifBn else FlowSerif,
    fontWeight = FontWeight.Normal,
    fontSize   = 17.sp,
    lineHeight = if (isBangla) 30.sp else 28.sp,
    lineHeightStyle = if (isBangla) FlowBengaliLineHeightStyle else LineHeightStyle.Default
)
