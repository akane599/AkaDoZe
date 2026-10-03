package com.akylas.enforcedoze.access;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.Collections;

import org.junit.Test;

public class JavaApiTest {
    @Test
    public void javaCallersCanUsePinnedCoreSurfaceAndDefaultTimeout() {
        CommandBackend backend = new CommandBackend() {
            @Override public AccessLevel getLevel() { return AccessLevel.SHELL; }
            @Override public CommandResult execute(String command) {
                return CommandResult.snapshot(0, Collections.singletonList(command),
                        Collections.emptyList(), 0, false);
            }
            @Override public void reset() { }
        };
        try (CommandLane lane = new CommandLane(backend, "java-api")) {
            CommandRunner runner = lane;
            assertEquals(8000, CommandRunner.DEFAULT_TIMEOUT_MS);
            assertTrue(runner.run("default-timeout").getOk());
            assertTrue(runner.run("explicit-timeout", 1000).getOk());
            assertEquals(AccessLevel.SHELL, runner.getLevel());
        }
        assertTrue(PackageNames.isValid("com.example.app"));
        assertEquals(FeatureStatus.Available.INSTANCE,
                CapabilityResolver.status(Feature.MOTION_SENSORS, AccessLevel.APP, 36,
                        new Grants(true, false)));
        assertEquals(Collections.singletonList("cmd wifi set-wifi-enabled enabled"),
                CommandCatalog.restore(Feature.WIFI, 36, "1"));
        assertEquals("executionMode", Prefs.EXECUTION_MODE);
    }

    // Compile-check only: Android singleton/lane/listener APIs remain usable by future Java migrations.
    private void androidSurface(AccessManager manager, android.content.Context context) {
        AccessManager singleton = AccessManager.getInstance(context);
        AccessManager.Listener listener = state -> state.getLevel();
        singleton.addListener(listener);
        singleton.removeListener(listener);
        CommandRunner control = manager.control();
        CommandRunner reads = manager.reads();
        manager.grantHelpers();
    }
}
