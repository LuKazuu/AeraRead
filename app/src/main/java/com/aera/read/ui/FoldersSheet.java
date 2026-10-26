package com.aera.read.ui;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.aera.read.R;
import com.aera.read.data.Library;
import com.aera.read.util.Ui;
import com.google.android.material.bottomsheet.BottomSheetDialog;

import java.util.ArrayList;
import java.util.List;

/** Lists granted folders; removing one drops its books on the next scan. */
public class FoldersSheet {

    public interface Listener {
        void onFoldersChanged();
    }

    private final MainActivity activity;
    private final Listener listener;
    private BottomSheetDialog dialog;

    public FoldersSheet(MainActivity activity, Listener listener) {
        this.activity = activity;
        this.listener = listener;
    }

    public void show() {
        dialog = Ui.createSheet(activity, R.layout.sheet_folders, 0);
        View root = dialog.findViewById(R.id.sheet_root);
        if (root == null) return;

        RecyclerView list = root.findViewById(R.id.folder_list);
        View empty = root.findViewById(R.id.folder_empty);
        list.setLayoutManager(new LinearLayoutManager(activity));
        FolderAdapter adapter = new FolderAdapter();
        list.setAdapter(adapter);

        View addRow = root.findViewById(R.id.row_add_folder);
        addRow.setOnClickListener(v -> {
            dialog.dismiss();
            activity.onPickFolderRequested();
        });

        reload(adapter, empty);
        dialog.show();
    }

    private void reload(FolderAdapter adapter, View empty) {
        List<String> folders = Library.folders(activity);
        adapter.submit(folders);
        empty.setVisibility(folders.isEmpty() ? View.VISIBLE : View.GONE);
    }

    private class FolderAdapter extends RecyclerView.Adapter<FolderAdapter.Holder> {
        private final List<String> folders = new ArrayList<>();

        void submit(List<String> items) {
            folders.clear();
            folders.addAll(items);
            notifyDataSetChanged();
        }

        @NonNull
        @Override
        public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new Holder(LayoutInflater.from(activity)
                    .inflate(R.layout.item_folder, parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull Holder holder, int position) {
            String uri = folders.get(position);
            holder.name.setText(activity.folderLabel(uri));
            holder.remove.setOnClickListener(v -> {
                Library.removeFolder(activity, uri);
                if (dialog != null) dialog.dismiss();
                listener.onFoldersChanged();
            });
        }

        @Override
        public int getItemCount() {
            return folders.size();
        }

        class Holder extends RecyclerView.ViewHolder {
            final TextView name;
            final View remove;

            Holder(View view) {
                super(view);
                name = view.findViewById(R.id.folder_name);
                remove = view.findViewById(R.id.folder_remove);
            }
        }
    }
}
