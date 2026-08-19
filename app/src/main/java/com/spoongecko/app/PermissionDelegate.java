package com.spoongecko.app;

import android.app.AlertDialog;
import android.content.pm.PackageManager;
import android.net.Uri;

import androidx.core.content.ContextCompat;

import org.mozilla.geckoview.GeckoResult;
import org.mozilla.geckoview.GeckoSession;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

class PermissionDelegate implements GeckoSession.PermissionDelegate {
    private final WeakReference<MainActivity> activityRef;
    private static final int REQUEST_CODE_PERMISSIONS = 1;
    private static final Set<Integer> AUTOPLAY_PERMISSIONS = new HashSet<>(Arrays.asList(
            PERMISSION_AUTOPLAY_AUDIBLE, PERMISSION_AUTOPLAY_INAUDIBLE));

    PermissionDelegate(MainActivity activity) {
        this.activityRef = new WeakReference<>(activity);
    }

    public GeckoResult<Integer> onContentPermissionRequest(
            GeckoSession session, GeckoSession.PermissionDelegate.ContentPermission perm) {
        MainActivity activity = activityRef.get();
        if (activity == null) return GeckoResult.fromValue(ContentPermission.VALUE_DENY);
        if (AUTOPLAY_PERMISSIONS.contains(perm.permission)) {
            return GeckoResult.fromValue(ContentPermission.VALUE_ALLOW);
        }
        if (perm.permission == PERMISSION_GEOLOCATION) {
            if (ContextCompat.checkSelfPermission(activity, android.Manifest.permission.ACCESS_FINE_LOCATION)
                    != PackageManager.PERMISSION_GRANTED) {
                activity.requestPermissions(new String[]{android.Manifest.permission.ACCESS_FINE_LOCATION},
                        REQUEST_CODE_PERMISSIONS);
                return GeckoResult.fromValue(ContentPermission.VALUE_DENY);
            }
            return promptPermission(activity, activity.getString(R.string.perm_location), perm.uri);
        }
        if (perm.permission == PERMISSION_DESKTOP_NOTIFICATION) {
            return promptPermission(activity, activity.getString(R.string.perm_notifications), perm.uri);
        }
        if (perm.permission == PERMISSION_PERSISTENT_STORAGE) {
            return promptPermission(activity, activity.getString(R.string.perm_persistent_storage), perm.uri);
        }
        return GeckoResult.fromValue(ContentPermission.VALUE_DENY);
    }

    private GeckoResult<Integer> promptPermission(MainActivity activity, String label, String uri) {
        GeckoResult<Integer> result = new GeckoResult<>();
        String host = extractHost(uri, activity);
        activity.runOnUiThread(() -> {
            if (activity.isFinishing() || activity.isDestroyed()) {
                result.complete(ContentPermission.VALUE_DENY);
                return;
            }
            new AlertDialog.Builder(activity)
                    .setTitle(activity.getString(R.string.permission_allow_title, label))
                    .setMessage(activity.getString(R.string.permission_message, host, label))
                    .setPositiveButton(R.string.allow, (d, w) -> result.complete(ContentPermission.VALUE_ALLOW))
                    .setNegativeButton(R.string.deny, (d, w) -> result.complete(ContentPermission.VALUE_DENY))
                    .setOnCancelListener(d -> result.complete(ContentPermission.VALUE_DENY))
                    .show();
        });
        return result;
    }

    private String extractHost(String uri, MainActivity activity) {
        if (uri == null) return activity.getString(R.string.this_site);
        String host = Uri.parse(uri).getHost();
        return host != null ? host : activity.getString(R.string.this_site);
    }

    public GeckoResult<Integer> onMediaPermissionRequest(
            GeckoSession session, String uri,
            GeckoSession.PermissionDelegate.MediaSource[] video,
            GeckoSession.PermissionDelegate.MediaSource[] audio) {
        MainActivity activity = activityRef.get();
        if (activity == null) return GeckoResult.fromValue(ContentPermission.VALUE_DENY);
        List<String> needed = new ArrayList<>();
        if (video != null && video.length > 0 &&
                ContextCompat.checkSelfPermission(activity, android.Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            needed.add(android.Manifest.permission.CAMERA);
        }
        if (audio != null && audio.length > 0 &&
                ContextCompat.checkSelfPermission(activity, android.Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            needed.add(android.Manifest.permission.RECORD_AUDIO);
        }
        if (!needed.isEmpty()) {
            activity.requestPermissions(needed.toArray(new String[0]), REQUEST_CODE_PERMISSIONS);
            return GeckoResult.fromValue(ContentPermission.VALUE_DENY);
        }
        return GeckoResult.fromValue(ContentPermission.VALUE_ALLOW);
    }

    public GeckoResult<Integer> onGeckoPermissionRequest(
            GeckoSession session, String uri, int type, GeckoSession.PermissionDelegate.Callback callback) {
        MainActivity activity = activityRef.get();
        if (activity == null) return GeckoResult.fromValue(ContentPermission.VALUE_DENY);
        if (type == PERMISSION_GEOLOCATION) {
            if (ContextCompat.checkSelfPermission(activity, android.Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                return GeckoResult.fromValue(ContentPermission.VALUE_ALLOW);
            } else {
                activity.requestPermissions(new String[]{android.Manifest.permission.ACCESS_FINE_LOCATION}, REQUEST_CODE_PERMISSIONS);
                return GeckoResult.fromValue(ContentPermission.VALUE_DENY);
            }
        }
        if (type == PERMISSION_AUTOPLAY_AUDIBLE ||
                type == PERMISSION_AUTOPLAY_INAUDIBLE) {
            return GeckoResult.fromValue(ContentPermission.VALUE_ALLOW);
        }
        return GeckoResult.fromValue(ContentPermission.VALUE_DENY);
    }
}
