package com.akylas.enforcedoze.access

object Prefs {
    const val EXECUTION_MODE = "executionMode"
    const val MODE_ROOT = "root"
    const val MODE_SHIZUKU = "shizuku"
    const val DEFAULT_EXECUTION_MODE = MODE_ROOT
    const val KEEP_DOZE_ENFORCED = "keepDozeEnforced"
    const val DEFAULT_KEEP_DOZE_ENFORCED = true
    const val ALLOW_EXTERNAL_BASIC_CONTROL = "allowExternalBasicControl"
    const val DEFAULT_ALLOW_EXTERNAL_BASIC_CONTROL = true
    const val ALLOW_EXTERNAL_PRIVILEGED_CONTROL = "allowExternalPrivilegedControl"
    const val DEFAULT_ALLOW_EXTERNAL_PRIVILEGED_CONTROL = false
    const val SCREEN_ON_SUMMARY = "screenOnSummary"
    const val DEFAULT_SCREEN_ON_SUMMARY = false
    const val RESTORE_LEDGER = "restoreLedger"
    const val DEFAULT_RESTORE_LEDGER = ""
    const val RESTRICT_SENSORS_ALLOW_TOKEN = "restrictSensorsAllowToken"
    const val DEFAULT_RESTRICT_SENSORS_ALLOW_TOKEN = "com.akylas.enforcedoze"
    const val SERVICE_ENABLED = "serviceEnabled"
    const val DISABLE_MOTION_SENSORS = "disableMotionSensors"
}
