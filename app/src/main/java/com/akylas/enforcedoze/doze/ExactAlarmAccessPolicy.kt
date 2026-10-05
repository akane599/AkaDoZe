package com.akylas.enforcedoze.doze

/** A fresh capability/intent snapshot; no cached grant or apply-current-window decision. */
object ExactAlarmAccessPolicy {
    data class Access(val exactAllowed: Boolean, val shouldRearm: Boolean)

    /** Denied exact access still permits the existing best-effort inexact boundary alarm. */
    @JvmStatic
    fun requery(
        apiLevel: Int,
        actualExactAccess: Boolean,
        userEnabled: Boolean,
        hasPeriods: Boolean,
    ): Access = Access(
        exactAllowed = apiLevel < 31 || actualExactAccess,
        shouldRearm = userEnabled && hasPeriods,
    )
}
