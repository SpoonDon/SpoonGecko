package com.spoongecko.browser;

/**
 * Waiting for GeckoView to open a session we handed to it.
 *
 * For a new window requested by a page (and for an extension's tabs.create)
 * GeckoView must be given a NEW, UNOPENED GeckoSession and opens it itself. If
 * the app opened it first, GeckoView fails with "Must use an unopened
 * GeckoSession instance" (the crash this class exists to prevent). So the app
 * hands the session back, waits until GeckoView has opened it, and only then
 * attaches it to the view.
 *
 * Timing: GeckoView handles the result we return in the NEXT main-loop
 * iteration. Posting one message lands BEFORE its handling (our message was
 * queued first); posting from inside that message lands after it. So the first
 * check is made two posts later, then every {@link #POLL_MS} for a while.
 *
 * No Android classes, so every timing case can be simulated.
 */
final class SessionAdoption {

    private SessionAdoption() {}

    static final long POLL_MS = 25;
    /** About 3 s: how long GeckoView gets to adopt a session it is expected to adopt. */
    static final int ATTEMPTS_WHEN_GECKOVIEW_ADOPTS = 120;
    /** About 0.4 s: grace period before the app opens a session nobody else is going to open. */
    static final int ATTEMPTS_BEFORE_OPENING_ITSELF = 16;

    interface Scheduler {
        void post(Runnable r);
        void postDelayed(Runnable r, long delayMs);
    }

    interface Target {
        /** The tab is gone or the activity is being destroyed: stop silently. */
        boolean isGone();
        boolean isOpen();
        /** GeckoView opened the session: show it. */
        void adopted();
        /** Nobody opened it and we are allowed to: open it, load the pending address, show it. */
        void openItself();
        /** GeckoView never took it: discard the empty tab. */
        void dropped();
    }

    /**
     * @param mayOpenItself true when the APP asked for this tab (so GeckoView is not
     *                      involved and will never open it); false when GeckoView is
     *                      expected to adopt it (a page's new window, an extension's tabs.create).
     */
    static void await(Scheduler scheduler, Target target, boolean mayOpenItself) {
        scheduler.post(() -> scheduler.post(() -> poll(scheduler, target, mayOpenItself, 0)));
    }

    private static void poll(Scheduler scheduler, Target target, boolean mayOpenItself, int attempt) {
        if (target.isGone()) return;
        if (target.isOpen()) {
            target.adopted();
            return;
        }
        final int limit = mayOpenItself ? ATTEMPTS_BEFORE_OPENING_ITSELF : ATTEMPTS_WHEN_GECKOVIEW_ADOPTS;
        if (attempt < limit) {
            scheduler.postDelayed(() -> poll(scheduler, target, mayOpenItself, attempt + 1), POLL_MS);
            return;
        }
        if (mayOpenItself) target.openItself();
        else target.dropped();
    }
}
