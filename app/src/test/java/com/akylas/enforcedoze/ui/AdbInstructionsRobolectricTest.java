package com.akylas.enforcedoze.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.Activity;
import android.app.Application;
import android.app.Dialog;
import android.content.ClipboardManager;
import android.content.Intent;
import android.os.Looper;
import android.widget.TextView;

import com.akylas.enforcedoze.R;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowDialog;

/**
 * SQ-42: the ADB commands wrap at '.' and '_' (display only) while copy and share still hand over the exact
 * command. The views are not selectable, because Android does not map selection offsets across a
 * length-changing transformation.
 */
@RunWith(RobolectricTestRunner.class)
@Config(application = Application.class)
public class AdbInstructionsRobolectricTest {
    private static final String WRITE_SECURE_SETTINGS =
            "adb -d shell pm grant com.akylas.enforcedoze android.permission.WRITE_SECURE_SETTINGS";

    private Dialog show(Activity activity) {
        activity.setTheme(R.style.AppTheme);
        AccessUi.showAdbInstructions(activity);
        shadowOf(Looper.getMainLooper()).idle();
        Dialog dialog = ShadowDialog.getLatestDialog();
        assertNotNull(dialog);
        return dialog;
    }

    @Test
    public void commandsWrapAtSeparatorsButKeepTheirExactText() {
        Dialog dialog = show(Robolectric.buildActivity(Activity.class).setup().get());

        for (int id : new int[] {R.id.commandTxt1, R.id.commandTxt2}) {
            TextView command = dialog.findViewById(id);
            assertTrue(command.getTransformationMethod() instanceof BroadcastNameBreaks);
            assertFalse(command.isTextSelectable());
            assertFalse(command.getText().toString().contains(String.valueOf(BroadcastNameBreaks.BREAK)));
        }
        assertEquals(WRITE_SECURE_SETTINGS, ((TextView) dialog.findViewById(R.id.commandTxt2)).getText().toString());
    }

    @Test
    public void copyAndShareCarryTheExactCommand() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        Dialog dialog = show(activity);

        dialog.findViewById(R.id.copyBtn2).performClick();
        ClipboardManager clipboard = activity.getSystemService(ClipboardManager.class);
        assertEquals(WRITE_SECURE_SETTINGS, clipboard.getPrimaryClip().getItemAt(0).getText().toString());

        dialog.findViewById(R.id.shareBtn2).performClick();
        Intent share = shadowOf(activity).getNextStartedActivity();
        assertEquals(Intent.ACTION_SEND, share.getAction());
        assertEquals(WRITE_SECURE_SETTINGS, share.getStringExtra(Intent.EXTRA_TEXT));
    }
}
