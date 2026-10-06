package com.akylas.enforcedoze.ui;

import android.app.Application;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.content.SharedPreferences;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowNotificationManager;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class)
// Keep this notice-only seam independent of MyApplication's alarm/runtime setup.
@Config(application = Application.class)
public class NoticeSinkRobolectricTest {
    private static final int ID_DEBT = 8802;
    private static final int ID_OTHER = 8801;

    @After
    public void resetNoticeState() throws Exception {
        Field instance = NoticeSink.class.getDeclaredField("instance");
        instance.setAccessible(true);
        instance.set(null, null);
        Field debtViews = NoticeSink.class.getDeclaredField("debtViews");
        debtViews.setAccessible(true);
        ((AtomicInteger) debtViews.get(null)).set(0);
        RuntimeEnvironment.getApplication().getSharedPreferences("notices", Context.MODE_PRIVATE)
                .edit().clear().commit();
    }

    @Test
    public void cancelDebtCancelsOnlyDebtAndClearsStoredGateState() {
        Context context = RuntimeEnvironment.getApplication();
        SharedPreferences notices = context.getSharedPreferences("notices", Context.MODE_PRIVATE);
        notices.edit()
                .putStringSet("debtNotified", new HashSet<>(Arrays.asList("ACCESS_LOST", "WIFI|wifi")))
                .putBoolean("debtPosted", true)
                .commit();
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        manager.createNotificationChannel(new NotificationChannel("test_debt", "Debt", NotificationManager.IMPORTANCE_DEFAULT));
        Notification notification = new Notification.Builder(context, "test_debt")
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle("Debt")
                .build();
        manager.notify(ID_DEBT, notification);
        manager.notify(ID_OTHER, notification);
        ShadowNotificationManager shadow = shadowOf(manager);
        assertNotNull("Debt notification is present before cancellation", shadow.getNotification(ID_DEBT));
        assertFalse("Stored debt keys are present before cancellation",
                notices.getStringSet("debtNotified", Collections.emptySet()).isEmpty());
        assertTrue("Posted flag is set before cancellation", notices.getBoolean("debtPosted", false));

        NoticeSink.cancelDebt(context);

        assertNull("Debt notification is cancelled", shadow.getNotification(ID_DEBT));
        assertNotNull("Unrelated notification is retained", shadow.getNotification(ID_OTHER));
        assertTrue("Stored debt keys are cleared", notices.getStringSet("debtNotified", Collections.emptySet()).isEmpty());
        assertFalse("Posted flag is cleared", notices.getBoolean("debtPosted", true));
    }
}
