# Round 3: file attach fix + PDF viewer / downloader

Apply everything together. `PopupsController.Host` gains two methods, so `MainActivity` will not
compile without its edits.

## Replace / add these files (same paths as before)

| File | Status |
|---|---|
| `java/.../BrowserPrompts.java` | **replace** (file inputs now go through `FilePicker`) |
| `java/.../FilePicker.java` | new |
| `java/.../UploadFiles.java` | new |
| `java/.../PdfSupport.java` | new |

No manifest, `build.gradle`, resource or dependency changes. **No storage permission is added**: see
the explanation in the chat reply.

---

## PopupsController.java: 2 edits

### Edit P1: two new methods in the `Host` interface

Find:
```java
        /** [DOWNLOAD] Show the system list of downloaded files. */
        void onShowDownloads();
```
Add directly under it:
```java
        /** [PDF] Menu entry for the page on screen ("Download PDF" / "Save page as PDF"), or null for none. */
        String pdfMenuLabel();
        /** [PDF] The user chose that entry. */
        void onPdfMenuAction();
```

### Edit P2: the menu entry (in `showMenu`)

Find:
```java
        addMenuItem(container, "Share", false, host::onShare);
```
Add directly under it:
```java
        final String pdfLabel = host.pdfMenuLabel();   // [PDF]
        if (pdfLabel != null) addMenuItem(container, pdfLabel, false, host::onPdfMenuAction);
```

---

## MainActivity.java: 3 edits

### Edit M1: turn the PDF viewer on (in `onCreate`)

Find:
```java
        applySelectionColor();   // [SELECT] visible text-selection highlight on dark pages
```
Add directly under it:
```java
        PdfSupport.enableViewer();   // [PDF] GeckoView ships its PDF viewer switched off
```

### Edit M2: the two new `Host` methods

Find:
```java
    @Override public void onSessionGone(Tab tab) {
```
Add this block **directly above** that line:
```java
    // ------------------------------------------------------------------
    // [PDF] "Download PDF" / "Save page as PDF" in the menu
    // ------------------------------------------------------------------

    /** The menu entry for the page on screen, or null when there is nothing to offer. */
    @Override public String pdfMenuLabel() {
        final Tab t = tabManager == null ? null : tabManager.getActiveTab();
        if (t == null || t.url == null || screen.isHomePanelVisible()) return null;
        final String lower = t.url.toLowerCase(java.util.Locale.ROOT);
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) return null;
        if (ErrorPages.isErrorTitle(t.title)) return null;
        return PdfSupport.looksLikePdfUrl(t.url) ? "Download PDF" : "Save page as PDF";
    }

    @Override public void onPdfMenuAction() {
        final Tab t = tabManager.getActiveTab();
        if (t == null || t.url == null || t.url.isEmpty()) return;
        if (PdfSupport.looksLikePdfUrl(t.url)) {
            onDownloadLink(t.url, null);   // the original file, through the normal downloader
        } else {
            PdfSupport.savePageAsPdf(this, sRuntime, t.session, t.title, t.url);
        }
    }

```

### Edit M3: close any open prompt with the activity (in `onDestroy`)

Find:
```java
        if (historyDialog != null) historyDialog.dismiss();   // [HISTORY]
```
Add directly under it:
```java
        if (prompts != null) prompts.dismissAll();   // [UPLOAD] cancels a file pick that is still waiting
```
(`BrowserPrompts.dismissAll()` has existed since the [M8] fix but was never called.)
