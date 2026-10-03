package com.akylas.enforcedoze.service

import com.akylas.enforcedoze.access.AccessLevel

/** Forward sessions and their self-tests need shell access; recovery keeps per-feature capabilities. */
object SessionAccess {
    @JvmStatic
    fun canRunSessions(level: AccessLevel): Boolean = level >= AccessLevel.SHELL
}
