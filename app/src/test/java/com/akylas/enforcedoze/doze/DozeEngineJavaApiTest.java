package com.akylas.enforcedoze.doze;

import com.akylas.enforcedoze.access.AccessLevel;
import com.akylas.enforcedoze.access.CapabilityResolver;
import com.akylas.enforcedoze.access.CommandCatalog;
import com.akylas.enforcedoze.access.CommandResult;
import com.akylas.enforcedoze.access.CommandRunner;
import com.akylas.enforcedoze.access.Grants;
import com.akylas.enforcedoze.doze.parse.DozeStateParser;
import com.akylas.enforcedoze.doze.parse.SensorModeReading;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.*;

/** Compile and exercise the API the Java service/monitor adapters will consume. */
public class DozeEngineJavaApiTest {
    @Test public void javaCallerCanEnterReconcileAndConsumeTypedPolicyEvents() {
        CommandRunner runner = new CommandRunner() {
            @Override public AccessLevel getLevel() { return AccessLevel.APP; }
            @Override public CommandResult run(String command) { return run(command, 8000); }
            @Override public CommandResult run(String command, long timeoutMs) {
                throw new AssertionError("No supported mutation requested");
            }
        };
        Clock clock = new Clock() {
            @Override public long elapsedRealtime() { return 0; }
            @Override public long wallTime() { return 0; }
        };
        LedgerStore store = new LedgerStore() {
            private String durable = "";
            @Override public RestoreLedger load() { return RestoreLedgerCodec.decode(durable).getLedger(); }
            @Override public void save(RestoreLedger ledger) { durable = RestoreLedgerCodec.encode(ledger); }
        };
        List<DozeEvent> events = new ArrayList<>();
        DozeController controller = new DozeController(runner, CommandCatalog.INSTANCE,
                CapabilityResolver.INSTANCE, store, clock, events::add, 36, new Grants(true, false));
        DozeConfig config = new DozeConfig(36, AccessLevel.APP, new Grants(true, false), false);
        assertEquals(com.akylas.enforcedoze.service.SessionMode.FORCE, config.getMode());
        // Every pre-mode positional constructor remains callable from Java.
        DozeConfig oldFull = new DozeConfig(36, AccessLevel.APP, new Grants(true, false), false,
                "com.akylas.enforcedoze", true, Collections.emptySet(), Collections.emptySet(),
                Collections.emptySet(), true, null);
        assertEquals(com.akylas.enforcedoze.service.SessionMode.FORCE, oldFull.getMode());
        DozeConfig sensors = new DozeConfig(36, AccessLevel.APP, new Grants(true, false), false,
                "com.akylas.enforcedoze", true, Collections.emptySet(), Collections.emptySet(),
                Collections.emptySet(), true, null, com.akylas.enforcedoze.service.SessionMode.SENSOR_ONLY);
        assertTrue(controller.enter(sensors, controller.getCurrentGeneration(), () -> true).getSteps().isEmpty());
        EnterResult result = controller.enter(config, controller.getCurrentGeneration(), () -> true);
        assertEquals(EnterStatus.COMPLETED, result.getStatus());
        assertEquals(StepStatus.SKIPPED, result.getSteps().get(0).getStatus());
        assertTrue(events.stream().anyMatch(event -> event.getType() == EventType.SKIPPED));
        ExitResult exit = controller.reconcile();
        assertTrue(exit.getComplete());
        assertTrue(exit.getErrors().isEmpty());
        for (java.lang.reflect.Constructor<?> constructor : DozeController.class.getConstructors()) {
            assertTrue("API and grants are mandatory", constructor.getParameterCount() >= 8);
            assertEquals(int.class, constructor.getParameterTypes()[6]);
            assertEquals(Grants.class, constructor.getParameterTypes()[7]);
            if (!constructor.isSynthetic()) {
                assertTrue("only diagnostics and readiness are optional", constructor.getParameterCount() <= 10);
            }
        }
        LedgerEntry entry = new LedgerEntry(com.akylas.enforcedoze.access.Feature.LOCATION,
                null, "1", 0, 0, false, 34);
        assertEquals(Integer.valueOf(34), entry.getApiLevel());
        assertEquals(Decision.REFORCE.INSTANCE, new WatchdogPolicy(clock).onIdleChanged(
                DozeStateParser.parse("mState=ACTIVE"), false, false, true));
        assertEquals(Collections.singletonList(Action.RESTORE_SENSORS), SafetyNet.check(
                new SensorModeReading(SensorMode.RESTRICTED, "com.example.app"), false, AccessLevel.APP,
                "com.example.app", false));
    }
}
