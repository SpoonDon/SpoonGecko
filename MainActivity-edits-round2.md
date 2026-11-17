# MainActivity.java: 6 edits (+1 optional)

These build on the edits you already made. **Apply them together with the new and replaced
files**. `TabSwitcher.Host` gained one method, so `MainActivity` will not compile without
edit 2.

---

## Edit 1: two new fields

Find:
```java
    private TabSwitcher tabSwitcher; // [TABS] Chrome-style tab switcher
```
Add directly under it:
```java
    private HistoryDialog historyDialog;   // [HISTORY] history with Newest / Oldest / Site order
    /** [TABS] Set once the last tab was closed from the tab switcher and the browser is exiting. */
    private boolean leavingWithNoTabs = false;
```

---

## Edit 2: in `onCreate`: implement the new `Host` method, create the history dialog

Find:
```java
            @Override public boolean isHomePanelShown() { return screen.isHomePanelVisible(); }
        });
```
Replace with:
```java
            @Override public boolean isHomePanelShown() { return screen.isHomePanelVisible(); }
            @Override public void closeAllTabsAndExit() { leaveWithNoTabs(); }
        });

        historyDialog = new HistoryDialog(this, history);   // [HISTORY]
```

---

## Edit 3: new method (put it right after `showExitConfirmation`)

Find the end of this method:
```java
    private void showExitConfirmation() {
        dialogs.showExitConfirmation(() -> {
            persistAllTabsNow();
            finishAndRemoveTask();
        });
    }
```
Add directly under it:
```java

    /**
     * [TABS] The last tab was closed from the tab switcher: close every tab and leave the browser
     * at once, with no question (showExitConfirmation is the question that Back asks).
     * The saved tab list is written empty, so the next start opens the home page instead of
     * bringing back the tab that was just closed.
     */
    private void leaveWithNoTabs() {
        if (destroying) return;
        leavingWithNoTabs = true;      // onTabsChanged() must not open a replacement tab
        tabManager.closeAll();
        persistAllTabsNow();
        finishAndRemoveTask();
    }
```

---

## Edit 4: `onTabsChanged`: do not open a replacement tab while leaving

Find:
```java
        if (tabManager.count() == 0) newTab();
```
(it appears once, in `onTabsChanged`). Replace with:
```java
        if (tabManager.count() == 0 && !leavingWithNoTabs) newTab();
```

---

## Edit 5: the menu's History opens the new dialog

Find:
```java
        dialogs.showHistory(this::navigateToUrl);
```
Replace with:
```java
        historyDialog.show(this::navigateToUrl);   // [HISTORY]
```
(`BrowserDialogs.showHistory` stays in the code, unused. Leave it.)

---

## Edit 6: `onDestroy`: close the history dialog with the activity

Find:
```java
        if (tabSwitcher != null) tabSwitcher.dismiss();   // [TABS]
```
Add directly under it:
```java
        if (historyDialog != null) historyDialog.dismiss();   // [HISTORY]
```

---

## Optional (edit 5 of last time that was not applied)

In `onActiveTabChanged`, after `if (tab == null) return;`, add:
```java
        if (tabSwitcher != null) tabSwitcher.refresh();   // [TABS]
```
It only matters when a page opens a new tab while the switcher is on screen; the blue outline
then follows it. Everything else works without it.

---

## Files

**Replace** (same path as before):

| File | What changed |
|---|---|
| `java/.../TabSwitcher.java` | last tab exits; "Close all" uses the app's own dialog |
| `java/.../HistoryStore.java` | ranked suggestions, `Entry` has date + visit count, `oldest()` |
| `java/.../SuggestionsController.java` | length and focus guards, tidier address display |

**New:**

| File | Purpose |
|---|---|
| `java/.../SuggestionRanker.java` | query clean-up, LIKE escaping, ranking, duplicate removal |
| `java/.../HistoryRows.java` | history ordering and day / site sections |
| `java/.../HistoryDialog.java` | the history dialog with sort chips |
| `res/layout/dialog_confirm.xml` | confirmation dialog in the exit-dialog style |
| `res/layout/dialog_history.xml` | history dialog layout |
| `res/layout/row_history.xml` | a history line (title, address, time) |
| `res/layout/row_history_header.xml` | a section heading |
| `res/drawable/bg_chip.xml`, `bg_chip_selected.xml` | the sort chips |

No manifest, `build.gradle` or dependency changes.
