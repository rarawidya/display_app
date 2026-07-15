package com.innodrive.evdash.presentation.ui.common

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.innodrive.evdash.R
import com.innodrive.evdash.ui.theme.Dim

/**
 * Branded launch overlay shown for a beat after the OS splash hands off.
 *
 * The Android 12+ system splash ([androidx.core.splashscreen]) can only render a
 * background color + animated icon — it has no text slot. So the tagline lives
 * here, in a Compose surface that mirrors the system splash's look (same
 * launcher background + foreground icon) and then fades away, making the handoff
 * from the OS splash seamless.
 *
 * @param taglineAlpha 0..1 fade driver so the caller can animate the overlay out.
 */
@Composable
fun BrandSplash(
    modifier: Modifier = Modifier,
    contentAlpha: Float = 1f,
) {
    // Matches @color/ic_launcher_background used by the system splash theme.
    val splashBackground = Color(0xFF050E1A)

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(splashBackground)
            .alpha(contentAlpha),
    ) {
        Column(
            modifier = Modifier.align(Alignment.Center),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Dim.lg),
        ) {
            Image(
                painter = painterResource(id = R.drawable.innoride_icon),
                contentDescription = null,
                modifier = Modifier.size(220.dp),
            )

            Text(
                text = "Powered by innodrive.ai\nBuilt for Every Ride.",
                color = Color.White.copy(alpha = 0.72f),
                fontSize = 12.sp,
                lineHeight = 16.sp,
                textAlign = TextAlign.Center,
            )
        }
    }
}
