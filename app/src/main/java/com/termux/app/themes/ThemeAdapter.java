package com.termux.app.themes;

import android.graphics.Color;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.termux.R;

import java.util.ArrayList;
import java.util.List;

public class ThemeAdapter extends RecyclerView.Adapter<ThemeAdapter.ViewHolder> {

    public interface Listener {
        void onThemeClick(ThemePreset theme);
    }

    private final List<ThemePreset> mList = new ArrayList<>();
    private final Listener mListener;

    public ThemeAdapter(Listener listener) {
        mListener = listener;
    }

    public void setThemes(ThemePreset[] themes) {
        mList.clear();
        for (ThemePreset t : themes) {
            mList.add(t);
        }
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
            .inflate(R.layout.item_theme_preset, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        ThemePreset theme = mList.get(position);
        holder.name.setText(theme.name);

        try {
            holder.previewBg.setBackgroundColor(Color.parseColor(theme.backgroundColor));
            holder.previewFg.setBackgroundColor(Color.parseColor(theme.foregroundColor));
            holder.previewAccent.setBackgroundColor(Color.parseColor(theme.ansiColors[1])); // Red/Accent preview
        } catch (Exception ignored) {
        }

        holder.itemView.setOnClickListener(v -> mListener.onThemeClick(theme));
    }

    @Override
    public int getItemCount() {
        return mList.size();
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        final TextView name;
        final View previewBg;
        final View previewFg;
        final View previewAccent;

        ViewHolder(@NonNull View itemView) {
            super(itemView);
            name = itemView.findViewById(R.id.theme_item_name);
            previewBg = itemView.findViewById(R.id.theme_preview_bg);
            previewFg = itemView.findViewById(R.id.theme_preview_fg);
            previewAccent = itemView.findViewById(R.id.theme_preview_accent);
        }
    }
}