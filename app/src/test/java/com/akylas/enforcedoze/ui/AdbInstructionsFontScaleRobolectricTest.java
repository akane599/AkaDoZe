package com.akylas.enforcedoze.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.Activity;
import android.app.Application;
import android.content.DialogInterface;
import android.os.Looper;
import android.view.View;
import android.view.ViewParent;
import android.widget.Button;
import android.widget.ScrollView;

import androidx.appcompat.app.AlertDialog;

import com.akylas.enforcedoze.R;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.shadows.ShadowDialog;

/**
 * SQ-43: at font scale 2.0 on a phone, the dialog's own message panel took the whole height, squeezed the
 * commands to a sliver and pushed the Okay button out of the window. The message now scrolls with the
 * commands inside the custom view, so AlertDialogLayout keeps the buttons and lets the middle scroll.
 * Robolectric draws no system bars, so a 731dp-tall phone stands in for the 914dp emulator minus its bars.
 */
@RunWith(RobolectricTestRunner.class)
@Config(application = Application.class, qualifiers = "w411dp-h731dp-port-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class AdbInstructionsFontScaleRobolectricTest {

    @After
    public void resetFontScale() {
        RuntimeEnvironment.setFontScale(1f);
    }

    private static ScrollView scrollingAncestor(View view) {
        ViewParent parent = view.getParent();
        while (parent != null && !(parent instanceof ScrollView)) parent = parent.getParent();
        return (ScrollView) parent;
    }

    @Test
    public void okayStaysInTheWindowAndMessageScrollsWithTheCommandsAtFontScale2() {
        RuntimeEnvironment.setFontScale(2f);
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        activity.setTheme(R.style.AppTheme);
        AccessUi.showAdbInstructions(activity);
        shadowOf(Looper.getMainLooper()).idle();
        AlertDialog dialog = (AlertDialog) ShadowDialog.getLatestDialog();
        assertNotNull(dialog);

        Button okay = dialog.getButton(DialogInterface.BUTTON_POSITIVE);
        assertNotNull(okay);
        assertTrue("Okay is shown", okay.isShown());
        assertTrue("Okay has height", okay.getHeight() > 0);
        int[] at = new int[2];
        okay.getLocationInWindow(at);
        View decor = dialog.getWindow().getDecorView();
        assertTrue("Okay ends inside the dialog window", at[1] + okay.getHeight() <= decor.getHeight());

        View message = dialog.findViewById(R.id.adbInstructionsMessage);
        assertNotNull(message);
        ScrollView scroll = scrollingAncestor(message);
        assertNotNull("message scrolls", scroll);
        for (int id : new int[] {R.id.commandTxt1, R.id.commandTxt2, R.id.copyBtn2, R.id.shareBtn2}) {
            assertEquals("same scroll as the message", scroll, scrollingAncestor(dialog.findViewById(id)));
        }
        // Before the fix the commands' panel got 0px here: the message panel took the middle of the dialog.
        View commandRow = (View) dialog.findViewById(R.id.commandTxt1).getParent();
        assertTrue("a whole command row fits the scroll viewport", scroll.getHeight() >= commandRow.getHeight());
        View platformMessage = dialog.findViewById(android.R.id.message);
        assertFalse("no separate message panel", platformMessage != null && platformMessage.isShown());
    }
}
