package com.termux.app.quickcommands;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.termux.R;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** TermuxMod: flat RecyclerView with two row types — a category header, and a
 * command row — built by grouping the given commands by {@link QuickCommand#category}
 * while preserving insertion order (so "My Commands" shows wherever it's inserted). */
public class QuickCommandsAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    private static final int TYPE_HEADER = 0;
    private static final int TYPE_COMMAND = 1;

    public interface Listener {
        void onCommandClick(QuickCommand command);
        void onCommandLongClick(QuickCommand command);
    }

    /** A row is either a category name (header) or a command. */
    private static class Row {
        final String header;
        final QuickCommand command;
        Row(String header) { this.header = header; this.command = null; }
        Row(QuickCommand command) { this.header = null; this.command = command; }
    }

    private final List<Row> mRows = new ArrayList<>();
    private final Listener mListener;

    public QuickCommandsAdapter(Listener listener) {
        mListener = listener;
    }

    public void setCommands(List<QuickCommand> commands) {
        mRows.clear();
        Map<String, List<QuickCommand>> byCategory = new LinkedHashMap<>();
        for (QuickCommand c : commands) {
            byCategory.computeIfAbsent(c.category, k -> new ArrayList<>()).add(c);
        }
        for (Map.Entry<String, List<QuickCommand>> entry : byCategory.entrySet()) {
            mRows.add(new Row(entry.getKey()));
            for (QuickCommand c : entry.getValue()) {
                mRows.add(new Row(c));
            }
        }
        notifyDataSetChanged();
    }

    @Override
    public int getItemViewType(int position) {
        return mRows.get(position).header != null ? TYPE_HEADER : TYPE_COMMAND;
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        LayoutInflater inflater = LayoutInflater.from(parent.getContext());
        if (viewType == TYPE_HEADER) {
            return new HeaderHolder(inflater.inflate(R.layout.item_quick_command_header, parent, false));
        }
        return new CommandHolder(inflater.inflate(R.layout.item_quick_command, parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        Row row = mRows.get(position);
        if (holder instanceof HeaderHolder) {
            ((HeaderHolder) holder).title.setText(row.header);
        } else {
            CommandHolder h = (CommandHolder) holder;
            h.label.setText(row.command.label);
            h.commandText.setText(row.command.command);
            h.itemView.setOnClickListener(v -> mListener.onCommandClick(row.command));
            h.itemView.setOnLongClickListener(v -> {
                if (row.command.custom) mListener.onCommandLongClick(row.command);
                return row.command.custom;
            });
        }
    }

    @Override
    public int getItemCount() {
        return mRows.size();
    }

    static class HeaderHolder extends RecyclerView.ViewHolder {
        final TextView title;
        HeaderHolder(@NonNull View itemView) {
            super(itemView);
            title = itemView.findViewById(R.id.quick_command_header_title);
        }
    }

    static class CommandHolder extends RecyclerView.ViewHolder {
        final TextView label;
        final TextView commandText;
        CommandHolder(@NonNull View itemView) {
            super(itemView);
            label = itemView.findViewById(R.id.quick_command_label);
            commandText = itemView.findViewById(R.id.quick_command_text);
        }
    }

}
