package com.akylas.enforcedoze;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.preference.PreferenceManager;

import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import com.akylas.enforcedoze.access.AccessManager;
import com.akylas.enforcedoze.access.CapabilityResolver;
import com.akylas.enforcedoze.access.CommandCatalog;
import com.akylas.enforcedoze.access.CommandResult;
import com.akylas.enforcedoze.access.ExternalControlPolicy;
import com.akylas.enforcedoze.access.ExternalControlPolicy.Action;
import com.akylas.enforcedoze.access.ExternalControlPolicy.Decision;
import com.akylas.enforcedoze.access.ExternalControlPolicy.DenialReason;
import com.akylas.enforcedoze.access.ExternalControlPolicy.SettingValue;
import com.akylas.enforcedoze.access.Feature;
import com.akylas.enforcedoze.access.FeatureStatus;
import com.akylas.enforcedoze.access.Prefs;
import com.akylas.enforcedoze.doze.DozeEvent;
import com.akylas.enforcedoze.doze.EventType;
import com.akylas.enforcedoze.service.DozeRuntime;

import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** One trust boundary for all exported automation entry points. No raw extra enters a shell command. */
public abstract class ExternalControlReceiver extends BroadcastReceiver {
    private static final long BUDGET_MS = 9_000;
    private static final ThreadPoolExecutor WORK = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(16), task -> new Thread(task, "external-control"));
    private static final ScheduledThreadPoolExecutor DEADLINES = new ScheduledThreadPoolExecutor(1,
            task -> new Thread(task, "external-control-deadlines"));
    static { DEADLINES.setRemoveOnCancelPolicy(true); }

    private enum Permission { ALLOWED, DENIED }
    private enum Outcome { REQUESTED, VERIFIED, FAILED, DENIED }
    private enum ExecutionReason {
        TIMED_OUT, BUSY, ADMISSION_CHANGED, PREFERENCE_WRITE_FAILED, PREFERENCE_WRITTEN,
        FOREGROUND_START_DENIED, SERVICE_START_REQUESTED, SERVICE_STOP_REQUESTED,
        NOT_ADMITTED, REAPPLY_REQUESTED, EXECUTION_FAILED, UNVERIFIED, COMMAND_FAILED, WHITELIST_READBACK,
    }

    private final Action action;

    protected ExternalControlReceiver(Action action) { this.action = action; }

    @Override
    public final void onReceive(Context context, Intent intent) {
        Context app = context.getApplicationContext();
        DozeRuntime runtime = MyApplication.getDozeRuntime(app);
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(app);
        String caller = Build.VERSION.SDK_INT >= 34 ? getSentFromPackage() : null;
        String key = null;
        String value = null;
        String pkg = null;
        Decision decision = decide(prefs, null, null, null);
        // Closed gates take precedence and do not require decoding untrusted extras.
        if (decision.getReason() == DenialReason.BASIC_CONTROL_DISABLED
                || decision.getReason() == DenialReason.PRIVILEGED_CONTROL_DISABLED) {
            journal(runtime, caller, Permission.DENIED, Outcome.DENIED, decision.getReason());
            return;
        }
        try {
            if (action == Action.CHANGE_SETTING) {
                key = stringExtra(intent, "settingName");
                value = stringExtra(intent, "settingValue");
            } else if (action == Action.ADD_WHITELIST || action == Action.REMOVE_WHITELIST) {
                pkg = stringExtra(intent, "packageName");
            }
            decision = decide(prefs, key, value, pkg);
        } catch (RuntimeException invalidExtra) {
            journal(runtime, caller, Permission.DENIED, Outcome.DENIED, DenialReason.INVALID_EXTRA);
            return;
        }
        if (!decision.getAllowed()) {
            journal(runtime, caller, Permission.DENIED, Outcome.DENIED, decision.getReason());
            return;
        }
        PendingResult pending = goAsync();
        Call call = new Call(app, runtime, prefs, caller, key, value, pkg, pending);
        call.timer = DEADLINES.schedule(() -> call.complete(Permission.ALLOWED, Outcome.FAILED, ExecutionReason.TIMED_OUT),
                BUDGET_MS, TimeUnit.MILLISECONDS);
        try {
            WORK.execute(call);
        } catch (RejectedExecutionException busy) {
            call.complete(Permission.ALLOWED, Outcome.FAILED, ExecutionReason.BUSY);
        }
    }

    private static String stringExtra(Intent intent, String key) {
        Bundle extras = intent == null ? null : intent.getExtras();
        Object value = extras == null ? null : extras.get(key);
        if (value != null && !(value instanceof String)) throw new IllegalArgumentException("Invalid extra type");
        return (String) value;
    }

    private Decision decide(SharedPreferences prefs, String key, String value, String pkg) {
        return ExternalControlPolicy.evaluate(action,
                prefs.getBoolean(Prefs.ALLOW_EXTERNAL_BASIC_CONTROL, Prefs.DEFAULT_ALLOW_EXTERNAL_BASIC_CONTROL),
                prefs.getBoolean(Prefs.ALLOW_EXTERNAL_PRIVILEGED_CONTROL, Prefs.DEFAULT_ALLOW_EXTERNAL_PRIVILEGED_CONTROL),
                key, value, pkg);
    }

    private void journal(DozeRuntime runtime, String caller, Permission permission, Outcome outcome, Enum<?> reason) {
        // Caller identity is platform supplied, never an Intent extra. Do not record target packages/values.
        runtime.getJournal().emit(new DozeEvent(EventType.EXTERNAL_CALL,
                permission.name().toLowerCase(java.util.Locale.ROOT) + " action=" + action.name() + " caller=" + caller
                        + " outcome=" + outcome.name() + " reason=" + reason.name()));
    }

    private final class Call implements Runnable {
        final Context app;
        final DozeRuntime runtime;
        final SharedPreferences prefs;
        final String caller, key, value, pkg;
        final PendingResult pending;
        final AtomicBoolean finished = new AtomicBoolean();
        final long deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(BUDGET_MS - 250);
        final long deadlineElapsed = SystemClock.elapsedRealtime() + BUDGET_MS - 250;
        volatile ScheduledFuture<?> timer;

        Call(Context app, DozeRuntime runtime, SharedPreferences prefs, String caller,
             String key, String value, String pkg, PendingResult pending) {
            this.app = app;
            this.runtime = runtime;
            this.prefs = prefs;
            this.caller = caller;
            this.key = key;
            this.value = value;
            this.pkg = pkg;
            this.pending = pending;
        }

        boolean live() { return !finished.get() && System.nanoTime() < deadlineNanos; }
        boolean admitted() { return live() && decide(prefs, key, value, pkg).getAllowed(); }

        void complete(Permission permission, Outcome outcome, Enum<?> reason) {
            if (!finished.compareAndSet(false, true)) return;
            ScheduledFuture<?> timeout = timer;
            if (timeout != null) timeout.cancel(false);
            WORK.remove(this);
            try { journal(runtime, caller, permission, outcome, reason); }
            finally { pending.finish(); }
        }

        @Override
        public void run() {
            if (!live()) { complete(Permission.ALLOWED, Outcome.FAILED, ExecutionReason.TIMED_OUT); return; }
            Decision current = decide(prefs, key, value, pkg);
            if (!current.getAllowed()) { complete(Permission.DENIED, Outcome.DENIED, current.getReason()); return; }
            try {
                switch (action) {
                    case ADD_WHITELIST:
                    case REMOVE_WHITELIST:
                        editWhitelist();
                        break;
                    case CHANGE_SETTING:
                        if (!admitted()) { complete(Permission.ALLOWED, Outcome.FAILED, ExecutionReason.ADMISSION_CHANGED); return; }
                        SharedPreferences.Editor editor = prefs.edit();
                        SettingValue setting = current.getValue();
                        if (setting instanceof SettingValue.BooleanValue) {
                            editor.putBoolean(key, ((SettingValue.BooleanValue) setting).getValue());
                        } else if (setting instanceof SettingValue.IntegerValue) {
                            editor.putInt(key, ((SettingValue.IntegerValue) setting).getValue());
                        }
                        if (!editor.commit()) { complete(Permission.ALLOWED, Outcome.FAILED, ExecutionReason.PREFERENCE_WRITE_FAILED); return; }
                        LocalBroadcastManager.getInstance(app).sendBroadcast(new Intent("reload-settings"));
                        complete(Permission.ALLOWED, Outcome.VERIFIED, ExecutionReason.PREFERENCE_WRITTEN);
                        break;
                    case ENABLE_SERVICE:
                        if (!admitted()) { complete(Permission.ALLOWED, Outcome.FAILED, ExecutionReason.ADMISSION_CHANGED); return; }
                        // Never persist a false enabled state when background FGS start was denied.
                        if (!Utils.startForceDozeService(app)) {
                            complete(Permission.ALLOWED, Outcome.FAILED, ExecutionReason.FOREGROUND_START_DENIED);
                        } else if (!prefs.edit().putBoolean(Prefs.SERVICE_ENABLED, true).commit()) {
                            complete(Permission.ALLOWED, Outcome.FAILED, ExecutionReason.PREFERENCE_WRITE_FAILED);
                        } else complete(Permission.ALLOWED, Outcome.REQUESTED, ExecutionReason.SERVICE_START_REQUESTED);
                        break;
                    case DISABLE_SERVICE:
                        if (!admitted()) { complete(Permission.ALLOWED, Outcome.FAILED, ExecutionReason.ADMISSION_CHANGED); return; }
                        if (!prefs.edit().putBoolean(Prefs.SERVICE_ENABLED, false).commit()) {
                            complete(Permission.ALLOWED, Outcome.FAILED, ExecutionReason.PREFERENCE_WRITE_FAILED); return;
                        }
                        Utils.stopForceDozeService(app);
                        complete(Permission.ALLOWED, Outcome.REQUESTED, ExecutionReason.SERVICE_STOP_REQUESTED);
                        break;
                    case REAPPLY_DOZE:
                        if (!admitted() || Utils.isScreenOn(app) || !Utils.isMyServiceRunning(ForceDozeService.class, app)) {
                            complete(Permission.ALLOWED, Outcome.FAILED, ExecutionReason.NOT_ADMITTED); return;
                        }
                        app.startService(new Intent(app, ForceDozeService.class)
                                .setAction(ForceDozeService.ACTION_REAPPLY_DOZE)
                                .putExtra(ForceDozeService.EXTRA_REAPPLY_DEADLINE, deadlineElapsed));
                        complete(Permission.ALLOWED, Outcome.REQUESTED, ExecutionReason.REAPPLY_REQUESTED);
                        break;
                }
            } catch (Exception error) {
                complete(Permission.ALLOWED, Outcome.FAILED, ExecutionReason.EXECUTION_FAILED);
            }
        }

        private void editWhitelist() {
            try {
                app.getPackageManager().getApplicationInfo(pkg, 0);
            } catch (PackageManager.NameNotFoundException missing) {
                complete(Permission.DENIED, Outcome.DENIED, DenialReason.PACKAGE_NOT_INSTALLED);
                return;
            }
            AccessManager access = runtime.getAccess();
            FeatureStatus status = CapabilityResolver.status(Feature.WHITELIST_EDIT, access.getLevel(),
                    Build.VERSION.SDK_INT, runtime.grants());
            if (status instanceof FeatureStatus.Unavailable) {
                complete(Permission.ALLOWED, Outcome.FAILED, ((FeatureStatus.Unavailable) status).getReason()); return;
            }
            boolean add = action == Action.ADD_WHITELIST;
            List<String> commands = CommandCatalog.setEnabled(Feature.WHITELIST_EDIT, Build.VERSION.SDK_INT, add, pkg);
            if (commands == null) { complete(Permission.ALLOWED, Outcome.FAILED, ExecutionReason.UNVERIFIED); return; }
            for (String command : commands) {
                CommandResult result = access.controlWithDeadline(command, deadlineNanos,
                        () -> admitted() && CapabilityResolver.status(Feature.WHITELIST_EDIT, access.getLevel(),
                                Build.VERSION.SDK_INT, runtime.grants()) == FeatureStatus.Available.INSTANCE);
                if (!result.getOk()) {
                    complete(Permission.ALLOWED, Outcome.FAILED, result.getTimedOut() ? ExecutionReason.TIMED_OUT : ExecutionReason.COMMAND_FAILED); return;
                }
            }
            String read = CommandCatalog.readback(Feature.WHITELIST_EDIT, Build.VERSION.SDK_INT, pkg);
            if (read == null) { complete(Permission.ALLOWED, Outcome.FAILED, ExecutionReason.UNVERIFIED); return; }
            CommandResult result = access.controlWithDeadline(read, deadlineNanos, this::admitted);
            Boolean membership = result.getOk() ? ExternalControlPolicy.whitelistMembership(result.getStdout(), pkg) : null;
            if (membership == null || membership != add) complete(Permission.ALLOWED, Outcome.FAILED, ExecutionReason.UNVERIFIED);
            else complete(Permission.ALLOWED, Outcome.VERIFIED, ExecutionReason.WHITELIST_READBACK);
        }
    }
}
