package com.innodrive.evdash.data.sharing

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File

/**
 * Wraps the share-sheet intent for exported trip files.
 *
 * Uses [FileProvider] with the authority declared in the manifest so the
 * recipient app gets a temporary read URI grant — no need for legacy
 * file:// URIs or external storage write permission.
 */
object ShareHelper {

    /**
     * Launches the system share sheet for [file].
     *
     * @param subject email-style subject (used by Gmail, etc.)
     * @param mimeType MIME — defaults to "text/csv" for CSVs.
     */
    fun shareFile(
        context: Context,
        file: File,
        subject: String = "EV Trip Telemetry",
        bodyText: String? = null,
        mimeType: String = "text/csv"
    ) {
        val authority = "${context.packageName}.fileprovider"
        val uri = FileProvider.getUriForFile(context, authority, file)

        val send = Intent(Intent.ACTION_SEND).apply {
            type = mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, subject)
            if (bodyText != null) putExtra(Intent.EXTRA_TEXT, bodyText)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        val chooser = Intent.createChooser(send, "Share trip CSV").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(chooser)
    }

    /**
     * Opens [uri] in a viewer (Files / Sheets / a text app) via `ACTION_VIEW`.
     * Used by the "Open" action after a CSV is saved to Downloads. Best-effort:
     * on a device with no app that handles `text/csv`, the launch is swallowed
     * rather than crashing.
     */
    fun openFile(context: Context, uri: Uri, mimeType: String = "text/csv") {
        val view = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mimeType)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val chooser = Intent.createChooser(view, "Open trip CSV").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { context.startActivity(chooser) }
            .onFailure { android.util.Log.w("ShareHelper", "No app to open $mimeType", it) }
    }
}
