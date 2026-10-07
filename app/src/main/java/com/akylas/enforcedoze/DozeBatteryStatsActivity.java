package com.akylas.enforcedoze;

import static com.akylas.enforcedoze.Utils.logToLogcat;

import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.preference.PreferenceManager;

import androidx.appcompat.widget.Toolbar;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.drawable.DrawableCompat;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;

import com.afollestad.materialdialogs.MaterialDialog;
import com.akylas.enforcedoze.service.LegacyDozeStats;
import com.akylas.enforcedoze.ui.StatsColorRules;
import com.akylas.enforcedoze.ui.amber.AmberDialogs;
import com.google.android.material.color.MaterialColors;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.nanotasks.BackgroundWork;
import com.nanotasks.Completion;
import com.nanotasks.Tasks;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

public class DozeBatteryStatsActivity extends AppCompatActivity {

    Set<String> dozeUsageStats;
    ArrayList<String> sortedDozeUsageStats;
    MaterialDialog progressDialog = null;
    RecyclerView mListView;
    DozeStatsAdapter adapter;
    SharedPreferences sharedPreferences;
    SharedPreferences.Editor editor;
    public static String TAG = "EnforceDoze";

    private static void log(String message) {
        logToLogcat(TAG, message);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_doze_battery_stats);

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        getSupportActionBar().setDisplayHomeAsUpEnabled(true);

        sharedPreferences = PreferenceManager.getDefaultSharedPreferences(getApplicationContext());
        editor = sharedPreferences.edit();
        dozeUsageStats = PreferenceManager.getDefaultSharedPreferences(getApplicationContext()).getStringSet("dozeUsageDataAdvanced", new LinkedHashSet<String>());
        mListView = findViewById(R.id.material_listview);
        adapter = new DozeStatsAdapter();
        mListView.setAdapter(adapter);
        mListView.setLayoutManager(new LinearLayoutManager(this));

        ViewCompat.setOnApplyWindowInsetsListener(mListView, (v, insets) -> {
            if (mListView != null) {
                int bottomInset = insets
                        .getInsets(WindowInsetsCompat.Type.systemBars())
                        .bottom;

                mListView.setPadding(
                        mListView.getPaddingLeft(),
                        mListView.getPaddingTop(),
                        mListView.getPaddingRight(),
                        bottomInset
                );
                mListView.setClipToPadding(false);
            }
            return insets;
        });
        if (!dozeUsageStats.isEmpty()) {
            sortedDozeUsageStats = new ArrayList<>(dozeUsageStats);
            Collections.sort(sortedDozeUsageStats);
            Collections.reverse(sortedDozeUsageStats);
            log("Size: " + sortedDozeUsageStats.size());

            if (sharedPreferences.contains("dozeUsageData")) {
                log("Found old stats data, deleting..");
                editor.remove("dozeUsageData").commit();
            } else if (sharedPreferences.contains("dozeUsageDataNew")) {
                log("Found old stats data, deleting..");
                editor.remove("dozeUsageDataNew").commit();
            }

            for (LegacyDozeStats.Interval interval : LegacyDozeStats.intervals(dozeUsageStats)) {
                LegacyDozeStats.Entry enter = interval.getStart();
                LegacyDozeStats.Entry exit = interval.getEnd();
                int batteryUsed = (int) enter.getBattery() - (int) exit.getBattery();
                DozeStatsCard card = new DozeStatsCard(
                        interval.getMaintenance() ? "Doze Session (Maintenance)" : "Doze Session",
                        "Start Time: " + Utils.getDateCurrentTimeZone(enter.getTime()) +
                                "\nEnd Time: " + Utils.getDateCurrentTimeZone(exit.getTime()) +
                                "\nTime spent: " + Utils.timeSpentString(enter.getTime(), exit.getTime()) +
                                "\nBattery used: " + batteryUsed + "%",
                        returnDrawableBattery(batteryUsed)
                );
                adapter.addCard(card);
            }
            mListView.scrollToPosition(0);
        }
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.doze_stats_menu_new, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.action_clear_stats) {
            clearStats();
        } else if (id == R.id.action_switch_stats_ui) {
            startActivity(new Intent(this, DozeStatsActivity.class));
        } else if (id == R.id.action_stats_more_info) {
            showMoreInfoDialog();
        } else if (id == android.R.id.home) {
            onBackPressed();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    public void showMoreInfoDialog() {
        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(this);
        builder.setTitle(getString(R.string.doze_stats_battery_icon_meaning_dialog_title));
        builder.setMessage(getString(R.string.doze_stats_battery_icon_meaning_dialog_text));
        builder.setPositiveButton(getString(R.string.close_button_text), new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialogInterface, int i) {
                dialogInterface.dismiss();
            }
        });
        builder.show();
    }

    public Drawable returnDrawableBattery(int bUsage) {
        boolean drain = StatsColorRules.isDrain(bUsage);
        Drawable icon = ContextCompat.getDrawable(this, StatsColorRules.batteryIcon(drain)).mutate();
        DrawableCompat.setTint(icon, MaterialColors.getColor(this, StatsColorRules.batteryTint(drain), TAG));
        return icon;
    }

    public void clearStats() {
        progressDialog = AmberDialogs.builder(this)
                .title(getString(R.string.please_wait_text))
                .cancelable(false)
                .autoDismiss(false)
                .content(getString(R.string.clearing_doze_stats_text))
                .progress(true, 0)
                .show();

        Tasks.executeInBackground(DozeBatteryStatsActivity.this, new BackgroundWork<Boolean>() {
            @Override
            public Boolean doInBackground() throws Exception {
                log("Clearing Doze stats");
                editor.remove("dozeUsageDataAdvanced");
                return editor.commit();
            }
        }, new Completion<Boolean>() {
            @Override
            public void onSuccess(Context context, Boolean result) {
                if (progressDialog != null) {
                    progressDialog.dismiss();
                }
                if (result) {
                    log("Doze stats successfully cleared");
                    if (Utils.isMyServiceRunning(ForceDozeService.class, context)) {
                        Intent intent = new Intent("reload-settings");
                        LocalBroadcastManager.getInstance(context).sendBroadcast(intent);
                    }
                    adapter.clearAll();
                    MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(context);
                    builder.setTitle(getString(R.string.cleared_text));
                    builder.setMessage(getString(R.string.doze_battery_stats_clear_msg));
                    builder.setPositiveButton(getString(R.string.close_button_text), new DialogInterface.OnClickListener() {
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
                Log.e(TAG, "Error clearing Doze stats: " + e.getMessage());

            }
        });
    }
}
