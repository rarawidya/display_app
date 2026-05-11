package com.example.displayapp.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// Numeric readouts use Monospace so digits don't shift width during realtime updates.
private val Numeric = FontFamily.Monospace
private val Brand   = FontFamily.SansSerif

val Typography = Typography(
    // Hero numerics (speedometer, big counters)
    displayLarge = TextStyle(
        fontFamily = Numeric,
        fontWeight = FontWeight.Bold,
        fontSize = 96.sp,
        lineHeight = 100.sp,
        letterSpacing = (-2).sp
    ),
    displayMedium = TextStyle(
        fontFamily = Numeric,
        fontWeight = FontWeight.SemiBold,
        fontSize = 56.sp,
        lineHeight = 60.sp,
        letterSpacing = (-1).sp
    ),
    displaySmall = TextStyle(
        fontFamily = Numeric,
        fontWeight = FontWeight.SemiBold,
        fontSize = 36.sp,
        lineHeight = 40.sp
    ),

    // Section headers
    headlineMedium = TextStyle(
        fontFamily = Brand,
        fontWeight = FontWeight.SemiBold,
        fontSize = 22.sp,
        lineHeight = 28.sp,
        letterSpacing = 0.sp
    ),
    headlineSmall = TextStyle(
        fontFamily = Brand,
        fontWeight = FontWeight.SemiBold,
        fontSize = 18.sp,
        lineHeight = 24.sp
    ),

    // Card titles
    titleLarge = TextStyle(
        fontFamily = Brand,
        fontWeight = FontWeight.SemiBold,
        fontSize = 18.sp,
        lineHeight = 24.sp
    ),
    titleMedium = TextStyle(
        fontFamily = Brand,
        fontWeight = FontWeight.Medium,
        fontSize = 15.sp,
        lineHeight = 20.sp
    ),
    titleSmall = TextStyle(
        fontFamily = Brand,
        fontWeight = FontWeight.Medium,
        fontSize = 13.sp,
        lineHeight = 18.sp,
        letterSpacing = 0.2.sp
    ),

    // Body
    bodyLarge = TextStyle(
        fontFamily = Brand,
        fontWeight = FontWeight.Normal,
        fontSize = 15.sp,
        lineHeight = 22.sp
    ),
    bodyMedium = TextStyle(
        fontFamily = Brand,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp
    ),
    bodySmall = TextStyle(
        fontFamily = Brand,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.2.sp
    ),

    // Eyebrow / label / caps
    labelLarge = TextStyle(
        fontFamily = Brand,
        fontWeight = FontWeight.SemiBold,
        fontSize = 13.sp,
        lineHeight = 18.sp,
        letterSpacing = 0.4.sp
    ),
    labelMedium = TextStyle(
        fontFamily = Brand,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 14.sp,
        letterSpacing = 0.6.sp
    ),
    labelSmall = TextStyle(
        fontFamily = Brand,
        fontWeight = FontWeight.Medium,
        fontSize = 10.sp,
        lineHeight = 14.sp,
        letterSpacing = 1.sp
    )
)
