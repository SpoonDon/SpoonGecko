# Round 4: page translation

Apply everything together. `PopupsController.Host` gains two methods, so `MainActivity` will not
compile without its edits.

## Add these files (same folders as before)

| File | Folder |
|---|---|
| `PageTranslator.java` | `app/src/main/java/com/spoongecko/browser/` |
| `TranslateDialogs.java` | same |
| `TranslationFlow.java` | same |
| `TranslationLanguages.java` | same |
| `view_translate_banner.xml` | `app/src/main/res/layout/` |
| `dialog_translate.xml` | same |
| `dialog_language_picker.xml` | same |
| `dialog_language_rules.xml` | same |
| `dialog_choice.xml` | same |

All are new files: nothing existing is replaced. No manifest, `build.gradle`, drawable or
dependency changes (GeckoView already contains the translation engine).

---

## PopupsController.java: 2 edits

### Edit P1: two new methods in the `Host` interface

Find the line `void onPdfMenuAction();` and add directly under it:
```java
        /** [TRANSLATE] Menu entry for page translation, or null when this device cannot translate. */
        String translateMenuLabel();
        /** [TRANSLATE] The user chose that entry. */
        void onTranslateMenuAction();
```

### Edit P2: the menu entry (in `showMenu`)

Find the line `if (pdfLabel != null) addMenuItem(container, pdfLabel, false, host::onPdfMenuAction);`
and add directly under it:
```java
        final String translateLabel = host.translateMenuLabel();   // [TRANSLATE]
        if (translateLabel != null) addMenuItem(container, translateLabel, false, host::onTranslateMenuAction);
```

---

## MainActivity.java: 8 edits

### Edit M1: let the engine offer translations (in `onCreate`)

Find the line `PdfSupport.enableViewer();   // [PDF] ...` and add directly under it:
```java
        PageTranslator.enableOffers(sRuntime);   // [TRANSLATE] the engine may offer to translate pages
```

### Edit M2: create the translator (in `onCreate`)

Find the line `historyDialog = new HistoryDialog(this, history);   // [HISTORY]` and add directly under it:
```java

        // [TRANSLATE] Page translation. Its banner floats over the page; bound before any tab is attached.
        PageTranslator.get().bind(this, (FrameLayout) findViewById(R.id.content_container), tabManager);
```
(`FrameLayout` is already imported in this file.)

### Edit M3: every tab gets it (in `attachDelegates`)

In `attachDelegates(Tab tab)`, add one line as the **last statement**, after the
`tab.session.setSelectionActionDelegate(...)` statement:
```java
        PageTranslator.get().attach(tab);   // [TRANSLATE]
```

### Edit M4: follow the tab on screen (in `onActiveTabChanged`)

Directly after `if (tab == null) return;` (the first line of `onActiveTabChanged`) add:
```java
        PageTranslator.get().onActiveTabChanged(tab);   // [TRANSLATE]
```

### Edit M5: a new page starts (in `onPageStarted`)

As the **first statement** of `onPageStarted(Tab tab, String url)` add:
```java
        PageTranslator.get().onPageStarted(tab);   // [TRANSLATE] what was known about the old page is void
```

### Edit M6: hide the banner over fullscreen video (two places)

In `setFullScreen(boolean fullScreen)`, directly after `screen.setFullScreenMode(fullScreen);` add:
```java
        PageTranslator.get().setFullScreen(fullScreen);   // [TRANSLATE]
```
In `leaveFullScreen()`, directly after `screen.setFullScreenMode(false);` add:
```java
        PageTranslator.get().setFullScreen(false);   // [TRANSLATE]
```

### Edit M7: the menu entry's two methods

Find `@Override public void onSessionGone(Tab tab) {` and add this block **directly above** it:
```java
    // [TRANSLATE] "Translate..." in the menu
    @Override public String translateMenuLabel() { return PageTranslator.get().menuLabel(); }
    @Override public void onTranslateMenuAction() { PageTranslator.get().onMenuAction(); }

```

### Edit M8: clean up with the activity (in `onDestroy`)

Find the line `if (prompts != null) prompts.dismissAll();   // [UPLOAD] ...` and add directly under it:
```java
        PageTranslator.get().unbind();   // [TRANSLATE]
```
