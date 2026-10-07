package com.akylas.enforcedoze.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.app.Dialog;
import android.os.AsyncTask;
import android.widget.TextView;

import androidx.fragment.app.FragmentActivity;

import com.afollestad.materialdialogs.MaterialDialog;
import com.akylas.enforcedoze.R;

import org.junit.Test;
import org.junit.experimental.runners.Enclosed;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.annotation.LooperMode;
import org.robolectric.annotation.Resetter;
import org.robolectric.shadows.ShadowDialog;

import java.util.ArrayDeque;
import java.util.Queue;

/** Characterizes helper-grant completion before and after splitting the Android glue. */
@RunWith(Enclosed.class)
public class AccessUiGrantHelpersTest {
    /** No Android runtime: these exercise the exact decisions used by the glue. */
    public static class Decisions {
        @Test
        public void grantRunsOnceAndReturnsTheSameResult() {
            Object result = new Object();
            int[] calls = {0};
            assertSame(result, AccessUi.GrantHelperDecision.attempt(() -> {
                calls[0]++;
                return result;
            }));
            assertEquals(1, calls[0]);
        }

        @Test
        public void grantExceptionsAndNullResultsBothUseTheFailurePath() {
            assertSame(null, AccessUi.GrantHelperDecision.attempt(() -> {
                throw new IllegalStateException("grant failed");
            }));
            assertSame(null, AccessUi.GrantHelperDecision.attempt(() -> null));
        }

        @Test(expected = AssertionError.class)
        public void errorsAreNotSwallowedAsGrantFailures() {
            AccessUi.GrantHelperDecision.attempt(() -> { throw new AssertionError("not an Exception"); });
        }

        @Test
        public void absentWeakReferentsAreSkippedAndPresentOnesArePassedThrough() {
            int[] calls = {0};
            Object value = new Object();
            AccessUi.GrantHelperDecision.whenPresent(null, ignored -> calls[0]++);
            assertEquals(0, calls[0]);
            AccessUi.GrantHelperDecision.whenPresent(value, actual -> {
                assertSame(value, actual);
                calls[0]++;
            });
            assertEquals(1, calls[0]);
        }

        @Test
        public void resultIsShownOnlyForAnOwnerThatIsNeitherFinishingNorDestroyed() {
            for (boolean finishing : new boolean[] {false, true}) {
                for (boolean destroyed : new boolean[] {false, true}) {
                    int[] calls = {0};
                    AccessUi.GrantHelperDecision.showResult(() -> finishing, () -> destroyed, () -> calls[0]++);
                    assertEquals("finishing=" + finishing + " destroyed=" + destroyed,
                            !finishing && !destroyed ? 1 : 0, calls[0]);
                }
            }
        }

        @Test
        public void finishingShortCircuitsTheDestroyedRead() {
            AccessUi.GrantHelperDecision.showResult(() -> true,
                    () -> { throw new AssertionError("destroyed must not be read"); },
                    () -> { throw new AssertionError("result must not be shown"); });
        }
    }

    @RunWith(RobolectricTestRunner.class)
    @Config(application = Application.class, sdk = 28, shadows = AndroidGlue.GrantExecutor.class)
    @LooperMode(LooperMode.Mode.LEGACY)
    public static class AndroidGlue {
        @Implements(AsyncTask.class)
        public static class GrantExecutor {
            private static final Queue<Runnable> workers = new ArrayDeque<>();

            @Implementation
            protected static void execute(Runnable worker) {
                workers.add(worker);
            }

            @Resetter
            public static void reset() {
                workers.clear();
            }
        }
        private ActivityController<FragmentActivity> owner() {
            ActivityController<FragmentActivity> controller = Robolectric.buildActivity(FragmentActivity.class);
            controller.get().setTheme(R.style.AppTheme);
            return controller.setup();
        }

        private void completeWorker() {
            assertEquals("exactly one grant operation is queued", 1, GrantExecutor.workers.size());
            GrantExecutor.workers.remove().run();
            Robolectric.flushForegroundThreadScheduler();
        }

        @Test
        public void grantFailureDismissesProgressAndShowsTheSameFailureWording() {
            ActivityController<FragmentActivity> controller = owner();
            FragmentActivity activity = controller.get();
            // A throwing grant operation follows the existing catch-and-report path.
            AccessUi.grantHelpers(activity, null);
            MaterialDialog progress = (MaterialDialog) ShadowDialog.getLatestDialog();
            assertTrue(progress.isShowing());
            assertEquals(activity.getString(R.string.please_wait_text), progress.getTitleView().getText().toString());
            assertEquals(activity.getString(R.string.granting_helpers_text), progress.getContentView().getText().toString());
            completeWorker();
            Dialog result = ShadowDialog.getLatestDialog();
            assertFalse(progress.isShowing());
            assertNotSame(progress, result);
            assertTrue(result.isShowing());
            TextView message = result.findViewById(android.R.id.message);
            assertEquals(activity.getString(R.string.grant_helpers_failed), message.getText().toString());
            result.dismiss();
            controller.pause().stop().destroy();
        }

        @Test
        public void finishingOwnerDismissesProgressWithoutShowingAResult() {
            ActivityController<FragmentActivity> controller = owner();
            AccessUi.grantHelpers(controller.get(), null);
            Dialog progress = ShadowDialog.getLatestDialog();
            controller.get().finish();
            completeWorker();
            assertFalse(progress.isShowing());
            assertSame(progress, ShadowDialog.getLatestDialog());
            controller.pause().stop().destroy();
        }

        @Test
        public void destroyingOwnerDismissesProgressImmediatelyAndSuppressesTheResult() {
            ActivityController<FragmentActivity> controller = owner();
            AccessUi.grantHelpers(controller.get(), null);
            Dialog progress = ShadowDialog.getLatestDialog();
            controller.pause().stop().destroy();
            assertFalse("lifecycle destruction must dismiss before the worker completes", progress.isShowing());
            completeWorker();
            assertSame(progress, ShadowDialog.getLatestDialog());
        }
    }
}
