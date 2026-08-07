package com.termux.shared.termux.settings.properties;

import com.google.common.collect.ImmutableBiMap;
import com.termux.shared.termux.shell.am.TermuxAmSocketServer;
import com.termux.shared.theme.NightMode;
import com.termux.shared.file.FileUtils;
import com.termux.shared.file.filesystem.FileType;
import com.termux.shared.settings.properties.SharedProperties;
import com.termux.shared.termux.TermuxConstants;
import com.termux.shared.logger.Logger;
import com.termux.terminal.TerminalEmulator;
import com.termux.view.TerminalView;

import java.io.File;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class TermuxPropertyConstants {

    private static final String LOG_TAG = "TermuxPropertyConstants";

    public static final String KEY_DISABLE_FILE_SHARE_RECEIVER =  "disable-file-share-receiver";
    public static final String KEY_DISABLE_FILE_VIEW_RECEIVER =  "disable-file-view-receiver";
    public static final String KEY_DISABLE_HARDWARE_KEYBOARD_SHORTCUTS =  "disable-hardware-keyboard-shortcuts";
    public static final String KEY_DISABLE_TERMINAL_SESSION_CHANGE_TOAST =  "disable-terminal-session-change-toast";
    public static final String KEY_ENFORCE_CHAR_BASED_INPUT =  "enforce-char-based-input";
    public static final String KEY_EXTRA_KEYS_TEXT_ALL_CAPS =  "extra-keys-text-all-caps";
    public static final String KEY_HIDE_SOFT_KEYBOARD_ON_STARTUP =  "hide-soft-keyboard-on-startup";
    public static final String KEY_RUN_TERMUX_AM_SOCKET_SERVER =  "run-termux-am-socket-server";
    public static final String KEY_TERMINAL_ONCLICK_URL_OPEN =  "terminal-onclick-url-open";

    @Deprecated
    public static final String KEY_USE_BLACK_UI =  "use-black-ui";
    public static final String KEY_USE_CTRL_SPACE_WORKAROUND =  "ctrl-space-workaround";
    public static final String KEY_USE_FULLSCREEN =  "fullscreen";
    public static final String KEY_USE_FULLSCREEN_WORKAROUND =  "use-fullscreen-workaround";

    public static final String KEY_BELL_BEHAVIOUR =  "bell-character";
    public static final String VALUE_BELL_BEHAVIOUR_VIBRATE = "vibrate";
    public static final String VALUE_BELL_BEHAVIOUR_BEEP = "beep";
    public static final String VALUE_BELL_BEHAVIOUR_IGNORE = "ignore";
    public static final String DEFAULT_VALUE_BELL_BEHAVIOUR = VALUE_BELL_BEHAVIOUR_VIBRATE;

    public static final int IVALUE_BELL_BEHAVIOUR_VIBRATE = 1;
    public static final int IVALUE_BELL_BEHAVIOUR_BEEP = 2;
    public static final int IVALUE_BELL_BEHAVIOUR_IGNORE = 3;
    public static final int DEFAULT_IVALUE_BELL_BEHAVIOUR = IVALUE_BELL_BEHAVIOUR_VIBRATE;

    public static final ImmutableBiMap<String, Integer> MAP_BELL_BEHAVIOUR =
        new ImmutableBiMap.Builder<String, Integer>()
            .put(VALUE_BELL_BEHAVIOUR_VIBRATE, IVALUE_BELL_BEHAVIOUR_VIBRATE)
            .put(VALUE_BELL_BEHAVIOUR_BEEP, IVALUE_BELL_BEHAVIOUR_BEEP)
            .put(VALUE_BELL_BEHAVIOUR_IGNORE, IVALUE_BELL_BEHAVIOUR_IGNORE)
            .build();

    public static final String KEY_TERMINAL_CURSOR_BLINK_RATE =  "terminal-cursor-blink-rate";
    public static final int IVALUE_TERMINAL_CURSOR_BLINK_RATE_MIN = TerminalView.TERMINAL_CURSOR_BLINK_RATE_MIN;
    public static final int IVALUE_TERMINAL_CURSOR_BLINK_RATE_MAX = TerminalView.TERMINAL_CURSOR_BLINK_RATE_MAX;
    public static final int DEFAULT_IVALUE_TERMINAL_CURSOR_BLINK_RATE = 0;

    public static final String KEY_TERMINAL_CURSOR_STYLE =  "terminal-cursor-style";
    public static final String VALUE_TERMINAL_CURSOR_STYLE_BLOCK = "block";
    public static final String VALUE_TERMINAL_CURSOR_STYLE_UNDERLINE = "underline";
    public static final String VALUE_TERMINAL_CURSOR_STYLE_BAR = "bar";

    public static final int IVALUE_TERMINAL_CURSOR_STYLE_BLOCK = TerminalEmulator.TERMINAL_CURSOR_STYLE_BLOCK;
    public static final int IVALUE_TERMINAL_CURSOR_STYLE_UNDERLINE = TerminalEmulator.TERMINAL_CURSOR_STYLE_UNDERLINE;
    public static final int IVALUE_TERMINAL_CURSOR_STYLE_BAR = TerminalEmulator.TERMINAL_CURSOR_STYLE_BAR;
    public static final int DEFAULT_IVALUE_TERMINAL_CURSOR_STYLE = TerminalEmulator.DEFAULT_TERMINAL_CURSOR_STYLE;

    public static final ImmutableBiMap<String, Integer> MAP_TERMINAL_CURSOR_STYLE =
        new ImmutableBiMap.Builder<String, Integer>()
            .put(VALUE_TERMINAL_CURSOR_STYLE_BLOCK, IVALUE_TERMINAL_CURSOR_STYLE_BLOCK)
            .put(VALUE_TERMINAL_CURSOR_STYLE_UNDERLINE, IVALUE_TERMINAL_CURSOR_STYLE_UNDERLINE)
            .put(VALUE_TERMINAL_CURSOR_STYLE_BAR, IVALUE_TERMINAL_CURSOR_STYLE_BAR)
            .build();

    public static final String KEY_DELETE_TMPDIR_FILES_OLDER_THAN_X_DAYS_ON_EXIT =  "delete-tmpdir-files-older-than-x-days-on-exit";
    public static final int IVALUE_DELETE_TMPDIR_FILES_OLDER_THAN_X_DAYS_ON_EXIT_MIN = -1;
    public static final int IVALUE_DELETE_TMPDIR_FILES_OLDER_THAN_X_DAYS_ON_EXIT_MAX = 100000;
    public static final int DEFAULT_IVALUE_DELETE_TMPDIR_FILES_OLDER_THAN_X_DAYS_ON_EXIT = 3;

    public static final String KEY_TERMINAL_MARGIN_HORIZONTAL =  "terminal-margin-horizontal";
    public static final int IVALUE_TERMINAL_MARGIN_HORIZONTAL_MIN = 0;
    public static final int IVALUE_TERMINAL_MARGIN_HORIZONTAL_MAX = 100;
    public static final int DEFAULT_IVALUE_TERMINAL_MARGIN_HORIZONTAL = 3;

    public static final String KEY_TERMINAL_MARGIN_VERTICAL =  "terminal-margin-vertical";
    public static final int IVALUE_TERMINAL_MARGIN_VERTICAL_MIN = 0;
    public static final int IVALUE_TERMINAL_MARGIN_VERTICAL_MAX = 100;
    public static final int DEFAULT_IVALUE_TERMINAL_MARGIN_VERTICAL = 0;

    public static final String KEY_TERMINAL_TRANSCRIPT_ROWS =  "terminal-transcript-rows";
    public static final int IVALUE_TERMINAL_TRANSCRIPT_ROWS_MIN = TerminalEmulator.TERMINAL_TRANSCRIPT_ROWS_MIN;
    public static final int IVALUE_TERMINAL_TRANSCRIPT_ROWS_MAX = TerminalEmulator.TERMINAL_TRANSCRIPT_ROWS_MAX;
    public static final int DEFAULT_IVALUE_TERMINAL_TRANSCRIPT_ROWS = TerminalEmulator.DEFAULT_TERMINAL_TRANSCRIPT_ROWS;

    public static final String KEY_TERMINAL_TOOLBAR_HEIGHT_SCALE_FACTOR =  "terminal-toolbar-height";
    public static final float IVALUE_TERMINAL_TOOLBAR_HEIGHT_SCALE_FACTOR_MIN = 0.4f;
    public static final float IVALUE_TERMINAL_TOOLBAR_HEIGHT_SCALE_FACTOR_MAX = 3;
    public static final float DEFAULT_IVALUE_TERMINAL_TOOLBAR_HEIGHT_SCALE_FACTOR = 1.0f;

    public static final String KEY_SHORTCUT_CREATE_SESSION =  "shortcut.create-session";
    public static final String KEY_SHORTCUT_NEXT_SESSION =  "shortcut.next-session";
    public static final String KEY_SHORTCUT_PREVIOUS_SESSION =  "shortcut.previous-session";
    public static final String KEY_SHORTCUT_RENAME_SESSION =  "shortcut.rename-session";

    public static final int ACTION_SHORTCUT_CREATE_SESSION = 1;
    public static final int ACTION_SHORTCUT_NEXT_SESSION = 2;
    public static final int ACTION_SHORTCUT_PREVIOUS_SESSION = 3;
    public static final int ACTION_SHORTCUT_RENAME_SESSION = 4;

    public static final ImmutableBiMap<String, Integer> MAP_SESSION_SHORTCUTS =
        new ImmutableBiMap.Builder<String, Integer>()
            .put(KEY_SHORTCUT_CREATE_SESSION, ACTION_SHORTCUT_CREATE_SESSION)
            .put(KEY_SHORTCUT_NEXT_SESSION, ACTION_SHORTCUT_NEXT_SESSION)
            .put(KEY_SHORTCUT_PREVIOUS_SESSION, ACTION_SHORTCUT_PREVIOUS_SESSION)
            .put(KEY_SHORTCUT_RENAME_SESSION, ACTION_SHORTCUT_RENAME_SESSION)
            .build();

    public static final String KEY_BACK_KEY_BEHAVIOUR =  "back-key";
    public static final String IVALUE_BACK_KEY_BEHAVIOUR_BACK = "back";
    public static final String IVALUE_BACK_KEY_BEHAVIOUR_ESCAPE = "escape";
    public static final String DEFAULT_IVALUE_BACK_KEY_BEHAVIOUR = IVALUE_BACK_KEY_BEHAVIOUR_BACK;

    public static final ImmutableBiMap<String, String> MAP_BACK_KEY_BEHAVIOUR =
        new ImmutableBiMap.Builder<String, String>()
            .put(IVALUE_BACK_KEY_BEHAVIOUR_BACK, IVALUE_BACK_KEY_BEHAVIOUR_BACK)
            .put(IVALUE_BACK_KEY_BEHAVIOUR_ESCAPE, IVALUE_BACK_KEY_BEHAVIOUR_ESCAPE)
            .build();

    public static final String KEY_DEFAULT_WORKING_DIRECTORY =  "default-working-directory";
    public static final String DEFAULT_IVALUE_DEFAULT_WORKING_DIRECTORY = TermuxConstants.TERMUX_HOME_DIR_PATH;

    public static final String KEY_EXTRA_KEYS =  "extra-keys";
    public static final String DEFAULT_IVALUE_EXTRA_KEYS =
        "[" +
            "['FILES','COMMANDS','SETTINGS','ENTER','CTRL','ALT']," +
            "['TAB','LEFT','UP','DOWN','RIGHT','KEYBOARD','PASTE']" +
        "]";

    public static final String KEY_EXTRA_KEYS_STYLE =  "extra-keys-style";
    public static final String DEFAULT_IVALUE_EXTRA_KEYS_STYLE = "default";

    public static final String KEY_NIGHT_MODE = "night-mode";
    public static final String IVALUE_NIGHT_MODE_TRUE = NightMode.TRUE.getName();
    public static final String IVALUE_NIGHT_MODE_FALSE = NightMode.FALSE.getName();
    public static final String IVALUE_NIGHT_MODE_SYSTEM = NightMode.SYSTEM.getName();
    public static final String DEFAULT_IVALUE_NIGHT_MODE = IVALUE_NIGHT_MODE_SYSTEM;

    public static final ImmutableBiMap<String, String> MAP_NIGHT_MODE =
        new ImmutableBiMap.Builder<String, String>()
            .put(IVALUE_NIGHT_MODE_TRUE, IVALUE_NIGHT_MODE_TRUE)
            .put(IVALUE_NIGHT_MODE_FALSE, IVALUE_NIGHT_MODE_FALSE)
            .put(IVALUE_NIGHT_MODE_SYSTEM, IVALUE_NIGHT_MODE_SYSTEM)
            .build();

    public static final String KEY_SOFT_KEYBOARD_TOGGLE_BEHAVIOUR =  "soft-keyboard-toggle-behaviour";
    public static final String IVALUE_SOFT_KEYBOARD_TOGGLE_BEHAVIOUR_SHOW_HIDE = "show/hide";
    public static final String IVALUE_SOFT_KEYBOARD_TOGGLE_BEHAVIOUR_ENABLE_DISABLE = "enable/disable";
    public static final String DEFAULT_IVALUE_SOFT_KEYBOARD_TOGGLE_BEHAVIOUR = IVALUE_SOFT_KEYBOARD_TOGGLE_BEHAVIOUR_SHOW_HIDE;

    public static final ImmutableBiMap<String, String> MAP_SOFT_KEYBOARD_TOGGLE_BEHAVIOUR =
        new ImmutableBiMap.Builder<String, String>()
            .put(IVALUE_SOFT_KEYBOARD_TOGGLE_BEHAVIOUR_SHOW_HIDE, IVALUE_SOFT_KEYBOARD_TOGGLE_BEHAVIOUR_SHOW_HIDE)
            .put(IVALUE_SOFT_KEYBOARD_TOGGLE_BEHAVIOUR_ENABLE_DISABLE, IVALUE_SOFT_KEYBOARD_TOGGLE_BEHAVIOUR_ENABLE_DISABLE)
            .build();

    public static final String KEY_VOLUME_KEYS_BEHAVIOUR =  "volume-keys";
    public static final String IVALUE_VOLUME_KEY_BEHAVIOUR_VIRTUAL = "virtual";
    public static final String IVALUE_VOLUME_KEY_BEHAVIOUR_VOLUME = "volume";
    public static final String DEFAULT_IVALUE_VOLUME_KEYS_BEHAVIOUR = IVALUE_VOLUME_KEY_BEHAVIOUR_VIRTUAL;

    public static final ImmutableBiMap<String, String> MAP_VOLUME_KEYS_BEHAVIOUR =
        new ImmutableBiMap.Builder<String, String>()
            .put(IVALUE_VOLUME_KEY_BEHAVIOUR_VIRTUAL, IVALUE_VOLUME_KEY_BEHAVIOUR_VIRTUAL)
            .put(IVALUE_VOLUME_KEY_BEHAVIOUR_VOLUME, IVALUE_VOLUME_KEY_BEHAVIOUR_VOLUME)
            .build();

    public static final Set<String> TERMUX_APP_PROPERTIES_LIST = new HashSet<>(Arrays.asList(
        KEY_DISABLE_FILE_SHARE_RECEIVER,
        KEY_DISABLE_FILE_VIEW_RECEIVER,
        KEY_DISABLE_HARDWARE_KEYBOARD_SHORTCUTS,
        KEY_DISABLE_TERMINAL_SESSION_CHANGE_TOAST,
        KEY_ENFORCE_CHAR_BASED_INPUT,
        KEY_EXTRA_KEYS_TEXT_ALL_CAPS,
        KEY_HIDE_SOFT_KEYBOARD_ON_STARTUP,
        KEY_RUN_TERMUX_AM_SOCKET_SERVER,
        KEY_TERMINAL_ONCLICK_URL_OPEN,
        KEY_USE_CTRL_SPACE_WORKAROUND,
        KEY_USE_FULLSCREEN,
        KEY_USE_FULLSCREEN_WORKAROUND,
        TermuxConstants.PROP_ALLOW_EXTERNAL_APPS,
        KEY_BELL_BEHAVIOUR,
        KEY_DELETE_TMPDIR_FILES_OLDER_THAN_X_DAYS_ON_EXIT,
        KEY_TERMINAL_CURSOR_BLINK_RATE,
        KEY_TERMINAL_CURSOR_STYLE,
        KEY_TERMINAL_MARGIN_HORIZONTAL,
        KEY_TERMINAL_MARGIN_VERTICAL,
        KEY_TERMINAL_TRANSCRIPT_ROWS,
        KEY_TERMINAL_TOOLBAR_HEIGHT_SCALE_FACTOR,
        KEY_SHORTCUT_CREATE_SESSION,
        KEY_SHORTCUT_NEXT_SESSION,
        KEY_SHORTCUT_PREVIOUS_SESSION,
        KEY_SHORTCUT_RENAME_SESSION,
        KEY_BACK_KEY_BEHAVIOUR,
        KEY_DEFAULT_WORKING_DIRECTORY,
        KEY_EXTRA_KEYS,
        KEY_EXTRA_KEYS_STYLE,
        KEY_NIGHT_MODE,
        KEY_SOFT_KEYBOARD_TOGGLE_BEHAVIOUR,
        KEY_VOLUME_KEYS_BEHAVIOUR
    ));

    public static final Set<String> TERMUX_DEFAULT_FALSE_BOOLEAN_BEHAVIOUR_PROPERTIES_LIST = new HashSet<>(Arrays.asList(
        KEY_DISABLE_FILE_SHARE_RECEIVER,
        KEY_DISABLE_FILE_VIEW_RECEIVER,
        KEY_DISABLE_HARDWARE_KEYBOARD_SHORTCUTS,
        KEY_DISABLE_TERMINAL_SESSION_CHANGE_TOAST,
        KEY_ENFORCE_CHAR_BASED_INPUT,
        KEY_HIDE_SOFT_KEYBOARD_ON_STARTUP,
        KEY_TERMINAL_ONCLICK_URL_OPEN,
        KEY_USE_CTRL_SPACE_WORKAROUND,
        KEY_USE_FULLSCREEN,
        KEY_USE_FULLSCREEN_WORKAROUND,
        TermuxConstants.PROP_ALLOW_EXTERNAL_APPS
    ));

    public static final Set<String> TERMUX_DEFAULT_TRUE_BOOLEAN_BEHAVIOUR_PROPERTIES_LIST = new HashSet<>(Arrays.asList(
        KEY_EXTRA_KEYS_TEXT_ALL_CAPS,
        KEY_RUN_TERMUX_AM_SOCKET_SERVER
    ));

    public static final Set<String> TERMUX_DEFAULT_INVERETED_FALSE_BOOLEAN_BEHAVIOUR_PROPERTIES_LIST = new HashSet<>(Arrays.asList(
    ));

    public static final Set<String> TERMUX_DEFAULT_INVERETED_TRUE_BOOLEAN_BEHAVIOUR_PROPERTIES_LIST = new HashSet<>(Arrays.asList(
    ));

}