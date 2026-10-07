package com.akylas.enforcedoze;

import android.app.Application;
import android.content.Context;
import android.content.pm.PackageManager;
import android.view.ContextThemeWrapper;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.akylas.enforcedoze.ui.amber.SquircleCornerTreatment;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.util.ArrayList;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/** SQ-22: the app-list row shows the package's icon, falling back to the system default for missing packages. */
@RunWith(RobolectricTestRunner.class)
@Config(application = Application.class)
public class AppsAdapterIconTest {
    private static final String MISSING = "com.example.not.installed";

    @Test
    public void installedPackageGetsItsIcon() {
        Context context = RuntimeEnvironment.getApplication();
        assertNotNull(AppsAdapter.appIcon(context.getPackageManager(), context.getPackageName()));
    }

    @Test
    public void missingPackageFallsBackToTheDefaultIcon() {
        PackageManager pm = RuntimeEnvironment.getApplication().getPackageManager();
        try {
            pm.getApplicationIcon(MISSING);
            fail("precondition: the package must not be installed");
        } catch (PackageManager.NameNotFoundException expected) {
            // The fallback branch is the one under test.
        }
        assertNotNull(AppsAdapter.appIcon(pm, MISSING));
    }

    @Test
    public void rowInflatesUnderAppThemeWithSquircleIconAndBindsText() {
        Context context = new ContextThemeWrapper(RuntimeEnvironment.getApplication(), R.style.AppTheme);
        RecyclerView parent = new RecyclerView(context);
        parent.setLayoutManager(new LinearLayoutManager(context));
        AppsItem item = new AppsItem();
        item.setAppName("System package");
        item.setAppPackageName(MISSING);
        ArrayList<AppsItem> data = new ArrayList<>();
        data.add(item);
        AppsAdapter adapter = new AppsAdapter(context, data);

        AppsAdapter.ViewHolder holder = adapter.onCreateViewHolder(parent, 0);
        adapter.onBindViewHolder(holder, 0);

        assertEquals("System package", holder.appName.getText().toString());
        assertEquals(MISSING, holder.appPackageName.getText().toString());
        assertNotNull("every row shows an icon", holder.appIcon.getDrawable());
        assertTrue("icon clip is the Amber squircle",
                holder.appIcon.getShapeAppearanceModel().getTopLeftCorner() instanceof SquircleCornerTreatment);
        assertEquals(context.getResources().getDimension(R.dimen.corner_inner),
                holder.appIcon.getShapeAppearanceModel().getTopLeftCornerSize().getCornerSize(new android.graphics.RectF(0, 0, 100, 100)),
                0.01f);
    }
}
