package com.akylas.enforcedoze.service

import com.akylas.enforcedoze.access.AccessLevel
import com.akylas.enforcedoze.access.CapabilityResolver
import com.akylas.enforcedoze.access.FeatureStatus
import com.akylas.enforcedoze.access.Grants
import com.akylas.enforcedoze.doze.RestoreLedger

object AccessRecovery {
    /** DUMP/WSS features can still be restored by the app; shell/root entries remain access debt. */
    @JvmStatic
    fun hasShellDebt(ledger: RestoreLedger, apiLevel: Int): Boolean = ledger.entries.any {
        CapabilityResolver.status(it.feature, AccessLevel.APP, it.apiLevel ?: apiLevel,
            Grants(true, true)) != FeatureStatus.Available
    }
}
