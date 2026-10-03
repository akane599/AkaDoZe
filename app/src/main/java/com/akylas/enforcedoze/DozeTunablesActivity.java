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
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.Toast;

import com.afollestad.materialdialogs.MaterialDialog;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.nanotasks.Completion;
import com.nanotasks.Tasks;

import java.util.List;

import com.akylas.enforcedoze.access.AccessManager;
import com.akylas.enforcedoze.access.AccessLevel;

public class DozeTunablesActivity extends AppCompatActivity {

    public static String TAG = "EnforceDoze";
    public static boolean suAvailable = false;
    public DozeTunableHandler.ApplyResult lastApplyResult;
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

        // Applying needs root, Shizuku (shell) or WRITE_SECURE_SETTINGS; otherwise only the adb command can be copied.
        boolean canApply = suAvailable;
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
        lastApplyResult = null;
        String tunables = DozeTunableHandler.getInstance().getTunableString();
        AccessManager manager = AccessManager.getInstance(this);
        Tasks.executeInBackground(this, () -> DozeTunableHandler.apply(manager.control(), manager.reads(),
                Build.VERSION.SDK_INT, manager.getState().getGrants(), tunables),
                new Completion<DozeTunableHandler.ApplyResult>() {
                    @Override
                    public void onSuccess(Context context, DozeTunableHandler.ApplyResult result) {
                        lastApplyResult = result;
                        if (result.allApplied()) {
                            Toast.makeText(DozeTunablesActivity.this, getString(R.string.applied_success_text), Toast.LENGTH_SHORT).show();
                        } else {
                            log("Tunable readback: " + result.keys);
                            showApplyResult(result.applied, result.notEffective, result.failed);
                        }
                    }
                    @Override
                    public void onError(Context context, Exception error) {
                        log("Error applying tunables: " + error.getMessage());
                    }
                });
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

    @SuppressLint("ValidFragment")
    public  static class DozeTunablesFragment extends PreferenceFragmentCompat {

        MaterialDialog grantPermProgDialog;
        private AccessManager accessManager;
        private boolean helpersRequested;
        private final AccessManager.Listener accessListener = state -> {
            suAvailable = state.getLevel() == AccessLevel.ROOT || state.getLevel() == AccessLevel.SHELL
                    || state.getGrants().getWriteSecureSettings();
            refreshApplyMenuItem();
            if (!helpersRequested && (state.getLevel() == AccessLevel.ROOT || state.getLevel() == AccessLevel.SHELL)) {
                helpersRequested = true;
                AsyncTask.execute(() -> accessManager.grantHelpers());
            }
        };

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

            accessManager = AccessManager.getInstance(requireContext());
        }

        // Access is detected asynchronously, after the menu was first prepared.
        private void refreshApplyMenuItem() {
            if (getActivity() != null) {
                getActivity().invalidateOptionsMenu();
            }
        }
    }
}
