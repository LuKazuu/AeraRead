package com.aera.read.ui;

import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.DocumentsContract;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.aera.read.R;
import com.aera.read.data.Book;
import com.aera.read.data.Library;
import com.aera.read.util.Ui;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.snackbar.Snackbar;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity implements LibraryAdapter.Listener {

    private RecyclerView grid;
    private LibraryAdapter adapter;
    private View emptyState;
    private ActivityResultLauncher<Uri> folderPicker;
    private final ExecutorService bg = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "aera-scan");
        t.setDaemon(true);
        return t;
    });
    private Snackbar scanSnackbar;
    private boolean scanning;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setBackgroundDrawable(null);
        Ui.enableEdgeToEdge(this);
        Ui.setLightSystemBars(this, !Ui.isNightUi(this));
        setContentView(R.layout.activity_main);

        View root = findViewById(R.id.root);
        View headerBar = findViewById(R.id.header_bar);
        grid = findViewById(R.id.grid);
        emptyState = findViewById(R.id.empty_state);
        setupInsets(root, headerBar);

        adapter = new LibraryAdapter(this);

        int widthDp = getResources().getConfiguration().screenWidthDp;
        int span = Math.max(2, widthDp / 190);
        grid.setLayoutManager(new GridLayoutManager(this, span));
        grid.addItemDecoration(new GridSpacing(Ui.dp(this, 14), span));
        grid.setAdapter(adapter);

        View.OnClickListener addFolder = v -> pickFolder();
        findViewById(R.id.empty_add).setOnClickListener(addFolder);
        findViewById(R.id.btn_scan).setOnClickListener(v -> scan(true));
        findViewById(R.id.btn_folders).setOnClickListener(v -> manageFolders());

        folderPicker = registerForActivityResult(new ActivityResultContracts.OpenDocumentTree(),
                this::onFolderPicked);
    }

    private void setupInsets(View root, View headerBar) {
        final int padH = Ui.dp(this, 20);
        final int headerEnd = Ui.dp(this, 8);
        final int headerTop = Ui.dp(this, 16);
        final int headerBot = Ui.dp(this, 2);
        final int gridTop = Ui.dp(this, 14);
        final int gridBot = Ui.dp(this, 96);
        final int emptyPad = Ui.dp(this, 40);
        final int emptyBot = Ui.dp(this, 48);

        ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            Insets safe = insets.getInsets(
                    WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
            headerBar.setPadding(padH + safe.left, headerTop + safe.top,
                    headerEnd + safe.right, headerBot);
            grid.setPadding(padH + safe.left, gridTop, padH + safe.right, gridBot + safe.bottom);
            emptyState.setPadding(emptyPad + safe.left, 0,
                    emptyPad + safe.right, emptyBot + safe.bottom);
            return insets;
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        List<Book> books = Library.books(this);
        refresh();
        if (books.isEmpty() && !Library.folders(this).isEmpty()) {
            scan(false);
        }
    }

    private void refresh() {
        List<Book> books = Library.books(this);
        adapter.submit(books);
        boolean empty = books.isEmpty();
        grid.setVisibility(empty ? View.GONE : View.VISIBLE);
        emptyState.setVisibility(empty ? View.VISIBLE : View.GONE);
    }

    void pickFolder() {
        folderPicker.launch(null);
    }

    void onPickFolderRequested() {
        pickFolder();
    }

    private void onFolderPicked(Uri treeUri) {
        if (treeUri == null) return;
        try {
            getContentResolver().takePersistableUriPermission(treeUri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (Exception ignored) {
        }
        Library.addFolder(this, treeUri.toString());
        scan(false);
    }

    private void manageFolders() {
        new FoldersSheet(this, () -> scan(false)).show();
    }

    void scan(boolean userInitiated) {
        List<String> folders = Library.folders(this);
        if (folders.isEmpty()) {
            if (userInitiated) {
                Snackbar.make(grid, R.string.no_folders, Snackbar.LENGTH_SHORT).show();
            }
            return;
        }
        if (scanning) return;
        scanning = true;
        if (scanSnackbar != null) scanSnackbar.dismiss();
        scanSnackbar = Snackbar.make(grid, R.string.scanning, Snackbar.LENGTH_INDEFINITE);
        scanSnackbar.show();

        List<Book> existing = Library.books(this);
        bg.execute(() -> {
            FolderScanner.Result result = FolderScanner.scan(this, folders, existing, null);
            runOnUiThread(() -> {
                scanning = false;
                if (isFinishing() || isDestroyed()) return;
                if (scanSnackbar != null) scanSnackbar.dismiss();
                Library.saveBooks(this, result.books);
                refresh();
                if (result.unreadableFolders > 0) {
                    Toast.makeText(this, R.string.folder_lost, Toast.LENGTH_LONG).show();
                }
                if (result.added > 0 || userInitiated) {
                    showScanSummary(result);
                }
                if (result.failed > 0) {
                    Toast.makeText(this, getString(R.string.scan_failed, result.failed),
                            Toast.LENGTH_LONG).show();
                }
            });
        });
    }

    private void showScanSummary(FolderScanner.Result result) {
        String text = getString(R.string.scan_result, result.books.size(), result.added);
        Snackbar.make(grid, text, Snackbar.LENGTH_SHORT).show();
    }

    @Override
    public void onOpen(Book book) {
        startActivity(new Intent(this, ReaderActivity.class)
                .putExtra(ReaderActivity.EXTRA_BOOK_ID, book.id()));
    }

    @Override
    public void onOptions(Book book) {
        BottomSheetDialog dialog = new BottomSheetDialog(this);
        dialog.setContentView(R.layout.sheet_book_actions);
        TextView title = dialog.findViewById(R.id.actions_title);
        if (title != null) title.setText(book.title);
        TextView sub = dialog.findViewById(R.id.actions_sub);
        if (sub != null) {
            String meta = (book.author == null || book.author.isEmpty())
                    ? book.type.toUpperCase(Locale.ROOT)
                    : book.author;
            String size = String.format(Locale.getDefault(), "%.1f MB",
                    Math.max(0.1f, book.size / (1024f * 1024f)));
            sub.setText(getString(R.string.book_meta, meta, size));
        }
        View open = dialog.findViewById(R.id.row_open);
        if (open != null) {
            open.setOnClickListener(v -> {
                dialog.dismiss();
                onOpen(book);
            });
        }
        View remove = dialog.findViewById(R.id.row_remove);
        if (remove != null) {
            remove.setOnClickListener(v -> {
                dialog.dismiss();
                confirmRemove(book);
            });
        }
        dialog.show();
    }

    void confirmRemove(Book book) {
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.remove_title)
                .setMessage(getString(R.string.remove_body, book.title))
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.remove, (d, w) -> {
                    Library.remove(this, book.id());
                    refresh();
                })
                .show();
    }

    @Override
    public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        if (adapter != null) adapter.trimMemory(level);
        PlaceholderCovers.trimMemory(level);
    }

    @Override
    protected void onDestroy() {
        if (scanSnackbar != null) {
            scanSnackbar.dismiss();
            scanSnackbar = null;
        }
        if (adapter != null) adapter.trimMemory(TRIM_MEMORY_UI_HIDDEN);
        bg.shutdownNow();
        super.onDestroy();
    }

    String folderLabel(String treeUri) {
        Uri tree = Uri.parse(treeUri);
        try {
            String id = DocumentsContract.getTreeDocumentId(tree);
            Uri doc = DocumentsContract.buildDocumentUriUsingTree(tree, id);
            try (Cursor cursor = getContentResolver().query(doc,
                    new String[]{DocumentsContract.Document.COLUMN_DISPLAY_NAME},
                    null, null, null)) {
                if (cursor != null && cursor.moveToFirst() && !cursor.isNull(0)) {
                    return cursor.getString(0);
                }
            }
        } catch (Exception ignored) {
        }
        String last = tree.getLastPathSegment();
        if (last == null) return treeUri;
        return last.replace(':', '/');
    }
}
