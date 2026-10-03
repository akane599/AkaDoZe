package com.akylas.enforcedoze.monitor

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Looper
import androidx.core.content.FileProvider
import java.io.File

/** Compile-verified Android adapter. Export must run on a background thread. */
class ReportExporter(context: Context) {
    private val context = context.applicationContext

    fun export(report: String): Uri {
        check(Looper.myLooper() != Looper.getMainLooper()) { "Report export requires a background thread" }
        val directory = File(context.cacheDir, "reports")
        check(directory.isDirectory || directory.mkdirs()) { "Cannot create report directory" }
        val file = File.createTempFile("doze-report-", ".txt", directory)
        try {
            file.writeText(report, Charsets.UTF_8)
            return FileProvider.getUriForFile(context, "${context.packageName}.reports", file)
        } catch (failure: Exception) {
            file.delete()
            throw failure
        }
    }

    fun shareIntent(uri: Uri): Intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_STREAM, uri)
        clipData = ClipData.newRawUri("Doze report", uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
}
