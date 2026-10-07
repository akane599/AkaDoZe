package com.akylas.enforcedoze;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.app.Application;
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
