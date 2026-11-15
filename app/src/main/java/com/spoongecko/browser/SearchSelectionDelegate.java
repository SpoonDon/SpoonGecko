package com.spoongecko.browser;

import android.app.Activity;
import android.view.MenuItem;

import org.mozilla.geckoview.BasicSelectionActionDelegate;
import org.mozilla.geckoview.GeckoSession;

import java.util.ArrayList;
import java.util.List;

/**
 * [SELECT] GeckoView's normal text-selection menu (Copy, Select all, Paste ...) with one extra
 * item, "Search", that hands the selected text to the browser to search for.
 *
 * It only uses the four extension points GeckoView documents for BasicSelectionActionDelegate
 * (getAllActions, isActionAvailable, prepareAction, performAction), so every built-in action
 * behaves exactly as it did before.
 */
public class SearchSelectionDelegate extends BasicSelectionActionDelegate {

    /** Receives the text to search for. Called on the UI thread. */
    public interface SearchHandler {
        void onSearch(String text);
    }

    /** Id of the custom action. It must differ from every built-in GeckoView action id. */
    private static final String ACTION_SEARCH = "com.spoongecko.browser.action.SEARCH_SELECTION";

    /** A search engine URL has a length limit; a whole page selected must not break the search. */
    private static final int MAX_QUERY_CHARS = 400;

    private final SearchHandler handler;

    public SearchSelectionDelegate(Activity activity, SearchHandler handler) {
        super(activity);
        // NOTE: the base constructor runs first, so getAllActions() / isActionAvailable() below
        // must never rely on fields of this class (they only use the base class's mSelection).
        this.handler = handler;
    }

    /** All actions in menu order: the built-in ones, with "Search" right after "Copy". */
    @Override
    protected String[] getAllActions() {
        final String[] base = super.getAllActions();
        final List<String> all = new ArrayList<>(base.length + 1);
        boolean added = false;
        for (String id : base) {
            all.add(id);
            if (!added && ACTION_COPY.equals(id)) {
                all.add(ACTION_SEARCH);
                added = true;
            }
        }
        if (!added) all.add(ACTION_SEARCH);
        return all.toArray(new String[0]);
    }

    @Override
    protected boolean isActionAvailable(String id) {
        if (ACTION_SEARCH.equals(id)) return canSearch();
        return super.isActionAvailable(id);
    }

    @Override
    protected void prepareAction(String id, MenuItem item) {
        if (ACTION_SEARCH.equals(id)) {
            item.setTitle("Search");
            return;
        }
        super.prepareAction(id, item);
    }

    @Override
    protected boolean performAction(String id, MenuItem item) {
        if (!ACTION_SEARCH.equals(id)) return super.performAction(id, item);
        final GeckoSession.SelectionActionDelegate.Selection selection = mSelection;
        if (selection == null || handler == null) return false;
        // Read the text before clearing: the selection object is invalid afterwards.
        final String query = toQuery(selection.text);
        if (query.isEmpty()) return false;
        clearSelection();            // close the menu and un-highlight the text
        handler.onSearch(query);
        return true;
    }

    /** True when something real is selected (not a caret, not a password field). */
    private boolean canSearch() {
        final GeckoSession.SelectionActionDelegate.Selection selection = mSelection;
        if (selection == null) return false;
        if ((selection.flags & FLAG_IS_COLLAPSED) != 0) return false;
        // Never offer to send a password to a search engine.
        if ((selection.flags & FLAG_IS_PASSWORD) != 0) return false;
        return !toQuery(selection.text).isEmpty();
    }

    /** Selected text as a one-line search query of reasonable length. */
    private static String toQuery(String text) {
        if (text == null) return "";
        String query = text.replaceAll("\\s+", " ").trim();
        if (query.length() > MAX_QUERY_CHARS) {
            query = query.substring(0, MAX_QUERY_CHARS);
            final int lastSpace = query.lastIndexOf(' ');
            if (lastSpace > MAX_QUERY_CHARS / 2) query = query.substring(0, lastSpace);
            // Do not leave half of a surrogate pair (an emoji) at the end.
            if (!query.isEmpty() && Character.isHighSurrogate(query.charAt(query.length() - 1))) {
                query = query.substring(0, query.length() - 1);
            }
        }
        return query;
    }
}
