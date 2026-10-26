package com.aera.read.ui;

import android.graphics.Bitmap;
import android.util.LruCache;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.aera.read.R;
import com.aera.read.data.Book;
import com.aera.read.util.Ui;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.progressindicator.LinearProgressIndicator;

import java.util.ArrayList;
import java.util.List;

public class LibraryAdapter extends RecyclerView.Adapter<LibraryAdapter.Holder> {

    private static final int MAX_COVER_CACHE_BYTES = 12 * 1024 * 1024;

    public interface Listener {
        void onOpen(Book book);

        void onOptions(Book book);
    }

    private final List<Book> items = new ArrayList<>();
    private final Listener listener;
    private final LruCache<String, Bitmap> covers;

    public LibraryAdapter(Listener listener) {
        this.listener = listener;
        long maxHeap = Runtime.getRuntime().maxMemory();
        int budget = (int) Math.max(2 * 1024 * 1024L,
                Math.min(MAX_COVER_CACHE_BYTES, maxHeap / 16));
        this.covers = new LruCache<String, Bitmap>(budget) {
            @Override
            protected int sizeOf(String key, Bitmap value) {
                return value.getAllocationByteCount();
            }
        };
    }

    public void submit(List<Book> books) {
        items.clear();
        items.addAll(books);
        notifyDataSetChanged();
    }

    public void trimMemory(int level) {
        Ui.trimCache(covers, level);
    }

    @NonNull
    @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_book, parent, false);
        return new Holder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull Holder holder, int position) {
        Book book = items.get(position);
        holder.title.setText(book.title);
        boolean hasAuthor = book.author != null && !book.author.trim().isEmpty();
        holder.author.setText(hasAuthor ? book.author
                : holder.author.getContext().getString(R.string.no_author));
        holder.author.setVisibility(View.VISIBLE);
        holder.progress.setProgressCompat(Math.round(book.progress * 100), false);
        holder.cover.setImageBitmap(coverFor(book));
        holder.card.setOnClickListener(v -> listener.onOpen(book));
        holder.card.setOnLongClickListener(v -> {
            listener.onOptions(book);
            return true;
        });
        holder.more.setOnClickListener(v -> listener.onOptions(book));
    }

    private Bitmap coverFor(Book book) {
        String key = book.id() + "|" + book.coverFile;
        Bitmap cached = covers.get(key);
        if (cached != null) return cached;
        Bitmap bitmap = Thumbnails.decodeCard(book.coverFile, 500);
        if (bitmap == null) bitmap = PlaceholderCovers.get(book);
        if (bitmap != null) covers.put(key, bitmap);
        return bitmap;
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    static class Holder extends RecyclerView.ViewHolder {
        final MaterialCardView card;
        final ImageView cover;
        final TextView title;
        final TextView author;
        final LinearProgressIndicator progress;
        final ImageButton more;

        Holder(@NonNull View view) {
            super(view);
            card = view.findViewById(R.id.card);
            cover = view.findViewById(R.id.cover);
            title = view.findViewById(R.id.title);
            author = view.findViewById(R.id.author);
            progress = view.findViewById(R.id.progress);
            more = view.findViewById(R.id.more);
        }
    }
}
