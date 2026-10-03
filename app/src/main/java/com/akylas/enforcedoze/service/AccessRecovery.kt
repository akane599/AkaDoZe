package com.akylas.enforcedoze.service

import com.akylas.enforcedoze.access.AccessLevel
import com.akylas.enforcedoze.access.CapabilityResolver
import com.akylas.enforcedoze.access.FeatureStatus
import com.akylas.enforcedoze.access.Grants
import com.akylas.enforcedoze.doze.RestoreLedger

object AccessRecovery {
    /** Same typed event path for service callbacks and detached restore workers. */
    @JvmStatic
    fun announce(state: com.akylas.enforcedoze.access.AccessState,
        sink: com.akylas.enforcedoze.doze.DozeEventSink, debt: Runnable) {
        if (!state.resolved) return
        sink.emit(com.akylas.enforcedoze.doze.DozeEvent(com.akylas.enforcedoze.doze.EventType.ACCESS_CHANGED,
            state.level.name + if (state.level < AccessLevel.SHELL) " NO_ACCESS" else ""))
        if (state.level < AccessLevel.SHELL) debt.run()
    }

    /** DUMP/WSS features can still be restored by the app; shell/root entries remain access debt. */
    @JvmStatic
    fun hasShellDebt(ledger: RestoreLedger, apiLevel: Int): Boolean = ledger.entries.any {
        CapabilityResolver.status(it.feature, AccessLevel.APP, it.apiLevel ?: apiLevel,
            Grants(true, true)) != FeatureStatus.Available
    }
}
