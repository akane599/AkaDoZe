package com.akylas.enforcedoze.access

import org.junit.Assert.assertEquals
import org.junit.Test

class CommandCatalogReadbackGoldenTest {
    @Test
    fun readbackMatchesEveryFeatureApiAndTargetGoldenOutcome() {
        val actual = Feature.entries.flatMap { feature ->
            (23..36).flatMap { api ->
                listOf(null, "com.example.app", "com.example.app;reboot").mapIndexed { index, target ->
                    "$feature\t$api\t$index\t${outcome(feature, api, target)}"
                }
            }
        }
        val expected = golden.lines()
        assertEquals("Every Feature x API 23..36 x null/valid/invalid target is pinned", expected.size, actual.size)
        for (index in expected.indices) {
            assertEquals("readback matrix row $index", expected[index], actual[index])
        }
    }

    @Test
    fun readbackValidatesTargetBeforeRejectingUnsupportedApi() {
        assertEquals("null", outcome(Feature.FORCE_DOZE, 22, null))
        assertEquals("null", outcome(Feature.APP_SUSPEND, 22, "com.example.app"))
        assertEquals("exception:java.lang.IllegalArgumentException", outcome(Feature.APP_SUSPEND, 22, null))
        assertEquals("exception:java.lang.IllegalArgumentException", outcome(Feature.FORCE_DOZE, 22, "a.b;reboot"))
    }

    private fun outcome(feature: Feature, api: Int, target: String?): String = try {
        CommandCatalog.readback(feature, api, target)?.let { "string:$it" } ?: "null"
    } catch (exception: Exception) {
        "exception:${exception.javaClass.name}"
    }

    // Captured from unchanged CommandCatalog at 0a5a18f. Target indices: null, valid, invalid.
    // Literal outputs only: do not regenerate expectations from the candidate at test time.
    private val golden = """
        FORCE_DOZE	23	0	string:dumpsys deviceidle
        FORCE_DOZE	23	1	string:dumpsys deviceidle
        FORCE_DOZE	23	2	exception:java.lang.IllegalArgumentException
        FORCE_DOZE	24	0	string:cmd deviceidle get deep
        FORCE_DOZE	24	1	string:cmd deviceidle get deep
        FORCE_DOZE	24	2	exception:java.lang.IllegalArgumentException
        FORCE_DOZE	25	0	string:cmd deviceidle get deep
        FORCE_DOZE	25	1	string:cmd deviceidle get deep
        FORCE_DOZE	25	2	exception:java.lang.IllegalArgumentException
        FORCE_DOZE	26	0	string:cmd deviceidle get deep
        FORCE_DOZE	26	1	string:cmd deviceidle get deep
        FORCE_DOZE	26	2	exception:java.lang.IllegalArgumentException
        FORCE_DOZE	27	0	string:cmd deviceidle get deep
        FORCE_DOZE	27	1	string:cmd deviceidle get deep
        FORCE_DOZE	27	2	exception:java.lang.IllegalArgumentException
        FORCE_DOZE	28	0	string:cmd deviceidle get deep
        FORCE_DOZE	28	1	string:cmd deviceidle get deep
        FORCE_DOZE	28	2	exception:java.lang.IllegalArgumentException
        FORCE_DOZE	29	0	string:cmd deviceidle get deep
        FORCE_DOZE	29	1	string:cmd deviceidle get deep
        FORCE_DOZE	29	2	exception:java.lang.IllegalArgumentException
        FORCE_DOZE	30	0	string:cmd deviceidle get deep
        FORCE_DOZE	30	1	string:cmd deviceidle get deep
        FORCE_DOZE	30	2	exception:java.lang.IllegalArgumentException
        FORCE_DOZE	31	0	string:cmd deviceidle get deep
        FORCE_DOZE	31	1	string:cmd deviceidle get deep
        FORCE_DOZE	31	2	exception:java.lang.IllegalArgumentException
        FORCE_DOZE	32	0	string:cmd deviceidle get deep
        FORCE_DOZE	32	1	string:cmd deviceidle get deep
        FORCE_DOZE	32	2	exception:java.lang.IllegalArgumentException
        FORCE_DOZE	33	0	string:cmd deviceidle get deep
        FORCE_DOZE	33	1	string:cmd deviceidle get deep
        FORCE_DOZE	33	2	exception:java.lang.IllegalArgumentException
        FORCE_DOZE	34	0	string:cmd deviceidle get deep
        FORCE_DOZE	34	1	string:cmd deviceidle get deep
        FORCE_DOZE	34	2	exception:java.lang.IllegalArgumentException
        FORCE_DOZE	35	0	string:cmd deviceidle get deep
        FORCE_DOZE	35	1	string:cmd deviceidle get deep
        FORCE_DOZE	35	2	exception:java.lang.IllegalArgumentException
        FORCE_DOZE	36	0	string:cmd deviceidle get deep
        FORCE_DOZE	36	1	string:cmd deviceidle get deep
        FORCE_DOZE	36	2	exception:java.lang.IllegalArgumentException
        DOZE_STATE_READ	23	0	string:dumpsys deviceidle
        DOZE_STATE_READ	23	1	string:dumpsys deviceidle
        DOZE_STATE_READ	23	2	exception:java.lang.IllegalArgumentException
        DOZE_STATE_READ	24	0	string:dumpsys deviceidle
        DOZE_STATE_READ	24	1	string:dumpsys deviceidle
        DOZE_STATE_READ	24	2	exception:java.lang.IllegalArgumentException
        DOZE_STATE_READ	25	0	string:dumpsys deviceidle
        DOZE_STATE_READ	25	1	string:dumpsys deviceidle
        DOZE_STATE_READ	25	2	exception:java.lang.IllegalArgumentException
        DOZE_STATE_READ	26	0	string:dumpsys deviceidle
        DOZE_STATE_READ	26	1	string:dumpsys deviceidle
        DOZE_STATE_READ	26	2	exception:java.lang.IllegalArgumentException
        DOZE_STATE_READ	27	0	string:dumpsys deviceidle
        DOZE_STATE_READ	27	1	string:dumpsys deviceidle
        DOZE_STATE_READ	27	2	exception:java.lang.IllegalArgumentException
        DOZE_STATE_READ	28	0	string:dumpsys deviceidle
        DOZE_STATE_READ	28	1	string:dumpsys deviceidle
        DOZE_STATE_READ	28	2	exception:java.lang.IllegalArgumentException
        DOZE_STATE_READ	29	0	string:dumpsys deviceidle
        DOZE_STATE_READ	29	1	string:dumpsys deviceidle
        DOZE_STATE_READ	29	2	exception:java.lang.IllegalArgumentException
        DOZE_STATE_READ	30	0	string:dumpsys deviceidle
        DOZE_STATE_READ	30	1	string:dumpsys deviceidle
        DOZE_STATE_READ	30	2	exception:java.lang.IllegalArgumentException
        DOZE_STATE_READ	31	0	string:dumpsys deviceidle
        DOZE_STATE_READ	31	1	string:dumpsys deviceidle
        DOZE_STATE_READ	31	2	exception:java.lang.IllegalArgumentException
        DOZE_STATE_READ	32	0	string:dumpsys deviceidle
        DOZE_STATE_READ	32	1	string:dumpsys deviceidle
        DOZE_STATE_READ	32	2	exception:java.lang.IllegalArgumentException
        DOZE_STATE_READ	33	0	string:dumpsys deviceidle
        DOZE_STATE_READ	33	1	string:dumpsys deviceidle
        DOZE_STATE_READ	33	2	exception:java.lang.IllegalArgumentException
        DOZE_STATE_READ	34	0	string:dumpsys deviceidle
        DOZE_STATE_READ	34	1	string:dumpsys deviceidle
        DOZE_STATE_READ	34	2	exception:java.lang.IllegalArgumentException
        DOZE_STATE_READ	35	0	string:dumpsys deviceidle
        DOZE_STATE_READ	35	1	string:dumpsys deviceidle
        DOZE_STATE_READ	35	2	exception:java.lang.IllegalArgumentException
        DOZE_STATE_READ	36	0	string:dumpsys deviceidle
        DOZE_STATE_READ	36	1	string:dumpsys deviceidle
        DOZE_STATE_READ	36	2	exception:java.lang.IllegalArgumentException
        TUNABLES	23	0	string:dumpsys deviceidle
        TUNABLES	23	1	string:dumpsys deviceidle
        TUNABLES	23	2	exception:java.lang.IllegalArgumentException
        TUNABLES	24	0	string:dumpsys deviceidle
        TUNABLES	24	1	string:dumpsys deviceidle
        TUNABLES	24	2	exception:java.lang.IllegalArgumentException
        TUNABLES	25	0	string:dumpsys deviceidle
        TUNABLES	25	1	string:dumpsys deviceidle
        TUNABLES	25	2	exception:java.lang.IllegalArgumentException
        TUNABLES	26	0	string:dumpsys deviceidle
        TUNABLES	26	1	string:dumpsys deviceidle
        TUNABLES	26	2	exception:java.lang.IllegalArgumentException
        TUNABLES	27	0	string:dumpsys deviceidle
        TUNABLES	27	1	string:dumpsys deviceidle
        TUNABLES	27	2	exception:java.lang.IllegalArgumentException
        TUNABLES	28	0	string:dumpsys deviceidle
        TUNABLES	28	1	string:dumpsys deviceidle
        TUNABLES	28	2	exception:java.lang.IllegalArgumentException
        TUNABLES	29	0	string:dumpsys deviceidle
        TUNABLES	29	1	string:dumpsys deviceidle
        TUNABLES	29	2	exception:java.lang.IllegalArgumentException
        TUNABLES	30	0	string:dumpsys deviceidle
        TUNABLES	30	1	string:dumpsys deviceidle
        TUNABLES	30	2	exception:java.lang.IllegalArgumentException
        TUNABLES	31	0	string:dumpsys deviceidle
        TUNABLES	31	1	string:dumpsys deviceidle
        TUNABLES	31	2	exception:java.lang.IllegalArgumentException
        TUNABLES	32	0	string:dumpsys deviceidle
        TUNABLES	32	1	string:dumpsys deviceidle
        TUNABLES	32	2	exception:java.lang.IllegalArgumentException
        TUNABLES	33	0	string:dumpsys deviceidle
        TUNABLES	33	1	string:dumpsys deviceidle
        TUNABLES	33	2	exception:java.lang.IllegalArgumentException
        TUNABLES	34	0	string:dumpsys deviceidle
        TUNABLES	34	1	string:dumpsys deviceidle
        TUNABLES	34	2	exception:java.lang.IllegalArgumentException
        TUNABLES	35	0	string:dumpsys deviceidle
        TUNABLES	35	1	string:dumpsys deviceidle
        TUNABLES	35	2	exception:java.lang.IllegalArgumentException
        TUNABLES	36	0	string:dumpsys deviceidle
        TUNABLES	36	1	string:dumpsys deviceidle
        TUNABLES	36	2	exception:java.lang.IllegalArgumentException
        MOTION_SENSORS	23	0	string:dumpsys sensorservice
        MOTION_SENSORS	23	1	string:dumpsys sensorservice
        MOTION_SENSORS	23	2	exception:java.lang.IllegalArgumentException
        MOTION_SENSORS	24	0	string:dumpsys sensorservice
        MOTION_SENSORS	24	1	string:dumpsys sensorservice
        MOTION_SENSORS	24	2	exception:java.lang.IllegalArgumentException
        MOTION_SENSORS	25	0	string:dumpsys sensorservice
        MOTION_SENSORS	25	1	string:dumpsys sensorservice
        MOTION_SENSORS	25	2	exception:java.lang.IllegalArgumentException
        MOTION_SENSORS	26	0	string:dumpsys sensorservice
        MOTION_SENSORS	26	1	string:dumpsys sensorservice
        MOTION_SENSORS	26	2	exception:java.lang.IllegalArgumentException
        MOTION_SENSORS	27	0	string:dumpsys sensorservice
        MOTION_SENSORS	27	1	string:dumpsys sensorservice
        MOTION_SENSORS	27	2	exception:java.lang.IllegalArgumentException
        MOTION_SENSORS	28	0	string:dumpsys sensorservice
        MOTION_SENSORS	28	1	string:dumpsys sensorservice
        MOTION_SENSORS	28	2	exception:java.lang.IllegalArgumentException
        MOTION_SENSORS	29	0	string:dumpsys sensorservice
        MOTION_SENSORS	29	1	string:dumpsys sensorservice
        MOTION_SENSORS	29	2	exception:java.lang.IllegalArgumentException
        MOTION_SENSORS	30	0	string:dumpsys sensorservice
        MOTION_SENSORS	30	1	string:dumpsys sensorservice
        MOTION_SENSORS	30	2	exception:java.lang.IllegalArgumentException
        MOTION_SENSORS	31	0	string:dumpsys sensorservice
        MOTION_SENSORS	31	1	string:dumpsys sensorservice
        MOTION_SENSORS	31	2	exception:java.lang.IllegalArgumentException
        MOTION_SENSORS	32	0	string:dumpsys sensorservice
        MOTION_SENSORS	32	1	string:dumpsys sensorservice
        MOTION_SENSORS	32	2	exception:java.lang.IllegalArgumentException
        MOTION_SENSORS	33	0	string:dumpsys sensorservice
        MOTION_SENSORS	33	1	string:dumpsys sensorservice
        MOTION_SENSORS	33	2	exception:java.lang.IllegalArgumentException
        MOTION_SENSORS	34	0	string:dumpsys sensorservice
        MOTION_SENSORS	34	1	string:dumpsys sensorservice
        MOTION_SENSORS	34	2	exception:java.lang.IllegalArgumentException
        MOTION_SENSORS	35	0	string:dumpsys sensorservice
        MOTION_SENSORS	35	1	string:dumpsys sensorservice
        MOTION_SENSORS	35	2	exception:java.lang.IllegalArgumentException
        MOTION_SENSORS	36	0	string:dumpsys sensorservice
        MOTION_SENSORS	36	1	string:dumpsys sensorservice
        MOTION_SENSORS	36	2	exception:java.lang.IllegalArgumentException
        BATTERY_SAVER	23	0	string:settings get global low_power
        BATTERY_SAVER	23	1	string:settings get global low_power
        BATTERY_SAVER	23	2	exception:java.lang.IllegalArgumentException
        BATTERY_SAVER	24	0	string:settings get global low_power
        BATTERY_SAVER	24	1	string:settings get global low_power
        BATTERY_SAVER	24	2	exception:java.lang.IllegalArgumentException
        BATTERY_SAVER	25	0	string:settings get global low_power
        BATTERY_SAVER	25	1	string:settings get global low_power
        BATTERY_SAVER	25	2	exception:java.lang.IllegalArgumentException
        BATTERY_SAVER	26	0	string:settings get global low_power
        BATTERY_SAVER	26	1	string:settings get global low_power
        BATTERY_SAVER	26	2	exception:java.lang.IllegalArgumentException
        BATTERY_SAVER	27	0	string:settings get global low_power
        BATTERY_SAVER	27	1	string:settings get global low_power
        BATTERY_SAVER	27	2	exception:java.lang.IllegalArgumentException
        BATTERY_SAVER	28	0	string:settings get global low_power
        BATTERY_SAVER	28	1	string:settings get global low_power
        BATTERY_SAVER	28	2	exception:java.lang.IllegalArgumentException
        BATTERY_SAVER	29	0	string:settings get global low_power
        BATTERY_SAVER	29	1	string:settings get global low_power
        BATTERY_SAVER	29	2	exception:java.lang.IllegalArgumentException
        BATTERY_SAVER	30	0	string:settings get global low_power
        BATTERY_SAVER	30	1	string:settings get global low_power
        BATTERY_SAVER	30	2	exception:java.lang.IllegalArgumentException
        BATTERY_SAVER	31	0	string:settings get global low_power
        BATTERY_SAVER	31	1	string:settings get global low_power
        BATTERY_SAVER	31	2	exception:java.lang.IllegalArgumentException
        BATTERY_SAVER	32	0	string:settings get global low_power
        BATTERY_SAVER	32	1	string:settings get global low_power
        BATTERY_SAVER	32	2	exception:java.lang.IllegalArgumentException
        BATTERY_SAVER	33	0	string:settings get global low_power
        BATTERY_SAVER	33	1	string:settings get global low_power
        BATTERY_SAVER	33	2	exception:java.lang.IllegalArgumentException
        BATTERY_SAVER	34	0	string:settings get global low_power
        BATTERY_SAVER	34	1	string:settings get global low_power
        BATTERY_SAVER	34	2	exception:java.lang.IllegalArgumentException
        BATTERY_SAVER	35	0	string:settings get global low_power
        BATTERY_SAVER	35	1	string:settings get global low_power
        BATTERY_SAVER	35	2	exception:java.lang.IllegalArgumentException
        BATTERY_SAVER	36	0	string:settings get global low_power
        BATTERY_SAVER	36	1	string:settings get global low_power
        BATTERY_SAVER	36	2	exception:java.lang.IllegalArgumentException
        WIFI	23	0	string:settings get global wifi_on
        WIFI	23	1	string:settings get global wifi_on
        WIFI	23	2	exception:java.lang.IllegalArgumentException
        WIFI	24	0	string:settings get global wifi_on
        WIFI	24	1	string:settings get global wifi_on
        WIFI	24	2	exception:java.lang.IllegalArgumentException
        WIFI	25	0	string:settings get global wifi_on
        WIFI	25	1	string:settings get global wifi_on
        WIFI	25	2	exception:java.lang.IllegalArgumentException
        WIFI	26	0	string:settings get global wifi_on
        WIFI	26	1	string:settings get global wifi_on
        WIFI	26	2	exception:java.lang.IllegalArgumentException
        WIFI	27	0	string:settings get global wifi_on
        WIFI	27	1	string:settings get global wifi_on
        WIFI	27	2	exception:java.lang.IllegalArgumentException
        WIFI	28	0	string:settings get global wifi_on
        WIFI	28	1	string:settings get global wifi_on
        WIFI	28	2	exception:java.lang.IllegalArgumentException
        WIFI	29	0	string:settings get global wifi_on
        WIFI	29	1	string:settings get global wifi_on
        WIFI	29	2	exception:java.lang.IllegalArgumentException
        WIFI	30	0	string:settings get global wifi_on
        WIFI	30	1	string:settings get global wifi_on
        WIFI	30	2	exception:java.lang.IllegalArgumentException
        WIFI	31	0	string:settings get global wifi_on
        WIFI	31	1	string:settings get global wifi_on
        WIFI	31	2	exception:java.lang.IllegalArgumentException
        WIFI	32	0	string:settings get global wifi_on
        WIFI	32	1	string:settings get global wifi_on
        WIFI	32	2	exception:java.lang.IllegalArgumentException
        WIFI	33	0	string:settings get global wifi_on
        WIFI	33	1	string:settings get global wifi_on
        WIFI	33	2	exception:java.lang.IllegalArgumentException
        WIFI	34	0	string:settings get global wifi_on
        WIFI	34	1	string:settings get global wifi_on
        WIFI	34	2	exception:java.lang.IllegalArgumentException
        WIFI	35	0	string:settings get global wifi_on
        WIFI	35	1	string:settings get global wifi_on
        WIFI	35	2	exception:java.lang.IllegalArgumentException
        WIFI	36	0	string:settings get global wifi_on
        WIFI	36	1	string:settings get global wifi_on
        WIFI	36	2	exception:java.lang.IllegalArgumentException
        MOBILE_DATA	23	0	string:settings get global mobile_data
        MOBILE_DATA	23	1	string:settings get global mobile_data
        MOBILE_DATA	23	2	exception:java.lang.IllegalArgumentException
        MOBILE_DATA	24	0	string:settings get global mobile_data
        MOBILE_DATA	24	1	string:settings get global mobile_data
        MOBILE_DATA	24	2	exception:java.lang.IllegalArgumentException
        MOBILE_DATA	25	0	string:settings get global mobile_data
        MOBILE_DATA	25	1	string:settings get global mobile_data
        MOBILE_DATA	25	2	exception:java.lang.IllegalArgumentException
        MOBILE_DATA	26	0	string:settings get global mobile_data
        MOBILE_DATA	26	1	string:settings get global mobile_data
        MOBILE_DATA	26	2	exception:java.lang.IllegalArgumentException
        MOBILE_DATA	27	0	string:settings get global mobile_data
        MOBILE_DATA	27	1	string:settings get global mobile_data
        MOBILE_DATA	27	2	exception:java.lang.IllegalArgumentException
        MOBILE_DATA	28	0	string:settings get global mobile_data
        MOBILE_DATA	28	1	string:settings get global mobile_data
        MOBILE_DATA	28	2	exception:java.lang.IllegalArgumentException
        MOBILE_DATA	29	0	string:settings get global mobile_data
        MOBILE_DATA	29	1	string:settings get global mobile_data
        MOBILE_DATA	29	2	exception:java.lang.IllegalArgumentException
        MOBILE_DATA	30	0	string:settings get global mobile_data
        MOBILE_DATA	30	1	string:settings get global mobile_data
        MOBILE_DATA	30	2	exception:java.lang.IllegalArgumentException
        MOBILE_DATA	31	0	string:settings get global mobile_data
        MOBILE_DATA	31	1	string:settings get global mobile_data
        MOBILE_DATA	31	2	exception:java.lang.IllegalArgumentException
        MOBILE_DATA	32	0	string:settings get global mobile_data
        MOBILE_DATA	32	1	string:settings get global mobile_data
        MOBILE_DATA	32	2	exception:java.lang.IllegalArgumentException
        MOBILE_DATA	33	0	string:settings get global mobile_data
        MOBILE_DATA	33	1	string:settings get global mobile_data
        MOBILE_DATA	33	2	exception:java.lang.IllegalArgumentException
        MOBILE_DATA	34	0	string:settings get global mobile_data
        MOBILE_DATA	34	1	string:settings get global mobile_data
        MOBILE_DATA	34	2	exception:java.lang.IllegalArgumentException
        MOBILE_DATA	35	0	string:settings get global mobile_data
        MOBILE_DATA	35	1	string:settings get global mobile_data
        MOBILE_DATA	35	2	exception:java.lang.IllegalArgumentException
        MOBILE_DATA	36	0	string:settings get global mobile_data
        MOBILE_DATA	36	1	string:settings get global mobile_data
        MOBILE_DATA	36	2	exception:java.lang.IllegalArgumentException
        BLUETOOTH	23	0	string:settings get global bluetooth_on
        BLUETOOTH	23	1	string:settings get global bluetooth_on
        BLUETOOTH	23	2	exception:java.lang.IllegalArgumentException
        BLUETOOTH	24	0	string:settings get global bluetooth_on
        BLUETOOTH	24	1	string:settings get global bluetooth_on
        BLUETOOTH	24	2	exception:java.lang.IllegalArgumentException
        BLUETOOTH	25	0	string:settings get global bluetooth_on
        BLUETOOTH	25	1	string:settings get global bluetooth_on
        BLUETOOTH	25	2	exception:java.lang.IllegalArgumentException
        BLUETOOTH	26	0	string:settings get global bluetooth_on
        BLUETOOTH	26	1	string:settings get global bluetooth_on
        BLUETOOTH	26	2	exception:java.lang.IllegalArgumentException
        BLUETOOTH	27	0	string:settings get global bluetooth_on
        BLUETOOTH	27	1	string:settings get global bluetooth_on
        BLUETOOTH	27	2	exception:java.lang.IllegalArgumentException
        BLUETOOTH	28	0	string:settings get global bluetooth_on
        BLUETOOTH	28	1	string:settings get global bluetooth_on
        BLUETOOTH	28	2	exception:java.lang.IllegalArgumentException
        BLUETOOTH	29	0	string:settings get global bluetooth_on
        BLUETOOTH	29	1	string:settings get global bluetooth_on
        BLUETOOTH	29	2	exception:java.lang.IllegalArgumentException
        BLUETOOTH	30	0	string:settings get global bluetooth_on
        BLUETOOTH	30	1	string:settings get global bluetooth_on
        BLUETOOTH	30	2	exception:java.lang.IllegalArgumentException
        BLUETOOTH	31	0	string:settings get global bluetooth_on
        BLUETOOTH	31	1	string:settings get global bluetooth_on
        BLUETOOTH	31	2	exception:java.lang.IllegalArgumentException
        BLUETOOTH	32	0	string:settings get global bluetooth_on
        BLUETOOTH	32	1	string:settings get global bluetooth_on
        BLUETOOTH	32	2	exception:java.lang.IllegalArgumentException
        BLUETOOTH	33	0	string:settings get global bluetooth_on
        BLUETOOTH	33	1	string:settings get global bluetooth_on
        BLUETOOTH	33	2	exception:java.lang.IllegalArgumentException
        BLUETOOTH	34	0	string:settings get global bluetooth_on
        BLUETOOTH	34	1	string:settings get global bluetooth_on
        BLUETOOTH	34	2	exception:java.lang.IllegalArgumentException
        BLUETOOTH	35	0	string:settings get global bluetooth_on
        BLUETOOTH	35	1	string:settings get global bluetooth_on
        BLUETOOTH	35	2	exception:java.lang.IllegalArgumentException
        BLUETOOTH	36	0	string:settings get global bluetooth_on
        BLUETOOTH	36	1	string:settings get global bluetooth_on
        BLUETOOTH	36	2	exception:java.lang.IllegalArgumentException
        AIRPLANE	23	0	null
        AIRPLANE	23	1	null
        AIRPLANE	23	2	exception:java.lang.IllegalArgumentException
        AIRPLANE	24	0	null
        AIRPLANE	24	1	null
        AIRPLANE	24	2	exception:java.lang.IllegalArgumentException
        AIRPLANE	25	0	null
        AIRPLANE	25	1	null
        AIRPLANE	25	2	exception:java.lang.IllegalArgumentException
        AIRPLANE	26	0	null
        AIRPLANE	26	1	null
        AIRPLANE	26	2	exception:java.lang.IllegalArgumentException
        AIRPLANE	27	0	null
        AIRPLANE	27	1	null
        AIRPLANE	27	2	exception:java.lang.IllegalArgumentException
        AIRPLANE	28	0	null
        AIRPLANE	28	1	null
        AIRPLANE	28	2	exception:java.lang.IllegalArgumentException
        AIRPLANE	29	0	null
        AIRPLANE	29	1	null
        AIRPLANE	29	2	exception:java.lang.IllegalArgumentException
        AIRPLANE	30	0	string:cmd connectivity airplane-mode
        AIRPLANE	30	1	string:cmd connectivity airplane-mode
        AIRPLANE	30	2	exception:java.lang.IllegalArgumentException
        AIRPLANE	31	0	string:cmd connectivity airplane-mode
        AIRPLANE	31	1	string:cmd connectivity airplane-mode
        AIRPLANE	31	2	exception:java.lang.IllegalArgumentException
        AIRPLANE	32	0	string:cmd connectivity airplane-mode
        AIRPLANE	32	1	string:cmd connectivity airplane-mode
        AIRPLANE	32	2	exception:java.lang.IllegalArgumentException
        AIRPLANE	33	0	string:cmd connectivity airplane-mode
        AIRPLANE	33	1	string:cmd connectivity airplane-mode
        AIRPLANE	33	2	exception:java.lang.IllegalArgumentException
        AIRPLANE	34	0	string:cmd connectivity airplane-mode
        AIRPLANE	34	1	string:cmd connectivity airplane-mode
        AIRPLANE	34	2	exception:java.lang.IllegalArgumentException
        AIRPLANE	35	0	string:cmd connectivity airplane-mode
        AIRPLANE	35	1	string:cmd connectivity airplane-mode
        AIRPLANE	35	2	exception:java.lang.IllegalArgumentException
        AIRPLANE	36	0	string:cmd connectivity airplane-mode
        AIRPLANE	36	1	string:cmd connectivity airplane-mode
        AIRPLANE	36	2	exception:java.lang.IllegalArgumentException
        LOCATION	23	0	string:settings get secure location_mode
        LOCATION	23	1	string:settings get secure location_mode
        LOCATION	23	2	exception:java.lang.IllegalArgumentException
        LOCATION	24	0	string:settings get secure location_mode
        LOCATION	24	1	string:settings get secure location_mode
        LOCATION	24	2	exception:java.lang.IllegalArgumentException
        LOCATION	25	0	string:settings get secure location_mode
        LOCATION	25	1	string:settings get secure location_mode
        LOCATION	25	2	exception:java.lang.IllegalArgumentException
        LOCATION	26	0	string:settings get secure location_mode
        LOCATION	26	1	string:settings get secure location_mode
        LOCATION	26	2	exception:java.lang.IllegalArgumentException
        LOCATION	27	0	string:settings get secure location_mode
        LOCATION	27	1	string:settings get secure location_mode
        LOCATION	27	2	exception:java.lang.IllegalArgumentException
        LOCATION	28	0	string:settings get secure location_mode
        LOCATION	28	1	string:settings get secure location_mode
        LOCATION	28	2	exception:java.lang.IllegalArgumentException
        LOCATION	29	0	string:settings get secure location_mode
        LOCATION	29	1	string:settings get secure location_mode
        LOCATION	29	2	exception:java.lang.IllegalArgumentException
        LOCATION	30	0	string:cmd location is-location-enabled
        LOCATION	30	1	string:cmd location is-location-enabled
        LOCATION	30	2	exception:java.lang.IllegalArgumentException
        LOCATION	31	0	string:cmd location is-location-enabled
        LOCATION	31	1	string:cmd location is-location-enabled
        LOCATION	31	2	exception:java.lang.IllegalArgumentException
        LOCATION	32	0	string:cmd location is-location-enabled
        LOCATION	32	1	string:cmd location is-location-enabled
        LOCATION	32	2	exception:java.lang.IllegalArgumentException
        LOCATION	33	0	string:cmd location is-location-enabled
        LOCATION	33	1	string:cmd location is-location-enabled
        LOCATION	33	2	exception:java.lang.IllegalArgumentException
        LOCATION	34	0	string:cmd location is-location-enabled
        LOCATION	34	1	string:cmd location is-location-enabled
        LOCATION	34	2	exception:java.lang.IllegalArgumentException
        LOCATION	35	0	string:cmd location is-location-enabled
        LOCATION	35	1	string:cmd location is-location-enabled
        LOCATION	35	2	exception:java.lang.IllegalArgumentException
        LOCATION	36	0	string:cmd location is-location-enabled
        LOCATION	36	1	string:cmd location is-location-enabled
        LOCATION	36	2	exception:java.lang.IllegalArgumentException
        BIOMETRICS	23	0	string:settings get secure biometric_keyguard_enabled
        BIOMETRICS	23	1	string:settings get secure biometric_keyguard_enabled
        BIOMETRICS	23	2	exception:java.lang.IllegalArgumentException
        BIOMETRICS	24	0	string:settings get secure biometric_keyguard_enabled
        BIOMETRICS	24	1	string:settings get secure biometric_keyguard_enabled
        BIOMETRICS	24	2	exception:java.lang.IllegalArgumentException
        BIOMETRICS	25	0	string:settings get secure biometric_keyguard_enabled
        BIOMETRICS	25	1	string:settings get secure biometric_keyguard_enabled
        BIOMETRICS	25	2	exception:java.lang.IllegalArgumentException
        BIOMETRICS	26	0	string:settings get secure biometric_keyguard_enabled
        BIOMETRICS	26	1	string:settings get secure biometric_keyguard_enabled
        BIOMETRICS	26	2	exception:java.lang.IllegalArgumentException
        BIOMETRICS	27	0	string:settings get secure biometric_keyguard_enabled
        BIOMETRICS	27	1	string:settings get secure biometric_keyguard_enabled
        BIOMETRICS	27	2	exception:java.lang.IllegalArgumentException
        BIOMETRICS	28	0	string:settings get secure biometric_keyguard_enabled
        BIOMETRICS	28	1	string:settings get secure biometric_keyguard_enabled
        BIOMETRICS	28	2	exception:java.lang.IllegalArgumentException
        BIOMETRICS	29	0	string:settings get secure biometric_keyguard_enabled
        BIOMETRICS	29	1	string:settings get secure biometric_keyguard_enabled
        BIOMETRICS	29	2	exception:java.lang.IllegalArgumentException
        BIOMETRICS	30	0	string:settings get secure biometric_keyguard_enabled
        BIOMETRICS	30	1	string:settings get secure biometric_keyguard_enabled
        BIOMETRICS	30	2	exception:java.lang.IllegalArgumentException
        BIOMETRICS	31	0	string:settings get secure biometric_keyguard_enabled
        BIOMETRICS	31	1	string:settings get secure biometric_keyguard_enabled
        BIOMETRICS	31	2	exception:java.lang.IllegalArgumentException
        BIOMETRICS	32	0	string:settings get secure biometric_keyguard_enabled
        BIOMETRICS	32	1	string:settings get secure biometric_keyguard_enabled
        BIOMETRICS	32	2	exception:java.lang.IllegalArgumentException
        BIOMETRICS	33	0	string:settings get secure biometric_keyguard_enabled
        BIOMETRICS	33	1	string:settings get secure biometric_keyguard_enabled
        BIOMETRICS	33	2	exception:java.lang.IllegalArgumentException
        BIOMETRICS	34	0	string:settings get secure biometric_keyguard_enabled
        BIOMETRICS	34	1	string:settings get secure biometric_keyguard_enabled
        BIOMETRICS	34	2	exception:java.lang.IllegalArgumentException
        BIOMETRICS	35	0	string:settings get secure biometric_keyguard_enabled
        BIOMETRICS	35	1	string:settings get secure biometric_keyguard_enabled
        BIOMETRICS	35	2	exception:java.lang.IllegalArgumentException
        BIOMETRICS	36	0	string:settings get secure biometric_keyguard_enabled
        BIOMETRICS	36	1	string:settings get secure biometric_keyguard_enabled
        BIOMETRICS	36	2	exception:java.lang.IllegalArgumentException
        APP_SUSPEND	23	0	exception:java.lang.IllegalArgumentException
        APP_SUSPEND	23	1	null
        APP_SUSPEND	23	2	exception:java.lang.IllegalArgumentException
        APP_SUSPEND	24	0	exception:java.lang.IllegalArgumentException
        APP_SUSPEND	24	1	string:dumpsys package com.example.app
        APP_SUSPEND	24	2	exception:java.lang.IllegalArgumentException
        APP_SUSPEND	25	0	exception:java.lang.IllegalArgumentException
        APP_SUSPEND	25	1	string:dumpsys package com.example.app
        APP_SUSPEND	25	2	exception:java.lang.IllegalArgumentException
        APP_SUSPEND	26	0	exception:java.lang.IllegalArgumentException
        APP_SUSPEND	26	1	string:dumpsys package com.example.app
        APP_SUSPEND	26	2	exception:java.lang.IllegalArgumentException
        APP_SUSPEND	27	0	exception:java.lang.IllegalArgumentException
        APP_SUSPEND	27	1	string:dumpsys package com.example.app
        APP_SUSPEND	27	2	exception:java.lang.IllegalArgumentException
        APP_SUSPEND	28	0	exception:java.lang.IllegalArgumentException
        APP_SUSPEND	28	1	string:dumpsys package com.example.app
        APP_SUSPEND	28	2	exception:java.lang.IllegalArgumentException
        APP_SUSPEND	29	0	exception:java.lang.IllegalArgumentException
        APP_SUSPEND	29	1	string:dumpsys package com.example.app
        APP_SUSPEND	29	2	exception:java.lang.IllegalArgumentException
        APP_SUSPEND	30	0	exception:java.lang.IllegalArgumentException
        APP_SUSPEND	30	1	string:dumpsys package com.example.app
        APP_SUSPEND	30	2	exception:java.lang.IllegalArgumentException
        APP_SUSPEND	31	0	exception:java.lang.IllegalArgumentException
        APP_SUSPEND	31	1	string:dumpsys package com.example.app
        APP_SUSPEND	31	2	exception:java.lang.IllegalArgumentException
        APP_SUSPEND	32	0	exception:java.lang.IllegalArgumentException
        APP_SUSPEND	32	1	string:dumpsys package com.example.app
        APP_SUSPEND	32	2	exception:java.lang.IllegalArgumentException
        APP_SUSPEND	33	0	exception:java.lang.IllegalArgumentException
        APP_SUSPEND	33	1	string:dumpsys package com.example.app
        APP_SUSPEND	33	2	exception:java.lang.IllegalArgumentException
        APP_SUSPEND	34	0	exception:java.lang.IllegalArgumentException
        APP_SUSPEND	34	1	string:dumpsys package com.example.app
        APP_SUSPEND	34	2	exception:java.lang.IllegalArgumentException
        APP_SUSPEND	35	0	exception:java.lang.IllegalArgumentException
        APP_SUSPEND	35	1	string:dumpsys package com.example.app
        APP_SUSPEND	35	2	exception:java.lang.IllegalArgumentException
        APP_SUSPEND	36	0	exception:java.lang.IllegalArgumentException
        APP_SUSPEND	36	1	string:dumpsys package com.example.app
        APP_SUSPEND	36	2	exception:java.lang.IllegalArgumentException
        NOTIFICATION_BLOCK	23	0	exception:java.lang.IllegalArgumentException
        NOTIFICATION_BLOCK	23	1	string:dumpsys notification
        NOTIFICATION_BLOCK	23	2	exception:java.lang.IllegalArgumentException
        NOTIFICATION_BLOCK	24	0	exception:java.lang.IllegalArgumentException
        NOTIFICATION_BLOCK	24	1	string:dumpsys notification
        NOTIFICATION_BLOCK	24	2	exception:java.lang.IllegalArgumentException
        NOTIFICATION_BLOCK	25	0	exception:java.lang.IllegalArgumentException
        NOTIFICATION_BLOCK	25	1	string:dumpsys notification
        NOTIFICATION_BLOCK	25	2	exception:java.lang.IllegalArgumentException
        NOTIFICATION_BLOCK	26	0	exception:java.lang.IllegalArgumentException
        NOTIFICATION_BLOCK	26	1	string:dumpsys notification
        NOTIFICATION_BLOCK	26	2	exception:java.lang.IllegalArgumentException
        NOTIFICATION_BLOCK	27	0	exception:java.lang.IllegalArgumentException
        NOTIFICATION_BLOCK	27	1	string:dumpsys notification
        NOTIFICATION_BLOCK	27	2	exception:java.lang.IllegalArgumentException
        NOTIFICATION_BLOCK	28	0	exception:java.lang.IllegalArgumentException
        NOTIFICATION_BLOCK	28	1	string:dumpsys notification
        NOTIFICATION_BLOCK	28	2	exception:java.lang.IllegalArgumentException
        NOTIFICATION_BLOCK	29	0	exception:java.lang.IllegalArgumentException
        NOTIFICATION_BLOCK	29	1	string:dumpsys notification
        NOTIFICATION_BLOCK	29	2	exception:java.lang.IllegalArgumentException
        NOTIFICATION_BLOCK	30	0	exception:java.lang.IllegalArgumentException
        NOTIFICATION_BLOCK	30	1	string:dumpsys notification
        NOTIFICATION_BLOCK	30	2	exception:java.lang.IllegalArgumentException
        NOTIFICATION_BLOCK	31	0	exception:java.lang.IllegalArgumentException
        NOTIFICATION_BLOCK	31	1	string:dumpsys notification
        NOTIFICATION_BLOCK	31	2	exception:java.lang.IllegalArgumentException
        NOTIFICATION_BLOCK	32	0	exception:java.lang.IllegalArgumentException
        NOTIFICATION_BLOCK	32	1	string:dumpsys notification
        NOTIFICATION_BLOCK	32	2	exception:java.lang.IllegalArgumentException
        NOTIFICATION_BLOCK	33	0	exception:java.lang.IllegalArgumentException
        NOTIFICATION_BLOCK	33	1	string:dumpsys package com.example.app
        NOTIFICATION_BLOCK	33	2	exception:java.lang.IllegalArgumentException
        NOTIFICATION_BLOCK	34	0	exception:java.lang.IllegalArgumentException
        NOTIFICATION_BLOCK	34	1	string:dumpsys package com.example.app
        NOTIFICATION_BLOCK	34	2	exception:java.lang.IllegalArgumentException
        NOTIFICATION_BLOCK	35	0	exception:java.lang.IllegalArgumentException
        NOTIFICATION_BLOCK	35	1	string:dumpsys package com.example.app
        NOTIFICATION_BLOCK	35	2	exception:java.lang.IllegalArgumentException
        NOTIFICATION_BLOCK	36	0	exception:java.lang.IllegalArgumentException
        NOTIFICATION_BLOCK	36	1	string:dumpsys package com.example.app
        NOTIFICATION_BLOCK	36	2	exception:java.lang.IllegalArgumentException
        WHITELIST_EDIT	23	0	string:dumpsys deviceidle whitelist
        WHITELIST_EDIT	23	1	string:dumpsys deviceidle whitelist
        WHITELIST_EDIT	23	2	exception:java.lang.IllegalArgumentException
        WHITELIST_EDIT	24	0	string:dumpsys deviceidle whitelist
        WHITELIST_EDIT	24	1	string:dumpsys deviceidle whitelist
        WHITELIST_EDIT	24	2	exception:java.lang.IllegalArgumentException
        WHITELIST_EDIT	25	0	string:dumpsys deviceidle whitelist
        WHITELIST_EDIT	25	1	string:dumpsys deviceidle whitelist
        WHITELIST_EDIT	25	2	exception:java.lang.IllegalArgumentException
        WHITELIST_EDIT	26	0	string:dumpsys deviceidle whitelist
        WHITELIST_EDIT	26	1	string:dumpsys deviceidle whitelist
        WHITELIST_EDIT	26	2	exception:java.lang.IllegalArgumentException
        WHITELIST_EDIT	27	0	string:dumpsys deviceidle whitelist
        WHITELIST_EDIT	27	1	string:dumpsys deviceidle whitelist
        WHITELIST_EDIT	27	2	exception:java.lang.IllegalArgumentException
        WHITELIST_EDIT	28	0	string:dumpsys deviceidle whitelist
        WHITELIST_EDIT	28	1	string:dumpsys deviceidle whitelist
        WHITELIST_EDIT	28	2	exception:java.lang.IllegalArgumentException
        WHITELIST_EDIT	29	0	string:dumpsys deviceidle whitelist
        WHITELIST_EDIT	29	1	string:dumpsys deviceidle whitelist
        WHITELIST_EDIT	29	2	exception:java.lang.IllegalArgumentException
        WHITELIST_EDIT	30	0	string:dumpsys deviceidle whitelist
        WHITELIST_EDIT	30	1	string:dumpsys deviceidle whitelist
        WHITELIST_EDIT	30	2	exception:java.lang.IllegalArgumentException
        WHITELIST_EDIT	31	0	string:dumpsys deviceidle whitelist
        WHITELIST_EDIT	31	1	string:dumpsys deviceidle whitelist
        WHITELIST_EDIT	31	2	exception:java.lang.IllegalArgumentException
        WHITELIST_EDIT	32	0	string:dumpsys deviceidle whitelist
        WHITELIST_EDIT	32	1	string:dumpsys deviceidle whitelist
        WHITELIST_EDIT	32	2	exception:java.lang.IllegalArgumentException
        WHITELIST_EDIT	33	0	string:dumpsys deviceidle whitelist
        WHITELIST_EDIT	33	1	string:dumpsys deviceidle whitelist
        WHITELIST_EDIT	33	2	exception:java.lang.IllegalArgumentException
        WHITELIST_EDIT	34	0	string:dumpsys deviceidle whitelist
        WHITELIST_EDIT	34	1	string:dumpsys deviceidle whitelist
        WHITELIST_EDIT	34	2	exception:java.lang.IllegalArgumentException
        WHITELIST_EDIT	35	0	string:dumpsys deviceidle whitelist
        WHITELIST_EDIT	35	1	string:dumpsys deviceidle whitelist
        WHITELIST_EDIT	35	2	exception:java.lang.IllegalArgumentException
        WHITELIST_EDIT	36	0	string:dumpsys deviceidle whitelist
        WHITELIST_EDIT	36	1	string:dumpsys deviceidle whitelist
        WHITELIST_EDIT	36	2	exception:java.lang.IllegalArgumentException
        FOCUSED_APP	23	0	string:dumpsys activity activities
        FOCUSED_APP	23	1	string:dumpsys activity activities
        FOCUSED_APP	23	2	exception:java.lang.IllegalArgumentException
        FOCUSED_APP	24	0	string:dumpsys activity activities
        FOCUSED_APP	24	1	string:dumpsys activity activities
        FOCUSED_APP	24	2	exception:java.lang.IllegalArgumentException
        FOCUSED_APP	25	0	string:dumpsys activity activities
        FOCUSED_APP	25	1	string:dumpsys activity activities
        FOCUSED_APP	25	2	exception:java.lang.IllegalArgumentException
        FOCUSED_APP	26	0	string:dumpsys activity activities
        FOCUSED_APP	26	1	string:dumpsys activity activities
        FOCUSED_APP	26	2	exception:java.lang.IllegalArgumentException
        FOCUSED_APP	27	0	string:dumpsys activity activities
        FOCUSED_APP	27	1	string:dumpsys activity activities
        FOCUSED_APP	27	2	exception:java.lang.IllegalArgumentException
        FOCUSED_APP	28	0	string:dumpsys activity activities
        FOCUSED_APP	28	1	string:dumpsys activity activities
        FOCUSED_APP	28	2	exception:java.lang.IllegalArgumentException
        FOCUSED_APP	29	0	string:dumpsys activity activities
        FOCUSED_APP	29	1	string:dumpsys activity activities
        FOCUSED_APP	29	2	exception:java.lang.IllegalArgumentException
        FOCUSED_APP	30	0	string:dumpsys activity activities
        FOCUSED_APP	30	1	string:dumpsys activity activities
        FOCUSED_APP	30	2	exception:java.lang.IllegalArgumentException
        FOCUSED_APP	31	0	string:dumpsys activity activities
        FOCUSED_APP	31	1	string:dumpsys activity activities
        FOCUSED_APP	31	2	exception:java.lang.IllegalArgumentException
        FOCUSED_APP	32	0	string:dumpsys activity activities
        FOCUSED_APP	32	1	string:dumpsys activity activities
        FOCUSED_APP	32	2	exception:java.lang.IllegalArgumentException
        FOCUSED_APP	33	0	string:dumpsys activity activities
        FOCUSED_APP	33	1	string:dumpsys activity activities
        FOCUSED_APP	33	2	exception:java.lang.IllegalArgumentException
        FOCUSED_APP	34	0	string:dumpsys activity activities
        FOCUSED_APP	34	1	string:dumpsys activity activities
        FOCUSED_APP	34	2	exception:java.lang.IllegalArgumentException
        FOCUSED_APP	35	0	string:dumpsys activity activities
        FOCUSED_APP	35	1	string:dumpsys activity activities
        FOCUSED_APP	35	2	exception:java.lang.IllegalArgumentException
        FOCUSED_APP	36	0	string:dumpsys activity activities
        FOCUSED_APP	36	1	string:dumpsys activity activities
        FOCUSED_APP	36	2	exception:java.lang.IllegalArgumentException
        SENSOR_PRIVACY_ALL	23	0	string:dumpsys sensor_privacy
        SENSOR_PRIVACY_ALL	23	1	string:dumpsys sensor_privacy
        SENSOR_PRIVACY_ALL	23	2	exception:java.lang.IllegalArgumentException
        SENSOR_PRIVACY_ALL	24	0	string:dumpsys sensor_privacy
        SENSOR_PRIVACY_ALL	24	1	string:dumpsys sensor_privacy
        SENSOR_PRIVACY_ALL	24	2	exception:java.lang.IllegalArgumentException
        SENSOR_PRIVACY_ALL	25	0	string:dumpsys sensor_privacy
        SENSOR_PRIVACY_ALL	25	1	string:dumpsys sensor_privacy
        SENSOR_PRIVACY_ALL	25	2	exception:java.lang.IllegalArgumentException
        SENSOR_PRIVACY_ALL	26	0	string:dumpsys sensor_privacy
        SENSOR_PRIVACY_ALL	26	1	string:dumpsys sensor_privacy
        SENSOR_PRIVACY_ALL	26	2	exception:java.lang.IllegalArgumentException
        SENSOR_PRIVACY_ALL	27	0	string:dumpsys sensor_privacy
        SENSOR_PRIVACY_ALL	27	1	string:dumpsys sensor_privacy
        SENSOR_PRIVACY_ALL	27	2	exception:java.lang.IllegalArgumentException
        SENSOR_PRIVACY_ALL	28	0	string:dumpsys sensor_privacy
        SENSOR_PRIVACY_ALL	28	1	string:dumpsys sensor_privacy
        SENSOR_PRIVACY_ALL	28	2	exception:java.lang.IllegalArgumentException
        SENSOR_PRIVACY_ALL	29	0	string:dumpsys sensor_privacy
        SENSOR_PRIVACY_ALL	29	1	string:dumpsys sensor_privacy
        SENSOR_PRIVACY_ALL	29	2	exception:java.lang.IllegalArgumentException
        SENSOR_PRIVACY_ALL	30	0	string:dumpsys sensor_privacy
        SENSOR_PRIVACY_ALL	30	1	string:dumpsys sensor_privacy
        SENSOR_PRIVACY_ALL	30	2	exception:java.lang.IllegalArgumentException
        SENSOR_PRIVACY_ALL	31	0	string:dumpsys sensor_privacy
        SENSOR_PRIVACY_ALL	31	1	string:dumpsys sensor_privacy
        SENSOR_PRIVACY_ALL	31	2	exception:java.lang.IllegalArgumentException
        SENSOR_PRIVACY_ALL	32	0	string:dumpsys sensor_privacy
        SENSOR_PRIVACY_ALL	32	1	string:dumpsys sensor_privacy
        SENSOR_PRIVACY_ALL	32	2	exception:java.lang.IllegalArgumentException
        SENSOR_PRIVACY_ALL	33	0	string:dumpsys sensor_privacy
        SENSOR_PRIVACY_ALL	33	1	string:dumpsys sensor_privacy
        SENSOR_PRIVACY_ALL	33	2	exception:java.lang.IllegalArgumentException
        SENSOR_PRIVACY_ALL	34	0	string:dumpsys sensor_privacy
        SENSOR_PRIVACY_ALL	34	1	string:dumpsys sensor_privacy
        SENSOR_PRIVACY_ALL	34	2	exception:java.lang.IllegalArgumentException
        SENSOR_PRIVACY_ALL	35	0	string:dumpsys sensor_privacy
        SENSOR_PRIVACY_ALL	35	1	string:dumpsys sensor_privacy
        SENSOR_PRIVACY_ALL	35	2	exception:java.lang.IllegalArgumentException
        SENSOR_PRIVACY_ALL	36	0	string:dumpsys sensor_privacy
        SENSOR_PRIVACY_ALL	36	1	string:dumpsys sensor_privacy
        SENSOR_PRIVACY_ALL	36	2	exception:java.lang.IllegalArgumentException
        SETPROP_DOZE	23	0	string:getprop persist.sys.doze_powersave
        SETPROP_DOZE	23	1	string:getprop persist.sys.doze_powersave
        SETPROP_DOZE	23	2	exception:java.lang.IllegalArgumentException
        SETPROP_DOZE	24	0	string:getprop persist.sys.doze_powersave
        SETPROP_DOZE	24	1	string:getprop persist.sys.doze_powersave
        SETPROP_DOZE	24	2	exception:java.lang.IllegalArgumentException
        SETPROP_DOZE	25	0	string:getprop persist.sys.doze_powersave
        SETPROP_DOZE	25	1	string:getprop persist.sys.doze_powersave
        SETPROP_DOZE	25	2	exception:java.lang.IllegalArgumentException
        SETPROP_DOZE	26	0	string:getprop persist.sys.doze_powersave
        SETPROP_DOZE	26	1	string:getprop persist.sys.doze_powersave
        SETPROP_DOZE	26	2	exception:java.lang.IllegalArgumentException
        SETPROP_DOZE	27	0	string:getprop persist.sys.doze_powersave
        SETPROP_DOZE	27	1	string:getprop persist.sys.doze_powersave
        SETPROP_DOZE	27	2	exception:java.lang.IllegalArgumentException
        SETPROP_DOZE	28	0	string:getprop persist.sys.doze_powersave
        SETPROP_DOZE	28	1	string:getprop persist.sys.doze_powersave
        SETPROP_DOZE	28	2	exception:java.lang.IllegalArgumentException
        SETPROP_DOZE	29	0	string:getprop persist.sys.doze_powersave
        SETPROP_DOZE	29	1	string:getprop persist.sys.doze_powersave
        SETPROP_DOZE	29	2	exception:java.lang.IllegalArgumentException
        SETPROP_DOZE	30	0	string:getprop persist.sys.doze_powersave
        SETPROP_DOZE	30	1	string:getprop persist.sys.doze_powersave
        SETPROP_DOZE	30	2	exception:java.lang.IllegalArgumentException
        SETPROP_DOZE	31	0	string:getprop persist.sys.doze_powersave
        SETPROP_DOZE	31	1	string:getprop persist.sys.doze_powersave
        SETPROP_DOZE	31	2	exception:java.lang.IllegalArgumentException
        SETPROP_DOZE	32	0	string:getprop persist.sys.doze_powersave
        SETPROP_DOZE	32	1	string:getprop persist.sys.doze_powersave
        SETPROP_DOZE	32	2	exception:java.lang.IllegalArgumentException
        SETPROP_DOZE	33	0	string:getprop persist.sys.doze_powersave
        SETPROP_DOZE	33	1	string:getprop persist.sys.doze_powersave
        SETPROP_DOZE	33	2	exception:java.lang.IllegalArgumentException
        SETPROP_DOZE	34	0	string:getprop persist.sys.doze_powersave
        SETPROP_DOZE	34	1	string:getprop persist.sys.doze_powersave
        SETPROP_DOZE	34	2	exception:java.lang.IllegalArgumentException
        SETPROP_DOZE	35	0	string:getprop persist.sys.doze_powersave
        SETPROP_DOZE	35	1	string:getprop persist.sys.doze_powersave
        SETPROP_DOZE	35	2	exception:java.lang.IllegalArgumentException
        SETPROP_DOZE	36	0	string:getprop persist.sys.doze_powersave
        SETPROP_DOZE	36	1	string:getprop persist.sys.doze_powersave
        SETPROP_DOZE	36	2	exception:java.lang.IllegalArgumentException
        PM_DISABLE	23	0	exception:java.lang.IllegalArgumentException
        PM_DISABLE	23	1	string:dumpsys package com.example.app
        PM_DISABLE	23	2	exception:java.lang.IllegalArgumentException
        PM_DISABLE	24	0	exception:java.lang.IllegalArgumentException
        PM_DISABLE	24	1	string:dumpsys package com.example.app
        PM_DISABLE	24	2	exception:java.lang.IllegalArgumentException
        PM_DISABLE	25	0	exception:java.lang.IllegalArgumentException
        PM_DISABLE	25	1	string:dumpsys package com.example.app
        PM_DISABLE	25	2	exception:java.lang.IllegalArgumentException
        PM_DISABLE	26	0	exception:java.lang.IllegalArgumentException
        PM_DISABLE	26	1	string:dumpsys package com.example.app
        PM_DISABLE	26	2	exception:java.lang.IllegalArgumentException
        PM_DISABLE	27	0	exception:java.lang.IllegalArgumentException
        PM_DISABLE	27	1	string:dumpsys package com.example.app
        PM_DISABLE	27	2	exception:java.lang.IllegalArgumentException
        PM_DISABLE	28	0	exception:java.lang.IllegalArgumentException
        PM_DISABLE	28	1	string:dumpsys package com.example.app
        PM_DISABLE	28	2	exception:java.lang.IllegalArgumentException
        PM_DISABLE	29	0	exception:java.lang.IllegalArgumentException
        PM_DISABLE	29	1	string:dumpsys package com.example.app
        PM_DISABLE	29	2	exception:java.lang.IllegalArgumentException
        PM_DISABLE	30	0	exception:java.lang.IllegalArgumentException
        PM_DISABLE	30	1	string:dumpsys package com.example.app
        PM_DISABLE	30	2	exception:java.lang.IllegalArgumentException
        PM_DISABLE	31	0	exception:java.lang.IllegalArgumentException
        PM_DISABLE	31	1	string:dumpsys package com.example.app
        PM_DISABLE	31	2	exception:java.lang.IllegalArgumentException
        PM_DISABLE	32	0	exception:java.lang.IllegalArgumentException
        PM_DISABLE	32	1	string:dumpsys package com.example.app
        PM_DISABLE	32	2	exception:java.lang.IllegalArgumentException
        PM_DISABLE	33	0	exception:java.lang.IllegalArgumentException
        PM_DISABLE	33	1	string:dumpsys package com.example.app
        PM_DISABLE	33	2	exception:java.lang.IllegalArgumentException
        PM_DISABLE	34	0	exception:java.lang.IllegalArgumentException
        PM_DISABLE	34	1	string:dumpsys package com.example.app
        PM_DISABLE	34	2	exception:java.lang.IllegalArgumentException
        PM_DISABLE	35	0	exception:java.lang.IllegalArgumentException
        PM_DISABLE	35	1	string:dumpsys package com.example.app
        PM_DISABLE	35	2	exception:java.lang.IllegalArgumentException
        PM_DISABLE	36	0	exception:java.lang.IllegalArgumentException
        PM_DISABLE	36	1	string:dumpsys package com.example.app
        PM_DISABLE	36	2	exception:java.lang.IllegalArgumentException
    """.trimIndent()
}
