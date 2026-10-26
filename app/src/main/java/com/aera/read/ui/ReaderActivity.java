package com.aera.read.ui;

import android.content.res.ColorStateList;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Choreographer;
import android.view.Menu;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.graphics.drawable.DrawableCompat;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewpager2.widget.ViewPager2;

import com.aera.read.R;
import com.aera.read.data.Book;
import com.aera.read.data.Library;
import com.aera.read.data.ReaderPrefs;
import com.aera.read.data.ReaderTheme;
import com.aera.read.reader.EpubSession;
import com.aera.read.reader.PdfSession;
import com.aera.read.reader.ReadingSession;
import com.aera.read.util.Fonts;
import com.aera.read.util.Ui;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.progressindicator.CircularProgressIndicator;
import com.google.android.material.snackbar.Snackbar;

import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.IntUnaryOperator;

public class ReaderActivity extends AppCompatActivity {

    public static final String EXTRA_BOOK_ID = "book_id";

    private static final long TAP_ANIM_NS = 180_000_000L;
    private static final long FIRST_FRAME_LEAD_NS = 16_000_000L;
    private static final long PREVIEW_INTERVAL_MS = 90;

    private Book book;
    private ReaderPrefs prefs;
    private ReaderTheme theme;
    private ReadingSession session;
    private int currentPage;
    private float currentFraction;
    private int pageCount;
    private boolean scrollMode;
    private boolean chromeVisible = true;

    private FrameLayout root;
    private FrameLayout contentFrame;
    private ViewPager2 pager;
    private RecyclerView scrollList;
    private View loadingView;
    private CircularProgressIndicator loadingSpinner;
    private TextView loadingText;
    private MaterialToolbar topBar;
    private View bottomBar;
    private SeekBar slider;
    private TextView pageLabel;
    private TextView percentLabel;

    private ReaderAdapter pagerAdapter;
    private ReaderAdapter scrollAdapter;

    private ExecutorService bg;
    private boolean layoutRunning;
    private boolean layoutPending;
    private boolean layoutPendingQuiet = true;
    private boolean updatingSlider;
    private boolean sliderDragging;

    private int lastPagedDx;
    private int lastScrolledDy;
    private final GlideAnimator pageGlide = new GlideAnimator(
            delta -> {
                RecyclerView rv = pagerList();
                lastPagedDx = 0;
                if (rv != null) rv.scrollBy(delta, 0);
                return lastPagedDx;
            },
            () -> {
                snapPagerToCurrentPage();
                persistPosition();
            });
    private final GlideAnimator scrollGlide = new GlideAnimator(
            delta -> {
                lastScrolledDy = 0;
                if (scrollList != null) scrollList.scrollBy(0, delta);
                return lastScrolledDy;
            },
            this::persistPosition);

    private float downX;
    private float downY;
    private int maxPointers;

    private int loadGeneration;
    private int navTicks;
    private int laidSize = -1;
    private int laidLine = -1;
    private String laidFont = "";
    private int laidFrameW = -1;
    private int laidFrameH = -1;
    private boolean previewing;
    private boolean firstPreview;
    private boolean releaseAnchor;
    private boolean previewQueued;
    private boolean previewScheduled;
    private final Handler ui = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        book = Library.byId(this, getIntent().getStringExtra(EXTRA_BOOK_ID));
        if (book == null) {
            finish();
            return;
        }
        prefs = new ReaderPrefs(this);
        if (!Fonts.isAvailable(this, prefs.font)) {
            prefs.font = Fonts.SYSTEM;
            prefs.save();
        }
        theme = ReaderTheme.from(prefs.theme);

        setContentView(R.layout.activity_reader);
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (!chromeVisible) {
                    setChromeVisible(true);
                    return;
                }
                flushAnimations();
                persistPosition();
                setEnabled(false);
                getOnBackPressedDispatcher().onBackPressed();
            }
        });
        bindViews();
        setupWindow();
        setupInsets();
        setupToolbar();
        setupPager();
        setupScroll();
        setupSlider();
        applyTheme();
        applyModeVisibility();
        applyDirection();
        updateKeepScreenOn();

        bg = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "aera-reader-bg");
            t.setDaemon(true);
            return t;
        });
        contentFrame.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            if (session == null || isFinishing() || isDestroyed()) return;
            int w = frameW();
            int h = frameH();
            if (laidFrameW > 0 && laidFrameH > 0 && (w != laidFrameW || h != laidFrameH)) {
                requestRelayout(true);
            }
        });
        contentFrame.post(this::startLoad);
    }

    private void bindViews() {
        root = findViewById(R.id.reader_root);
        contentFrame = findViewById(R.id.content_frame);
        pager = findViewById(R.id.pager);
        scrollList = findViewById(R.id.scroll_list);
        loadingView = findViewById(R.id.loading);
        loadingSpinner = findViewById(R.id.loading_spinner);
        loadingText = findViewById(R.id.loading_text);
        topBar = findViewById(R.id.top_bar);
        bottomBar = findViewById(R.id.bottom_bar);
        slider = findViewById(R.id.progress_slider);
        pageLabel = findViewById(R.id.page_label);
        percentLabel = findViewById(R.id.percent_label);
    }

    @SuppressWarnings("deprecation")
    private void setupWindow() {
        Ui.enableEdgeToEdge(this);
        if (Build.VERSION.SDK_INT >= 29) {
            getWindow().setStatusBarContrastEnforced(false);
        }
    }

    private void setupInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            Insets safe = insets.getInsets(
                    WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
            contentFrame.setPadding(safe.left, safe.top, safe.right, safe.bottom);
            topBar.setPadding(safe.left, safe.top + Ui.dp(this, 4),
                    safe.right, Ui.dp(this, 12));
            bottomBar.setPadding(Ui.dp(this, 20) + safe.left, Ui.dp(this, 8),
                    Ui.dp(this, 20) + safe.right, safe.bottom + Ui.dp(this, 6));
            return insets;
        });
    }

    private int frameW() {
        return Math.max(1, contentFrame.getWidth() - contentFrame.getPaddingLeft()
                - contentFrame.getPaddingRight());
    }

    private int frameH() {
        return Math.max(1, contentFrame.getHeight() - contentFrame.getPaddingTop()
                - contentFrame.getPaddingBottom());
    }

    private void setupToolbar() {
        topBar.inflateMenu(R.menu.menu_reader);
        topBar.setNavigationOnClickListener(v -> {
            flushAnimations();
            persistPosition();
            finish();
        });
        topBar.setOnMenuItemClickListener(item -> {
            int id = item.getItemId();
            if (id == R.id.action_bookmark) {
                toggleBookmark();
                return true;
            }
            if (id == R.id.action_toc) {
                openNav(0);
                return true;
            }
            if (id == R.id.action_appearance) {
                openAppearance();
                return true;
            }
            return false;
        });
    }

    private void setupPager() {
        pager.setOffscreenPageLimit(1);
        pager.setOverScrollMode(View.OVER_SCROLL_NEVER);
        pager.getChildAt(0).setOverScrollMode(View.OVER_SCROLL_NEVER);
        RecyclerView pagerRv = pagerList();
        if (pagerRv != null) {
            pagerRv.setItemAnimator(null);
            pagerRv.addOnScrollListener(new RecyclerView.OnScrollListener() {
                @Override
                public void onScrolled(@NonNull RecyclerView recyclerView, int dx, int dy) {
                    lastPagedDx = dx;
                }
            });
        }
        pager.registerOnPageChangeCallback(new ViewPager2.OnPageChangeCallback() {
            @Override
            public void onPageSelected(int position) {
                if (pageGlide.isRunning()) return;
                if (position != pager.getCurrentItem()) return;
                if (position != currentPage) {
                    currentPage = position;
                    currentFraction = 0f;
                    if (session != null) session.noteNavigation();
                    updateChrome();
                    if (!sliderDragging) persistPosition();
                }
            }
        });
    }

    private void setupScroll() {
        scrollList.setLayoutManager(new LinearLayoutManager(this));
        scrollList.setItemAnimator(null);
        scrollList.setItemViewCacheSize(4);
        scrollList.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView recyclerView, int dx, int dy) {
                lastScrolledDy = dy;
                if (!scrollMode || dy == 0) return;
                if (session != null) session.noteNavigation();
                int first = firstVisiblePage();
                if (first != RecyclerView.NO_POSITION) {
                    boolean pageChanged = first != currentPage;
                    currentPage = first;
                    currentFraction = scrollFraction();
                    if (pageChanged) updateChrome();
                }
            }

            @Override
            public void onScrollStateChanged(@NonNull RecyclerView recyclerView, int newState) {
                if (newState == RecyclerView.SCROLL_STATE_IDLE) persistPosition();
            }
        });
    }

    private void setupSlider() {
        slider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                onSliderProgress(progress, fromUser);
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
                sliderDragging = true;
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                sliderDragging = false;
                onSliderProgress(seekBar.getProgress(), true);
                persistPosition();
            }
        });
    }

    void onSliderProgress(int page, boolean fromUser) {
        if (!fromUser || updatingSlider || session == null) return;
        pageLabel.setText(session.pageLabel(page));
        percentLabel.setText(session.percentLabel(page));
        int target = clamp(page);
        if (target != currentPage) showPage(target);
    }

    // ------------------------------------------------------------------ loading

    private void startLoad() {
        int w = frameW();
        int h = frameH();
        if (contentFrame.getWidth() <= 0 || contentFrame.getHeight() <= 0) {
            contentFrame.post(this::startLoad);
            return;
        }
        showLoading(true);
        final int generation = ++loadGeneration;
        bg.execute(() -> {
            try {
                ReadingSession loaded = book.isPdf() ? PdfSession.open(this, book)
                        : EpubSession.open(this, book);
                loaded.setTheme(theme);
                loaded.setPdfFullTheme(prefs.pdfFullTheme);
                loaded.layout(prefs, theme, w, h);
                int page = loaded.initialPage();
                float fraction = loaded.initialFraction();
                runOnUiThread(() -> {
                    if (generation != loadGeneration || isFinishing() || isDestroyed()) {
                        loaded.close();
                        return;
                    }
                    onLoaded(loaded, page, fraction, w, h);
                });
            } catch (Throwable t) {
                runOnUiThread(() -> {
                    if (generation != loadGeneration || isFinishing() || isDestroyed()) return;
                    showLoading(false);
                    Toast.makeText(this, R.string.open_failed, Toast.LENGTH_LONG).show();
                    finish();
                });
            }
        });
    }

    private void rememberLayout(int w, int h) {
        laidSize = prefs.sizeSp;
        laidLine = prefs.linePct;
        laidFont = prefs.font;
        laidFrameW = w;
        laidFrameH = h;
    }

    private void onLoaded(ReadingSession loaded, int page, float fraction, int w, int h) {
        session = loaded;
        rememberLayout(w, h);
        layoutRunning = false;
        layoutPending = false;
        layoutPendingQuiet = true;
        pageCount = loaded.pageCount();
        scrollMode = prefs.isScroll() && loaded.supportsViewMode();
        currentPage = clamp(page);
        currentFraction = Math.max(0f, Math.min(0.999f, fraction));
        buildAdapters();
        showLoading(false);
        applyModeVisibility();
        applyDirection();
        updateChrome();
        if (scrollMode) restoreScroll(currentPage, currentFraction);
        else pager.setCurrentItem(currentPage, false);
        persistPosition();
    }

    private int clamp(int page) {
        return Math.max(0, Math.min(Math.max(0, pageCount - 1), page));
    }

    private void buildAdapters() {
        if (session == null) return;
        pagerAdapter = new ReaderAdapter(session, false, frameH());
        pager.setAdapter(pagerAdapter);
        scrollAdapter = new ReaderAdapter(session, true, frameH());
        scrollList.setAdapter(scrollAdapter);
    }

    private void showLoading(boolean show) {
        loadingView.setVisibility(show ? View.VISIBLE : View.GONE);
        loadingView.setBackgroundColor(theme.bg);
        loadingSpinner.setIndicatorColor(theme.subtle);
        loadingText.setTextColor(theme.subtle);
    }

    private void refreshAdapters() {
        if (pagerAdapter != null) pagerAdapter.submitPageCount(session.pageCount());
        if (scrollAdapter != null) scrollAdapter.submitPageCount(session.pageCount());
    }

    private void setPagerPage(int page) {
        if (pager.getCurrentItem() == page) return;
        if (pager.getChildCount() > 0
                && pager.getChildAt(0) instanceof RecyclerView
                && ((RecyclerView) pager.getChildAt(0)).isComputingLayout()) {
            pager.post(() -> setPagerPage(page));
            return;
        }
        pager.setCurrentItem(page, false);
    }

    // ------------------------------------------------------------------ chrome

    private int chromeBookmarkState = -1;
    private int chromeBookmarkColor = 0;
    private ReadingSession chromeMenuSession;
    private int chromeMenuColor = 0;

    private void updateChrome() {
        if (session == null) return;
        if (!Objects.equals(topBar.getTitle(), book.title)) {
            topBar.setTitle(book.title);
        }

        if (chromeMenuSession != session || chromeMenuColor != theme.fg) {
            chromeMenuSession = session;
            chromeMenuColor = theme.fg;
            Menu menu = topBar.getMenu();
            MenuItem toc = menu.findItem(R.id.action_toc);
            if (toc != null) {
                toc.setVisible(session.supportsContents() && !session.contents().isEmpty());
                for (int i = 0; i < menu.size(); i++) tintMenuItem(menu.getItem(i), theme.fg);
            }
        }

        pageLabel.setText(session.pageLabel(currentPage));
        percentLabel.setText(session.percentLabel(currentPage));
        updatingSlider = true;
        slider.setEnabled(pageCount > 1);
        slider.setMax(Math.max(1, pageCount - 1));
        if (!sliderDragging) {
            slider.setProgress(Math.min(currentPage, Math.max(1, pageCount - 1)));
        }
        updatingSlider = false;
        updateBookmarkIcon();
    }

    private void updateBookmarkIcon() {
        MenuItem item = topBar.getMenu().findItem(R.id.action_bookmark);
        if (item == null || session == null) return;
        boolean marked = session.isBookmarked(currentPage);
        int state = marked ? 1 : 0;
        if (state == chromeBookmarkState && chromeBookmarkColor == theme.fg) return;
        chromeBookmarkState = state;
        chromeBookmarkColor = theme.fg;
        item.setIcon(marked ? R.drawable.ic_bookmark : R.drawable.ic_bookmark_border);
        item.setTitle(marked ? R.string.remove_bookmark : R.string.add_bookmark);
        tintMenuItem(item, theme.fg);
    }

    private void tintMenuItem(MenuItem item, int color) {
        Drawable icon = item.getIcon();
        if (icon != null) {
            icon = icon.mutate();
            DrawableCompat.setTint(icon, color);
            item.setIcon(icon);
        }
    }

    void setChromeVisible(boolean visible) {
        chromeVisible = visible;
        if (visible) {
            topBar.setVisibility(View.VISIBLE);
            bottomBar.setVisibility(View.VISIBLE);
        }
        topBar.animate()
                .alpha(visible ? 1f : 0f)
                .translationY(visible ? 0 : -topBar.getHeight() - Ui.dp(this, 24))
                .setDuration(180)
                .withEndAction(() -> {
                    if (!chromeVisible) topBar.setVisibility(View.INVISIBLE);
                })
                .start();
        bottomBar.animate()
                .alpha(visible ? 1f : 0f)
                .translationY(visible ? 0 : bottomBar.getHeight() + Ui.dp(this, 24))
                .setDuration(180)
                .withEndAction(() -> {
                    if (!chromeVisible) bottomBar.setVisibility(View.INVISIBLE);
                })
                .start();
    }

    // ------------------------------------------------------------------ navigation

    private int firstVisiblePage() {
        RecyclerView.LayoutManager manager = scrollList.getLayoutManager();
        if (!(manager instanceof LinearLayoutManager)) return RecyclerView.NO_POSITION;
        return ((LinearLayoutManager) manager).findFirstVisibleItemPosition();
    }

    private float scrollFraction() {
        if (!scrollMode) return 0f;
        int first = firstVisiblePage();
        if (first == RecyclerView.NO_POSITION) return 0f;
        View child = scrollList.getLayoutManager() == null ? null
                : scrollList.getLayoutManager().findViewByPosition(first);
        int height = (child != null && child.getHeight() > 0) ? child.getHeight() : frameH();
        if (child == null || height <= 0) return 0f;
        return Math.max(0f, Math.min(0.999f, -child.getTop() / (float) height));
    }

    private void restoreScroll(int page, float fraction) {
        RecyclerView.LayoutManager manager = scrollList.getLayoutManager();
        if (!(manager instanceof LinearLayoutManager)) return;
        int target = clamp(page);
        float clampedFraction = Math.max(0f, Math.min(0.999f, fraction));
        int offset = 0;
        if (clampedFraction > 0.0005f) {
            int refH = session != null
                    ? session.scrollPageHeight(target, frameW(), frameH())
                    : frameH();
            int maxOff = Math.max(0, refH - 1);
            offset = -Math.min(maxOff, Math.round(clampedFraction * refH));
        }
        ((LinearLayoutManager) manager).scrollToPositionWithOffset(target, offset);
    }

    private void turnPage(int delta) {
        if (session == null) return;
        if (scrollMode) {
            int dy = (int) (frameH() * 0.85f);
            navTicks++;
            session.noteNavigation();
            scrollList.stopScroll();
            scrollGlide.addStep(delta > 0 ? dy : -dy);
            return;
        }
        int prev = currentPage;
        int target = clamp(prev + delta);
        if (target != prev) {
            boolean rtl = prefs.isRtl() && session.supportsViewMode();
            int dirSign = rtl ? -1 : 1;
            currentPage = target;
            currentFraction = 0f;
            navTicks++;
            session.noteNavigation();
            RecyclerView pagerRv = pagerList();
            int w = pager.getWidth();
            if (pagerRv == null || w <= 0) {
                pager.setCurrentItem(currentPage, false);
            } else {
                pagerRv.stopScroll();
                pageGlide.addStep((target - prev) * dirSign * w);
            }
            updateChrome();
            persistPosition();
        }
    }

    private void flushAnimations() {
        scrollGlide.flush();
        pageGlide.flush();
        snapPagerToCurrentPage();
    }

    private void snapPagerToCurrentPage() {
        if (pager == null || scrollMode) return;
        if (pager.getCurrentItem() != currentPage) {
            pager.setCurrentItem(currentPage, false);
            return;
        }
        RecyclerView rv = pagerList();
        if (rv == null || rv.getScrollState() != RecyclerView.SCROLL_STATE_IDLE) return;
        RecyclerView.LayoutManager lm = rv.getLayoutManager();
        View active = lm == null ? null : lm.findViewByPosition(currentPage);
        if (active == null) {
            pager.setCurrentItem(currentPage, false);
        } else if (active.getLeft() != 0) {
            rv.scrollBy(active.getLeft(), 0);
        }
    }

    private void cancelAnimations() {
        pageGlide.cancel();
        scrollGlide.cancel();
    }

    /** Unified hardware-VSYNC additive glide animator for both paged and scroll steps. */
    private static final class GlideAnimator implements Choreographer.FrameCallback {
        private final IntUnaryOperator scrollStep;
        private final Runnable onSettled;
        private boolean running;
        private long startNs;
        private int totalPx;
        private int appliedPx;

        GlideAnimator(IntUnaryOperator scrollStep, Runnable onSettled) {
            this.scrollStep = scrollStep;
            this.onSettled = onSettled;
        }

        boolean isRunning() {
            return running;
        }

        void addStep(int stepPx) {
            int remaining = running ? (totalPx - appliedPx) : 0;
            totalPx = remaining + stepPx;
            appliedPx = 0;
            Choreographer.getInstance().removeFrameCallback(this);
            if (totalPx == 0) {
                running = false;
                onSettled.run();
                return;
            }
            running = true;
            startNs = -1L;
            Choreographer.getInstance().postFrameCallback(this);
        }

        void cancel() {
            running = false;
            Choreographer.getInstance().removeFrameCallback(this);
            totalPx = 0;
            appliedPx = 0;
        }

        void flush() {
            if (!running) return;
            int rem = totalPx - appliedPx;
            cancel();
            if (rem != 0) scrollStep.applyAsInt(rem);
            onSettled.run();
        }

        @Override
        public void doFrame(long frameTimeNanos) {
            if (!running) return;
            if (startNs < 0L) startNs = frameTimeNanos - FIRST_FRAME_LEAD_NS;
            long elapsedNs = Math.max(0L, frameTimeNanos - startNs);
            boolean done = elapsedNs >= TAP_ANIM_NS;
            float inv = 1f - Math.max(0f, Math.min(1f, elapsedNs / (float) TAP_ANIM_NS));
            float u = done ? 1f : (1f - inv * inv * inv);
            int want = Math.round(totalPx * u) - appliedPx;
            if (want != 0) {
                appliedPx += want;
                int actual = scrollStep.applyAsInt(want);
                if ((want > 0 && actual < want) || (want < 0 && actual > want)) {
                    cancel();
                    onSettled.run();
                    return;
                }
            }
            if (done) {
                cancel();
                onSettled.run();
            } else {
                Choreographer.getInstance().postFrameCallback(this);
            }
        }
    }

    void goToPage(int page) {
        if (session == null) return;
        showPage(clamp(page));
        persistPosition();
    }

    private void showPage(int page) {
        flushAnimations();
        currentPage = page;
        currentFraction = 0f;
        session.noteNavigation();
        if (scrollMode) restoreScroll(currentPage, 0f);
        else setPagerPage(currentPage);
        updateChrome();
    }

    private void syncScrollPosition() {
        if (!scrollMode || layoutRunning || scrollList == null
                || scrollList.getChildCount() == 0 || scrollList.isLayoutRequested()) {
            return;
        }
        if (laidFrameW > 0 && laidFrameH > 0
                && (frameW() != laidFrameW || frameH() != laidFrameH)) {
            return;
        }
        int first = firstVisiblePage();
        if (first != RecyclerView.NO_POSITION) {
            currentPage = clamp(first);
            currentFraction = scrollFraction();
        }
    }

    void persistPosition() {
        if (session == null) return;
        syncScrollPosition();
        session.persistPosition(currentPage, scrollMode ? currentFraction : 0f, frameH());
    }

    private void toggleBookmark() {
        if (session == null) return;
        boolean wasMarked = session.isBookmarked(currentPage);
        session.toggleBookmark(currentPage);
        updateBookmarkIcon();
        Snackbar.make(root, wasMarked ? R.string.bookmark_removed : R.string.bookmark_added,
                Snackbar.LENGTH_SHORT).show();
    }

    void openNav(int tab) {
        if (session == null) return;
        new NavSheet(this, session, currentPage, this::goToPage, this::updateBookmarkIcon)
                .openOn(tab).show();
    }

    private void openAppearance() {
        if (session == null) return;
        new AppearanceSheet(this, prefs, session.supportsTypography(),
                session.supportsViewMode(), new AppearanceSheet.Callback() {
                    @Override
                    public void onAppearanceChanged(boolean needsRelayout) {
                        ReaderActivity.this.onAppearanceChanged(needsRelayout);
                    }

                    @Override
                    public void onAppearancePreview() {
                        ReaderActivity.this.onAppearancePreview();
                    }
                }).show();
    }

    void onAppearanceChanged(boolean needsRelayout) {
        flushAnimations();
        prefs = new ReaderPrefs(this);
        if (session != null && session.needsRelayout(prefs)) {
            needsRelayout = true;
        }
        if (session != null && session.supportsTypography()
                && (prefs.sizeSp != laidSize || prefs.linePct != laidLine
                || !prefs.font.equals(laidFont))) {
            needsRelayout = true;
        }
        if (previewing) {
            previewing = false;
            previewQueued = false;
            if (needsRelayout) {
                releaseAnchor = true;
            } else if (session != null) {
                session.clearAnchor();
            }
        }
        theme = ReaderTheme.from(prefs.theme);
        if (session != null) {
            session.setTheme(theme);
            session.setPdfFullTheme(prefs.pdfFullTheme);
        }
        boolean wasScroll = scrollMode;
        scrollMode = prefs.isScroll() && session != null && session.supportsViewMode();
        applyTheme();
        applyModeVisibility();
        applyDirection();
        updateKeepScreenOn();
        if (session == null) return;

        if (needsRelayout) {
            relayoutAsync();
            return;
        }
        if (wasScroll != scrollMode) {
            currentFraction = 0f;
            if (scrollMode) restoreScroll(currentPage, 0f);
            else setPagerPage(currentPage);
        }
        applyThemeToMountedPages();
        refreshAdapters();
        updateChrome();
    }

    private void applyThemeToMountedPages() {
        for (RecyclerView list : new RecyclerView[]{pagerList(), scrollList}) {
            if (list == null) continue;
            for (int i = 0; i < list.getChildCount(); i++) {
                View child = list.getChildAt(i);
                if (child instanceof PageView) {
                    ((PageView) child).setTheme(theme);
                } else if (child instanceof ZoomImageView) {
                    ((ZoomImageView) child).setTheme(theme, prefs.pdfFullTheme);
                }
            }
        }
    }

    void onAppearancePreview() {
        prefs = new ReaderPrefs(this);
        if (session == null) return;
        if (prefs.sizeSp == laidSize && prefs.linePct == laidLine
                && prefs.font.equals(laidFont)) {
            return;
        }
        if (!previewing) {
            previewing = true;
            firstPreview = true;
            session.pinAnchor(currentPage, scrollMode ? currentFraction : 0f, frameH());
        }
        previewQueued = true;
        if (previewScheduled) return;
        previewScheduled = true;
        long delay = firstPreview ? 0 : PREVIEW_INTERVAL_MS;
        firstPreview = false;
        ui.postDelayed(() -> {
            previewScheduled = false;
            if (!previewQueued) return;
            previewQueued = false;
            if (isFinishing() || isDestroyed()) return;
            requestRelayout(true);
        }, delay);
    }

    private void relayoutAsync() {
        requestRelayout(session != null && !session.supportsTypography());
    }

    private void requestRelayout(boolean quiet) {
        if (isFinishing() || isDestroyed() || session == null) return;
        if (layoutRunning) {
            layoutPending = true;
            layoutPendingQuiet &= quiet;
            return;
        }
        layoutRunning = true;
        syncScrollPosition();
        final int generation = ++loadGeneration;
        final int nav = navTicks;
        final int page = currentPage;
        final float fraction = scrollMode ? currentFraction : 0f;
        final int w = frameW();
        final int h = frameH();
        final ReadingSession target = session;
        if (!quiet) showLoading(true);
        try {
            bg.execute(() -> runRelayout(target, generation, nav, page, fraction, w, h, quiet));
        } catch (RejectedExecutionException e) {
            layoutRunning = false;
            showLoading(false);
        }
    }

    private void runRelayout(ReadingSession target, int generation, int nav, int page,
                             float fraction, int w, int h, boolean quiet) {
        try {
            prefs = new ReaderPrefs(this);
            int mapped = target.relayout(prefs, w, h, page, fraction);
            runOnUiThread(() -> {
                layoutRunning = false;
                if (isFinishing() || isDestroyed()) return;
                if (generation != loadGeneration || target != session) {
                    showLoading(false);
                    return;
                }
                if (nav != navTicks) {
                    session.clearAnchor();
                    requestRelayout(quiet);
                    return;
                }
                flushAnimations();
                pageCount = target.pageCount();
                rememberLayout(w, h);
                if (target.supportsTypography()
                        && scrollAdapter != null && scrollAdapter.itemHeightPx() != h) {
                    buildAdapters();
                } else {
                    refreshAdapters();
                }
                float nextFraction = target.supportsTypography()
                        ? 0f
                        : Math.max(0f, Math.min(0.999f, fraction));
                currentPage = clamp(mapped);
                currentFraction = nextFraction;
                if (scrollMode) restoreScroll(currentPage, nextFraction);
                else setPagerPage(currentPage);
                showLoading(false);
                updateChrome();
                if (releaseAnchor) {
                    releaseAnchor = false;
                    session.clearAnchor();
                }
                if (!previewing) persistPosition();
                runPendingLayout();
            });
        } catch (Throwable t) {
            runOnUiThread(() -> {
                layoutRunning = false;
                if (isFinishing() || isDestroyed()) return;
                if (generation == loadGeneration) showLoading(false);
                runPendingLayout();
            });
        }
    }

    private void runPendingLayout() {
        if (!layoutPending) return;
        layoutPending = false;
        boolean quiet = layoutPendingQuiet;
        layoutPendingQuiet = true;
        requestRelayout(quiet);
    }

    private void applyTheme() {
        getWindow().setBackgroundDrawable(new ColorDrawable(theme.bg));
        root.setBackgroundColor(theme.bg);
        contentFrame.setBackground(null);
        pager.setBackground(null);
        scrollList.setBackground(null);
        topBar.setBackground(makeScrim(true));
        bottomBar.setBackground(makeScrim(false));
        topBar.setTitleTextColor(theme.fg);

        Drawable navigation = topBar.getNavigationIcon();
        if (navigation != null) {
            navigation = navigation.mutate();
            DrawableCompat.setTint(navigation, theme.fg);
            topBar.setNavigationIcon(navigation);
        }
        Menu menu = topBar.getMenu();
        for (int i = 0; i < menu.size(); i++) tintMenuItem(menu.getItem(i), theme.fg);

        pageLabel.setTextColor(theme.subtle);
        percentLabel.setTextColor(theme.subtle);
        if (slider.getProgressDrawable() instanceof LayerDrawable) {
            LayerDrawable track = (LayerDrawable) slider.getProgressDrawable().mutate();
            Drawable rest = track.findDrawableByLayerId(android.R.id.background);
            Drawable read = track.findDrawableByLayerId(android.R.id.progress);
            if (rest != null) rest.setTint(Ui.alpha(theme.fg, 0.18f));
            if (read != null) read.setTint(Ui.alpha(theme.fg, 0.9f));
            slider.setProgressDrawable(track);
        }
        if (slider.getThumb() != null) {
            slider.getThumb().mutate().setTintList(ColorStateList.valueOf(theme.fg));
        }

        loadingView.setBackgroundColor(theme.bg);
        loadingText.setTextColor(theme.subtle);
        loadingSpinner.setIndicatorColor(theme.subtle);
        Ui.setLightSystemBars(this, !theme.dark);
    }

    private GradientDrawable makeScrim(boolean top) {
        int solid = theme.bg;
        int clear = Ui.alpha(theme.bg, 0f);
        return new GradientDrawable(
                top ? GradientDrawable.Orientation.TOP_BOTTOM
                        : GradientDrawable.Orientation.BOTTOM_TOP,
                new int[]{solid, solid, clear});
    }

    private void applyModeVisibility() {
        boolean scroll = scrollMode && session != null && session.supportsViewMode();
        pager.setVisibility(scroll ? View.GONE : View.VISIBLE);
        scrollList.setVisibility(scroll ? View.VISIBLE : View.GONE);
    }

    private void applyDirection() {
        boolean rtl = prefs.isRtl() && session != null && session.supportsViewMode();
        pager.setLayoutDirection(rtl ? View.LAYOUT_DIRECTION_RTL : View.LAYOUT_DIRECTION_LTR);
    }

    private void updateKeepScreenOn() {
        if (prefs.keepScreenOn) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        } else {
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
    }

    // ------------------------------------------------------------------ touch

    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        boolean isTap = false;
        float tapX = 0f;
        float tapY = 0f;
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = ev.getX();
                downY = ev.getY();
                maxPointers = ev.getPointerCount();
                break;
            case MotionEvent.ACTION_POINTER_DOWN:
                maxPointers = Math.max(maxPointers, ev.getPointerCount());
                cancelAnimations();
                break;
            case MotionEvent.ACTION_MOVE:
                if (maxPointers > 1 || Math.hypot(ev.getX() - downX, ev.getY() - downY)
                        >= Ui.dp(this, 18)) {
                    cancelAnimations();
                }
                break;
            case MotionEvent.ACTION_UP:
                float dx = ev.getX() - downX;
                float dy = ev.getY() - downY;
                long elapsed = ev.getEventTime() - ev.getDownTime();
                if (session != null && maxPointers == 1 && elapsed < 500
                        && Math.hypot(dx, dy) < Ui.dp(this, 18)) {
                    isTap = true;
                    tapX = ev.getX();
                    tapY = ev.getY();
                }
                break;
            default:
                break;
        }
        boolean handled = super.dispatchTouchEvent(ev);
        if (isTap) handleTap(tapX, tapY);
        return handled;
    }

    private void handleTap(float x, float y) {
        if (isInside(topBar, x, y) || isInside(bottomBar, x, y)) return;
        float width = contentFrame.getWidth();
        if (width <= 0) return;
        if (resetVisibleZoom()) return;
        if (scrollMode) {
            if (x < width * 0.30f) turnPage(-1);
            else if (x > width * 0.70f) turnPage(1);
            else setChromeVisible(!chromeVisible);
            return;
        }
        boolean rtl = prefs.isRtl() && session != null && session.supportsViewMode();
        if (x < width * 0.30f) turnPage(rtl ? 1 : -1);
        else if (x > width * 0.70f) turnPage(rtl ? -1 : 1);
        else setChromeVisible(!chromeVisible);
    }

    private boolean resetVisibleZoom() {
        RecyclerView list = scrollMode ? scrollList : pagerList();
        if (list == null) return false;
        for (int i = 0; i < list.getChildCount(); i++) {
            View item = list.getChildAt(i);
            if (item instanceof ZoomablePage && ((ZoomablePage) item).isZoomed()) {
                ((ZoomablePage) item).resetZoom();
                return true;
            }
        }
        return false;
    }

    private RecyclerView pagerList() {
        View child = pager.getChildAt(0);
        return child instanceof RecyclerView ? (RecyclerView) child : null;
    }

    private boolean isInside(View view, float x, float y) {
        if (view.getVisibility() != View.VISIBLE || view.getAlpha() < 0.5f) return false;
        int[] loc = new int[2];
        view.getLocationInWindow(loc);
        return x >= loc[0] && x <= loc[0] + view.getWidth()
                && y >= loc[1] && y <= loc[1] + view.getHeight();
    }

    // ------------------------------------------------------------------ lifecycle

    void setSliderDragging(boolean dragging) {
        sliderDragging = dragging;
    }

    @Override
    protected void onPause() {
        flushAnimations();
        persistPosition();
        super.onPause();
    }

    @Override
    public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        if (session != null) session.trimMemory(level);
    }

    @Override
    protected void onDestroy() {
        loadGeneration++;
        ui.removeCallbacksAndMessages(null);
        cancelAnimations();
        if (session != null) {
            session.close();
            session = null;
        }
        if (bg != null) bg.shutdownNow();
        super.onDestroy();
    }

    // ------------------------------------------------------------------ accessors for tests

    public ReadingSession getSession() {
        return session;
    }

    public int getCurrentPage() {
        return currentPage;
    }

    void turnPageForwardForTest() {
        turnPage(1);
    }

    public boolean isChromeVisible() {
        return chromeVisible;
    }
}
