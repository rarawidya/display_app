package com.example.displayapp.data.format

import com.example.displayapp.domain.model.TimeFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Centralized time / date / datetime formatters.
 *
 * Reads the user's [TimeFormat] preference so trip stamps and ETA labels honor
 * 12-hour vs 24-hour across the whole app.
 *
 * SimpleDateFormat instances are cached per pattern — building them is cheap
 * but not free, and these methods get called inside LazyColumn item composition.
 */
object Formatters {

    private val cache = mutableMapOf<String, SimpleDateFormat>()

    private fun fmt(pattern: String): SimpleDateFormat =
        cache.getOrPut(pattern) { SimpleDateFormat(pattern, Locale.getDefault()) }

    /** "14:33" (24h) or "2:33 PM" (12h). */
    fun time(epochMs: Long, format: TimeFormat): String =
        fmt(if (format == TimeFormat.H12) "h:mm a" else "HH:mm").format(Date(epochMs))

    /** "Mar 12" — date-only, format-independent. */
    fun shortDate(epochMs: Long): String =
        fmt("MMM d").format(Date(epochMs))

    /** "Mar 12 · 14:33" or "Mar 12 · 2:33 PM". */
    fun dateTime(epochMs: Long, format: TimeFormat): String =
        "${shortDate(epochMs)} · ${time(epochMs, format)}"

    /** "Mar 12, 2026 · 14:33" — long form used on the Trip Detail header. */
    fun longDateTime(epochMs: Long, format: TimeFormat): String {
        val date = fmt("MMM d, yyyy").format(Date(epochMs))
        return "$date · ${time(epochMs, format)}"
    }
}
