package com.termux.app.workspaces;

import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.termux.R;

import java.util.ArrayList;
import java.util.List;

public class WorkspacesAdapter extends RecyclerView.Adapter<WorkspacesAdapter.ViewHolder> {

    public interface Listener {
        void onWorkspaceClick(Workspace workspace);
        void onWorkspaceLongClick(Workspace workspace);
    }

    private final List<Workspace> mList = new ArrayList<>();
    private final Listener mListener;

    public WorkspacesAdapter(Listener listener) {
        mListener = listener;
    }

    public void setWorkspaces(List<Workspace> workspaces) {
        mList.clear();
        mList.addAll(workspaces);
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
            .inflate(R.layout.item_workspace, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        Workspace workspace = mList.get(position);

        holder.name.setText(workspace.name);
        holder.path.setText(workspace.path);

        if (TextUtils.isEmpty(workspace.command)) {
            holder.command.setVisibility(View.GONE);
        } else {
            holder.command.setVisibility(View.VISIBLE);
            holder.command.setText("$ " + workspace.command);
        }

        holder.itemView.setOnClickListener(v -> mListener.onWorkspaceClick(workspace));
        holder.itemView.setOnLongClickListener(v -> {
            mListener.onWorkspaceLongClick(workspace);
            return true;
        });
    }

    @Override
    public int getItemCount() {
        return mList.size();
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        final TextView name;
        final TextView path;
        final TextView command;

        ViewHolder(@NonNull View itemView) {
            super(itemView);
            name = itemView.findViewById(R.id.workspace_item_name);
            path = itemView.findViewById(R.id.workspace_item_path);
            command = itemView.findViewById(R.id.workspace_item_command);
        }
    }
}