package com.flowbangla.app.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// Font families. System Serif and SansSerif map cleanly to Noto Serif / Noto Sans Bengali.
val FlowSans = FontFamily.SansSerif
val FlowSerif = FontFamily.Serif
val FlowSansBn = FontFamily.SansSerif
val FlowSerifBn = FontFamily.Serif

// Pick the family set based on the UI language. Pass isBangla from the
// language setting. Bangla gets taller line boxes (see §9 of redesign guide).
fun flowTypography(isBangla: Boolean): Typography {
    val sans  = if (isBangla) FlowSansBn  else FlowSans
    val serif = if (isBangla) FlowSerifBn else FlowSerif
    return Typography(
        displayMedium = TextStyle(
            fontFamily = serif,
            fontWeight = FontWeight.Normal,
            fontSize = 30.sp,
            lineHeight = if (isBangla) 44.sp else 38.sp
        ),
        titleLarge = TextStyle(
            fontFamily = sans,
            fontWeight = FontWeight.Medium,
            fontSize = 20.sp,
            lineHeight = if (isBangla) 32.sp else 28.sp
        ),
        bodyLarge = TextStyle(
            fontFamily = sans,
            fontWeight = FontWeight.Normal,
            fontSize = 16.sp,
            lineHeight = if (isBangla) 26.sp else 24.sp
        ),
        bodySmall = TextStyle(
            fontFamily = sans,
            fontWeight = FontWeight.Normal,
            fontSize = 12.sp,
            lineHeight = if (isBangla) 20.sp else 18.sp
        ),
        labelLarge = TextStyle(
            fontFamily = sans,
            fontWeight = FontWeight.Medium,
            fontSize = 14.sp,
            lineHeight = if (isBangla) 22.sp else 20.sp
        )
    )
}

// Transcript style, used for History text and the live caption.
fun transcriptStyle(isBangla: Boolean) = TextStyle(
    fontFamily = if (isBangla) FlowSerifBn else FlowSerif,
    fontWeight = FontWeight.Normal,
    fontSize   = 17.sp,
    lineHeight = if (isBangla) 30.sp else 28.sp,
)
