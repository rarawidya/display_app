package com.example.displayapp.presentation.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

@Composable
fun IndicatorLamps(
    leftIndicator: Boolean,
    rightIndicator: Boolean,
    headlamp: Boolean,
    modifier: Modifier = Modifier
) {
    val blinkTransition = rememberInfiniteTransition(label = "blink")
    val blinkAlpha by blinkTransition.animateFloat(
        initialValue = 1f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(500, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "blinkAlpha"
    )

    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(24.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Left turn signal
        Icon(
            imageVector = LeftArrowIcon,
            contentDescription = "Left indicator",
            modifier = Modifier
                .size(28.dp)
                .alpha(if (leftIndicator) blinkAlpha else 0.2f),
            tint = if (leftIndicator) Color(0xFFFFAB00) else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)
        )

        // Headlamp
        Icon(
            imageVector = HeadlampIcon,
            contentDescription = "Headlamp",
            modifier = Modifier.size(28.dp),
            tint = if (headlamp) Color(0xFF2979FF) else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)
        )

        // Right turn signal
        Icon(
            imageVector = RightArrowIcon,
            contentDescription = "Right indicator",
            modifier = Modifier
                .size(28.dp)
                .alpha(if (rightIndicator) blinkAlpha else 0.2f),
            tint = if (rightIndicator) Color(0xFFFFAB00) else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)
        )
    }
}

private val LeftArrowIcon: ImageVector by lazy {
    ImageVector.Builder(
        name = "LeftArrow",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f
    ).apply {
        path(fill = androidx.compose.ui.graphics.SolidColor(Color.White)) {
            moveTo(14f, 7f)
            lineTo(9f, 12f)
            lineTo(14f, 17f)
            lineTo(14f, 13f)
            lineTo(20f, 13f)
            lineTo(20f, 11f)
            lineTo(14f, 11f)
            close()
        }
    }.build()
}

private val RightArrowIcon: ImageVector by lazy {
    ImageVector.Builder(
        name = "RightArrow",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f
    ).apply {
        path(fill = androidx.compose.ui.graphics.SolidColor(Color.White)) {
            moveTo(10f, 7f)
            lineTo(15f, 12f)
            lineTo(10f, 17f)
            lineTo(10f, 13f)
            lineTo(4f, 13f)
            lineTo(4f, 11f)
            lineTo(10f, 11f)
            close()
        }
    }.build()
}

private val HeadlampIcon: ImageVector by lazy {
    ImageVector.Builder(
        name = "Headlamp",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f
    ).apply {
        path(fill = androidx.compose.ui.graphics.SolidColor(Color.White)) {
            // Simple headlamp shape
            moveTo(4f, 8f)
            lineTo(4f, 16f)
            lineTo(8f, 16f)
            arcTo(4f, 4f, 0f, false, false, 8f, 8f)
            close()
            // Light rays
            moveTo(13f, 9f)
            lineTo(20f, 7f)
            lineTo(20f, 8f)
            lineTo(13f, 10f)
            close()
            moveTo(13f, 11f)
            lineTo(20f, 11f)
            lineTo(20f, 12f)
            lineTo(13f, 12f)
            close()
            moveTo(13f, 14f)
            lineTo(20f, 16f)
            lineTo(20f, 15f)
            lineTo(13f, 13f)
            close()
        }
    }.build()
}
