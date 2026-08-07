package com.termux.app.quickcommands;

/** TermuxMod: a single entry in the Quick Commands panel — either one of the built-in
 * default commands, or one the user added themselves (custom == true, deletable). */
public class QuickCommand {

    public final String category;
    public final String label;
    public final String command;
    public final boolean custom;

    public QuickCommand(String category, String label, String command, boolean custom) {
        this.category = category;
        this.label = label;
        this.command = command;
        this.custom = custom;
    }
}
