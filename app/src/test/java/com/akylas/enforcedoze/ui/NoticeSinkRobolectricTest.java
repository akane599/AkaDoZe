package com.akylas.enforcedoze.ui;

import android.app.Application;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.content.SharedPreferences;

import com.akylas.enforcedoze.MyApplication;
import com.akylas.enforcedoze.doze.DozeEvent;
import com.akylas.enforcedoze.doze.EventType;
import com.akylas.enforcedoze.monitor.EventCodes;

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

import static org.junit.Assert.assertEquals;
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
        for (String name : Arrays.asList("context", "dozeRuntime")) {
            Field field = MyApplication.class.getDeclaredField(name);
            field.setAccessible(true);
            field.set(null, null);
        }
        Field debtViews = NoticeSink.class.getDeclaredField("debtViews");
        debtViews.setAccessible(true);
        ((AtomicInteger) debtViews.get(null)).set(0);
        RuntimeEnvironment.getApplication().getSharedPreferences("notices", Context.MODE_PRIVATE)
                .edit().clear().commit();
    }

    @Test
    public void postedDefaultsTrueAndSetPostedWritesOnlyWhenChanged() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        SharedPreferences notices = context.getSharedPreferences("notices", Context.MODE_PRIVATE);
        DebtRules.NoticeGate.Store store = debtStore(context);
        assertFalse("No posted flag exists initially", notices.contains("debtPosted"));
        assertTrue("Pre-flag notices default to posted", store.posted());

        store.setPosted(true);
        assertFalse("An unchanged default must not write a flag", notices.contains("debtPosted"));
        store.setPosted(false);
        assertTrue("A changed value writes the flag", notices.contains("debtPosted"));
        assertFalse("False is stored", notices.getBoolean("debtPosted", true));
        store.setPosted(false);
        assertFalse("An unchanged false remains false", store.posted());
        store.setPosted(true);
        assertTrue("A changed true is stored", notices.getBoolean("debtPosted", false));
    }

    @Test
    public void settledLedgerCancelsPreFlagDebtThroughStoreAndClearsPostedFlag() {
        Context context = RuntimeEnvironment.getApplication();
        SharedPreferences notices = context.getSharedPreferences("notices", Context.MODE_PRIVATE);
        notices.edit().putStringSet("debtNotified", Collections.singleton("WIFI|wifi")).commit();
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        manager.createNotificationChannel(new NotificationChannel("test_debt", "Debt", NotificationManager.IMPORTANCE_DEFAULT));
        Notification notification = new Notification.Builder(context, "test_debt")
                .setSmallIcon(android.R.drawable.ic_dialog_info).setContentTitle("Debt").build();
        manager.notify(ID_DEBT, notification);
        manager.notify(ID_OTHER, notification);
        ShadowNotificationManager shadow = shadowOf(manager);
        assertNotNull("Pre-flag debt is present", shadow.getNotification(ID_DEBT));
        assertFalse("Legacy notice has no posted flag", notices.contains("debtPosted"));

        NoticeSink.restoresChecked(context, false);

        assertNull("Store cancels ID_DEBT when ledger debt settles", shadow.getNotification(ID_DEBT));
        assertNotNull("Store cancellation leaves other IDs alone", shadow.getNotification(ID_OTHER));
        assertTrue("Settled keys are removed", notices.getStringSet("debtNotified", Collections.emptySet()).isEmpty());
        assertFalse("Cancellation writes posted=false", notices.getBoolean("debtPosted", true));
    }

    @Test
    public void emittedStarvationPostsRestoreActionAndCleanLedgerSettlesIt() {
        Context context = RuntimeEnvironment.getApplication();
        shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS);
        NoticeSink sink = NoticeSink.get(context);
        SharedPreferences notices = context.getSharedPreferences("notices", Context.MODE_PRIVATE);
        ShadowNotificationManager shadow = shadowOf(context.getSystemService(NotificationManager.class));

        sink.emit(new DozeEvent(EventType.RECOVERY_DEBT, EventCodes.RESTORE_WINDOW_STARVED));
        Notification notice = shadow.getNotification(ID_DEBT);
        assertNotNull("Journal starvation reaches the real notice poster", notice);
        assertEquals("Starvation offers the existing Restore now action", 1, notice.actions.length);
        assertNotNull("Restore action is wired", notice.actions[0].actionIntent);
        assertTrue("The real emit mapping records the starvation key",
                notices.getStringSet("debtNotified", Collections.emptySet()).contains(EventCodes.RESTORE_WINDOW_STARVED));

        NoticeSink.restoresChecked(context, true);
        assertNotNull("Unsettled ledger keeps the notice", shadow.getNotification(ID_DEBT));
        NoticeSink.restoresChecked(context, false);
        assertNull("A clean runtime ledger check cancels starvation", shadow.getNotification(ID_DEBT));
        assertFalse(notices.getBoolean("debtPosted", true));
        sink.emit(new DozeEvent(EventType.RECOVERY_DEBT, EventCodes.RESTORE_WINDOW_STARVED));
        assertNotNull("A later starvation is announced again", shadow.getNotification(ID_DEBT));
    }

    private DebtRules.NoticeGate.Store debtStore(Context context) throws Exception {
        Field gate = NoticeSink.class.getDeclaredField("debtGate");
        gate.setAccessible(true);
        Field store = DebtRules.NoticeGate.class.getDeclaredField("store");
        store.setAccessible(true);
        return (DebtRules.NoticeGate.Store) store.get(gate.get(NoticeSink.get(context)));
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
