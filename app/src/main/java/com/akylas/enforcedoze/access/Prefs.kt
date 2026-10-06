package com.akylas.enforcedoze.access

object Prefs {
    // Device-local grant attempts: excluded from backup/transfer and cleared on reset.
    const val HELPER_GRANTS = "helper_grants"
    const val APPLIED_HELPERS = "appliedHelpers"
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
    const val RESTRICT_SENSORS_ALLOW_TOKEN = "sensorWhitelistPackage"
    const val DEFAULT_RESTRICT_SENSORS_ALLOW_TOKEN = "com.akylas.enforcedoze"
    const val SERVICE_ENABLED = "serviceEnabled"
    const val SERVICE_USER_ENABLED = "serviceUserEnabled"
    // Preserve existing installs' schedule behavior until an explicit user toggle.
    const val DEFAULT_SERVICE_USER_ENABLED = true
    const val DISABLE_MOTION_SENSORS = "disableMotionSensors"
    const val TURN_OFF_WIFI = "turnOffWiFiInDoze"
    const val TURN_OFF_DATA = "turnOffDataInDoze"
    const val TURN_OFF_BLUETOOTH = "turnOffBluetoothInDoze"
    const val TURN_OFF_LOCATION = "turnOffGPSInDoze"
    const val TURN_ON_AIRPLANE = "turnOnAirplaneInDoze"
    const val TURN_ON_BATTERY_SAVER = "turnOnBatterySaverInDoze"
    const val TURN_OFF_BIOMETRICS = "turnOffBiometricsInDoze"
    const val TURN_OFF_ALL_SENSORS = "turnOffAllSensorsInDoze"
    const val APP_BLOCKLIST = "dozeAppBlockList"
    const val NOTIFICATION_BLOCKLIST = "notificationBlockList"
}
