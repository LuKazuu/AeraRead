package com.aera.read.util;

import android.app.Activity;
import android.content.ComponentCallbacks2;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Color;
import android.os.Build;
import android.util.LruCache;
import android.util.TypedValue;
import android.view.View;
import android.view.Window;

import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;

import com.aera.read.R;
import com.google.android.material.bottomsheet.BottomSheetDialog;

public final class Ui {

    private Ui() {
    }

    public static int dp(Context c, float v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }

    public static float dpf(Context c, float v) {
        return v * c.getResources().getDisplayMetrics().density;
    }

    public static float sp(Context c, float v) {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v,
                c.getResources().getDisplayMetrics());
    }

    /** Resolves a theme attribute to a colour, with a hard fallback for odd themes. */
    public static int colorAttr(Context c, int attr) {
        TypedValue tv = new TypedValue();
        if (c.getTheme().resolveAttribute(attr, tv, true)) {
            if (tv.type >= TypedValue.TYPE_FIRST_COLOR_INT
                    && tv.type <= TypedValue.TYPE_LAST_COLOR_INT) {
                return tv.data;
            }
            if (tv.resourceId != 0) return c.getColor(tv.resourceId);
        }
        return Color.BLACK;
    }

    public static int alpha(int color, float fraction) {
        int a = Math.round(Color.alpha(color) * Math.max(0f, Math.min(1f, fraction)));
        return (color & 0x00FFFFFF) | (a << 24);
    }

    public static boolean isNightUi(Context c) {
        return (c.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
    }

    /** Enables unified edge-to-edge system bar rendering across Android 9 through Android 17. */
    @SuppressWarnings("deprecation")
    public static void enableEdgeToEdge(Activity activity) {
        Window window = activity.getWindow();
        WindowCompat.setDecorFitsSystemWindows(window, false);
        window.setStatusBarColor(Color.TRANSPARENT);
        window.setNavigationBarColor(Color.TRANSPARENT);
        if (Build.VERSION.SDK_INT >= 29) {
            window.setNavigationBarContrastEnforced(false);
        }
    }

    public static void setLightSystemBars(Activity activity, boolean lightIcons) {
        Window window = activity.getWindow();
        WindowInsetsControllerCompat controller =
                WindowCompat.getInsetsController(window, window.getDecorView());
        controller.setAppearanceLightStatusBars(lightIcons);
        controller.setAppearanceLightNavigationBars(lightIcons);
    }

    /** Unified LruCache trimming under Android memory pressure. */
    @SuppressWarnings("deprecation")
    public static void trimCache(LruCache<?, ?> cache, int level) {
        if (cache == null) return;
        if (level >= ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN
                || level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL) {
            cache.evictAll();
        } else if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) {
            cache.trimToSize(cache.maxSize() / 2);
        }
    }

    /** Inflates a Material 3 bottom sheet and wires navigation-bar bottom insets cleanly. */
    public static BottomSheetDialog createSheet(Context ctx, int layoutRes, int baseBottomDp) {
        BottomSheetDialog dialog = new BottomSheetDialog(ctx);
        dialog.setContentView(layoutRes);
        View root = dialog.findViewById(R.id.sheet_root);
        if (root != null) {
            final int extraBottom = dp(ctx, baseBottomDp);
            ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
                Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
                v.setPadding(v.getPaddingLeft(), v.getPaddingTop(), v.getPaddingRight(),
                        extraBottom + bars.bottom);
                return insets;
            });
        }
        return dialog;
    }
}
