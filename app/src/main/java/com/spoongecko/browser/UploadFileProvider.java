package com.spoongecko.browser;

import android.content.ContentProvider;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Environment;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import android.webkit.MimeTypeMap;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.List;
import java.util.Locale;

/**
 * [UPLOAD] Exposes temporary capture files (cache/uploads/capture/...) and local downloads
 * as content:// URIs without storage permissions or external library dependencies.
 */
public final class UploadFileProvider extends ContentProvider {

    private static final String CAPTURE_SUBDIR = "uploads/capture";
    private static final String STAGED_SUBDIR = "uploads";
    private static final String[] DEFAULT_COLUMNS = {
            OpenableColumns.DISPLAY_NAME,
            OpenableColumns.SIZE
    };

    static String authority(Context context) {
        return context.getPackageName() + ".uploads";
    }

    static Uri uriForCaptureFile(Context context, File file) throws IOException {
        return uriForFileUnder(context, captureRoot(context), "capture", file);
    }

    static Uri uriForStagedFile(Context context, File file) throws IOException {
        return uriForFileUnder(context, stagedRoot(context), "staged", file);
    }

    static Uri uriForDownloadFile(Context context, File file) throws IOException {
        return uriForFileUnder(context, downloadsRoot(context), "downloads", file);
    }

    private static Uri uriForFileUnder(Context context, File rootDir, String prefix, File file)
            throws IOException {
        final File root = rootDir.getCanonicalFile();
        final File target = file.getCanonicalFile();
        final String rootPath = root.getPath();
        final String targetPath = target.getPath();
        if (!targetPath.startsWith(rootPath + File.separator)) {
            throw new SecurityException("Upload file must live under " + rootPath);
        }
        final String relative = targetPath.substring(rootPath.length() + 1);
        final Uri.Builder builder = new Uri.Builder()
                .scheme(ContentResolver.SCHEME_CONTENT)
                .authority(authority(context))
                .appendPath(prefix);
        for (String segment : relative.split(File.separatorChar == '\\' ? "\\\\" : "/")) {
            if (!segment.isEmpty()) builder.appendPath(segment);
        }
        return builder.build();
    }

    static File captureRoot(Context context) {
        return new File(context.getCacheDir(), CAPTURE_SUBDIR);
    }

    static File stagedRoot(Context context) {
        return new File(context.getCacheDir(), STAGED_SUBDIR);
    }

    static File downloadsRoot(Context context) {
        File dir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
        if (dir == null) dir = new File(context.getFilesDir(), "downloads");
        return dir;
    }

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs,
                        String sortOrder) {
        final ResolvedTarget target = resolveTarget(uri);
        if (target == null) return null;
        final File file = target.file;
        final String[] cols = projection != null ? projection : DEFAULT_COLUMNS;
        final Object[] row = new Object[cols.length];
        for (int i = 0; i < cols.length; i++) {
            if (OpenableColumns.DISPLAY_NAME.equals(cols[i])) {
                row[i] = file.getName();
            } else if (OpenableColumns.SIZE.equals(cols[i])) {
                row[i] = file.length();
            } else {
                row[i] = null;
            }
        }
        final MatrixCursor cursor = new MatrixCursor(cols, 1);
        cursor.addRow(row);
        return cursor;
    }

    @Override
    public String getType(Uri uri) {
        final ResolvedTarget target = resolveTarget(uri);
        if (target == null) return null;
        final String ext = UploadFiles.extensionOf(target.file.getName());
        if (!ext.isEmpty()) {
            final String mime = MimeTypeMap.getSingleton()
                    .getMimeTypeFromExtension(ext.toLowerCase(Locale.ROOT));
            if (mime != null) return mime;
        }
        return "application/octet-stream";
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        final ResolvedTarget target = resolveTarget(uri);
        if (target == null) throw new FileNotFoundException("Invalid upload URI: " + uri);
        final File file = target.file;
        final String resolvedMode = mode != null ? mode : "r";
        if (target.readOnly && (resolvedMode.contains("w") || resolvedMode.contains("t")
                || resolvedMode.contains("+"))) {
            throw new SecurityException("Staged upload files are read-only");
        }
        final File parent = file.getParentFile();
        if (!target.readOnly && parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new FileNotFoundException("Could not create parent directory");
        }
        if (!target.readOnly && resolvedMode.contains("w") && !file.exists()) {
            try {
                //noinspection ResultOfMethodCallIgnored
                file.createNewFile();
            } catch (IOException e) {
                throw new FileNotFoundException("Could not create capture file: " + e.getMessage());
            }
        }
        final int pfdMode = ParcelFileDescriptor.parseMode(resolvedMode);
        return ParcelFileDescriptor.open(file, pfdMode);
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        throw new UnsupportedOperationException("Inserts are not supported");
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        final ResolvedTarget target = resolveTarget(uri);
        if (target == null || target.readOnly) return 0;
        return target.file.delete() ? 1 : 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException("Updates are not supported");
    }

    private static final class ResolvedTarget {
        final File file;
        final boolean readOnly;

        ResolvedTarget(File file, boolean readOnly) {
            this.file = file;
            this.readOnly = readOnly;
        }
    }

    private ResolvedTarget resolveTarget(Uri uri) {
        final Context context = getContext();
        if (context == null || uri == null) return null;
        final List<String> segments = uri.getPathSegments();
        if (segments == null || segments.size() < 2) return null;
        final String kind = segments.get(0);
        final File baseRoot;
        final boolean readOnly;
        if ("capture".equals(kind)) {
            baseRoot = captureRoot(context);
            readOnly = false;
        } else if ("staged".equals(kind)) {
            if ("capture".equals(segments.get(1))) return null;
            baseRoot = stagedRoot(context);
            readOnly = true;
        } else if ("downloads".equals(kind)) {
            baseRoot = downloadsRoot(context);
            readOnly = true;
        } else {
            return null;
        }
        try {
            final File root = baseRoot.getCanonicalFile();
            File current = root;
            for (int i = 1; i < segments.size(); i++) {
                final String seg = segments.get(i);
                if (seg == null || seg.isEmpty() || ".".equals(seg) || "..".equals(seg)) {
                    return null;
                }
                current = new File(current, seg);
            }
            final File canonical = current.getCanonicalFile();
            if (!canonical.getPath().startsWith(root.getPath() + File.separator)) {
                return null;
            }
            return new ResolvedTarget(canonical, readOnly);
        } catch (IOException e) {
            return null;
        }
    }
}
