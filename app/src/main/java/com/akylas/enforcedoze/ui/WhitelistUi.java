package com.akylas.enforcedoze.ui;

import android.content.Context;

import com.akylas.enforcedoze.R;
import com.akylas.enforcedoze.access.Reason;
import com.akylas.enforcedoze.access.WhitelistParseReason;

/** Whitelist screen outcomes as user text; the decisions are pure so they can be JVM-tested. */
public final class WhitelistUi {
    private WhitelistUi() { throw new AssertionError(); }

    public enum Problem { READ_FAILED, READ_TIMED_OUT, PARTIAL, NOT_APPLIED, ACCESS, INVALID_PACKAGE, NOT_INSTALLED, ERROR }

    /** Rows a read can show: a complete or empty read, or the parsable rows of a partial one. */
    public static boolean showsList(WhitelistParseReason parseReason) {
        return parseReason == null || parseReason == WhitelistParseReason.EMPTY
                || parseReason == WhitelistParseReason.PARTIALLY_PARSED;
    }

    /** Why a whitelist read isn't the whole list, or null when it is (an empty list is a real answer). */
    public static Problem readProblem(WhitelistParseReason parseReason) {
        if (parseReason == null) return null;
        switch (parseReason) {
            case COMMAND_FAILED: return Problem.READ_FAILED;
            case TIMED_OUT: return Problem.READ_TIMED_OUT;
            case PARTIALLY_PARSED: return Problem.PARTIAL;
            case EMPTY:
            default: return null;
        }
    }

    /**
     * Why an edit isn't confirmed, or null when its readback confirmed it. Access comes first, then a
     * readback that couldn't be read, then a readback that shows the change didn't take.
     */
    public static Problem editProblem(boolean verified, Reason reason, WhitelistParseReason readback) {
        if (verified) return null;
        if (reason != null && reason != Reason.UNVERIFIED) return Problem.ACCESS;
        Problem read = readProblem(readback);
        return read != null ? read : Problem.NOT_APPLIED;
    }

    /**
     * Whether a background read or edit may still touch the screen when it finishes. A finishing or destroyed
     * Activity has no window left for a dialog, so its result is dropped; a recreated screen loads fresh.
     */
    public static boolean mayTouchUi(boolean finishing, boolean destroyed) {
        return !finishing && !destroyed;
    }

    public static int title(boolean edit) {
        return edit ? R.string.whitelist_edit_problem_title : R.string.whitelist_read_problem_title;
    }

    /** For an edit, a readback problem means the change was sent but couldn't be confirmed. */
    public static String text(Context context, Problem problem, int unparsedLines, Reason reason, boolean edit,
                              boolean remove) {
        boolean readback = problem == Problem.READ_FAILED || problem == Problem.READ_TIMED_OUT || problem == Problem.PARTIAL;
        if (edit && readback) {
            return context.getString(R.string.whitelist_edit_unconfirmed,
                    text(context, problem, unparsedLines, reason, false, remove));
        }
        switch (problem) {
            case READ_FAILED: return context.getString(R.string.whitelist_read_failed);
            case READ_TIMED_OUT: return context.getString(R.string.whitelist_read_timed_out);
            case PARTIAL:
                return context.getResources().getQuantityString(R.plurals.whitelist_read_partial, unparsedLines, unparsedLines);
            case NOT_APPLIED:
                return context.getString(remove ? R.string.whitelist_remove_not_applied : R.string.whitelist_add_not_applied);
            case ACCESS:
                return AccessUi.reasonText(context, reason == null ? Reason.NO_ACCESS : reason, AccessUi.isShizukuMode(context));
            case INVALID_PACKAGE: return context.getString(R.string.whitelist_invalid_package);
            case NOT_INSTALLED: return context.getString(R.string.whitelist_not_installed);
            case ERROR:
            default: return context.getString(remove ? R.string.whitelist_remove_error : R.string.whitelist_add_error);
        }
    }
}
