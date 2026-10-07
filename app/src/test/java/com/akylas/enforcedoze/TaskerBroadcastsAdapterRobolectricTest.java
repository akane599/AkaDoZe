package com.akylas.enforcedoze;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.view.ContextThemeWrapper;
import android.view.View;
import android.widget.ListView;
import android.widget.TextView;

import com.akylas.enforcedoze.ui.amber.AmberCardView;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.util.ArrayList;

/** SQ-23: each Tasker broadcast is an Amber card whose tap still reaches the list's copy-to-clipboard click. */
@RunWith(RobolectricTestRunner.class)
@Config(application = Application.class)
public class TaskerBroadcastsAdapterRobolectricTest {
    private ListView list;
    private TaskerBroadcastsAdapter adapter;
    private final int[] clicked = {-1};
    private final View[] clickedView = {null};

    @Before
    public void setUp() {
        Context context = new ContextThemeWrapper(RuntimeEnvironment.getApplication(), R.style.AppTheme);
        ArrayList<TaskerBroadcastsItem> items = new ArrayList<>();
        items.add(new TaskerBroadcastsItem("com.akylas.enforcedoze.ENABLE_FORCEDOZE", "none"));
        items.add(new TaskerBroadcastsItem("com.akylas.enforcedoze.ADD_WHITELIST", "packageName"));
        adapter = new TaskerBroadcastsAdapter(context, items);
        list = new ListView(context);
        list.setAdapter(adapter);
        list.setOnItemClickListener((parent, view, position, id) -> {
            clicked[0] = position;
            clickedView[0] = view;
        });
    }

    @Test
    public void rowIsAnAmberCardBoundToItsBroadcast() {
        View row = adapter.getView(1, null, list);

        assertTrue(row instanceof AmberCardView);
        assertEquals("com.akylas.enforcedoze.ADD_WHITELIST",
                ((TextView) row.findViewById(R.id.broadcastName)).getText().toString());
        assertEquals("packageName", ((TextView) row.findViewById(R.id.broadcastValues)).getText().toString());
    }

    @Test
    public void cardTapForwardsToTheListItemClick() {
        View row = adapter.getView(1, null, list);

        assertTrue(row.performClick());

        assertEquals(1, clicked[0]);
        assertSame(row, clickedView[0]);
    }

    @Test
    public void identifierIsLaidOutWithBreaksAfterDotsAndUnderscores() {
        View row = adapter.getView(1, null, list);
        row.measure(View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        row.layout(0, 0, 400, row.getMeasuredHeight());

        TextView name = row.findViewById(R.id.broadcastName);
        assertEquals("com.​akylas.​enforcedoze.​ADD_​WHITELIST",
                name.getLayout().getText().toString());
    }

    @Test
    public void copiedIdentifierIsTheOriginalWithoutZeroWidthSpaces() {
        Context context = list.getContext();
        ClipboardManager clipboard = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
        // Same read as TaskerBroadcastsActivity's item click: the tapped row's broadcastName text.
        list.setOnItemClickListener((parent, view, position, id) -> clipboard.setPrimaryClip(
                ClipData.newPlainText("fd_broadcast", ((TextView) view.findViewById(R.id.broadcastName)).getText())));
        View row = adapter.getView(0, null, list);

        row.performClick();

        String copied = clipboard.getPrimaryClip().getItemAt(0).getText().toString();
        assertEquals("com.akylas.enforcedoze.ENABLE_FORCEDOZE", copied);
        assertFalse(copied.contains("​"));
    }

    @Test
    public void recycledRowRebindsAndForwardsTheNewPosition() {
        View row = adapter.getView(1, null, list);
        View recycled = adapter.getView(0, row, list);

        assertSame(row, recycled);
        assertEquals("com.akylas.enforcedoze.ENABLE_FORCEDOZE",
                ((TextView) recycled.findViewById(R.id.broadcastName)).getText().toString());
        recycled.performClick();
        assertEquals(0, clicked[0]);
    }
}
