package com.termux.app.quickcommands;

import android.app.AlertDialog;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.bottomsheet.BottomSheetDialogFragment;
import com.google.android.material.textfield.TextInputEditText;
import com.termux.R;
import com.termux.app.TermuxActivity;
import com.termux.terminal.TerminalSession;

import java.util.ArrayList;
import java.util.List;

public class QuickCommandsBottomSheet extends BottomSheetDialogFragment implements QuickCommandsAdapter.Listener {

    private QuickCommandStorage mStorage;
    private QuickCommandsAdapter mAdapter;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.bottomsheet_quick_commands, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        mStorage = new QuickCommandStorage(requireContext());
        mAdapter = new QuickCommandsAdapter(this);

        RecyclerView recyclerView = view.findViewById(R.id.quick_commands_recycler_view);
        recyclerView.setLayoutManager(new LinearLayoutManager(requireContext()));
        recyclerView.setAdapter(mAdapter);

        view.findViewById(R.id.quick_commands_add_button).setOnClickListener(v -> showAddCommandDialog());
        view.findViewById(R.id.quick_commands_backup_button).setOnClickListener(v -> showBackupDialog());

        refreshList();
    }

    private void refreshList() {
        List<QuickCommand> all = new ArrayList<>(QuickCommandDefaults.get());
        all.addAll(mStorage.getCustomCommands());
        mAdapter.setCommands(all);
    }

    private void showAddCommandDialog() {
        View dialogView = LayoutInflater.from(requireContext())
            .inflate(R.layout.dialog_add_quick_command, null);
        TextInputEditText labelInput = dialogView.findViewById(R.id.quick_command_input_label);
        TextInputEditText commandInput = dialogView.findViewById(R.id.quick_command_input_command);

        new AlertDialog.Builder(requireContext())
            .setTitle(R.string.quick_commands_add_title)
            .setView(dialogView)
            .setPositiveButton(R.string.quick_commands_save, (dialog, which) -> {
                String label = labelInput.getText() == null ? "" : labelInput.getText().toString().trim();
                String command = commandInput.getText() == null ? "" : commandInput.getText().toString().trim();
                if (TextUtils.isEmpty(command)) {
                    Toast.makeText(requireContext(), R.string.quick_commands_command_required, Toast.LENGTH_SHORT).show();
                    return;
                }
                if (TextUtils.isEmpty(label)) label = command;
                mStorage.addCustomCommand(label, command);
                refreshList();
            })
            .setNegativeButton(R.string.file_browser_cancel, null)
            .show();
    }

    private void showBackupDialog() {
        new AlertDialog.Builder(requireContext())
            .setTitle(R.string.quick_commands_backup_title)
            .setItems(new String[]{"Export JSON", "Import JSON"}, (dialog, which) -> {
                if (which == 0) {
                    showExportDialog();
                } else {
                    showImportDialog();
                }
            })
            .setNegativeButton(R.string.file_browser_cancel, null)
            .show();
    }

    private void showExportDialog() {
        String json = mStorage.exportToJson();
        EditText editText = new EditText(requireContext());
        editText.setText(json);
        editText.setSelectAllOnFocus(true);

        new AlertDialog.Builder(requireContext())
            .setTitle(R.string.quick_commands_export_title)
            .setMessage(R.string.quick_commands_export_message)
            .setView(editText)
            .setPositiveButton(R.string.file_browser_ok, null)
            .show();
    }

    private void showImportDialog() {
        EditText editText = new EditText(requireContext());
        editText.setHint("[{\"label\":\"Check IP\",\"command\":\"curl ifconfig.me\"}]");

        new AlertDialog.Builder(requireContext())
            .setTitle(R.string.quick_commands_import_title)
            .setMessage(R.string.quick_commands_import_message)
            .setView(editText)
            .setPositiveButton(R.string.quick_commands_save, (dialog, which) -> {
                String input = editText.getText().toString().trim();
                if (!TextUtils.isEmpty(input)) {
                    boolean success = mStorage.importFromJson(input);
                    if (success) {
                        Toast.makeText(requireContext(), R.string.quick_commands_import_success, Toast.LENGTH_SHORT).show();
                        refreshList();
                    } else {
                        Toast.makeText(requireContext(), R.string.quick_commands_import_error, Toast.LENGTH_LONG).show();
                    }
                }
            })
            .setNegativeButton(R.string.file_browser_cancel, null)
            .show();
    }

    @Override
    public void onCommandClick(QuickCommand command) {
        if (getActivity() instanceof TermuxActivity) {
            TerminalSession session = ((TermuxActivity) getActivity()).getCurrentSession();
            if (session != null && session.isRunning()) {
                session.write(command.command);
            }
        }
        dismiss();
    }

    @Override
    public void onCommandLongClick(QuickCommand command) {
        new AlertDialog.Builder(requireContext())
            .setTitle(R.string.quick_commands_delete_title)
            .setMessage(getString(R.string.quick_commands_delete_message, command.label))
            .setPositiveButton(R.string.quick_commands_delete, (dialog, which) -> {
                mStorage.deleteCustomCommand(command);
                refreshList();
            })
            .setNegativeButton(R.string.file_browser_cancel, null)
            .show();
    }

}