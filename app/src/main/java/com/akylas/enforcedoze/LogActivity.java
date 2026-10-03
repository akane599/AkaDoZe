package com.akylas.enforcedoze;

import static com.akylas.enforcedoze.Utils.logToLogcat;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;

import androidx.appcompat.app.AppCompatActivity;
import android.view.Menu;
import android.view.MenuItem;
import android.widget.EditText;

import com.afollestad.materialdialogs.MaterialDialog;
import com.nanotasks.Completion;
import com.nanotasks.Tasks;

import java.util.List;

import com.akylas.enforcedoze.access.AccessManager;
import com.akylas.enforcedoze.access.CommandResult;
import com.akylas.enforcedoze.monitor.ReportExporter;
import java.util.ArrayList;
import java.util.Collections;

public class LogActivity extends AppCompatActivity {

    public static String TAG = "EnforceDoze";
    public List<String> log = Collections.emptyList();
    MaterialDialog progressDialog = null;

    private static void log(String message) {
        logToLogcat(TAG, message);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_log);

        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }

        progressDialog = new MaterialDialog.Builder(this)
                .title("Please wait")
                .cancelable(false)
                .autoDismiss(false)
                .content("Requesting SU access and fetching log")
                .progress(true, 0)
                .show();
        grantLogsPermissionAndPrintLog();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.debug_logs_menu, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.action_share_log) {
            saveAndShareLog();
        } else if (id == R.id.action_share_fulllog) {
            progressDialog = new MaterialDialog.Builder(this)
                    .title("Please wait")
                    .content("Requesting SU access and fetching log...")
                    .progress(true, 0)
                    .show();
            getFullLogcat();
        } else if (id == android.R.id.home) {
            onBackPressed();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    static String logCommand(boolean full) {
        return full ? "logcat -d" : "logcat -d -s EnforceDoze ForceDozeService ShizukuHandler Utils ForceDozeTileService BlockAppsActivity AirplaneTileService";
    }

    public void getAndPrintLogcat() { readLogs(false); }
    public void getFullLogcat() { readLogs(true); }
    public void grantLogsPermissionAndPrintLog() { getAndPrintLogcat(); }

    private void readLogs(boolean full) {
        AccessManager manager = AccessManager.getInstance(this);
        Tasks.executeInBackground(this, () -> manager.reads().run(logCommand(full)), new Completion<CommandResult>() {
            @Override
            public void onSuccess(Context context, CommandResult result) {
                dismissProgress();
                if (full) {
                    if (result.getOk()) saveAndShareFullLog(result.getStdout());
                    else log("Unable to get full logcat");
                    return;
                }
                log = result.getOk() ? new ArrayList<>(result.getStdout()) : Collections.emptyList();
                EditText view = findViewById(R.id.editText);
                view.setLongClickable(false);
                view.setFocusable(false);
                view.setClickable(true);
                view.setText(result.getOk() ? android.text.TextUtils.join("\n", log) : "Unable to get logcat");
            }
            @Override
            public void onError(Context context, Exception error) {
                dismissProgress();
                log("Error getting logcat: " + error.getMessage());
                if (!full) {
                    log = Collections.emptyList();
                    ((EditText) findViewById(R.id.editText)).setText("Unable to get logcat");
                }
            }
        });
    }

    private void dismissProgress() {
        if (progressDialog != null) progressDialog.dismiss();
    }

    public void saveAndShareLog() { shareLines(log); }
    public void saveAndShareFullLog(List<String> logcat) { shareLines(logcat); }

    private void shareLines(List<String> lines) {
        if (lines == null || lines.isEmpty()) {
            log("Unable to get logcat");
            return;
        }
        String text = android.text.TextUtils.join("\n", new ArrayList<>(lines));
        ReportExporter exporter = new ReportExporter(this);
        Tasks.executeInBackground(this, () -> exporter.export(text), new Completion<Uri>() {
            @Override
            public void onSuccess(Context context, Uri uri) {
                startActivity(Intent.createChooser(exporter.shareIntent(uri), ""));
            }
            @Override
            public void onError(Context context, Exception error) {
                log("Error sharing logcat: " + error.getMessage());
            }
        });
    }

}
