package com.akylas.enforcedoze;

import static com.akylas.enforcedoze.Utils.logToLogcat;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.provider.Settings;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import android.text.InputType;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;

import com.afollestad.materialdialogs.MaterialDialog;
import com.akylas.enforcedoze.ui.amber.AmberDialogs;
import com.akylas.enforcedoze.ui.amber.Haptics;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.nanotasks.Completion;
import com.nanotasks.Tasks;

import java.util.ArrayList;
import java.util.List;

import android.os.Build;
import com.akylas.enforcedoze.access.AccessManager;
import com.akylas.enforcedoze.access.CommandRunner;
import com.akylas.enforcedoze.access.CommandResult;
import com.akylas.enforcedoze.access.CommandCatalog;
import com.akylas.enforcedoze.access.CapabilityResolver;
import com.akylas.enforcedoze.access.FeatureStatus;
import com.akylas.enforcedoze.access.Feature;
import com.akylas.enforcedoze.access.Grants;
import com.akylas.enforcedoze.access.PackageNames;
import com.akylas.enforcedoze.access.Reason;
import com.akylas.enforcedoze.access.WhitelistParser;
import com.akylas.enforcedoze.access.WhitelistParseResult;
import com.akylas.enforcedoze.access.WhitelistParseReason;
import com.akylas.enforcedoze.ui.WhitelistUi;
import java.util.Collections;

public class WhitelistAppsActivity extends AppCompatActivity {
    RecyclerView recyclerView;
    SharedPreferences sharedPreferences;
    AppsAdapter whitelistAppsAdapter;
    ArrayList<String> whitelistedPackages;
    ArrayList<AppsItem> listData;
    public static String TAG = "EnforceDoze";
    boolean showDozeWhitelistWarning = true;
    private AccessManager accessManager;
    public WhitelistResult lastResult;
    MaterialDialog progressDialog = null;private static void log(String message) {
        logToLogcat(TAG, message);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        setContentView(R.layout.activity_whitelist_apps);

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        getSupportActionBar().setDisplayHomeAsUpEnabled(true);

        recyclerView = findViewById(R.id.recycler_view);
        View appBar = findViewById(R.id.appbarlayout);
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.coordinatorLayout), (v, windowInsets) -> {
            Insets bars = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
            appBar.setPadding(bars.left, bars.top, bars.right, 0);
            recyclerView.setPadding(bars.left, 0, bars.right, bars.bottom);
            return WindowInsetsCompat.CONSUMED;
        });
        recyclerView.setHasFixedSize(true);
        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        whitelistedPackages = new ArrayList<>();
        listData = new ArrayList<>();
        sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this);
        whitelistAppsAdapter = new AppsAdapter(this, listData);
        recyclerView.setAdapter(whitelistAppsAdapter);
        accessManager = AccessManager.getInstance(this);
        loadPackagesFromWhitelist();
        showDozeWhitelistWarning = sharedPreferences.getBoolean("showDozeWhitelistWarning", true);

        ItemTouchHelper.SimpleCallback simpleItemTouchCallback = new ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.LEFT | ItemTouchHelper.RIGHT) {
            public boolean onMove(RecyclerView recyclerView,
                                  RecyclerView.ViewHolder viewHolder, RecyclerView.ViewHolder target) {
                return false;// true if moved, false otherwise
            }

            @Override
            public void onSwiped(RecyclerView.ViewHolder viewHolder, int swipeDir) {
                verifyAndRemovePackage(listData.get(viewHolder.getLayoutPosition()).getAppPackageName());
            }
        };
        ItemTouchHelper itemTouchHelper = new ItemTouchHelper(simpleItemTouchCallback);
        itemTouchHelper.attachToRecyclerView(recyclerView);

        if (showDozeWhitelistWarning) {
            displayDialog(getString(R.string.whitelisting_text), getString(R.string.whitelisted_apps_restrictions_text));
            SharedPreferences.Editor editor = sharedPreferences.edit();
            editor.putBoolean("showDozeWhitelistWarning", false);
            editor.apply();
        }
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.whitelist_menu, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.action_add_whitelist) {
            startActivityForResult(new Intent(WhitelistAppsActivity.this, PackageChooserActivity.class), 999);
        } else if (id == R.id.action_add_whitelist_package) {
            showManuallyAddPackageDialog();
        } else if (id == R.id.action_remove_whitelist_package) {
            showManuallyRemovePackageDialog();
        } else if (id == R.id.action_whitelist_more_info) {
            displayDialog(getString(R.string.whitelisting_text), getString(R.string.whitelisted_apps_restrictions_text));
        } else if (id == R.id.action_launch_system_whitelist) {
            startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));

            getOnBackPressedDispatcher().onBackPressed();
            return true;
        } else if (id == android.R.id.home) {
            getOnBackPressedDispatcher().onBackPressed();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (data != null) {
            if (requestCode == 999) {
                String pkg = data.getStringExtra("package_name");
                verifyAndAddPackage(pkg);
            } else if (requestCode == 998) {
                String pkg = data.getStringExtra("package_name");
                verifyAndRemovePackage(pkg);
            }
        }
    }

    public void loadPackagesFromWhitelist() {
        loadPackagesFromWhitelist(true);
    }

    /** {@code reportProblems} false after an edit whose own dialog already said what went wrong. */
    void loadPackagesFromWhitelist(boolean reportProblems) {
        log("Loading whitelisted packages...");
        progressDialog = AmberDialogs.builder(this)
                .title(getString(R.string.please_wait_text))
                .autoDismiss(false)
                .cancelable(false)
                .content(R.string.loading_whitelisted_packages)
                .progress(true, 0)
                .show();

        Tasks.executeInBackground(this, () -> readWhitelist(accessManager.reads()), new Completion<WhitelistResult>() {
            @Override
            public void onSuccess(Context context, WhitelistResult result) {
                if (!WhitelistUi.mayTouchUi(isFinishing(), isDestroyed())) return;
                dismissProgress();
                lastResult = result;
                WhitelistUi.Problem problem = WhitelistUi.readProblem(result.parseReason);
                if (problem != null && reportProblems) {
                    displayDialog(getString(WhitelistUi.title(false)), WhitelistUi.text(WhitelistAppsActivity.this,
                            problem, result.unparsedLineCount, result.reason, false, false));
                }
                if (!WhitelistUi.showsList(result.parseReason)) {
                    whitelistAppsAdapter.notifyDataSetChanged();
                    return;
                }
                // A partial read still lists every row it could parse.
                listData.clear();
                whitelistedPackages.clear();
                for (String pkg : result.packages) {
                    AppsItem item = new AppsItem();
                    item.setAppPackageName(pkg);
                    whitelistedPackages.add(pkg);
                    try {
                        item.setAppName(getPackageManager().getApplicationLabel(getPackageManager()
                                .getApplicationInfo(pkg, PackageManager.GET_META_DATA)).toString());
                    } catch (PackageManager.NameNotFoundException e) {
                        item.setAppName(getString(R.string.whitelist_system_package));
                    }
                    listData.add(item);
                }
                whitelistAppsAdapter.notifyDataSetChanged();
            }
            @Override
            public void onError(Context context, Exception error) {
                if (!WhitelistUi.mayTouchUi(isFinishing(), isDestroyed())) return;
                dismissProgress();
                log("Error loading packages: " + error.getMessage());
                if (reportProblems) {
                    displayDialog(getString(WhitelistUi.title(false)), WhitelistUi.text(WhitelistAppsActivity.this,
                            WhitelistUi.Problem.READ_FAILED, 0, null, false, false));
                }
                whitelistAppsAdapter.notifyDataSetChanged();
            }
        });
    }

    private void dismissProgress() {
        if (progressDialog != null) progressDialog.dismiss();
        progressDialog = null;
    }

    /** An in-flight read or edit result is dropped by its callback's guard; only its dialog is ours to close. */
    @Override
    protected void onDestroy() {
        dismissProgress();
        super.onDestroy();
    }

    public static final class WhitelistResult {
        public final List<String> packages;
        public final boolean verified;
        public final Reason reason;
        public final WhitelistParseReason parseReason;
        public final int unparsedLineCount;
        WhitelistResult(List<String> packages, boolean verified, Reason reason) {
            this(packages, verified, reason, null, 0);
        }
        WhitelistResult(List<String> packages, boolean verified, Reason reason,
                        WhitelistParseReason parseReason, int unparsedLineCount) {
            this.packages = Collections.unmodifiableList(new ArrayList<>(packages));
            this.verified = verified;
            this.reason = reason;
            this.parseReason = parseReason;
            this.unparsedLineCount = unparsedLineCount;
        }
    }

    static WhitelistResult readWhitelist(CommandRunner reads) {
        return parseWhitelist(reads.run("dumpsys deviceidle whitelist"));
    }

    static WhitelistResult parseWhitelist(CommandResult result) {
        WhitelistParseResult parsed = WhitelistParser.parse(result);
        return new WhitelistResult(parsed.getPackages(), parsed.getVerified(),
                parsed.getVerified() ? null : Reason.UNVERIFIED, parsed.getParseReason(), parsed.getUnparsedLineCount());
    }

    static WhitelistResult editWhitelist(CommandRunner control, CommandRunner reads, int apiLevel,
                                         Grants grants, String pkg, boolean remove) {
        if (pkg == null || !PackageNames.isValid(pkg)) {
            return new WhitelistResult(Collections.emptyList(), false, Reason.UNVERIFIED);
        }
        FeatureStatus status = CapabilityResolver.status(Feature.WHITELIST_EDIT, control.getLevel(), apiLevel, grants);
        if (status instanceof FeatureStatus.Unavailable) {
            return new WhitelistResult(Collections.emptyList(), false, ((FeatureStatus.Unavailable) status).getReason());
        }
        List<String> commands = CommandCatalog.setEnabled(Feature.WHITELIST_EDIT, apiLevel, !remove, pkg);
        if (commands == null) return new WhitelistResult(Collections.emptyList(), false, Reason.UNVERIFIED);
        for (String command : commands) control.run(command);
        WhitelistResult readback = readWhitelist(reads);
        boolean verified = readback.verified && (readback.packages.contains(pkg) != remove);
        return new WhitelistResult(readback.packages, verified, verified ? null : Reason.UNVERIFIED,
                readback.parseReason, readback.unparsedLineCount);
    }

    public void showManuallyAddPackageDialog() {
        AmberDialogs.builder(this)
                .title(getString(R.string.whitelist_apps_setting_text))
                .content(R.string.manually_add_package_dialog_text)
                .inputType(InputType.TYPE_CLASS_TEXT)
                .cancelable(true)
                .input("com.spotify.music", "", false, new MaterialDialog.InputCallback() {
                    @Override
                    public void onInput(MaterialDialog dialog, CharSequence input) {
                        Haptics.tick(recyclerView);
                        verifyAndAddPackage(input.toString());
                    }
                }).show();
    }

    public void showManuallyRemovePackageDialog() {
        AmberDialogs.builder(this)
                .title(getString(R.string.whitelist_apps_setting_text))
                .content(R.string.manually_remove_package_dialog_text)
                .inputType(InputType.TYPE_CLASS_TEXT)
                .cancelable(true)
                .input("com.spotify.music", "", false, new MaterialDialog.InputCallback() {
                    @Override
                    public void onInput(MaterialDialog dialog, CharSequence input) {
                        Haptics.tick(recyclerView);
                        verifyAndRemovePackage(input.toString());
                    }
                }).show();
    }


    public void verifyAndAddPackage(String packageName) {
        if (whitelistedPackages.contains(packageName)) {
            displayDialog(getString(R.string.info_text), getString(R.string.app_already_whitelisted_text));
        } else {
            modifyWhitelist(packageName, false);
        }
    }

    public void verifyAndRemovePackage(String packageName) {
        if (!whitelistedPackages.contains(packageName)) {
            displayDialog(getString(R.string.info_text), getString(R.string.app_not_whitelisted_text));
        } else {
            modifyWhitelist(packageName, true);
        }
    }

    public void modifyWhitelist(String packageName, boolean remove) {
        if (packageName == null || !PackageNames.isValid(packageName)) {
            displayDialog(getString(WhitelistUi.title(true)), WhitelistUi.text(this,
                    WhitelistUi.Problem.INVALID_PACKAGE, 0, null, true, remove));
            whitelistAppsAdapter.notifyDataSetChanged();
            return;
        }
        Tasks.executeInBackground(this, () -> {
            if (!remove) getPackageManager().getApplicationInfo(packageName, 0);
            return editWhitelist(accessManager.control(), accessManager.reads(), Build.VERSION.SDK_INT,
                    accessManager.getState().getGrants(), packageName, remove);
        }, new Completion<WhitelistResult>() {
            @Override
            public void onSuccess(Context context, WhitelistResult result) {
                if (!WhitelistUi.mayTouchUi(isFinishing(), isDestroyed())) return;
                lastResult = result;
                WhitelistUi.Problem problem = WhitelistUi.editProblem(result.verified, result.reason, result.parseReason);
                if (problem != null) {
                    displayDialog(getString(WhitelistUi.title(true)), WhitelistUi.text(WhitelistAppsActivity.this,
                            problem, result.unparsedLineCount, result.reason, true, remove));
                }
                loadPackagesFromWhitelist(problem == null);
            }
            @Override
            public void onError(Context context, Exception error) {
                if (!WhitelistUi.mayTouchUi(isFinishing(), isDestroyed())) return;
                log("Error modifying whitelist: " + error.getMessage());
                WhitelistUi.Problem problem = error instanceof PackageManager.NameNotFoundException
                        ? WhitelistUi.Problem.NOT_INSTALLED : WhitelistUi.Problem.ERROR;
                displayDialog(getString(WhitelistUi.title(true)), WhitelistUi.text(WhitelistAppsActivity.this,
                        problem, 0, null, true, remove));
                whitelistAppsAdapter.notifyDataSetChanged();
            }
        });
    }

    public void displayDialog(String title, String message) {
        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(this);
        builder.setTitle(title);
        builder.setMessage(message);
        builder.setPositiveButton(getString(R.string.close_button_text), null);
        builder.show();
    }


}
