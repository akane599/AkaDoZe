package com.akylas.enforcedoze;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.os.Bundle;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import android.view.MenuItem;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;

import android.content.SharedPreferences;
import androidx.preference.PreferenceManager;

import com.akylas.enforcedoze.access.ExternalControlPolicy;
import com.akylas.enforcedoze.access.Prefs;
import com.akylas.enforcedoze.ui.amber.Haptics;

public class TaskerBroadcastsActivity extends AppCompatActivity {

    /** Scalar preferences from prefs.xml; the list shown is filtered through ExternalControlPolicy. */
    private static final String[] SETTING_CANDIDATES = {
            "turnOffDataInDoze", "turnOffWiFiInDoze", "turnOnAirplaneInDoze", "turnOffBluetoothInDoze",
            "turnOffGPSInDoze", "ignoreIfHotspot", "disableMotionSensors", "turnOffAllSensorsInDoze",
            "turnOffBiometricsInDoze", "turnOnBatterySaverInDoze", "whitelistMusicAppNetwork", "whitelistCurrentApp",
            "keepDozeEnforced", "ignoreLockscreenTimeout", "dozeEnterDelay", "disableWhenCharging",
            "showPersistentNotif", "showDisabledNotification", "screenOnSummary", "disableStats",
    };

    ArrayList<TaskerBroadcastsItem> items;
    ListView listView;
    TaskerBroadcastsAdapter taskerBroadcastsAdapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_tasker_broadcasts);

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        getSupportActionBar().setDisplayHomeAsUpEnabled(true);

        listView = (ListView) findViewById(R.id.listViewBroadcasts);
        View appBar = findViewById(R.id.appbarlayout);
        int listPaddingLeft = listView.getPaddingLeft();
        int listPaddingTop = listView.getPaddingTop();
        int listPaddingRight = listView.getPaddingRight();
        int listPaddingBottom = listView.getPaddingBottom();
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.coordinatorLayout), (v, windowInsets) -> {
            Insets bars = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
            appBar.setPadding(bars.left, bars.top, bars.right, 0);
            listView.setPadding(listPaddingLeft + bars.left, listPaddingTop,
                    listPaddingRight + bars.right, listPaddingBottom + bars.bottom);
            return windowInsets;
        });
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        String basicGate = getString(R.string.tasker_gate_basic, getString(prefs.getBoolean(
                Prefs.ALLOW_EXTERNAL_BASIC_CONTROL, Prefs.DEFAULT_ALLOW_EXTERNAL_BASIC_CONTROL)
                ? R.string.tasker_gate_on : R.string.tasker_gate_off));
        String privilegedGate = getString(R.string.tasker_gate_privileged, getString(prefs.getBoolean(
                Prefs.ALLOW_EXTERNAL_PRIVILEGED_CONTROL, Prefs.DEFAULT_ALLOW_EXTERNAL_PRIVILEGED_CONTROL)
                ? R.string.tasker_gate_on : R.string.tasker_gate_off));
        // Only settings the external-control policy accepts; it rejects everything else.
        StringBuilder settings = new StringBuilder();
        int index = 1;
        for (String key : SETTING_CANDIDATES) {
            if (ExternalControlPolicy.settingType(key) == null) continue;
            if (settings.length() > 0) settings.append('\n');
            settings.append(index++).append(") ").append(key);
        }
        items = new ArrayList<>();
        items.add(new TaskerBroadcastsItem("com.akylas.enforcedoze.ENABLE_FORCEDOZE",
                getString(R.string.tasker_no_values) + "\n\n" + basicGate));
        items.add(new TaskerBroadcastsItem("com.akylas.enforcedoze.DISABLE_FORCEDOZE",
                getString(R.string.tasker_no_values) + "\n\n" + basicGate));
        items.add(new TaskerBroadcastsItem("com.akylas.enforcedoze.ReenterDoze",
                getString(R.string.tasker_reapply_desc) + "\n\n" + basicGate));
        items.add(new TaskerBroadcastsItem("com.akylas.enforcedoze.ADD_WHITELIST",
                getString(R.string.tasker_add_whitelist_desc) + "\n\n" + privilegedGate));
        items.add(new TaskerBroadcastsItem("com.akylas.enforcedoze.REMOVE_WHITELIST",
                getString(R.string.tasker_remove_whitelist_desc) + "\n\n" + privilegedGate));
        items.add(new TaskerBroadcastsItem("com.akylas.enforcedoze.CHANGE_SETTING",
                getString(R.string.tasker_change_setting_desc, settings.toString()) + "\n\n" + privilegedGate));
        taskerBroadcastsAdapter = new TaskerBroadcastsAdapter(this, items);
        listView.setAdapter(taskerBroadcastsAdapter);

        listView.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> adapterView, View view, int i, long l) {
                ClipboardManager clipboard = (ClipboardManager)getSystemService(CLIPBOARD_SERVICE);
                ClipData broadcastData = ClipData.newPlainText("fd_broadcast", ((TextView)view.findViewById(R.id.broadcastName)).getText());
                clipboard.setPrimaryClip(broadcastData);
                Haptics.tick(view);
                Toast.makeText(getApplicationContext(), R.string.tasker_copied_toast, Toast.LENGTH_SHORT).show();
            }
        });
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();
        switch (id) {
            case android.R.id.home:
                getOnBackPressedDispatcher().onBackPressed();
                return true;
        }
        return super.onOptionsItemSelected(item);
    }
}