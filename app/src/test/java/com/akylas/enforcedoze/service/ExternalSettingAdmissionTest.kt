package com.akylas.enforcedoze.service

import com.akylas.enforcedoze.ExternalControlReceiver.Admission
import com.akylas.enforcedoze.access.ExternalControlPolicy
import com.akylas.enforcedoze.access.ExternalControlPolicy.Action
import com.akylas.enforcedoze.access.ExternalControlPolicy.Decision
import com.akylas.enforcedoze.access.ExternalControlPolicy.DenialReason
import com.akylas.enforcedoze.access.ExternalControlPolicy.SettingValue
import org.junit.Assert.*
import org.junit.Test

class ExternalSettingAdmissionTest {
    @Test fun allowedPolicyWithoutParsedValueDeniesBeforeWritingOrVerifying() {
        val policy = Admission.Policy<String> { Decision() }
        val reasons = mutableListOf<DenialReason>()
        var writes = 0
        Admission.setting(policy.apply("true"), { reasons += it }, { writes++ })
        assertEquals(listOf(DenialReason.UNVERIFIED_SETTING_VALUE), reasons)
        assertEquals("no editor, commit, reload or VERIFIED callback", 0, writes)
    }

    @Test fun parsedBooleanAndIntegerKeepTheirValuesAndWriteExactlyOnce() {
        val cases = listOf(
            Triple("disableWhenCharging", "false", SettingValue.BooleanValue(false)),
            Triple("disableWhenCharging", "true", SettingValue.BooleanValue(true)),
            Triple("dozeEnterDelay", "0", SettingValue.IntegerValue(0)),
            Triple("dozeEnterDelay", "1800", SettingValue.IntegerValue(1800)),
        )
        for ((key, text, expected) in cases) {
            val writes = mutableListOf<SettingValue>()
            Admission.setting(ExternalControlPolicy.evaluate(Action.CHANGE_SETTING, true, true, key, text),
                { fail("unexpected denial: $it") }, { writes += it })
            assertEquals(listOf(expected), writes)
        }
    }

    @Test fun policyDenialKeepsItsReasonAndCannotWrite() {
        val reasons = mutableListOf<DenialReason>()
        Admission.setting(Decision(DenialReason.PROTECTED_SETTING, SettingValue.BooleanValue(true)),
            { reasons += it }, { fail("denied policy must not write") })
        assertEquals(listOf(DenialReason.PROTECTED_SETTING), reasons)
    }
}
