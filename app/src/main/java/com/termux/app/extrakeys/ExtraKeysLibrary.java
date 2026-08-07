package com.termux.app.extrakeys;

import java.util.ArrayList;
import java.util.List;

public class ExtraKeysLibrary {

    public static List<ExtraKeysItem> getAllAvailableKeys() {
        List<ExtraKeysItem> list = new ArrayList<>();

        // Actions & Utilities
        list.add(new ExtraKeysItem("📁 Files", "FILES", false));
        list.add(new ExtraKeysItem("⚡ Commands", "COMMANDS", false));
        list.add(new ExtraKeysItem("⚙ Settings", "SETTINGS", false));
        list.add(new ExtraKeysItem("≡ Drawer", "DRAWER", false));
        list.add(new ExtraKeysItem("⌨ Keyboard", "KEYBOARD", false));
        list.add(new ExtraKeysItem("⎘ Paste", "PASTE", false));
        list.add(new ExtraKeysItem("✎ Type Line", "TYPE", false));

        // Modifiers
        list.add(new ExtraKeysItem("CTRL", "CTRL", false));
        list.add(new ExtraKeysItem("ALT", "ALT", false));
        list.add(new ExtraKeysItem("SHIFT", "SHIFT", false));
        list.add(new ExtraKeysItem("FN", "FN", false));

        // Navigation
        list.add(new ExtraKeysItem("←", "LEFT", false));
        list.add(new ExtraKeysItem("↑", "UP", false));
        list.add(new ExtraKeysItem("↓", "DOWN", false));
        list.add(new ExtraKeysItem("→", "RIGHT", false));
        list.add(new ExtraKeysItem("HOME", "HOME", false));
        list.add(new ExtraKeysItem("END", "END", false));
        list.add(new ExtraKeysItem("PGUP", "PGUP", false));
        list.add(new ExtraKeysItem("PGDN", "PGDN", false));

        // Editing
        list.add(new ExtraKeysItem("ESC", "ESC", false));
        list.add(new ExtraKeysItem("TAB", "TAB", false));
        list.add(new ExtraKeysItem("ENTER", "ENTER", false));
        list.add(new ExtraKeysItem("BKSP", "BKSP", false));
        list.add(new ExtraKeysItem("DEL", "DEL", false));
        list.add(new ExtraKeysItem("INS", "INS", false));

        // Function Keys
        for (int i = 1; i <= 12; i++) {
            list.add(new ExtraKeysItem("F" + i, "F" + i, false));
        }

        // Useful Macro Shortcuts
        list.add(new ExtraKeysItem("Stop", "CTRL c", true));
        list.add(new ExtraKeysItem("Clear", "CTRL l", true));
        list.add(new ExtraKeysItem("Exit", "CTRL d", true));

        return list;
    }
}