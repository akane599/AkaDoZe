package com.akylas.enforcedoze.service

import android.content.Context
import com.akylas.enforcedoze.access.Prefs

/** Lightweight receiver preflight: runtime construction stays inside the admitted callback. */
object BootRestore {
    @JvmStatic
    fun restoreIfPending(context: Context, restore: Runnable): Boolean {
        val snapshot = readSnapshot(context)
        return BootRestorePolicy.restoreIfPending(snapshot?.first, snapshot?.second, restore)
    }

    private fun readSnapshot(context: Context): Pair<String, String>? = try {
        val prefs = context.getSharedPreferences("doze_ledger", Context.MODE_PRIVATE)
        prefs.getString(Prefs.RESTORE_LEDGER, "").orEmpty() to
            prefs.getString("restoreLedgerCorruptLines", "").orEmpty()
    } catch (_: Exception) {
        // Unreadable restoration intent must be retained and checked, not treated as an empty ledger.
        null
    }
}
