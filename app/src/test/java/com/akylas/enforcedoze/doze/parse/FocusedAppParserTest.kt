package com.akylas.enforcedoze.doze.parse

import com.akylas.enforcedoze.access.CommandResult
import com.akylas.enforcedoze.access.Reason
import org.junit.Assert.assertEquals
import org.junit.Test

class FocusedAppParserTest {
    private val focusedWindow = """
        WINDOW MANAGER WINDOWS (dumpsys window windows)
          mCurrentFocus=Window{a4b1c23 u0 com.example.reader/com.example.reader.MainActivity}
          mFocusedApp=ActivityRecord{be3942d u0 com.example.reader/.MainActivity t31}
    """.trimIndent()

    private fun result(output: String, exit: Int = 0, timedOut: Boolean = false) =
        CommandResult(exit, output.lines(), emptyList(), 1, timedOut)

    private fun unknown(output: String, exit: Int = 0, timedOut: Boolean = false) {
        assertEquals(FocusedApps.Unknown(Reason.UNVERIFIED), FocusedAppParser.parse(result(output, exit, timedOut)))
    }

    @Test fun focusedWindowAndActivityAreKnownValidatedPackages() {
        assertEquals(FocusedApps.Known(setOf("com.example.reader")), FocusedAppParser.parse(result(focusedWindow)))
    }

    @Test fun explicitNullFocusIsTheOnlyKnownEmptyReading() {
        assertEquals(FocusedApps.Known(emptySet<String>()), FocusedAppParser.parse(result("""
            WINDOW MANAGER WINDOWS (dumpsys window windows)
              mCurrentFocus=null
              mFocusedApp=null
        """.trimIndent())))
    }

    @Test fun failedKilledAndTimedOutReadsAreUnknownEvenWithPlausibleStdout() {
        unknown(focusedWindow, exit = 1)
        unknown(focusedWindow, exit = 137)
        unknown(focusedWindow, timedOut = true)
    }

    @Test fun emptyGarbageAndMissingFocusFieldsAreUnknownNotNoFocusedApp() {
        unknown("")
        unknown("Permission Denial: can't dump WindowManager")
        unknown("WINDOW MANAGER WINDOWS\n  Window #0 Window{123abc u0 com.example.reader/.MainActivity}")
        unknown("mCurrentFocus=Window{malformed}\nmFocusedApp=null")
    }

    @Test fun everyFocusRowMustBeParseableAndPackageNamesRemainValidated() {
        unknown("mCurrentFocus=Window{123abc u0 com.example.reader;evil/.MainActivity}")
        unknown("mCurrentFocus=Window{123abc u0 invalid/.MainActivity}")
        unknown("$focusedWindow\nmCurrentFocus=Window{malformed}")
    }

    @Test fun multipleDisplaysAndNullWindowWithFocusedActivityKeepKnownPackages() {
        assertEquals(FocusedApps.Known(setOf("com.example.reader", "com.example.video")), FocusedAppParser.parse(result("""
            $focusedWindow
            mCurrentFocus=Window{abc123 u10 com.example.video/.PlayerActivity}
        """.trimIndent())))
        assertEquals(FocusedApps.Known(setOf("com.example.reader")), FocusedAppParser.parse(result("""
            mCurrentFocus=null
            mFocusedApp=ActivityRecord{be3942d u0 com.example.reader/.MainActivity t31}
        """.trimIndent())))
    }

    @Test fun notificationShadeDoesNotHideFocusedActivity() {
        assertEquals(FocusedApps.Known(setOf("com.example.reader")), FocusedAppParser.parse(result("""
            mCurrentFocus=Window{abc123 u0 NotificationShade}
            mFocusedApp=ActivityRecord{be3942d u0 com.example.reader/.MainActivity t31}
        """.trimIndent())))
    }

    @Test fun legacyAppWindowTokenExposesNestedActivity() {
        assertEquals(FocusedApps.Known(setOf("com.example.reader")), FocusedAppParser.parse(result("""
            mFocusedApp=AppWindowToken{4f2a1b token=Token{9c1d2e ActivityRecord{77aa u0 com.example.reader/.MainActivity t5}}}
        """.trimIndent())))
    }

    @Test fun legacyStatusBarDoesNotHideWrappedFocusedActivity() {
        assertEquals(FocusedApps.Known(setOf("com.example.reader")), FocusedAppParser.parse(result("""
            mCurrentFocus=Window{abc123 u0 StatusBar}
            mFocusedApp=AppWindowToken{4f2a1b token=Token{9c1d2e ActivityRecord{77aa u0 com.example.reader/.MainActivity t5}}}
        """.trimIndent())))
    }

    @Test fun keyguardAfterFocusedActivityDoesNotDiscardKnownPackage() {
        assertEquals(FocusedApps.Known(setOf("com.example.reader")), FocusedAppParser.parse(result("""
            $focusedWindow
            mCurrentFocus=Window{abc123 u10 Keyguard}
        """.trimIndent())))
    }

    @Test fun malformedWrappedRowsRemainUnknownEvenWithKnownPackageEvidence() {
        for (focus in listOf(
            "AppWindowToken{4f2a1b token=Token{9c1d2e ActivityRecord{77aa u0 com.example.reader/.MainActivity t5}}",
            "AppWindowToken{4f2a1b token=Token{9c1d2e ActivityRecord{77aa u0 com.example.reader/.MainActivity t5}}}}",
            "}ActivityRecord{77aa u0 com.example.reader/.MainActivity t5}{",
            "AppWindowToken{4f2a1b token=Token{9c1d2e ActivityRecord{77aa u0 invalid/.MainActivity t5}}}",
            "AppWindowToken{4f2a1b token=Token{9c1d2e ActivityRecord{77aa u0 com.example.reader;evil/.MainActivity t5}}}",
        )) {
            unknown("$focusedWindow\nmFocusedApp=$focus")
        }
        unknown("$focusedWindow\nmCurrentFocus=Window{abc123 u0 NotificationShade")
    }

    @Test fun nonActivityFocusedWindowIsUnknownWithoutFocusedPackageEvidence() {
        unknown("mCurrentFocus=Window{abc123 u0 NotificationShade}")
        unknown("mCurrentFocus=Window{abc123 u0 StatusBar}\nmFocusedApp=null")
    }
}
