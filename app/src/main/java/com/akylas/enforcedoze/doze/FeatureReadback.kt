package com.akylas.enforcedoze.doze

import com.akylas.enforcedoze.access.AccessLevel
import com.akylas.enforcedoze.access.Feature
import com.akylas.enforcedoze.access.PackageNames
import com.akylas.enforcedoze.doze.parse.DozeStateParser
import com.akylas.enforcedoze.doze.parse.SensorModeParser

/** Strict original-value/readback decoding. Unsupported OEM output must not authorize a mutation. */
internal object FeatureReadback {
    fun value(feature: Feature, apiLevel: Int, output: List<String>, target: String?): String? = when (feature) {
        // We only take ownership of NORMAL sensors. Never replace another owner's restriction.
        Feature.MOTION_SENSORS -> SensorModeParser.parse(output).mode.takeIf { it == SensorMode.NORMAL }?.name
        Feature.FORCE_DOZE -> DozeStateParser.parse(output).forceIdle?.let { if (it) "1" else "0" }
        Feature.LOCATION -> if (apiLevel < 30) {
            output.joinToString("\n").trim().takeIf { it in setOf("0", "1", "2", "3") }
        } else bit(output)
        Feature.APP_SUSPEND -> suspension(output, target)
        Feature.NOTIFICATION_BLOCK -> if (apiLevel >= 33) notification(output, target) else legacyNotification(output, target)
        Feature.PM_DISABLE -> userZero(output, target)?.firstOrNull()?.let {
            Regex("(?:^|\\s)enabled=([0-4])(?:\\s|$)").find(it)?.groupValues?.get(1)
        }
        Feature.SETPROP_DOZE -> bit(output)
        Feature.SENSOR_PRIVACY_ALL -> output.mapNotNull {
            Regex("\\s*All sensor privacy(?: enabled)?: (true|false)\\s*").matchEntire(it)
                ?.groupValues?.get(1)
        }.singleOrNull()?.let { if (it == "true") "1" else "0" }
        Feature.BATTERY_SAVER, Feature.WIFI, Feature.MOBILE_DATA, Feature.BLUETOOTH,
        Feature.AIRPLANE, Feature.BIOMETRICS -> bit(output)
        else -> null
    }

    /** Restore only our pm suspension, not another owner's aggregate suspension. */
    fun restoredSuspensionValue(output: List<String>, target: String?, level: AccessLevel): String? {
        val aggregate = suspension(output, target) ?: return null
        val ours = when (level) {
            AccessLevel.SHELL -> setOf("com.android.shell")
            AccessLevel.ROOT -> setOf("root", "android")
            else -> return aggregate
        }
        val user = userZero(output, target) ?: return aggregate
        val start = user.indices.filter { user[it].trim() == "Suspend params:" }.singleOrNull()
            ?: return aggregate
        val indent = user[start].takeWhile { it.isWhitespace() }.length
        val params = user.drop(start + 1).takeWhile {
            it.isBlank() || it.takeWhile { c -> c.isWhitespace() }.length > indent
        }.filter { it.isNotBlank() }
        val entryIndent = params.minOfOrNull { it.takeWhile { c -> c.isWhitespace() }.length }
            ?: return aggregate
        val entries = params.filter { it.takeWhile { c -> c.isWhitespace() }.length == entryIndent }
        val pattern = Regex("\\s*suspendingPackage=([a-zA-Z0-9_.]+)\\s*")
        val suspenders = entries.map { line ->
            val name = pattern.matchEntire(line)?.groupValues?.get(1) ?: return aggregate
            if (name !in setOf("root", "android") && !PackageNames.isValid(name)) return aggregate
            name
        }
        // A differently indented/encoded entry makes the set incomplete: keep the aggregate oracle.
        if (params.any { it !in entries && "suspendingPackage" in it }) return aggregate
        return if (suspenders.any { it in ours }) "1" else "0"
    }

    fun appliedValue(feature: Feature): String = when (feature) {
        Feature.MOTION_SENSORS -> "RESTRICTED"
        Feature.WIFI, Feature.MOBILE_DATA, Feature.BLUETOOTH, Feature.LOCATION, Feature.BIOMETRICS -> "0"
        Feature.PM_DISABLE -> "2"
        Feature.NOTIFICATION_BLOCK -> "0,1,1" // grant,user-set,user-fixed
        else -> "1"
    }

    private fun suspension(output: List<String>, target: String?): String? =
        userZero(output, target)?.firstOrNull()?.let { header ->
            Regex("(?:^|\\s)suspended=(true|false)(?:\\s|$)").find(header)?.groupValues?.get(1)
                ?.let { if (it == "true") "1" else "0" }
        }

    private fun legacyNotification(output: List<String>, target: String?): String? {
        // A package-wide custom importance cannot be restored by the boolean hidden method.
        // Only the explicit default/unblocked or NONE states have a reversible boolean value.
        val pattern = Regex("\\s*PackagePreferences: " + Regex.escape(target ?: return null) +
            " \\(([0-9]+)\\) importance=(UNSPECIFIED|-1000|NONE|0)(?:\\s.*)?")
        val matches = output.mapNotNull { pattern.matchEntire(it) }
        val match = matches.singleOrNull() ?: return null
        val uid = match.groupValues[1].toIntOrNull()?.takeIf { it in 0..99_999 } ?: return null
        val enabled = match.groupValues[2] !in setOf("NONE", "0")
        return (if (enabled) "1" else "0") + ",$uid"
    }

    private fun bit(output: List<String>): String? = when (output.joinToString("\n").trim()) {
        "0", "false", "disabled" -> "0"
        "1", "true", "enabled" -> "1"
        else -> null
    }

    private fun userZero(output: List<String>, target: String?): List<String>? {
        // Catalog's pm commands address user 0. Do not borrow state from another user/package.
        val start = output.indexOfFirst { it.trim().startsWith("Package [$target] (") }
        if (start < 0) return null
        val packageIndent = output[start].takeWhile { it.isWhitespace() }.length
        val body = output.drop(start + 1).takeWhile {
            it.isBlank() || it.takeWhile { c -> c.isWhitespace() }.length > packageIndent
        }
        val user = body.indexOfFirst { it.trim().startsWith("User 0:") }
        if (user < 0) return null
        val indent = body[user].takeWhile { it.isWhitespace() }.length
        return listOf(body[user]) + body.drop(user + 1).takeWhile {
            it.isBlank() || it.takeWhile { c -> c.isWhitespace() }.length > indent
        }
    }

    private fun notification(output: List<String>, target: String?): String? {
        val user = userZero(output, target) ?: return null
        val start = user.indexOfFirst { it.trim() == "runtime permissions:" }
        if (start < 0) return null
        val indent = user[start].takeWhile { it.isWhitespace() }.length
        val permissions = user.drop(start + 1).takeWhile {
            it.isBlank() || it.takeWhile { c -> c.isWhitespace() }.length > indent
        }
        val line = permissions.mapNotNull {
            Regex("\\s*android\\.permission\\.POST_NOTIFICATIONS: granted=(true|false), flags=\\[([^]]*)]\\s*")
                .matchEntire(it)
        }.singleOrNull() ?: return null
        val flags = line.groupValues[2].split('|').map { it.trim() }.filter { it.isNotEmpty() }
        return listOf(line.groupValues[1] == "true", "USER_SET" in flags, "USER_FIXED" in flags)
            .joinToString(",") { if (it) "1" else "0" }
    }
}
