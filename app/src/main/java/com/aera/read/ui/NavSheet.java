package com.aera.read.ui;

import android.content.Context;
import android.graphics.Typeface;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.aera.read.R;
import com.aera.read.data.Bookmark;
import com.aera.read.epub.EpubBook;
import com.aera.read.reader.ReadingSession;
import com.aera.read.util.Ui;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.button.MaterialButtonToggleGroup;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Contents and bookmarks of the open book behind one segmented switch. */
public class NavSheet {

    public interface JumpListener {
        void onJump(int page);
    }

    static final class Entry {
        final String title;
        final int page;

        Entry(String title, int page) {
            this.title = title;
            this.page = page;
        }
    }

    private final Context ctx;
    private final ReadingSession session;
    private final int currentPage;
    private final JumpListener listener;
    private final Runnable onBookmarksChanged;
    private final int initialTab;

    private BottomSheetDialog dialog;
    private RecyclerView list;
    private TextView empty;
    private MaterialButtonToggleGroup tabs;
    private boolean hasToc;

    public NavSheet(Context ctx, ReadingSession session, int currentPage, JumpListener listener,
                    Runnable onBookmarksChanged) {
        this(ctx, session, currentPage, listener, onBookmarksChanged, 0);
    }

    private NavSheet(Context ctx, ReadingSession session, int currentPage, JumpListener listener,
                     Runnable onBookmarksChanged, int initialTab) {
        this.ctx = ctx;
        this.session = session;
        this.currentPage = currentPage;
        this.listener = listener;
        this.onBookmarksChanged = onBookmarksChanged;
        this.initialTab = initialTab;
    }

    public NavSheet openOn(int tab) {
        return new NavSheet(ctx, session, currentPage, listener, onBookmarksChanged, tab);
    }

    public void show() {
        dialog = Ui.createSheet(ctx, R.layout.sheet_nav, 0);
        View root = dialog.findViewById(R.id.sheet_root);
        if (root == null) return;

        TextView title = root.findViewById(R.id.nav_title);
        tabs = root.findViewById(R.id.nav_tabs);
        list = root.findViewById(R.id.list);
        empty = root.findViewById(R.id.empty);
        list.setLayoutManager(new LinearLayoutManager(ctx));
        list.setHasFixedSize(false);

        hasToc = session.supportsContents() && !session.contents().isEmpty();
        if (!hasToc) {
            tabs.setVisibility(View.GONE);
            if (title != null) title.setText(R.string.bookmarks);
        }

        tabs.addOnButtonCheckedListener((group, checkedId, isChecked) -> {
            if (!isChecked) return;
            if (checkedId == R.id.btn_contents) showContents();
            else showBookmarks();
        });

        boolean contents = hasToc && initialTab == 0;
        tabs.check(contents ? R.id.btn_contents : R.id.btn_bookmarks);
        if (contents) showContents();
        else showBookmarks();

        dialog.show();
    }

    static List<Entry> buildEntries(List<String> titles, List<Integer> pages) {
        List<Entry> entries = new ArrayList<>();
        Set<Integer> seen = new HashSet<>();
        for (int i = 0; i < titles.size() && i < pages.size(); i++) {
            int page = pages.get(i);
            if (!seen.add(page)) continue;
            String title = titles.get(i);
            if (title == null || title.trim().isEmpty()) continue;
            entries.add(new Entry(title.trim(), page));
        }
        return entries;
    }

    private void layoutList() {
        View frame = (View) list.getParent();
        View root = dialog.findViewById(R.id.sheet_root);
        if (frame == null || root == null) return;
        int max = Math.round(ctx.getResources().getDisplayMetrics().heightPixels * 0.52f);
        ViewGroup.LayoutParams params = frame.getLayoutParams();
        if (params == null) return;
        params.height = max;
        frame.setLayoutParams(params);
    }

    private void showContents() {
        List<EpubBook.TocEntry> toc = session.contents();
        List<String> titles = new ArrayList<>(toc.size());
        List<Integer> pages = new ArrayList<>(toc.size());
        for (int i = 0; i < toc.size(); i++) {
            titles.add(toc.get(i).title);
            pages.add(session.pageOfContents(i));
        }
        List<Entry> entries = buildEntries(titles, pages);

        int selected = -1;
        for (int i = 0; i < entries.size(); i++) {
            if (entries.get(i).page <= currentPage) selected = i;
        }
        empty.setVisibility(View.GONE);
        list.setAdapter(new TocAdapter(entries, selected));
        layoutList();
        if (selected > 0) {
            ((LinearLayoutManager) list.getLayoutManager())
                    .scrollToPositionWithOffset(Math.max(0, selected - 1), 0);
        }
    }

    private void showBookmarks() {
        List<Bookmark> marks = session.bookmarks();
        layoutList();
        if (marks.isEmpty()) {
            list.setAdapter(null);
            empty.setVisibility(View.VISIBLE);
            empty.setText(R.string.no_bookmarks);
        } else {
            empty.setVisibility(View.GONE);
            list.setAdapter(new BookmarkAdapter(marks));
        }
    }

    private void jump(int page) {
        listener.onJump(page);
        dismiss();
    }

    private void dismiss() {
        if (dialog != null) dialog.dismiss();
    }

    class TocAdapter extends RecyclerView.Adapter<TocAdapter.Holder> {
        final List<Entry> items;
        private final int selected;

        TocAdapter(List<Entry> items, int selected) {
            this.items = items;
            this.selected = selected;
        }

        @NonNull
        @Override
        public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new Holder(LayoutInflater.from(ctx).inflate(R.layout.item_toc, parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull Holder holder, int position) {
            Entry entry = items.get(position);
            boolean current = position == selected;
            holder.title.setText(entry.title);
            holder.title.setTextColor(current
                    ? Ui.colorAttr(holder.title.getContext(),
                            com.google.android.material.R.attr.colorPrimary)
                    : Ui.colorAttr(holder.title.getContext(),
                            com.google.android.material.R.attr.colorOnSurface));
            holder.title.setTypeface(null, current ? Typeface.BOLD : Typeface.NORMAL);
            holder.page.setText(String.valueOf(entry.page + 1));
            holder.divider.setVisibility(position == items.size() - 1 ? View.GONE : View.VISIBLE);
            holder.itemView.setOnClickListener(v -> jump(entry.page));
        }

        @Override
        public int getItemCount() {
            return items.size();
        }

        class Holder extends RecyclerView.ViewHolder {
            final TextView title;
            final TextView page;
            final View divider;

            Holder(View view) {
                super(view);
                title = view.findViewById(R.id.toc_title);
                page = view.findViewById(R.id.toc_page);
                divider = view.findViewById(R.id.toc_divider);
            }
        }
    }

    class BookmarkAdapter extends RecyclerView.Adapter<BookmarkAdapter.Holder> {
        private final List<Bookmark> items;

        BookmarkAdapter(List<Bookmark> items) {
            this.items = items;
        }

        @NonNull
        @Override
        public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new Holder(LayoutInflater.from(ctx)
                    .inflate(R.layout.item_bookmark, parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull Holder holder, int position) {
            Bookmark bookmark = items.get(position);
            int page = session.pageOfBookmark(bookmark);
            holder.title.setText(session.bookmarkTitle(page));
            if (bookmark.chapter < 0) {
                holder.sub.setVisibility(View.GONE);
            } else {
                holder.sub.setVisibility(View.VISIBLE);
                holder.sub.setText(session.pageLabel(page));
            }
            holder.divider.setVisibility(position == items.size() - 1 ? View.GONE : View.VISIBLE);
            holder.itemView.setOnClickListener(v -> jump(session.pageOfBookmark(bookmark)));
            holder.delete.setOnClickListener(v -> {
                session.deleteBookmark(bookmark);
                if (onBookmarksChanged != null) onBookmarksChanged.run();
                showBookmarks();
            });
        }

        @Override
        public int getItemCount() {
            return items.size();
        }

        class Holder extends RecyclerView.ViewHolder {
            final TextView title;
            final TextView sub;
            final ImageButton delete;
            final View divider;

            Holder(View view) {
                super(view);
                title = view.findViewById(R.id.bm_title);
                sub = view.findViewById(R.id.bm_sub);
                delete = view.findViewById(R.id.bm_delete);
                divider = view.findViewById(R.id.bm_divider);
            }
        }
    }
}
