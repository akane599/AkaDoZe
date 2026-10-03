package com.akylas.enforcedoze.doze.parse

internal fun deviceIdleFixture(): String =
    checkNotNull(DozeStateParserTest::class.java.getResource("/doze/deviceidle.txt")).readText()

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
