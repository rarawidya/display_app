package com.example.displayapp.presentation.ui.icons

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * Self-contained icon set so we don't depend on material-icons-extended (~22 MB).
 * Each icon is a 24×24 viewport drawn in pure path data — tinted by the caller.
 */
object EvIcons {

    val Drive: ImageVector by lazy { vec("Drive") {
        // Speedometer dial silhouette
        path(fill = SolidColor(Color.White)) {
            moveTo(12f, 4f)
            arcToRelative(8f, 8f, 0f, false, false, -7.07f, 11.74f)
            lineToRelative(1.78f, -1f)
            arcTo(6f, 6f, 0f, true, true, 17.29f, 14.74f)
            lineToRelative(1.78f, 1f)
            arcTo(8f, 8f, 0f, false, false, 12f, 4f)
            close()
            // Needle
            moveTo(11.5f, 9f)
            lineToRelative(1f, 0f)
            lineToRelative(.4f, 4.4f)
            arcToRelative(.9f, .9f, 0f, true, true, -1.8f, 0f)
            close()
        }
    } }

    val Charts: ImageVector by lazy { vec("Charts") {
        path(fill = SolidColor(Color.White)) {
            moveTo(4f, 19f); lineTo(20f, 19f); lineTo(20f, 21f); lineTo(4f, 21f); close()
            moveTo(5f, 17f); lineTo(5f, 11f); lineTo(8f, 11f); lineTo(8f, 17f); close()
            moveTo(10.5f, 17f); lineTo(10.5f, 7f); lineTo(13.5f, 7f); lineTo(13.5f, 17f); close()
            moveTo(16f, 17f); lineTo(16f, 13f); lineTo(19f, 13f); lineTo(19f, 17f); close()
        }
    } }

    val Logs: ImageVector by lazy { vec("Logs") {
        path(fill = SolidColor(Color.White)) {
            // Clock-list hybrid
            moveTo(12f, 3f)
            arcToRelative(9f, 9f, 0f, true, false, 9f, 9f)
            lineToRelative(-2f, 0f)
            arcToRelative(7f, 7f, 0f, true, true, -7f, -7f)
            close()
            moveTo(11f, 7f); lineTo(13f, 7f); lineTo(13f, 12.4f); lineTo(17f, 14.7f); lineTo(16f, 16.4f); lineTo(11f, 13.5f); close()
        }
    } }

    // ------------------------------------------------------------------
    // Bluetooth / Wi-Fi — path data sourced from Google's open-source
    // Material Icons (Apache 2.0). Translated 1:1 from the official 24×24
    // SVGs at github.com/google/material-design-icons.
    // ------------------------------------------------------------------

    val Bluetooth: ImageVector by lazy { vec("Bluetooth") {
        path(fill = SolidColor(Color.White)) {
            moveTo(17.71f, 7.71f)
            lineTo(12f, 2f)
            horizontalLineToRelative(-1f)
            verticalLineToRelative(7.59f)
            lineTo(6.41f, 5f)
            lineTo(5f, 6.41f)
            lineTo(10.59f, 12f)
            lineTo(5f, 17.59f)
            lineTo(6.41f, 19f)
            lineTo(11f, 14.41f)
            verticalLineTo(22f)
            horizontalLineToRelative(1f)
            lineToRelative(5.71f, -5.71f)
            lineToRelative(-4.3f, -4.29f)
            lineToRelative(4.3f, -4.29f)
            close()
            moveTo(13f, 5.83f)
            lineToRelative(1.88f, 1.88f)
            lineTo(13f, 9.59f)
            verticalLineTo(5.83f)
            close()
            moveToRelative(1.88f, 10.46f)
            lineTo(13f, 18.17f)
            verticalLineToRelative(-3.76f)
            lineToRelative(1.88f, 1.88f)
            close()
        }
    } }

    /** Official Material "bluetooth_disabled" — fragmented glyph, no slash. */
    val BluetoothOff: ImageVector by lazy { vec("BluetoothOff") {
        path(fill = SolidColor(Color.White)) {
            moveTo(13f, 5.83f)
            lineToRelative(1.88f, 1.88f)
            lineToRelative(-1.6f, 1.6f)
            lineToRelative(1.41f, 1.41f)
            lineToRelative(3.02f, -3.02f)
            lineTo(12f, 2f)
            horizontalLineToRelative(-1f)
            verticalLineToRelative(5.03f)
            lineToRelative(2f, 2f)
            verticalLineToRelative(-3.2f)
            close()
            moveTo(5.41f, 4f)
            lineTo(4f, 5.41f)
            lineTo(10.59f, 12f)
            lineTo(5f, 17.59f)
            lineTo(6.41f, 19f)
            lineTo(11f, 14.41f)
            verticalLineTo(22f)
            horizontalLineToRelative(1f)
            lineToRelative(4.29f, -4.29f)
            lineToRelative(2.3f, 2.29f)
            lineTo(20f, 18.59f)
            lineTo(5.41f, 4f)
            close()
            moveTo(13f, 18.17f)
            verticalLineToRelative(-3.76f)
            lineToRelative(1.88f, 1.88f)
            lineTo(13f, 18.17f)
            close()
        }
    } }

    val Wifi: ImageVector by lazy { vec("Wifi") {
        path(fill = SolidColor(Color.White)) {
            moveTo(1f, 9f)
            lineToRelative(2f, 2f)
            curveToRelative(4.97f, -4.97f, 13.03f, -4.97f, 18f, 0f)
            lineToRelative(2f, -2f)
            curveTo(16.93f, 2.93f, 7.08f, 2.93f, 1f, 9f)
            close()
            moveToRelative(8f, 8f)
            lineToRelative(3f, 3f)
            lineToRelative(3f, -3f)
            curveToRelative(-1.65f, -1.66f, -4.34f, -1.66f, -6f, 0f)
            close()
            moveToRelative(-4f, -4f)
            lineToRelative(2f, 2f)
            curveToRelative(2.76f, -2.76f, 7.24f, -2.76f, 10f, 0f)
            lineToRelative(2f, -2f)
            curveTo(15.14f, 9.14f, 8.87f, 9.14f, 5f, 13f)
            close()
        }
    } }

    val WifiOff: ImageVector by lazy { vec("WifiOff") {
        path(fill = SolidColor(Color.White)) {
            moveTo(22.99f, 9f)
            curveTo(19.15f, 5.16f, 13.8f, 3.76f, 8.84f, 4.78f)
            lineToRelative(2.52f, 2.52f)
            curveToRelative(3.47f, -0.17f, 6.99f, 1.05f, 9.63f, 3.7f)
            lineToRelative(2f, -2f)
            close()
            moveToRelative(-4f, 4f)
            curveToRelative(-1.29f, -1.29f, -2.84f, -2.13f, -4.49f, -2.56f)
            lineToRelative(3.53f, 3.53f)
            lineToRelative(0.96f, -0.97f)
            close()
            moveTo(2f, 3.05f)
            lineTo(5.07f, 6.1f)
            curveTo(3.6f, 6.82f, 2.22f, 7.78f, 1f, 9f)
            lineToRelative(1.99f, 2f)
            curveToRelative(1.24f, -1.24f, 2.67f, -2.16f, 4.2f, -2.77f)
            lineToRelative(2.24f, 2.24f)
            curveTo(7.81f, 10.89f, 6.27f, 11.73f, 5f, 13f)
            verticalLineToRelative(0.01f)
            lineTo(6.99f, 15f)
            curveToRelative(1.36f, -1.36f, 3.14f, -2.04f, 4.92f, -2.06f)
            lineTo(18.98f, 20f)
            lineToRelative(1.27f, -1.26f)
            lineTo(3.29f, 1.79f)
            lineTo(2f, 3.05f)
            close()
            moveTo(9f, 17f)
            lineToRelative(3f, 3f)
            lineToRelative(3f, -3f)
            curveToRelative(-1.65f, -1.66f, -4.34f, -1.66f, -6f, 0f)
            close()
        }
    } }

    val Search: ImageVector by lazy { vec("Search") {
        path(fill = SolidColor(Color.White)) {
            moveTo(15.5f, 14f)
            lineToRelative(-.79f, 0f)
            lineToRelative(-.28f, -.27f)
            arcToRelative(6.5f, 6.5f, 0f, true, false, -.7f, .7f)
            lineToRelative(.27f, .28f)
            lineToRelative(0f, .79f)
            lineToRelative(5f, 4.99f)
            lineTo(20.49f, 19f)
            close()
            moveTo(9.5f, 14f)
            arcTo(4.5f, 4.5f, 0f, true, true, 14f, 9.5f)
            arcTo(4.5f, 4.5f, 0f, false, true, 9.5f, 14f)
            close()
        }
    } }

    val Download: ImageVector by lazy { vec("Download") {
        path(fill = SolidColor(Color.White)) {
            moveTo(11f, 3f); lineTo(13f, 3f); lineTo(13f, 13.17f); lineTo(16.59f, 9.59f); lineTo(18f, 11f); lineTo(12f, 17f); lineTo(6f, 11f); lineTo(7.41f, 9.59f); lineTo(11f, 13.17f); close()
            moveTo(5f, 19f); lineTo(19f, 19f); lineTo(19f, 21f); lineTo(5f, 21f); close()
        }
    } }

    val MoreVert: ImageVector by lazy { vec("MoreVert") {
        path(fill = SolidColor(Color.White)) {
            moveTo(12f, 8f); arcToRelative(2f, 2f, 0f, true, true, 0f, -4f); arcToRelative(2f, 2f, 0f, true, true, 0f, 4f); close()
            moveTo(12f, 14f); arcToRelative(2f, 2f, 0f, true, true, 0f, -4f); arcToRelative(2f, 2f, 0f, true, true, 0f, 4f); close()
            moveTo(12f, 20f); arcToRelative(2f, 2f, 0f, true, true, 0f, -4f); arcToRelative(2f, 2f, 0f, true, true, 0f, 4f); close()
        }
    } }

    val ArrowBack: ImageVector by lazy { vec("ArrowBack") {
        path(fill = SolidColor(Color.White)) {
            moveTo(20f, 11f); lineTo(20f, 13f); lineTo(7.83f, 13f); lineTo(13.41f, 18.59f); lineTo(12f, 20f); lineTo(4f, 12f); lineTo(12f, 4f); lineTo(13.41f, 5.41f); lineTo(7.83f, 11f); close()
        }
    } }

    val Bolt: ImageVector by lazy { vec("Bolt") {
        path(fill = SolidColor(Color.White)) {
            moveTo(13f, 2f); lineTo(4f, 14f); lineTo(11f, 14f); lineTo(10f, 22f); lineTo(20f, 9f); lineTo(13f, 9f); close()
        }
    } }

    val Thermo: ImageVector by lazy { vec("Thermo") {
        path(fill = SolidColor(Color.White)) {
            moveTo(13f, 14.59f); lineTo(13f, 5f)
            arcToRelative(1f, 1f, 0f, false, false, -2f, 0f)
            lineTo(11f, 14.59f)
            arcToRelative(3.5f, 3.5f, 0f, true, false, 2f, 0f)
            close()
        }
    } }

    val Settings: ImageVector by lazy { vec("Settings") {
        // Gear / cog — 8-tooth, centered ring
        path(fill = SolidColor(Color.White)) {
            moveTo(19.14f, 12.94f)
            arcToRelative(7.49f, 7.49f, 0f, false, false, 0.05f, -0.94f)
            arcToRelative(7.49f, 7.49f, 0f, false, false, -0.05f, -0.94f)
            lineToRelative(2.03f, -1.58f)
            arcToRelative(.5f, .5f, 0f, false, false, .12f, -.64f)
            lineToRelative(-1.92f, -3.32f)
            arcToRelative(.5f, .5f, 0f, false, false, -.61f, -.22f)
            lineToRelative(-2.39f, .96f)
            arcToRelative(7.03f, 7.03f, 0f, false, false, -1.62f, -.94f)
            lineToRelative(-.36f, -2.54f)
            arcToRelative(.5f, .5f, 0f, false, false, -.5f, -.42f)
            lineToRelative(-3.84f, 0f)
            arcToRelative(.5f, .5f, 0f, false, false, -.5f, .42f)
            lineToRelative(-.36f, 2.54f)
            arcToRelative(7.03f, 7.03f, 0f, false, false, -1.62f, .94f)
            lineToRelative(-2.39f, -.96f)
            arcToRelative(.5f, .5f, 0f, false, false, -.61f, .22f)
            lineToRelative(-1.92f, 3.32f)
            arcToRelative(.5f, .5f, 0f, false, false, .12f, .64f)
            lineToRelative(2.03f, 1.58f)
            arcToRelative(7.49f, 7.49f, 0f, false, false, -.05f, .94f)
            arcToRelative(7.49f, 7.49f, 0f, false, false, .05f, .94f)
            lineToRelative(-2.03f, 1.58f)
            arcToRelative(.5f, .5f, 0f, false, false, -.12f, .64f)
            lineToRelative(1.92f, 3.32f)
            arcToRelative(.5f, .5f, 0f, false, false, .61f, .22f)
            lineToRelative(2.39f, -.96f)
            arcToRelative(7.03f, 7.03f, 0f, false, false, 1.62f, .94f)
            lineToRelative(.36f, 2.54f)
            arcToRelative(.5f, .5f, 0f, false, false, .5f, .42f)
            lineToRelative(3.84f, 0f)
            arcToRelative(.5f, .5f, 0f, false, false, .5f, -.42f)
            lineToRelative(.36f, -2.54f)
            arcToRelative(7.03f, 7.03f, 0f, false, false, 1.62f, -.94f)
            lineToRelative(2.39f, .96f)
            arcToRelative(.5f, .5f, 0f, false, false, .61f, -.22f)
            lineToRelative(1.92f, -3.32f)
            arcToRelative(.5f, .5f, 0f, false, false, -.12f, -.64f)
            close()
            // Inner hub cutout
            moveTo(12f, 15.5f)
            arcToRelative(3.5f, 3.5f, 0f, true, true, 0f, -7f)
            arcToRelative(3.5f, 3.5f, 0f, true, true, 0f, 7f)
            close()
        }
    } }

    val Home: ImageVector by lazy { vec("Home") {
        path(fill = SolidColor(Color.White)) {
            moveTo(12f, 3.5f); lineTo(3f, 11f); lineTo(5f, 11f); lineTo(5f, 20f)
            lineTo(10f, 20f); lineTo(10f, 14f); lineTo(14f, 14f); lineTo(14f, 20f)
            lineTo(19f, 20f); lineTo(19f, 11f); lineTo(21f, 11f); close()
        }
    } }

    val Chart: ImageVector by lazy { vec("Chart") {
        path(fill = SolidColor(Color.White)) {
            moveTo(4f, 19f); lineTo(20f, 19f); lineTo(20f, 21f); lineTo(4f, 21f); close()
            moveTo(5.5f, 17f); lineTo(5.5f, 11f); lineTo(8.5f, 11f); lineTo(8.5f, 17f); close()
            moveTo(10.5f, 17f); lineTo(10.5f, 7f); lineTo(13.5f, 7f); lineTo(13.5f, 17f); close()
            moveTo(15.5f, 17f); lineTo(15.5f, 13f); lineTo(18.5f, 13f); lineTo(18.5f, 17f); close()
        }
    } }

    val Data: ImageVector by lazy { vec("Data") {
        // Stack of horizontal rows (table rows silhouette)
        path(fill = SolidColor(Color.White)) {
            moveTo(4f, 5f); lineTo(20f, 5f); lineTo(20f, 7.5f); lineTo(4f, 7.5f); close()
            moveTo(4f, 9.5f); lineTo(20f, 9.5f); lineTo(20f, 12f); lineTo(4f, 12f); close()
            moveTo(4f, 14f); lineTo(20f, 14f); lineTo(20f, 16.5f); lineTo(4f, 16.5f); close()
            moveTo(4f, 18.5f); lineTo(20f, 18.5f); lineTo(20f, 21f); lineTo(4f, 21f); close()
        }
    } }

    val Grid: ImageVector by lazy { vec("Grid") {
        // 2x2 squares (settings/menu)
        path(fill = SolidColor(Color.White)) {
            moveTo(4f, 4f); lineTo(10.5f, 4f); lineTo(10.5f, 10.5f); lineTo(4f, 10.5f); close()
            moveTo(13.5f, 4f); lineTo(20f, 4f); lineTo(20f, 10.5f); lineTo(13.5f, 10.5f); close()
            moveTo(4f, 13.5f); lineTo(10.5f, 13.5f); lineTo(10.5f, 20f); lineTo(4f, 20f); close()
            moveTo(13.5f, 13.5f); lineTo(20f, 13.5f); lineTo(20f, 20f); lineTo(13.5f, 20f); close()
        }
    } }

    val Motorcycle: ImageVector by lazy { vec("Motorcycle") {
        // Google Material Icons "two_wheeler" (motorcycle), Apache 2.0. Parsed
        // verbatim from the official 24×24 SVG path so it matches Google's glyph
        // exactly (two ringed wheels + frame/handlebar) instead of the old
        // hand-drawn silhouette.
        addPath(
            pathData = PathParser().parsePathString(
                "M20,11c-0.18,0-0.36,0.03-0.53,0.05L17.41,9H20V6l-3.72,1.86L13.41,5H9v2h3.59l2,2H11l-4,2L5,9H0v2h4" +
                    "c-2.21,0-4,1.79-4,4c0,2.21,1.79,4,4,4c2.21,0,4-1.79,4-4l2,2h3l3.49-6.1l1.01,1.01" +
                    "C16.59,12.64,16,13.75,16,15c0,2.21,1.79,4,4,4c2.21,0,4-1.79,4-4C24,12.79,22.21,11,20,11z" +
                    "M4,17c-1.1,0-2-0.9-2-2c0-1.1,0.9-2,2-2c1.1,0,2,0.9,2,2C6,16.1,5.1,17,4,17z" +
                    "M20,17c-1.1,0-2-0.9-2-2c0-1.1,0.9-2,2-2s2,0.9,2,2C22,16.1,21.1,17,20,17z"
            ).toNodes(),
            fill = SolidColor(Color.White)
        )
    } }

    val Battery: ImageVector by lazy { vec("Battery") {
        // Modern EV cell — **vertical**: terminal on top, hollow rounded body,
        // lightning-bolt core. One EvenOdd path so the frame reads hollow and the
        // bolt as a solid core — single tint, geometric, futuristic.
        path(fill = SolidColor(Color.White), pathFillType = PathFillType.EvenOdd) {
            // Outer body (vertical rounded rectangle)
            moveTo(9f, 6f)
            lineTo(15f, 6f)
            arcToRelative(3f, 3f, 0f, false, true, 3f, 3f)
            lineTo(18f, 19f)
            arcToRelative(3f, 3f, 0f, false, true, -3f, 3f)
            lineTo(9f, 22f)
            arcToRelative(3f, 3f, 0f, false, true, -3f, -3f)
            lineTo(6f, 9f)
            arcToRelative(3f, 3f, 0f, false, true, 3f, -3f)
            close()
            // Inner cutout → hollow frame
            moveTo(9.3f, 7.5f)
            lineTo(14.7f, 7.5f)
            arcToRelative(1.8f, 1.8f, 0f, false, true, 1.8f, 1.8f)
            lineTo(16.5f, 18.7f)
            arcToRelative(1.8f, 1.8f, 0f, false, true, -1.8f, 1.8f)
            lineTo(9.3f, 20.5f)
            arcToRelative(1.8f, 1.8f, 0f, false, true, -1.8f, -1.8f)
            lineTo(7.5f, 9.3f)
            arcToRelative(1.8f, 1.8f, 0f, false, true, 1.8f, -1.8f)
            close()
            // Lightning-bolt core (solid)
            moveTo(12.5f, 8.5f)
            lineTo(9f, 14.2f)
            lineTo(11.2f, 14.2f)
            lineTo(10.5f, 19.5f)
            lineTo(15f, 12.8f)
            lineTo(12.6f, 12.8f)
            close()
            // Terminal (top nub)
            moveTo(10f, 6f)
            lineTo(10f, 3.5f)
            arcToRelative(1f, 1f, 0f, false, true, 1f, -1f)
            lineTo(13f, 2.5f)
            arcToRelative(1f, 1f, 0f, false, true, 1f, 1f)
            lineTo(14f, 6f)
            close()
        }
    } }

    val Plug: ImageVector by lazy { vec("Plug") {
        // EU-style plug head + cable
        path(fill = SolidColor(Color.White)) {
            // Prongs
            moveTo(9f, 2f); lineTo(11f, 2f); lineTo(11f, 6f); lineTo(9f, 6f); close()
            moveTo(13f, 2f); lineTo(15f, 2f); lineTo(15f, 6f); lineTo(13f, 6f); close()
            // Body
            moveTo(7f, 7f); lineTo(17f, 7f); lineTo(17f, 12f)
            arcToRelative(5f, 5f, 0f, false, true, -3f, 4.58f)
            lineTo(14f, 22f); lineTo(10f, 22f); lineTo(10f, 16.58f)
            arcToRelative(5f, 5f, 0f, false, true, -3f, -4.58f)
            close()
        }
    } }

    val InfoOutline: ImageVector by lazy { vec("InfoOutline") {
        // Simple "i" glyph rendered as a dot + stem; the surrounding ring is drawn
        // separately by the caller (info-button background), keeping this icon tint-safe.
        path(fill = SolidColor(Color.White)) {
            // dot
            moveTo(10.75f, 6.5f); lineTo(13.25f, 6.5f); lineTo(13.25f, 9f); lineTo(10.75f, 9f); close()
            // stem
            moveTo(10.75f, 10.5f); lineTo(13.25f, 10.5f); lineTo(13.25f, 18f); lineTo(10.75f, 18f); close()
        }
    } }

    val ChevronRight: ImageVector by lazy { vec("ChevronRight") {
        path(fill = SolidColor(Color.White)) {
            moveTo(9.3f, 6f); lineTo(15.3f, 12f); lineTo(9.3f, 18f); lineTo(7.9f, 16.6f); lineTo(12.5f, 12f); lineTo(7.9f, 7.4f); close()
        }
    } }

    val Fullscreen: ImageVector by lazy { vec("Fullscreen") {
        path(fill = SolidColor(Color.White)) {
            moveTo(7f, 14f); lineTo(5f, 14f); lineTo(5f, 19f); lineTo(10f, 19f); lineTo(10f, 17f); lineTo(7f, 17f); close()
            moveTo(5f, 10f); lineTo(7f, 10f); lineTo(7f, 7f); lineTo(10f, 7f); lineTo(10f, 5f); lineTo(5f, 5f); close()
            moveTo(17f, 17f); lineTo(14f, 17f); lineTo(14f, 19f); lineTo(19f, 19f); lineTo(19f, 14f); lineTo(17f, 14f); close()
            moveTo(14f, 5f); lineTo(14f, 7f); lineTo(17f, 7f); lineTo(17f, 10f); lineTo(19f, 10f); lineTo(19f, 5f); close()
        }
    } }

    val FullscreenExit: ImageVector by lazy { vec("FullscreenExit") {
        path(fill = SolidColor(Color.White)) {
            moveTo(5f, 16f); lineTo(8f, 16f); lineTo(8f, 19f); lineTo(10f, 19f); lineTo(10f, 14f); lineTo(5f, 14f); close()
            moveTo(8f, 8f); lineTo(5f, 8f); lineTo(5f, 10f); lineTo(10f, 10f); lineTo(10f, 5f); lineTo(8f, 5f); close()
            moveTo(14f, 19f); lineTo(16f, 19f); lineTo(16f, 16f); lineTo(19f, 16f); lineTo(19f, 14f); lineTo(14f, 14f); close()
            moveTo(16f, 8f); lineTo(16f, 5f); lineTo(14f, 5f); lineTo(14f, 10f); lineTo(19f, 10f); lineTo(19f, 8f); close()
        }
    } }

    val Speed: ImageVector by lazy { vec("Speed") {
        path(fill = SolidColor(Color.White)) {
            moveTo(12f, 16f)
            arcToRelative(2f, 2f, 0f, true, false, -2f, -2f)
            arcToRelative(2f, 2f, 0f, false, false, 2f, 2f)
            close()
            moveTo(15.04f, 8.95f); lineToRelative(1.42f, 1.42f); lineToRelative(-3.54f, 3.54f); lineToRelative(-1.42f, -1.42f); close()
            moveTo(12f, 4f)
            arcToRelative(8f, 8f, 0f, false, false, -7.07f, 11.74f)
            lineToRelative(1.78f, -1f)
            arcTo(6f, 6f, 0f, true, true, 17.29f, 14.74f)
            lineToRelative(1.78f, 1f)
            arcTo(8f, 8f, 0f, false, false, 12f, 4f)
            close()
        }
    } }

    // Trash can — translated 1:1 from Google's open-source Material Symbols
    // "delete" 24x24 (Apache 2.0).
    val Trash: ImageVector by lazy { vec("Trash") {
        path(fill = SolidColor(Color.White)) {
            moveTo(7f, 21f)
            quadToRelative(-0.825f, 0f, -1.412f, -0.587f)
            quadTo(5f, 19.825f, 5f, 19f)
            lineTo(5f, 6f)
            lineTo(4f, 6f)
            lineTo(4f, 4f)
            horizontalLineToRelative(5f)
            lineTo(9f, 3f)
            horizontalLineToRelative(6f)
            verticalLineToRelative(1f)
            horizontalLineToRelative(5f)
            verticalLineToRelative(2f)
            horizontalLineToRelative(-1f)
            verticalLineToRelative(13f)
            quadToRelative(0f, 0.825f, -0.587f, 1.413f)
            quadTo(17.825f, 21f, 17f, 21f)
            close()
            moveTo(7f, 6f)
            verticalLineToRelative(13f)
            horizontalLineToRelative(10f)
            lineTo(17f, 6f)
            close()
            moveTo(9f, 17f)
            horizontalLineToRelative(2f)
            lineTo(11f, 8f)
            lineTo(9f, 8f)
            close()
            moveTo(13f, 17f)
            horizontalLineToRelative(2f)
            lineTo(15f, 8f)
            horizontalLineToRelative(-2f)
            close()
        }
    } }

    // ------------------------------------------------------------------
    // Telltale / warning lamps — driven by VotolTelemetry `flags`/`faultCode`.
    // Each uses EvenOdd fill so the exclamation reads as a punched hole,
    // keeping the glyph a single tint-able shape (see TelltaleRow).
    // ------------------------------------------------------------------

    /** Warning triangle with exclamation — controller fault / MIL. */
    val Warning: ImageVector by lazy { vec("Warning") {
        path(fill = SolidColor(Color.White), pathFillType = PathFillType.EvenOdd) {
            // Triangle body.
            moveTo(12f, 3f); lineTo(22f, 20.5f); lineTo(2f, 20.5f); close()
            // Exclamation stem (hole).
            moveTo(11f, 9.5f); lineTo(13f, 9.5f); lineTo(13f, 15f); lineTo(11f, 15f); close()
            // Exclamation dot (hole).
            moveTo(11f, 16.5f); lineTo(13f, 16.5f); lineTo(13f, 18.5f); lineTo(11f, 18.5f); close()
        }
    } }

    /** Filled disc with exclamation — brake warning lamp. */
    val Brake: ImageVector by lazy { vec("Brake") {
        path(fill = SolidColor(Color.White), pathFillType = PathFillType.EvenOdd) {
            // Disc (two semicircle arcs).
            moveTo(21f, 12f)
            arcToRelative(9f, 9f, 0f, true, true, -18f, 0f)
            arcToRelative(9f, 9f, 0f, true, true, 18f, 0f)
            close()
            // Exclamation stem (hole).
            moveTo(11f, 6.5f); lineTo(13f, 6.5f); lineTo(13f, 13f); lineTo(11f, 13f); close()
            // Exclamation dot (hole).
            moveTo(11f, 15f); lineTo(13f, 15f); lineTo(13f, 17f); lineTo(11f, 17f); close()
        }
    } }

    // ------------------------------------------------------------------
    // Home-screen glyphs — notifications, rename, health + summary tiles.
    // Material paths (Apache 2.0) where noted; stroke-drawn where a thin
    // line reads better than a filled silhouette.
    // ------------------------------------------------------------------

    /** Notification bell — Material "notifications". */
    val Bell: ImageVector by lazy { vec("Bell") {
        path(fill = SolidColor(Color.White)) {
            moveTo(12f, 22f)
            curveToRelative(1.1f, 0f, 2f, -0.9f, 2f, -2f)
            horizontalLineToRelative(-4f)
            curveToRelative(0f, 1.1f, 0.89f, 2f, 2f, 2f)
            close()
            moveTo(18f, 16f)
            verticalLineToRelative(-5f)
            curveToRelative(0f, -3.07f, -1.64f, -5.64f, -4.5f, -6.32f)
            verticalLineTo(4f)
            curveToRelative(0f, -0.83f, -0.67f, -1.5f, -1.5f, -1.5f)
            reflectiveCurveToRelative(-1.5f, 0.67f, -1.5f, 1.5f)
            verticalLineToRelative(0.68f)
            curveTo(7.63f, 5.36f, 6f, 7.92f, 6f, 11f)
            verticalLineToRelative(5f)
            lineToRelative(-2f, 2f)
            verticalLineToRelative(1f)
            horizontalLineToRelative(16f)
            verticalLineToRelative(-1f)
            lineToRelative(-2f, -2f)
            close()
        }
    } }

    /** Pencil — Material "edit". */
    val Edit: ImageVector by lazy { vec("Edit") {
        path(fill = SolidColor(Color.White)) {
            moveTo(3f, 17.25f)
            verticalLineTo(21f)
            horizontalLineToRelative(3.75f)
            lineTo(17.81f, 9.94f)
            lineToRelative(-3.75f, -3.75f)
            lineTo(3f, 17.25f)
            close()
            moveTo(20.71f, 7.04f)
            curveToRelative(0.39f, -0.39f, 0.39f, -1.02f, 0f, -1.41f)
            lineToRelative(-2.34f, -2.34f)
            curveToRelative(-0.39f, -0.39f, -1.02f, -0.39f, -1.41f, 0f)
            lineToRelative(-1.83f, 1.83f)
            lineToRelative(3.75f, 3.75f)
            lineToRelative(1.83f, -1.83f)
            close()
        }
    } }

    /** Shield with a check punched out — controller health. */
    val Shield: ImageVector by lazy { vec("Shield") {
        path(fill = SolidColor(Color.White), pathFillType = PathFillType.EvenOdd) {
            moveTo(12f, 2f)
            lineTo(4f, 5f)
            verticalLineToRelative(6f)
            curveToRelative(0f, 5f, 3.4f, 9.6f, 8f, 11f)
            curveToRelative(4.6f, -1.4f, 8f, -6f, 8f, -11f)
            verticalLineTo(5f)
            close()
            // check (hole)
            moveTo(10.6f, 15.6f)
            lineTo(7.4f, 12.4f)
            lineTo(8.8f, 11f)
            lineTo(10.6f, 12.8f)
            lineTo(15.2f, 8.2f)
            lineTo(16.6f, 9.6f)
            close()
        }
    } }

    /** Processor / chip — system health. */
    val Cpu: ImageVector by lazy { vec("Cpu") {
        path(fill = SolidColor(Color.White), pathFillType = PathFillType.EvenOdd) {
            moveTo(7f, 7f); horizontalLineToRelative(10f); verticalLineToRelative(10f); horizontalLineToRelative(-10f); close()
            moveTo(10f, 10f); horizontalLineToRelative(4f); verticalLineToRelative(4f); horizontalLineToRelative(-4f); close()
        }
        path(fill = SolidColor(Color.White)) {
            moveTo(9f, 4f); horizontalLineToRelative(1.5f); verticalLineToRelative(2f); horizontalLineToRelative(-1.5f); close()
            moveTo(13.5f, 4f); horizontalLineToRelative(1.5f); verticalLineToRelative(2f); horizontalLineToRelative(-1.5f); close()
            moveTo(9f, 18f); horizontalLineToRelative(1.5f); verticalLineToRelative(2f); horizontalLineToRelative(-1.5f); close()
            moveTo(13.5f, 18f); horizontalLineToRelative(1.5f); verticalLineToRelative(2f); horizontalLineToRelative(-1.5f); close()
            moveTo(4f, 9f); horizontalLineToRelative(2f); verticalLineToRelative(1.5f); horizontalLineToRelative(-2f); close()
            moveTo(4f, 13.5f); horizontalLineToRelative(2f); verticalLineToRelative(1.5f); horizontalLineToRelative(-2f); close()
            moveTo(18f, 9f); horizontalLineToRelative(2f); verticalLineToRelative(1.5f); horizontalLineToRelative(-2f); close()
            moveTo(18f, 13.5f); horizontalLineToRelative(2f); verticalLineToRelative(1.5f); horizontalLineToRelative(-2f); close()
        }
    } }

    /** Heartbeat / activity line — error-state tile. */
    val Pulse: ImageVector by lazy { vec("Pulse") {
        path(
            stroke = SolidColor(Color.White),
            strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round
        ) {
            moveTo(3f, 12f); lineTo(8f, 12f); lineTo(10.5f, 6f); lineTo(13.5f, 18f); lineTo(16f, 12f); lineTo(21f, 12f)
        }
    } }

    /** Perspective road — range / distance tiles. */
    val Road: ImageVector by lazy { vec("Road") {
        path(fill = SolidColor(Color.White)) {
            moveTo(6f, 4f); lineTo(8f, 4f); lineTo(6.5f, 20f); lineTo(4f, 20f); close()
            moveTo(16f, 4f); lineTo(18f, 4f); lineTo(20f, 20f); lineTo(17.5f, 20f); close()
            moveTo(11.25f, 4f); lineTo(12.75f, 4f); lineTo(12.6f, 7f); lineTo(11.4f, 7f); close()
            moveTo(11.15f, 9f); lineTo(12.85f, 9f); lineTo(12.7f, 13f); lineTo(11.3f, 13f); close()
            moveTo(11.05f, 15f); lineTo(12.95f, 15f); lineTo(12.8f, 20f); lineTo(11.2f, 20f); close()
        }
    } }

    /** Circular refresh arrow — "last sync". Material "refresh". */
    val Refresh: ImageVector by lazy { vec("Refresh") {
        path(fill = SolidColor(Color.White)) {
            moveTo(17.65f, 6.35f)
            curveTo(16.2f, 4.9f, 14.21f, 4f, 12f, 4f)
            curveToRelative(-4.42f, 0f, -7.99f, 3.58f, -7.99f, 8f)
            reflectiveCurveToRelative(3.57f, 8f, 7.99f, 8f)
            curveToRelative(3.73f, 0f, 6.84f, -2.55f, 7.73f, -6f)
            horizontalLineToRelative(-2.08f)
            curveToRelative(-0.82f, 2.33f, -3.04f, 4f, -5.65f, 4f)
            curveToRelative(-3.31f, 0f, -6f, -2.69f, -6f, -6f)
            reflectiveCurveToRelative(2.69f, -6f, 6f, -6f)
            curveToRelative(1.66f, 0f, 3.14f, 0.69f, 4.22f, 1.78f)
            lineTo(13f, 11f)
            horizontalLineToRelative(7f)
            verticalLineTo(4f)
            lineToRelative(-2.35f, 2.35f)
            close()
        }
    } }

    /** Stopwatch — trip duration. Material "timer". */
    val Timer: ImageVector by lazy { vec("Timer") {
        path(fill = SolidColor(Color.White), pathFillType = PathFillType.EvenOdd) {
            moveTo(15f, 1f); horizontalLineTo(9f); verticalLineToRelative(2f); horizontalLineToRelative(6f); close()
            moveTo(11f, 14f); horizontalLineToRelative(2f); verticalLineTo(8f); horizontalLineToRelative(-2f); close()
            moveTo(19.03f, 7.39f)
            lineToRelative(1.42f, -1.42f)
            curveToRelative(-0.43f, -0.51f, -0.9f, -0.99f, -1.41f, -1.41f)
            lineToRelative(-1.42f, 1.42f)
            curveTo(16.07f, 4.74f, 14.12f, 4f, 12f, 4f)
            curveToRelative(-4.97f, 0f, -9f, 4.03f, -9f, 9f)
            reflectiveCurveToRelative(4.02f, 9f, 9f, 9f)
            reflectiveCurveToRelative(9f, -4.03f, 9f, -9f)
            curveToRelative(0f, -2.12f, -0.74f, -4.07f, -1.97f, -5.61f)
            close()
            moveTo(12f, 20f)
            curveToRelative(-3.87f, 0f, -7f, -3.13f, -7f, -7f)
            reflectiveCurveToRelative(3.13f, -7f, 7f, -7f)
            reflectiveCurveToRelative(7f, 3.13f, 7f, 7f)
            reflectiveCurveToRelative(-3.13f, 7f, -7f, 7f)
            close()
        }
    } }

    /** Chevron pointing right, in a lighter weight than [ChevronRight]. */
    val ArrowForward: ImageVector by lazy { vec("ArrowForward") {
        path(fill = SolidColor(Color.White)) {
            moveTo(4f, 11f); lineTo(16.17f, 11f); lineTo(10.59f, 5.41f); lineTo(12f, 4f); lineTo(20f, 12f); lineTo(12f, 20f); lineTo(10.59f, 18.59f); lineTo(16.17f, 13f); lineTo(4f, 13f); close()
        }
    } }
}

private inline fun vec(
    name: String,
    block: ImageVector.Builder.() -> Unit
): ImageVector = ImageVector.Builder(
    name = name,
    defaultWidth = 24.dp,
    defaultHeight = 24.dp,
    viewportWidth = 24f,
    viewportHeight = 24f
).apply(block).build()
