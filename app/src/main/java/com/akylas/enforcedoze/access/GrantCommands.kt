package com.akylas.enforcedoze.access

/** Helper command output reports transport results, never assumes the grant is effective. */
object GrantCommands {
    @JvmStatic
    fun forApp(apiLevel: Int, packageName: String, notificationService: String): Map<String, String> {
        val pkg = PackageNames.requireValid(packageName)
        val service = PackageNames.requireValid(notificationService)
        val commands = linkedMapOf<String, String>()
        for (permission in listOf("DUMP", "WRITE_SECURE_SETTINGS", "READ_PHONE_STATE")) {
            commands[permission] = "pm grant $pkg android.permission.$permission"
        }
        if (apiLevel >= 31) commands["SCHEDULE_EXACT_ALARM"] = "appops set $pkg SCHEDULE_EXACT_ALARM allow"
        commands["GET_USAGE_STATS"] = "appops set $pkg GET_USAGE_STATS allow"
        commands["NOTIFICATION_LISTENER"] = "cmd notification allow_listener $pkg/$service"
        commands["SELF_WHITELIST"] = "${if (apiLevel >= 24) "cmd" else "dumpsys"} deviceidle whitelist +$pkg"
        return commands.toMap()
    }
}
