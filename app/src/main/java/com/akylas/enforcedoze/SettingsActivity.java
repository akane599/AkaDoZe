package com.akylas.enforcedoze;

import static com.akylas.enforcedoze.Utils.logToLogcat;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.app.TimePickerDialog;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.Toolbar;
import androidx.core.app.ActivityCompat;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.fragment.app.DialogFragment;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;
import androidx.appcompat.app.AppCompatActivity;
import androidx.preference.ListPreference;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragment;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.PreferenceGroup;
import androidx.preference.PreferenceManager;
import androidx.preference.SwitchPreferenceCompat;
import androidx.recyclerview.widget.RecyclerView;

import android.util.Log;
import android.view.MenuItem;
import android.view.View;

import com.afollestad.materialdialogs.MaterialDialog;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.jakewharton.processphoenix.ProcessPhoenix;
import com.nanotasks.Completion;
import com.nanotasks.Tasks;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import com.akylas.enforcedoze.access.AccessManager;
import com.akylas.enforcedoze.access.AccessLevel;
import com.akylas.enforcedoze.access.AccessState;
import com.akylas.enforcedoze.access.CapabilityResolver;
import com.akylas.enforcedoze.access.Feature;
import com.akylas.enforcedoze.access.FeatureStatus;
import com.akylas.enforcedoze.access.Reason;
import com.akylas.enforcedoze.access.CommandRunner;
import com.akylas.enforcedoze.access.Prefs;
import com.akylas.enforcedoze.access.ShizukuState;
import com.akylas.enforcedoze.doze.ExactAlarmAccessPolicy;
import com.akylas.enforcedoze.ui.AccessUi;
import com.akylas.enforcedoze.ui.ModeSwitchRules;
import com.akylas.enforcedoze.ui.ResetReport;
import com.akylas.enforcedoze.service.ResetCommandId;
import com.akylas.enforcedoze.service.SystemResetResult;

import android.Manifest;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;

import java.util.HashMap;
import java.util.Map;

import rikka.shizuku.Shizuku;

public class SettingsActivity extends AppCompatActivity {
    public static String TAG = "EnforceDoze";
    static MaterialDialog progressDialog1 = null;

    private static void log(String message) {
            logToLogcat(TAG, message);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setContentView(R.layout.activity_settings);
        if (savedInstanceState == null) {
            getSupportFragmentManager()
                    .beginTransaction()
                    .replace(R.id.settings, new SettingsFragment())
                    .commit();
        }
        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        getSupportActionBar().setDisplayHomeAsUpEnabled(true);

    }

    public static void reloadSettings(Context context) {
        if (Utils.isMyServiceRunning(ForceDozeService.class, context)) {
            Intent intent = new Intent("reload-settings");
            LocalBroadcastManager.getInstance(context).sendBroadcast(intent);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        reloadSettings(this);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();
        switch (id) {
            case android.R.id.home:
                onBackPressed();
                return true;
        }
        return super.onOptionsItemSelected(item);
    }

    public static class SettingsFragment extends PreferenceFragmentCompat implements SharedPreferences.OnSharedPreferenceChangeListener {

        boolean isSuAvailable = false;
        boolean isShizukuAvailable = false;
        private AccessManager accessManager;
        private final ModeSwitch modeSwitch = new ModeSwitch();
        private final AccessManager.Listener accessListener = this::onAccessChanged;
        private final Handler mainHandler = new Handler(Looper.getMainLooper());
        /** Preferences gated by CapabilityResolver. Gating only toggles enabled/summary, never the stored value. */
        static final String[] FEATURE_PREFS = {
                Prefs.KEEP_DOZE_ENFORCED, Prefs.DISABLE_MOTION_SENSORS, Prefs.TURN_OFF_WIFI, Prefs.TURN_OFF_DATA,
                Prefs.TURN_ON_AIRPLANE, Prefs.TURN_OFF_BLUETOOTH, Prefs.TURN_OFF_LOCATION, Prefs.TURN_OFF_ALL_SENSORS,
                Prefs.TURN_OFF_BIOMETRICS, Prefs.TURN_ON_BATTERY_SAVER, "whitelistCurrentApp",
                "whitelistAppsFromDozeMode", "blacklistAppNotifications", "blacklistApps",
        };
        private static final String ACCESS_STATUS = "accessStatus";
        private static final String MUSIC_WHITELIST = "whitelistMusicAppNetwork";
        private static final String EXACT_ALARM_ACCESS = "exactAlarmAccess";
        /** Last requeried exact-alarm capability; null until the first resume. */
        private ExactAlarmAccessPolicy.Access exactAlarmAccess;
        private final Map<String, CharSequence> baseSummaries = new HashMap<>();
        // A pending mode switch survives recreation (rotation while the Shizuku prompt is up).
        private static final String STATE_PENDING_MODE = "modeSwitchPending";
        private static final String STATE_MODE_BEFORE = "modeBeforeSwitch";
        private static final String STATE_AWAITING_SHIZUKU = "awaitingShizukuResult";
        private static final String STATE_AWAITING_ROOT = "awaitingRootProbe";
        /** The su probe is one bounded lane command; allow one probe already queued ahead of it. */
        private static final long ROOT_PROBE_WAIT_MS = 2 * CommandRunner.DEFAULT_TIMEOUT_MS + 1_000;
        private MaterialDialog modeProgress;
        private MaterialDialog resetProgress;
        private androidx.appcompat.app.AlertDialog resetReport;
        private final ResetReport.Tracker.Listener resetListener = this::renderReset;
        private String modeBeforeSwitch;
        private boolean awaitingShizukuResult;
        /** The Activity was covered after the Shizuku request, so no prompt is in flight once it resumes. */
        private boolean shizukuPromptSettled;
        private boolean awaitingRoot;
        private final Runnable rootProbeTimeout = () -> finishRootWait(
                accessManager.getLevel() == AccessLevel.ROOT && Prefs.MODE_ROOT.equals(selectedMode()));
        private final Shizuku.OnRequestPermissionResultListener shizukuResult = (requestCode, grantResult) ->
                mainHandler.post(() -> onShizukuPermissionResult(grantResult == PackageManager.PERMISSION_GRANTED));
        private final ActivityResultLauncher<String> summaryPermission = registerForActivityResult(
                new ActivityResultContracts.RequestPermission(), this::onSummaryPermissionResult);

        static Feature featureFor(String key) {
            switch (key) {
                case Prefs.KEEP_DOZE_ENFORCED: return Feature.FORCE_DOZE;
                case Prefs.DISABLE_MOTION_SENSORS: return Feature.MOTION_SENSORS;
                case Prefs.TURN_OFF_WIFI: return Feature.WIFI;
                case Prefs.TURN_OFF_DATA: return Feature.MOBILE_DATA;
                case Prefs.TURN_ON_AIRPLANE: return Feature.AIRPLANE;
                case Prefs.TURN_OFF_BLUETOOTH: return Feature.BLUETOOTH;
                case Prefs.TURN_OFF_LOCATION: return Feature.LOCATION;
                case Prefs.TURN_OFF_ALL_SENSORS: return Feature.SENSOR_PRIVACY_ALL;
                case Prefs.TURN_OFF_BIOMETRICS: return Feature.BIOMETRICS;
                case Prefs.TURN_ON_BATTERY_SAVER: return Feature.BATTERY_SAVER;
                case "whitelistCurrentApp": return Feature.FOCUSED_APP;
                case "whitelistAppsFromDozeMode": return Feature.WHITELIST_EDIT;
                case "blacklistAppNotifications": return Feature.NOTIFICATION_BLOCK;
                // Mirrors DozeController: suspend on 24+, pm disable before.
                case "blacklistApps": return Build.VERSION.SDK_INT >= 24 ? Feature.APP_SUSPEND : Feature.PM_DISABLE;
                default: throw new IllegalArgumentException(key);
            }
        }

        // The switch is consumed once, only after the selected transport is usable (ModeSwitchRules).
        static final class ModeSwitch {
            String pending;
            private int generation;
            int select(String mode) { pending = mode; return ++generation; }
            int consume() { pending = null; return generation; }
            boolean current(int token) { return token == generation; }
        }

        @Override
        public void onStart() {
            super.onStart();
            accessManager.addListener(accessListener);
            accessManager.refresh();
            // A reset outlives this screen: show its wait, or the result it missed while recreated or stopped.
            ResetReport.TRACKER.setListener(resetListener);
            renderReset();
            // A recreated fragment shows its restored wait again (the old dialog went with the old window).
            if (modeProgress == null && awaitingShizukuResult) {
                showModeProgress(R.string.mode_switch_waiting_shizuku, () -> onShizukuPermissionResult(false));
            } else if (modeProgress == null && awaitingRoot) {
                showModeProgress(R.string.mode_switch_waiting_root, () -> finishRootWait(false));
            }
        }

        @Override
        public void onResume() {
            super.onResume();
            // Back in front: a prompt that covered us is gone, so re-check the transport itself.
            if (awaitingShizukuResult) {
                accessManager.refreshShizuku();
                resolveShizukuWait(shizukuPromptSettled);
            }
            // Back from Alarms & reminders (granted, denied or untouched) or any other cover: ask the platform
            // again and re-arm through the shared seam, which never re-arms after master-off.
            exactAlarmAccess = Utils.requeryExactAlarmAccess(requireContext());
            renderExactAlarmStatus();
        }

        @Override
        public void onPause() {
            if (awaitingShizukuResult) shizukuPromptSettled = true;
            super.onPause();
        }

        @Override
        public void onStop() {
            accessManager.removeListener(accessListener);
            ResetReport.TRACKER.removeListener(resetListener);
            super.onStop();
        }

        @Override
        public void onSaveInstanceState(@NonNull Bundle outState) {
            super.onSaveInstanceState(outState);
            outState.putString(STATE_PENDING_MODE, modeSwitch.pending);
            outState.putString(STATE_MODE_BEFORE, modeBeforeSwitch);
            outState.putBoolean(STATE_AWAITING_SHIZUKU, awaitingShizukuResult);
            outState.putBoolean(STATE_AWAITING_ROOT, awaitingRoot);
        }

        @Override
        public void onDestroy() {
            PreferenceManager.getDefaultSharedPreferences(requireContext())
                    .unregisterOnSharedPreferenceChangeListener(this);
            Shizuku.removeRequestPermissionResultListener(shizukuResult);
            mainHandler.removeCallbacksAndMessages(null);
            dismissModeProgress();
            dismissResetProgress();
            dismissResetReport();
            super.onDestroy();
        }

        private String selectedMode() {
            return PreferenceManager.getDefaultSharedPreferences(requireContext())
                    .getString(Prefs.EXECUTION_MODE, Prefs.DEFAULT_EXECUTION_MODE);
        }

        private void onAccessChanged(AccessState state) {
            if (!isAdded()) return;
            AccessLevel shizukuLevel = accessManager.getShizukuState().getLevel();
            isSuAvailable = state.getLevel() == AccessLevel.ROOT;
            isShizukuAvailable = Utils.isShizukuMode(requireContext()) && shizukuLevel != AccessLevel.NONE;
            applyCapabilities(state);
            // Access can arrive without a prompt result (already granted, or granted from the Shizuku app),
            // and Shizuku can stop mid-wait; both are read from the transport, never a stale published level.
            if (awaitingShizukuResult) resolveShizukuWait(shizukuPromptSettled && isResumed());
            String selected = selectedMode();
            if (awaitingRoot && Prefs.MODE_ROOT.equals(selected) && state.getLevel() == AccessLevel.ROOT) {
                finishRootWait(true);
            }
            if (!ModeSwitchRules.switchReady(modeSwitch.pending, selected, state.getLevel(), shizukuLevel)) return;
            int token = modeSwitch.consume();
            Context context = requireContext().getApplicationContext();
            AsyncTask.execute(() -> {
                accessManager.grantHelpers();
                new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
                    SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
                    if (!modeSwitch.current(token) || !selected.equals(prefs.getString("executionMode", "root"))) return;
                    if (accessManager.getLevel() != AccessLevel.ROOT && accessManager.getLevel() != AccessLevel.SHELL) return;
                    if (prefs.getBoolean("serviceEnabled", false)) {
                        context.stopService(new Intent(context, ForceDozeService.class));
                        Utils.startForceDozeService(context);
                    }
                    ForceDozeService.requestSafetyCheck(context);
                });
            });
        }

        private void removeIconSpace(PreferenceGroup group) {
            for (int i = 0; i < group.getPreferenceCount(); i++) {
                Preference pref = group.getPreference(i);
                pref.setIconSpaceReserved(false);

                if (pref instanceof PreferenceGroup) {
                    removeIconSpace((PreferenceGroup) pref);
                }
            }
        }
        @Override
        public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
            super.onViewCreated(view, savedInstanceState);

            ViewCompat.setOnApplyWindowInsetsListener(view, (v, insets) -> {
                RecyclerView recyclerView =
                        v.findViewById(androidx.preference.R.id.recycler_view);

                if (recyclerView != null) {
                    int bottomInset = insets
                            .getInsets(WindowInsetsCompat.Type.systemBars())
                            .bottom;

                    recyclerView.setPadding(
                            recyclerView.getPaddingLeft(),
                            recyclerView.getPaddingTop(),
                            recyclerView.getPaddingRight(),
                            bottomInset
                    );
                    recyclerView.setClipToPadding(false);
                }
                return insets;
            });
        }
        @Override
        public void onDisplayPreferenceDialog(@NonNull androidx.preference.Preference preference) {
            if (preference instanceof ListPreference) {
                showListPreferenceDialog((ListPreference)preference);
            } else {
                super.onDisplayPreferenceDialog(preference);
            }
        }

        private void showListPreferenceDialog(ListPreference preference) {
            DialogFragment dialogFragment = new MaterialListPreference();
            Bundle bundle = new Bundle(1);
            bundle.putString("key", preference.getKey());
            dialogFragment.setArguments(bundle);
            dialogFragment.setTargetFragment(this, 0);
            dialogFragment.show(getParentFragmentManager(), "androidx.preference.PreferenceFragment.DIALOG");
        }

        @Override
        public void onCreate(Bundle savedInstanceState) {
            super.onCreate(savedInstanceState);
            if (savedInstanceState == null) return;
            String pending = savedInstanceState.getString(STATE_PENDING_MODE);
            if (pending != null) modeSwitch.select(pending);
            modeBeforeSwitch = savedInstanceState.getString(STATE_MODE_BEFORE);
            awaitingShizukuResult = savedInstanceState.getBoolean(STATE_AWAITING_SHIZUKU);
            awaitingRoot = savedInstanceState.getBoolean(STATE_AWAITING_ROOT);
            if (awaitingShizukuResult) {
                // Recreated meanwhile: the prompt was up or has been answered; onResume re-checks Shizuku.
                shizukuPromptSettled = true;
                Shizuku.addRequestPermissionResultListener(shizukuResult);
            }
            if (awaitingRoot) mainHandler.postDelayed(rootProbeTimeout, ROOT_PROBE_WAIT_MS);
        }

        @Override
        public void onCreatePreferences(@Nullable Bundle savedInstanceState, String rootKey) {
            accessManager = AccessManager.getInstance(requireContext());

            addPreferencesFromResource(R.xml.prefs);
            removeIconSpace(getPreferenceScreen());
            for (String key : FEATURE_PREFS) {
                Preference pref = findPreference(key);
                if (pref != null) baseSummaries.put(key, pref.getSummary());
            }
            Preference musicPref = findPreference(MUSIC_WHITELIST);
            if (musicPref != null) baseSummaries.put(MUSIC_WHITELIST, musicPref.getSummary());
//            PreferenceScreen preferenceScreen = (PreferenceScreen) findPreference("preferenceScreen");
//            PreferenceCategory mainSettings = (PreferenceCategory) findPreference("mainSettings");
//            PreferenceCategory dozeSettings = (PreferenceCategory) findPreference("dozeSettings");
            Preference resetForceDozePref = (Preference) findPreference("resetForceDoze");
            Preference clearDozeStats = (Preference) findPreference("resetDozeStats");
            Preference dozeDelay = (Preference) findPreference("dozeEnterDelay");
            Preference customDozePeriods = (Preference) findPreference("customDozePeriods");
            Preference showPersistentNotif = (Preference) findPreference("showPersistentNotif");
            Preference usePermanentDoze = (Preference) findPreference("usePermanentDoze");
            Preference dozeNotificationBlocklist = (Preference) findPreference("blacklistAppNotifications");
            Preference dozeAppBlocklist = (Preference) findPreference("blacklistApps");
            final Preference executionMode = (Preference) findPreference("executionMode");
            final Preference disableMotionSensors = (Preference) findPreference("disableMotionSensors");
            Preference turnOffDataInDoze = (Preference) findPreference("turnOffDataInDoze");
            Preference whitelistMusicAppNetwork = (Preference) findPreference("whitelistMusicAppNetwork");
            Preference whitelistCurrentApp = (Preference) findPreference("whitelistCurrentApp");
            final Preference autoRotateBrightnessFix = (Preference) findPreference("autoRotateAndBrightnessFix");
            SwitchPreferenceCompat autoRotateFixPref = (SwitchPreferenceCompat) findPreference("autoRotateAndBrightnessFix");

            SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(getActivity());
            sharedPreferences.registerOnSharedPreferenceChangeListener(this);
            updateCustomDozePeriodsSummary(customDozePeriods, sharedPreferences);

            resetForceDozePref.setOnPreferenceClickListener(preference -> {
                MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(getActivity());
                builder.setTitle(getString(R.string.forcedoze_reset_initial_dialog_title));
                builder.setMessage(getString(R.string.forcedoze_reset_initial_dialog_text));
                builder.setPositiveButton(getString(R.string.yes_button_text), (dialogInterface, i) -> {
                    dialogInterface.dismiss();
                    resetForceDoze();
                });
                builder.setNegativeButton(getString(R.string.no_button_text), (dialogInterface, i) -> dialogInterface.dismiss());
                builder.show();
                return true;
            });
            showPersistentNotif.setOnPreferenceChangeListener((preference, value) -> {
                if ((boolean)value) {
                    if (!Utils.isPostNotificationPermissionGranted(getActivity())) {
                        requestNotificationPermission();
                        return false;
                    }
                }
                return true;
            });


            executionMode.setOnPreferenceChangeListener((preference, value) -> {
                String previous = sharedPreferences.getString(Prefs.EXECUTION_MODE, Prefs.DEFAULT_EXECUTION_MODE);
                if (!ModeSwitchRules.isSwitch(previous, (String) value)) return true;
                modeSwitch.select((String) value);
                if (Prefs.MODE_SHIZUKU.equals(value)) {
                    accessManager.refreshShizuku();
                    Reason reason = accessManager.getShizukuState().getReason();
                    if (reason == Reason.SHIZUKU_PERMISSION_MISSING) {
                        awaitForShizukuPermission(previous);
                    } else if (reason == Reason.SHIZUKU_NOT_RUNNING) {
                        new MaterialAlertDialogBuilder(requireActivity())
                                .setTitle(R.string.execution_mode_setting_title)
                                .setMessage(R.string.mode_switch_not_running_text)
                                .setPositiveButton(R.string.okay_button_text, null)
                                .show();
                    }
                } else if (Prefs.MODE_ROOT.equals(value)) {
                    awaitRoot(previous);
                }
                // SharedPreferences and AccessManager publish the selected mode before we consume it.
                return true;
            });

            Preference accessStatus = findPreference(ACCESS_STATUS);
            if (accessStatus != null) {
                accessStatus.setOnPreferenceClickListener(preference -> {
                    AccessState state = accessManager.getState();
                    AccessUi.perform(requireActivity(), accessManager,
                            AccessUi.primaryAction(state, Utils.isShizukuMode(requireContext())));
                    return true;
                });
            }

            Preference screenOnSummary = findPreference(Prefs.SCREEN_ON_SUMMARY);
            if (screenOnSummary != null) {
                screenOnSummary.setOnPreferenceChangeListener((preference, value) -> {
                    if ((boolean) value && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                            && requireContext().checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                            != PackageManager.PERMISSION_GRANTED) {
                        // Turned on only once the permission is actually granted.
                        summaryPermission.launch(Manifest.permission.POST_NOTIFICATIONS);
                        return false;
                    }
                    return true;
                });
            }

            dozeDelay.setOnPreferenceChangeListener((preference, o) -> {
                int delay = (int) o;
                if (delay >= 5 * 60) {
                    MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(getActivity());
                    builder.setTitle(getString(R.string.doze_delay_warning_dialog_title));
                    builder.setMessage(getString(R.string.doze_delay_warning_dialog_text));
                    builder.setPositiveButton(getString(R.string.okay_button_text), (dialogInterface, i) -> dialogInterface.dismiss());
                    builder.show();
                }
                return true;
            });

            customDozePeriods.setOnPreferenceClickListener(preference -> {
                showCustomDozePeriodsDialog(sharedPreferences, customDozePeriods);
                return true;
            });

            Preference exactAlarm = findPreference(EXACT_ALARM_ACCESS);
            if (exactAlarm != null) {
                exactAlarm.setOnPreferenceClickListener(preference -> {
                    // The status changes only when onResume requeries; opening the page is not a grant.
                    AccessUi.requestExactAlarmAccess(requireActivity());
                    return true;
                });
                renderExactAlarmStatus();
            }

            autoRotateFixPref.setOnPreferenceChangeListener((preference, o) -> {
                if (!Utils.isWriteSettingsPermissionGranted(getActivity())) {
                    requestWriteSettingsPermission();
                    return false;
                } else return true;
            });

            clearDozeStats.setOnPreferenceClickListener(preference -> {
                progressDialog1 = new MaterialDialog.Builder(getActivity())
                        .title(getString(R.string.please_wait_text))
                        .cancelable(false)
                        .autoDismiss(false)
                        .content(getString(R.string.clearing_doze_stats_text))
                        .progress(true, 0)
                        .show();
                Tasks.executeInBackground(getActivity(), () -> {
                    log("Clearing Doze stats");
                    SharedPreferences sharedPreferences13 = PreferenceManager.getDefaultSharedPreferences(getContext());
                    SharedPreferences.Editor editor = sharedPreferences13.edit();
                    editor.remove("dozeUsageDataAdvanced");
                    return editor.commit();
                }, new Completion<Boolean>() {
                    @Override
                    public void onSuccess(Context context, Boolean result) {
                        if (progressDialog1 != null) {
                            progressDialog1.dismiss();
                        }
                        if (result) {
                            log("Doze stats successfully cleared");
                            if (Utils.isMyServiceRunning(ForceDozeService.class, context)) {
                                Intent intent = new Intent("reload-settings");
                                LocalBroadcastManager.getInstance(context).sendBroadcast(intent);
                            }
                            MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(context);
                            builder.setTitle(getString(R.string.cleared_text));
                            builder.setMessage(getString(R.string.doze_battery_stats_clear_msg));
                            builder.setPositiveButton(getString(R.string.close_button_text), (dialogInterface, i) -> dialogInterface.dismiss());
                            builder.show();
                        }

                    }

                    @Override
                    public void onError(Context context, Exception e) {
                        Log.e(TAG, "Error clearing Doze stats: " + e.getMessage());

                    }
                });
                return true;
            });

            turnOffDataInDoze.setOnPreferenceChangeListener((preference, o) -> {
                final boolean newValue = (boolean) o;
                if (!newValue) {
                    return true;
                } else {
                    if (isSuAvailable || isShizukuAvailable) {
                        log("Root or Shizuku permission granted");
                        log("Granting android.permission.READ_PHONE_STATE to com.akylas.enforcedoze");
                        AsyncTask.execute(() -> accessManager.grantHelper("READ_PHONE_STATE"));
                        return true;
                    } else {
                        log("SU permission denied or not available");
                        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(getActivity());
                        builder.setTitle(getString(R.string.error_text));
                        builder.setMessage(getString(R.string.su_perm_denied_msg));
                        builder.setPositiveButton(getString(R.string.close_button_text), (dialogInterface, i) -> dialogInterface.dismiss());
                        builder.show();
                        return false;
                    }
                }
            });

            whitelistMusicAppNetwork.setOnPreferenceChangeListener((preference, o) -> {
                final boolean newValue = (boolean) o;
                if (newValue) {
                    // we need to check if we have notifications permissions
                    Boolean hasPermission = AccessUi.hasListenerAccess(requireContext());
                    if (!hasPermission) {
                        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(getActivity());
                        builder.setTitle(getString(R.string.notifications_permission));
                        builder.setMessage(getString(R.string.notifications_permission_explanation));
                        builder.setPositiveButton(getString(R.string.open_button_text), (dialogInterface, i) -> {
                            // The listener-settings action exists on every supported API (22+); only the
                            // package extra is API 26+. Never launch a null intent.
                            Intent settingsIntent = new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                                settingsIntent.putExtra(Settings.EXTRA_APP_PACKAGE, requireActivity().getPackageName());
                            }
                            try {
                                requireActivity().startActivity(settingsIntent);
                            } catch (android.content.ActivityNotFoundException missing) {
                                Toast.makeText(requireContext(), R.string.notification_listener_settings_missing,
                                        Toast.LENGTH_LONG).show();
                            }
                            dialogInterface.dismiss();
                        });
                        builder.show();
                    }
                }
                return true;
            });

            whitelistCurrentApp.setOnPreferenceChangeListener((preference, value) -> {
                if (!(boolean) value) return true;
                AccessState state = accessManager.getState();
                FeatureStatus status = CapabilityResolver.status(Feature.FOCUSED_APP, state.getLevel(),
                        Build.VERSION.SDK_INT, state.getGrants());
                if (!(status instanceof FeatureStatus.Available)) {
                    log(((FeatureStatus.Unavailable) status).getReason().name());
                    return false;
                }
                AsyncTask.execute(() -> {
                    com.akylas.enforcedoze.access.CommandResult result = accessManager.reads().run("dumpsys activity activities");
                    if (!result.getOk()) log(Reason.UNVERIFIED.name());
                });
                return true;
            });

//            if (sharedPreferences.getBoolean("useNonRootSensorWorkaround", false)) {
//                autoRotateBrightnessFix.setEnabled(true);
//                disableMotionSensors.setEnabled(true);
//                sharedPreferences.edit().putBoolean("autoRotateAndBrightnessFix", false).apply();
//                sharedPreferences.edit().putBoolean("disableMotionSensors", true).apply();
//            }

            // The service no longer runs the rotation/brightness workaround (ledger-only restoration).
            // Keep the saved value; only the control is retired.
            autoRotateFixPref.setEnabled(false);
            autoRotateFixPref.setSummary(getString(R.string.rotate_brightness_fix_retired_summary));

            applyCapabilities(accessManager.getState());

            Preference sponsorPref = findPreference("sponsorProject");
            if (sponsorPref != null) {
                sponsorPref.setOnPreferenceClickListener(preference -> {
                    Utils.openUrl(getActivity(), "https://github.com/sponsors/farfromrefug");
                    return true;
                });
            }

        }

        public void requestWriteSettingsPermission() {
            MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(getActivity());
            builder.setTitle(getString(R.string.auto_rotate_brightness_fix_dialog_title));
            builder.setMessage(getString(R.string.auto_rotate_brightness_fix_dialog_text));
            builder.setPositiveButton(getString(R.string.authorize_button_text), (dialogInterface, i) -> {
                Intent intent = new Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS);
                intent.setData(Uri.parse("package:" + getActivity().getPackageName()));
                startActivity(intent);
            });
            builder.setNegativeButton(getString(R.string.deny_button_text), (dialogInterface, i) -> dialogInterface.dismiss());
            builder.show();
        }

        private void showCustomDozePeriodsDialog(SharedPreferences sharedPreferences, Preference preference) {
            ArrayList<String> periods = getSortedCustomDozePeriods(sharedPreferences);
            ArrayList<String> items = new ArrayList<>(periods);
            items.add(getString(R.string.add_custom_doze_period_button));

            MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(getActivity());
            builder.setTitle(getString(R.string.custom_doze_periods_setting_title));
            builder.setItems(items.toArray(new String[0]), (dialogInterface, which) -> {
                dialogInterface.dismiss();
                if (which == periods.size()) {
                    showStartTimePicker(sharedPreferences, preference);
                } else {
                    showRemoveCustomDozePeriodDialog(sharedPreferences, preference, periods.get(which));
                }
            });
            builder.setNegativeButton(getString(R.string.close_button_text), (dialogInterface, i) -> dialogInterface.dismiss());
            builder.show();
        }

        private void showRemoveCustomDozePeriodDialog(SharedPreferences sharedPreferences, Preference preference, String period) {
            MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(getActivity());
            builder.setTitle(period);
            builder.setMessage(getString(R.string.remove_custom_doze_period_dialog_text));
            builder.setPositiveButton(getString(R.string.remove_menu_item), (dialogInterface, i) -> {
                ArrayList<String> periods = getSortedCustomDozePeriods(sharedPreferences);
                periods.remove(period);
                saveCustomDozePeriods(sharedPreferences, preference, periods);
                dialogInterface.dismiss();
            });
            builder.setNegativeButton(getString(R.string.no_button_text), (dialogInterface, i) -> dialogInterface.dismiss());
            builder.show();
        }

        private void showStartTimePicker(SharedPreferences sharedPreferences, Preference preference) {
            TimePickerDialog dialog = new TimePickerDialog(getActivity(), (view, hourOfDay, minute) ->
                    showEndTimePicker(sharedPreferences, preference, hourOfDay, minute), 22, 0, true);
            dialog.setTitle(getString(R.string.custom_doze_period_start_title));
            dialog.show();
        }

        private void showEndTimePicker(SharedPreferences sharedPreferences, Preference preference, int startHour, int startMinute) {
            TimePickerDialog dialog = new TimePickerDialog(getActivity(), (view, hourOfDay, minute) -> {
                int start = startHour * 60 + startMinute;
                int end = hourOfDay * 60 + minute;
                if (start == end) {
                    MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(getActivity());
                    builder.setTitle(getString(R.string.error_text));
                    builder.setMessage(getString(R.string.custom_doze_period_same_time_error));
                    builder.setPositiveButton(getString(R.string.okay_button_text), (dialogInterface, i) -> dialogInterface.dismiss());
                    builder.show();
                    return;
                }

                ArrayList<String> periods = getSortedCustomDozePeriods(sharedPreferences);
                periods.add(formatTime(startHour, startMinute) + "-" + formatTime(hourOfDay, minute));
                saveCustomDozePeriods(sharedPreferences, preference, periods);
            }, 7, 0, true);
            dialog.setTitle(getString(R.string.custom_doze_period_end_title));
            dialog.show();
        }

        private ArrayList<String> getSortedCustomDozePeriods(SharedPreferences sharedPreferences) {
            Set<String> periodSet = sharedPreferences.getStringSet("customDozePeriods", new LinkedHashSet<String>());
            ArrayList<String> periods = new ArrayList<>(periodSet);
            Collections.sort(periods);
            return periods;
        }

        private void saveCustomDozePeriods(SharedPreferences sharedPreferences, Preference preference, ArrayList<String> periods) {
            sharedPreferences.edit()
                    .putStringSet("customDozePeriods", new LinkedHashSet<>(periods))
                    .apply();
            updateCustomDozePeriodsSummary(preference, sharedPreferences);
            Utils.scheduleNextCustomDozePeriodBoundary(getActivity());
            reloadSettings(getActivity());
        }

        private void updateCustomDozePeriodsSummary(Preference preference, SharedPreferences sharedPreferences) {
            if (preference == null) {
                return;
            }
            ArrayList<String> periods = getSortedCustomDozePeriods(sharedPreferences);
            if (periods.isEmpty()) {
                preference.setSummary(getString(R.string.custom_doze_periods_setting_summary_empty));
            } else {
                preference.setSummary(getString(R.string.custom_doze_periods_setting_summary, android.text.TextUtils.join(", ", periods)));
            }
        }

        /** Shown with custom periods on API 31+ only; reads the last requery and never re-arms itself. */
        private void renderExactAlarmStatus() {
            Preference exactAlarm = findPreference(EXACT_ALARM_ACCESS);
            if (exactAlarm == null || !isAdded()) return;
            AccessUi.ExactAlarmStatus status = exactAlarmAccess == null ? AccessUi.ExactAlarmStatus.HIDDEN
                    : AccessUi.exactAlarmStatus(Build.VERSION.SDK_INT, Utils.hasCustomDozePeriods(requireContext()),
                            exactAlarmAccess.getExactAllowed());
            exactAlarm.setVisible(status != AccessUi.ExactAlarmStatus.HIDDEN);
            if (status != AccessUi.ExactAlarmStatus.HIDDEN) exactAlarm.setSummary(AccessUi.exactAlarmStatusText(status));
        }

        private String formatTime(int hour, int minute) {
            return String.format(Locale.US, "%02d:%02d", hour, minute);
        }

        final int POST_NOTIF_PERMISSION_REQUEST_CODE =112;
        public void requestNotificationPermission(){
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    ActivityCompat.requestPermissions(getActivity(),
                            new String[]{"android.permission.POST_NOTIFICATIONS"},
                            POST_NOTIF_PERMISSION_REQUEST_CODE);
                }
            } catch (Exception e){

            }
        }

        @Override
        public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
            super.onRequestPermissionsResult(requestCode, permissions, grantResults);

            switch (requestCode) {
                case POST_NOTIF_PERMISSION_REQUEST_CODE:
                    Preference showPersistentNotif = (Preference) findPreference("showPersistentNotif");
                    showPersistentNotif.setEnabled(false);
                    // If request is cancelled, the result arrays are empty.
                    if (grantResults.length > 0 &&
                            grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                        showPersistentNotif.setEnabled(true);

                    }  else {
                        showPersistentNotif.setEnabled(false);
                        PreferenceManager.getDefaultSharedPreferences(getContext())
                                .edit()
                                .putBoolean("showPersistentNotif", false)
                                .apply();
                    }

            }

        }

        public void resetForceDoze() {
            if (!ResetReport.TRACKER.begin()) {
                renderReset();
                return;
            }
            Context context = requireContext().getApplicationContext();
            context.stopService(new Intent(context, ForceDozeService.class));
            renderReset();
            // Restores through the ledger first, then the reset steps; the callback runs on doze-worker.
            MyApplication.getDozeRuntime(context).resetSystemState(ResetReport.callback(
                    PreferenceManager.getDefaultSharedPreferences(context),
                    context.getSharedPreferences(Prefs.HELPER_GRANTS, Context.MODE_PRIVATE), ResetReport.TRACKER,
                    task -> new Handler(Looper.getMainLooper()).post(task)));
        }

        private void dismissResetProgress() {
            if (resetProgress != null) {
                if (resetProgress.isShowing()) resetProgress.dismiss();
                resetProgress = null;
            }
        }

        private void dismissResetReport() {
            if (resetReport != null) {
                if (resetReport.isShowing()) resetReport.dismiss();
                resetReport = null;
            }
        }

        /** Shows the reset's phase: the wait while it runs or finishes, else its report until confirmed. */
        private void renderReset() {
            if (!isAdded()) return;
            ResetReport.Tracker.Phase phase = ResetReport.TRACKER.phase();
            if (phase == ResetReport.Tracker.Phase.RUNNING || phase == ResetReport.Tracker.Phase.FINISHING) {
                dismissResetReport();
                if (resetProgress == null || !resetProgress.isShowing()) {
                    resetProgress = new MaterialDialog.Builder(requireActivity())
                            .title(R.string.please_wait_text)
                            .content(R.string.reset_running_text)
                            .progress(true, 0)
                            .cancelable(false)
                            .show();
                }
            } else if (phase == ResetReport.Tracker.Phase.REPORTED) {
                dismissResetProgress();
                if (resetReport != null && resetReport.isShowing()) return;
                Context context = requireContext();
                SystemResetResult result = ResetReport.TRACKER.result();
                boolean cleared = ResetReport.TRACKER.prefsCleared();
                resetReport = new MaterialAlertDialogBuilder(context)
                        .setTitle(ResetReport.title(result, cleared))
                        .setMessage(ResetReport.message(context, result, cleared))
                        .setCancelable(false)
                        .setPositiveButton(R.string.okay_button_text, (dialogInterface, i) -> finishReset())
                        .show();
            } else {
                dismissResetProgress();
                dismissResetReport();
            }
        }

        /** The user saw the report: only now run the revokes Android kills this app for, then restart. */
        private void finishReset() {
            List<ResetCommandId> deferred = ResetReport.TRACKER.confirm();
            if (deferred == null) {
                renderReset();
                return;
            }
            resetReport = null;
            Context app = requireContext().getApplicationContext();
            renderReset();
            MyApplication.getDozeRuntime(app).finishReset(deferred,
                    () -> new Handler(Looper.getMainLooper()).post(() -> ProcessPhoenix.triggerRebirth(app)));
        }

        /**
         * Resolver-driven availability. A disabled preference keeps its stored value: this method only
         * changes enabled state and summaries, never SharedPreferences.
         */
        private void applyCapabilities(AccessState state) {
            if (!isAdded()) return;
            Context context = requireContext();
            boolean shizukuMode = Utils.isShizukuMode(context);
            for (String key : FEATURE_PREFS) {
                Preference pref = findPreference(key);
                if (pref == null) continue;
                Feature feature = featureFor(key);
                // Session features also need Shizuku or root, whatever the resolver allows below SHELL.
                String unavailable = AccessUi.unavailableText(context, feature, state, shizukuMode);
                CharSequence base = baseSummaries.get(key);
                if (unavailable == null) {
                    pref.setEnabled(true);
                    pref.setSummary(availableSummary(feature, state, shizukuMode, base));
                } else {
                    pref.setEnabled(false);
                    pref.setSummary(unavailable);
                }
            }
            Preference music = findPreference(MUSIC_WHITELIST);
            CharSequence musicBase = baseSummaries.get(MUSIC_WHITELIST);
            if (music != null && musicBase != null) {
                // Without the listener the service treats music as not playing (MUSIC_SELECTION_UNAVAILABLE).
                music.setSummary(!AccessUi.hasListenerAccess(context)
                        ? getString(R.string.whitelist_music_needs_listener_summary, musicBase) : musicBase);
            }
            Preference access = findPreference(ACCESS_STATUS);
            if (access != null) {
                access.setSummary(getString(R.string.access_settings_summary,
                        AccessUi.modeLabel(context, state, shizukuMode), AccessUi.statusText(context, state, shizukuMode)));
            }
        }

        private CharSequence availableSummary(Feature feature, AccessState state, boolean shizukuMode, CharSequence base) {
            if (base == null) return null;
            // APP+DUMP: the only screen-off change, and Android still decides when Doze starts.
            if (AccessUi.sensorOnlyFeature(feature, state)) return getString(R.string.sensors_only_setting_summary, base);
            return shizukuMode && AccessUi.isRootOnly(feature, state) ? getString(R.string.root_tag_summary, base) : base;
        }

        /** Shows progress until Shizuku reports the permission result; a denial reverts the selection. */
        private void awaitForShizukuPermission(String previousMode) {
            modeBeforeSwitch = previousMode;
            awaitingShizukuResult = true;
            shizukuPromptSettled = false;
            Shizuku.addRequestPermissionResultListener(shizukuResult);
            showModeProgress(R.string.mode_switch_waiting_shizuku, () -> onShizukuPermissionResult(false));
            accessManager.requestShizukuPermission();
        }

        /** Applies ModeSwitchRules to the Shizuku transport state; KEEP leaves the wait running. */
        private void resolveShizukuWait(boolean promptSettled) {
            ShizukuState shizuku = accessManager.getShizukuState();
            switch (ModeSwitchRules.shizukuWait(awaitingShizukuResult, shizuku.getLevel(), shizuku.getReason(), promptSettled)) {
                case GRANTED:
                    onShizukuPermissionResult(true);
                    break;
                case REVERT_DENIED:
                    onShizukuPermissionResult(false);
                    break;
                case REVERT_NOT_RUNNING:
                    endShizukuWait();
                    revertMode(R.string.mode_switch_stopped_title, R.string.mode_switch_stopped_text);
                    break;
                default:
                    break;
            }
        }

        private void onShizukuPermissionResult(boolean granted) {
            if (!awaitingShizukuResult) return;
            endShizukuWait();
            // Granted: the access listener completes the switch.
            if (!granted) revertMode(R.string.mode_switch_denied_title, R.string.mode_switch_denied_text);
        }

        private void endShizukuWait() {
            awaitingShizukuResult = false;
            shizukuPromptSettled = false;
            Shizuku.removeRequestPermissionResultListener(shizukuResult);
            dismissModeProgress();
        }

        /** Root has no prompt result to wait for: the su probe either publishes ROOT or the wait times out. */
        private void awaitRoot(String previousMode) {
            modeBeforeSwitch = previousMode;
            awaitingRoot = true;
            showModeProgress(R.string.mode_switch_waiting_root, () -> finishRootWait(false));
            mainHandler.removeCallbacks(rootProbeTimeout);
            mainHandler.postDelayed(rootProbeTimeout, ROOT_PROBE_WAIT_MS);
        }

        private void finishRootWait(boolean granted) {
            if (!awaitingRoot) return;
            awaitingRoot = false;
            mainHandler.removeCallbacks(rootProbeTimeout);
            dismissModeProgress();
            if (!granted) revertMode(R.string.mode_switch_root_failed_title, R.string.mode_switch_root_failed_text);
        }

        /** Puts the previous mode back (setValue skips the change listener) and says why. */
        private void revertMode(int title, int text) {
            if (!isAdded()) return;
            modeSwitch.consume();
            String previous = modeBeforeSwitch == null ? Prefs.DEFAULT_EXECUTION_MODE : modeBeforeSwitch;
            ListPreference executionMode = findPreference(Prefs.EXECUTION_MODE);
            if (executionMode != null) executionMode.setValue(previous);
            String label = Prefs.MODE_SHIZUKU.equals(previous)
                    ? getString(R.string.execution_mode_shizuku) : getString(R.string.execution_mode_root);
            new MaterialAlertDialogBuilder(requireActivity())
                    .setTitle(title)
                    .setMessage(getString(text, label))
                    .setPositiveButton(R.string.okay_button_text, null)
                    .show();
        }

        private void showModeProgress(int content, Runnable onCancel) {
            dismissModeProgress();
            modeProgress = new MaterialDialog.Builder(requireActivity())
                    .title(R.string.execution_mode_setting_title)
                    .content(content)
                    .progress(true, 0)
                    .cancelable(false)
                    .negativeText(R.string.cancel_button_text)
                    .onNegative((dialog, which) -> onCancel.run())
                    .show();
        }

        private void dismissModeProgress() {
            if (modeProgress != null) {
                if (modeProgress.isShowing()) modeProgress.dismiss();
                modeProgress = null;
            }
        }

        private void onSummaryPermissionResult(boolean granted) {
            if (!isAdded()) return;
            if (granted) {
                SwitchPreferenceCompat summary = findPreference(Prefs.SCREEN_ON_SUMMARY);
                if (summary != null) summary.setChecked(true);
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                    && !shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS)) {
                // Denied for good: Android won't ask again, so only the app's notification settings can fix it.
                new MaterialAlertDialogBuilder(requireActivity())
                        .setTitle(R.string.notifications_permission)
                        .setMessage(R.string.screen_on_summary_permission_blocked)
                        .setPositiveButton(R.string.open_notification_settings_button, (dialog, which) -> {
                            try {
                                startActivity(new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                                        .putExtra(Settings.EXTRA_APP_PACKAGE, requireContext().getPackageName()));
                            } catch (android.content.ActivityNotFoundException missing) {
                                startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                        Uri.fromParts("package", requireContext().getPackageName(), null)));
                            }
                        })
                        .setNegativeButton(R.string.cancel_button_text, null)
                        .show();
            } else {
                Toast.makeText(requireContext(), R.string.screen_on_summary_permission_denied, Toast.LENGTH_LONG).show();
            }
        }

        @Override
        public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, @Nullable String key) {
            if ("executionMode".equals(key)) {
                // After every listener of this change ran: AccessManager's own listener publishes the new
                // mode first, so the state read here is never the previous mode's (e.g. a stale ROOT).
                mainHandler.post(() -> { if (isAdded()) onAccessChanged(accessManager.getState()); });
                return;
            }
            if ("customDozePeriods".equals(key)) {
                updateCustomDozePeriodsSummary(findPreference("customDozePeriods"), sharedPreferences);
                renderExactAlarmStatus();
            }
            if (getActivity() != null) {
                reloadSettings(getActivity());
            }
        }
    }
}
