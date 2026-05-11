package com.example.displayapp.ui.theme

import androidx.compose.ui.unit.dp

/**
 * Centralized spacing/sizing tokens.
 * Use these instead of magic dp values so the layout stays proportional.
 */
object Dim {
    val xxs = 2.dp
    val xs  = 4.dp
    val sm  = 8.dp
    val md  = 12.dp
    val lg  = 16.dp
    val xl  = 24.dp
    val xxl = 32.dp
    val xxxl = 48.dp

    // Screen-edge gutter (phone vs tablet handled at the screen level)
    val screenGutter   = 16.dp
    val screenGutterLg = 24.dp

    // Card defaults
    val cardCorner = 20.dp
    val cardElev   = 0.dp        // glass cards rely on tint+stroke, not shadow
    val tallCard   = 168.dp
    val mediumCard = 124.dp
    val shortCard  = 96.dp

    // Bottom navigation
    val navBarHeight = 72.dp
    val navIcon      = 22.dp
}
