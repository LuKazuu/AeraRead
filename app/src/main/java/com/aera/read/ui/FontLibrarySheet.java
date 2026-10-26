package com.aera.read.ui;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.aera.read.R;
import com.aera.read.util.FontCatalog;
import com.aera.read.util.FontStore;
import com.aera.read.util.Fonts;
import com.aera.read.util.Ui;
import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.button.MaterialButton;

import java.util.ArrayList;
import java.util.List;

/** Download and remove fonts. Only the system face ships with the app. */
public class FontLibrarySheet {

    public interface Callback {
        void onFontsChanged();
    }

    private final Context ctx;
    private final Callback callback;
    private BottomSheetDialog dialog;
    private LinearLayout list;
    private final List<Row> rows = new ArrayList<>();

    public FontLibrarySheet(Context ctx, Callback callback) {
        this.ctx = ctx;
        this.callback = callback;
    }

    public void show() {
        FontStore.cleanPartials(ctx);
        dialog = Ui.createSheet(ctx, R.layout.sheet_fonts, 16);
        View root = dialog.findViewById(R.id.sheet_root);
        if (root == null) return;

        list = root.findViewById(R.id.font_list);
        dialog.show();

        View sheet = dialog.findViewById(com.google.android.material.R.id.design_bottom_sheet);
        if (sheet != null) {
            android.util.DisplayMetrics metrics = ctx.getResources().getDisplayMetrics();
            sheet.getLayoutParams().height = (int) (metrics.heightPixels * 0.85f);
            BottomSheetBehavior.from(sheet).setState(BottomSheetBehavior.STATE_EXPANDED);
        }
        buildRows();
    }

    private void buildRows() {
        if (list == null) return;
        list.removeAllViews();
        rows.clear();

        addGroup(R.string.fonts_latin);
        for (FontCatalog.Entry entry : FontCatalog.ALL) {
            if (!entry.isCjk()) addRow(entry);
        }
        addGroup(R.string.fonts_cjk);
        for (FontCatalog.Entry entry : FontCatalog.ALL) {
            if (entry.isCjk()) addRow(entry);
        }
    }

    private void addGroup(int labelRes) {
        TextView header = new TextView(ctx);
        header.setText(labelRes);
        header.setTextAppearance(R.style.TextAppearance_Aera_SectionLabel);
        header.setPadding(0, Ui.dp(ctx, 18), 0, Ui.dp(ctx, 4));
        list.addView(header);
    }

    private void addRow(FontCatalog.Entry entry) {
        LinearLayout row = (LinearLayout) LayoutInflater.from(ctx)
                .inflate(R.layout.item_font, list, false);
        TextView preview = row.findViewById(R.id.font_preview);
        TextView name = row.findViewById(R.id.font_name);
        TextView state = row.findViewById(R.id.font_state);
        MaterialButton action = row.findViewById(R.id.font_action);

        name.setText(entry.label);
        preview.setText(entry.isCjk() ? R.string.font_preview_cjk : R.string.font_preview_latin);
        action.setText(R.string.remove);

        Row holder = new Row(entry, row, preview, state, action);
        action.setOnClickListener(v -> remove(holder));
        rows.add(holder);
        render(holder);
        row.setOnClickListener(v -> download(holder));
        list.addView(row);
    }

    private void render(Row row) {
        boolean installed = FontStore.isInstalled(ctx, row.entry.id);
        boolean busy = FontStore.isDownloading(row.entry.id);

        row.preview.setTypeface(installed ? Fonts.get(ctx, row.entry.id, false, false)
                : Fonts.get(ctx, Fonts.SYSTEM, false, false));

        if (busy) {
            row.state.setText(R.string.font_downloading);
        } else if (installed) {
            row.state.setText(R.string.font_installed);
        } else if (row.failed) {
            row.state.setText(R.string.font_download_failed);
        } else {
            row.state.setText(ctx.getString(R.string.font_download_size,
                    FontCatalog.sizeLabel(row.entry.bytes)));
        }
        row.action.setVisibility(installed && !busy ? View.VISIBLE : View.GONE);
        row.row.setEnabled(!busy);
        row.row.setAlpha(busy ? 0.6f : 1f);
    }

    private void download(Row row) {
        if (FontStore.isDownloading(row.entry.id)) return;
        if (FontStore.isInstalled(ctx, row.entry.id)) return;
        row.failed = false;
        FontStore.download(ctx, row.entry, new FontStore.Listener() {
            @Override
            public void onProgress(String id, int percent) {
                Row target = find(id);
                if (target == null) return;
                target.state.setText(ctx.getString(R.string.font_downloading_percent, percent));
            }

            @Override
            public void onFinished(String id, boolean ok) {
                Row target = find(id);
                if (target == null) return;
                target.failed = !ok;
                render(target);
                callback.onFontsChanged();
            }
        });
        render(row);
    }

    private void remove(Row row) {
        FontStore.remove(ctx, row.entry.id);
        callback.onFontsChanged();
        render(row);
    }

    private Row find(String id) {
        for (Row row : rows) {
            if (row.entry.id.equals(id)) return row;
        }
        return null;
    }

    private static final class Row {
        final FontCatalog.Entry entry;
        final LinearLayout row;
        final TextView preview;
        final TextView state;
        final MaterialButton action;
        boolean failed;

        Row(FontCatalog.Entry entry, LinearLayout row, TextView preview, TextView state,
            MaterialButton action) {
            this.entry = entry;
            this.row = row;
            this.preview = preview;
            this.state = state;
            this.action = action;
        }
    }
}
