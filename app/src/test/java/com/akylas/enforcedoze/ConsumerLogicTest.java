package com.akylas.enforcedoze;

import com.akylas.enforcedoze.access.AccessLevel;
import com.akylas.enforcedoze.ui.ModeSwitchRules;
import com.akylas.enforcedoze.access.CommandResult;
import com.akylas.enforcedoze.access.CommandRunner;
import com.akylas.enforcedoze.access.Grants;
import com.akylas.enforcedoze.access.Reason;
import org.junit.Test;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import static org.junit.Assert.*;

public class ConsumerLogicTest {
    private static class Runner implements CommandRunner {
        final AccessLevel level;
        final CommandResult reply;
        final List<String> commands = new ArrayList<>();
        Runner(AccessLevel level, CommandResult reply) { this.level = level; this.reply = reply; }
        @Override public AccessLevel getLevel() { return level; }
        @Override public CommandResult run(String command) { return run(command, 8000); }
        @Override public CommandResult run(String command, long timeoutMs) {
            commands.add(command);
            return reply;
        }
    }
    private static CommandResult result(int exit, String... lines) {
        return new CommandResult(exit, Arrays.asList(lines), Collections.emptyList(), 1, false);
    }

    @Test public void whitelistParserDeduplicatesAndRejectsMalformedOutput() {
        WhitelistAppsActivity.WhitelistResult parsed = WhitelistAppsActivity.parseWhitelist(result(0,
                "system,com.android.phone,1001", "user,com.example.app,10123", "user,com.example.app,10123"));
        assertTrue(parsed.verified);
        assertEquals(Arrays.asList("com.android.phone", "com.example.app"), parsed.packages);
        assertFalse(WhitelistAppsActivity.parseWhitelist(result(0, "Error: denied")).verified);
        assertFalse(WhitelistAppsActivity.parseWhitelist(result(0, "user,com.example.app")).verified);
        assertFalse(WhitelistAppsActivity.parseWhitelist(result(1, "user,com.example.app,10123")).verified);
        assertTrue(WhitelistAppsActivity.parseWhitelist(result(0)).verified);
    }

    @Test public void whitelistDiagnosticsSurviveActivityAdapterAndEditReadback() {
        WhitelistAppsActivity.WhitelistResult parsed = WhitelistAppsActivity.parseWhitelist(result(0,
                "user,com.example.app,10123", "OEM format"));
        assertEquals(Collections.singletonList("com.example.app"), parsed.packages);
        assertEquals(1, parsed.unparsedLineCount);
        assertEquals(com.akylas.enforcedoze.access.WhitelistParseReason.PARTIALLY_PARSED, parsed.parseReason);
        Runner control = new Runner(AccessLevel.SHELL, result(0));
        Runner reads = new Runner(AccessLevel.SHELL, result(0, "user,com.example.other,10123", "OEM format"));
        WhitelistAppsActivity.WhitelistResult edited = WhitelistAppsActivity.editWhitelist(control, reads, 36,
                new Grants(false, false), "com.example.app", true);
        assertFalse("partial output cannot prove removal by absence", edited.verified);
        assertEquals(parsed.parseReason, edited.parseReason);
        assertEquals(1, edited.unparsedLineCount);
    }

    @Test public void whitelistEditsUseCatalogControlAndVerifyThroughReads() {
        Runner control = new Runner(AccessLevel.SHELL, result(0));
        Runner reads = new Runner(AccessLevel.SHELL, result(0, "user,com.example.app,10123"));
        assertTrue(WhitelistAppsActivity.editWhitelist(control, reads, 36, new Grants(false, false), "com.example.app", false).verified);
        assertEquals(Collections.singletonList("cmd deviceidle whitelist +com.example.app"), control.commands);
        assertEquals(Collections.singletonList("dumpsys deviceidle whitelist"), reads.commands);
        assertFalse(WhitelistAppsActivity.editWhitelist(control, reads, 36, new Grants(false, false), "com.example.app", true).verified);
        assertEquals("cmd deviceidle whitelist -com.example.app", control.commands.get(1));
    }

    @Test public void exceptIdleMembershipDoesNotVerifyDeepWhitelistAdd() {
        Runner control = new Runner(AccessLevel.SHELL, result(0));
        Runner reads = new Runner(AccessLevel.SHELL, result(0, "system-excidle,com.example.app,10123"));
        WhitelistAppsActivity.WhitelistResult outcome = WhitelistAppsActivity.editWhitelist(control, reads, 36,
                new Grants(false, false), "com.example.app", false);
        assertFalse(outcome.verified);
        assertTrue(outcome.packages.isEmpty());
        assertEquals(Reason.UNVERIFIED, outcome.reason);
    }

    @Test public void whitelistRejectsInjectionAndUnavailableAccessWithoutExecuting() {
        Runner control = new Runner(AccessLevel.SHELL, result(0));
        Runner reads = new Runner(AccessLevel.SHELL, result(0));
        assertFalse(WhitelistAppsActivity.editWhitelist(control, reads, 36, new Grants(false, false), "com.example.app; id", false).verified);
        assertFalse(WhitelistAppsActivity.editWhitelist(control, reads, 36, new Grants(false, false), null, false).verified);
        assertTrue(control.commands.isEmpty());
        assertTrue(reads.commands.isEmpty());
        Runner app = new Runner(AccessLevel.APP, result(0));
        assertEquals(Reason.NO_ACCESS, WhitelistAppsActivity.editWhitelist(app, reads, 36, new Grants(true, true), "com.example.app", false).reason);
        assertTrue(app.commands.isEmpty());
    }

    @Test public void shellTunablesIncludeNamespaceAndVerifyEveryKey() {
        Runner control = new Runner(AccessLevel.SHELL, result(0));
        Runner reads = new Runner(AccessLevel.SHELL, result(0, "Settings:", "  inactive_to=+5m0s0ms", "  idle_factor=2.0", "  idle_to=42"));
        DozeTunableHandler.ApplyResult applied = DozeTunableHandler.apply(control, reads, 36,
                new Grants(false, false), "inactive_to=300000,idle_factor=2,idle_to=60,absent_to=10");
        assertEquals(Arrays.asList("cmd device_config put device_idle inactive_to 300000", "cmd device_config put device_idle idle_factor 2",
                "cmd device_config put device_idle idle_to 60", "cmd device_config put device_idle absent_to 10"), control.commands);
        assertEquals(DozeTunableHandler.Outcome.APPLIED, applied.keys.get("inactive_to"));
        assertEquals(DozeTunableHandler.Outcome.APPLIED, applied.keys.get("idle_factor"));
        assertEquals(DozeTunableHandler.Outcome.NOT_EFFECTIVE, applied.keys.get("idle_to"));
        assertEquals(DozeTunableHandler.Outcome.NOT_EFFECTIVE, applied.keys.get("absent_to"));
        assertEquals(Arrays.asList("inactive_to", "idle_factor"), applied.applied);
        assertEquals(Arrays.asList("idle_to", "absent_to"), applied.notEffective);
        assertTrue(applied.failed.isEmpty());
        assertFalse(applied.allApplied());
        assertEquals(Reason.NOT_EFFECTIVE_ON_THIS_VERSION, applied.reason);
    }

    @Test public void shellTunablesAtApi30UseGlobalFallbackAndVerifyEveryKey() {
        Runner control = new Runner(AccessLevel.SHELL, result(0));
        Runner reads = new Runner(AccessLevel.SHELL, result(0, "Settings:", "  inactive_to=+5m0s0ms", "  idle_factor=2.0", "  idle_to=42"));
        DozeTunableHandler.ApplyResult applied = DozeTunableHandler.apply(control, reads, 30,
                new Grants(false, false), "inactive_to=300000,idle_factor=2,idle_to=60,absent_to=10");
        assertEquals(Collections.singletonList("settings put global device_idle_constants inactive_to=300000,idle_factor=2,idle_to=60,absent_to=10"), control.commands);
        assertEquals(Collections.singletonList("dumpsys deviceidle"), reads.commands);
        assertEquals(Arrays.asList("inactive_to", "idle_factor"), applied.applied);
        assertEquals(Arrays.asList("idle_to", "absent_to"), applied.notEffective);
        assertTrue(applied.failed.isEmpty());
        assertFalse(applied.allApplied());
        assertEquals(Reason.NOT_EFFECTIVE_ON_THIS_VERSION, applied.reason);
    }

    @Test public void privilegedTunablesSwitchToDeviceConfigAtApi31() {
        for (AccessLevel level : Arrays.asList(AccessLevel.SHELL, AccessLevel.ROOT)) {
            for (int api : new int[] {30, 31, 33, 34}) {
                Runner control = new Runner(level, result(0));
                Runner reads = new Runner(level, result(0, "Settings:", "  idle_to=60000"));
                DozeTunableHandler.ApplyResult applied = DozeTunableHandler.apply(control, reads, api,
                        new Grants(false, false), "idle_to=60000");
                assertEquals(level + " at API " + api, Collections.singletonList(api < 31
                        ? "settings put global device_idle_constants idle_to=60000"
                        : "cmd device_config put device_idle idle_to 60000"), control.commands);
                assertEquals(Collections.singletonList("dumpsys deviceidle"), reads.commands);
                assertTrue(applied.allApplied());
            }
        }
    }

    @Test public void appWssUsesGlobalFallbackAndOnlyReadbackEstablishesSuccess() {
        Runner control = new Runner(AccessLevel.APP, result(1));
        Runner reads = new Runner(AccessLevel.APP, result(0, "Settings:", "  idle_to=60000"));
        assertTrue(DozeTunableHandler.apply(control, reads, 36, new Grants(true, true), "idle_to=60000").allApplied());
        assertEquals(Collections.singletonList("settings put global device_idle_constants idle_to=60000"), control.commands);
        Runner failedReads = new Runner(AccessLevel.APP, result(1, "Settings:", "  idle_to=60000"));
        assertEquals(DozeTunableHandler.Outcome.UNVERIFIED,
                DozeTunableHandler.apply(control, failedReads, 36, new Grants(true, true), "idle_to=60000").keys.get("idle_to"));
        Runner unknownReads = new Runner(AccessLevel.APP, result(0, "OEM output"));
        assertFalse(DozeTunableHandler.apply(control, unknownReads, 36, new Grants(true, true), "idle_to=60000").allApplied());
    }

    @Test public void tunablesWithoutWssDoNotMutateAndApi23UsesFallback() {
        Runner app = new Runner(AccessLevel.APP, result(0));
        Runner reads = new Runner(AccessLevel.APP, result(0));
        assertEquals(Reason.NEEDS_WRITE_SECURE_SETTINGS,
                DozeTunableHandler.apply(app, reads, 36, new Grants(true, false), "idle_to=10").reason);
        assertTrue(app.commands.isEmpty());
        assertTrue(reads.commands.isEmpty());
        Runner shell = new Runner(AccessLevel.SHELL, result(0));
        DozeTunableHandler.apply(shell, reads, 23, new Grants(false, false), "idle_to=10");
        assertEquals(Collections.singletonList("settings put global device_idle_constants idle_to=10"), shell.commands);
    }

    @Test(expected = IllegalArgumentException.class) public void tunableInjectionIsRejectedBeforeCommands() {
        Runner runner = new Runner(AccessLevel.SHELL, result(0));
        DozeTunableHandler.apply(runner, runner, 36, new Grants(false, false), "idle_to=10;id");
    }

    @Test public void durationReadbackMustBeExactAndFactorsMayUseDecimals() {
        assertTrue(DozeTunableHandler.equivalentValue("90061001", "+1d1h1m1s1ms"));
        assertTrue(DozeTunableHandler.equivalentValue("2", "2.0"));
        assertFalse(DozeTunableHandler.equivalentValue("5000", "5s OEM"));
        assertFalse(DozeTunableHandler.equivalentValue("5000", null));
    }

    @Test public void modeSwitchWaitsForPermissionAndDoesNotTreatShellAsRoot() {
        SettingsActivity.SettingsFragment.ModeSwitch change = new SettingsActivity.SettingsFragment.ModeSwitch();
        change.select("shizuku");
        // Shizuku is judged by its own transport level (last argument), root by the published level.
        assertFalse(ModeSwitchRules.switchReady(change.pending, "shizuku", AccessLevel.APP, AccessLevel.NONE));
        assertFalse(ModeSwitchRules.switchReady(change.pending, "root", AccessLevel.ROOT, AccessLevel.NONE));
        assertTrue(ModeSwitchRules.switchReady(change.pending, "shizuku", AccessLevel.SHELL, AccessLevel.SHELL));
        int token = change.consume();
        assertFalse(ModeSwitchRules.switchReady(change.pending, "shizuku", AccessLevel.SHELL, AccessLevel.SHELL));
        change.select("root");
        assertFalse(change.current(token));
        assertFalse(ModeSwitchRules.switchReady(change.pending, "root", AccessLevel.SHELL, AccessLevel.NONE));
        assertTrue(ModeSwitchRules.switchReady(change.pending, "root", AccessLevel.ROOT, AccessLevel.NONE));
    }

    private static final class PermissionRecordingFile extends File {
        int mode;
        final List<Integer> modes = new ArrayList<>();

        PermissionRecordingFile(String name, int mode) {
            super(name);
            this.mode = mode;
        }

        @Override public boolean isFile() { return true; }
        @Override public boolean setReadable(boolean readable, boolean ownerOnly) {
            int bits = ownerOnly ? 0400 : 0444;
            recordMode(readable ? mode | bits : mode & ~bits);
            return true;
        }
        @Override public boolean setWritable(boolean writable, boolean ownerOnly) {
            int bits = ownerOnly ? 0200 : 0222;
            recordMode(writable ? mode | bits : mode & ~bits);
            return true;
        }
        void recordMode(int nextMode) {
            mode = nextMode;
            modes.add(mode);
        }
    }

    private static File prefsDirectory(File... files) {
        return new File("shared_prefs") {
            @Override public File[] listFiles() { return files; }
        };
    }

    @Test public void prefsRepairNeverDropsOwnerReadWrite() {
        PermissionRecordingFile xml = new PermissionRecordingFile("doze_ledger.xml", 0666);
        PermissionRecordingFile backup = new PermissionRecordingFile("doze_ledger.xml.bak", 0600);
        PermissionRecordingFile legacy = new PermissionRecordingFile("preferences.xml", 0777);
        assertTrue(Utils.PreferencesPermissions.repairDirectory(prefsDirectory(xml, backup, legacy), (file, mode) -> {
            ((PermissionRecordingFile) file).recordMode(mode);
            return true;
        }));
        for (PermissionRecordingFile file : Arrays.asList(xml, backup, legacy)) {
            assertFalse(file.getName() + " must be repaired", file.modes.isEmpty());
            for (int mode : file.modes) {
                assertEquals(file.getName() + " owner read/write must survive every change", 0600, mode & 0600);
            }
            assertEquals(file.getName() + " must finish owner-only rw", 0600, file.mode);
            assertEquals(file.getName() + " must use one atomic mode change", Collections.singletonList(0600), file.modes);
        }
    }

    @Test public void prefsRepairFailureStillAttemptsRemainingFilesAndCanBeRetried() {
        PermissionRecordingFile denied = new PermissionRecordingFile("doze_ledger.xml", 0666);
        PermissionRecordingFile backup = new PermissionRecordingFile("doze_ledger.xml.bak", 0666);
        File nestedDirectory = new File("nested") {
            @Override public boolean isFile() { return false; }
        };
        File directory = prefsDirectory(denied, nestedDirectory, backup);
        List<File> attempts = new ArrayList<>();
        assertFalse(Utils.PreferencesPermissions.repairDirectory(directory, (file, mode) -> {
            attempts.add(file);
            assertEquals(0600, mode);
            if (file == denied) return false;
            ((PermissionRecordingFile) file).recordMode(mode);
            return true;
        }));
        assertEquals(Arrays.asList(denied, backup), attempts);
        assertEquals(0666, denied.mode);
        assertEquals(Collections.singletonList(0600), backup.modes);
        assertTrue(Utils.PreferencesPermissions.repairDirectory(directory, (file, mode) -> {
            ((PermissionRecordingFile) file).recordMode(mode);
            return true;
        }));
        assertEquals(0600, denied.mode);
        assertEquals(0600, backup.mode);
    }

    @Test public void prefsRepairKeepsMissingUnreadableAndEmptyDirectoryResults() {
        for (boolean exists : new boolean[] {false, true}) {
            File directory = new File("shared_prefs") {
                @Override public File[] listFiles() { return null; }
                @Override public boolean exists() { return exists; }
            };
            assertEquals(!exists, Utils.PreferencesPermissions.repairDirectory(directory, (file, mode) -> {
                fail("Cannot chmod files when directory listing is unavailable");
                return false;
            }));
        }
        assertTrue(Utils.PreferencesPermissions.repairDirectory(prefsDirectory(), (file, mode) -> {
            fail("Empty directory needs no chmod");
            return false;
        }));
    }

    @Test public void prefsRepairMakesExistingXmlAndBackupOwnerOnly() throws Exception {
        File directory = Files.createTempDirectory("prefs-repair-").toFile();
        try {
            File file = new File(directory, "preferences.xml");
            File backup = new File(directory, "preferences.xml.bak");
            assertTrue(file.createNewFile()); assertTrue(backup.createNewFile());
            assertTrue(file.setReadable(true, false)); assertTrue(file.setWritable(true, false));
            assertTrue(backup.setReadable(true, false)); assertTrue(backup.setWritable(true, false));
            assertTrue(Utils.PreferencesPermissions.repairDirectory(directory, (target, mode) -> {
                assertEquals(0600, mode);
                try {
                    Files.setPosixFilePermissions(target.toPath(), java.util.EnumSet.of(
                            PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
                    return true;
                } catch (java.io.IOException error) {
                    return false;
                }
            }));
            for (File target : Arrays.asList(file, backup)) {
                Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(target.toPath());
                assertTrue(permissions.contains(PosixFilePermission.OWNER_READ));
                assertTrue(permissions.contains(PosixFilePermission.OWNER_WRITE));
                assertFalse(permissions.contains(PosixFilePermission.GROUP_READ));
                assertFalse(permissions.contains(PosixFilePermission.GROUP_WRITE));
                assertFalse(permissions.contains(PosixFilePermission.OTHERS_READ));
                assertFalse(permissions.contains(PosixFilePermission.OTHERS_WRITE));
            }
        } finally {
            for (File file : directory.listFiles()) file.delete();
            directory.delete();
        }
    }

    @Test public void phoneStateDenialDoesNotDisablePersistentNotifications() {
        assertNull(MainActivity.deniedPermissionPreference(MainActivity.READ_PHONE_STATE_PERMISSION_REQUEST_CODE));
        assertEquals("showPersistentNotif", MainActivity.deniedPermissionPreference(MainActivity.POST_NOTIF_PERMISSION_REQUEST_CODE));
        assertNull(MainActivity.deniedPermissionPreference(999));
    }

    @Test public void logFilterIncludesActualServiceAndBridgeTags() {
        assertEquals("logcat -d", LogActivity.logCommand(true));
        String filtered = LogActivity.logCommand(false);
        assertTrue(filtered.contains("EnforceDoze"));
        assertTrue(filtered.contains("ForceDozeService"));
        assertTrue(filtered.contains("ShizukuHandler"));
        assertFalse(filtered.contains(","));
    }
}
