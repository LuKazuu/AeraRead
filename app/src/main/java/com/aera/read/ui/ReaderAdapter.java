package com.aera.read.ui;

import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.aera.read.reader.ReadingSession;

/** Shared adapter for both paged (ViewPager2) and vertical scroll reading. */
public class ReaderAdapter extends RecyclerView.Adapter<ReaderAdapter.Holder> {

    private final ReadingSession session;
    private final boolean scrollMode;
    private final int itemHeight;
    /** Item count the list was last told about; it must never change behind its back. */
    private int pageCount;

    public ReaderAdapter(ReadingSession session, boolean scrollMode, int itemHeight) {
        this.session = session;
        this.scrollMode = scrollMode;
        this.itemHeight = Math.max(1, itemHeight);
        this.pageCount = session.pageCount();
    }

    /**
     * Re-pagination changes how many pages exist. A plain "range changed" while the count
     * moves leaves RecyclerView with stale positions and makes it throw, so a new count is
     * announced as a full data set change.
     */
    public void submitPageCount(int count) {
        pageCount = Math.max(1, count);
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View item = session.createItemView(parent, scrollMode);
        item.setLayoutParams(new RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                scrollMode ? itemHeight : ViewGroup.LayoutParams.MATCH_PARENT));
        return new Holder(item);
    }

    @Override
    public void onBindViewHolder(@NonNull Holder holder, int position) {
        session.bindItemView(holder.itemView, position, itemHeight, scrollMode);
    }

    @Override
    public int getItemCount() {
        return pageCount;
    }

    public static class Holder extends RecyclerView.ViewHolder {
        public Holder(@NonNull View itemView) {
            super(itemView);
        }
    }
}
