package com.akylas.enforcedoze.service

import android.content.Context
import com.akylas.enforcedoze.access.Prefs

/** Lightweight receiver preflight: never constructs AccessManager or the runtime for an empty ledger. */
object BootRestore {
    @JvmStatic
    fun hasPending(context: Context): Boolean = try {
        val prefs = context.getSharedPreferences("doze_ledger", Context.MODE_PRIVATE)
        BootRestorePolicy.shouldRestore(false,
            prefs.getString(Prefs.RESTORE_LEDGER, "").orEmpty(),
            prefs.getString("restoreLedgerCorruptLines", "").orEmpty())
    } catch (_: Exception) {
        // Unreadable restoration intent must be retained and checked, not treated as an empty ledger.
        true
    }
}
