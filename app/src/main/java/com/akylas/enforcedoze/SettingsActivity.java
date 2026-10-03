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

import java.util.List;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

import com.akylas.enforcedoze.access.AccessManager;
import com.akylas.enforcedoze.access.AccessLevel;
import com.akylas.enforcedoze.access.AccessState;
import com.akylas.enforcedoze.access.CapabilityResolver;
import com.akylas.enforcedoze.access.Feature;
import com.akylas.enforcedoze.access.FeatureStatus;
import com.akylas.enforcedoze.access.Reason;

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

        // The switch is consumed once, only after the selected transport is usable.
        static final class ModeSwitch {
            private String pending;
            private int generation;
            int select(String mode) { pending = mode; return ++generation; }
            boolean ready(String selected, AccessLevel level) {
                return pending != null && pending.equals(selected)
                        && (level == AccessLevel.ROOT || ("shizuku".equals(selected) && level == AccessLevel.SHELL));
            }
            int consume() { pending = null; return generation; }
            boolean current(int token) { return token == generation; }
            boolean waitingForRoot() { return "root".equals(pending); }
        }

        @Override
        public void onStart() {
            super.onStart();
            accessManager.addListener(accessListener);
            accessManager.refresh();
        }

        @Override
        public void onStop() {
            accessManager.removeListener(accessListener);
            super.onStop();
        }

        @Override
        public void onDestroy() {
            PreferenceManager.getDefaultSharedPreferences(requireContext())
                    .unregisterOnSharedPreferenceChangeListener(this);
            super.onDestroy();
        }

        private void onAccessChanged(AccessState state) {
            if (!isAdded()) return;
            isSuAvailable = state.getLevel() == AccessLevel.ROOT;
            isShizukuAvailable = Utils.isShizukuMode(requireContext())
                    && (isSuAvailable || state.getLevel() == AccessLevel.SHELL);
            if (!modeSwitch.waitingForRoot() || isSuAvailable) {
                toggleRootFeatures(isSuAvailable || isShizukuAvailable);
            }
            String selected = PreferenceManager.getDefaultSharedPreferences(requireContext())
                    .getString("executionMode", "root");
            if (!modeSwitch.ready(selected, state.getLevel())) return;
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
        }

        @Override
        public void onCreatePreferences(@Nullable Bundle savedInstanceState, String rootKey) {
            accessManager = AccessManager.getInstance(requireContext());

            addPreferencesFromResource(R.xml.prefs);
            removeIconSpace(getPreferenceScreen());
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
                modeSwitch.select((String) value);
                if ("shizuku".equals(value)) {
                    accessManager.refreshShizuku();
                    if (accessManager.getShizukuState().getReason() == Reason.SHIZUKU_PERMISSION_MISSING) {
                        accessManager.requestShizukuPermission();
                    }
                }
                // SharedPreferences and AccessManager publish the selected mode before we consume it.
                return true;
            });

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
                        AsyncTask.execute(() -> accessManager.grantHelpers());
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
                    Boolean hasPermission = NotificationService.Companion.getInstance() != null;
                    if (!hasPermission) {
                        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(getActivity());
                        builder.setTitle(getString(R.string.notifications_permission));
                        builder.setMessage(getString(R.string.notifications_permission_explanation));
                        builder.setPositiveButton(getString(R.string.open_button_text), (dialogInterface, i) -> {
                            Intent settingsIntent = null;
                            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                                settingsIntent = new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                        .putExtra(Settings.EXTRA_APP_PACKAGE, getActivity().getPackageName());
                            }
                            getActivity().startActivity(settingsIntent);
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

            turnOffDataInDoze.setEnabled(false);
            turnOffDataInDoze.setSummary(getString(R.string.root_required_text));
            dozeNotificationBlocklist.setEnabled(false);
            dozeNotificationBlocklist.setSummary(getString(R.string.root_required_text));
            dozeAppBlocklist.setEnabled(false);
            dozeAppBlocklist.setSummary(getString(R.string.root_required_text));
            
            Preference turnOffBluetoothInDoze = (Preference) findPreference("turnOffBluetoothInDoze");
            turnOffBluetoothInDoze.setEnabled(false);
            turnOffBluetoothInDoze.setSummary(getString(R.string.root_required_text));
            
            Preference turnOffGPSInDoze = (Preference) findPreference("turnOffGPSInDoze");
            turnOffGPSInDoze.setEnabled(false);
            turnOffGPSInDoze.setSummary(getString(R.string.root_required_text));

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
            Context context = requireContext().getApplicationContext();
            context.stopService(new Intent(context, ForceDozeService.class));
            ForceDozeService.requestSafetyCheck(context);
            AsyncTask.execute(() -> {
                String suffix = Utils.isDeviceRunningOnN() ? " all" : "";
                accessManager.control().run("dumpsys deviceidle disable" + suffix);
                accessManager.control().run("dumpsys deviceidle enable" + suffix);
                for (String permission : new String[]{"DUMP", "READ_LOGS", "READ_PHONE_STATE", "WRITE_SECURE_SETTINGS", "WRITE_SETTINGS"}) {
                    accessManager.control().run("pm revoke " + context.getPackageName() + " android.permission." + permission);
                }
                // Preserve the selected runner until all resets/revocations have completed.
                PreferenceManager.getDefaultSharedPreferences(context).edit().clear().commit();
                new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
                    if (!isAdded()) return;
                    MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(requireContext());
                    builder.setTitle(getString(R.string.reset_complete_dialog_title));
                    builder.setMessage(getString(R.string.reset_complete_dialog_text));
                    builder.setPositiveButton(getString(R.string.okay_button_text), (dialogInterface, i) -> {
                        dialogInterface.dismiss();
                        ProcessPhoenix.triggerRebirth(requireContext());
                    });
                    builder.show();
                });
            });
        }

        public void toggleRootFeatures(final boolean enabled) {
            if (getActivity() != null) {
                getActivity().runOnUiThread(() -> {
                    Preference turnOffDataInDoze = (Preference) findPreference("turnOffDataInDoze");
                    Preference dozeNotificationBlocklist = (Preference) findPreference("blacklistAppNotifications");
                    Preference dozeAppBlocklist = (Preference) findPreference("blacklistApps");
                    Preference turnOffAllSensorsInDoze = (Preference) findPreference("turnOffAllSensorsInDoze");
                    Preference turnOnBatterySaverInDoze = (Preference) findPreference("turnOnBatterySaverInDoze");
                    Preference turnOffBiometricsInDoze = (Preference) findPreference("turnOffBiometricsInDoze");
                    Preference turnOnAirplaneInDoze = (Preference) findPreference("turnOnAirplaneInDoze");
                    Preference turnOffBluetoothInDoze = (Preference) findPreference("turnOffBluetoothInDoze");
                    Preference turnOffGPSInDoze = (Preference) findPreference("turnOffGPSInDoze");
                    Preference whitelistAppsFromDozeMode = (Preference) findPreference("whitelistAppsFromDozeMode");
                    if (enabled) {
                        turnOffDataInDoze.setEnabled(true);
                        turnOffDataInDoze.setSummary(getString(R.string.disable_data_during_doze_setting_summary));
                        dozeNotificationBlocklist.setEnabled(true);
                        dozeNotificationBlocklist.setSummary(getString(R.string.notif_blocklist_setting_summary));
                        dozeAppBlocklist.setEnabled(true);
                        dozeAppBlocklist.setSummary(getString(R.string.app_blocklist_setting_summary));
                        turnOffAllSensorsInDoze.setEnabled(true);
                        turnOffAllSensorsInDoze.setSummary(getString(R.string.disable_all_sensors_setting_summary));
                        turnOnBatterySaverInDoze.setEnabled(true);
                        turnOnBatterySaverInDoze.setSummary(getString(R.string.enable_battery_saver_setting_summary));
                        turnOffBiometricsInDoze.setEnabled(true);
                        turnOffBiometricsInDoze.setSummary(getString(R.string.disable_biometrics_setting_summary));
                        turnOnAirplaneInDoze.setEnabled(true);
                        turnOnAirplaneInDoze.setSummary(getString(R.string.enable_airplane_setting_summary));
                        turnOffBluetoothInDoze.setEnabled(true);
                        turnOffBluetoothInDoze.setSummary(getString(R.string.disable_bluetooth_setting_summary));
                        turnOffGPSInDoze.setEnabled(true);
                        turnOffGPSInDoze.setSummary(getString(R.string.disable_gps_setting_summary));
                        whitelistAppsFromDozeMode.setEnabled(true);
                        whitelistAppsFromDozeMode.setSummary(getString(R.string.whitelist_apps_setting_summary));
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            Preference turnOffWiFiInDoze = (Preference) findPreference("turnOffWiFiInDoze");
                            turnOffWiFiInDoze.setEnabled(true);
                            turnOffWiFiInDoze.setSummary(getString(R.string.disable_wifi_during_doze_setting_summary));
                        }
                    } else {
                        turnOffDataInDoze.setEnabled(false);
                        turnOffDataInDoze.setSummary(getString(R.string.root_required_text));
                        dozeNotificationBlocklist.setEnabled(false);
                        dozeNotificationBlocklist.setSummary(getString(R.string.root_required_text));
                        dozeAppBlocklist.setEnabled(false);
                        dozeAppBlocklist.setSummary(getString(R.string.root_required_text));
                        turnOffAllSensorsInDoze.setEnabled(false);
                        turnOffAllSensorsInDoze.setSummary(getString(R.string.root_required_text));
                        turnOnBatterySaverInDoze.setEnabled(false);
                        turnOnBatterySaverInDoze.setSummary(getString(R.string.root_required_text));
                        turnOffBiometricsInDoze.setEnabled(false);
                        turnOffBiometricsInDoze.setSummary(getString(R.string.root_required_text));
                        turnOnAirplaneInDoze.setEnabled(false);
                        turnOnAirplaneInDoze.setSummary(getString(R.string.root_required_text));
                        turnOffBluetoothInDoze.setEnabled(false);
                        turnOffBluetoothInDoze.setSummary(getString(R.string.root_required_text));
                        turnOffGPSInDoze.setEnabled(false);
                        turnOffGPSInDoze.setSummary(getString(R.string.root_required_text));
                        whitelistAppsFromDozeMode.setEnabled(false);
                        whitelistAppsFromDozeMode.setSummary(getString(R.string.root_required_text));

                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            Preference turnOffWiFiInDoze = (Preference) findPreference("turnOffWiFiInDoze");
                            turnOffWiFiInDoze.setEnabled(false);
                            turnOffWiFiInDoze.setSummary(getString(R.string.root_required_text));
                        }

                    }
                });
            }
        }

        @Override
        public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, @Nullable String key) {
            if ("executionMode".equals(key)) {
                onAccessChanged(accessManager.getState());
                return;
            }
            if ("customDozePeriods".equals(key)) {
                updateCustomDozePeriodsSummary(findPreference("customDozePeriods"), sharedPreferences);
            }
            if (getActivity() != null) {
                reloadSettings(getActivity());
            }
        }
    }
}
