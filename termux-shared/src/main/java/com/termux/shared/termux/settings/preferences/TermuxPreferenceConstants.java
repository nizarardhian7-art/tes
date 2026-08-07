package com.termux.shared.termux.settings.preferences;

import com.termux.shared.shell.command.ExecutionCommand;

public final class TermuxPreferenceConstants {

    public static final class TERMUX_APP {

        public static final String KEY_TERMINAL_MARGIN_ADJUSTMENT =  "terminal_margin_adjustment";
        public static final boolean DEFAULT_TERMINAL_MARGIN_ADJUSTMENT = true;

        public static final String KEY_SHOW_TERMINAL_TOOLBAR = "show_extra_keys";
        public static final boolean DEFAULT_VALUE_SHOW_TERMINAL_TOOLBAR = true;

        public static final String KEY_SOFT_KEYBOARD_ENABLED = "soft_keyboard_enabled";
        public static final boolean DEFAULT_VALUE_KEY_SOFT_KEYBOARD_ENABLED = true;

        public static final String KEY_SOFT_KEYBOARD_ENABLED_ONLY_IF_NO_HARDWARE = "soft_keyboard_enabled_only_if_no_hardware";
        public static final boolean DEFAULT_VALUE_KEY_SOFT_KEYBOARD_ENABLED_ONLY_IF_NO_HARDWARE = false;

        public static final String KEY_AUTO_START_TMUX = "auto_start_tmux";
        public static final boolean DEFAULT_VALUE_KEY_AUTO_START_TMUX = false;

        public static final String KEY_KEEP_SCREEN_ON = "screen_always_on";
        public static final boolean DEFAULT_VALUE_KEEP_SCREEN_ON = false;

        public static final String KEY_HAS_AUTO_REQUESTED_STORAGE = "termuxmod_has_auto_requested_storage";
        public static final boolean DEFAULT_VALUE_KEY_HAS_AUTO_REQUESTED_STORAGE = false;

        public static final String KEY_FONTSIZE = "fontsize";
        public static final String KEY_CURRENT_SESSION = "current_session";
        public static final String KEY_LOG_LEVEL = "log_level";

        public static final String KEY_LAST_NOTIFICATION_ID = "last_notification_id";
        public static final int DEFAULT_VALUE_KEY_LAST_NOTIFICATION_ID = 0;

        public static final String KEY_APP_SHELL_NUMBER_SINCE_BOOT = "app_shell_number_since_boot";
        public static final int DEFAULT_VALUE_APP_SHELL_NUMBER_SINCE_BOOT = 0;

        public static final String KEY_TERMINAL_SESSION_NUMBER_SINCE_BOOT = "terminal_session_number_since_boot";
        public static final int DEFAULT_VALUE_TERMINAL_SESSION_NUMBER_SINCE_BOOT = 0;

        public static final String KEY_TERMINAL_VIEW_KEY_LOGGING_ENABLED = "terminal_view_key_logging_enabled";
        public static final boolean DEFAULT_VALUE_TERMINAL_VIEW_KEY_LOGGING_ENABLED = false;

        public static final String KEY_PLUGIN_ERROR_NOTIFICATIONS_ENABLED = "plugin_error_notifications_enabled";
        public static final boolean DEFAULT_VALUE_PLUGIN_ERROR_NOTIFICATIONS_ENABLED = true;

        public static final String KEY_CRASH_REPORT_NOTIFICATIONS_ENABLED = "crash_report_notifications_enabled";
        public static final boolean DEFAULT_VALUE_CRASH_REPORT_NOTIFICATIONS_ENABLED = true;

    }

    public static final class TERMUX_API_APP {
        public static final String KEY_LOG_LEVEL = "log_level";
        public static final String KEY_LAST_PENDING_INTENT_REQUEST_CODE = "last_pending_intent_request_code";
        public static final int DEFAULT_VALUE_KEY_LAST_PENDING_INTENT_REQUEST_CODE = 0;
    }

    public static final class TERMUX_BOOT_APP {
        public static final String KEY_LOG_LEVEL = "log_level";
    }

    public static final class TERMUX_FLOAT_APP {
        public static final String KEY_WINDOW_X = "window_x";
        public static final String KEY_WINDOW_Y = "window_y";
        public static final String KEY_WINDOW_WIDTH = "window_width";
        public static final String KEY_WINDOW_HEIGHT = "window_height";
        public static final String KEY_FONTSIZE = "fontsize";
        public static final String KEY_LOG_LEVEL = "log_level";
        public static final String KEY_TERMINAL_VIEW_KEY_LOGGING_ENABLED = "terminal_view_key_logging_enabled";
        public static final boolean DEFAULT_VALUE_TERMINAL_VIEW_KEY_LOGGING_ENABLED = false;
    }

    public static final class TERMUX_STYLING_APP {
        public static final String KEY_LOG_LEVEL = "log_level";
    }

    public static final class TERMUX_TASKER_APP {
        public static final String KEY_LOG_LEVEL = "log_level";
        public static final String KEY_LAST_PENDING_INTENT_REQUEST_CODE = "last_pending_intent_request_code";
        public static final int DEFAULT_VALUE_KEY_LAST_PENDING_INTENT_REQUEST_CODE = 0;
    }

    public static final class TERMUX_WIDGET_APP {
        public static final String KEY_LOG_LEVEL = "log_level";
        public static final String KEY_TOKEN = "token";
    }

}