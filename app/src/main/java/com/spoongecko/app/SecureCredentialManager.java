package com.spoongecko.app;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.security.crypto.EncryptedSharedPreferences;
import androidx.security.crypto.MasterKey;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

final class SecureCredentialManager {

    private static final String PREFS_FILE = "spoon_secure_credentials";
    private static final String KEY_INDEX = "__vault_index__";
    private static final String SUFFIX_USER = "_user";
    private static final String SUFFIX_PASS = "_pass";
    private static final String SUFFIX_PRIMARY = "_primary_user";

    private static SecureCredentialManager instance;
    private final SharedPreferences prefs;
    private final ExecutorService io = Executors.newSingleThreadExecutor();

    static final class Entry {
        public final String host;
        public final String username;
        public final String password;

        Entry(String host, String username, String password) {
            this.host = host;
            this.username = username;
            this.password = password;
        }
    }

    interface CredentialsCallback {
        void onResult(List<Entry> entries);
    }

    interface BooleanCallback {
        void onResult(boolean value);
    }

    interface StringCallback {
        void onResult(String value);
    }

    private static final class Credential {
        final String host;
        final String username;
        final String password;

        Credential(String host, String username, String password) {
            this.host = host;
            this.username = username;
            this.password = password;
        }
    }

    private SecureCredentialManager(Context context) {
        try {
            MasterKey masterKey = new MasterKey.Builder(context)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build();
            prefs = EncryptedSharedPreferences.create(
                    context,
                    PREFS_FILE,
                    masterKey,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to initialize encrypted vault", e);
        }
    }

    static synchronized SecureCredentialManager get(Context context) {
        if (instance == null) {
            instance = new SecureCredentialManager(context.getApplicationContext());
        }
        return instance;
    }

    void saveCredentials(String rawHost, String username, String password) {
        String host = sanitizeHost(rawHost);
        String user = username == null ? "" : username.trim();
        if (host.isEmpty() || user.isEmpty() || password == null || password.isEmpty()) return;
        io.execute(() -> store(host, user, password));
    }

    void hasCredential(String rawHost, String username, String password, BooleanCallback callback) {
        String host = sanitizeHost(rawHost);
        String user = username == null ? "" : username.trim();
        if (host.isEmpty() || password == null) {
            callback.onResult(false);
            return;
        }
        io.execute(() -> {
            boolean same = false;
            synchronized (prefs) {
                String stored = prefs.getString(keyPass(host, user), null);
                if (stored != null && stored.equals(password)) {
                    same = true;
                }
                if (!same) {
                    String primary = prefs.getString(keyPrimary(host), null);
                    if (primary != null) {
                        String primaryPass = prefs.getString(keyPass(host, primary), null);
                        if (primaryPass != null && primaryPass.equals(password)) {
                            same = true;
                        }
                    }
                }
            }
            callback.onResult(same);
        });
    }

    void getAllCredentials(CredentialsCallback callback) {
        io.execute(() -> {
            List<Entry> out = new ArrayList<>();
            synchronized (prefs) {
                for (Credential credential : readIndex()) {
                    out.add(new Entry(credential.host, credential.username, credential.password));
                }
            }
            callback.onResult(out);
        });
    }

    void deleteCredentials(String rawHost, String username) {
        String host = sanitizeHost(rawHost);
        String user = username == null ? "" : username.trim();
        if (host.isEmpty() || user.isEmpty()) return;
        io.execute(() -> {
            synchronized (prefs) {
                prefs.edit()
                        .remove(keyUser(host, user))
                        .remove(keyPass(host, user))
                        .apply();
                String primary = prefs.getString(keyPrimary(host), null);
                if (user.equals(primary)) {
                    String replacement = null;
                    for (Credential credential : readIndex()) {
                        if (credential.host.equals(host) && !credential.username.equals(user)) {
                            replacement = credential.username;
                            break;
                        }
                    }
                    if (replacement == null) {
                        prefs.edit().remove(keyPrimary(host)).apply();
                    } else {
                        prefs.edit().putString(keyPrimary(host), replacement).apply();
                    }
                }
                List<Credential> index = readIndex();
                for (int i = index.size() - 1; i >= 0; i--) {
                    Credential credential = index.get(i);
                    if (credential.host.equals(host) && credential.username.equals(user)) {
                        index.remove(i);
                    }
                }
                writeIndex(index);
            }
        });
    }

    void importFromCsv(InputStream input, Runnable done) {
        io.execute(() -> {
            importFromCsvSync(input);
            if (done != null) done.run();
        });
    }

    void getExportCsv(StringCallback callback) {
        io.execute(() -> callback.onResult(exportCsvSync()));
    }

    private void store(String host, String username, String password) {
        synchronized (prefs) {
            prefs.edit()
                    .putString(keyUser(host, username), username)
                    .putString(keyPass(host, username), password)
                    .putString(keyPrimary(host), username)
                    .apply();
            List<Credential> index = readIndex();
            boolean found = false;
            for (Credential credential : index) {
                if (credential.host.equals(host) && credential.username.equals(username)) {
                    found = true;
                    break;
                }
            }
            if (!found) {
                index.add(new Credential(host, username, password));
            }
            writeIndex(index);
        }
    }

    private void importFromCsvSync(InputStream input) {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(input, StandardCharsets.UTF_8))) {
            String header = reader.readLine();
            if (header == null) return;
            String[] columns = splitCsv(header);
            int colUrl = -1;
            int colUser = -1;
            int colPass = -1;
            for (int i = 0; i < columns.length; i++) {
                String column = columns[i].trim().toLowerCase(Locale.ROOT);
                if (colUrl < 0 && (column.equals("url") || column.equals("host")
                        || column.equals("website") || column.equals("site"))) {
                    colUrl = i;
                } else if (colUser < 0 && (column.equals("username")
                        || column.equals("user") || column.equals("login"))) {
                    colUser = i;
                } else if (colPass < 0 && (column.equals("password")
                        || column.equals("pass"))) {
                    colPass = i;
                }
            }
            if (colUser < 0 || colPass < 0) return;
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.trim().isEmpty()) continue;
                String[] parts = splitCsv(line);
                if (parts.length <= Math.max(colUser, colPass)) continue;
                String host = colUrl >= 0 && colUrl < parts.length ? parts[colUrl] : "";
                String user = parts[colUser];
                String pass = parts[colPass];
                String normalized = sanitizeHost(host);
                if (normalized.isEmpty()) normalized = "imported";
                String normalizedUser = user == null ? "" : user.trim();
                if (normalizedUser.isEmpty() || pass == null || pass.isEmpty()) continue;
                store(normalized, normalizedUser, pass);
            }
        } catch (Exception ignored) {
        }
    }

    private String exportCsvSync() {
        synchronized (prefs) {
            StringBuilder sb = new StringBuilder("host,username,password\n");
            for (Credential credential : readIndex()) {
                sb.append(csvField(credential.host)).append(',')
                        .append(csvField(credential.username)).append(',')
                        .append(csvField(credential.password)).append('\n');
            }
            return sb.toString();
        }
    }

    private List<Credential> readIndex() {
        List<Credential> result = new ArrayList<>();
        String json = prefs.getString(KEY_INDEX, "[]");
        try {
            JSONArray array = new JSONArray(json);
            for (int i = 0; i < array.length(); i++) {
                JSONObject object = array.getJSONObject(i);
                String host = object.optString("host", "");
                String username = object.optString("username", "");
                if (host.isEmpty() || username.isEmpty()) continue;
                String password = prefs.getString(keyPass(host, username), null);
                if (password != null) {
                    result.add(new Credential(host, username, password));
                }
            }
        } catch (Exception ignored) {
        }
        return result;
    }

    private void writeIndex(List<Credential> index) {
        JSONArray array = new JSONArray();
        for (Credential credential : index) {
            JSONObject object = new JSONObject();
            try {
                object.put("host", credential.host);
                object.put("username", credential.username);
            } catch (Exception ignored) {
                continue;
            }
            array.put(object);
        }
        prefs.edit().putString(KEY_INDEX, array.toString()).apply();
    }

    private String keyUser(String host, String username) {
        return host + "_" + username + SUFFIX_USER;
    }

    private String keyPass(String host, String username) {
        return host + "_" + username + SUFFIX_PASS;
    }

    private String keyPrimary(String host) {
        return host + SUFFIX_PRIMARY;
    }

    private static String sanitizeHost(String host) {
        if (host == null) return "";
        String value = host.trim().toLowerCase(Locale.ROOT);
        int scheme = value.indexOf("://");
        if (scheme >= 0) value = value.substring(scheme + 3);
        int slash = value.indexOf('/');
        if (slash >= 0) value = value.substring(0, slash);
        int at = value.indexOf('@');
        if (at >= 0) value = value.substring(at + 1);
        int colon = value.lastIndexOf(':');
        if (colon > 0 && value.indexOf(']') < colon) value = value.substring(0, colon);
        value = value.trim();
        while (value.startsWith(".")) value = value.substring(1);
        return value;
    }

    private static String csvField(String value) {
        if (value == null) return "";
        if (value.contains(",") || value.contains("\"") || value.contains("\n")) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
    }

    private static String[] splitCsv(String line) {
        List<String> out = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < line.length(); i++) {
            char ch = line.charAt(i);
            if (inQuotes) {
                if (ch == '"') {
                    if (i + 1 < line.length() && line.charAt(i + 1) == '"') {
                        current.append('"');
                        i++;
                    } else {
                        inQuotes = false;
                    }
                } else {
                    current.append(ch);
                }
            } else {
                if (ch == '"') {
                    inQuotes = true;
                } else if (ch == ',') {
                    out.add(current.toString());
                    current.setLength(0);
                } else {
                    current.append(ch);
                }
            }
        }
        out.add(current.toString());
        return out.toArray(new String[0]);
    }
}
