package com.example.displayapp.data.sharing

import android.content.Context
import android.content.Intent
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
}
