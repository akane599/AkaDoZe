package com.akylas.enforcedoze;

import com.akylas.enforcedoze.access.AccessLevel;
import com.akylas.enforcedoze.access.CommandResult;
import com.akylas.enforcedoze.access.CommandRunner;
import com.akylas.enforcedoze.access.Grants;
import com.akylas.enforcedoze.access.Reason;
import org.junit.Test;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import static org.junit.Assert.*;

public class DozeTunableHandlerTest {
    private static final String REQUEST = "light_after_inactive_to=123456,idle_factor=2";
    private static final String SETTINGS = "settings put global device_idle_constants " + REQUEST;
    private static final Grants NO_GRANTS = new Grants(false, false);

    private static final class Runner implements CommandRunner {
        final AccessLevel level;
        final List<String> commands;
        final ArrayDeque<CommandResult> replies;

        Runner(AccessLevel level, List<String> commands, CommandResult... replies) {
            this.level = level;
            this.commands = commands;
            this.replies = new ArrayDeque<>(Arrays.asList(replies));
        }
        @Override public AccessLevel getLevel() { return level; }
        @Override public CommandResult run(String command) { return run(command, 8000); }
        @Override public CommandResult run(String command, long timeoutMs) {
            commands.add(command);
            return replies.removeFirst();
        }
    }

    private static CommandResult result(int exit, String... lines) {
        return new CommandResult(exit, Arrays.asList(lines), Collections.emptyList(), 1, false);
    }

    @Test public void shellApi36DeniedDeviceConfigFallsBackOnceAndIsApplied() {
        List<String> commands = new ArrayList<>();
        CommandResult denied = new CommandResult(255, Collections.emptyList(),
                Collections.singletonList("SecurityException: flag must be allowlisted"), 1, false);
        Runner control = new Runner(AccessLevel.SHELL, commands, denied, denied, result(0));
        Runner reads = new Runner(AccessLevel.SHELL, commands,
                result(0, "Settings:", "  light_after_inactive_to=+5m0s0ms", "  idle_factor=2.0"),
                result(0, "Settings:", "  light_after_inactive_to=+2m3s456ms", "  idle_factor=2.0"));

        DozeTunableHandler.ApplyResult applied = DozeTunableHandler.apply(control, reads, 36, NO_GRANTS, REQUEST);

        assertEquals(Arrays.asList("cmd device_config put device_idle light_after_inactive_to 123456",
                "cmd device_config put device_idle idle_factor 2", "dumpsys deviceidle", SETTINGS,
                "dumpsys deviceidle"), commands);
        assertTrue(applied.allApplied());
        assertEquals(DozeTunableHandler.Outcome.APPLIED, applied.keys.get("light_after_inactive_to"));
        assertEquals(Arrays.asList("light_after_inactive_to", "idle_factor"), applied.applied);
        assertNull(applied.reason);
    }

    @Test public void successfulExitWithOldReadbackStillFallsBackAndUsesOnlyFinalValues() {
        List<String> commands = new ArrayList<>();
        Runner control = new Runner(AccessLevel.SHELL, commands, result(0), result(0), result(0));
        Runner reads = new Runner(AccessLevel.SHELL, commands,
                result(0, "Settings:", "  light_after_inactive_to=300000", "  idle_factor=2.0"),
                result(0, "Settings:", "  light_after_inactive_to=123456", "  idle_factor=3.0"));

        DozeTunableHandler.ApplyResult applied = DozeTunableHandler.apply(control, reads, 36, NO_GRANTS, REQUEST);

        assertEquals(5, commands.size());
        assertEquals(SETTINGS, commands.get(3));
        assertEquals(Collections.singletonList("light_after_inactive_to"), applied.applied);
        assertEquals(Collections.singletonList("idle_factor"), applied.notEffective);
        assertEquals(Reason.NOT_EFFECTIVE_ON_THIS_VERSION, applied.reason);
    }

    @Test public void ineffectiveFallbackStopsAfterSecondReadbackWithExistingReason() {
        List<String> commands = new ArrayList<>();
        Runner control = new Runner(AccessLevel.SHELL, commands, result(255), result(255), result(1));
        CommandResult oldValues = result(0, "Settings:", "  light_after_inactive_to=300000", "  idle_factor=3.0");
        Runner reads = new Runner(AccessLevel.SHELL, commands, oldValues, oldValues);

        DozeTunableHandler.ApplyResult applied = DozeTunableHandler.apply(control, reads, 36, NO_GRANTS, REQUEST);

        assertEquals(5, commands.size());
        assertEquals(SETTINGS, commands.get(3));
        assertEquals("dumpsys deviceidle", commands.get(4));
        assertEquals(Arrays.asList("light_after_inactive_to", "idle_factor"), applied.notEffective);
        assertEquals(DozeTunableHandler.Outcome.NOT_EFFECTIVE, applied.keys.get("light_after_inactive_to"));
        assertEquals(Reason.NOT_EFFECTIVE_ON_THIS_VERSION, applied.reason);
        assertFalse(applied.allApplied());
    }

    @Test public void effectiveReadbackSkipsFallbackEvenIfDeviceConfigExitFails() {
        List<String> commands = new ArrayList<>();
        Runner control = new Runner(AccessLevel.SHELL, commands, result(255), result(255));
        Runner reads = new Runner(AccessLevel.SHELL, commands,
                result(0, "Settings:", "  light_after_inactive_to=123456", "  idle_factor=2"));

        assertTrue(DozeTunableHandler.apply(control, reads, 36, NO_GRANTS, REQUEST).allApplied());
        assertEquals(3, commands.size());
        assertEquals("dumpsys deviceidle", commands.get(2));
    }

    @Test public void failedFinalReadbackDoesNotRetainPreviouslyAppliedKeys() {
        List<String> commands = new ArrayList<>();
        Runner control = new Runner(AccessLevel.ROOT, commands, result(0), result(0), result(0));
        Runner reads = new Runner(AccessLevel.ROOT, commands,
                result(0, "Settings:", "  light_after_inactive_to=123456", "  idle_factor=3"),
                result(1, "Settings:", "  light_after_inactive_to=123456", "  idle_factor=2"));

        DozeTunableHandler.ApplyResult applied = DozeTunableHandler.apply(control, reads, 36, NO_GRANTS, REQUEST);

        assertEquals(5, commands.size());
        assertTrue(applied.applied.isEmpty());
        assertEquals(Arrays.asList("light_after_inactive_to", "idle_factor"), applied.failed);
        assertEquals(Reason.UNVERIFIED, applied.reason);
    }

    @Test public void emptyPrivilegedRequestDoesNotClearGlobalSettings() {
        List<String> commands = new ArrayList<>();
        Runner control = new Runner(AccessLevel.SHELL, commands);
        Runner reads = new Runner(AccessLevel.SHELL, commands, result(0, "Settings:", "  idle_factor=2"));

        DozeTunableHandler.ApplyResult applied = DozeTunableHandler.apply(control, reads, 36, NO_GRANTS, "");

        assertEquals(Collections.singletonList("dumpsys deviceidle"), commands);
        assertTrue(applied.keys.isEmpty());
        assertFalse(applied.allApplied());
        assertNull(applied.reason);
    }

    @Test public void failedFirstReadbackCanRecoverThroughOneSettingsPass() {
        List<String> commands = new ArrayList<>();
        Runner control = new Runner(AccessLevel.SHELL, commands, result(0), result(0), result(0));
        Runner reads = new Runner(AccessLevel.SHELL, commands, result(1),
                result(0, "Settings:", "  light_after_inactive_to=123456", "  idle_factor=2"));

        assertTrue(DozeTunableHandler.apply(control, reads, 36, NO_GRANTS, REQUEST).allApplied());
        assertEquals(5, commands.size());
        assertEquals(SETTINGS, commands.get(3));
    }
}
