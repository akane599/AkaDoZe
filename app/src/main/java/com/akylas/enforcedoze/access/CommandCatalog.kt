package com.akylas.enforcedoze.access

/** Pure command construction. Null means no supported command, not successful execution. */
object CommandCatalog {
    private const val POST_NOTIFICATIONS = "android.permission.POST_NOTIFICATIONS"
    private const val DOZE_PROPERTY = "persist.sys.doze_powersave"
    private val tunable = Regex("[A-Za-z][A-Za-z0-9_]*=-?[0-9]+(\\.[0-9]+)?")

    /** Apply the screen-off behaviour (radios/location/biometrics off, other features on). */
    @JvmStatic
    @JvmOverloads
    fun apply(feature: Feature, apiLevel: Int, target: String? = null, value: String? = null): List<String>? {
        if (target != null) PackageNames.requireValid(target)
        if (apiLevel < 23) return null
        if (feature == Feature.TUNABLES) return value?.let { tunables(it) }
        val enabled = feature !in setOf(
            Feature.WIFI, Feature.MOBILE_DATA, Feature.BLUETOOTH, Feature.LOCATION, Feature.BIOMETRICS,
        )
        return setEnabled(feature, apiLevel, enabled, target)
    }

    /** Restore a previously read value; never infer the original state from preferences. */
    @JvmStatic
    @JvmOverloads
    fun restore(feature: Feature, apiLevel: Int, originalValue: String, target: String? = null): List<String>? {
        if (target != null) PackageNames.requireValid(target)
        if (apiLevel < 23) return null
        return when (feature) {
            Feature.TUNABLES -> if (originalValue == "null") {
                listOf("settings delete global device_idle_constants")
            } else tunables(originalValue)
            Feature.MOTION_SENSORS -> when {
                originalValue == "NORMAL" -> listOf("dumpsys sensorservice enable")
                originalValue.startsWith("RESTRICTED:") -> listOf(
                    "dumpsys sensorservice restrict ${PackageNames.requireValid(originalValue.substringAfter(':'))}",
                )
                else -> null
            }
            // Grant AND flags must be captured; use restoreNotification for the structured snapshot.
            Feature.NOTIFICATION_BLOCK -> null
            Feature.PM_DISABLE -> {
                val state = mapOf("0" to "default-state", "1" to "enable", "2" to "disable",
                    "3" to "disable-user", "4" to "disable-until-used")[originalValue] ?: return null
                listOf("pm $state ${PackageNames.requireValid(target)}")
            }
            Feature.LOCATION -> if (apiLevel < 30) {
                require(originalValue in listOf("0", "1", "2", "3")) { "Invalid location mode" }
                listOf("settings put secure location_mode $originalValue")
            } else setEnabled(feature, apiLevel, booleanValue(originalValue), target)
            Feature.FORCE_DOZE, Feature.DOZE_STATE_READ, Feature.BATTERY_SAVER, Feature.WIFI,
            Feature.MOBILE_DATA, Feature.BLUETOOTH, Feature.AIRPLANE, Feature.BIOMETRICS,
            Feature.APP_SUSPEND, Feature.WHITELIST_EDIT, Feature.FOCUSED_APP,
            Feature.SENSOR_PRIVACY_ALL, Feature.SETPROP_DOZE -> setEnabled(feature, apiLevel, booleanValue(originalValue), target)
        }
    }

    /** Boolean is the actual desired state, except block/restrict/force/suspend features: true applies. */
    @JvmStatic
    @JvmOverloads
    fun setEnabled(feature: Feature, apiLevel: Int, enabled: Boolean, target: String? = null): List<String>? {
        if (target != null) PackageNames.requireValid(target)
        val pkg = when (feature) {
            Feature.MOTION_SENSORS -> if (enabled) PackageNames.requireValid(target) else null
            Feature.APP_SUSPEND, Feature.NOTIFICATION_BLOCK, Feature.WHITELIST_EDIT, Feature.PM_DISABLE ->
                PackageNames.requireValid(target)
            Feature.FORCE_DOZE, Feature.DOZE_STATE_READ, Feature.TUNABLES, Feature.BATTERY_SAVER,
            Feature.WIFI, Feature.MOBILE_DATA, Feature.BLUETOOTH, Feature.AIRPLANE, Feature.LOCATION,
            Feature.BIOMETRICS, Feature.FOCUSED_APP, Feature.SENSOR_PRIVACY_ALL, Feature.SETPROP_DOZE -> null
        }
        if (apiLevel < 23) return null
        val state = if (enabled) "enable" else "disable"
        val bit = if (enabled) 1 else 0
        val command = when (feature) {
            Feature.FORCE_DOZE -> "${deviceIdle(apiLevel)} ${if (enabled) "force-idle${if (apiLevel >= 24) " deep" else ""}" else if (apiLevel >= 24) "unforce" else "step"}"
            Feature.MOTION_SENSORS -> "dumpsys sensorservice ${if (enabled) "restrict $pkg" else "enable"}"
            Feature.BATTERY_SAVER -> if (apiLevel >= 29) "cmd power set-mode $bit"
                else "settings put global low_power $bit"
            Feature.WIFI -> if (apiLevel >= 30) "cmd wifi set-wifi-enabled ${if (enabled) "enabled" else "disabled"}"
                else "svc wifi $state"
            Feature.MOBILE_DATA -> "svc data $state"
            Feature.BLUETOOTH -> if (apiLevel >= 33) "cmd bluetooth_manager $state" else "svc bluetooth $state"
            Feature.AIRPLANE -> if (apiLevel >= 30) "cmd connectivity airplane-mode $state" else return null
            Feature.LOCATION -> if (apiLevel >= 30) "cmd location set-location-enabled $enabled"
                else "settings put secure location_mode ${if (enabled) 3 else 0}"
            Feature.BIOMETRICS -> "settings put secure biometric_keyguard_enabled $bit"
            Feature.APP_SUSPEND -> if (apiLevel >= 24) "pm ${if (enabled) "suspend" else "unsuspend"} $pkg" else return null
            Feature.NOTIFICATION_BLOCK -> if (apiLevel >= 33) return listOf(
                "pm ${if (enabled) "revoke" else "grant"} $pkg $POST_NOTIFICATIONS",
                "pm ${if (enabled) "set" else "clear"}-permission-flags $pkg $POST_NOTIFICATIONS user-set user-fixed",
            ) else return null // Hidden pre-33 notification transactions are not a stable shell API.
            Feature.WHITELIST_EDIT -> "${deviceIdle(apiLevel)} whitelist ${if (enabled) "+" else "-"}$pkg"
            Feature.SETPROP_DOZE -> "setprop $DOZE_PROPERTY $enabled"
            Feature.PM_DISABLE -> "pm ${if (enabled) "disable" else "enable"} $pkg"
            Feature.SENSOR_PRIVACY_ALL -> {
                val transaction = if (apiLevel >= 33) 9 else if (apiLevel >= 31) 8 else 4
                "service call sensor_privacy $transaction i32 $bit"
            }
            Feature.DOZE_STATE_READ, Feature.TUNABLES, Feature.FOCUSED_APP -> return null
        }
        return listOf(command)
    }

    @JvmStatic
    @JvmOverloads
    fun readback(feature: Feature, apiLevel: Int, target: String? = null): String? {
        if (target != null) PackageNames.requireValid(target)
        val pkg = when (feature) {
            Feature.APP_SUSPEND, Feature.NOTIFICATION_BLOCK, Feature.PM_DISABLE -> PackageNames.requireValid(target)
            Feature.FORCE_DOZE, Feature.DOZE_STATE_READ, Feature.TUNABLES, Feature.MOTION_SENSORS,
            Feature.BATTERY_SAVER, Feature.WIFI, Feature.MOBILE_DATA, Feature.BLUETOOTH,
            Feature.AIRPLANE, Feature.LOCATION, Feature.BIOMETRICS, Feature.WHITELIST_EDIT,
            Feature.FOCUSED_APP, Feature.SENSOR_PRIVACY_ALL, Feature.SETPROP_DOZE -> null
        }
        if (apiLevel < 23) return null
        return when (feature) {
            Feature.FORCE_DOZE -> if (apiLevel >= 24) "${deviceIdle(apiLevel)} get deep" else "dumpsys deviceidle"
            Feature.DOZE_STATE_READ, Feature.TUNABLES -> "dumpsys deviceidle"
            Feature.MOTION_SENSORS -> "dumpsys sensorservice"
            Feature.BATTERY_SAVER -> "settings get global low_power"
            Feature.WIFI -> "settings get global wifi_on"
            Feature.MOBILE_DATA -> "settings get global mobile_data"
            Feature.BLUETOOTH -> "settings get global bluetooth_on"
            Feature.AIRPLANE -> if (apiLevel >= 30) "cmd connectivity airplane-mode" else null
            Feature.LOCATION -> if (apiLevel >= 30) "cmd location is-location-enabled" else "settings get secure location_mode"
            Feature.BIOMETRICS -> "settings get secure biometric_keyguard_enabled"
            Feature.APP_SUSPEND -> if (apiLevel >= 24) "dumpsys package $pkg" else null
            Feature.NOTIFICATION_BLOCK -> if (apiLevel >= 33) "dumpsys package $pkg" else "dumpsys notification"
            Feature.WHITELIST_EDIT -> "dumpsys deviceidle whitelist"
            Feature.FOCUSED_APP -> "dumpsys activity activities"
            Feature.SENSOR_PRIVACY_ALL -> "dumpsys sensor_privacy"
            Feature.SETPROP_DOZE -> "getprop $DOZE_PROPERTY"
            Feature.PM_DISABLE -> "dumpsys package $pkg"
        }
    }

    @JvmStatic
    @JvmOverloads
    fun originalValueRead(feature: Feature, apiLevel: Int, target: String? = null): String? {
        if (target != null) PackageNames.requireValid(target)
        if (apiLevel < 23) return null
        return when (feature) {
            Feature.FORCE_DOZE -> "dumpsys deviceidle" // mForceIdle, not the current deep-state token.
            Feature.TUNABLES -> "settings get global device_idle_constants"
            Feature.DOZE_STATE_READ, Feature.MOTION_SENSORS, Feature.BATTERY_SAVER, Feature.WIFI,
            Feature.MOBILE_DATA, Feature.BLUETOOTH, Feature.AIRPLANE, Feature.LOCATION,
            Feature.BIOMETRICS, Feature.APP_SUSPEND, Feature.NOTIFICATION_BLOCK, Feature.WHITELIST_EDIT,
            Feature.FOCUSED_APP, Feature.SENSOR_PRIVACY_ALL, Feature.SETPROP_DOZE, Feature.PM_DISABLE -> readback(feature, apiLevel, target)
        }
    }

    @JvmStatic
    fun restoreNotification(apiLevel: Int, target: String, granted: Boolean, userSet: Boolean, userFixed: Boolean): List<String>? {
        val pkg = PackageNames.requireValid(target)
        if (apiLevel < 33) return null
        val commands = mutableListOf("pm ${if (granted) "grant" else "revoke"} $pkg $POST_NOTIFICATIONS")
        for ((flag, set) in listOf("user-set" to userSet, "user-fixed" to userFixed)) {
            commands.add("pm ${if (set) "set" else "clear"}-permission-flags $pkg $POST_NOTIFICATIONS $flag")
        }
        return commands.toList()
    }

    /** Only a resolved hidden transaction and an observed user-0 uid authorize the legacy path. */
    @JvmStatic
    fun legacyNotification(apiLevel: Int, target: String, uid: Int, transaction: Int, enabled: Boolean): List<String>? {
        val pkg = PackageNames.requireValid(target)
        if (apiLevel !in 23..32 || uid !in 0..99_999 || transaction <= 0) return null
        return listOf("service call notification $transaction s16 $pkg i32 $uid i32 ${if (enabled) 1 else 0}")
    }

    /** SHELL alternative to the APP+WSS global settings fallback. Null restores an absent override. */
    @JvmStatic
    fun deviceConfigTunable(apiLevel: Int, key: String, value: Long?): String? {
        require(Regex("[A-Za-z][A-Za-z0-9_]*").matches(key)) { "Invalid tunable key" }
        if (apiLevel < 29) return null
        return if (value == null) "cmd device_config delete device_idle $key"
        else "cmd device_config put device_idle $key $value"
    }

    @JvmStatic
    fun originalDeviceConfigTunable(apiLevel: Int, key: String): String? {
        require(Regex("[A-Za-z][A-Za-z0-9_]*").matches(key)) { "Invalid tunable key" }
        return if (apiLevel >= 29) "cmd device_config get device_idle $key" else null
    }

    private fun deviceIdle(apiLevel: Int): String = if (apiLevel >= 24) "cmd deviceidle" else "dumpsys deviceidle"

    private fun booleanValue(value: String): Boolean = when (value) {
        "1", "true", "enabled" -> true
        "0", "false", "disabled" -> false
        else -> throw IllegalArgumentException("Unverified original value")
    }

    private fun tunables(value: String): List<String> {
        require(value.isEmpty() || value.split(',').all { tunable.matches(it) }) { "Invalid tunables" }
        return listOf("settings put global device_idle_constants ${value.ifEmpty { "''" }}")
    }
}
