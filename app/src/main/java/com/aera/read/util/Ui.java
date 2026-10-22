package com.aera.read.util;

import android.app.Activity;
import android.content.Context;
import android.graphics.Color;
import android.util.TypedValue;
import android.view.Window;

import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsControllerCompat;

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

    public static void setLightSystemBars(Activity activity, boolean lightIcons) {
        Window window = activity.getWindow();
        WindowInsetsControllerCompat controller =
                WindowCompat.getInsetsController(window, window.getDecorView());
        controller.setAppearanceLightStatusBars(lightIcons);
        controller.setAppearanceLightNavigationBars(lightIcons);
    }
}
