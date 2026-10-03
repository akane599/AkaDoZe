package com.akylas.enforcedoze.ui;

import android.content.Context;
import android.content.res.ColorStateList;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.view.ViewCompat;
import androidx.core.widget.ImageViewCompat;
import androidx.recyclerview.widget.RecyclerView;

import com.akylas.enforcedoze.R;
import com.akylas.enforcedoze.monitor.JournalEvent;
import com.akylas.enforcedoze.monitor.Problem;
import com.akylas.enforcedoze.monitor.SessionSummary;
import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;
import com.google.android.material.color.MaterialColors;

import java.util.ArrayList;
import java.util.List;

/** One list for the whole screen: header cards, sessions, or a session's timeline. */
final class MonitorAdapter extends RecyclerView.Adapter<MonitorAdapter.Holder> {
    static final int LIVE = 0;
    static final int TESTS = 1;
    static final int SECTION = 2;
    static final int TEXT = 3;
    static final int SESSION = 4;
    static final int DETAIL = 5;
    static final int BANNER = 6;
    static final int EVENT = 7;

    interface Host {
        void bindLive(View card);
        void bindTests(View card);
        void openSession(SessionSummary summary);
        boolean shizukuMode();
    }

    static final class Row {
        final int type;
        final Object value;
        final long startElapsed;

        Row(int type, Object value) {
            this(type, value, 0);
        }

        Row(int type, Object value, long startElapsed) {
            this.type = type;
            this.value = value;
            this.startElapsed = startElapsed;
        }
    }

    static final class Holder extends RecyclerView.ViewHolder {
        Holder(View view) {
            super(view);
        }
    }

    private final Host host;
    private List<Row> rows = new ArrayList<>();

    MonitorAdapter(Host host) {
        this.host = host;
    }

    void submit(List<Row> next) {
        rows = next;
        notifyDataSetChanged();
    }

    /** Header cards are re-bound in place without rebuilding the list. */
    void refreshType(int type) {
        for (int i = 0; i < rows.size(); i++) if (rows.get(i).type == type) notifyItemChanged(i);
    }

    @Override
    public int getItemCount() {
        return rows.size();
    }

    @Override
    public int getItemViewType(int position) {
        return rows.get(position).type;
    }

    @NonNull
    @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        LayoutInflater inflater = LayoutInflater.from(parent.getContext());
        View view;
        switch (viewType) {
            case LIVE:
                view = inflater.inflate(R.layout.item_monitor_live, parent, false);
                ViewCompat.setAccessibilityHeading(view.findViewById(R.id.liveTitle), true);
                break;
            case TESTS:
                view = inflater.inflate(R.layout.item_monitor_tests, parent, false);
                ViewCompat.setAccessibilityHeading(view.findViewById(R.id.testsTitle), true);
                break;
            case SECTION:
                view = inflater.inflate(R.layout.item_monitor_section, parent, false);
                ViewCompat.setAccessibilityHeading(view, true);
                break;
            case SESSION:
            case DETAIL:
                view = inflater.inflate(R.layout.item_monitor_session, parent, false);
                break;
            case BANNER:
                view = inflater.inflate(R.layout.item_monitor_banner, parent, false);
                break;
            case EVENT:
                view = inflater.inflate(R.layout.item_monitor_event, parent, false);
                break;
            default:
                view = inflater.inflate(R.layout.item_monitor_text, parent, false);
                break;
        }
        return new Holder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull Holder holder, int position) {
        Row row = rows.get(position);
        View view = holder.itemView;
        switch (row.type) {
            case LIVE: host.bindLive(view); break;
            case TESTS: host.bindTests(view); break;
            case SECTION:
            case TEXT: ((TextView) view).setText((String) row.value); break;
            case BANNER: ((TextView) view.findViewById(R.id.bannerText)).setText((String) row.value); break;
            case SESSION: bindSession(view, (SessionSummary) row.value, true); break;
            case DETAIL: bindSession(view, (SessionSummary) row.value, false); break;
            case EVENT: bindEvent(view, (JournalEvent) row.value, row.startElapsed); break;
            default: break;
        }
    }

    private void bindSession(View view, SessionSummary summary, boolean clickable) {
        Context context = view.getContext();
        String date = MonitorFormat.sessionDate(context, summary.getStartWallTime());
        String duration = MonitorFormat.duration(context, summary.getDurationMs());
        ((TextView) view.findViewById(R.id.sessionDate)).setText(context.getString(R.string.monitor_session_header, date, duration));
        ((TextView) view.findViewById(R.id.sessionCoverage)).setText(
                clickable ? MonitorFormat.coverage(context, summary) : MonitorFormat.coverageDetail(context, summary));
        ((TextView) view.findViewById(R.id.sessionCounts)).setText(MonitorFormat.counts(context, summary));

        TextView extra = view.findViewById(R.id.sessionExtra);
        if (clickable) {
            extra.setVisibility(View.GONE);
        } else {
            Long firstDeep = summary.getTimeToFirstDeepIdleMs();
            String text = firstDeep == null ? context.getString(R.string.monitor_detail_never_deep)
                    : context.getString(R.string.monitor_detail_first_deep, MonitorFormat.duration(context, firstDeep));
            String exits = MonitorFormat.exits(context, summary);
            if (exits != null) text = text + "\n" + exits;
            extra.setText(text);
            extra.setVisibility(View.VISIBLE);
        }

        ChipGroup chips = view.findViewById(R.id.sessionProblems);
        chips.removeAllViews();
        List<String> problems = new ArrayList<>();
        for (Problem problem : summary.getProblems()) {
            String label = MonitorFormat.problem(context, problem);
            problems.add(label);
            Chip chip = new Chip(context);
            chip.setText(label);
            chip.setClickable(false);
            chip.setFocusable(false);
            chip.setEnsureMinTouchTargetSize(false);
            chips.addView(chip);
        }
        chips.setVisibility(problems.isEmpty() ? View.GONE : View.VISIBLE);

        // One spoken sentence per card: readable percentages and durations instead of "7h02 · ✓".
        String spoken = context.getString(R.string.monitor_session_cd, date,
                MonitorFormat.durationSpoken(context, summary.getDurationMs()),
                MonitorFormat.coverageSpoken(context, summary), MonitorFormat.countsSpoken(context, summary));
        if (!problems.isEmpty()) spoken = spoken + " " + android.text.TextUtils.join(", ", problems) + ".";
        if (clickable) spoken = spoken + " " + context.getString(R.string.monitor_session_open);
        view.setContentDescription(spoken);

        if (clickable) {
            view.setOnClickListener(v -> host.openSession(summary));
        } else {
            view.setOnClickListener(null);
            view.setClickable(false);
        }
        view.setFocusable(true);
    }

    private void bindEvent(View view, JournalEvent event, long startElapsed) {
        Context context = view.getContext();
        ImageView icon = view.findViewById(R.id.eventIcon);
        icon.setImageResource(MonitorFormat.eventIcon(event));
        int tint = MaterialColors.getColor(view, MonitorFormat.isProblem(event)
                ? androidx.appcompat.R.attr.colorError : com.google.android.material.R.attr.colorOnSurfaceVariant);
        ImageViewCompat.setImageTintList(icon, ColorStateList.valueOf(tint));

        ((TextView) view.findViewById(R.id.eventLabel)).setText(MonitorFormat.eventLabel(context, event, host.shizukuMode()));
        String clock = MonitorFormat.clock(event.getWallTime());
        long offset = event.getElapsedRealtime() - startElapsed;
        ((TextView) view.findViewById(R.id.eventTime)).setText(offset >= 0
                ? context.getString(R.string.monitor_event_time, clock, MonitorFormat.duration(context, offset)) : clock);
        TextView detail = view.findViewById(R.id.eventDetail);
        String text = MonitorFormat.eventDetail(context, event);
        detail.setText(text);
        detail.setVisibility(text == null ? View.GONE : View.VISIBLE);
    }
}
