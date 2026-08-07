package com.termux.app.workspaces;

public class Workspace {

    public final String id;
    public final String name;
    public final String path;
    public final String command;

    public Workspace(String id, String name, String path, String command) {
        this.id = id;
        this.name = name;
        this.path = path;
        this.command = command;
    }
}