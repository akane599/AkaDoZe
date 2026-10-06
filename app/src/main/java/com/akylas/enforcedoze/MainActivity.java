package com.akylas.enforcedoze;

import static com.akylas.enforcedoze.Utils.logToLogcat;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.service.quicksettings.TileService;

import androidx.annotation.NonNull;
import androidx.appcompat.widget.Toolbar;
import androidx.coordinatorlayout.widget.CoordinatorLayout;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.snackbar.Snackbar;

import androidx.core.app.ActivityCompat;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.SwitchCompat;

import android.text.SpannableString;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.CompoundButton;
import android.widget.TextView;

import com.afollestad.materialdialogs.MaterialDialog;


//import de.cketti.library.changelog.ChangeLog;
import com.akylas.enforcedoze.access.AccessManager;
import com.akylas.enforcedoze.access.AccessLevel;
import com.akylas.enforcedoze.access.AccessState;
import com.akylas.enforcedoze.access.Reason;
import com.akylas.enforcedoze.access.Prefs;
import com.akylas.enforcedoze.ui.AccessCard;
import com.akylas.enforcedoze.ui.AccessUi;

public class MainActivity extends AppCompatActivity implements CompoundButton.OnCheckedChangeListener,  SharedPreferences.OnSharedPreferenceChangeListener {

    /** Notification "Restore now" action: reconciles the restore ledger through the service runtime. */
    public static final String ACTION_RESTORE_NOW = "com.akylas.enforcedoze.action.RESTORE_NOW";

    private AccessManager accessManager;
    private AccessCard accessCard;
    /** Last published access, null until the first callback. */
    private AccessState lastAccess;
    /** Root, Shizuku or DUMP in the last published access; false until the first callback. */
    private boolean accessUsable;
    private boolean helpersRequested;
    private final AccessManager.Listener accessListener = this::onAccessChanged;
    private UpdateForceDozeEnabledState updateStateFromTile;
    public static String TAG = "EnforceDoze";
    SharedPreferences settings;
    SharedPreferences.Editor editor;
    boolean isDozeEnabledByOEM = true;
    boolean isSuAvailable = false;
    boolean isShizukuAvailable = false;
    boolean isDozeDisabled = false;
    boolean serviceEnabled = false;
    boolean isDumpPermGranted = false;
    boolean isWriteSecureSettingsPermGranted = false;
    boolean ignoreLockscreenTimeout = true;
//    boolean showDonateDevDialog = true;
    SwitchCompat toggleForceDozeSwitch;
    MaterialDialog progressDialog = null;
    TextView textViewStatus;
    CoordinatorLayout coordinatorLayout;

    private static void log(String message) {
        logToLogcat(TAG, message);
    }

    private void updateToggleState() {
        serviceEnabled = settings.getBoolean("serviceEnabled", false);
        toggleForceDozeSwitch.setOnCheckedChangeListener(null);
        toggleForceDozeSwitch.setChecked(serviceEnabled);
        toggleForceDozeSwitch.setOnCheckedChangeListener(this);

        renderServiceStatus();
    }
    private void updateToggleEnabled() {
        serviceEnabled = settings.getBoolean("serviceEnabled", false);
        toggleForceDozeSwitch.setOnCheckedChangeListener(null);
        toggleForceDozeSwitch.setChecked(serviceEnabled);
        toggleForceDozeSwitch.setOnCheckedChangeListener(this);

        renderServiceStatus();
    }

    /** Forcing, sensor-only (Android keeps the idle timing), passive or still checking: say which. */
    private void renderServiceStatus() {
        toggleForceDozeSwitch.setEnabled(AccessUi.mainSwitchEnabled(serviceEnabled, accessUsable));
        textViewStatus.setText(AccessUi.mainStatusText(
                AccessUi.mainStatus(serviceEnabled, accessUsable, lastAccess, AccessUi.sensorsEnabled(this))));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);


        setContentView(R.layout.activity_main);
        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        View appBar = findViewById(R.id.appbarlayout);
        View mainScrollView = findViewById(R.id.mainScrollView);
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.coordinatorLayout), (v, windowInsets) -> {
            Insets bars = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
            appBar.setPadding(bars.left, bars.top, bars.right, 0);
            mainScrollView.setPadding(bars.left, 0, bars.right, bars.bottom);
            // Not consumed: a Snackbar attached to the coordinator still needs the bottom inset.
            return windowInsets;
        });

        CustomTabs.with(getApplicationContext()).warm();
        settings = PreferenceManager.getDefaultSharedPreferences(getApplicationContext());
        isDozeEnabledByOEM = Utils.checkForAutoPowerModesFlag();
//        showDonateDevDialog = settings.getBoolean("showDonateDevDialog2", true);
        isDozeDisabled = settings.getBoolean("isDozeDisabled", false);
        accessManager = AccessManager.getInstance(this);
        accessCard = new AccessCard(this, accessManager);
        if (savedInstanceState == null) handleIntent(getIntent());
        AsyncTask.execute(() -> Utils.repairPreferencesPermissions(getApplicationContext()));
        ignoreLockscreenTimeout = settings.getBoolean("ignoreLockscreenTimeout", true);
        toggleForceDozeSwitch = (SwitchCompat) findViewById(R.id.switch1);
        isDumpPermGranted = Utils.isDumpPermissionGranted(getApplicationContext());
        isWriteSecureSettingsPermGranted = Utils.isSecureSettingsPermissionGranted(getApplicationContext());
        textViewStatus = (TextView) findViewById(R.id.textView2);
        updateStateFromTile = new UpdateForceDozeEnabledState();
        LocalBroadcastManager.getInstance(this).registerReceiver(updateStateFromTile, new IntentFilter("update-state-from-tile"));
        toggleForceDozeSwitch.setOnCheckedChangeListener(null);

        if (!Utils.isPostNotificationPermissionGranted(this)) {
            requestNotificationPermission();
        } else if (!Utils.isReadPhoneStatePermissionGranted(this)) {
            requestReadPhoneStatePermission();
        }

        updateToggleState();

        toggleForceDozeSwitch.setOnCheckedChangeListener(this);
        
        accessManager.refresh();
        if (Utils.isShizukuMode(this)
                && accessManager.getShizukuState().getReason() == Reason.SHIZUKU_PERMISSION_MISSING) {
            accessManager.requestShizukuPermission();
        }

        if (Utils.isLockscreenTimeoutValueTooHigh(getContentResolver())) {
            if (!ignoreLockscreenTimeout) {
                coordinatorLayout = (CoordinatorLayout) findViewById(R.id.coordinatorLayout);
                Snackbar.make(coordinatorLayout, R.string.lockscreen_timeout_snackbar_text, Snackbar.LENGTH_INDEFINITE)
                        .setAction(R.string.more_info_text, new View.OnClickListener() {
                            @Override
                            public void onClick(View view) {
                                showLockScreenTimeoutInfoDialog();
                            }
                        })
                        .setActionTextColor(Color.RED)
                        .show();
            }
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (settings != null) {
            settings.registerOnSharedPreferenceChangeListener(this);
        }
        accessManager.addListener(accessListener);
        accessCard.start();
        accessManager.refresh();
        // Foreground return: exact-alarm access may have changed meanwhile. Requery and re-arm the next
        // boundary through the shared seam (never after master-off, never applying the current window).
        Utils.requeryExactAlarmAccess(this);
        ForceDozeService.requestSafetyCheck(this);
        updateToggleState();
        // Show disabled notification if EnforceDoze is disabled
        if (!serviceEnabled) {
            Utils.showDisabledNotification(getApplicationContext());
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        accessManager.removeListener(accessListener);
        accessCard.stop();
        if (settings != null) {
            settings.unregisterOnSharedPreferenceChangeListener(this);
        }
    }

    @Override
    protected void onNewIntent(@NonNull Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIntent(intent);
    }

    private void handleIntent(Intent intent) {
        if (intent != null && ACTION_RESTORE_NOW.equals(intent.getAction())) {
            accessCard.restoreNow();
        }
    }

    @Override
    public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String key) {
        if ("serviceEnabled".equals(key)) {
            // update UI on main thread
            runOnUiThread(this::updateToggleState);
        } else if ("executionMode".equals(key)) {
            // update UI on main thread
            runOnUiThread(this::updateToggleState);
        }
    }

    static final int POST_NOTIF_PERMISSION_REQUEST_CODE = 112;
    static final int READ_PHONE_STATE_PERMISSION_REQUEST_CODE = 113;
    public void requestNotificationPermission(){
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                ActivityCompat.requestPermissions(this,
                        new String[]{"android.permission.POST_NOTIFICATIONS"},
                        POST_NOTIF_PERMISSION_REQUEST_CODE);
            }
        } catch (Exception e){
            e.printStackTrace();
        }
    }
    public void requestReadPhoneStatePermission(){
        try {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.READ_PHONE_STATE},
                    READ_PHONE_STATE_PERMISSION_REQUEST_CODE);
        } catch (Exception e){
            e.printStackTrace();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        if (grantResults.length == 0 || grantResults[0] != PackageManager.PERMISSION_GRANTED) {
            String preference = deniedPermissionPreference(requestCode);
            if (preference != null) settings.edit().putBoolean(preference, false).apply();
        }
        if (requestCode == POST_NOTIF_PERMISSION_REQUEST_CODE && !Utils.isReadPhoneStatePermissionGranted(this)) {
            requestReadPhoneStatePermission();
        }
        accessManager.refresh();
    }

    static String deniedPermissionPreference(int requestCode) {
        // Phone-state denial affects call detection, never notification preferences.
        return requestCode == POST_NOTIF_PERMISSION_REQUEST_CODE ? "showPersistentNotif" : null;
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        accessManager.removeListener(accessListener);
        LocalBroadcastManager.getInstance(this).unregisterReceiver(updateStateFromTile);
    }

    private void onAccessChanged(AccessState state) {
        AccessUi.AccessUpdate update = new AccessUi.AccessUpdate(state, Utils.isShizukuMode(this), helpersRequested);
        lastAccess = state;
        isSuAvailable = update.su;
        isShizukuAvailable = update.shizuku;
        isDumpPermGranted = update.dump;
        isWriteSecureSettingsPermGranted = update.writeSecureSettings;
        settings.edit().putBoolean("isSuAvailable", isSuAvailable).apply();
        helpersRequested = update.helpersRequested;
        update.requestHelpers(() -> AsyncTask.execute(() -> accessManager.grantHelpersAutomatically()));
        accessUsable = update.usable;
        update.render(this::doAfterSuCheckSetup, this::renderServiceStatus);
    }

    public void doAfterSuCheckSetup() {
        updateToggleState();
        if (serviceEnabled && !Utils.isMyServiceRunning(ForceDozeService.class, this)) {
            Utils.startForceDozeService(this);
        }
    }

    @Override
    public boolean onPrepareOptionsMenu(Menu menu) {
        isDumpPermGranted = Utils.isDumpPermissionGranted(getApplicationContext());

        if (isDozeEnabledByOEM || (Utils.isDeviceRunningOnN() && !isSuAvailable)) {
            menu.findItem(R.id.action_toggle_doze).setVisible(false);
        }

        return super.onPrepareOptionsMenu(menu);
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.main, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.action_toggle_doze) {
            showEnableDozeOnUnsupportedDeviceDialog();
        } else if (id == R.id.action_donate_dev) {
            openDonatePage();
        } else if (id == R.id.action_doze_monitor) {
            startActivity(new Intent(MainActivity.this, com.akylas.enforcedoze.ui.DozeMonitorActivity.class));
        } else if (id == R.id.action_doze_batterystats) {
            startActivity(new Intent(MainActivity.this, DozeBatteryStatsActivity.class));
        } else if (id == R.id.action_app_settings) {
            startActivity(new Intent(MainActivity.this, SettingsActivity.class));
        } else if (id == R.id.action_doze_more_info) {
            showMoreInfoDialog();
        } else if (id == R.id.action_show_doze_tunables) {
            showDozeTunablesActivity();
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    public void onCheckedChanged(CompoundButton compoundButton, boolean b) {
        if (b) {
            if (!Utils.startForceDozeService(this)) {
                updateToggleState();
                return;
            }
            settings.edit().putBoolean("serviceEnabled", true)
                    .putBoolean(Prefs.SERVICE_USER_ENABLED, true).apply();
            Utils.scheduleNextCustomDozePeriodBoundary(this);
            serviceEnabled = true;
            renderServiceStatus();
            showSwitchedOnDialog(lastAccess != null ? lastAccess : accessManager.getState());
        } else {
            editor = settings.edit();
            editor.putBoolean("serviceEnabled", false);
            editor.putBoolean(Prefs.SERVICE_USER_ENABLED, false);
            editor.apply();
            Utils.cancelCustomDozePeriodAlarm(this);
            serviceEnabled = false;
            renderServiceStatus();
            if (Utils.isMyServiceRunning(ForceDozeService.class, MainActivity.this)) {
                log("Disabling ForceDoze");
                Utils.stopForceDozeService(MainActivity.this);
            }
        }

        if (Utils.isDeviceRunningOnN()) {
            TileService.requestListeningState(this, new ComponentName(this, ForceDozeTileService.class.getName()));
        }
    }

    public void showDozeTunablesActivity() {
        if (serviceEnabled) {
            MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(this);
            builder.setTitle("Warning");
            builder.setMessage("Modified Doze tunables will be overriden when not using root, as ForceDoze overrides Doze tunables by default in order to put your device immediately into Doze mode.\n\nAre you sure you want to continue?");
            builder.setPositiveButton(getString(R.string.yes_button_text), new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialogInterface, int i) {
                    dialogInterface.dismiss();
//                    toggleForceDozeSwitch.setChecked(false);
                    startActivity(new Intent(MainActivity.this, DozeTunablesActivity.class));
                }
            });
            builder.setNegativeButton(getString(R.string.no_button_text), new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialogInterface, int i) {
                    dialogInterface.dismiss();
                }
            });
            builder.show();
        } else {
            startActivity(new Intent(MainActivity.this, DozeTunablesActivity.class));
        }
    }

    public void openDonatePage() {
        CustomTabs.with(getApplicationContext())
                .setStyle(new CustomTabs.Style(getApplicationContext())
                        .setShowTitle(true)
                        .setExitAnimation(android.R.anim.slide_in_left, android.R.anim.slide_out_right)
                        .setToolbarColor(R.color.colorPrimary))
                .openUrl("https://github.com/farfromrefug", this);
    }

    public void showEnableDozeOnUnsupportedDeviceDialog() {
        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(this);
        builder.setTitle(getString(R.string.doze_unsupported_more_info_title));
        builder.setMessage(getString(R.string.doze_unsupported_more_info));
        builder.setPositiveButton(getString(R.string.close_button_text), new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialogInterface, int i) {
                dialogInterface.dismiss();
            }
        });
        builder.setNegativeButton(getString(R.string.enable_doze_button_text), new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialogInterface, int i) {
                AsyncTask.execute(() -> {
                    if (accessManager.getLevel() != AccessLevel.ROOT) {
                        log(Reason.REQUIRES_ROOT.name());
                        return;
                    }
                    accessManager.control().run("setprop persist.sys.doze_powersave true");
                    String suffix = Utils.isDeviceRunningOnN() ? " all" : "";
                    accessManager.control().run("dumpsys deviceidle disable" + suffix);
                    accessManager.control().run("dumpsys deviceidle enable" + suffix);
                    // Legacy root-only workaround: transport success is not an effectiveness claim.
                });
                dialogInterface.dismiss();
            }
        });
        builder.show();
    }

    public void showRootWorkaroundInstructions() {
        AccessUi.showAdbInstructions(this);
    }

    public void showLockScreenTimeoutInfoDialog() {
        String lockscreenTimeout = Float.toHexString(Utils.getLockscreenTimeoutValue(getContentResolver()));
        if (Float.valueOf(lockscreenTimeout) < 1.0f) {
            lockscreenTimeout = Float.toString(Float.valueOf(lockscreenTimeout) * 60.0f) + " seconds & 0";
        }
        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(this);
        builder.setTitle(getString(R.string.lockscreen_timeout_dialog_title));
        builder.setMessage(getString(R.string.lockscreen_timeout_dialog_text_p1) + lockscreenTimeout + getString(R.string.lockscreen_timeout_dialog_text_p2) +
                getString(R.string.lockscreen_timeout_dialog_text_p3) + lockscreenTimeout + getString(R.string.lockscreen_timeout_dialog_text_p4) +
                getString(R.string.lockscreen_timeout_dialog_text_p5) + lockscreenTimeout + getString(R.string.lockscreen_timeout_dialog_text_p6));
        builder.setPositiveButton(getString(R.string.okay_button_text), null);
        builder.setNegativeButton(getString(R.string.open_security_settings_button_text), new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialogInterface, int i) {
                dialogInterface.dismiss();
                Intent securitySettingsIntent = new Intent(android.provider.Settings.ACTION_SECURITY_SETTINGS);
                startActivity(securitySettingsIntent);

            }
        });
        builder.show();
    }

    public void showMoreInfoDialog() {
        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(this);
        builder.setTitle(getString(R.string.more_info_text));
        builder.setMessage(getString(R.string.how_doze_works_dialog_text));
        builder.setPositiveButton(getString(R.string.okay_button_text), (dialogInterface, i) -> dialogInterface.dismiss());
        builder.show();
    }

    /** Passive: the service runs for readback and recovery, but nothing changes at screen-off. */
    public void showSessionsNeedAccessDialog() {
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.sessions_need_access_dialog_title)
                .setMessage(R.string.sessions_need_access_dialog_text)
                .setPositiveButton(R.string.okay_button_text, null)
                .show();
    }

    /** What switching on does now. Still checking: the status line says so and updates once access resolves. */
    private void showSwitchedOnDialog(AccessState access) {
        switch (AccessUi.serviceStatus(true, access, AccessUi.sensorsEnabled(this))) {
            case FORCING:
                showForceDozeActiveDialog();
                break;
            case SENSORS_ONLY:
                showSensorsOnlyDialog();
                break;
            case PASSIVE:
                showSessionsNeedAccessDialog();
                break;
            default:
                break;
        }
    }

    /** Switched on with DUMP and the sensor setting: motion sensors only, Android keeps the idle timing. */
    public void showSensorsOnlyDialog() {
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.sensors_only_dialog_title)
                .setMessage(R.string.sensors_only_dialog_text)
                .setPositiveButton(R.string.okay_button_text, null)
                .show();
    }

    public void showForceDozeActiveDialog() {
        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(this);
        builder.setTitle(getString(R.string.forcedoze_active_dialog_title));
        builder.setMessage(getString(R.string.forcedoze_active_dialog_text));
        builder.setPositiveButton(getString(R.string.okay_button_text), (dialogInterface, i) -> dialogInterface.dismiss());
        builder.show();
    }

//    public void showDonateDevDialog() {
//        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(this);
//        builder.setTitle(getString(R.string.donate_dialog_title));
//        builder.setMessage(getString(R.string.donate_dialog_text));
//        builder.setPositiveButton(getString(R.string.donate_dialog_button_text), new DialogInterface.OnClickListener() {
//            @Override
//            public void onClick(DialogInterface dialogInterface, int i) {
//                editor = settings.edit();
//                editor.putBoolean("showDonateDevDialog2", false);
//                editor.apply();
//                dialogInterface.dismiss();
//                openDonatePage();
//            }
//        });
//        builder.setNegativeButton(getString(R.string.close_button_text), new DialogInterface.OnClickListener() {
//            @Override
//            public void onClick(DialogInterface dialogInterface, int i) {
//                editor = settings.edit();
//                editor.putBoolean("showDonateDevDialog2", false);
//                editor.apply();
//                dialogInterface.dismiss();
//            }
//        });
//        builder.show();
//    }



    class UpdateForceDozeEnabledState extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            log("User toggled the QuickTile, now updating the state in app");
            updateToggleState();
        }
    }
}
