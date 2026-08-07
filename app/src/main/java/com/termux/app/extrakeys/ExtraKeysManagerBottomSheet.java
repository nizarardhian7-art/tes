package com.termux.app.extrakeys;

import android.app.AlertDialog;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.bottomsheet.BottomSheetDialogFragment;
import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;
import com.google.android.material.textfield.TextInputEditText;
import com.termux.R;
import com.termux.app.TermuxActivity;
import com.termux.shared.logger.Logger;
import com.termux.shared.termux.TermuxConstants;
import com.termux.shared.termux.settings.properties.TermuxAppSharedProperties;
import com.termux.shared.termux.settings.properties.TermuxPropertyConstants;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

public class ExtraKeysManagerBottomSheet extends BottomSheetDialogFragment implements ActiveKeysAdapter.Listener {

    private static final String LOG_TAG = "ExtraKeysManager";

    private ActiveKeysAdapter mRow1Adapter;
    private ActiveKeysAdapter mRow2Adapter;
    private ChipGroup mAvailableChipGroup;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.bottomsheet_extra_keys_manager, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        mRow1Adapter = new ActiveKeysAdapter(this);
        RecyclerView row1RecyclerView = view.findViewById(R.id.extra_keys_row1_recycler_view);
        row1RecyclerView.setLayoutManager(new GridLayoutManager(requireContext(), 4));
        row1RecyclerView.setAdapter(mRow1Adapter);

        ItemTouchHelper touchHelper1 = new ItemTouchHelper(new ActiveKeysAdapter.TouchHelperCallback(mRow1Adapter));
        touchHelper1.attachToRecyclerView(row1RecyclerView);

        mRow2Adapter = new ActiveKeysAdapter(this);
        RecyclerView row2RecyclerView = view.findViewById(R.id.extra_keys_row2_recycler_view);
        row2RecyclerView.setLayoutManager(new GridLayoutManager(requireContext(), 4));
        row2RecyclerView.setAdapter(mRow2Adapter);

        ItemTouchHelper touchHelper2 = new ItemTouchHelper(new ActiveKeysAdapter.TouchHelperCallback(mRow2Adapter));
        touchHelper2.attachToRecyclerView(row2RecyclerView);

        mAvailableChipGroup = view.findViewById(R.id.extra_keys_available_chip_group);

        view.findViewById(R.id.extra_keys_add_macro_button).setOnClickListener(v -> showAddCustomMacroDialog());
        view.findViewById(R.id.extra_keys_save_button).setOnClickListener(v -> saveAndApplyLayout());
        view.findViewById(R.id.extra_keys_reset_button).setOnClickListener(v -> resetToDefaultLayout());

        loadCurrentLayout();
    }

    private void populateAvailableKeys() {
        if (mAvailableChipGroup == null) return;
        mAvailableChipGroup.removeAllViews();

        List<String> activeKeysSet = new ArrayList<>();
        for (ExtraKeysItem item : mRow1Adapter.getItems()) activeKeysSet.add(item.key);
        for (ExtraKeysItem item : mRow2Adapter.getItems()) activeKeysSet.add(item.key);

        List<ExtraKeysItem> library = ExtraKeysLibrary.getAllAvailableKeys();

        for (ExtraKeysItem item : library) {
            if (activeKeysSet.contains(item.key)) continue;

            Chip chip = new Chip(requireContext());
            chip.setText(item.label);
            chip.setClickable(true);
            chip.setFocusable(true);
            chip.setOnClickListener(v -> {
                if (mRow1Adapter.getItemCount() < 7) {
                    mRow1Adapter.addItem(item);
                } else if (mRow2Adapter.getItemCount() < 7) {
                    mRow2Adapter.addItem(item);
                } else {
                    Toast.makeText(requireContext(), "Max 7 keys per row reached!", Toast.LENGTH_SHORT).show();
                    return;
                }
                populateAvailableKeys();
            });
            mAvailableChipGroup.addView(chip);
        }
    }

    private void loadCurrentLayout() {
        List<ExtraKeysItem> row1List = new ArrayList<>();
        List<ExtraKeysItem> row2List = new ArrayList<>();

        try {
            String extraKeysJson = (String) TermuxAppSharedProperties.getProperties()
                .getInternalPropertyValue(TermuxPropertyConstants.KEY_EXTRA_KEYS, true);

            if (extraKeysJson != null && !extraKeysJson.isEmpty()) {
                JSONArray rows = new JSONArray(extraKeysJson);
                if (rows.length() > 0) {
                    parseRowArray(rows.getJSONArray(0), row1List);
                }
                if (rows.length() > 1) {
                    parseRowArray(rows.getJSONArray(1), row2List);
                }
            }
        } catch (Exception e) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Failed to parse current extra keys JSON", e);
        }

        ensureSettingsKeyPresent(row1List);

        if (row1List.isEmpty() && row2List.isEmpty()) {
            row1List.add(new ExtraKeysItem("⚙ Settings", "SETTINGS", false, true));
            row1List.add(new ExtraKeysItem("📁 Files", "FILES", false));
            row1List.add(new ExtraKeysItem("⚡ Commands", "COMMANDS", false));
            row1List.add(new ExtraKeysItem("ENTER", "ENTER", false));
            row1List.add(new ExtraKeysItem("CTRL", "CTRL", false));

            row2List.add(new ExtraKeysItem("TAB", "TAB", false));
            row2List.add(new ExtraKeysItem("←", "LEFT", false));
            row2List.add(new ExtraKeysItem("↑", "UP", false));
            row2List.add(new ExtraKeysItem("↓", "DOWN", false));
            row2List.add(new ExtraKeysItem("→", "RIGHT", false));
        }

        mRow1Adapter.setItems(row1List);
        mRow2Adapter.setItems(row2List);

        populateAvailableKeys();
    }

    private void ensureSettingsKeyPresent(List<ExtraKeysItem> row1List) {
        boolean hasSettings = false;
        for (ExtraKeysItem item : row1List) {
            if ("SETTINGS".equals(item.key)) {
                hasSettings = true;
                break;
            }
        }
        if (!hasSettings) {
            row1List.add(0, new ExtraKeysItem("⚙ Settings", "SETTINGS", false, true));
        }
    }

    private void parseRowArray(JSONArray rowArray, List<ExtraKeysItem> targetList) throws Exception {
        for (int c = 0; c < rowArray.length(); c++) {
            Object itemObj = rowArray.get(c);
            if (itemObj instanceof JSONObject) {
                JSONObject obj = (JSONObject) itemObj;
                String macro = obj.optString("macro", "");
                String display = obj.optString("display", obj.optString("key", ""));
                boolean isLocked = "SETTINGS".equals(macro) || "SETTINGS".equals(display);
                targetList.add(new ExtraKeysItem(display, macro, true, isLocked));
            } else if (itemObj instanceof String) {
                String key = (String) itemObj;
                String label = getLabelForKey(key);
                boolean isLocked = "SETTINGS".equals(key);
                targetList.add(new ExtraKeysItem(label, key, false, isLocked));
            }
        }
    }

    private String getLabelForKey(String key) {
        for (ExtraKeysItem item : ExtraKeysLibrary.getAllAvailableKeys()) {
            if (item.key.equals(key)) return item.label;
        }
        return key;
    }

    private void showAddCustomMacroDialog() {
        View dialogView = LayoutInflater.from(requireContext())
            .inflate(R.layout.dialog_add_custom_macro, null);
        TextInputEditText labelInput = dialogView.findViewById(R.id.macro_input_label);
        TextInputEditText commandInput = dialogView.findViewById(R.id.macro_input_command);
        CheckBox autoEnterCheckBox = dialogView.findViewById(R.id.macro_checkbox_auto_enter);

        new AlertDialog.Builder(requireContext())
            .setTitle("Add Custom Macro Button")
            .setView(dialogView)
            .setPositiveButton(R.string.quick_commands_save, (dialog, which) -> {
                String label = labelInput.getText() == null ? "" : labelInput.getText().toString().trim();
                String rawCommand = commandInput.getText() == null ? "" : commandInput.getText().toString().trim();
                boolean autoEnter = autoEnterCheckBox.isChecked();

                if (TextUtils.isEmpty(rawCommand)) {
                    Toast.makeText(requireContext(), "Command required", Toast.LENGTH_SHORT).show();
                    return;
                }
                if (TextUtils.isEmpty(label)) label = rawCommand;

                String formattedMacro = convertCommandToTermuxMacro(rawCommand, autoEnter);

                ExtraKeysItem newItem = new ExtraKeysItem(label, formattedMacro, true);
                if (mRow1Adapter.getItemCount() < 7) {
                    mRow1Adapter.addItem(newItem);
                } else {
                    mRow2Adapter.addItem(newItem);
                }
                populateAvailableKeys();
            })
            .setNegativeButton(R.string.file_browser_cancel, null)
            .show();
    }

    public static String convertCommandToTermuxMacro(String rawCommand, boolean autoEnter) {
        if (rawCommand == null) return "";
        String trimmed = rawCommand.trim();
        if (trimmed.isEmpty()) return "";

        if (trimmed.contains(" SPACE ") || trimmed.endsWith(" ENTER")) {
            return trimmed;
        }

        String[] parts = trimmed.split("\\s+");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) sb.append(" SPACE ");
            sb.append(parts[i]);
        }

        if (autoEnter) {
            sb.append(" ENTER");
        }

        return sb.toString();
    }

    private void saveAndApplyLayout() {
        List<ExtraKeysItem> row1Items = mRow1Adapter.getItems();
        List<ExtraKeysItem> row2Items = mRow2Adapter.getItems();

        if (row1Items.isEmpty() && row2Items.isEmpty()) {
            Toast.makeText(requireContext(), "Cannot save empty layout", Toast.LENGTH_SHORT).show();
            return;
        }

        try {
            JSONArray row1Array = buildRowJsonArray(row1Items);
            JSONArray row2Array = buildRowJsonArray(row2Items);

            JSONArray rootArray = new JSONArray();
            if (row1Array.length() > 0) rootArray.put(row1Array);
            if (row2Array.length() > 0) rootArray.put(row2Array);

            String jsonString = rootArray.toString();
            writeExtraKeysToProperties(jsonString);

            TermuxAppSharedProperties.getProperties().loadTermuxPropertiesFromDisk();

            if (getActivity() instanceof TermuxActivity) {
                TermuxActivity activity = (TermuxActivity) getActivity();
                TermuxActivity.updateTermuxActivityStyling(activity, true);
            }

            Toast.makeText(requireContext(), "Layout saved & applied!", Toast.LENGTH_SHORT).show();
            dismiss();

        } catch (Exception e) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Failed to save extra keys layout", e);
            Toast.makeText(requireContext(), "Failed to save layout: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private JSONArray buildRowJsonArray(List<ExtraKeysItem> items) throws Exception {
        JSONArray rowArray = new JSONArray();
        for (ExtraKeysItem item : items) {
            if (item.isMacro) {
                JSONObject macroObj = new JSONObject();
                macroObj.put("macro", item.key);
                macroObj.put("display", item.label);
                rowArray.put(macroObj);
            } else {
                rowArray.put(item.key);
            }
        }
        return rowArray;
    }

    private void writeExtraKeysToProperties(String jsonString) {
        File propFile = TermuxConstants.TERMUX_PROPERTIES_PRIMARY_FILE;
        File parent = propFile.getParentFile();
        if (parent != null && !parent.exists()) parent.mkdirs();

        Properties props = new Properties();
        if (propFile.exists()) {
            try (FileInputStream in = new FileInputStream(propFile)) {
                props.load(in);
            } catch (Exception ignored) {
            }
        }

        props.setProperty("extra-keys", jsonString);

        try (FileOutputStream out = new FileOutputStream(propFile)) {
            props.store(out, "Updated by Extra Keys Manager");
        } catch (Exception e) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Failed to write termux.properties", e);
        }
    }

    private void resetToDefaultLayout() {
        File propFile = TermuxConstants.TERMUX_PROPERTIES_PRIMARY_FILE;
        if (propFile.exists()) {
            Properties props = new Properties();
            try (FileInputStream in = new FileInputStream(propFile)) {
                props.load(in);
            } catch (Exception ignored) {
            }
            props.remove("extra-keys");
            try (FileOutputStream out = new FileOutputStream(propFile)) {
                props.store(out, "Reset to default");
            } catch (Exception ignored) {
            }
        }

        TermuxAppSharedProperties.getProperties().loadTermuxPropertiesFromDisk();

        if (getActivity() instanceof TermuxActivity) {
            TermuxActivity activity = (TermuxActivity) getActivity();
            TermuxActivity.updateTermuxActivityStyling(activity, true);
        }

        Toast.makeText(requireContext(), "Reset to default layout", Toast.LENGTH_SHORT).show();
        dismiss();
    }

    @Override
    public void onItemRemove(ExtraKeysItem item, int position) {
        populateAvailableKeys();
    }
}