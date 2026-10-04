package com.akylas.enforcedoze.doze.parse

import com.akylas.enforcedoze.access.CommandResult
import com.akylas.enforcedoze.access.PackageNames
import com.akylas.enforcedoze.access.Reason

sealed interface FocusedApps {
    data class Known(val packages: Set<String>) : FocusedApps
    data class Unknown(val reason: Reason) : FocusedApps
}

/** A successful dump with explicit null focus differs from a failed or unfamiliar dump. */
object FocusedAppParser {
    private val focusLine = Regex("^\\s*(?:mCurrentFocus|mFocusedApp)\\s*=\\s*(.*?)\\s*$")
    private val component = Regex("^(?:Window|ActivityRecord)\\{[a-fA-F0-9]+\\s+u[0-9]+\\s+([^\\s/{}]+)/[^\\s{}]+(?:\\s+[^{}]*)?}$")
    private val nestedActivity = Regex("ActivityRecord\\{[a-fA-F0-9]+\\s+u[0-9]+\\s+([^\\s/{}]+)/[^\\s{}]+(?:\\s+[^{}]*)?}")
    private val nonActivityWindow = Regex("^Window\\{[a-fA-F0-9]+\\s+u[0-9]+\\s+[^\\s/{}][^/{}]*}$")

    @JvmStatic
    fun parse(result: CommandResult): FocusedApps {
        if (!result.ok) return FocusedApps.Unknown(Reason.UNVERIFIED)
        val packages = linkedSetOf<String>()
        var sawFocus = false
        var sawNonActivityWindow = false
        for (line in result.stdout) {
            val focus = focusLine.matchEntire(line)?.groupValues?.get(1) ?: continue
            sawFocus = true
            if (focus == "null") continue
            if (!hasBalancedBraces(focus)) return FocusedApps.Unknown(Reason.UNVERIFIED)
            if (nonActivityWindow.matches(focus)) {
                sawNonActivityWindow = true
                continue
            }
            val pkg = (component.matchEntire(focus) ?: nestedActivity.find(focus))?.groupValues?.get(1)
                ?: return FocusedApps.Unknown(Reason.UNVERIFIED)
            if (!PackageNames.isValid(pkg)) return FocusedApps.Unknown(Reason.UNVERIFIED)
            packages += pkg
        }
        return if (sawFocus && (!sawNonActivityWindow || packages.isNotEmpty())) {
            FocusedApps.Known(packages.toSet())
        } else {
            FocusedApps.Unknown(Reason.UNVERIFIED)
        }
    }

    private fun hasBalancedBraces(value: String): Boolean {
        var depth = 0
        for (character in value) {
            when (character) {
                '{' -> depth++
                '}' -> if (--depth < 0) return false
            }
        }
        return depth == 0
    }
}
