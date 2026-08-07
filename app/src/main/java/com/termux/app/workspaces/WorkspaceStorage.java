package com.termux.app.workspaces;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class WorkspaceStorage {

    private static final String PREFS_NAME = "project_workspaces";
    private static final String KEY_WORKSPACES = "workspaces_list";

    private final SharedPreferences mPrefs;

    public WorkspaceStorage(Context context) {
        mPrefs = context.getApplicationContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    public List<Workspace> getWorkspaces() {
        List<Workspace> result = new ArrayList<>();
        String json = mPrefs.getString(KEY_WORKSPACES, null);
        if (json == null) return result;

        try {
            JSONArray array = new JSONArray(json);
            for (int i = 0; i < array.length(); i++) {
                JSONObject obj = array.getJSONObject(i);
                result.add(new Workspace(
                    obj.optString("id", UUID.randomUUID().toString()),
                    obj.getString("name"),
                    obj.getString("path"),
                    obj.optString("command", "")
                ));
            }
        } catch (JSONException e) {
            // Return what was parsed
        }
        return result;
    }

    public void addWorkspace(String name, String path, String command) {
        List<Workspace> current = getWorkspaces();
        current.add(new Workspace(UUID.randomUUID().toString(), name, path, command));
        save(current);
    }

    public void deleteWorkspace(String id) {
        List<Workspace> current = getWorkspaces();
        current.removeIf(w -> w.id.equals(id));
        save(current);
    }

    private void save(List<Workspace> workspaces) {
        try {
            JSONArray array = new JSONArray();
            for (Workspace w : workspaces) {
                JSONObject obj = new JSONObject();
                obj.put("id", w.id);
                obj.put("name", w.name);
                obj.put("path", w.path);
                obj.put("command", w.command);
                array.put(obj);
            }
            mPrefs.edit().putString(KEY_WORKSPACES, array.toString()).apply();
        } catch (JSONException ignored) {
        }
    }
}