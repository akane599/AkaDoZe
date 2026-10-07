package com.akylas.enforcedoze.ui;

import android.graphics.Rect;
import android.text.method.TransformationMethod;
import android.view.View;

/**
 * Lets a long broadcast identifier such as {@code com.akylas.enforcedoze.ENABLE_FORCEDOZE} wrap at its segment
 * boundaries instead of mid-word, by putting a zero-width space after every {@code .} and {@code _}.
 *
 * <p>Set as a TextView's transformation method, it changes only the text that is laid out and drawn: the view's
 * {@code getText()} keeps the original identifier, so copying it to the clipboard never carries a U+200B.
 */
public final class BroadcastNameBreaks implements TransformationMethod {
    /** Zero-width space: an invisible line-break opportunity. */
    public static final char BREAK = '​';

    @Override
    public CharSequence getTransformation(CharSequence source, View view) {
        return withBreaks(source);
    }

    @Override
    public void onFocusChanged(View view, CharSequence sourceText, boolean focused, int direction, Rect previouslyFocusedRect) {
    }

    /** {@code name} with {@link #BREAK} after each {@code .} and {@code _}. */
    public static String withBreaks(CharSequence name) {
        StringBuilder out = new StringBuilder(name.length() + 8);
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            out.append(c);
            if (c == '.' || c == '_') {
                out.append(BREAK);
            }
        }
        return out.toString();
    }
}
