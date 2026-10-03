package com.akylas.enforcedoze;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Bundle;
import android.util.Log;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;

import com.afollestad.materialdialogs.MaterialDialog;
import com.nanotasks.BackgroundWork;
import com.nanotasks.Completion;
import com.nanotasks.Tasks;

import java.util.Collections;
import java.util.List;

public class PackageChooserActivity extends AppCompatActivity {
    AppAdapter adapter = null;
    ListView listView;
    MaterialDialog progressDialog = null;
    public static String TAG = "EnforceDoze";
    PackageManager pm;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        setContentView(R.layout.package_chooser_layout);

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        getSupportActionBar().setDisplayHomeAsUpEnabled(true);

        listView = findViewById(R.id.list);
        View appBar = findViewById(R.id.appbarlayout);
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.coordinatorLayout), (v, windowInsets) -> {
            Insets bars = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
            appBar.setPadding(bars.left, bars.top, bars.right, 0);
            listView.setPadding(bars.left, 0, bars.right, bars.bottom);
            return WindowInsetsCompat.CONSUMED;
        });
        listView.setOnItemClickListener((parent, view, position, id) -> onPackageChosen(position));

        pm = getPackageManager();

        progressDialog = new MaterialDialog.Builder(this)
                .title(getString(R.string.please_wait_text))
                .autoDismiss(false)
                .cancelable(false)
                .content(R.string.loading_installed_apps_text)
                .progress(true, 0)
                .show();

        Tasks.executeInBackground(PackageChooserActivity.this, new BackgroundWork<List<ResolveInfo>>() {
            @Override
            public List<ResolveInfo> doInBackground() throws Exception {
                Intent main = new Intent(Intent.ACTION_MAIN, null);
                main.addCategory(Intent.CATEGORY_LAUNCHER);
                List<ResolveInfo> launchables = pm.queryIntentActivities(main, 0);
                Collections.sort(launchables,
                        new ResolveInfo.DisplayNameComparator(pm));
                return launchables;
            }
        }, new Completion<List<ResolveInfo>>() {
            @Override
            public void onSuccess(Context context, List<ResolveInfo> result) {
                if (progressDialog != null) {
                    progressDialog.dismiss();
                }
                adapter = new AppAdapter(pm, result);
                listView.setAdapter(adapter);
            }

            @Override
            public void onError(Context context, Exception e) {
                Log.e(TAG, "Error loading packages: " + e.getMessage());

            }
        });
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            getOnBackPressedDispatcher().onBackPressed();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void onPackageChosen(int position) {

        ResolveInfo launchable = adapter.getItem(position);
        ActivityInfo activity = launchable.activityInfo;
        ComponentName name = new ComponentName(activity.applicationInfo.packageName,
                activity.name);

        String pack_name = name.getPackageName();

        Intent intentMessage = new Intent();
        intentMessage.putExtra("package_name", pack_name);
        setResult(1, intentMessage);
        finish();

    }

    class AppAdapter extends ArrayAdapter<ResolveInfo> {
        private PackageManager pm = null;
        AppAdapter(PackageManager pm, List<ResolveInfo> apps) {
            super(PackageChooserActivity.this, R.layout.package_chooser_row, apps);
            this.pm = pm;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            if (convertView == null) {
                convertView = newView(parent);
            }

            bindView(position, convertView);
            return (convertView);
        }

        private View newView(ViewGroup parent) {
            return (getLayoutInflater().inflate(R.layout.package_chooser_row, parent, false));
        }

        private void bindView(int position, View row) {
            TextView label = (TextView) row.findViewById(R.id.label);
            label.setText(getItem(position).loadLabel(pm));
            ImageView icon = (ImageView) row.findViewById(R.id.icon);
            icon.setImageDrawable(getItem(position).loadIcon(pm));
        }
    }


}
