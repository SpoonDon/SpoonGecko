package com.spoongecko.browser;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.mozilla.geckoview.GeckoSession;
import org.mozilla.geckoview.MediaSession;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * Background audio/video playback.
 *
 * Why this exists: when the activity stops, a page's content process has no
 * surface and GeckoView lowers its priority, so Android reclaims it after a
 * minute or two and the sound stops. Keeping a page playing therefore needs
 * three things at once, all driven from here:
 *   1. the playing page's process kept at PRIORITY_HIGH (MainActivity applies
 *      it when told about state changes through {@link Listener});
 *   2. a foreground "media playback" service, which keeps the app process
 *      alive and gives the user notification / lock-screen / headset controls
 *      ({@link MediaPlaybackService});
 *   3. audio focus, so calls and other players pause us and we pause them.
 *
 * GeckoView reports media per GeckoSession through MediaSession.Delegate.
 * Everything here runs on the UI thread. MediaTracker holds the pure state.
 */
final class MediaPlaybackController {

    private static final String TAG = "SpoonGecko";
    private static final long DEFAULT_FEATURES =
            MediaSession.Feature.PLAY | MediaSession.Feature.PAUSE | MediaSession.Feature.STOP;

    /** Notified on the UI thread. */
    interface Listener {
        /**
         * Which pages count as "playing" changed (play, pause, stop, session
         * ended, service finished). Re-evaluate process priorities.
         */
        void onMediaStateChanged();
        /** A page just started playing. */
        void onMediaStarted();
    }

    /** What the notification shows. */
    static final class Info {
        final String title;
        final String subtitle;
        final boolean playing;
        final boolean canNext;
        final boolean canPrevious;

        Info(String title, String subtitle, boolean playing, boolean canNext, boolean canPrevious) {
            this.title = title;
            this.subtitle = subtitle;
            this.playing = playing;
            this.canNext = canNext;
            this.canPrevious = canPrevious;
        }

        Info withPlaying(boolean p) {
            return new Info(title, subtitle, p, canNext, canPrevious);
        }

        String key() {
            return title + "\u0001" + subtitle + "\u0001" + canNext + canPrevious;
        }
    }

    private static final MediaPlaybackController INSTANCE = new MediaPlaybackController();

    static MediaPlaybackController get() {
        return INSTANCE;
    }

    private final MediaTracker<GeckoSession> tracker = new MediaTracker<>();
    private final Map<GeckoSession, Tab> tabs = new HashMap<>();
    private final Map<GeckoSession, MediaSession> geckoSessions = new HashMap<>();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private Context appContext;
    private Listener listener;

    // Audio focus
    private AudioManager audioManager;
    private AudioFocusRequest focusRequest;
    private boolean haveFocus;
    private boolean resumeOnGain;

    private MediaPlaybackController() {}

    void setListener(Listener l) {
        listener = l;
    }

    // ------------------------------------------------------------------
    // GeckoView -> state
    // ------------------------------------------------------------------

    MediaSession.Delegate delegateFor(Context ctx, final Tab tab) {
        if (appContext == null) appContext = ctx.getApplicationContext();
        return new MediaSession.Delegate() {
            @Override
            public void onActivated(GeckoSession session, MediaSession mediaSession) {
                tabs.put(session, tab);
                geckoSessions.put(session, mediaSession);
                tracker.activated(session);
            }

            @Override
            public void onDeactivated(GeckoSession session, MediaSession mediaSession) {
                tracker.deactivated(session);
                geckoSessions.remove(session);
                tabs.remove(session);
                afterChange(session);
            }

            @Override
            public void onMetadata(GeckoSession session, MediaSession mediaSession,
                                   MediaSession.Metadata meta) {
                if (meta == null) return;
                tracker.metadata(session, meta.title, meta.artist, meta.album);
                syncService();
            }

            @Override
            public void onFeatures(GeckoSession session, MediaSession mediaSession, long features) {
                tracker.features(session, features);
                syncService();
            }

            @Override
            public void onPlay(GeckoSession session, MediaSession mediaSession) {
                tabs.put(session, tab);
                geckoSessions.put(session, mediaSession);
                final boolean wasPlaying = tracker.isPlaying(session);
                tracker.play(session);
                resumeOnGain = false;
                requestFocus();
                afterChange(session);
                if (!wasPlaying && listener != null) listener.onMediaStarted();
            }

            @Override
            public void onPause(GeckoSession session, MediaSession mediaSession) {
                tracker.pause(session);
                if (!tracker.anyPlaying() && !resumeOnGain) abandonFocus();
                afterChange(session);
            }

            @Override
            public void onStop(GeckoSession session, MediaSession mediaSession) {
                tracker.stop(session);
                if (!tracker.anyPlaying() && !resumeOnGain) abandonFocus();
                afterChange(session);
            }
        };
    }

    private void afterChange(GeckoSession session) {
        syncService();
        if (listener != null) listener.onMediaStateChanged();
    }

    /**
     * Should this page's process be kept at high priority even in the
     * background? Yes while it plays, and while the service is still holding
     * on to it (the pause between two tracks, a rebuffer, or a paused
     * notification the user may resume): letting the process drop to low
     * priority at exactly that moment is what would let Android reclaim it.
     */
    boolean wantsHighPriority(GeckoSession session) {
        if (tracker.isPlaying(session)) return true;
        if (!MediaPlaybackService.isRunning()) return false;
        // latest(), not current(): after a page reports "stop" (end of a track) it is no
        // longer selectable, but the service is still lingering for it and it must not
        // lose priority in that gap.
        final MediaTracker.Entry<GeckoSession> last = tracker.latest();
        return last != null && last.key == session;
    }

    /** The service has finished: pages it was holding no longer need high priority. */
    void onServiceStopped() {
        if (!tracker.anyPlaying() && !resumeOnGain) abandonFocus();
        if (listener != null) listener.onMediaStateChanged();
    }

    /** Forgets pages whose tab has been closed. Call whenever the tab list changes. */
    void prune() {
        if (!tracker.prune(GeckoSession::isOpen)) return;
        for (Iterator<GeckoSession> it = geckoSessions.keySet().iterator(); it.hasNext(); ) {
            if (!it.next().isOpen()) it.remove();
        }
        for (Iterator<GeckoSession> it = tabs.keySet().iterator(); it.hasNext(); ) {
            if (!it.next().isOpen()) it.remove();
        }
        if (!tracker.anyPlaying() && !resumeOnGain) abandonFocus();
        // A closed tab is gone for good (unlike a pause between two tracks): if nothing is
        // left to control, take the notification down now instead of letting it linger.
        if (tracker.current() == null) MediaPlaybackService.stop();
        else syncService();
        if (listener != null) listener.onMediaStateChanged();
    }

    /** The activity is going away (its tabs are about to be closed): stop everything. */
    void shutdown() {
        tracker.clear();
        tabs.clear();
        geckoSessions.clear();
        resumeOnGain = false;
        abandonFocus();
        MediaPlaybackService.stop();
        listener = null;
    }

    /**
     * The app is leaving the foreground. If something is playing but the
     * service isn't running yet, start it now — Android only allows starting a
     * foreground service from the background in a few cases, and "the app was
     * just visible" is the reliable one.
     */
    void onAppBackgrounded() {
        if (appContext != null && tracker.anyPlaying()) MediaPlaybackService.startOrUpdate(appContext);
    }

    // ------------------------------------------------------------------
    // For the service
    // ------------------------------------------------------------------

    /** What to show right now, or null if there is nothing to control. */
    Info describe() {
        final MediaTracker.Entry<GeckoSession> e = tracker.current();
        if (e == null) return null;
        final Tab tab = tabs.get(e.key);
        final String pageTitle = tab == null || ErrorPages.isErrorTitle(tab.title) ? null : tab.title;
        final String host = tab == null ? null : LocalAddress.hostOf(tab.url);
        final String title = MediaTracker.displayTitle(e.title, pageTitle, host);
        final String subtitle = !isBlank(e.artist) ? e.artist.trim() : (!isBlank(host) ? host : "");
        final long f = e.features != null ? e.features : DEFAULT_FEATURES;
        return new Info(title, subtitle, e.playing,
                (f & MediaSession.Feature.NEXT_TRACK) != 0,
                (f & MediaSession.Feature.PREVIOUS_TRACK) != 0);
    }

    boolean anyPlaying() {
        return tracker.anyPlaying();
    }

    void playCurrent() {
        final MediaSession ms = currentGecko();
        if (ms == null) return;
        try { ms.play(); } catch (RuntimeException e) { Log.w(TAG, "play failed", e); }
    }

    void pauseCurrent() {
        pausePlaying();
    }

    void stopCurrent() {
        final MediaSession ms = currentGecko();
        if (ms == null) return;
        try { ms.stop(); } catch (RuntimeException e) { Log.w(TAG, "stop failed", e); }
    }

    void nextTrack() {
        final MediaSession ms = currentGecko();
        if (ms == null) return;
        try { ms.nextTrack(); } catch (RuntimeException e) { Log.w(TAG, "next failed", e); }
    }

    void previousTrack() {
        final MediaSession ms = currentGecko();
        if (ms == null) return;
        try { ms.previousTrack(); } catch (RuntimeException e) { Log.w(TAG, "previous failed", e); }
    }

    private MediaSession currentGecko() {
        final MediaTracker.Entry<GeckoSession> e = tracker.current();
        return e == null ? null : geckoSessions.get(e.key);
    }

    private void pausePlaying() {
        for (MediaTracker.Entry<GeckoSession> e : tracker.playingEntries()) {
            final MediaSession ms = geckoSessions.get(e.key);
            if (ms == null) continue;
            try { ms.pause(); } catch (RuntimeException ex) { Log.w(TAG, "pause failed", ex); }
        }
    }

    private void syncService() {
        if (appContext == null) return;
        if (tracker.anyPlaying()) MediaPlaybackService.startOrUpdate(appContext);
        else MediaPlaybackService.updateIfRunning();   // the service decides how long to linger
    }

    // ------------------------------------------------------------------
    // Audio focus
    // ------------------------------------------------------------------

    private final AudioManager.OnAudioFocusChangeListener focusListener = change -> {
        switch (change) {
            case AudioManager.AUDIOFOCUS_GAIN:
                haveFocus = true;
                if (resumeOnGain) {
                    resumeOnGain = false;
                    playCurrent();
                }
                break;
            case AudioManager.AUDIOFOCUS_LOSS:
                // Another app took over for good (a different player started): pause, don't resume.
                haveFocus = false;
                resumeOnGain = false;
                pausePlaying();
                break;
            case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT:
                // A call or similar: pause now, carry on when focus comes back.
                haveFocus = false;
                resumeOnGain = tracker.anyPlaying();
                pausePlaying();
                break;
            default:
                // AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK: the system lowers our volume by itself.
                break;
        }
    };

    private void requestFocus() {
        if (haveFocus || appContext == null) return;
        try {
            if (audioManager == null) {
                audioManager = (AudioManager) appContext.getSystemService(Context.AUDIO_SERVICE);
            }
            if (audioManager == null) return;
            if (focusRequest == null) {
                focusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                        .setAudioAttributes(new AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_MEDIA)
                                .setContentType(AudioAttributes.CONTENT_TYPE_UNKNOWN)
                                .build())
                        .setOnAudioFocusChangeListener(focusListener, mainHandler)
                        .build();
            }
            // If this is refused (e.g. during a call) we simply keep playing; no loss events follow.
            haveFocus = audioManager.requestAudioFocus(focusRequest)
                    == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
        } catch (RuntimeException e) {
            Log.w(TAG, "audio focus request failed", e);
        }
    }

    private void abandonFocus() {
        if (audioManager != null && focusRequest != null) {
            try { audioManager.abandonAudioFocusRequest(focusRequest); }
            catch (RuntimeException e) { Log.w(TAG, "abandon audio focus failed", e); }
        }
        haveFocus = false;
        resumeOnGain = false;
    }

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }
}
