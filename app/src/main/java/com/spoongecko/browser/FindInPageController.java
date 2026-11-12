package com.spoongecko.browser;

import android.app.Activity;
import android.content.Context;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.mozilla.geckoview.GeckoResult;
import org.mozilla.geckoview.GeckoSession;

/**
 * [FIND] The "Find in page" bar.
 *
 * A compact strip between the toolbar and the page: a text field, a match
 * counter and three buttons (previous match, next match, close). Everything
 * search-related is delegated to GeckoView's SessionFinder, which owns the
 * matches and the highlighting; this class only feeds it strings and shows
 * the numbers it returns.
 *
 * Placement: the bar is added to the root layout just above the content
 * container, the same place the suggestions dropdown hangs from the other
 * side. The window never resizes (SYSTEM_UI_FLAG_LAYOUT_STABLE, the IME
 * inset is applied to the content container by ScreenController), so the
 * bar stays visible while the keyboard is open.
 *
 * Lifecycle: the bar belongs to the page it was opened on. MainActivity
 * closes it when the active tab changes or a page goes fullscreen, and
 * the back button closes it before the page itself is navigated. Closing
 * clears the highlighting on the session that was searched, whichever tab
 * is active by then.
 */
public final class FindInPageController {

    /** Supplies the tab whose page is currently shown (null = none). */
    public interface Host {
        Tab activeSearchTab();
    }

    private final Activity activity;
    private final Host host;

    private final LinearLayout bar;
    private final EditText input;
    private final TextView counter;
    private final ImageButton upButton;
    private final ImageButton downButton;

    /** The session the last search ran on — close() clears the matches there,
     * even if another tab has become active in the meantime. */
    private GeckoSession searchedSession;

    public FindInPageController(Activity activity, Host host) {
        this.activity = activity;
        this.host = host;

        bar = new LinearLayout(activity);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        // Same solid shade as the suggestions list: another full-width strip
        // that hangs directly under the toolbar.
        bar.setBackgroundColor(0xFF111111);
        int pad = dp(6);
        bar.setPadding(pad, pad, pad, dp(7));

        input = new EditText(activity);
        input.setBackgroundResource(R.drawable.bg_glass_input);
        input.setHint("Find in page");
        input.setHintTextColor(0x66FFFFFF);
        input.setTextColor(0xFFFFFFFF);
        input.setTextSize(14);
        input.setMaxLines(1);
        input.setSingleLine(true);
        input.setPadding(dp(14), 0, dp(14), 0);
        input.setInputType(InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        input.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        input.setSelectAllOnFocus(true);
        input.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
        // Find as you type, like the address bar suggests as you type.
        input.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable s) {
                find(GeckoSession.FINDER_FIND_FORWARD);
            }
        });
        // The keyboard's search button (and Enter on a hardware keyboard)
        // jumps to the next match, keeping the bar and keyboard open.
        input.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH
                    || actionId == EditorInfo.IME_ACTION_GO
                    || (event != null && event.getAction() == KeyEvent.ACTION_DOWN
                    && event.getKeyCode() == KeyEvent.KEYCODE_ENTER)) {
                find(GeckoSession.FINDER_FIND_FORWARD);
                return true;
            }
            return false;
        });
        LinearLayout.LayoutParams inputLp = new LinearLayout.LayoutParams(
                0, dp(40), 1f);
        bar.addView(input, inputLp);

        counter = new TextView(activity);
        counter.setTextColor(0x99FFFFFF);
        counter.setTextSize(13);
        counter.setGravity(Gravity.CENTER);
        counter.setMinWidth(dp(44));
        LinearLayout.LayoutParams counterLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        counterLp.setMarginStart(dp(6));
        bar.addView(counter, counterLp);

        upButton = iconButton(R.drawable.ic_arrow_up, "Find previous match",
                v -> find(GeckoSession.FINDER_FIND_BACKWARDS));
        downButton = iconButton(R.drawable.ic_arrow_down, "Find next match",
                v -> find(GeckoSession.FINDER_FIND_FORWARD));
        iconButton(R.drawable.ic_close, "Close find in page", v -> close());
        setNavEnabled(false);

        // [FIT] Inserted just above the content container (found by position,
        // not a hard-coded index), so the order of the root layout's children
        // stays exactly as activity_main.xml defines it.
        ViewGroup root = activity.findViewById(R.id.root);
        View content = activity.findViewById(R.id.content_container);
        int at = root.indexOfChild(content);
        if (at < 0) at = root.getChildCount();
        root.addView(bar, at, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        bar.setVisibility(View.GONE);
    }

    // ------------------------------------------------------------------
    // Opening / closing
    // ------------------------------------------------------------------

    /** Opens the bar, focuses the field and shows the keyboard. */
    public void show() {
        final Tab t = host.activeSearchTab();
        if (t == null || !t.session.isOpen()) return;
        try {
            // Highlight every match, not just the selected one.
            t.session.getFinder().setDisplayFlags(GeckoSession.FINDER_DISPLAY_HIGHLIGHT_ALL);
        } catch (RuntimeException ignored) { /* the session died under us */ }
        counter.setText("");
        setNavEnabled(false);
        bar.setVisibility(View.VISIBLE);
        input.requestFocus();
        showKeyboard();
        // Reopening with a query left over from last time: search again, so
        // the highlighting comes back with the bar.
        if (input.getText().length() > 0) find(GeckoSession.FINDER_FIND_FORWARD);
    }

    /** Hides the bar, the keyboard, and the highlighting on the page. */
    public void close() {
        bar.setVisibility(View.GONE);
        hideKeyboard();
        clearMatches();
    }

    public boolean isVisible() {
        return bar.getVisibility() == View.VISIBLE;
    }

    // ------------------------------------------------------------------
    // Searching
    // ------------------------------------------------------------------

    /**
     * Runs a search for the field's current text. SessionFinder keeps the
     * current match as the page's selection, so a repeated call with the same
     * string advances to the next (or, with FINDER_FIND_BACKWARDS, the
     * previous) match — that is what the up/down buttons rely on.
     */
    private void find(int flags) {
        final String query = input.getText().toString();
        if (query.isEmpty()) {
            clearMatches();
            return;
        }
        final Tab t = host.activeSearchTab();
        if (t == null || !t.session.isOpen()) return;
        searchedSession = t.session;
        try {
            GeckoResult<GeckoSession.FinderResult> result =
                    t.session.getFinder().find(query, flags);
            if (result == null) return;
            result.accept(r -> {
                if (r != null) updateCounter(r);
            });
        } catch (RuntimeException e) {
            // The session closed between the check and the call: nothing to search.
        }
    }

    private void clearMatches() {
        final GeckoSession session = searchedSession;
        searchedSession = null;
        if (session == null) return;
        try {
            session.getFinder().clear();
        } catch (RuntimeException ignored) { /* nothing left to clear */ }
        counter.setText("");
        setNavEnabled(false);
    }

    private void updateCounter(GeckoSession.FinderResult r) {
        if (r.found) {
            // total is a running count and can be -1 while unknown.
            counter.setText(r.total > 0
                    ? r.current + "/" + r.total
                    : String.valueOf(r.current));
            setNavEnabled(true);
        } else {
            counter.setText("0/0");
            setNavEnabled(false);
        }
    }

    private void setNavEnabled(boolean enabled) {
        upButton.setAlpha(enabled ? 1f : 0.35f);
        upButton.setEnabled(enabled);
        downButton.setAlpha(enabled ? 1f : 0.35f);
        downButton.setEnabled(enabled);
    }

    // ------------------------------------------------------------------
    // View helpers
    // ------------------------------------------------------------------

    private ImageButton iconButton(int iconRes, String description,
                                   View.OnClickListener onClick) {
        ImageButton b = new ImageButton(activity);
        b.setImageResource(iconRes);
        b.setBackgroundResource(R.drawable.bg_glass_button);
        b.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        b.setContentDescription(description);
        b.setOnClickListener(onClick);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(40), dp(40));
        lp.setMarginStart(dp(6));
        bar.addView(b, lp);
        return b;
    }

    private void showKeyboard() {
        InputMethodManager imm = (InputMethodManager)
                activity.getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT);
    }

    private void hideKeyboard() {
        InputMethodManager imm = (InputMethodManager)
                activity.getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(input.getWindowToken(), 0);
    }

    private int dp(int v) {
        return Math.round(v * activity.getResources().getDisplayMetrics().density);
    }
}
