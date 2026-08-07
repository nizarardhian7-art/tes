package com.termux.app.extrakeys;

public class ExtraKeysItem {

    public final String label;
    public final String key;
    public final boolean isMacro;
    public final boolean isLocked;

    public ExtraKeysItem(String label, String key, boolean isMacro) {
        this(label, key, isMacro, false);
    }

    public ExtraKeysItem(String label, String key, boolean isMacro, boolean isLocked) {
        this.label = label;
        this.key = key;
        this.isMacro = isMacro;
        this.isLocked = isLocked;
    }
}