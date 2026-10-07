package com.akylas.enforcedoze;

import static com.akylas.enforcedoze.Utils.logToLogcat;

import android.Manifest;
import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
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
import android.graphics.drawable.GradientDrawable;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.service.quicksettings.TileService;

import androidx.annotation.NonNull;
import androidx.appcompat.widget.Toolbar;
import androidx.coordinatorlayout.widget.CoordinatorLayout;
import androidx.core.graphics.ColorUtils;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.dynamicanimation.animation.DynamicAnimation;

import com.google.android.material.color.MaterialColors;
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
import android.view.ViewGroup;
import android.widget.CompoundButton;
import android.widget.TextView;



//import de.cketti.library.changelog.ChangeLog;
import com.akylas.enforcedoze.access.AccessManager;
import com.akylas.enforcedoze.access.AccessLevel;
import com.akylas.enforcedoze.access.AccessState;
import com.akylas.enforcedoze.access.Feature;
import com.akylas.enforcedoze.access.Reason;
import com.akylas.enforcedoze.access.Prefs;
import com.akylas.enforcedoze.ui.AccessCard;
import com.akylas.enforcedoze.ui.AccessUi;
import com.akylas.enforcedoze.ui.LaunchGlowRules;
import com.akylas.enforcedoze.ui.MainRules;
import com.akylas.enforcedoze.ui.amber.AmberDialogs;
import com.akylas.enforcedoze.ui.amber.AmberGlow;
import com.akylas.enforcedoze.ui.amber.Haptics;
import com.akylas.enforcedoze.ui.amber.MotionPolicy;
import com.akylas.enforcedoze.ui.amber.Springs;

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
    boolean serviceEnabled = false;
    boolean ignoreLockscreenTimeout = true;
//    boolean showDonateDevDialog = true;
    SwitchCompat toggleForceDozeSwitch;
    TextView textViewStatus;
    CoordinatorLayout coordinatorLayout;
    /** The hero card around the status line and switch: glows while the service is on. */
    private View serviceCard;
    /** Service state the hero last showed, so only a real change springs the card. */
    private boolean renderedServiceEnabled;
    /** The launch glow plays once per process, on the first MainActivity creation. */
    private static boolean launchedInProcess;
    /** Decided in onCreate, started (once) when the enter animation completes: see {@link #onEnterAnimationComplete}. */
    private boolean launchGlowPending;
    /** Running launch glow, null when none was started. Cancelled (and its overlay removed) in onDestroy. */
    private ValueAnimator launchGlow;
    /** User toggles only: updateToggleState detaches it while it sets the switch programmatically. */
    private final CompoundButton.OnCheckedChangeListener serviceSwitchListener = this::onServiceSwitchToggled;

    private static void log(String message) {
        logToLogcat(TAG, message);
    }

    private void updateToggleState() {
        serviceEnabled = settings.getBoolean("serviceEnabled", false);
        toggleForceDozeSwitch.setOnCheckedChangeListener(null);
        toggleForceDozeSwitch.setChecked(serviceEnabled);
        toggleForceDozeSwitch.setOnCheckedChangeListener(serviceSwitchListener);

        renderServiceStatus();
    }

    /** Forcing, sensor-only (Android keeps the idle timing), passive or still checking: say which. */
    private void renderServiceStatus() {
        toggleForceDozeSwitch.setEnabled(AccessUi.mainSwitchEnabled(serviceEnabled, accessUsable));
        textViewStatus.setText(AccessUi.mainStatusText(
                AccessUi.mainStatus(serviceEnabled, accessUsable, lastAccess, AccessUi.sensorsEnabled(this))));
        MainRules.when(renderedServiceEnabled != serviceEnabled, this::pulseServiceCard);
        renderedServiceEnabled = serviceEnabled;
        AmberGlow.setActive(serviceCard, serviceEnabled);
    }

    /** A small spring back to full size; under reduced motion Springs snaps straight to 1, so nothing moves. */
    private void pulseServiceCard() {
        serviceCard.setScaleX(0.97f);
        serviceCard.setScaleY(0.97f);
        Springs.animate(serviceCard, DynamicAnimation.SCALE_X, 1f);
        Springs.animate(serviceCard, DynamicAnimation.SCALE_Y, 1f);
    }

    private void onServiceSwitchToggled(CompoundButton button, boolean checked) {
        Haptics.tick(button);
        onCheckedChanged(button, checked);
    }

    /**
     * Fresh start, first launch in this process and motion on: see {@link LaunchGlowRules#shouldPlay}. Only decides;
     * the animator waits for {@link #onEnterAnimationComplete}, since started here it ran out under the splash.
     */
    private void startLaunchGlow(Bundle savedInstanceState) {
        launchGlowPending = LaunchGlowRules.shouldPlay(savedInstanceState == null, !launchedInProcess,
                MotionPolicy.reducedMotion(this));
        launchedInProcess = true;
    }

    /**
     * The window's entering animation is done, so the content is actually on screen (the platform documents this as
     * the point an Activity may safely start drawing; on a cold start it follows the starting window/splash hand-off,
     * which onCreate and the first drawn frame both precede). Starts the pending glow once; later calls, e.g. on
     * returning from another screen, find nothing pending or an animator already started.
     */
    @Override
    public void onEnterAnimationComplete() {
        super.onEnterAnimationComplete();
        MainRules.when(LaunchGlowRules.startNow(launchGlowPending, launchGlow != null), this::playLaunchGlow);
        launchGlowPending = false;
    }

    /**
     * A full-bleed overlay on the decor view (outside the layout): a radial ?attr/colorPrimary gradient centred on
     * the bottom edge that rises and fades over 500 ms, then removes itself. It never takes touches or focus.
     */
    private void playLaunchGlow() {
        ViewGroup decor = (ViewGroup) getWindow().getDecorView();
        int height = getResources().getDisplayMetrics().heightPixels;
        int primary = MaterialColors.getColor(decor, androidx.appcompat.R.attr.colorPrimary);
        GradientDrawable gradient = new GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP,
                new int[] {primary, ColorUtils.setAlphaComponent(primary, 0)});
        gradient.setGradientType(GradientDrawable.RADIAL_GRADIENT);
        gradient.setGradientCenter(0.5f, 1f);
        gradient.setGradientRadius(LaunchGlowRules.radius(height));
        View glow = new View(this);
        glow.setBackground(gradient);
        glow.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        glow.setAlpha(0f);
        decor.addView(glow, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        launchGlow = ValueAnimator.ofFloat(0f, 1f);
        launchGlow.setDuration(LaunchGlowRules.DURATION_MS);
        launchGlow.addUpdateListener(animation -> {
            float fraction = animation.getAnimatedFraction();
            glow.setAlpha(LaunchGlowRules.alpha(fraction));
            glow.setTranslationY(LaunchGlowRules.offset(fraction) * height);
        });
        launchGlow.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                decor.removeView(glow);
            }
        });
        launchGlow.start();
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);


        setContentView(R.layout.activity_main);
        startLaunchGlow(savedInstanceState);
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
        accessManager = AccessManager.getInstance(this);
        accessCard = new AccessCard(this, accessManager);
        MainRules.when(savedInstanceState == null, () -> handleIntent(getIntent()));
        AsyncTask.execute(() -> Utils.repairPreferencesPermissions(getApplicationContext()));
        ignoreLockscreenTimeout = settings.getBoolean("ignoreLockscreenTimeout", true);
        toggleForceDozeSwitch = (SwitchCompat) findViewById(R.id.switch1);
        textViewStatus = (TextView) findViewById(R.id.textView2);
        serviceCard = findViewById(R.id.serviceCard);
        renderedServiceEnabled = settings.getBoolean("serviceEnabled", false);
        updateStateFromTile = new UpdateForceDozeEnabledState();
        LocalBroadcastManager.getInstance(this).registerReceiver(updateStateFromTile, new IntentFilter("update-state-from-tile"));
        toggleForceDozeSwitch.setOnCheckedChangeListener(null);

        MainRules.requestFirstMissingPermission(Utils.isPostNotificationPermissionGranted(this),
                Utils.isReadPhoneStatePermissionGranted(this),
                this::requestNotificationPermission, this::requestReadPhoneStatePermission);

        updateToggleState();

        toggleForceDozeSwitch.setOnCheckedChangeListener(serviceSwitchListener);

        accessManager.refresh();
        MainRules.when(MainRules.asksShizukuPermission(Utils.isShizukuMode(this),
                accessManager.getShizukuState().getReason()), accessManager::requestShizukuPermission);

        MainRules.when(MainRules.showsLockscreenTimeoutNotice(
                Utils.isLockscreenTimeoutValueTooHigh(getContentResolver()), ignoreLockscreenTimeout),
                this::showLockscreenTimeoutSnackbar);
    }

    private void showLockscreenTimeoutSnackbar() {
        coordinatorLayout = (CoordinatorLayout) findViewById(R.id.coordinatorLayout);
        Snackbar.make(coordinatorLayout, R.string.lockscreen_timeout_snackbar_text, Snackbar.LENGTH_INDEFINITE)
                .setAction(R.string.more_info_text, new View.OnClickListener() {
                    @Override
                    public void onClick(View view) {
                        showLockScreenTimeoutInfoDialog();
                    }
                })
                .setActionTextColor(MaterialColors.getColor(coordinatorLayout, androidx.appcompat.R.attr.colorError))
                .show();
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
        // Cancelling ends the animator, whose end listener removes the overlay: nothing keeps this Activity.
        MainRules.when(launchGlow != null, () -> launchGlow.cancel());
        accessManager.removeListener(accessListener);
        LocalBroadcastManager.getInstance(this).unregisterReceiver(updateStateFromTile);
    }

    private void onAccessChanged(AccessState state) {
        AccessUi.AccessUpdate update = new AccessUi.AccessUpdate(state, Utils.isShizukuMode(this), helpersRequested);
        lastAccess = state;
        isSuAvailable = update.su;
        settings.edit().putBoolean("isSuAvailable", isSuAvailable).apply();
        helpersRequested = update.helpersRequested;
        update.requestHelpers(() -> AsyncTask.execute(() -> accessManager.grantHelpersAutomatically()));
        accessUsable = update.usable;
        update.render(this::doAfterSuCheckSetup, this::renderServiceStatus);
        invalidateOptionsMenu();
    }

    public void doAfterSuCheckSetup() {
        updateToggleState();
        if (serviceEnabled && !Utils.isMyServiceRunning(ForceDozeService.class, this)) {
            Utils.startForceDozeService(this);
        }
    }

    @Override
    public boolean onPrepareOptionsMenu(Menu menu) {
        MainRules.when(MainRules.hidesUnsupportedDozeItem(isDozeEnabledByOEM, Utils.isDeviceRunningOnN(), isSuAvailable),
                () -> menu.findItem(R.id.action_toggle_doze).setVisible(false));
        // Greyed out, not hidden, while the current access can't write tunables (refreshed on access changes).
        menu.findItem(R.id.action_show_doze_tunables).setEnabled(AccessUi.unavailableReason(Feature.TUNABLES,
                accessManager.getState(), Utils.isShizukuMode(this)) == null);
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

    /**
     * No session writes tunables (only DozeTunablesActivity does). A forcing session steps Doze straight to
     * IDLE, though, so the screen first says which timeouts that skips; otherwise it opens directly.
     */
    public void showDozeTunablesActivity() {
        boolean forcing = AccessUi.serviceStatus(serviceEnabled, accessManager.getState(),
                AccessUi.sensorsEnabled(this)) == AccessUi.ServiceStatus.FORCING;
        MainRules.when(forcing, this::showForcedDozeTunablesNote);
        MainRules.when(!forcing, this::openDozeTunables);
    }

    private void showForcedDozeTunablesNote() {
        AmberDialogs.builder(this)
                .content(R.string.amber_SQ46_tunables_forced_doze)
                .positiveText(R.string.okay_button_text)
                .negativeText(R.string.cancel_button_text)
                .onPositive((dialog, which) -> openDozeTunables())
                .show();
    }

    private void openDozeTunables() {
        startActivity(new Intent(MainActivity.this, DozeTunablesActivity.class));
    }

    public void openDonatePage() {
        CustomTabs.with(getApplicationContext())
                .setStyle(new CustomTabs.Style(getApplicationContext())
                        .setShowTitle(true)
                        .setExitAnimation(android.R.anim.slide_in_left, android.R.anim.slide_out_right)
                        .setToolbarColor(R.color.colorPrimaryDark))
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
