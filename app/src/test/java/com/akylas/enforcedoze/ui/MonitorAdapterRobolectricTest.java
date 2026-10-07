package com.akylas.enforcedoze.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.content.Context;
import android.provider.Settings;
import android.view.ContextThemeWrapper;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.core.widget.ImageViewCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.akylas.enforcedoze.R;
import com.akylas.enforcedoze.doze.DeepState;
import com.akylas.enforcedoze.doze.EventType;
import com.akylas.enforcedoze.doze.SensorMode;
import com.akylas.enforcedoze.monitor.Coverage;
import com.akylas.enforcedoze.monitor.JournalEvent;
import com.akylas.enforcedoze.monitor.Problem;
import com.akylas.enforcedoze.monitor.SensorVerification;
import com.akylas.enforcedoze.monitor.SessionSummary;
import com.akylas.enforcedoze.monitor.Source;
import com.akylas.enforcedoze.ui.amber.SquircleCornerTreatment;
import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;
import com.google.android.material.color.MaterialColors;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/** SQ-20: the monitor list's Amber treatment, bound through the real adapter against the real AppTheme. */
@RunWith(RobolectricTestRunner.class)
@Config(application = Application.class)
public class MonitorAdapterRobolectricTest {
    private static final class FakeHost implements MonitorAdapter.Host {
        MonitorData.Live live;
        int snapshots;
        SessionSummary opened;

        @Override public void bindLive(View card) { }
        @Override public void bindTests(View card) { }
        @Override public void openSession(SessionSummary summary) { opened = summary; }
        @Override public boolean shizukuMode() { return false; }

        @Nullable
        @Override
        public MonitorData.Live liveSnapshot() {
            snapshots++;
            return live;
        }
    }

    private Context context;
    private RecyclerView parent;
    private FakeHost host;
    private MonitorAdapter adapter;
    private MonitorAdapter.Holder holder;

    @Before
    public void setUp() {
        context = new ContextThemeWrapper(RuntimeEnvironment.getApplication(), R.style.AppTheme);
        parent = new RecyclerView(context);
        parent.setLayoutManager(new LinearLayoutManager(context));
        host = new FakeHost();
        adapter = new MonitorAdapter(host);
        setAnimatorScale(1f);
    }

    private static void setAnimatorScale(float scale) {
        Settings.Global.putFloat(RuntimeEnvironment.getApplication().getContentResolver(),
                Settings.Global.ANIMATOR_DURATION_SCALE, scale);
    }

    private View bind(MonitorAdapter.Row row) {
        List<MonitorAdapter.Row> rows = new ArrayList<>();
        rows.add(row);
        adapter.submit(rows);
        holder = adapter.onCreateViewHolder(parent, row.type);
        adapter.bindViewHolder(holder, 0);
        return holder.itemView;
    }

    /** The recycled-bind path: the same holder bound again, as refreshType's notifyItemChanged does. */
    private void rebind(View view) {
        assertSame(holder.itemView, view);
        adapter.bindViewHolder(holder, 0);
    }

    private static SessionSummary summary(Long firstDeep, Map<String, Integer> exits, List<Problem> problems) {
        Map<Coverage, Long> ms = new EnumMap<>(Coverage.class);
        Map<Coverage, Double> percent = new EnumMap<>(Coverage.class);
        for (Coverage coverage : Coverage.values()) {
            ms.put(coverage, coverage == Coverage.DEEP_IDLE ? 3_600_000L : 0L);
            percent.put(coverage, coverage == Coverage.DEEP_IDLE ? 100.0 : 0.0);
        }
        return new SessionSummary(1, 7L, 0L, 3_600_000L, 1_700_000_000_000L, 3_600_000L, firstDeep, ms, percent,
                2, exits, 0, SensorVerification.YES, null, null, 0L, problems);
    }

    private static JournalEvent event(long elapsed, EventType type, DeepState deep, String detail) {
        return new JournalEvent(1, elapsed, 1_700_000_000_000L, 7L, Source.APP, type, deep, null, null, null, null, detail);
    }

    // --- Live card ---

    @Test
    public void liveEntranceSpringsOnlyWhenTheShownStateChanges() {
        host.live = MonitorMotionRulesTest.live(MonitorMotionRulesTest.idle(DeepState.IDLE, true), SensorMode.RESTRICTED);
        View card = bind(new MonitorAdapter.Row(MonitorAdapter.LIVE, null));
        ImageView icon = card.findViewById(R.id.liveIcon);
        assertEquals("first reading springs in", MonitorMotionRules.ENTRANCE_SCALE, icon.getScaleX(), 0f);
        assertEquals(MonitorMotionRules.ENTRANCE_SCALE, icon.getScaleY(), 0f);

        icon.setScaleX(1f);
        icon.setScaleY(1f);
        host.live = MonitorMotionRulesTest.live(MonitorMotionRulesTest.idle(DeepState.IDLE, true), SensorMode.RESTRICTED);
        rebind(card);
        assertEquals("a rebind of the same state does not replay", 1f, icon.getScaleX(), 0f);

        host.live = MonitorMotionRulesTest.live(MonitorMotionRulesTest.idle(DeepState.ACTIVE, false), SensorMode.NORMAL);
        rebind(card);
        assertEquals("a real change springs again", MonitorMotionRules.ENTRANCE_SCALE, icon.getScaleX(), 0f);
    }

    @Test
    public void liveCardGlowsOnlyWhileDozeIsForced() {
        float glow = context.getResources().getDimension(R.dimen.glow_elevation);
        host.live = MonitorMotionRulesTest.live(MonitorMotionRulesTest.idle(DeepState.IDLE, true), null);
        View card = bind(new MonitorAdapter.Row(MonitorAdapter.LIVE, null));
        assertEquals(glow, card.getElevation(), 0f);

        host.live = MonitorMotionRulesTest.live(MonitorMotionRulesTest.idle(DeepState.IDLE, false), null);
        rebind(card);
        assertEquals(0f, card.getElevation(), 0f);
    }

    @Test
    public void reducedMotionShowsTheIconAtRest() {
        setAnimatorScale(0f);
        host.live = MonitorMotionRulesTest.live(MonitorMotionRulesTest.idle(DeepState.IDLE, true), null);
        View card = bind(new MonitorAdapter.Row(MonitorAdapter.LIVE, null));
        assertEquals(1f, ((ImageView) card.findViewById(R.id.liveIcon)).getScaleX(), 0f);
    }

    @Test
    public void otherRowsNeverReadTheLiveSnapshot() {
        View text = bind(new MonitorAdapter.Row(MonitorAdapter.TEXT, "Loading"));
        assertEquals("Loading", ((TextView) text).getText().toString());
        assertEquals(0, host.snapshots);
    }

    // --- Sessions ---

    @Test
    public void sessionRowBuildsSquircleProblemChipsAndOpensOnClick() {
        SessionSummary summary = summary(240_000L, Collections.emptyMap(),
                Collections.singletonList(Problem.NEVER_REACHED_DEEP));
        View card = bind(new MonitorAdapter.Row(MonitorAdapter.SESSION, summary));

        ChipGroup chips = card.findViewById(R.id.sessionProblems);
        assertEquals(View.VISIBLE, chips.getVisibility());
        assertEquals(1, chips.getChildCount());
        Chip chip = (Chip) chips.getChildAt(0);
        String label = MonitorFormat.problem(context, Problem.NEVER_REACHED_DEEP);
        assertEquals(label, chip.getText().toString());
        assertTrue("Amber.treat squircles the code-built chip",
                chip.getShapeAppearanceModel().getTopLeftCorner() instanceof SquircleCornerTreatment);

        assertEquals(View.GONE, card.findViewById(R.id.sessionExtra).getVisibility());
        String spoken = card.getContentDescription().toString();
        assertTrue(spoken.contains(label));
        assertTrue(spoken.endsWith(context.getString(R.string.monitor_session_open)));
        assertTrue(card.performClick());
        assertSame(summary, host.opened);
    }

    @Test
    public void detailRowShowsFirstDeepAndExitsWithoutChipsOrClick() {
        Map<String, Integer> exits = Collections.singletonMap("motion", 2);
        SessionSummary summary = summary(240_000L, exits, Collections.emptyList());
        View card = bind(new MonitorAdapter.Row(MonitorAdapter.DETAIL, summary));

        TextView extra = card.findViewById(R.id.sessionExtra);
        assertEquals(View.VISIBLE, extra.getVisibility());
        String expected = context.getString(R.string.monitor_detail_first_deep, MonitorFormat.duration(context, 240_000L))
                + "\n" + MonitorFormat.exits(context, summary);
        assertEquals(expected, extra.getText().toString());
        assertEquals(View.GONE, card.findViewById(R.id.sessionProblems).getVisibility());
        assertFalse(card.isClickable());
        assertFalse(card.getContentDescription().toString().contains(context.getString(R.string.monitor_session_open)));
        assertNull(host.opened);
    }

    @Test
    public void detailRowWithoutDeepIdleOrExitsSaysSo() {
        View card = bind(new MonitorAdapter.Row(MonitorAdapter.DETAIL,
                summary(null, Collections.emptyMap(), Collections.emptyList())));
        assertEquals(context.getString(R.string.monitor_detail_never_deep),
                ((TextView) card.findViewById(R.id.sessionExtra)).getText().toString());
    }

    // --- Timeline events ---

    private int iconTint(View row) {
        return ImageViewCompat.getImageTintList((ImageView) row.findViewById(R.id.eventIcon)).getDefaultColor();
    }

    @Test
    public void verifiedEventsAreSageWithTheirOffset() {
        View row = bind(new MonitorAdapter.Row(MonitorAdapter.EVENT,
                event(240_000L, EventType.VERIFY, DeepState.IDLE, "FORCE_DOZE"), 0L));
        assertEquals(MaterialColors.getColor(row, com.google.android.material.R.attr.colorSecondary), iconTint(row));
        String clock = MonitorFormat.clock(1_700_000_000_000L);
        assertEquals(context.getString(R.string.monitor_event_time, clock, MonitorFormat.duration(context, 240_000L)),
                ((TextView) row.findViewById(R.id.eventTime)).getText().toString());
        assertEquals(View.VISIBLE, row.findViewById(R.id.eventDetail).getVisibility());
    }

    @Test
    public void problemEventsAreErrorsAndOthersNeutral() {
        View problem = bind(new MonitorAdapter.Row(MonitorAdapter.EVENT,
                event(0L, EventType.ERROR, null, "boom"), 0L));
        assertEquals(MaterialColors.getColor(problem, androidx.appcompat.R.attr.colorError), iconTint(problem));

        View neutral = bind(new MonitorAdapter.Row(MonitorAdapter.EVENT,
                event(0L, EventType.SCREEN_OFF, null, null), 5_000L));
        assertEquals(MaterialColors.getColor(neutral, com.google.android.material.R.attr.colorOnSurfaceVariant),
                iconTint(neutral));
        assertEquals("before the session start only the clock shows", MonitorFormat.clock(1_700_000_000_000L),
                ((TextView) neutral.findViewById(R.id.eventTime)).getText().toString());
        assertEquals(View.GONE, neutral.findViewById(R.id.eventDetail).getVisibility());
    }
}
