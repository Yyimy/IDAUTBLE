package com.example.idautble.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// The same compact technical typography used by Readings and Device status.
private val compactBody =
    TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 11.sp,
        lineHeight = 13.sp,
        letterSpacing = 0.sp,
    )
private val compactLabel = compactBody.copy(fontWeight = FontWeight.Medium)
private val compactTitle =
    compactBody.copy(fontWeight = FontWeight.SemiBold, fontSize = 12.sp, lineHeight = 15.sp)

val Typography =
    Typography(
        bodyLarge = compactBody,
        bodyMedium = compactBody,
        bodySmall = compactBody,
        labelLarge = compactLabel,
        labelMedium = compactLabel,
        labelSmall = compactLabel.copy(fontSize = 10.sp),
        titleLarge = compactTitle.copy(fontSize = 14.sp, lineHeight = 18.sp),
        titleMedium = compactTitle,
        titleSmall = compactTitle,
        headlineSmall = compactTitle.copy(fontSize = 16.sp, lineHeight = 20.sp),
    )
