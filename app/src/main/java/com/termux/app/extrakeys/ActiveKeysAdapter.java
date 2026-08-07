package com.termux.app.extrakeys;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.RecyclerView;

import com.termux.R;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class ActiveKeysAdapter extends RecyclerView.Adapter<ActiveKeysAdapter.ViewHolder> {

    public interface Listener {
        void onItemRemove(ExtraKeysItem item, int position);
    }

    private final List<ExtraKeysItem> mActiveList = new ArrayList<>();
    private final Listener mListener;

    public ActiveKeysAdapter(Listener listener) {
        mListener = listener;
    }

    public void setItems(List<ExtraKeysItem> items) {
        mActiveList.clear();
        mActiveList.addAll(items);
        notifyDataSetChanged();
    }

    public List<ExtraKeysItem> getItems() {
        return mActiveList;
    }

    public void addItem(ExtraKeysItem item) {
        mActiveList.add(item);
        notifyItemInserted(mActiveList.size() - 1);
    }

    public void removeItem(int position) {
        if (position >= 0 && position < mActiveList.size()) {
            ExtraKeysItem item = mActiveList.get(position);
            if (item.isLocked) {
                if (mListener instanceof ExtraKeysManagerBottomSheet) {
                    Toast.makeText(((ExtraKeysManagerBottomSheet) mListener).requireContext(),
                        "Settings key (⚙) is required and cannot be removed.", Toast.LENGTH_SHORT).show();
                }
                return;
            }
            ExtraKeysItem removed = mActiveList.remove(position);
            notifyItemRemoved(position);
            if (mListener != null) {
                mListener.onItemRemove(removed, position);
            }
        }
    }

    public void swapItems(int fromPosition, int toPosition) {
        if (fromPosition < toPosition) {
            for (int i = fromPosition; i < toPosition; i++) {
                Collections.swap(mActiveList, i, i + 1);
            }
        } else {
            for (int i = fromPosition; i > toPosition; i--) {
                Collections.swap(mActiveList, i, i - 1);
            }
        }
        notifyItemMoved(fromPosition, toPosition);
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
            .inflate(R.layout.item_active_extra_key, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        ExtraKeysItem item = mActiveList.get(position);
        String text = (position + 1) + ". " + item.label;
        if (item.isLocked) {
            text += " 🔒";
        }
        holder.label.setText(text);
        holder.itemView.setOnClickListener(v -> removeItem(holder.getAdapterPosition()));
    }

    @Override
    public int getItemCount() {
        return mActiveList.size();
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        final TextView label;

        ViewHolder(@NonNull View itemView) {
            super(itemView);
            label = itemView.findViewById(R.id.active_extra_key_label);
        }
    }

    public static class TouchHelperCallback extends ItemTouchHelper.SimpleCallback {
        private final ActiveKeysAdapter mAdapter;

        public TouchHelperCallback(ActiveKeysAdapter adapter) {
            super(ItemTouchHelper.UP | ItemTouchHelper.DOWN | ItemTouchHelper.LEFT | ItemTouchHelper.RIGHT, 0);
            mAdapter = adapter;
        }

        @Override
        public boolean onMove(@NonNull RecyclerView recyclerView, @NonNull RecyclerView.ViewHolder viewHolder, @NonNull RecyclerView.ViewHolder target) {
            mAdapter.swapItems(viewHolder.getAdapterPosition(), target.getAdapterPosition());
            return true;
        }

        @Override
        public void onSwiped(@NonNull RecyclerView.ViewHolder viewHolder, int direction) {
        }
    }
}