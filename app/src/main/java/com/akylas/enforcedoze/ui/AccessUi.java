package com.akylas.enforcedoze.ui;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.app.NotificationManagerCompat;
import androidx.lifecycle.DefaultLifecycleObserver;
import androidx.lifecycle.LifecycleOwner;

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
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Presentation of AccessManager/CapabilityResolver state. All user-visible access text lives here. */
public final class AccessUi {
    public static final String SHIZUKU_PACKAGE = "moe.shizuku.privileged.api";

    public enum Action { GRANT_HELPERS, REQUEST_SHIZUKU, OPEN_SHIZUKU, ADB_INSTRUCTIONS }

    private AccessUi() {}

    public static boolean isPrivileged(AccessState state) {
        return state.getLevel() == AccessLevel.SHELL || state.getLevel() == AccessLevel.ROOT;
    }

    /**
     * Null when the feature is available. A generic no-access verdict is refined with the selected
     * transport's actual problem (Shizuku not running / not permitted), which is what the user can fix.
     */
    public static Reason unavailableReason(Feature feature, AccessState state, boolean shizukuMode) {
        FeatureStatus status = CapabilityResolver.status(feature, state.getLevel(), Build.VERSION.SDK_INT, state.getGrants());
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
            default:
                return context.getString(R.string.access_mode_none);
        }
    }

    public static String statusText(Context context, AccessState state, boolean shizukuMode) {
        if (isPrivileged(state)) return context.getString(R.string.access_status_ready);
        if (shizukuMode) {
            Reason reason = state.getReason() == null ? Reason.NO_ACCESS : state.getReason();
            return reasonText(context, reason, true);
        }
        return context.getString(R.string.access_status_root_unavailable);
    }

    /** Problems the user can act on; empty when everything core is available. */
    public static List<String> problems(Context context, AccessState state, boolean shizukuMode, boolean musicWhitelist) {
        List<String> problems = new ArrayList<>();
        addFeatureProblem(problems, context, state, shizukuMode, Feature.FORCE_DOZE, R.string.access_feature_force_doze);
        addFeatureProblem(problems, context, state, shizukuMode, Feature.MOTION_SENSORS, R.string.access_feature_motion_sensors);
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
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) {
            problems.add(context.getString(R.string.access_problem_plain, context.getString(R.string.access_problem_notifications_off)));
        }
        if (musicWhitelist && !hasListenerAccess(context)) {
            problems.add(context.getString(R.string.access_problem_plain, context.getString(R.string.access_problem_music_listener)));
        }
        return problems;
    }

    private static void addFeatureProblem(List<String> problems, Context context, AccessState state, boolean shizukuMode,
                                          Feature feature, int label) {
        Reason reason = unavailableReason(feature, state, shizukuMode);
        if (reason != null) {
            problems.add(context.getString(R.string.access_problem_line, context.getString(label),
                    reasonText(context, reason, shizukuMode)));
        }
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

    /** Runs the helper grants off-main and reports each item; permissions are checked, not assumed. */
    public static void grantHelpers(Activity activity, AccessManager access) {
        MaterialDialog progress = new MaterialDialog.Builder(activity)
                .title(R.string.please_wait_text)
                .content(R.string.granting_helpers_text)
                .progress(true, 0)
                .cancelable(false)
                .show();
        // The dialog goes with its Activity (no WindowLeaked); the worker holds neither of them.
        DefaultLifecycleObserver dismissOnDestroy = new DefaultLifecycleObserver() {
            @Override
            public void onDestroy(@NonNull LifecycleOwner owner) {
                if (progress.isShowing()) progress.dismiss();
            }
        };
        if (activity instanceof LifecycleOwner) ((LifecycleOwner) activity).getLifecycle().addObserver(dismissOnDestroy);
        WeakReference<Activity> owner = new WeakReference<>(activity);
        WeakReference<MaterialDialog> dialog = new WeakReference<>(progress);
        WeakReference<DefaultLifecycleObserver> observer = new WeakReference<>(dismissOnDestroy);
        Context app = activity.getApplicationContext();
        Handler main = new Handler(Looper.getMainLooper());
        AsyncTask.execute(() -> {
            Map<String, CommandResult> results;
            try {
                results = access.grantHelpers();
            } catch (Exception error) {
                results = null;
            }
            String message = results == null ? app.getString(R.string.grant_helpers_failed) : describe(app, results);
            main.post(() -> {
                Activity current = owner.get();
                MaterialDialog shown = dialog.get();
                DefaultLifecycleObserver registered = observer.get();
                if (current instanceof LifecycleOwner && registered != null) {
                    ((LifecycleOwner) current).getLifecycle().removeObserver(registered);
                }
                if (shown != null && shown.isShowing()) shown.dismiss();
                if (current == null || current.isFinishing() || current.isDestroyed()) return;
                new MaterialAlertDialogBuilder(current)
                        .setTitle(R.string.grant_helpers_result_title)
                        .setMessage(message)
                        .setPositiveButton(R.string.okay_button_text, null)
                        .show();
            });
        });
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
}
