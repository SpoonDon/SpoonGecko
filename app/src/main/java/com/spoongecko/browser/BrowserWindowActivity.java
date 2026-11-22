package com.spoongecko.browser;

/**
 * [WINDOW] An additional browser window, opened by "New window" / "New incognito window".
 *
 * It is exactly the browser screen of MainActivity (same layout, tabs, menu, tab switcher,
 * extensions and engine), started as a task of its own so the system can show it as a separate
 * window (side by side in split screen, as a freeform window, or as another entry in the recent
 * apps). Its own tabs are independent of every other window's.
 *
 * The only difference is that it does not own the tabs saved on the device. Those belong to the
 * main window: a new window starts empty and does not write them, so the two windows can never
 * overwrite each other's tabs or open the same tabs twice. (Its tabs still survive Android
 * re-creating the window, as in any window; they are not kept across a restart of the app.)
 *
 * It is a class of its own, and not a second launch of MainActivity, because MainActivity is
 * singleTask (see the manifest): that is what makes a link from another app reach the browser
 * that is already open, and it also means there can only be one of it.
 */
public class BrowserWindowActivity extends MainActivity {

    @Override
    protected boolean ownsSavedTabs() {
        return false;
    }
}
