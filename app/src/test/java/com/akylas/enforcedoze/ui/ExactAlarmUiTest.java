package com.akylas.enforcedoze.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import com.akylas.enforcedoze.doze.ExactAlarmAccessPolicy;

import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

/** SQ-117 (SQ-41 2B): exact-alarm status and the explicit Alarms & reminders request in Settings. */
public class ExactAlarmUiTest {

    private static String read(String path) throws IOException {
        return new String(Files.readAllBytes(Paths.get(path)), StandardCharsets.UTF_8);
    }

    private static String between(String source, String start, String end) {
        int from = source.indexOf(start);
        assertTrue("missing " + start, from >= 0);
        int to = source.indexOf(end, from + start.length());
        assertTrue("missing " + end + " after " + start, to > from);
        return source.substring(from, to);
    }

    private static String string(String strings, String name) {
        return between(strings, "<string name=\"" + name + "\"", "</string>");
    }

    private static int count(String source, String needle) {
        int n = 0;
        for (int i = source.indexOf(needle); i >= 0; i = source.indexOf(needle, i + needle.length())) n++;
        return n;
    }

    private static String settings() throws IOException {
        return read("src/main/java/com/akylas/enforcedoze/SettingsActivity.java");
    }

    // --- API < 31: no special access exists, so no status and no request action ---

    @Test
    public void actionIsHiddenAndUnsupportedBelowApi31() {
        for (int api = 23; api <= 30; api++) {
            assertFalse("API " + api + " has no exact-alarm special access", AccessUi.canRequestExactAlarm(api));
            for (boolean allowed : new boolean[] {false, true}) {
                assertEquals(AccessUi.ExactAlarmStatus.HIDDEN, AccessUi.exactAlarmStatus(api, true, allowed));
            }
        }
        for (int api = 31; api <= 36; api++) assertTrue(AccessUi.canRequestExactAlarm(api));
    }

    @Test
    public void requestHelperIsApiGuardedPackageScopedAndFallsBackWhenSettingsLacksTheScreen() throws IOException {
        String ui = read("src/main/java/com/akylas/enforcedoze/ui/AccessUi.java");
        String request = between(ui, "public static boolean requestExactAlarmAccess(", "\n    }\n");
        int guard = request.indexOf("Build.VERSION.SDK_INT < Build.VERSION_CODES.S");
        int launch = request.indexOf("Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM");
        assertTrue("never build the API 31 action below 31", guard >= 0 && launch > guard);
        assertTrue(request.substring(guard, launch).contains("return false;"));
        assertTrue("opens the per-app page, not the global list",
                request.contains("Uri.fromParts(\"package\", activity.getPackageName(), null)"));
        String fallback = request.substring(request.indexOf("catch (ActivityNotFoundException"));
        assertTrue("OEM Settings without the AOSP screen fall back to app details",
                fallback.contains("Settings.ACTION_APPLICATION_DETAILS_SETTINGS"));
        assertFalse("the launch result is not a grant: no activity-result reliance",
                request.contains("ForResult") || request.contains("RESULT_OK"));
    }

    // --- Grant status matrix (API 31+) ---

    @Test
    public void grantStatusMatrix() {
        for (int api = 31; api <= 36; api++) {
            assertEquals("no periods: nothing to time", AccessUi.ExactAlarmStatus.HIDDEN,
                    AccessUi.exactAlarmStatus(api, false, true));
            assertEquals(AccessUi.ExactAlarmStatus.HIDDEN, AccessUi.exactAlarmStatus(api, false, false));
            assertEquals(AccessUi.ExactAlarmStatus.EXACT, AccessUi.exactAlarmStatus(api, true, true));
            assertEquals("denied degrades to best-effort timing, not to off", AccessUi.ExactAlarmStatus.BEST_EFFORT,
                    AccessUi.exactAlarmStatus(api, true, false));
        }
        assertNotEquals(AccessUi.exactAlarmStatusText(AccessUi.ExactAlarmStatus.EXACT),
                AccessUi.exactAlarmStatusText(AccessUi.ExactAlarmStatus.BEST_EFFORT));
    }

    @Test
    public void statusWordingIsHonestAboutBothOutcomes() throws IOException {
        String strings = read("src/main/res/values/strings.xml");
        String exact = string(strings, "exact_alarm_status_exact").toLowerCase();
        String bestEffort = string(strings, "exact_alarm_status_best_effort").toLowerCase();
        // Effective capability (an allowlist exemption also counts), not the raw permission bit.
        assertTrue(exact.contains("available"));
        assertTrue(bestEffort.contains("best-effort"));
        assertTrue("without access a boundary may not restart a backgrounded service",
                bestEffort.contains("may not restart") && bestEffort.contains("background"));
        for (String overclaim : new String[] {"will fail", "never", "always", "cannot"}) {
            assertFalse("denial does not mean every alarm fails: " + overclaim, bestEffort.contains(overclaim));
        }
        for (String overclaim : new String[] {"guarantee", "always", "every device"}) {
            assertFalse("exact access does not guarantee every OEM start: " + overclaim, exact.contains(overclaim));
        }
        assertTrue("exact access still leaves room for OEM delays", exact.contains("may"));
    }

    // --- No request without a user action ---

    @Test
    public void requestOnlyRunsFromTheExplicitPreferenceTap() throws IOException {
        String settings = settings();
        assertEquals("one launch site", 1, count(settings, "AccessUi.requestExactAlarmAccess("));
        String click = between(settings, "exactAlarm.setOnPreferenceClickListener(", "});");
        assertTrue(click.contains("AccessUi.requestExactAlarmAccess(requireActivity())"));
        String lifecycle = between(settings, "public void onStart()", "public void onStop()");
        assertFalse("no automatic prompt on start/resume", lifecycle.contains("requestExactAlarmAccess("));
        assertFalse(lifecycle.contains("ACTION_REQUEST_SCHEDULE_EXACT_ALARM"));
        String main = read("src/main/java/com/akylas/enforcedoze/MainActivity.java");
        assertFalse("no cold-start prompt on the main screen", main.contains("requestExactAlarmAccess("));
        assertFalse(main.contains("ACTION_REQUEST_SCHEDULE_EXACT_ALARM"));
        String prefs = read("src/main/res/xml/prefs.xml");
        String entry = between(prefs, "android:key=\"exactAlarmAccess\"", "/>");
        assertTrue("a status row, not a stored setting", entry.contains("android:persistent=\"false\""));
        assertFalse("no new screen", entry.contains("<intent"));
        assertTrue("sits with the custom periods it describes",
                prefs.indexOf("android:key=\"exactAlarmAccess\"") > prefs.indexOf("android:key=\"customDozePeriods\"")
                        && prefs.indexOf("android:key=\"exactAlarmAccess\"") < prefs.indexOf("android:key=\"blacklistAppNotifications\""));
    }

    // --- Return from the system page: requery, never trust the launch ---

    @Test
    public void statusRefreshesFromAFreshRequeryOnReturnFromGrantAndDenial() {
        // Granted on the system page, then back: the requery reports access.
        ExactAlarmAccessPolicy.Access granted = ExactAlarmAccessPolicy.requery(34, true, true, true);
        assertEquals(AccessUi.ExactAlarmStatus.EXACT, AccessUi.exactAlarmStatus(34, true, granted.getExactAllowed()));
        // Denied (or left untouched), then back: best-effort, still re-armed inexactly.
        ExactAlarmAccessPolicy.Access denied = ExactAlarmAccessPolicy.requery(34, false, true, true);
        assertEquals(AccessUi.ExactAlarmStatus.BEST_EFFORT, AccessUi.exactAlarmStatus(34, true, denied.getExactAllowed()));
        assertTrue(denied.getShouldRearm());
    }

    @Test
    public void settingsResumeRequeriesThroughTheSharedSeamAndLaunchingIsNotAGrant() throws IOException {
        String settings = settings();
        String resume = between(settings, "public void onResume()", "public void onPause()");
        int requery = resume.indexOf("exactAlarmAccess = Utils.requeryExactAlarmAccess(requireContext());");
        assertTrue("every return (grant, denial or untouched) asks the platform again", requery >= 0);
        assertTrue(resume.indexOf("renderExactAlarmStatus();") > requery);
        assertFalse("resume never prompts or catches up the current window",
                resume.contains("applyForceDozeSchedule(") || resume.contains("startForceDozeService("));
        String click = between(settings, "exactAlarm.setOnPreferenceClickListener(", "});");
        assertFalse("launching Settings is not a grant result", click.contains("renderExactAlarmStatus")
                || click.contains("setSummary") || click.contains("exactAlarmAccess ="));
        String render = between(settings, "private void renderExactAlarmStatus()", "\n        }\n");
        assertTrue(render.contains("AccessUi.exactAlarmStatus(Build.VERSION.SDK_INT,"));
        assertTrue(render.contains("exactAlarmAccess.getExactAllowed()"));
        assertTrue("hidden below 31 and without periods", render.contains("setVisible("));
        assertFalse("rendering never re-arms or requeries", render.contains("requeryExactAlarmAccess(")
                || render.contains("scheduleNextCustomDozePeriodBoundary("));
        String changed = between(settings, "public void onSharedPreferenceChanged(", "reloadSettings(getActivity());");
        assertTrue("adding or removing periods updates the row",
                between(changed, "if (\"customDozePeriods\".equals(key))", "}").contains("renderExactAlarmStatus();"));
    }

    // --- Master off ---

    @Test
    public void masterOffStillShowsStatusButCannotRearm() {
        for (boolean actual : new boolean[] {false, true}) {
            ExactAlarmAccessPolicy.Access off = ExactAlarmAccessPolicy.requery(36, actual, false, true);
            assertFalse("a foreground return cannot revive master-off", off.getShouldRearm());
            assertNotEquals("the row still describes the capability", AccessUi.ExactAlarmStatus.HIDDEN,
                    AccessUi.exactAlarmStatus(36, true, off.getExactAllowed()));
        }
    }
}
