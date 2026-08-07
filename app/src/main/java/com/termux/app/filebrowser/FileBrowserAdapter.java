package com.termux.app.filebrowser;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.termux.R;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** TermuxMod: adapter that draws the list of {@link FileEntry} rows. */
public class FileBrowserAdapter extends RecyclerView.Adapter<FileBrowserAdapter.ViewHolder> {

    public interface OnEntryClickListener {
        void onEntryClick(FileEntry entry);
        void onEntryLongClick(FileEntry entry);
    }

    private final List<FileEntry> mEntries = new ArrayList<>();
    private final OnEntryClickListener mListener;

    public FileBrowserAdapter(OnEntryClickListener listener) {
        mListener = listener;
    }

    public void setEntries(List<FileEntry> entries) {
        mEntries.clear();
        mEntries.addAll(entries);
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
            .inflate(R.layout.item_file_browser, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        FileEntry entry = mEntries.get(position);

        holder.name.setText(entry.getName());

        if (entry.isDirectory) {
            holder.icon.setImageResource(R.drawable.ic_file_browser_folder);
            holder.subtitle.setText(R.string.file_browser_title_directory);
        } else if (entry.isScript) {
            holder.icon.setImageResource(R.drawable.ic_file_browser_script);
            holder.subtitle.setText(formatSize(entry.getSize()));
        } else {
            holder.icon.setImageResource(R.drawable.ic_file_browser_file);
            holder.subtitle.setText(formatSize(entry.getSize()));
        }

        holder.itemView.setOnClickListener(v -> mListener.onEntryClick(entry));
        holder.itemView.setOnLongClickListener(v -> {
            mListener.onEntryLongClick(entry);
            return true;
        });
    }

    @Override
    public int getItemCount() {
        return mEntries.size();
    }

    private static String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        int exp = (int) (Math.log(bytes) / Math.log(1024));
        String unit = "KMGTPE".charAt(exp - 1) + "B";
        return String.format(Locale.getDefault(), "%.1f %s", bytes / Math.pow(1024, exp), unit);
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        final ImageView icon;
        final TextView name;
        final TextView subtitle;

        ViewHolder(@NonNull View itemView) {
            super(itemView);
            icon = itemView.findViewById(R.id.file_browser_item_icon);
            name = itemView.findViewById(R.id.file_browser_item_name);
            subtitle = itemView.findViewById(R.id.file_browser_item_subtitle);
        }
    }
}
