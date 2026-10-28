package com.spoongecko.browser;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;

public class ThemeManager {
    public static final int DARK   = 0;
    public static final int AMOLED = 1;
    public static final int LIGHT  = 2;

    private static final String PREFS = "spoongecko_theme";
    private static final String KEY   = "theme";

    public static int get(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY, DARK);
    }
    public static void set(Context c, int t) {
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt(KEY, t).apply();
    }
    public static int next(int t) { return (t + 1) % 3; }
    public static String name(int t) {
        switch (t) {
            case AMOLED: return "AMOLED";
            case LIGHT:  return "Light";
            default:     return "Dark";
        }
    }

    // ---- colors ----

    public static int windowBg(int t) {
        switch (t) {
            case AMOLED: return 0xFF000000;
            case LIGHT:  return 0xFFF2F2F2;
            default:     return 0xFF111111;
        }
    }
    public static int textPrimary(int t) { return t == LIGHT ? 0xFF111111 : 0xFFFFFFFF; }
    public static int textMuted(int t)   { return t == LIGHT ? 0xFF666666 : 0x99FFFFFF; }
    public static int accent(int t)      { return t == LIGHT ? 0xFF0078D4 : 0xFF7CB7FF; }

    // ---- programmatic drawables (replaces static XML ones per theme) ----

    private static GradientDrawable base(Context c, int t, int radiusDp, boolean input) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.RECTANGLE);
        d.setCornerRadius(dp(c, radiusDp));
        if (t == LIGHT) {
            d.setColor(input ? 0xFFFFFFFF : 0xEEF0F0F0);
            d.setStroke(dp(c, 1), 0x22000000);
        } else {
            d.setColor(input ? 0x1AFFFFFF : 0xB3000000);
            d.setStroke(dp(c, 1), 0x33FFFFFF);
        }
        return d;
    }

    public static Drawable glassButton(Context c, int t) { return base(c, t, 14, false); }
    public static Drawable glassInput(Context c, int t)  { return base(c, t, 22, true);  }
    public static Drawable glassSearch(Context c, int t) { return base(c, t, 26, true);  }
    public static Drawable glassDialog(Context c, int t) { return base(c, t, 20, false); }

    public static Drawable toolbarBg(Context c, int t) {
        GradientDrawable d = new GradientDrawable(
                GradientDrawable.Orientation.BOTTOM_TOP,
                t == LIGHT
                        ? new int[]{0xFFF2F2F2, 0xFFF2F2F2}
                        : new int[]{0xCC000000, 0xFF000000});
        d.setShape(GradientDrawable.RECTANGLE);
        d.setCornerRadii(new float[]{0, 0, 0, 0, dp(c, 18), dp(c, 18), dp(c, 18), dp(c, 18)});
        return d;
    }

    private static int dp(Context c, int v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }
}
