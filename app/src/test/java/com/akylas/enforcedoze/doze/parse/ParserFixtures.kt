package com.akylas.enforcedoze.doze.parse

internal fun deviceIdleFixture(): String =
    checkNotNull(DozeStateParserTest::class.java.getResource("/doze/deviceidle.txt")).readText()

/** Synthetic suspension shape; the per-suspender dump format is device-unverified. */
internal fun suspensionFixture(target: String, suspended: Boolean, vararg suspenders: String): String = buildString {
    appendLine("Packages:")
    appendLine("  Package [$target] (abc):")
    appendLine("    User 0: installed=true suspended=$suspended hidden=false")
    if (suspenders.isNotEmpty()) {
        appendLine("      Suspend params:")
        suspenders.forEach {
            appendLine("        suspendingPackage=$it")
            appendLine("          dialogInfo=null")
        }
    }
    appendLine("    User 10: installed=true suspended=true")
    appendLine("      Suspend params:")
    appendLine("        suspendingPackage=com.android.shell")
}

internal fun sensorFixture(modeLine: String): String = """
    Sensor Device:
      Total 2 h/w sensors, 2 running 0 disabled clients
    Sensor List:
      Accelerometer | Vendor | version=1 | continuous
    Fusion States:
      9-axis fusion (disabled)
    $modeLine
    Active sensors:
      Accelerometer (handle=0x00000001, connections=1)
""".trimIndent()
