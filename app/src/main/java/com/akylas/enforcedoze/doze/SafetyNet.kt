package com.akylas.enforcedoze.doze

import com.akylas.enforcedoze.monitor.EventCodes

import com.akylas.enforcedoze.access.AccessLevel
import com.akylas.enforcedoze.doze.parse.SensorModeReading

enum class Action(val detail: String) {
    RESTORE_SENSORS(EventCodes.RESTORE_SENSORS),
    UNFORCE(EventCodes.UNFORCE),
    RAISE_DEBT(EventCodes.RAISE_DEBT),
}

object SafetyNet {
    /** APP assumes DUMP, as required to obtain the supplied reading. Execute and verify via control. */
    @JvmStatic
    fun check(
        sensorReading: SensorModeReading,
        forceIdle: Boolean?,
        level: AccessLevel,
        ownToken: String,
        ledgerHasForce: Boolean,
    ): List<Action> {
        val actions = linkedSetOf<Action>()
        if (sensorReading.mode == SensorMode.RESTRICTED && sensorReading.allowToken == ownToken) {
            actions.add(if (level == AccessLevel.NONE) Action.RAISE_DEBT else Action.RESTORE_SENSORS)
        }
        if (forceIdle == true && ledgerHasForce) {
            actions.add(if (level >= AccessLevel.SHELL) Action.UNFORCE else Action.RAISE_DEBT)
        }
        return actions.toList()
    }
}
