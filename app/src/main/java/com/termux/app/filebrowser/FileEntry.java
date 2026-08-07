package com.termux.app.filebrowser;

import java.io.File;

/** TermuxMod: a single row shown in the file browser (a directory or a file). */
public class FileEntry {

    public final File file;
    public final boolean isDirectory;
    public final boolean isScript;

    /** TermuxMod: name of the interpreter binary (relative to $PREFIX/bin) needed to run
     * this file, chosen by its extension, or null if this isn't a runnable script.
     * ".sh" -> "bash", ".py" -> "python3", ".js" -> "node". */
    public final String scriptInterpreter;

    public FileEntry(File file) {
        this.file = file;
        this.isDirectory = file.isDirectory();
        this.scriptInterpreter = isDirectory ? null : interpreterFor(file.getName().toLowerCase());
        this.isScript = scriptInterpreter != null;
    }

    private static String interpreterFor(String lowerCaseName) {
        if (lowerCaseName.endsWith(".sh")) return "bash";
        if (lowerCaseName.endsWith(".py")) return "python3";
        if (lowerCaseName.endsWith(".js")) return "node";
        return null;
    }

    public String getName() {
        return file.getName();
    }

    public long getSize() {
        return isDirectory ? 0 : file.length();
    }
}
