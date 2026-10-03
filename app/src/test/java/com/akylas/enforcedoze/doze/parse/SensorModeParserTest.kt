package com.akylas.enforcedoze.doze.parse

import com.akylas.enforcedoze.doze.SensorMode
import org.junit.Assert.assertEquals
import org.junit.Test

class SensorModeParserTest {
    @Test
    fun normalAndRestrictedModesReadAllowTokenFromRealisticDump() {
        assertEquals(SensorModeReading(SensorMode.NORMAL, null), SensorModeParser.parse(sensorFixture("Mode : NORMAL")))
        assertEquals(
            SensorModeReading(SensorMode.RESTRICTED, "com.akylas.enforcedoze"),
            SensorModeParser.parse(sensorFixture("Mode : RESTRICTED : com.akylas.enforcedoze")),
        )
    }

    @Test
    fun allInjectionModesAndUnknownOemModeAreOtherNotNormal() {
        for (mode in listOf("DATA_INJECTION", "REPLAY_DATA_INJECTION", "HAL_BYPASS_REPLAY_DATA_INJECTION", "OEM_MODE")) {
            assertEquals(
                SensorModeReading(SensorMode.OTHER, "com.test.injector"),
                SensorModeParser.parse(sensorFixture("Mode : $mode : com.test.injector")),
            )
        }
    }

    @Test
    fun variableSpacesCrLfAndStdoutListsAreAccepted() {
        for (line in listOf("Mode:RESTRICTED:x", "  Mode  :  RESTRICTED  :  x  ", "\tMode\t:\tRESTRICTED\t:\tx")) {
            val dump = sensorFixture(line).replace("\n", "\r\n")
            assertEquals(SensorModeReading(SensorMode.RESTRICTED, "x"), SensorModeParser.parse(dump))
            assertEquals(SensorModeParser.parse(dump), SensorModeParser.parse(dump.lines()))
        }
    }

    @Test
    fun missingModeAndMalformedLineAreUnverified() {
        for (dump in listOf("No Sensors on the device", "", "permission denied", "Mode :", "Mode : NORMAL extra", "not Mode : NORMAL")) {
            assertEquals(SensorModeReading(SensorMode.UNVERIFIED, null), SensorModeParser.parse(dump))
        }
        assertEquals(SensorModeReading(SensorMode.RESTRICTED, null), SensorModeParser.parse("Mode:RESTRICTED:"))
        assertEquals(SensorModeReading(SensorMode.RESTRICTED, null), SensorModeParser.parse("Mode:RESTRICTED"))
    }
}
