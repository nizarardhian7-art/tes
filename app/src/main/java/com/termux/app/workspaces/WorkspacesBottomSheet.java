package com.termux.app.workspaces;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.bottomsheet.BottomSheetDialogFragment;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.textfield.TextInputEditText;
import com.termux.R;
import com.termux.app.TermuxActivity;
import com.termux.app.TermuxService;
import com.termux.app.filebrowser.FileBrowserActivity;
import com.termux.shared.termux.TermuxConstants;
import com.termux.shared.termux.TermuxConstants.TERMUX_APP.TERMUX_SERVICE;

import java.io.File;
import java.util.List;

public class WorkspacesBottomSheet extends BottomSheetDialogFragment implements WorkspacesAdapter.Listener {

    private WorkspaceStorage mStorage;
    private WorkspacesAdapter mAdapter;
    private TextView mEmptyText;

    private TextInputEditText mCurrentPathInput;
    private ActivityResultLauncher<Intent> mFolderPickerLauncher;

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mFolderPickerLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() == Activity.RESULT_OK && result.getData() != null) {
                    String pickedPath = result.getData().getStringExtra(FileBrowserActivity.RESULT_EXTRA_PATH);
                    if (pickedPath != null && mCurrentPathInput != null) {
                        mCurrentPathInput.setText(pickedPath);
                    }
                }
            }
        );
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.bottomsheet_workspaces, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        mStorage = new WorkspaceStorage(requireContext());
        mAdapter = new WorkspacesAdapter(this);

        mEmptyText = view.findViewById(R.id.workspaces_empty_text);
        RecyclerView recyclerView = view.findViewById(R.id.workspaces_recycler_view);
        recyclerView.setLayoutManager(new LinearLayoutManager(requireContext()));
        recyclerView.setAdapter(mAdapter);

        view.findViewById(R.id.workspaces_add_button).setOnClickListener(v -> showAddWorkspaceDialog());

        refreshList();
    }

    private void refreshList() {
        List<Workspace> list = mStorage.getWorkspaces();
        mAdapter.setWorkspaces(list);
        if (mEmptyText != null) {
            mEmptyText.setVisibility(list.isEmpty() ? View.VISIBLE : View.GONE);
        }
    }

    private void showAddWorkspaceDialog() {
        View dialogView = LayoutInflater.from(requireContext())
            .inflate(R.layout.dialog_add_workspace, null);
        TextInputEditText nameInput = dialogView.findViewById(R.id.workspace_input_name);
        mCurrentPathInput = dialogView.findViewById(R.id.workspace_input_path);
        TextInputEditText commandInput = dialogView.findViewById(R.id.workspace_input_command);
        MaterialButton browseButton = dialogView.findViewById(R.id.workspace_browse_button);

        mCurrentPathInput.setText(TermuxConstants.TERMUX_HOME_DIR_PATH);

        browseButton.setOnClickListener(v -> {
            Intent pickerIntent = new Intent(requireContext(), FileBrowserActivity.class);
            pickerIntent.putExtra(FileBrowserActivity.EXTRA_PICK_MODE, FileBrowserActivity.PICK_MODE_FOLDER);
            pickerIntent.putExtra(FileBrowserActivity.EXTRA_START_PATH,
                mCurrentPathInput.getText() != null ? mCurrentPathInput.getText().toString() : TermuxConstants.TERMUX_HOME_DIR_PATH);
            mFolderPickerLauncher.launch(pickerIntent);
        });

        new AlertDialog.Builder(requireContext())
            .setTitle(R.string.workspace_add_title)
            .setView(dialogView)
            .setPositiveButton(R.string.quick_commands_save, (dialog, which) -> {
                String name = nameInput.getText() == null ? "" : nameInput.getText().toString().trim();
                String path = mCurrentPathInput.getText() == null ? "" : mCurrentPathInput.getText().toString().trim();
                String command = commandInput.getText() == null ? "" : commandInput.getText().toString().trim();

                if (TextUtils.isEmpty(path)) {
                    path = TermuxConstants.TERMUX_HOME_DIR_PATH;
                }
                if (TextUtils.isEmpty(name)) {
                    name = new File(path).getName();
                }

                mStorage.addWorkspace(name, path, command);
                refreshList();
            })
            .setNegativeButton(R.string.file_browser_cancel, null)
            .show();
    }

    @Override
    public void onWorkspaceClick(Workspace workspace) {
        if (getActivity() instanceof TermuxActivity) {
            TermuxActivity activity = (TermuxActivity) getActivity();
            activity.getDrawer().closeDrawers();

            Intent execIntent = new Intent(TERMUX_SERVICE.ACTION_SERVICE_EXECUTE, null, activity, TermuxService.class);
            execIntent.putExtra(TERMUX_SERVICE.EXTRA_WORKDIR, workspace.path);
            execIntent.putExtra(TERMUX_SERVICE.EXTRA_SHELL_NAME, workspace.name);

            activity.startService(execIntent);

            if (!TextUtils.isEmpty(workspace.command)) {
                activity.getTerminalView().postDelayed(() -> {
                    if (activity.getCurrentSession() != null && activity.getCurrentSession().isRunning()) {
                        activity.getCurrentSession().write(workspace.command + "\r");
                    }
                }, 500);
            }
        }
        dismiss();
    }

    @Override
    public void onWorkspaceLongClick(Workspace workspace) {
        new AlertDialog.Builder(requireContext())
            .setTitle(R.string.workspace_delete_title)
            .setMessage(getString(R.string.workspace_delete_message, workspace.name))
            .setPositiveButton(R.string.quick_commands_delete, (dialog, which) -> {
                mStorage.deleteWorkspace(workspace.id);
                refreshList();
            })
            .setNegativeButton(R.string.file_browser_cancel, null)
            .show();
    }
}