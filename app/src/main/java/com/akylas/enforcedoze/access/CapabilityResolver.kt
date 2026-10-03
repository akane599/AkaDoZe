package com.akylas.enforcedoze.access

object PackageNames {
    private val grammar = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+$")

    @JvmStatic
    fun isValid(name: String): Boolean = name.length <= 255 && grammar.matches(name)

    internal fun requireValid(name: String?): String {
        require(name != null && isValid(name)) { "Invalid package name" }
        return name
    }
}

object CapabilityResolver {
    @JvmStatic
    fun status(feature: Feature, level: AccessLevel, apiLevel: Int, grants: Grants): FeatureStatus {
        if (apiLevel < 23 || feature == Feature.APP_SUSPEND && apiLevel < 24) {
            return FeatureStatus.Unavailable(Reason.API_TOO_OLD)
        }
        if (level == AccessLevel.NONE) return FeatureStatus.Unavailable(Reason.NO_ACCESS)
        val privileged = level == AccessLevel.SHELL || level == AccessLevel.ROOT
        val rootOnly = when (feature) {
            Feature.SENSOR_PRIVACY_ALL, Feature.SETPROP_DOZE, Feature.PM_DISABLE -> true
            Feature.NOTIFICATION_BLOCK -> apiLevel < 33
            Feature.AIRPLANE -> apiLevel < 30
            else -> false
        }
        if (rootOnly) {
            return if (level == AccessLevel.ROOT) FeatureStatus.Available
            else FeatureStatus.Unavailable(Reason.REQUIRES_ROOT)
        }
        return when (feature) {
            Feature.MOTION_SENSORS, Feature.DOZE_STATE_READ ->
                if (privileged || grants.dump) FeatureStatus.Available
                else FeatureStatus.Unavailable(Reason.NEEDS_DUMP)
            Feature.TUNABLES, Feature.BIOMETRICS ->
                if (privileged || grants.writeSecureSettings) FeatureStatus.Available
                else FeatureStatus.Unavailable(Reason.NEEDS_WRITE_SECURE_SETTINGS)
            else -> if (privileged) FeatureStatus.Available
                else FeatureStatus.Unavailable(Reason.NO_ACCESS)
        }
    }
}
