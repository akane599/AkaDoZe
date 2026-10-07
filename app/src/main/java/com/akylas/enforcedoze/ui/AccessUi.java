package com.akylas.enforcedoze.ui;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.StringRes;
import androidx.core.app.NotificationManagerCompat;
import androidx.lifecycle.DefaultLifecycleObserver;
import androidx.lifecycle.LifecycleOwner;
import androidx.preference.PreferenceManager;

import com.afollestad.materialdialogs.MaterialDialog;
import com.akylas.enforcedoze.R;
import com.akylas.enforcedoze.Utils;
import com.akylas.enforcedoze.access.AccessLevel;
import com.akylas.enforcedoze.access.AccessManager;
import com.akylas.enforcedoze.access.AccessState;
import com.akylas.enforcedoze.access.CapabilityResolver;
import com.akylas.enforcedoze.access.CommandResult;
import com.akylas.enforcedoze.access.Feature;
import com.akylas.enforcedoze.access.FeatureStatus;
import com.akylas.enforcedoze.access.Reason;
import com.akylas.enforcedoze.service.SelfTestKind;
import com.akylas.enforcedoze.service.SessionAccess;
import com.akylas.enforcedoze.service.SessionMode;
import com.akylas.enforcedoze.ui.amber.AmberDialogs;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Presentation of AccessManager/CapabilityResolver state. All user-visible access text lives here. */
public final class AccessUi {
    public static final String SHIZUKU_PACKAGE = "moe.shizuku.privileged.api";

    public enum Action { GRANT_HELPERS, REQUEST_SHIZUKU, OPEN_SHIZUKU, ADB_INSTRUCTIONS }

    private AccessUi() { throw new AssertionError(); }

    public static boolean isPrivileged(AccessState state) {
        return state.getLevel() == AccessLevel.SHELL || state.getLevel() == AccessLevel.ROOT;
    }

    /**
     * Null when the feature is available. A generic no-access verdict is refined with the selected
     * transport's actual problem (Shizuku not running / not permitted), which is what the user can fix.
     */
    public static Reason unavailableReason(Feature feature, AccessState state, boolean shizukuMode) {
        return unavailableReason(feature, state, shizukuMode, Build.VERSION.SDK_INT);
    }

    static Reason unavailableReason(Feature feature, AccessState state, boolean shizukuMode, int apiLevel) {
        FeatureStatus status = CapabilityResolver.status(feature, state.getLevel(), apiLevel, state.getGrants());
        if (!(status instanceof FeatureStatus.Unavailable)) return null;
        Reason reason = ((FeatureStatus.Unavailable) status).getReason();
        Reason transport = state.getReason();
        boolean shizukuProblem = transport == Reason.SHIZUKU_NOT_RUNNING || transport == Reason.SHIZUKU_PERMISSION_MISSING;
        if (shizukuMode && shizukuProblem && (reason == Reason.NO_ACCESS || reason == Reason.NEEDS_DUMP
                || reason == Reason.NEEDS_WRITE_SECURE_SETTINGS)) {
            return transport;
        }
        return reason;
    }

    /** Full sessions (force Doze and every in-Doze change) need Shizuku or root, once discovery settles. */
    public static boolean sessionsAvailable(AccessState state) {
        return state.getResolved() && SessionAccess.canRunSessions(state.getLevel());
    }

    /**
     * The session the service runs for this access, the same rule as DozeRuntime.sessionMode(): the
     * published grants are read live on every refresh, and {@code sensorsEnabled} is the user's
     * disableMotionSensors setting.
     */
    public static SessionMode sessionMode(AccessState state, boolean sensorsEnabled) {
        return SessionAccess.mode(state.getLevel(), state.getGrants(), sensorsEnabled, state.getResolved());
    }

    /** The disableMotionSensors setting, with the service's own default. */
    public static boolean sensorsEnabled(Context context) {
        return PreferenceManager.getDefaultSharedPreferences(context).getBoolean("disableMotionSensors", true);
    }

    /**
     * The main switch's status. FORCING needs Shizuku or root. SENSORS_ONLY (DUMP plus the sensor setting)
     * only restricts motion sensors: Android still decides when the device dozes. PASSIVE reads state and
     * restores, nothing more. CHECKING: access isn't known yet, so nothing is claimed. Switched off with no
     * usable access: UNAVAILABLE once access is resolved, RESOLVING before.
     */
    public enum ServiceStatus {
        INACTIVE(R.string.service_inactive),
        CHECKING(R.string.service_checking),
        FORCING(R.string.service_active),
        SENSORS_ONLY(R.string.service_sensors_only),
        PASSIVE(R.string.service_needs_session_access),
        UNAVAILABLE(R.string.service_disabled),
        RESOLVING(R.string.access_status_checking);

        @StringRes private final int text;

        ServiceStatus(@StringRes int text) {
            this.text = text;
        }
    }

    /** The main screen's status line for a {@link #mainStatus} result. */
    @StringRes
    public static int mainStatusText(ServiceStatus status) {
        return status.text;
    }

    /**
     * What the main screen takes from a published access state. Helpers are requested at most once while
     * root or Shizuku access holds; the request flag resets when that access drops, so a later return asks
     * again. {@code isSuAvailable} is root whatever the execution mode.
     */
    public static final class AccessUpdate {
        public final boolean su;
        public final boolean shizuku;
        public final boolean dump;
        public final boolean writeSecureSettings;
        /** Root, Shizuku or DUMP. */
        public final boolean usable;
        /** The helpers-requested flag once this update is applied. */
        public final boolean helpersRequested;
        private final boolean requestHelpers;

        public AccessUpdate(AccessState state, boolean shizukuMode, boolean helpersRequested) {
            su = state.getLevel() == AccessLevel.ROOT;
            shizuku = shizukuAvailable(state.getLevel(), shizukuMode);
            dump = state.getGrants().getDump();
            writeSecureSettings = state.getGrants().getWriteSecureSettings();
            boolean helperAccess = su || shizuku;
            this.helpersRequested = helperAccess;
            requestHelpers = helperAccess && !helpersRequested;
            usable = helperAccess || dump;
        }

        /** Runs {@code grant} only on the update that first sees root or Shizuku access. */
        public void requestHelpers(Runnable grant) {
            if (requestHelpers) grant.run();
        }

        /** Usable access runs the full setup; otherwise only the status line is redrawn. */
        public void render(Runnable setup, Runnable status) {
            (usable ? setup : status).run();
        }
    }

    /** Shizuku mode with a shell or root Shizuku uid. */
    static boolean shizukuAvailable(AccessLevel level, boolean shizukuMode) {
        return shizukuMode && (level == AccessLevel.SHELL || level == AccessLevel.ROOT);
    }

    /**
     * Main screen status. {@code usable}: root, Shizuku or DUMP. A switched-on service is described by what
     * it does (PASSIVE without access), never as off.
     */
    public static ServiceStatus mainStatus(boolean enabled, boolean usable, AccessState state, boolean sensorsEnabled) {
        if (enabled || usable) return serviceStatus(enabled, state, sensorsEnabled);
        return state != null && state.getResolved() ? ServiceStatus.UNAVAILABLE : ServiceStatus.RESOLVING;
    }

    /** The main switch can always turn a running service off; turning it on needs usable access. */
    public static boolean mainSwitchEnabled(boolean enabled, boolean usable) {
        return enabled || usable;
    }

    public static ServiceStatus serviceStatus(boolean enabled, AccessState state, boolean sensorsEnabled) {
        if (!enabled) return ServiceStatus.INACTIVE;
        if (state == null || !state.getResolved()) return ServiceStatus.CHECKING;
        switch (sessionMode(state, sensorsEnabled)) {
            case FORCE: return ServiceStatus.FORCING;
            case SENSOR_ONLY: return ServiceStatus.SENSORS_ONLY;
            default: return ServiceStatus.PASSIVE;
        }
    }

    /** Features that only act inside an admitted Doze session (force Doze and every in-Doze change). */
    public static boolean isSessionFeature(Feature feature) {
        switch (feature) {
            case FORCE_DOZE:
            case MOTION_SENSORS:
            case BIOMETRICS:
            case BATTERY_SAVER:
            case WIFI:
            case MOBILE_DATA:
            case BLUETOOTH:
            case AIRPLANE:
            case LOCATION:
            case APP_SUSPEND:
            case PM_DISABLE:
            case NOTIFICATION_BLOCK:
            case SENSOR_PRIVACY_ALL:
                return true;
            default:
                return false;
        }
    }

    /**
     * True when the session rule is why a feature can't be offered. The resolver may still call it
     * available below SHELL (biometrics with WRITE_SECURE_SETTINGS: the restore path needs that), but no
     * full session runs there. At APP level motion sensors run in a sensor-only session, so the resolver's
     * own answer (available with DUMP, NEEDS_DUMP without) stands. The feature's own platform limit, when it
     * has one, stays the more precise reason.
     */
    public static boolean sessionBlocked(Feature feature, AccessState state, Reason resolverReason) {
        return isSessionFeature(feature) && !sessionsAvailable(state) && !sensorOnlyFeature(feature, state)
                && !isPlatformLimit(resolverReason);
    }

    /** Below SHELL, motion sensors are the one feature a (sensor-only) session runs. */
    public static boolean sensorOnlyFeature(Feature feature, AccessState state) {
        return feature == Feature.MOTION_SENSORS && state.getLevel() == AccessLevel.APP;
    }

    /**
     * Access is still being discovered (the Shizuku window, or root's 1+3 probe) and the feature isn't
     * offered for a reason discovery can still change: the UI says "checking", not an access problem.
     */
    static boolean checking(Feature feature, AccessState state, Reason reason) {
        return !state.getResolved() && !isVersionLimit(reason)
                && (reason != null || sessionBlocked(feature, state, reason));
    }

    /** Final whatever access resolves to. */
    private static boolean isVersionLimit(Reason reason) {
        return reason == Reason.API_TOO_OLD || reason == Reason.NOT_EFFECTIVE_ON_THIS_VERSION;
    }

    private static boolean isPlatformLimit(Reason reason) {
        return reason == Reason.REQUIRES_ROOT || reason == Reason.API_TOO_OLD
                || reason == Reason.NOT_EFFECTIVE_ON_THIS_VERSION;
    }

    /** The UI can offer the feature as working. Independent of the feature's own on/off setting. */
    static boolean offered(Feature feature, AccessState state, boolean shizukuMode, int apiLevel) {
        Reason reason = unavailableReason(feature, state, shizukuMode, apiLevel);
        return reason == null && !sessionBlocked(feature, state, reason);
    }

    /** The feature a self-test applies: the DOZE test forces idle, the SENSORS test restricts sensors. */
    public static Feature selfTestFeature(SelfTestKind kind) {
        return kind == SelfTestKind.DOZE ? Feature.FORCE_DOZE : Feature.MOTION_SENSORS;
    }

    /** Judged per kind: SENSORS runs at APP+DUMP whatever the sensor setting, DOZE needs Shizuku or root. */
    public static boolean selfTestOffered(SelfTestKind kind, AccessState state, boolean shizukuMode, int apiLevel) {
        return offered(selfTestFeature(kind), state, shizukuMode, apiLevel);
    }

    /** Why the UI can't offer the feature as working, or null when it can. */
    public static String unavailableText(Context context, Feature feature, AccessState state, boolean shizukuMode) {
        Reason reason = unavailableReason(feature, state, shizukuMode);
        if (checking(feature, state, reason)) return context.getString(R.string.access_status_checking);
        if (sessionBlocked(feature, state, reason)) return context.getString(R.string.reason_sessions_need_access);
        return reason == null ? null : reasonText(context, reason, shizukuMode);
    }

    /** Root-only by the resolver's own matrix: unavailable to a shell (Shizuku) uid for REQUIRES_ROOT. */
    public static boolean isRootOnly(Feature feature, AccessState state) {
        FeatureStatus status = CapabilityResolver.status(feature, AccessLevel.SHELL, Build.VERSION.SDK_INT, state.getGrants());
        return status instanceof FeatureStatus.Unavailable
                && ((FeatureStatus.Unavailable) status).getReason() == Reason.REQUIRES_ROOT;
    }

    public static String reasonText(Context context, Reason reason, boolean shizukuMode) {
        switch (reason) {
            case REQUIRES_ROOT:
                return context.getString(shizukuMode ? R.string.reason_requires_root_shizuku : R.string.reason_requires_root);
            case SHIZUKU_NOT_RUNNING:
                return context.getString(R.string.reason_shizuku_not_running);
            case SHIZUKU_PERMISSION_MISSING:
                return context.getString(R.string.reason_shizuku_permission);
            case NEEDS_DUMP:
                return context.getString(R.string.reason_needs_dump);
            case NEEDS_WRITE_SECURE_SETTINGS:
                return context.getString(R.string.reason_needs_wss);
            case API_TOO_OLD:
                return context.getString(R.string.reason_api_too_old, Build.VERSION.RELEASE);
            case NOT_EFFECTIVE_ON_THIS_VERSION:
                return context.getString(R.string.reason_not_effective, Build.VERSION.RELEASE);
            case UNVERIFIED:
                return context.getString(R.string.reason_unverified);
            case NO_ACCESS:
            default:
                return context.getString(shizukuMode ? R.string.reason_no_access_shizuku : R.string.reason_no_access_root);
        }
    }

    public static String modeLabel(Context context, AccessState state, boolean shizukuMode) {
        switch (state.getLevel()) {
            case ROOT:
                return context.getString(shizukuMode ? R.string.access_mode_shizuku_root : R.string.access_mode_root);
            case SHELL:
                return context.getString(R.string.access_mode_shizuku_shell);
            case APP:
                if (state.getGrants().getDump()) return context.getString(R.string.access_mode_dump);
                return context.getString(R.string.access_mode_none);
            default:
                return context.getString(R.string.access_mode_none);
        }
    }

    public static String statusText(Context context, AccessState state, boolean shizukuMode) {
        if (!state.getResolved()) return context.getString(R.string.access_status_checking);
        if (isPrivileged(state)) return context.getString(R.string.access_status_ready);
        if (shizukuMode) {
            Reason reason = state.getReason() == null ? Reason.NO_ACCESS : state.getReason();
            return reasonText(context, reason, true);
        }
        return context.getString(R.string.access_status_root_unavailable);
    }

    /** Problems the user can act on; empty when everything core is available. */
    public static List<String> problems(Context context, AccessState state, boolean shizukuMode, boolean musicWhitelist,
                                        boolean sensorsEnabled) {
        List<String> problems = new ArrayList<>();
        // Still discovering: the status line says "checking access", and no access problem is known yet.
        if (state.getResolved()) addAccessProblems(problems, context, state, shizukuMode, sensorsEnabled);
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) {
            problems.add(context.getString(R.string.access_problem_plain, context.getString(R.string.access_problem_notifications_off)));
        }
        if (musicWhitelist && !hasListenerAccess(context)) {
            problems.add(context.getString(R.string.access_problem_plain, context.getString(R.string.access_problem_music_listener)));
        }
        return problems;
    }

    private static void addAccessProblems(List<String> problems, Context context, AccessState state, boolean shizukuMode,
                                          boolean sensorsEnabled) {
        if (sessionsAvailable(state)) {
            addFeatureProblem(problems, context, state, shizukuMode, Feature.FORCE_DOZE, R.string.access_feature_force_doze);
            addFeatureProblem(problems, context, state, shizukuMode, Feature.MOTION_SENSORS, R.string.access_feature_motion_sensors);
        } else {
            // One reason for the session features; the status line above names the transport problem.
            problems.add(context.getString(R.string.access_problem_plain, context.getString(sessionProblem(state, sensorsEnabled))));
        }
        addFeatureProblem(problems, context, state, shizukuMode, Feature.DOZE_STATE_READ, R.string.access_feature_doze_state);
        if (isPrivileged(state)) {
            List<String> missing = new ArrayList<>();
            if (!state.getGrants().getDump()) missing.add("DUMP");
            if (!state.getGrants().getWriteSecureSettings()) missing.add("WRITE_SECURE_SETTINGS");
            if (!missing.isEmpty()) {
                problems.add(context.getString(R.string.access_problem_plain,
                        context.getString(R.string.access_problem_helpers_missing, android.text.TextUtils.join(", ", missing))));
            }
        }
    }

    private static void addFeatureProblem(List<String> problems, Context context, AccessState state, boolean shizukuMode,
                                          Feature feature, int label) {
        Reason reason = unavailableReason(feature, state, shizukuMode);
        if (reason != null) {
            problems.add(context.getString(R.string.access_problem_line, context.getString(label),
                    reasonText(context, reason, shizukuMode)));
        }
    }

    /** Below SHELL: what runs at screen-off, from the live DUMP grant and the sensor setting. */
    static int sessionProblem(AccessState state, boolean sensorsEnabled) {
        if (sessionMode(state, sensorsEnabled) == SessionMode.SENSOR_ONLY) return R.string.access_problem_sensors_only;
        if (state.getLevel() == AccessLevel.APP && state.getGrants().getDump()) return R.string.access_problem_sensors_off;
        return R.string.access_problem_sessions;
    }

    public static Action primaryAction(AccessState state, boolean shizukuMode) {
        if (isPrivileged(state)) return Action.GRANT_HELPERS;
        if (shizukuMode) {
            return state.getReason() == Reason.SHIZUKU_PERMISSION_MISSING ? Action.REQUEST_SHIZUKU : Action.OPEN_SHIZUKU;
        }
        return Action.ADB_INSTRUCTIONS;
    }

    public static int actionLabel(Action action) {
        switch (action) {
            case GRANT_HELPERS: return R.string.access_action_grant_helpers;
            case REQUEST_SHIZUKU: return R.string.access_action_request_shizuku;
            case OPEN_SHIZUKU: return R.string.access_action_open_shizuku;
            default: return R.string.access_action_adb;
        }
    }

    public static void perform(Activity activity, AccessManager access, Action action) {
        switch (action) {
            case GRANT_HELPERS:
                grantHelpers(activity, access);
                break;
            case REQUEST_SHIZUKU:
                access.requestShizukuPermission();
                break;
            case OPEN_SHIZUKU:
                if (!openShizuku(activity)) {
                    new MaterialAlertDialogBuilder(activity)
                            .setMessage(R.string.access_shizuku_not_installed)
                            .setPositiveButton(R.string.okay_button_text, null)
                            .show();
                }
                break;
            case ADB_INSTRUCTIONS:
                showAdbInstructions(activity);
                break;
        }
    }

    public static Intent shizukuLaunchIntent(Context context) {
        return context.getPackageManager().getLaunchIntentForPackage(SHIZUKU_PACKAGE);
    }

    public static boolean openShizuku(Context context) {
        Intent intent = shizukuLaunchIntent(context);
        if (intent == null) return false;
        context.startActivity(intent);
        return true;
    }

    /** Pure grant/completion decisions; callbacks keep platform reads lazy and Android objects out. */
    static final class GrantHelperDecision {
        // Own SAM types: java.util.function is API 24+, minSdk is 23.
        interface Grant<T> { T run(); }
        interface Use<T> { void accept(T value); }
        interface Flag { boolean get(); }

        static <T> T attempt(Grant<T> grant) {
            try {
                return grant.run();
            } catch (Exception error) {
                return null;
            }
        }

        static <T> void whenPresent(T value, Use<T> action) {
            if (value != null) action.accept(value);
        }

        static void showResult(Flag finishing, Flag destroyed, Runnable show) {
            if (!finishing.get() && !destroyed.get()) show.run();
        }
    }

    /** Runs the helper grants off-main and reports each item; permissions are checked, not assumed. */
    public static void grantHelpers(Activity activity, AccessManager access) {
        MaterialDialog progress = AmberDialogs.builder(activity)
                .title(R.string.please_wait_text)
                .content(R.string.granting_helpers_text)
                .progress(true, 0)
                .cancelable(false)
                .show();
        // The dialog goes with its Activity (no WindowLeaked); the worker holds neither of them.
        DefaultLifecycleObserver dismissOnDestroy = new DefaultLifecycleObserver() {
            @Override
            public void onDestroy(@NonNull LifecycleOwner owner) {
                dismissHelperDialog(progress);
            }
        };
        withLifecycle(activity, lifecycle -> lifecycle.getLifecycle().addObserver(dismissOnDestroy));
        WeakReference<Activity> owner = new WeakReference<>(activity);
        WeakReference<MaterialDialog> dialog = new WeakReference<>(progress);
        WeakReference<DefaultLifecycleObserver> observer = new WeakReference<>(dismissOnDestroy);
        Context app = activity.getApplicationContext();
        Handler main = new Handler(Looper.getMainLooper());
        AsyncTask.execute(() -> {
            Map<String, CommandResult> outcome = GrantHelperDecision.attempt(() -> {
                Map<String, CommandResult> results = access.grantHelpers();
                return results;
            });
            String message = outcome == null ? app.getString(R.string.grant_helpers_failed) : describe(app, outcome);
            main.post(() -> finishHelperGrant(owner.get(), dialog.get(), observer.get(), message));
        });
    }

    private static void withLifecycle(Activity activity, GrantHelperDecision.Use<LifecycleOwner> action) {
        if (activity instanceof LifecycleOwner) action.accept((LifecycleOwner) activity);
    }

    private static void dismissHelperDialog(MaterialDialog dialog) {
        GrantHelperDecision.whenPresent(dialog, shown -> MainRules.when(shown.isShowing(), shown::dismiss));
    }

    private static void finishHelperGrant(Activity current, MaterialDialog shown,
                                           DefaultLifecycleObserver registered, String message) {
        GrantHelperDecision.whenPresent(registered, observer ->
                withLifecycle(current, lifecycle -> lifecycle.getLifecycle().removeObserver(observer)));
        dismissHelperDialog(shown);
        GrantHelperDecision.whenPresent(current, activity ->
                GrantHelperDecision.showResult(activity::isFinishing, activity::isDestroyed, () ->
                        new MaterialAlertDialogBuilder(activity)
                                .setTitle(R.string.grant_helpers_result_title)
                                .setMessage(message)
                                .setPositiveButton(R.string.okay_button_text, null)
                                .show()));
    }

    private static String describe(Context context, Map<String, CommandResult> results) {
        StringBuilder text = new StringBuilder();
        for (Map.Entry<String, CommandResult> entry : results.entrySet()) {
            String item = entry.getKey();
            Boolean verified = verify(context, item);
            int format;
            if (verified != null) format = verified ? R.string.grant_result_granted : R.string.grant_result_not_granted;
            else format = entry.getValue().getOk() ? R.string.grant_result_accepted : R.string.grant_result_failed;
            if (text.length() > 0) text.append('\n');
            text.append(context.getString(format, item));
        }
        return text.toString();
    }

    /** Readback where the platform offers one; null means only the command's transport result is known. */
    private static Boolean verify(Context context, String item) {
        switch (item) {
            case "DUMP":
            case "WRITE_SECURE_SETTINGS":
            case "READ_PHONE_STATE":
            case "READ_LOGS":
                return context.checkSelfPermission("android.permission." + item) == android.content.pm.PackageManager.PERMISSION_GRANTED;
            case "SELF_WHITELIST":
                PowerManager power = context.getSystemService(PowerManager.class);
                return power != null && power.isIgnoringBatteryOptimizations(context.getPackageName());
            default:
                return null;
        }
    }

    public static void showAdbInstructions(Activity activity) {
        View view = LayoutInflater.from(activity).inflate(R.layout.non_root_workaround, null, false);
        bindCommand(activity, view, R.id.commandTxt1, R.id.copyBtn1, R.id.shareBtn1);
        bindCommand(activity, view, R.id.commandTxt2, R.id.copyBtn2, R.id.shareBtn2);
        new MaterialAlertDialogBuilder(activity)
                .setTitle(R.string.no_root_workaround_dialog_title)
                .setMessage(R.string.no_root_workaround_dialog_text)
                .setView(view)
                .setPositiveButton(R.string.okay_button_text, null)
                .show();
    }

    private static void bindCommand(Activity activity, View root, int textId, int copyId, int shareId) {
        TextView command = root.findViewById(textId);
        root.findViewById(copyId).setOnClickListener(v -> {
            ClipboardManager clipboard = activity.getSystemService(ClipboardManager.class);
            if (clipboard != null) clipboard.setPrimaryClip(ClipData.newPlainText("command", command.getText()));
        });
        root.findViewById(shareId).setOnClickListener(v -> activity.startActivity(new Intent(Intent.ACTION_SEND)
                .putExtra(Intent.EXTRA_TEXT, command.getText().toString())
                .setType("text/plain")));
    }

    public static boolean isShizukuMode(Context context) {
        return Utils.isShizukuMode(context);
    }

    /** The user's grant, not whether the listener is bound right now (it can still be rebinding). */
    public static boolean hasListenerAccess(Context context) {
        return NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.getPackageName());
    }

    /** Custom-period timing as the scheduler will arm it. HIDDEN: no periods, or no special access below API 31. */
    public enum ExactAlarmStatus { HIDDEN, EXACT, BEST_EFFORT }

    /** Alarms & reminders special access, and its Settings action, exist from API 31. */
    public static boolean canRequestExactAlarm(int apiLevel) {
        return apiLevel >= Build.VERSION_CODES.S;
    }

    /** {@code exactAllowed} is the effective capability from a fresh requery, never a launch result. */
    public static ExactAlarmStatus exactAlarmStatus(int apiLevel, boolean hasPeriods, boolean exactAllowed) {
        if (!canRequestExactAlarm(apiLevel) || !hasPeriods) return ExactAlarmStatus.HIDDEN;
        return exactAllowed ? ExactAlarmStatus.EXACT : ExactAlarmStatus.BEST_EFFORT;
    }

    public static int exactAlarmStatusText(ExactAlarmStatus status) {
        return status == ExactAlarmStatus.EXACT ? R.string.exact_alarm_status_exact : R.string.exact_alarm_status_best_effort;
    }

    /**
     * Opens this app's Alarms &amp; reminders page. Call only from an explicit tap: whatever the user does
     * there, the caller learns it by requerying on return, not from this launch.
     */
    public static boolean requestExactAlarmAccess(Activity activity) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return false;
        Uri pkg = Uri.fromParts("package", activity.getPackageName(), null);
        try {
            activity.startActivity(new Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, pkg));
        } catch (ActivityNotFoundException missing) {
            // The screen is in AOSP Settings only; some OEM builds lack it.
            activity.startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkg));
        }
        return true;
    }
}
