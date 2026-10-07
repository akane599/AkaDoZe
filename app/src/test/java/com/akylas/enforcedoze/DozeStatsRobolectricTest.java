package com.akylas.enforcedoze;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.graphics.drawable.Drawable;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.core.widget.ImageViewCompat;

import com.akylas.enforcedoze.ui.StatsColorRules;
import com.akylas.enforcedoze.ui.amber.AmberCardView;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/** Amber session cards on the Doze battery stats screen, inflated under the real activity theme. */
@RunWith(RobolectricTestRunner.class)
@Config(application = Application.class)
public class DozeStatsRobolectricTest {

    @Test
    public void sessionCardIsAnAmberCardWithTabularFiguresAndNoLayoutTint() {
        DozeBatteryStatsActivity activity = Robolectric.buildActivity(DozeBatteryStatsActivity.class).setup().get();

        DozeStatsAdapter.ViewHolder holder = new DozeStatsAdapter().onCreateViewHolder(new FrameLayout(activity), 0);

        assertTrue(holder.itemView.findViewById(R.id.cardView) instanceof AmberCardView);
        TextView supporting = holder.itemView.findViewById(R.id.supportingText);
        assertEquals("tnum", supporting.getFontFeatureSettings());
        ImageView image = holder.itemView.findViewById(R.id.image);
        // The icon carries its own drain/good tint; a layout tint would paint over it.
        assertNull(ImageViewCompat.getImageTintList(image));
    }

    @Test
    public void batteryIconsResolveTheirTintsUnderTheActivityTheme() {
        DozeBatteryStatsActivity activity = Robolectric.buildActivity(DozeBatteryStatsActivity.class).setup().get();

        // MaterialColors.getColor(context, attr, tag) throws when colorError / colorSecondary don't resolve.
        Drawable drain = activity.returnDrawableBattery(StatsColorRules.DRAIN_THRESHOLD);
        Drawable good = activity.returnDrawableBattery(StatsColorRules.DRAIN_THRESHOLD - 1);

        assertNotNull(drain);
        assertNotNull(good);
    }
}
