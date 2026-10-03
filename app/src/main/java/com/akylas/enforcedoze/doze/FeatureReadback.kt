package com.akylas.enforcedoze.doze

import com.akylas.enforcedoze.access.Feature
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
        Feature.APP_SUSPEND -> userZero(output, target)?.firstOrNull()?.let { header ->
            Regex("(?:^|\\s)suspended=(true|false)(?:\\s|$)").find(header)?.groupValues?.get(1)
                ?.let { if (it == "true") "1" else "0" }
        }
        Feature.NOTIFICATION_BLOCK -> notification(output, target)
        Feature.BATTERY_SAVER, Feature.WIFI, Feature.MOBILE_DATA, Feature.BLUETOOTH,
        Feature.AIRPLANE, Feature.BIOMETRICS -> bit(output)
        else -> null
    }

    fun appliedValue(feature: Feature): String = when (feature) {
        Feature.MOTION_SENSORS -> "RESTRICTED"
        Feature.WIFI, Feature.MOBILE_DATA, Feature.BLUETOOTH, Feature.LOCATION, Feature.BIOMETRICS -> "0"
        Feature.NOTIFICATION_BLOCK -> "0,1,1" // grant,user-set,user-fixed
        else -> "1"
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
