package com.spoongecko.browser;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.drawable.Icon;
import android.media.MediaMetadata;
import android.media.session.PlaybackState;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;

/**
 * Foreground "media playback" service that keeps a page playing while the app
 * is in the background (see MediaPlaybackController for the full picture).
 *
 * It is purely a presenter: all state lives in MediaPlaybackController, this
 * class mirrors it into
 *   - a media-style notification (play/pause, next/previous when the page
 *     supports them, stop),
 *   - a platform MediaSession, so lock-screen controls and headset / Bluetooth
 *     buttons work,
 *   - a partial wake lock and a Wi-Fi lock, held only while audio is playing.
 *
 * Timing rules (the reason for the "linger"):
 *   - Sites report pause/stop between tracks (YouTube autoplaying the next
 *     video, rebuffering). If the service tore itself down at each of those,
 *     Android 12+ would refuse to start it again from the background and
 *     playback would die at the first track change. So after playback stops
 *     it stays in the foreground, with the locks held, for LINGER_MS.
 *   - A pause the user asked for (notification, headset) is explicit and is
 *     honoured at once: locks released, notification becomes dismissible.
 *   - A paused notification is removed after PAUSED_TIMEOUT_MS.
 */
public final class MediaPlaybackService extends Service {

    private static final String TAG = "SpoonGecko";

    static final String CHANNEL_ID = "spoongecko_media";
    private static final int NOTIFICATION_ID = 0x5150;

    private static final String PKG = "com.spoongecko.browser.action.";
    static final String ACTION_PLAY = PKG + "MEDIA_PLAY";
    static final String ACTION_PAUSE = PKG + "MEDIA_PAUSE";
    static final String ACTION_NEXT = PKG + "MEDIA_NEXT";
    static final String ACTION_PREVIOUS = PKG + "MEDIA_PREVIOUS";
    static final String ACTION_STOP = PKG + "MEDIA_STOP";
    static final String ACTION_DISMISS = PKG + "MEDIA_DISMISS";

    private static final long LINGER_MS = 2 * 60 * 1000L;
    private static final long PAUSED_TIMEOUT_MS = 10 * 60 * 1000L;
    private static final long LOCK_TIMEOUT_MS = 4 * 60 * 60 * 1000L;

    /** Set while the service exists. UI thread only. */
    private static MediaPlaybackService instance;

    // ------------------------------------------------------------------
    // Entry points used by MediaPlaybackController (UI thread)
    // ------------------------------------------------------------------

    static void startOrUpdate(Context ctx) {
        if (instance != null) {
            instance.render(false);
            return;
        }
        try {
            ctx.startForegroundService(new Intent(ctx, MediaPlaybackService.class));
        } catch (RuntimeException e) {
            // e.g. ForegroundServiceStartNotAllowedException when started from the background.
            Log.w(TAG, "can't start the media service", e);
        }
    }

    static void updateIfRunning() {
        if (instance != null) instance.render(false);
    }

    static void stop() {
        if (instance != null) instance.shutdown();
    }

    static boolean isRunning() {
        return instance != null;
    }

    // ------------------------------------------------------------------

    private final Handler handler = new Handler(Looper.getMainLooper());
    private NotificationManager nm;
    private android.media.session.MediaSession platformSession;
    private PowerManager.WakeLock wakeLock;
    private WifiManager.WifiLock wifiLock;

    private boolean isForeground;
    private boolean explicitPause;
    private boolean lingerScheduled;
    private boolean pausedTimeoutScheduled;
    private MediaPlaybackController.Info lastInfo;
    private String lastRenderKey;

    private final Runnable lingerRunnable = () -> {
        lingerScheduled = false;
        final MediaPlaybackController c = MediaPlaybackController.get();
        if (c.anyPlaying()) return;
        if (c.describe() == null || lastInfo == null) {
            shutdown();                 // nothing left to control
            return;
        }
        enterPausedState(lastInfo.withPlaying(false));
    };

    private final Runnable pausedTimeoutRunnable = () -> {
        pausedTimeoutScheduled = false;
        shutdown();
    };

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        nm = getSystemService(NotificationManager.class);
        createChannel();
        createPlatformSession();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        final String action = intent == null ? null : intent.getAction();
        final MediaPlaybackController c = MediaPlaybackController.get();
        if (action != null) {
            switch (action) {
                case ACTION_PLAY:
                    c.playCurrent();
                    break;
                case ACTION_PAUSE:
                    explicitPause = true;
                    c.pauseCurrent();
                    break;
                case ACTION_NEXT:
                    c.nextTrack();
                    break;
                case ACTION_PREVIOUS:
                    c.previousTrack();
                    break;
                case ACTION_STOP:
                    c.stopCurrent();
                    shutdown();             // explicit: no lingering
                    return START_NOT_STICKY;
                case ACTION_DISMISS:        // the (paused) notification was swiped away
                    shutdown();
                    return START_NOT_STICKY;
                default:
                    break;
            }
            return START_NOT_STICKY;
        }

        // Started by startForegroundService(): Android requires startForeground() promptly.
        MediaPlaybackController.Info info = c.describe();
        if (info == null) info = lastInfo;
        if (info == null) {
            // Playback ended before we got here. Satisfy the contract, then leave.
            promote(build(new MediaPlaybackController.Info("Media", "", false, false, false)));
            shutdown();
            return START_NOT_STICKY;
        }
        promote(build(info));
        render(true);
        return START_NOT_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        if (instance == this) instance = null;
        cancelTimers();
        releaseLocks();
        releasePlatformSession();
        if (nm != null) nm.cancel(NOTIFICATION_ID);
        notifyStopped();
        super.onDestroy();
    }

    // ------------------------------------------------------------------
    // Rendering
    // ------------------------------------------------------------------

    void render(boolean force) {
        final MediaPlaybackController c = MediaPlaybackController.get();
        MediaPlaybackController.Info info = c.describe();
        if (info == null) {
            if (lastInfo == null) {
                shutdown();
                return;
            }
            info = lastInfo.withPlaying(false);   // page ended its session: show it as paused until we leave
        }
        final String key = info.key() + "\u0001" + info.playing;
        if (!force && key.equals(lastRenderKey)) return;      // nothing visible changed
        lastRenderKey = key;
        lastInfo = info;

        updatePlatformSession(info);

        if (info.playing) {
            cancelTimers();
            explicitPause = false;
            promote(build(info));
            acquireLocks();
        } else if (explicitPause) {
            explicitPause = false;
            enterPausedState(info);
        } else {
            // Between tracks, rebuffering, or the page stopped by itself: hold on for a while.
            nm.notify(NOTIFICATION_ID, build(info));
            scheduleLinger();
        }
    }

    private void enterPausedState(MediaPlaybackController.Info info) {
        cancelLinger();
        releaseLocks();
        if (isForeground) {
            stopForeground(Service.STOP_FOREGROUND_DETACH);   // keep the notification, let it be dismissed
            isForeground = false;
        }
        // Built only now: build() marks the notification dismissible once we are no longer foreground.
        nm.notify(NOTIFICATION_ID, build(info));
        if (!pausedTimeoutScheduled) {
            pausedTimeoutScheduled = true;
            handler.postDelayed(pausedTimeoutRunnable, PAUSED_TIMEOUT_MS);
        }
    }

    private void promote(Notification n) {
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
            } else {
                startForeground(NOTIFICATION_ID, n);
            }
            isForeground = true;
        } catch (RuntimeException e) {
            // Not allowed right now (app in the background and not exempt). Keep the controls visible at least.
            Log.w(TAG, "startForeground failed", e);
            if (nm != null) nm.notify(NOTIFICATION_ID, n);
        }
    }

    void shutdown() {
        if (instance == this) instance = null;     // from now on the controller sees "not running"
        cancelTimers();
        releaseLocks();
        releasePlatformSession();
        if (nm != null) nm.cancel(NOTIFICATION_ID);
        stopForeground(Service.STOP_FOREGROUND_REMOVE);
        isForeground = false;
        stopSelf();
        notifyStopped();
    }

    private boolean stoppedNotified;

    private void notifyStopped() {
        if (stoppedNotified) return;
        stoppedNotified = true;
        MediaPlaybackController.get().onServiceStopped();
    }

    // ------------------------------------------------------------------
    // Timers
    // ------------------------------------------------------------------

    private void scheduleLinger() {
        if (lingerScheduled) return;
        lingerScheduled = true;
        handler.postDelayed(lingerRunnable, LINGER_MS);
    }

    private void cancelLinger() {
        lingerScheduled = false;
        handler.removeCallbacks(lingerRunnable);
    }

    private void cancelTimers() {
        cancelLinger();
        pausedTimeoutScheduled = false;
        handler.removeCallbacks(pausedTimeoutRunnable);
    }

    // ------------------------------------------------------------------
    // Locks: held only while audio is (or just was) playing
    // ------------------------------------------------------------------

    private void acquireLocks() {
        try {
            if (wakeLock == null) {
                final PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
                if (pm != null) {
                    wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SpoonGecko:media");
                    wakeLock.setReferenceCounted(false);
                }
            }
            if (wakeLock != null && !wakeLock.isHeld()) wakeLock.acquire(LOCK_TIMEOUT_MS);

            if (wifiLock == null) {
                final WifiManager wm = (WifiManager) getApplicationContext()
                        .getSystemService(Context.WIFI_SERVICE);
                if (wm != null) {
                    wifiLock = wm.createWifiLock(Build.VERSION.SDK_INT >= 29
                            ? WifiManager.WIFI_MODE_FULL_LOW_LATENCY
                            : WifiManager.WIFI_MODE_FULL_HIGH_PERF, "SpoonGecko:media");
                    wifiLock.setReferenceCounted(false);
                }
            }
            if (wifiLock != null && !wifiLock.isHeld()) wifiLock.acquire();
        } catch (RuntimeException e) {
            Log.w(TAG, "acquiring locks failed", e);
        }
    }

    private void releaseLocks() {
        try {
            if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
            if (wifiLock != null && wifiLock.isHeld()) wifiLock.release();
        } catch (RuntimeException e) {
            Log.w(TAG, "releasing locks failed", e);
        }
    }

    // ------------------------------------------------------------------
    // Platform media session (lock screen, headset and Bluetooth buttons)
    // ------------------------------------------------------------------

    private void createPlatformSession() {
        try {
            platformSession = new android.media.session.MediaSession(this, "SpoonGecko");
            platformSession.setCallback(new android.media.session.MediaSession.Callback() {
                @Override public void onPlay() { MediaPlaybackController.get().playCurrent(); }
                @Override public void onPause() {
                    explicitPause = true;
                    MediaPlaybackController.get().pauseCurrent();
                }
                @Override public void onStop() {
                    MediaPlaybackController.get().stopCurrent();
                    shutdown();
                }
                @Override public void onSkipToNext() { MediaPlaybackController.get().nextTrack(); }
                @Override public void onSkipToPrevious() { MediaPlaybackController.get().previousTrack(); }
            });
        } catch (RuntimeException e) {
            Log.w(TAG, "creating the media session failed", e);
            platformSession = null;
        }
    }

    private void updatePlatformSession(MediaPlaybackController.Info info) {
        if (platformSession == null) return;
        try {
            long actions = PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE
                    | PlaybackState.ACTION_PLAY_PAUSE | PlaybackState.ACTION_STOP;
            if (info.canNext) actions |= PlaybackState.ACTION_SKIP_TO_NEXT;
            if (info.canPrevious) actions |= PlaybackState.ACTION_SKIP_TO_PREVIOUS;
            platformSession.setPlaybackState(new PlaybackState.Builder()
                    .setActions(actions)
                    .setState(info.playing ? PlaybackState.STATE_PLAYING : PlaybackState.STATE_PAUSED,
                            PlaybackState.PLAYBACK_POSITION_UNKNOWN, info.playing ? 1f : 0f)
                    .build());
            platformSession.setMetadata(new MediaMetadata.Builder()
                    .putString(MediaMetadata.METADATA_KEY_TITLE, info.title)
                    .putString(MediaMetadata.METADATA_KEY_ARTIST, info.subtitle)
                    .build());
            platformSession.setActive(true);
        } catch (RuntimeException e) {
            Log.w(TAG, "updating the media session failed", e);
        }
    }

    private void releasePlatformSession() {
        if (platformSession == null) return;
        try {
            platformSession.setActive(false);
            platformSession.release();
        } catch (RuntimeException e) {
            Log.w(TAG, "releasing the media session failed", e);
        }
        platformSession = null;
    }

    // ------------------------------------------------------------------
    // Notification
    // ------------------------------------------------------------------

    private void createChannel() {
        final NotificationChannel ch = new NotificationChannel(
                CHANNEL_ID, "Media playback", NotificationManager.IMPORTANCE_LOW);
        ch.setDescription("Controls for audio and video playing in the background");
        ch.setShowBadge(false);
        ch.setSound(null, null);
        ch.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
        nm.createNotificationChannel(ch);
    }

    private Notification build(MediaPlaybackController.Info info) {
        final Notification.Builder b = new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setContentTitle(info.title)
                .setOngoing(info.playing || isForeground)
                .setShowWhen(false)
                .setCategory(Notification.CATEGORY_TRANSPORT)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setDeleteIntent(servicePending(ACTION_DISMISS));
        if (info.subtitle != null && !info.subtitle.isEmpty()) b.setContentText(info.subtitle);
        if (Build.VERSION.SDK_INT >= 31) {
            b.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE);
        }
        final PendingIntent open = openAppPending();
        if (open != null) b.setContentIntent(open);

        final List<Integer> compact = new ArrayList<>();
        int index = 0;
        if (info.canPrevious) {
            b.addAction(action(android.R.drawable.ic_media_previous, "Previous", ACTION_PREVIOUS));
            compact.add(index++);
        }
        if (info.playing) {
            b.addAction(action(android.R.drawable.ic_media_pause, "Pause", ACTION_PAUSE));
        } else {
            b.addAction(action(android.R.drawable.ic_media_play, "Play", ACTION_PLAY));
        }
        compact.add(index++);
        if (info.canNext) {
            b.addAction(action(android.R.drawable.ic_media_next, "Next", ACTION_NEXT));
            compact.add(index++);
        }
        b.addAction(action(android.R.drawable.ic_menu_close_clear_cancel, "Stop", ACTION_STOP));

        final int[] compactArray = new int[Math.min(compact.size(), 3)];
        for (int i = 0; i < compactArray.length; i++) compactArray[i] = compact.get(i);
        final Notification.MediaStyle style = new Notification.MediaStyle()
                .setShowActionsInCompactView(compactArray);
        if (platformSession != null) style.setMediaSession(platformSession.getSessionToken());
        b.setStyle(style);
        return b.build();
    }

    private Notification.Action action(int iconRes, String title, String action) {
        return new Notification.Action.Builder(
                Icon.createWithResource(this, iconRes), title, servicePending(action)).build();
    }

    private PendingIntent servicePending(String action) {
        final Intent i = new Intent(this, MediaPlaybackService.class).setAction(action);
        return PendingIntent.getService(this, action.hashCode(), i,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    /** Tapping the notification returns to the existing task (launcher semantics: no second activity). */
    private PendingIntent openAppPending() {
        final Intent launch = getPackageManager().getLaunchIntentForPackage(getPackageName());
        if (launch == null) return null;
        return PendingIntent.getActivity(this, 0, launch,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }
}
