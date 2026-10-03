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

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.snackbar.Snackbar;

import androidx.core.app.ActivityCompat;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.SwitchCompat;

import android.text.SpannableString;
import android.text.method.ScrollingMovementMethod;
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

public class MainActivity extends AppCompatActivity implements CompoundButton.OnCheckedChangeListener,  SharedPreferences.OnSharedPreferenceChangeListener {

    private AccessManager accessManager;
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

        if (serviceEnabled) {
            textViewStatus.setText(R.string.service_active);
        } else {
            textViewStatus.setText(R.string.service_inactive);
        }
    }
    private void updateToggleEnabled() {
        serviceEnabled = settings.getBoolean("serviceEnabled", false);
        toggleForceDozeSwitch.setOnCheckedChangeListener(null);
        toggleForceDozeSwitch.setChecked(serviceEnabled);
        toggleForceDozeSwitch.setOnCheckedChangeListener(this);

        if (serviceEnabled) {
            textViewStatus.setText(R.string.service_active);
        } else {
            textViewStatus.setText(R.string.service_inactive);
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);


        setContentView(R.layout.activity_main);
        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);

        CustomTabs.with(getApplicationContext()).warm();
        settings = PreferenceManager.getDefaultSharedPreferences(getApplicationContext());
        isDozeEnabledByOEM = Utils.checkForAutoPowerModesFlag();
//        showDonateDevDialog = settings.getBoolean("showDonateDevDialog2", true);
        isDozeDisabled = settings.getBoolean("isDozeDisabled", false);
        accessManager = AccessManager.getInstance(this);
        AsyncTask.execute(() -> Utils.repairPreferencesPermissions(getApplicationContext()));
        ignoreLockscreenTimeout = settings.getBoolean("ignoreLockscreenTimeout", true);
        toggleForceDozeSwitch = (SwitchCompat) findViewById(R.id.switch1);
        isDumpPermGranted = Utils.isDumpPermissionGranted(getApplicationContext());
        isWriteSecureSettingsPermGranted = Utils.isSecureSettingsPermissionGranted(getApplicationContext());
        textViewStatus = (TextView) findViewById(R.id.textView2);
        updateStateFromTile = new UpdateForceDozeEnabledState();
        LocalBroadcastManager.getInstance(this).registerReceiver(updateStateFromTile, new IntentFilter("update-state-from-tile"));
        ((TextView) findViewById(R.id.textView)).setMovementMethod(new ScrollingMovementMethod());
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
        accessManager.refresh();
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
        if (settings != null) {
            settings.unregisterOnSharedPreferenceChangeListener(this);
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
        isSuAvailable = state.getLevel() == AccessLevel.ROOT;
        isShizukuAvailable = Utils.isShizukuMode(this)
                && (state.getLevel() == AccessLevel.SHELL || isSuAvailable);
        isDumpPermGranted = state.getGrants().getDump();
        isWriteSecureSettingsPermGranted = state.getGrants().getWriteSecureSettings();
        settings.edit().putBoolean("isSuAvailable", isSuAvailable).apply();
        if (isSuAvailable || isShizukuAvailable) {
            if (!helpersRequested) {
                helpersRequested = true;
                AsyncTask.execute(() -> accessManager.grantHelpers());
            }
        } else {
            helpersRequested = false;
        }
        boolean usable = isSuAvailable || isShizukuAvailable || isDumpPermGranted;
        toggleForceDozeSwitch.setEnabled(usable);
        if (usable) doAfterSuCheckSetup();
        else textViewStatus.setText(R.string.service_disabled);
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
            menu.getItem(2).setVisible(false);
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
            settings.edit().putBoolean("serviceEnabled", true).apply();
            serviceEnabled = true;
            textViewStatus.setText(R.string.service_active);
            showForceDozeActiveDialog();
        } else {
            editor = settings.edit();
            editor.putBoolean("serviceEnabled", false);
            editor.apply();
            serviceEnabled = false;
            textViewStatus.setText(R.string.service_inactive);
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
        View customAlertDialogView = LayoutInflater.from(this)
                .inflate(R.layout.non_root_workaround, null, false);

        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(this);
        builder.setTitle(getString(R.string.no_root_workaround_dialog_title));
        builder.setView(customAlertDialogView);
        builder.setMessage(getString(R.string.no_root_workaround_dialog_text));
        builder.setPositiveButton(getString(R.string.okay_button_text), null);
        customAlertDialogView.findViewById(R.id.copyBtn1).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                SpannableString command = (SpannableString) ((TextView)customAlertDialogView.findViewById(R.id.commandTxt1)).getText();
                ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                ClipData clip = ClipData.newPlainText("Copied text", command);
                clipboard.setPrimaryClip(clip);
            }
        });
        customAlertDialogView.findViewById(R.id.copyBtn2).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                SpannableString command = (SpannableString) ((TextView)customAlertDialogView.findViewById(R.id.commandTxt2)).getText();
                ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                ClipData clip = ClipData.newPlainText("Copied text", command);
                clipboard.setPrimaryClip(clip);
            }
        });
        customAlertDialogView.findViewById(R.id.shareBtn1).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                SpannableString command = (SpannableString) ((TextView)customAlertDialogView.findViewById(R.id.commandTxt1)).getText();
                Intent sendIntent = new Intent();
                sendIntent.setAction(Intent.ACTION_SEND);
                sendIntent.putExtra(Intent.EXTRA_TEXT, command);
                sendIntent.setType("text/plain");
                startActivity(sendIntent);
            }
        });
        customAlertDialogView.findViewById(R.id.shareBtn2).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                SpannableString command = (SpannableString) ((TextView)customAlertDialogView.findViewById(R.id.commandTxt2)).getText();
                Intent sendIntent = new Intent();
                sendIntent.setAction(Intent.ACTION_SEND);
                sendIntent.putExtra(Intent.EXTRA_TEXT, command);
                sendIntent.setType("text/plain");
                startActivity(sendIntent);
            }
        });
//        builder.setNeutralButton(getString(R.string.copy_command_button_text), new DialogInterface.OnClickListener() {
//            @Override
//            public void onClick(DialogInterface dialogInterface, int i) {
//                dialogInterface.dismiss();
//                ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
//                ClipData clip = ClipData.newPlainText("Copied text", command);
//                clipboard.setPrimaryClip(clip);
//
//            }
//        });
//        builder.setNegativeButton(getString(R.string.share_command_button_text), new DialogInterface.OnClickListener() {
//            @Override
//            public void onClick(DialogInterface dialogInterface, int i) {
//                dialogInterface.dismiss();
//                Intent sendIntent = new Intent();
//                sendIntent.setAction(Intent.ACTION_SEND);
//                sendIntent.putExtra(Intent.EXTRA_TEXT, command);
//                sendIntent.setType("text/plain");
//                startActivity(sendIntent);
//
//            }
//        });
        builder.show();
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
