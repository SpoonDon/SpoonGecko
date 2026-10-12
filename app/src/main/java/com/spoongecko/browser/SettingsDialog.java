package com.spoongecko.browser;

import android.app.Activity;
import android.app.AlertDialog;

/**
 * Settings menu. Currently hosts the OEM survival settings (battery
 * optimization, autostart). More items will be added later — search engine,
 * homepage, dark mode, cookie policy, etc.
 */
public final class SettingsDialog {

    private static final int ITEM_BATTERY = 0;
    private static final int ITEM_AUTOSTART = 1;

    private SettingsDialog() {}

    public static void show(final Activity activity) {
        final CharSequence[] items = new CharSequence[] {
                "Battery optimization",
                "Autostart settings"
        };

        new AlertDialog.Builder(activity)
                .setTitle("Settings")
                .setItems(items, (dialog, which) -> {
                    switch (which) {
                        case ITEM_BATTERY:
                            OemHelper.requestIgnoreBatteryOptimizations(activity);
                            break;
                        case ITEM_AUTOSTART:
                            OemHelper.openAutostartSettings(activity);
                            break;
                    }
                })
                .setNegativeButton("Close", null)
                .show();
    }
}
