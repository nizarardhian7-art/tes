package com.termux.app.quickcommands;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

public class QuickCommandStorage {

    private static final String PREFS_NAME = "quick_commands";
    private static final String KEY_CUSTOM_COMMANDS = "custom_commands";
    private static final String CATEGORY_CUSTOM = "My Commands";

    private final SharedPreferences mPrefs;

    public QuickCommandStorage(Context context) {
        mPrefs = context.getApplicationContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    public List<QuickCommand> getCustomCommands() {
        List<QuickCommand> result = new ArrayList<>();
        String json = mPrefs.getString(KEY_CUSTOM_COMMANDS, null);
        if (json == null) return result;

        try {
            JSONArray array = new JSONArray(json);
            for (int i = 0; i < array.length(); i++) {
                JSONObject obj = array.getJSONObject(i);
                result.add(new QuickCommand(CATEGORY_CUSTOM, obj.getString("label"), obj.getString("command"), true));
            }
        } catch (JSONException e) {
            // Corrupt data - return empty
        }
        return result;
    }

    public void addCustomCommand(String label, String command) {
        List<QuickCommand> current = getCustomCommands();
        current.add(new QuickCommand(CATEGORY_CUSTOM, label, command, true));
        save(current);
    }

    public void deleteCustomCommand(QuickCommand toDelete) {
        List<QuickCommand> current = getCustomCommands();
        current.removeIf(c -> c.label.equals(toDelete.label) && c.command.equals(toDelete.command));
        save(current);
    }

    public String exportToJson() {
        return mPrefs.getString(KEY_CUSTOM_COMMANDS, "[]");
    }

    public boolean importFromJson(String json) {
        try {
            JSONArray array = new JSONArray(json);
            mPrefs.edit().putString(KEY_CUSTOM_COMMANDS, array.toString()).apply();
            return true;
        } catch (JSONException e) {
            return false;
        }
    }

    private void save(List<QuickCommand> commands) {
        try {
            JSONArray array = new JSONArray();
            for (QuickCommand c : commands) {
                JSONObject obj = new JSONObject();
                obj.put("label", c.label);
                obj.put("command", c.command);
                array.put(obj);
            }
            mPrefs.edit().putString(KEY_CUSTOM_COMMANDS, array.toString()).apply();
        } catch (JSONException e) {
            // Ignore
        }
    }

}