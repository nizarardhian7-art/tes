package com.termux.app.themes;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.bottomsheet.BottomSheetDialogFragment;
import com.termux.R;

public class ThemeSelectionBottomSheet extends BottomSheetDialogFragment implements ThemeAdapter.Listener {

    private ThemeAdapter mAdapter;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.bottomsheet_theme_selection, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        mAdapter = new ThemeAdapter(this);
        RecyclerView recyclerView = view.findViewById(R.id.themes_recycler_view);
        recyclerView.setLayoutManager(new LinearLayoutManager(requireContext()));
        recyclerView.setAdapter(mAdapter);

        mAdapter.setThemes(ThemePreset.getPresets());
    }

    @Override
    public void onThemeClick(ThemePreset theme) {
        ThemeManager.applyTheme(requireContext(), theme);
        dismiss();
    }
}