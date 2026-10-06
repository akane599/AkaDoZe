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

import com.akylas.enforcedoze.MyApplication.Callback;
import com.akylas.enforcedoze.MyApplication.Factory;
import com.akylas.enforcedoze.access.AccessManager;
import com.akylas.enforcedoze.access.CapabilityResolver;
import com.akylas.enforcedoze.access.CommandCatalog;
import com.akylas.enforcedoze.access.CommandResult;
import com.akylas.enforcedoze.access.ExternalCallOutcome;
import com.akylas.enforcedoze.access.ExternalCallRateLimiter;
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
import com.akylas.enforcedoze.doze.ReapplySkip;
import com.akylas.enforcedoze.service.DozeRuntime;
import com.akylas.enforcedoze.service.JournalSink;

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

    private static final ExternalCallRateLimiter JOURNAL_LIMIT = new ExternalCallRateLimiter(SystemClock::elapsedRealtime);

    private enum Permission { ALLOWED, DENIED }
    private enum ExecutionReason {
        TIMED_OUT, BUSY, ADMISSION_CHANGED, PREFERENCE_WRITE_FAILED, PREFERENCE_WRITTEN,
        FOREGROUND_START_DENIED, SERVICE_START_REQUESTED, SERVICE_STOP_REQUESTED,
        NOT_ADMITTED, REAPPLY_REQUESTED, EXECUTION_FAILED, UNVERIFIED, COMMAND_FAILED, WHITELIST_READBACK,
    }

    private final Action action;

    protected ExternalControlReceiver(Action action) { this.action = action; }

    /** Pure admission/ownership seam; denied calls never invoke the admitted-work factory. */
    public static final class Admission {
        // Own SAM types: java.util.function is API 24+, minSdk is 23.
        public interface Policy<T> { Decision apply(T input); }
        public interface Summary<T> { void accept(T journal, long suppressed); }

        public static <T> void run(Decision gates, Factory<T> decode, Policy<T> policy,
                                   Callback<DenialReason> denied, Callback<T> admitted) {
            if (gates.getReason() == DenialReason.BASIC_CONTROL_DISABLED
                    || gates.getReason() == DenialReason.PRIVILEGED_CONTROL_DISABLED) {
                denied.accept(gates.getReason());
                return;
            }
            T input;
            try {
                input = decode.get();
            } catch (RuntimeException invalidExtra) {
                denied.accept(DenialReason.INVALID_EXTRA);
                return;
            }
            Decision decision = evaluatePolicy(policy, input);
            if (!decision.getAllowed()) {
                denied.accept(decision.getReason());
                return;
            }
            admitted.accept(input);
        }

        private static <T> Decision evaluatePolicy(Policy<T> policy, T input) {
            try {
                return policy.apply(input);
            } catch (RuntimeException error) {
                android.util.Log.e("ExternalControl", "Admission policy failed", error);
                return new Decision(DenialReason.INTERNAL_ERROR, null);
            }
        }

        /** Only a parsed scalar can authorize a preference write and VERIFIED outcome. */
        public static void setting(Decision decision, Callback<DenialReason> denied,
                                   Callback<SettingValue> write) {
            if (!decision.getAllowed()) {
                denied.accept(decision.getReason());
            } else if (!(decision.getValue() instanceof SettingValue.BooleanValue)
                    && !(decision.getValue() instanceof SettingValue.IntegerValue)) {
                denied.accept(DenialReason.UNVERIFIED_SETTING_VALUE);
            } else {
                write.accept(decision.getValue());
            }
        }

        public static <T> T journal(ExternalCallRateLimiter limiter, Action action,
                                    Factory<T> factory, Summary<T> summary) {
            ExternalCallRateLimiter.Admission admission = limiter.record(action);
            if (!admission.getAdmitted()) return null;
            T journal = factory.get();
            if (admission.getSuppressed() > 0) summary.accept(journal, admission.getSuppressed());
            return journal;
        }
    }

    private static final class Input {
        final String key, value, pkg;
        Input(String key, String value, String pkg) {
            this.key = key;
            this.value = value;
            this.pkg = pkg;
        }
    }

    @Override
    public final void onReceive(Context context, Intent intent) {
        Context app = context.getApplicationContext();
        try {
            SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(app);
            String caller = Build.VERSION.SDK_INT >= 34 ? getSentFromPackage() : null;
            // Closed gates take precedence and do not require decoding untrusted extras.
            Admission.run(decide(prefs, null, null, null), () -> decode(intent),
                    input -> decide(prefs, input.key, input.value, input.pkg),
                    reason -> journal(app, caller, Permission.DENIED, ExternalCallOutcome.DENIED, reason),
                    input -> execute(app, prefs, caller, input));
        } catch (RuntimeException error) {
            android.util.Log.e("ExternalControl", "External control admission failed", error);
            journal(app, null, Permission.DENIED, ExternalCallOutcome.DENIED, DenialReason.INTERNAL_ERROR);
        }
    }

    private Input decode(Intent intent) {
        if (action == Action.CHANGE_SETTING) {
            return new Input(stringExtra(intent, "settingName"), stringExtra(intent, "settingValue"), null);
        }
        if (action == Action.ADD_WHITELIST || action == Action.REMOVE_WHITELIST) {
            return new Input(null, null, stringExtra(intent, "packageName"));
        }
        return new Input(null, null, null);
    }

    private void execute(Context app, SharedPreferences prefs, String caller, Input input) {
        DozeRuntime runtime = MyApplication.getDozeRuntime(app);
        PendingResult pending = goAsync();
        Call call = new Call(app, runtime, prefs, caller, input.key, input.value, input.pkg, pending);
        call.timer = DEADLINES.schedule(() -> call.complete(Permission.ALLOWED, ExternalCallOutcome.UNVERIFIED, ExecutionReason.TIMED_OUT),
                BUDGET_MS, TimeUnit.MILLISECONDS);
        try {
            WORK.execute(call);
        } catch (RejectedExecutionException busy) {
            call.complete(Permission.ALLOWED, ExternalCallOutcome.FAILED, ExecutionReason.BUSY);
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

    private static JournalSink admitJournal(Factory<JournalSink> factory, Action action) {
        return Admission.journal(JOURNAL_LIMIT, action, factory, (journal, suppressed) ->
                journal.emit(new DozeEvent(EventType.EXTERNAL_CALL,
                        "action=" + action.name() + " suppressed=" + suppressed)));
    }

    static void journalReapplySkipped(DozeRuntime runtime, ReapplySkip reason) {
        JournalSink journal = admitJournal(runtime::getJournal, Action.REAPPLY_DOZE);
        if (journal == null) return;
        journal.emit(new DozeEvent(EventType.SKIPPED, reason.getDetail()));
    }

    private void journal(Context app, String caller, Permission permission, ExternalCallOutcome outcome, Enum<?> reason) {
        JournalSink journal = admitJournal(() -> MyApplication.getJournal(app), action);
        if (journal == null) return;
        // Caller identity is platform supplied, never an Intent extra. Do not record target packages/values.
        journal.emit(new DozeEvent(EventType.EXTERNAL_CALL,
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

        void complete(Permission permission, ExternalCallOutcome outcome, Enum<?> reason) {
            if (!finished.compareAndSet(false, true)) return;
            ScheduledFuture<?> timeout = timer;
            if (timeout != null) timeout.cancel(false);
            WORK.remove(this);
            try { journal(app, caller, permission, outcome, reason); }
            finally { pending.finish(); }
        }

        @Override
        public void run() {
            if (!live()) { complete(Permission.ALLOWED, ExternalCallOutcome.UNVERIFIED, ExecutionReason.TIMED_OUT); return; }
            try {
                Decision current = decide(prefs, key, value, pkg);
                if (!current.getAllowed()) { complete(Permission.DENIED, ExternalCallOutcome.DENIED, current.getReason()); return; }
                dispatch(current);
            } catch (Exception error) {
                android.util.Log.e("ExternalControl", "External control execution failed", error);
                complete(Permission.ALLOWED, ExternalCallOutcome.FAILED, ExecutionReason.EXECUTION_FAILED);
            }
        }

        private void dispatch(Decision current) {
            switch (action) {
                case ADD_WHITELIST:
                case REMOVE_WHITELIST:
                    editWhitelist();
                    break;
                case CHANGE_SETTING:
                    changeSetting(current);
                    break;
                default:
                    basicControl();
                    break;
            }
        }

        private void changeSetting(Decision current) {
            if (!admitted()) { complete(Permission.ALLOWED, ExternalCallOutcome.FAILED, ExecutionReason.ADMISSION_CHANGED); return; }
            Admission.setting(current,
                    reason -> complete(Permission.DENIED, ExternalCallOutcome.DENIED, reason),
                    this::writeSetting);
        }

        private void basicControl() {
            if (action == Action.REAPPLY_DOZE) { reapplyDoze(); return; }
            if (!admitted()) { complete(Permission.ALLOWED, ExternalCallOutcome.FAILED, ExecutionReason.ADMISSION_CHANGED); return; }
            switch (action) {
                case ENABLE_SERVICE:
                    enableService();
                    break;
                case DISABLE_SERVICE:
                    disableService();
                    break;
                default:
                    throw new IllegalStateException("Not a basic control action");
            }
        }

        private void enableService() {
            // Explicit ON starts at once; persist intent only after the start is accepted.
            if (!Utils.startForceDozeService(app)) {
                complete(Permission.ALLOWED, ExternalCallOutcome.FAILED, ExecutionReason.FOREGROUND_START_DENIED);
            } else if (!prefs.edit().putBoolean(Prefs.SERVICE_ENABLED, true)
                    .putBoolean(Prefs.SERVICE_USER_ENABLED, true).commit()) {
                // Cancel a pending start too: Utils.stopForceDozeService only stops running services.
                app.stopService(new Intent(app, ForceDozeService.class));
                complete(Permission.ALLOWED, ExternalCallOutcome.FAILED, ExecutionReason.PREFERENCE_WRITE_FAILED);
            } else {
                Utils.scheduleNextCustomDozePeriodBoundary(app);
                complete(Permission.ALLOWED, ExternalCallOutcome.REQUESTED, ExecutionReason.SERVICE_START_REQUESTED);
            }
        }

        private void disableService() {
            if (!prefs.edit().putBoolean(Prefs.SERVICE_ENABLED, false)
                    .putBoolean(Prefs.SERVICE_USER_ENABLED, false).commit()) {
                complete(Permission.ALLOWED, ExternalCallOutcome.FAILED, ExecutionReason.PREFERENCE_WRITE_FAILED); return;
            }
            Utils.cancelCustomDozePeriodAlarm(app);
            Utils.stopForceDozeService(app);
            complete(Permission.ALLOWED, ExternalCallOutcome.REQUESTED, ExecutionReason.SERVICE_STOP_REQUESTED);
        }

        private void reapplyDoze() {
            if (!admitted() || Utils.isScreenOn(app) || !Utils.isMyServiceRunning(ForceDozeService.class, app)) {
                complete(Permission.ALLOWED, ExternalCallOutcome.FAILED, ExecutionReason.NOT_ADMITTED); return;
            }
            app.startService(new Intent(app, ForceDozeService.class)
                    .setAction(ForceDozeService.ACTION_REAPPLY_DOZE)
                    .putExtra(ForceDozeService.EXTRA_REAPPLY_DEADLINE, deadlineElapsed));
            complete(Permission.ALLOWED, ExternalCallOutcome.REQUESTED, ExecutionReason.REAPPLY_REQUESTED);
        }

        private void writeSetting(SettingValue setting) {
            SharedPreferences.Editor editor = prefs.edit();
            if (setting instanceof SettingValue.BooleanValue) {
                editor.putBoolean(key, ((SettingValue.BooleanValue) setting).getValue());
            } else if (setting instanceof SettingValue.IntegerValue) {
                editor.putInt(key, ((SettingValue.IntegerValue) setting).getValue());
            }
            if (!editor.commit()) { complete(Permission.ALLOWED, ExternalCallOutcome.FAILED, ExecutionReason.PREFERENCE_WRITE_FAILED); return; }
            LocalBroadcastManager.getInstance(app).sendBroadcast(new Intent("reload-settings"));
            complete(Permission.ALLOWED, ExternalCallOutcome.VERIFIED, ExecutionReason.PREFERENCE_WRITTEN);
        }

        private void editWhitelist() {
            try {
                app.getPackageManager().getApplicationInfo(pkg, 0);
            } catch (PackageManager.NameNotFoundException missing) {
                complete(Permission.DENIED, ExternalCallOutcome.DENIED, DenialReason.PACKAGE_NOT_INSTALLED);
                return;
            }
            AccessManager access = runtime.getAccess();
            FeatureStatus status = CapabilityResolver.status(Feature.WHITELIST_EDIT, access.getLevel(),
                    Build.VERSION.SDK_INT, runtime.grants());
            if (status instanceof FeatureStatus.Unavailable) {
                complete(Permission.ALLOWED, ExternalCallOutcome.FAILED, ((FeatureStatus.Unavailable) status).getReason()); return;
            }
            boolean add = action == Action.ADD_WHITELIST;
            List<String> commands = CommandCatalog.setEnabled(Feature.WHITELIST_EDIT, Build.VERSION.SDK_INT, add, pkg);
            if (commands == null) { complete(Permission.ALLOWED, ExternalCallOutcome.UNVERIFIED, ExecutionReason.UNVERIFIED); return; }
            for (String command : commands) {
                CommandResult result = access.controlWithDeadline(command, deadlineNanos,
                        () -> admitted() && CapabilityResolver.status(Feature.WHITELIST_EDIT, access.getLevel(),
                                Build.VERSION.SDK_INT, runtime.grants()) == FeatureStatus.Available.INSTANCE);
                if (!result.getOk()) {
                    complete(Permission.ALLOWED, ExternalCallOutcome.fromCommand(result), result.getTimedOut() ? ExecutionReason.TIMED_OUT : ExecutionReason.COMMAND_FAILED); return;
                }
            }
            String read = CommandCatalog.readback(Feature.WHITELIST_EDIT, Build.VERSION.SDK_INT, pkg);
            if (read == null) { complete(Permission.ALLOWED, ExternalCallOutcome.UNVERIFIED, ExecutionReason.UNVERIFIED); return; }
            CommandResult result = access.controlWithDeadline(read, deadlineNanos, this::admitted);
            Boolean membership = result.getOk() ? ExternalControlPolicy.whitelistMembership(result.getStdout(), pkg) : null;
            complete(Permission.ALLOWED, ExternalCallOutcome.fromReadback(membership == null ? null : membership == add),
                    membership == null ? ExecutionReason.UNVERIFIED : ExecutionReason.WHITELIST_READBACK);
        }
    }
}
