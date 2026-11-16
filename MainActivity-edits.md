# MainActivity.java: 6 small edits

Open `app/src/main/java/com/spoongecko/browser/MainActivity.java` and make these six edits.
Nothing is deleted except one 2-line statement (edit 3). All other code stays as it is.

---

## Edit 1: add a field

Find:
```java
    private BrowserDelegates delegates;
    private FindInPageController findInPage;   // [FIND] the find-in-page bar
```
Add one line directly under it:
```java
    private TabSwitcher tabSwitcher;           // [TABS] Chrome-style tab switcher
```

---

## Edit 2: create it in `onCreate`

Find (in `onCreate`):
```java
        delegates = new BrowserDelegates(this, this, prompts);
```
Add directly under it:
```java

        // [TABS] Chrome-style tab switcher, opened by the tab button.
        tabSwitcher = new TabSwitcher(this, tabManager, geckoView, new TabSwitcher.Host() {
            @Override public void openNewTab() { newTab(); }
            @Override public void tabsEdited() { persistAllTabs(); }
            @Override public boolean isHomePanelShown() { return screen.isHomePanelVisible(); }
        });
```

---

## Edit 3: the tab button opens it (in `setupInputHandlers`)

Replace these two lines:
```java
        tabButton.setOnClickListener(v -> dialogs.showTabList(
                idx -> tabManager.switchTo(idx), this::newTab));
```
with:
```java
        tabButton.setOnClickListener(v -> tabSwitcher.open());   // [TABS]
```
(`BrowserDialogs.showTabList` stays in the code, unused. Leave it.)

---

## Edit 4: keep it in sync when tabs change (`onTabsChanged`)

Find:
```java
        updateTabCount();
        if (tabManager.count() == 0) newTab();
    }
```
(that is the end of `onTabsChanged`). Insert one line between the two statements:
```java
        updateTabCount();
        if (tabSwitcher != null) tabSwitcher.refresh();   // [TABS]
        if (tabManager.count() == 0) newTab();
    }
```

---

## Edit 5: keep the highlight in sync (`onActiveTabChanged`)

Find:
```java
    public void onActiveTabChanged(Tab tab) {
        if (tab == null) return;
```
Add one line after `if (tab == null) return;`:
```java
        if (tabSwitcher != null) tabSwitcher.refresh();   // [TABS]
```

---

## Edit 6: close it with the activity (`onDestroy`)

Find:
```java
        if (popups != null) popups.dismissAll();
```
Add directly under it:
```java
        if (tabSwitcher != null) tabSwitcher.dismiss();   // [TABS]
```

---

## New / changed files (copy as-is, same paths)

| Path | Status |
|---|---|
| `app/src/main/java/com/spoongecko/browser/TabSwitcher.java` | new |
| `app/src/main/java/com/spoongecko/browser/Tab.java` | one field added (`thumbnail`) |
| `app/src/main/res/layout/dialog_tab_switcher.xml` | new |
| `app/src/main/res/layout/item_tab_card.xml` | new |
| `app/src/main/res/drawable/bg_tab_card.xml` | new |
| `app/src/main/res/drawable/bg_tab_card_border.xml` | new |
| `app/src/main/res/drawable/bg_tab_card_border_active.xml` | new |

No changes to the manifest, `build.gradle`, or any other file. No new dependencies.
