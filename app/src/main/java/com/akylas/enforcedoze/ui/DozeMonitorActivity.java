package com.akylas.enforcedoze.ui;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.service.quicksettings.TileService;
import android.view.MenuItem;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.IntentCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.akylas.enforcedoze.BuildConfig;
import com.akylas.enforcedoze.ForceDozeService;
import com.akylas.enforcedoze.Utils;
import com.akylas.enforcedoze.ForceDozeTileService;
import com.akylas.enforcedoze.MainActivity;
import com.akylas.enforcedoze.MyApplication;
import com.akylas.enforcedoze.R;
import com.akylas.enforcedoze.access.AccessManager;
import com.akylas.enforcedoze.access.AccessState;
import com.akylas.enforcedoze.access.Reason;
import com.akylas.enforcedoze.doze.DozeEventSink;
import com.akylas.enforcedoze.doze.EventType;
import com.akylas.enforcedoze.doze.SensorMode;
import com.akylas.enforcedoze.doze.parse.DozeStateReading;
import com.akylas.enforcedoze.doze.parse.SensorModeReading;
import com.akylas.enforcedoze.monitor.JournalEvent;
import com.akylas.enforcedoze.monitor.Problem;
import com.akylas.enforcedoze.monitor.ReportExporter;
import com.akylas.enforcedoze.monitor.ReportFormatter;
import com.akylas.enforcedoze.monitor.SessionAggregator;
import com.akylas.enforcedoze.monitor.SessionSummary;
import com.akylas.enforcedoze.service.SelfTestKind;
import com.akylas.enforcedoze.service.SelfTestResult;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Doze monitor: live state (reads lane, on resume and on request only), the session journal with a
 * per-session timeline, self-tests through the runtime's ledger, restore and report sharing.
 * Blocking work runs on this screen's executors; results are posted to main and dropped once paused.
 */
public final class DozeMonitorActivity extends AppCompatActivity implements MonitorAdapter.Host {
    public static final String EXTRA_BOOT_ID = "com.akylas.enforcedoze.extra.MONITOR_BOOT_ID";
    public static final String EXTRA_SESSION_ID = "com.akylas.enforcedoze.extra.MONITOR_SESSION_ID";
    public static final String EXTRA_START_ELAPSED = "com.akylas.enforcedoze.extra.MONITOR_START_ELAPSED";
    private static final String STATE_SELECTED = "selectedSession";
    private static final String STATE_RAW = "rawExpanded";
    private static final long[] RESTORE_RECHECK_MS = {2_000, 6_000};

    // Self-test state is process-scoped (main thread only) so a result survives rotation or leaving
    // the screen while the test runs on doze-worker.
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static SelfTestKind runningTest;
    private static SelfTestResult lastResult;
    private static Runnable testListener;
    /** Raw output of lastResult, built once per result off main and capped (MonitorFormat.raw). */
    private static String lastRaw;
    private final Runnable ownTestListener = this::onTestFinished;

    private final Handler main = new Handler(Looper.getMainLooper());
    private ExecutorService reads;
    private ExecutorService journal;
    private MonitorAdapter adapter;
    private RecyclerView list;
    private AccessManager access;
    private OnBackPressedCallback backToSessions;

    private boolean resumed;
    private int loadToken;
    private MonitorData.SessionKey selected;
    private List<SessionSummary> sessions;
    private boolean sessionsFailed;
    private MonitorData.Timeline timeline;
    private boolean timelineFailed;

    private MonitorData.Live live;
    private boolean liveLoading;
    private boolean restoring;
    private String debtDetail;
    private boolean sharing;
    private boolean rawExpanded;

    private final AccessManager.Listener accessListener = state -> {
        if (!resumed) return;
        adapter.refreshType(MonitorAdapter.LIVE);
        adapter.refreshType(MonitorAdapter.TESTS);
    };

    /** Sinks run on whichever thread emits (mostly doze-worker, sometimes main): hop to main, only while visible. */
    private final DozeEventSink events = event -> {
        if (event.getType() != EventType.RECOVERY_DEBT) return;
        String detail = event.getDetail();
        main.post(() -> {
            if (!resumed) return;
            debtDetail = detail;
            restoring = false;
            adapter.refreshType(MonitorAdapter.LIVE);
        });
    };

    /** Opens the timeline of one session, e.g. from the screen-on summary notification. */
    public static Intent sessionIntent(Context context, int bootId, long sessionId, long startElapsed) {
        return new Intent(context, DozeMonitorActivity.class)
                .putExtra(EXTRA_BOOT_ID, bootId)
                .putExtra(EXTRA_SESSION_ID, sessionId)
                .putExtra(EXTRA_START_ELAPSED, startElapsed);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (forwardForeignTile(getIntent())) return;
        setContentView(R.layout.activity_doze_monitor);
        setSupportActionBar(findViewById(R.id.toolbar));
        if (getSupportActionBar() != null) getSupportActionBar().setDisplayHomeAsUpEnabled(true);

        access = AccessManager.getInstance(this);
        reads = Executors.newSingleThreadExecutor(task -> new Thread(task, "monitor-reads"));
        journal = Executors.newSingleThreadExecutor(task -> new Thread(task, "monitor-journal"));

        list = findViewById(R.id.monitorList);
        list.setLayoutManager(new LinearLayoutManager(this));
        adapter = new MonitorAdapter(this);
        list.setAdapter(adapter);

        View appBar = findViewById(R.id.appbarlayout);
        int padStart = list.getPaddingStart();
        int padTop = list.getPaddingTop();
        int padEnd = list.getPaddingEnd();
        int padBottom = list.getPaddingBottom();
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.coordinatorLayout), (v, windowInsets) -> {
            Insets bars = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
            appBar.setPadding(bars.left, bars.top, bars.right, 0);
            boolean rtl = ViewCompat.getLayoutDirection(list) == ViewCompat.LAYOUT_DIRECTION_RTL;
            int left = rtl ? padEnd : padStart;
            int right = rtl ? padStart : padEnd;
            list.setPadding(left + bars.left, padTop, right + bars.right, padBottom + bars.bottom);
            return windowInsets;
        });

        backToSessions = new OnBackPressedCallback(false) {
            @Override
            public void handleOnBackPressed() {
                closeSession();
            }
        };
        getOnBackPressedDispatcher().addCallback(this, backToSessions);

        if (savedInstanceState != null) {
            rawExpanded = savedInstanceState.getBoolean(STATE_RAW);
            long[] key = savedInstanceState.getLongArray(STATE_SELECTED);
            if (key != null && key.length == 3) selected = new MonitorData.SessionKey((int) key[0], key[1], key[2]);
        } else {
            selected = keyFrom(getIntent());
        }
        render();
    }

    @Override
    protected void onNewIntent(@NonNull Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (forwardForeignTile(intent)) return;
        MonitorData.SessionKey key = keyFrom(intent);
        if (key != null) {
            selected = key;
            timeline = null;
            timelineFailed = false;
            render();
            if (resumed) reload();
        }
    }

    /** Long-pressing another of the app's tiles still opens the main screen, as before. */
    private boolean forwardForeignTile(Intent intent) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N || intent == null
                || !TileService.ACTION_QS_TILE_PREFERENCES.equals(intent.getAction())) {
            return false;
        }
        // SystemUI names the long-pressed tile from Android 8; older versions open the monitor.
        ComponentName tile;
        try {
            tile = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                    ? IntentCompat.getParcelableExtra(intent, Intent.EXTRA_COMPONENT_NAME, ComponentName.class) : null;
        } catch (RuntimeException unreadable) {
            tile = null;
        }
        if (tile == null || ForceDozeTileService.class.getName().equals(tile.getClassName())) return false;
        startActivity(new Intent(this, MainActivity.class));
        finish();
        return true;
    }

    /** The activity is exported: a foreign app's unknown Parcelable (pre-33 unparcelling) means no selection. */
    private static MonitorData.SessionKey keyFrom(Intent intent) {
        try {
            if (intent == null || !intent.hasExtra(EXTRA_SESSION_ID)) return null;
            return new MonitorData.SessionKey(intent.getIntExtra(EXTRA_BOOT_ID, -1),
                    intent.getLongExtra(EXTRA_SESSION_ID, 0), intent.getLongExtra(EXTRA_START_ELAPSED, 0));
        } catch (RuntimeException unreadable) { // BadParcelableException and friends
            return null;
        }
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putBoolean(STATE_RAW, rawExpanded);
        if (selected != null) {
            outState.putLongArray(STATE_SELECTED, new long[]{selected.bootId, selected.sessionId, selected.startElapsed});
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        NoticeSink.setDebtShownInApp(true); // The live card shows recovery debt in place of the notice.
        access.addListener(accessListener);
        ForceDozeService.addSink(this, events);
        testListener = ownTestListener;
        refreshLive();
        reload();
    }

    @Override
    protected void onPause() {
        super.onPause();
        resumed = false;
        NoticeSink.setDebtShownInApp(false);
        access.removeListener(accessListener);
        ForceDozeService.removeSink(this, events);
        // Another resumed instance (split screen) may own it by now.
        if (testListener == ownTestListener) testListener = null;
        main.removeCallbacksAndMessages(null);
        liveLoading = false;
        sharing = false;
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (reads != null) reads.shutdownNow();
        if (journal != null) journal.shutdownNow();
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            getOnBackPressedDispatcher().onBackPressed();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    // --- Rendering ---

    private void render() {
        List<MonitorAdapter.Row> rows = new ArrayList<>();
        if (selected == null) {
            setTitle(R.string.monitor_title);
            rows.add(new MonitorAdapter.Row(MonitorAdapter.LIVE, null));
            rows.add(new MonitorAdapter.Row(MonitorAdapter.TESTS, null));
            rows.add(new MonitorAdapter.Row(MonitorAdapter.SECTION, getString(R.string.monitor_sessions_title)));
            if (sessionsFailed) {
                rows.add(new MonitorAdapter.Row(MonitorAdapter.TEXT, getString(R.string.monitor_journal_failed)));
            } else if (sessions == null) {
                rows.add(new MonitorAdapter.Row(MonitorAdapter.TEXT, getString(R.string.monitor_sessions_loading)));
            } else if (sessions.isEmpty()) {
                rows.add(new MonitorAdapter.Row(MonitorAdapter.TEXT, getString(R.string.monitor_sessions_empty)));
            } else {
                for (SessionSummary summary : sessions) rows.add(new MonitorAdapter.Row(MonitorAdapter.SESSION, summary));
            }
        } else {
            setTitle(R.string.monitor_timeline_title);
            if (timelineFailed) {
                rows.add(new MonitorAdapter.Row(MonitorAdapter.TEXT, getString(R.string.monitor_journal_failed)));
            } else if (timeline == null) {
                rows.add(new MonitorAdapter.Row(MonitorAdapter.TEXT, getString(R.string.monitor_sessions_loading)));
            } else if (timeline.summary == null) {
                rows.add(new MonitorAdapter.Row(MonitorAdapter.TEXT, getString(R.string.monitor_timeline_missing)));
            } else {
                SessionSummary summary = timeline.summary;
                rows.add(new MonitorAdapter.Row(MonitorAdapter.DETAIL, summary));
                if (summary.getProblems().contains(Problem.PARTIAL_SESSION)) {
                    rows.add(new MonitorAdapter.Row(MonitorAdapter.BANNER, getString(R.string.monitor_banner_partial)));
                }
                if (summary.getProblems().contains(Problem.HISTORY_TRUNCATED)) {
                    rows.add(new MonitorAdapter.Row(MonitorAdapter.BANNER, getString(R.string.monitor_banner_truncated)));
                }
                if (timeline.events.isEmpty()) {
                    rows.add(new MonitorAdapter.Row(MonitorAdapter.TEXT, getString(R.string.monitor_timeline_empty)));
                }
                for (JournalEvent event : timeline.events) {
                    rows.add(new MonitorAdapter.Row(MonitorAdapter.EVENT, event, summary.getStartElapsed()));
                }
            }
        }
        backToSessions.setEnabled(selected != null);
        adapter.submit(rows);
    }

    @Override
    public boolean shizukuMode() {
        return AccessUi.isShizukuMode(this);
    }

    @Override
    public MonitorData.Live liveSnapshot() {
        return live;
    }

    private AccessState accessState() {
        return access.getState();
    }

    @Override
    public void bindLive(View card) {
        AccessState state = accessState();
        boolean shizuku = shizukuMode();
        String mode = AccessUi.modeLabel(this, state, shizuku);
        if (!AccessUi.isPrivileged(state)) {
            mode = mode + getString(R.string.monitor_separator) + AccessUi.statusText(this, state, shizuku);
        }
        text(card, R.id.liveAccess, getString(R.string.monitor_live_access, mode));

        MonitorData.Live snapshot = live;
        DozeStateReading idle = snapshot == null ? null : snapshot.idle;
        SensorModeReading sensor = snapshot == null ? null : snapshot.sensor;
        TextView unavailable = card.findViewById(R.id.liveUnavailable);
        Reason reason = snapshot == null ? null : snapshot.readUnavailable;
        unavailable.setVisibility(reason == null ? View.GONE : View.VISIBLE);
        if (reason != null) unavailable.setText(getString(R.string.monitor_live_unavailable, AccessUi.reasonText(this, reason, shizuku)));

        text(card, R.id.liveDeep, getString(R.string.monitor_live_deep, MonitorFormat.deep(this, idle == null ? null : idle.getDeep())));
        text(card, R.id.liveLight, getString(R.string.monitor_live_light, MonitorFormat.light(this, idle == null ? null : idle.getLight())));
        text(card, R.id.liveForced, getString(R.string.monitor_live_forced, MonitorFormat.yesNo(this, idle == null ? null : idle.getForceIdle())));
        text(card, R.id.liveQuick, getString(R.string.monitor_live_quick,
                MonitorFormat.yesNo(this, idle == null ? null : idle.getQuickDozeActivated())));
        String sensorText = MonitorFormat.sensor(this, sensor == null ? null : sensor.getMode());
        if (sensor != null && sensor.getMode() == SensorMode.RESTRICTED && sensor.getAllowToken() != null) {
            sensorText = getString(R.string.monitor_live_sensors_token, sensorText, sensor.getAllowToken());
        }
        text(card, R.id.liveSensors, getString(R.string.monitor_live_sensors, sensorText));
        text(card, R.id.liveWatchdog, getString(R.string.monitor_live_watchdog, getString(
                snapshot == null || snapshot.watchdog ? R.string.monitor_value_on : R.string.monitor_value_off)));
        card.findViewById(R.id.liveWatchdog).setVisibility(snapshot == null ? View.INVISIBLE : View.VISIBLE);

        boolean privileged = AccessUi.isPrivileged(state);
        // A running self-test's own ledger entry is in flight, not debt; its outcome refreshes this card.
        boolean testing = runningTest != null;
        boolean debt = restoring || debtDetail != null || !testing && snapshot != null && snapshot.debt;
        card.findViewById(R.id.liveDebtCard).setVisibility(debt ? View.VISIBLE : View.GONE);
        card.findViewById(R.id.liveDebtNone).setVisibility(debt || testing || snapshot == null ? View.GONE : View.VISIBLE);
        if (debt) {
            String message;
            if (restoring && debtDetail == null) {
                message = getString(R.string.access_restoring);
            } else {
                message = getString("ACCESS_LOST".equals(debtDetail) ? R.string.access_debt_access_lost : R.string.access_debt_generic);
                if (dismissible(snapshot, testing)) {
                    message = message + "\n\n" + DamagedRecords.debtText(this, snapshot.dismissible);
                }
                if (!privileged) message = message + "\n" + getString(R.string.access_debt_needs_access);
            }
            text(card, R.id.liveDebtText, message);
        }
        Button dismiss = card.findViewById(R.id.liveDismiss);
        // A local ledger edit: no access needed, but not while a restore or self-test may still settle it.
        dismiss.setVisibility(debt && !restoring && dismissible(snapshot, testing) ? View.VISIBLE : View.GONE);
        dismiss.setOnClickListener(v -> {
            MonitorData.Live shown = live;
            if (shown == null || shown.dismissible.isEmpty()) return;
            DamagedRecords.confirmDismiss(this, shown.dismissible, () -> {
                if (!resumed) return;
                if ("LEDGER_DAMAGED".equals(debtDetail)) debtDetail = null;
                liveLoading = false;
                refreshLive();
            });
        });

        text(card, R.id.liveUpdated, liveLoading || snapshot == null ? getString(R.string.monitor_live_reading)
                : getString(R.string.monitor_live_updated, MonitorFormat.clock(snapshot.readAt)));

        Button refresh = card.findViewById(R.id.liveRefresh);
        refresh.setEnabled(!liveLoading && !testing);
        refresh.setOnClickListener(v -> refreshLive());
        Button restore = card.findViewById(R.id.liveRestore);
        restore.setEnabled(privileged && !testing && !(restoring && debtDetail == null));
        restore.setOnClickListener(v -> restoreSystem());
        Button share = card.findViewById(R.id.liveShare);
        share.setEnabled(!sharing);
        share.setOnClickListener(v -> shareReport());
    }

    @Override
    public void bindTests(View card) {
        AccessState state = accessState();
        boolean shizuku = shizukuMode();
        boolean service = serviceRunning();
        // Each test gets its own feature's reason: SENSORS runs at APP+DUMP, DOZE needs Shizuku or root.
        bindTestButton(card, R.id.testDoze, R.id.testDozeUnavailable, SelfTestKind.DOZE,
                AccessUi.unavailableText(this, AccessUi.selfTestFeature(SelfTestKind.DOZE), state, shizuku), service);
        bindTestButton(card, R.id.testSensors, R.id.testSensorsUnavailable, SelfTestKind.SENSORS,
                AccessUi.unavailableText(this, AccessUi.selfTestFeature(SelfTestKind.SENSORS), state, shizuku), service);

        boolean running = runningTest != null;
        card.findViewById(R.id.testProgress).setVisibility(running ? View.VISIBLE : View.GONE);
        TextView status = card.findViewById(R.id.testStatus);
        status.setVisibility(running || !service ? View.VISIBLE : View.GONE);
        if (running) status.setText(getString(R.string.monitor_test_running, MonitorFormat.testName(this, runningTest)));
        else if (!service) status.setText(R.string.monitor_test_needs_service);

        SelfTestResult result = lastResult;
        card.findViewById(R.id.testResult).setVisibility(result == null || running ? View.GONE : View.VISIBLE);
        if (result == null || running) return;
        text(card, R.id.testResultTitle, getString(R.string.monitor_test_result_title,
                MonitorFormat.testName(this, result.getKind()), MonitorFormat.outcome(this, result.getOutcome())));
        text(card, R.id.testResultText, MonitorFormat.resultText(this, result, shizuku, state));
        // The capped text is only laid out while expanded.
        text(card, R.id.testRaw, rawExpanded && lastRaw != null ? lastRaw : "");
        card.findViewById(R.id.testRawScroll).setVisibility(rawExpanded ? View.VISIBLE : View.GONE);
        Button toggle = card.findViewById(R.id.testRawToggle);
        toggle.setText(rawExpanded ? R.string.monitor_test_raw_hide : R.string.monitor_test_raw_show);
        toggle.setOnClickListener(v -> {
            rawExpanded = !rawExpanded;
            adapter.refreshType(MonitorAdapter.TESTS);
        });
    }

    private void bindTestButton(View card, int buttonId, int reasonId, SelfTestKind kind, String reason, boolean service) {
        Button button = card.findViewById(buttonId);
        button.setEnabled(reason == null && runningTest == null && service);
        button.setOnClickListener(v -> confirmTest(kind));
        TextView why = card.findViewById(reasonId);
        why.setVisibility(reason == null ? View.GONE : View.VISIBLE);
        if (reason != null) {
            why.setText(getString(R.string.monitor_test_unavailable, MonitorFormat.testName(this, kind), reason));
        }
    }

    private static boolean dismissible(MonitorData.Live snapshot, boolean testing) {
        return !testing && snapshot != null && !snapshot.dismissible.isEmpty();
    }

    private static void text(View root, int id, CharSequence value) {
        ((TextView) root.findViewById(id)).setText(value);
    }

    // --- Live state ---

    private void refreshLive() {
        if (!resumed || liveLoading) return;
        liveLoading = true;
        adapter.refreshType(MonitorAdapter.LIVE);
        Context app = getApplicationContext();
        reads.execute(() -> {
            MonitorData.Live snapshot = MonitorData.readLive(app);
            main.post(() -> {
                if (!resumed) return;
                live = snapshot;
                liveLoading = false;
                restoring = false;
                // Event-raised debt is not ledger-backed; only a restore clears it (as on the access card).
                if (!snapshot.debt && debtDetail == null) NoticeSink.cancelDebt(app);
                else NoticeSink.ledgerChecked(app, snapshot.debt);
                adapter.refreshType(MonitorAdapter.LIVE);
                adapter.refreshType(MonitorAdapter.TESTS);
            });
        });
    }

    /** The access card's restore path: the runtime reconciles the ledger, then SafetyNet checks. */
    private void restoreSystem() {
        debtDetail = null;
        restoring = true;
        adapter.refreshType(MonitorAdapter.LIVE);
        ForceDozeService.requestSafetyCheck(this);
        for (long delay : RESTORE_RECHECK_MS) {
            main.postDelayed(() -> {
                liveLoading = false;
                refreshLive();
            }, delay);
        }
    }

    // --- Sessions and timeline ---

    private void reload() {
        if (selected == null) loadSessions();
        else loadTimeline(selected);
    }

    private void loadSessions() {
        int token = ++loadToken;
        Context app = getApplicationContext();
        journal.execute(() -> {
            List<SessionSummary> result = null;
            try {
                result = MonitorData.loadSessions(app);
            } catch (Exception unreadable) {
                result = null;
            }
            List<SessionSummary> loaded = result;
            main.post(() -> {
                if (!resumed || token != loadToken) return;
                sessions = loaded;
                sessionsFailed = loaded == null;
                if (selected == null) render();
            });
        });
    }

    private void loadTimeline(MonitorData.SessionKey key) {
        int token = ++loadToken;
        Context app = getApplicationContext();
        journal.execute(() -> {
            MonitorData.Timeline result;
            try {
                result = MonitorData.loadTimeline(app, key);
            } catch (Exception unreadable) {
                result = null;
            }
            MonitorData.Timeline loaded = result;
            main.post(() -> {
                if (!resumed || token != loadToken || selected != key) return;
                timeline = loaded;
                timelineFailed = loaded == null;
                render();
            });
        });
    }

    @Override
    public void openSession(SessionSummary summary) {
        selected = MonitorData.SessionKey.of(summary);
        timeline = null;
        timelineFailed = false;
        render();
        list.scrollToPosition(0);
        loadTimeline(selected);
    }

    private void closeSession() {
        selected = null;
        timeline = null;
        timelineFailed = false;
        render();
        loadSessions();
    }

    // --- Self-tests ---

    private void confirmTest(SelfTestKind kind) {
        boolean doze = kind == SelfTestKind.DOZE;
        new MaterialAlertDialogBuilder(this)
                .setTitle(doze ? R.string.monitor_test_doze_confirm_title : R.string.monitor_test_sensors_confirm_title)
                .setMessage(doze ? R.string.monitor_test_doze_confirm_text : R.string.monitor_test_sensors_confirm_text)
                .setPositiveButton(R.string.monitor_test_run, (dialog, which) -> startTest(kind))
                .setNegativeButton(R.string.cancel_button_text, null)
                .show();
    }

    /** The runtime runs it on doze-worker (ledger first, restore in finally, then SafetyNet). */
    private void startTest(SelfTestKind kind) {
        if (runningTest != null) return;
        // The running service (START_STICKY + startup reconcile) is what restores a test cut short.
        // Access can drop while the confirm dialog is open: the rebind shows this test's reason.
        if (!serviceRunning() || !AccessUi.selfTestOffered(kind, accessState(), shizukuMode(), Build.VERSION.SDK_INT)) {
            adapter.refreshType(MonitorAdapter.TESTS);
            return;
        }
        runningTest = kind;
        rawExpanded = false;
        adapter.refreshType(MonitorAdapter.TESTS);
        adapter.refreshType(MonitorAdapter.LIVE); // Restore/Refresh pause while the test runs.
        Context app = getApplicationContext();
        MyApplication.getDozeRuntime(this).requestSelfTest(kind, result -> {
            // On doze-worker: format the raw output once per result, never on main.
            String raw = MonitorFormat.raw(app, result.getCommands());
            MAIN.post(() -> {
                runningTest = null;
                lastResult = result;
                lastRaw = raw;
                Runnable listener = testListener;
                if (listener != null) listener.run();
            });
        });
    }

    private boolean serviceRunning() {
        return Utils.isMyServiceRunning(ForceDozeService.class, this);
    }

    private void onTestFinished() {
        if (!resumed) return;
        adapter.refreshType(MonitorAdapter.TESTS);
        liveLoading = false;
        refreshLive();
    }

    // --- Report ---

    private void shareReport() {
        if (sharing) return;
        sharing = true;
        adapter.refreshType(MonitorAdapter.LIVE);
        Context app = getApplicationContext();
        ReportExporter exporter = new ReportExporter(app);
        journal.execute(() -> {
            Uri uri;
            try {
                List<JournalEvent> events = MyApplication.getDozeRuntime(app).getJournal().queryRecent()
                        .get(5, TimeUnit.SECONDS);
                Map<String, String> device = new LinkedHashMap<>();
                device.put("manufacturer", Build.MANUFACTURER);
                device.put("model", Build.MODEL);
                device.put("android", Build.VERSION.RELEASE);
                device.put("sdk", String.valueOf(Build.VERSION.SDK_INT));
                String report = ReportFormatter.format(SessionAggregator.summarize(events), events,
                        BuildConfig.VERSION_NAME, device);
                uri = exporter.export(report);
            } catch (Exception failed) {
                uri = null;
            }
            Uri shared = uri;
            main.post(() -> {
                sharing = false;
                if (!resumed) return;
                adapter.refreshType(MonitorAdapter.LIVE);
                if (shared == null) {
                    Toast.makeText(this, R.string.monitor_share_failed, Toast.LENGTH_SHORT).show();
                } else {
                    startActivity(Intent.createChooser(exporter.shareIntent(shared), getString(R.string.monitor_share_chooser)));
                }
            });
        });
    }
}
