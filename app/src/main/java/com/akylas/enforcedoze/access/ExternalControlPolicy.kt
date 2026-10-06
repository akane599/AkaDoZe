package com.akylas.enforcedoze.access

/** Pure trust/input policy. An allow decision is not evidence that the requested work succeeded. */
object ExternalControlPolicy {
    enum class Action(val privileged: Boolean) {
        ENABLE_SERVICE(false), DISABLE_SERVICE(false), REAPPLY_DOZE(false),
        ADD_WHITELIST(true), REMOVE_WHITELIST(true), CHANGE_SETTING(true),
    }

    enum class DenialReason {
        BASIC_CONTROL_DISABLED, PRIVILEGED_CONTROL_DISABLED, PROTECTED_SETTING,
        UNKNOWN_SETTING, MISSING_VALUE, INVALID_BOOLEAN, INVALID_INTEGER, OUT_OF_RANGE,
        INVALID_PACKAGE, PACKAGE_NOT_INSTALLED, INVALID_EXTRA, UNVERIFIED_SETTING_VALUE,
        INTERNAL_ERROR,
    }

    enum class SettingType { BOOLEAN, INTEGER }

    sealed interface SettingValue {
        data class BooleanValue(val value: Boolean) : SettingValue
        data class IntegerValue(val value: Int) : SettingValue
    }

    data class Decision(val reason: DenialReason? = null, val value: SettingValue? = null) {
        val allowed: Boolean get() = reason == null
    }

    // Only scalar, non-security preferences. No arbitrary keys, lists, grants or ledger state.
    // Names/types mirror prefs.xml; dozeEnterDelay is seconds, with the XML's 0..1800 bounds.
    private val booleanSettings = setOf(
        "disableWhenCharging", "autoRotateAndBrightnessFix", "showPersistentNotif",
        "showDisabledNotification", "ignoreLockscreenTimeout", "turnOffWiFiInDoze",
        "turnOffDataInDoze", "turnOnAirplaneInDoze", "turnOffBluetoothInDoze",
        "turnOffGPSInDoze", "ignoreIfHotspot", "whitelistMusicAppNetwork", "whitelistCurrentApp",
        Prefs.DISABLE_MOTION_SENSORS, "turnOffAllSensorsInDoze", "turnOffBiometricsInDoze",
        "turnOnBatterySaverInDoze", "disableStats", Prefs.KEEP_DOZE_ENFORCED, Prefs.SCREEN_ON_SUMMARY,
    )
    private val protectedSettings = setOf(
        Prefs.ALLOW_EXTERNAL_BASIC_CONTROL, Prefs.ALLOW_EXTERNAL_PRIVILEGED_CONTROL,
        Prefs.RESTORE_LEDGER, Prefs.EXECUTION_MODE, Prefs.RESTRICT_SENSORS_ALLOW_TOKEN,
        Prefs.SERVICE_ENABLED, "isSuAvailable", "isShizukuAvailable", "disableLogcat", "waitForUnlock",
    )

    @JvmStatic
    fun settingType(key: String?): SettingType? = when {
        key in booleanSettings -> SettingType.BOOLEAN
        key == "dozeEnterDelay" -> SettingType.INTEGER
        else -> null
    }

    @JvmStatic
    @JvmOverloads
    fun evaluate(
        action: Action,
        allowBasic: Boolean = Prefs.DEFAULT_ALLOW_EXTERNAL_BASIC_CONTROL,
        allowPrivileged: Boolean = Prefs.DEFAULT_ALLOW_EXTERNAL_PRIVILEGED_CONTROL,
        settingName: String? = null,
        settingValue: String? = null,
        packageName: String? = null,
    ): Decision {
        if (action.privileged && !allowPrivileged) return Decision(DenialReason.PRIVILEGED_CONTROL_DISABLED)
        if (!action.privileged && !allowBasic) return Decision(DenialReason.BASIC_CONTROL_DISABLED)
        return when (action) {
            Action.CHANGE_SETTING -> validateSetting(settingName, settingValue)
            Action.ADD_WHITELIST, Action.REMOVE_WHITELIST -> if (packageName != null && PackageNames.isValid(packageName)) {
                Decision()
            } else Decision(DenialReason.INVALID_PACKAGE)
            else -> Decision()
        }
    }

    /** AOSP whitelist rows only. Empty/error/OEM output is unknown, never verified removal. */
    @JvmStatic
    fun whitelistMembership(lines: List<String>, target: String): Boolean? {
        if (lines.isEmpty() || !PackageNames.isValid(target)) return null
        var found = false
        var parsedRows = 0
        for (line in lines) {
            if (line.isBlank()) continue
            val row = WhitelistRow.parse(line) ?: return null
            parsedRows++
            if (row.packageName == target && row.deepDozeMember) found = true
        }
        return if (parsedRows == 0) null else found
    }

    private fun validateSetting(key: String?, text: String?): Decision {
        if (key in protectedSettings) return Decision(DenialReason.PROTECTED_SETTING)
        val type = settingType(key) ?: return Decision(DenialReason.UNKNOWN_SETTING)
        if (text == null) return Decision(DenialReason.MISSING_VALUE)
        return when (type) {
            SettingType.BOOLEAN -> when {
                text.equals("true", ignoreCase = true) -> Decision(value = SettingValue.BooleanValue(true))
                text.equals("false", ignoreCase = true) -> Decision(value = SettingValue.BooleanValue(false))
                else -> Decision(DenialReason.INVALID_BOOLEAN)
            }
            SettingType.INTEGER -> {
                val number = text.toIntOrNull() ?: return Decision(DenialReason.INVALID_INTEGER)
                if (number !in 0..1800) Decision(DenialReason.OUT_OF_RANGE)
                else Decision(value = SettingValue.IntegerValue(number))
            }
        }
    }
}
