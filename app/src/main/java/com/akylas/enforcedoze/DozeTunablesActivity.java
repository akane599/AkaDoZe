package com.akylas.enforcedoze;

import static com.akylas.enforcedoze.Utils.applicationContext;
import static com.akylas.enforcedoze.Utils.logToLogcat;

import android.annotation.SuppressLint;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.preference.Preference;
import androidx.preference.PreferenceCategory;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.PreferenceGroup;
import androidx.preference.PreferenceManager;
import androidx.preference.PreferenceScreen;
import androidx.recyclerview.widget.RecyclerView;

import android.text.TextUtils;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.Toast;

import com.afollestad.materialdialogs.MaterialDialog;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.nanotasks.BackgroundWork;
import com.nanotasks.Completion;
import com.nanotasks.Tasks;

import java.util.List;

import eu.chainfire.libsuperuser.Shell;

public class DozeTunablesActivity extends AppCompatActivity {

    public static String TAG = "EnforceDoze";
    public static boolean suAvailable = false;
    static boolean isShizukuAvailable = false;
    private static ShizukuHandler shizukuHandler;
    private final String tunableCommand = Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE ? "device_config put device_idle" : "settings put global device_idle_constants";

    private static void log(String message) {
        logToLogcat(TAG, message);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_tunables);
        if (savedInstanceState == null) {
            getSupportFragmentManager()
                    .beginTransaction()
                    .replace(R.id.content, new DozeTunablesFragment())
                    .commit();
        }

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        getSupportActionBar().setDisplayHomeAsUpEnabled(true);

        View appBar = findViewById(R.id.appbarlayout);
        View content = findViewById(R.id.content);
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.coordinator), (v, windowInsets) -> {
            Insets bars = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
            appBar.setPadding(bars.left, bars.top, bars.right, 0);
            content.setPadding(bars.left, 0, bars.right, 0);
            // Not consumed: DozeTunablesFragment pads its list with the bottom inset.
            return windowInsets;
        });
    }

    @Override
    public boolean onPrepareOptionsMenu(Menu menu) {
        super.onPrepareOptionsMenu(menu);

        // Applying needs root or a running Shizuku; without either only the adb command can be copied.
        boolean canApply = suAvailable || (Utils.isShizukuMode(this) && isShizukuAvailable);
        menu.findItem(R.id.action_apply_tunables).setVisible(canApply);

        return true;
    }
    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.doze_tunables_menu, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.action_apply_tunables) {
            applyTunables();
        } else if (id == R.id.action_copy_tunables) {
            showCopyTunableDialog();
        } else if (id == android.R.id.home) {
            getOnBackPressedDispatcher().onBackPressed();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    public void applyTunables() {
        String tunable_string = DozeTunableHandler.getInstance().getTunableString();
        log("Setting device_idle_constants=" + tunable_string);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            for (String s : tunable_string.split(",")) {
                executeCommand(tunableCommand + " " + TextUtils.join(" ", s.split("=")));
            }
        } else {
            executeCommand(tunableCommand + " " + tunable_string);

        }
        // The commands run asynchronously and are not read back here, so do not claim success.
        Toast.makeText(this, getString(R.string.tunables_apply_sent), Toast.LENGTH_SHORT).show();
    }

    /**
     * Shows the verified per-key outcome of an apply: keys whose new value was read back, keys the
     * running Android version ignores, and keys whose command or readback failed.
     */
    public void showApplyResult(List<String> applied, List<String> notEffective, List<String> failed) {
        StringBuilder message = new StringBuilder();
        appendResultSection(message, R.string.tunables_result_applied, applied);
        appendResultSection(message, R.string.tunables_result_not_effective, notEffective);
        appendResultSection(message, R.string.tunables_result_failed, failed);
        new MaterialAlertDialogBuilder(this)
                .setTitle(failed.isEmpty() && notEffective.isEmpty()
                        ? R.string.tunables_result_title_applied
                        : R.string.tunables_result_title_partial)
                .setMessage(message.toString())
                .setPositiveButton(R.string.okay_button_text, null)
                .show();
    }

    private void appendResultSection(StringBuilder message, int labelRes, List<String> keys) {
        if (keys.isEmpty()) {
            return;
        }
        if (message.length() > 0) {
            message.append("\n\n");
        }
        message.append(getString(labelRes, keys.size())).append('\n').append(TextUtils.join(", ", keys));
    }

    public void showCopyTunableDialog() {
        String tunable_string = DozeTunableHandler.getInstance().getTunableString();
        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(this);
        builder.setTitle(getString(R.string.adb_command_text));
        builder.setMessage("You can apply the new values using ADB by running the following command:\n\nadb shell " + tunableCommand + " " + tunable_string);
        builder.setPositiveButton(getString(R.string.close_button_text), new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialogInterface, int i) {
                dialogInterface.dismiss();
            }
        });
        builder.setNegativeButton(getString(R.string.copy_to_clipboard_button_text), new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialogInterface, int i) {
                ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                ClipData clip = ClipData.newPlainText("Copied Tunable k/v string", "adb shell " + tunableCommand + " " + tunable_string);
                clipboard.setPrimaryClip(clip);
                dialogInterface.dismiss();
            }
        });
        builder.show();
    }

    public static void executeCommand(final String command) {
        boolean useShizuku = Utils.isShizukuMode(applicationContext.getApplicationContext());

        if (useShizuku && isShizukuAvailable) {
            shizukuHandler.executeCommand(command, (commandCode, exitCode, stdout, stderr) -> {
                printShellOutput(stderr);
                if (exitCode == 0) {
                    printShellOutput(stdout);

                } else {
                    log("Error occurred while executing command (" + command + ")");
                }
            }, false);
            return;
        }
        AsyncTask.execute(new Runnable() {
            @Override
            public void run() {
                List<String> output = Shell.SU.run(command);
                if (output != null) {
                    printShellOutput(output);
                } else {
                    log("Error occurred while executing command (" + command + ")");
                }
            }
        });
    }

    public static void printShellOutput(List<String> output) {
        if (!output.isEmpty()) {
            for (String s : output) {
                log(s);
            }
        }
    }

    @SuppressLint("ValidFragment")
    public  static class DozeTunablesFragment extends PreferenceFragmentCompat {

        MaterialDialog grantPermProgDialog;
        boolean isSuAvailable = false;

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
                            .getInsets(WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout())
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
        public void onCreatePreferences(@Nullable Bundle savedInstanceState, @Nullable String rootKey) {
            addPreferencesFromResource(R.xml.prefs_doze_tunables);
            removeIconSpace(getPreferenceScreen());
            SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(getActivity());
            final PreferenceScreen preferenceScreen = (PreferenceScreen) findPreference("tunablesPreferenceScreen");
            PreferenceCategory lightDozeSettings = (PreferenceCategory) findPreference("lightDozeSettings");

            if (!Utils.isDeviceRunningOnN()) {
                preferenceScreen.removePreference(lightDozeSettings);
            }

            shizukuHandler = ShizukuHandler.getInstance(getActivity());
            boolean useShizuku = Utils.isShizukuMode(getActivity());
            isShizukuAvailable = false;
            if (useShizuku) {
                shizukuHandler.checkShizukuAvailability();
                shizukuHandler.setOnAvailibilityChangeListener(value -> {
                    isShizukuAvailable = value;
                    refreshApplyMenuItem();
                });
                isShizukuAvailable = shizukuHandler.isShizukuAvailable();
                log("Shizuku mode enabled, available: " + isShizukuAvailable);
                if (isShizukuAvailable && !Utils.isSecureSettingsPermissionGranted(getActivity())) {
                    executeCommand("pm grant com.akylas.enforcedoze android.permission.WRITE_SECURE_SETTINGS");
                }
                return;
            }

            if (!preferences.getBoolean("isSuAvailable", false)) {
                grantPermProgDialog = new MaterialDialog.Builder(getActivity())
                        .title(getString(R.string.please_wait_text))
                        .cancelable(false)
                        .autoDismiss(false)
                        .content(getString(R.string.requesting_su_access_text))
                        .progress(true, 0)
                        .show();
                log("Check if SU is available, and request SU permission if it is");
                Tasks.executeInBackground(getActivity(), new BackgroundWork<Boolean>() {
                    @Override
                    public Boolean doInBackground() throws Exception {
                        return Shell.SU.available();
                    }
                }, new Completion<Boolean>() {
                    @Override
                    public void onSuccess(Context context, Boolean result) {
                        if (grantPermProgDialog != null) {
                            grantPermProgDialog.dismiss();
                        }
                        isSuAvailable = result;
                        suAvailable = isSuAvailable;
                        refreshApplyMenuItem();
                        log("SU available: " + Boolean.toString(result));
                        if (isSuAvailable) {
                            log("Phone is rooted and SU permission granted");
                            if (!Utils.isSecureSettingsPermissionGranted(getActivity())) {
                                executeCommand("pm grant com.akylas.enforcedoze android.permission.WRITE_SECURE_SETTINGS");
                            }
                        } else {
                            log("SU permission denied or not available");
                            MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(context);
                            builder.setTitle(getString(R.string.error_text));
                            builder.setMessage(getString(R.string.tunables_su_not_available_error_text));
                            builder.setPositiveButton(getString(R.string.okay_button_text), new DialogInterface.OnClickListener() {
                                @Override
                                public void onClick(DialogInterface dialogInterface, int i) {
                                    dialogInterface.dismiss();
                                }
                            });
                            builder.show();
                        }
                    }

                    @Override
                    public void onError(Context context, Exception e) {
                        Log.e(TAG, "Error querying SU: " + e.getMessage());
                    }
                });
            } else {
                suAvailable = true;
            }
        }


//        public void executeCommand(final String command) {
//            boolean useShizuku = Utils.isShizukuMode(getActivity());
//
//            if (useShizuku && isShizukuAvailable) {
//                shizukuHandler.executeCommand(command, (commandCode, exitCode, stdout, stderr) -> {
//                        printShellOutput(stdout);
//                        printShellOutput(stderr);
//                }, false);
//                return;
//            }
//            AsyncTask.execute(new Runnable() {
//                @Override
//                public void run() {
//                    List<String> output = Shell.SU.run(command);
//                    if (output != null) {
//                        printShellOutput(output);
//                    } else {
//                        log("Error occurred while executing command (" + command + ")");
//                    }
//                }
//            });
//        }

        public void printShellOutput(List<String> output) {
            if (!output.isEmpty()) {
                for (String s : output) {
                    log(s);
                }
            }
        }

        // Access is detected asynchronously, after the menu was first prepared.
        private void refreshApplyMenuItem() {
            if (getActivity() != null) {
                getActivity().invalidateOptionsMenu();
            }
        }
    }
}
