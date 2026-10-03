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
        EnterResult result = controller.enter(config, controller.getCurrentGeneration(), () -> true);
        assertEquals(EnterStatus.COMPLETED, result.getStatus());
        assertEquals(StepStatus.SKIPPED, result.getSteps().get(0).getStatus());
        assertTrue(events.stream().anyMatch(event -> event.getType() == EventType.SKIPPED));
        assertTrue(controller.reconcile().getComplete());
        assertEquals(Decision.REFORCE.INSTANCE, new WatchdogPolicy(clock).onIdleChanged(
                DozeStateParser.parse("mState=ACTIVE"), false, false, true));
        assertEquals(Collections.singletonList(Action.RESTORE_SENSORS), SafetyNet.check(
                new SensorModeReading(SensorMode.RESTRICTED, "com.example.app"), false, AccessLevel.APP));
    }
}
