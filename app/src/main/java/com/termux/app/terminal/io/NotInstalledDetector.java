package com.termux.app.terminal.io;

import android.app.AlertDialog;
import android.os.Handler;
import android.os.Looper;

import com.termux.R;
import com.termux.app.TermuxActivity;
import com.termux.terminal.TerminalSession;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * TermuxMod: watches terminal output for Termux's built-in "command not found" message
 * (e.g. "The program 'git' is not installed. Install it by executing: pkg install git")
 * and offers a one-tap dialog to run the suggested install command, instead of the user
 * having to read it and type it out by hand.
 *
 * Wired from {@code TermuxTerminalSessionActivityClient#onTextChanged}. Debounced so the
 * (relatively expensive) transcript scan only runs once output has paused briefly, not on
 * every single incoming byte.
 */
public class NotInstalledDetector {

    // Matches both the current "pkg install X" and legacy "apt install X" suggestion forms.
    // The program name may or may not be wrapped in single quotes depending on the
    // Termux package version ("The program 'git' is not installed." vs "The program
    // python3 is not installed.") — both come from Termux's own command-not-found
    // handler script, not something this app controls, so we accept either form.
    private static final Pattern PATTERN = Pattern.compile(
        "The program '?([^'\\s]+)'?\\s+is not installed\\.\\s*Install it by executing:\\s*(\\S+\\s+install\\s+\\S+)");

    // Only look at the tail of the transcript: the message is short, and this keeps the
    // regex scan cheap regardless of how long the scrollback has grown.
    private static final int TAIL_WINDOW_CHARS = 800;
    private static final long DEBOUNCE_MS = 400;

    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private Runnable mPendingCheck;
    private String mLastHandledMatch;

    public void onTextChanged(TerminalSession session, TermuxActivity activity) {
        if (session == null || activity == null) return;

        if (mPendingCheck != null) mHandler.removeCallbacks(mPendingCheck);
        mPendingCheck = () -> check(session, activity);
        mHandler.postDelayed(mPendingCheck, DEBOUNCE_MS);
    }

    private void check(TerminalSession session, TermuxActivity activity) {
        if (!session.isRunning() || activity.isFinishing()) return;

        String full = session.getEmulator().getScreen().getTranscriptTextWithFullLinesJoined();
        if (full == null || full.isEmpty()) return;

        String tail = full.length() > TAIL_WINDOW_CHARS
            ? full.substring(full.length() - TAIL_WINDOW_CHARS) : full;
        // Collapse whitespace so a message that was wrapped across terminal rows (and
        // padded with spaces at the wrap point) matches the same as it would on one line.
        String normalized = tail.replaceAll("\\s+", " ");

        Matcher matcher = PATTERN.matcher(normalized);
        String lastMatch = null;
        while (matcher.find()) lastMatch = matcher.group(); // keep only the most recent hit
        if (lastMatch == null || lastMatch.equals(mLastHandledMatch)) return;
        mLastHandledMatch = lastMatch;

        Matcher fields = PATTERN.matcher(lastMatch);
        if (!fields.find()) return;
        String program = fields.group(1);
        String installCommand = fields.group(2);

        showInstallPrompt(activity, session, program, installCommand);
    }

    private void showInstallPrompt(TermuxActivity activity, TerminalSession session, String program, String installCommand) {
        new AlertDialog.Builder(activity)
            .setTitle(R.string.not_installed_dialog_title)
            .setMessage(activity.getString(R.string.not_installed_dialog_message, program))
            .setPositiveButton(R.string.not_installed_dialog_install, (dialog, which) -> {
                if (session.isRunning()) session.write(installCommand + "\r");
            })
            .setNegativeButton(R.string.file_browser_cancel, null)
            .show();
    }

}
