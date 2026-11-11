package com.masheeds.player;

import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.AtomicFile;

import com.masheeds.player.TrackLibrary.Track;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

import javax.net.ssl.HttpsURLConnection;

final class DiscoverBackend {
    private static final int PAGE_SIZE = 25;
    private static final int MAX_JSON_BYTES = 1024 * 1024;
    private static final int TIMEOUT_MS = 10_000;
    private static final long CATALOG_FRESH_MS = 5 * 60_000L;
    private static final long CATALOG_MAX_AGE_MS = 24 * 60 * 60_000L;
    private static final String BASE = BuildConfig.SUPABASE_URL.replaceFirst("/+$", "");
    private static final String KEY = BuildConfig.SUPABASE_PUBLISHABLE_KEY;
    private static DiscoverBackend instance;

    record Account(String id, String email) {}
    record Result<T>(T value, boolean success, String code) {}
    record CatalogSnapshot(List<Track> tracks, long savedAt) {
        boolean fresh() { return youngerThan(CATALOG_FRESH_MS); }
        boolean usable() { return youngerThan(CATALOG_MAX_AGE_MS); }
        long millisUntilStale() { return Math.max(1L, CATALOG_FRESH_MS - (System.currentTimeMillis() - savedAt)); }
        long millisUntilExpiry() { return Math.max(1L, CATALOG_MAX_AGE_MS - (System.currentTimeMillis() - savedAt)); }

        private boolean youngerThan(long limit) {
            long now = System.currentTimeMillis();
            return savedAt > 0 && savedAt <= now && now - savedAt < limit;
        }
    }
    record UploadGrant(String id, Uri url, long size, String mime) {}
    record PlaybackGrant(Uri url, int expiresIn) {}
    private record Session(Account account, String accessToken, String refreshToken, long expiresAt) {}

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService catalogWorker = Executors.newSingleThreadExecutor();
    // Refresh token rotation, likes and sign-out share one worker across activity instances.
    private final ExecutorService accountWorker = Executors.newSingleThreadExecutor();
    private final DiscoverTokenStore tokens;
    private final AtomicFile catalogFile;
    private volatile CatalogSnapshot cachedCatalog;
    private boolean catalogCacheRead;
    private Session session;

    private DiscoverBackend(Context context) {
        Context app = context.getApplicationContext();
        tokens = new DiscoverTokenStore(app, BASE);
        catalogFile = new AtomicFile(new File(app.getCacheDir(), "discover-catalog-v1.json"));
    }

    static synchronized DiscoverBackend get(Context context) {
        if (instance == null) {
            instance = new DiscoverBackend(context);
        }
        return instance;
    }

    static boolean configured() {
        Uri uri = httpsUri(BASE);
        return uri != null && uri.getQuery() == null && uri.getFragment() == null
                && (uri.getPath() == null || uri.getPath().isEmpty()) && !KEY.isEmpty();
    }

    static boolean signInConfigured() {
        return configured() && !BuildConfig.GOOGLE_WEB_CLIENT_ID.isEmpty();
    }

    void catalog(boolean forceRefresh, Consumer<CatalogSnapshot> onCached,
                 Consumer<Result<CatalogSnapshot>> callback) {
        CatalogSnapshot memory = cachedCatalog;
        if (memory != null && memory.usable()) {
            onCached.accept(memory);
        }
        run(catalogWorker, () -> {
            CatalogSnapshot saved = readCatalogCache();
            if (saved != null) {
                if (saved != memory) {
                    main.post(() -> onCached.accept(saved));
                }
                if (!forceRefresh && saved.fresh()) {
                    return saved;
                }
            }
            LinkedHashMap<String, Track> tracks = new LinkedHashMap<>();
            for (int offset = 0; ; offset += PAGE_SIZE) {
                String path = "/rest/v1/discover_tracks?published=eq.true"
                        + "&select=id,title,artist,album,duration_ms,audio_url,artwork_url"
                        + "&order=published_at.desc,id.asc&limit=" + PAGE_SIZE + "&offset=" + offset;
                JSONArray page = new JSONArray(request("GET", path, null, null, null));
                for (int index = 0; index < page.length(); index++) {
                    Track track = readTrack(page.getJSONObject(index));
                    if (track != null) {
                        tracks.put(track.onlineId(), track);
                    }
                }
                if (page.length() < PAGE_SIZE) {
                    CatalogSnapshot current = new CatalogSnapshot(new ArrayList<>(tracks.values()),
                            System.currentTimeMillis());
                    cachedCatalog = current;
                    writeCatalogCache(current);
                    return current;
                }
            }
        }, callback);
    }

    private CatalogSnapshot readCatalogCache() {
        if (!catalogCacheRead) {
            catalogCacheRead = true;
            try (InputStream input = catalogFile.openRead()) {
                JSONObject document = new JSONObject(new String(readBytes(input, MAX_JSON_BYTES),
                        StandardCharsets.UTF_8));
                if (BASE.equals(document.getString("project"))) {
                    JSONArray rows = document.getJSONArray("tracks");
                    List<Track> tracks = new ArrayList<>(rows.length());
                    for (int index = 0; index < rows.length(); index++) {
                        Track track = readTrack(rows.getJSONObject(index));
                        if (track != null) {
                            tracks.add(track);
                        }
                    }
                    cachedCatalog = new CatalogSnapshot(tracks, document.getLong("saved_at"));
                }
            } catch (IOException | JSONException ignored) {
                // The catalog can be fetched when the cache is unavailable.
            }
        }
        return cachedCatalog != null && cachedCatalog.usable() ? cachedCatalog : null;
    }

    private void writeCatalogCache(CatalogSnapshot snapshot) {
        FileOutputStream output = null;
        try {
            JSONArray rows = new JSONArray();
            for (Track track : snapshot.tracks()) {
                rows.put(new JSONObject().put("id", track.onlineId()).put("title", track.title())
                        .put("artist", track.artist()).put("duration_ms", track.duration())
                        .put("album", track.album())
                        .put("audio_url", track.uri().toString())
                        .put("artwork_url", track.artworkUri() == null ? null : track.artworkUri().toString()));
            }
            byte[] bytes = new JSONObject().put("project", BASE).put("saved_at", snapshot.savedAt())
                    .put("tracks", rows).toString().getBytes(StandardCharsets.UTF_8);
            if (bytes.length > MAX_JSON_BYTES) {
                catalogFile.delete();
                return;
            }
            output = catalogFile.startWrite();
            output.write(bytes);
            catalogFile.finishWrite(output);
        } catch (IOException | JSONException ignored) {
            if (output != null) {
                catalogFile.failWrite(output);
            }
        }
    }

    void account(Consumer<Result<Account>> callback) {
        run(accountWorker, () -> {
            Session active = session(false);
            return active == null ? null : active.account();
        }, callback);
    }

    void signIn(String idToken, String nonce, Consumer<Result<Account>> callback) {
        run(accountWorker, () -> exchange("id_token", new JSONObject()
                .put("provider", "google").put("id_token", idToken).put("nonce", nonce)).account(), callback);
    }

    void signOut(Consumer<Result<Void>> callback) {
        run(accountWorker, () -> {
            Session previous = session;
            clearSession();
            if (previous != null) {
                try {
                    request("POST", "/auth/v1/logout?scope=local", previous.accessToken(), null, null);
                } catch (IOException ignored) {
                    // Local credentials have already been cleared.
                }
            }
            return null;
        }, callback);
    }

    void likes(String accountId, Consumer<Result<Set<String>>> callback) {
        run(accountWorker, () -> {
            Set<String> ids = new LinkedHashSet<>();
            for (int offset = 0; ; offset += PAGE_SIZE) {
                String path = "/rest/v1/discover_likes?select=track_id&order=track_id.asc"
                        + "&limit=" + PAGE_SIZE + "&offset=" + offset;
                JSONArray page = new JSONArray(authorized(accountId, "GET", path, null, null));
                for (int index = 0; index < page.length(); index++) {
                    ids.add(page.getJSONObject(index).getString("track_id"));
                }
                if (page.length() < PAGE_SIZE) {
                    return ids;
                }
            }
        }, callback);
    }

    void setLiked(String accountId, String trackId, boolean liked, Consumer<Result<Void>> callback) {
        run(accountWorker, () -> {
            String path = "/rest/v1/discover_likes";
            if (liked) {
                authorized(accountId, "POST", path + "?on_conflict=user_id,track_id",
                        new JSONObject().put("user_id", accountId).put("track_id", trackId),
                        "resolution=ignore-duplicates,return=minimal");
            } else {
                authorized(accountId, "DELETE", path + "?track_id=eq." + Uri.encode(trackId), null, "return=minimal");
            }
            return null;
        }, callback);
    }

    void privateTracks(String accountId, Consumer<Result<List<Track>>> callback) {
        run(accountWorker, () -> {
            List<Track> tracks = new ArrayList<>();
            for (int offset = 0; ; offset += PAGE_SIZE) {
                String path = "/rest/v1/private_tracks?state=eq.ready&select=id,title,artist,duration_ms"
                        + "&order=created_at.desc,id.asc&limit=" + PAGE_SIZE + "&offset=" + offset;
                JSONArray page = new JSONArray(authorized(accountId, "GET", path, null, null));
                for (int index = 0; index < page.length(); index++) {
                    JSONObject item = page.getJSONObject(index);
                    String id = UUID.fromString(item.getString("id")).toString();
                    Uri identity = Uri.parse("masheeds://uploads/" + id);
                    tracks.add(new Track(identity, identity, item.getString("title"), item.optString("artist"),
                            item.getLong("duration_ms"), 0, 0));
                }
                if (page.length() < PAGE_SIZE) {
                    return tracks;
                }
            }
        }, callback);
    }

    void beginPrivateUpload(String accountId, PrivateMusicTransfer.Audio audio,
                            Consumer<Result<UploadGrant>> callback) {
        run(accountWorker, () -> {
            JSONObject response = privateMusic(accountId, new JSONObject().put("action", "begin")
                    .put("title", audio.title()).put("artist", audio.artist()).put("duration_ms", audio.duration())
                    .put("size_bytes", audio.file().length()).put("mime_type", audio.mime()));
            JSONObject headers = response.getJSONObject("headers");
            return new UploadGrant(response.getString("id"), signedUri(response.getString("upload_url")),
                    headers.getLong("Content-Length"), headers.getString("Content-Type"));
        }, callback);
    }

    void finishPrivateUpload(String accountId, String id, Consumer<Result<Void>> callback) {
        run(accountWorker, () -> {
            privateMusic(accountId, new JSONObject().put("action", "finish").put("id", id));
            return null;
        }, callback);
    }

    void deletePrivateTrack(String accountId, String id, Consumer<Result<Void>> callback) {
        run(accountWorker, () -> {
            privateMusic(accountId, new JSONObject().put("action", "delete").put("id", id));
            return null;
        }, callback);
    }

    void privatePlayback(String id, Consumer<Result<PlaybackGrant>> callback) {
        run(accountWorker, () -> {
            JSONObject response = privateMusic(null, new JSONObject().put("action", "play").put("id", id));
            int expires = response.getInt("expires_in");
            if (expires < 1 || expires > 3600) {
                throw new IOException("Invalid playback expiry");
            }
            return new PlaybackGrant(signedUri(response.getString("url")), expires);
        }, callback);
    }

    private JSONObject privateMusic(String accountId, JSONObject body) throws Exception {
        return new JSONObject(authorized(accountId, "POST", "/functions/v1/private-music", body, null));
    }

    private static Uri signedUri(String value) throws IOException {
        Uri uri = httpsUri(value);
        if (uri == null || !uri.getHost().endsWith(".r2.cloudflarestorage.com")) {
            throw new IOException("Invalid R2 URL");
        }
        return uri;
    }

    private String authorized(String accountId, String method, String path, JSONObject body, String prefer)
            throws Exception {
        Session active = requireAccount(session(false), accountId);
        try {
            return request(method, path, active.accessToken(), body, prefer);
        } catch (HttpError error) {
            if (error.status != 401) {
                throw error;
            }
            active = requireAccount(session(true), accountId);
            return request(method, path, active.accessToken(), body, prefer);
        }
    }

    private Session requireAccount(Session active, String accountId) throws IOException {
        if (active == null || accountId != null && !active.account().id().equals(accountId)) {
            throw new HttpError(401, "unauthorized");
        }
        return active;
    }

    private Session session(boolean forceRefresh) throws Exception {
        if (session != null && !forceRefresh && SystemClock.elapsedRealtime() < session.expiresAt()) {
            return session;
        }
        String token = session == null ? tokens.read() : session.refreshToken();
        if (token == null) {
            return null;
        }
        try {
            return exchange("refresh_token", new JSONObject().put("refresh_token", token));
        } catch (HttpError error) {
            if (error.status == 400 || error.status == 401 || error.status == 403) {
                clearSession();
                return null;
            }
            throw error;
        }
    }

    private Session exchange(String grant, JSONObject body) throws Exception {
        JSONObject response = new JSONObject(request("POST", "/auth/v1/token?grant_type=" + grant,
                null, body, null));
        JSONObject user = response.getJSONObject("user");
        Account account = new Account(user.getString("id"), user.optString("email"));
        long expiresAt = SystemClock.elapsedRealtime() + Math.max(0L, response.getInt("expires_in") - 30L) * 1000;
        Session next = new Session(account, response.getString("access_token"),
                response.getString("refresh_token"), expiresAt);
        tokens.save(next.refreshToken());
        session = next;
        return next;
    }

    private void clearSession() {
        session = null;
        tokens.clear();
    }

    private static Track readTrack(JSONObject item) throws JSONException {
        String id;
        try {
            id = UUID.fromString(item.getString("id")).toString();
        } catch (IllegalArgumentException error) {
            return null;
        }
        Uri audio = httpsUri(item.optString("audio_url"));
        if (audio == null) {
            return null;
        }
        return new Track(audio, Uri.parse("masheeds://discover/" + id), item.getString("title"),
                item.optString("artist"), item.getLong("duration_ms"), 0, 0,
                httpsUri(item.optString("artwork_url")), item.isNull("album") ? "" : item.getString("album").trim());
    }

    private static Uri httpsUri(String value) {
        Uri uri = Uri.parse(value);
        return "https".equals(uri.getScheme()) && uri.getHost() != null && uri.getUserInfo() == null ? uri : null;
    }

    private static String request(String method, String path, String token, JSONObject body, String prefer)
            throws IOException {
        if (!configured()) {
            throw new IOException("Discover is not configured");
        }
        HttpsURLConnection connection = open(new URL(BASE + path));
        try {
            connection.setRequestMethod(method);
            connection.setRequestProperty("apikey", KEY);
            connection.setRequestProperty("Accept", "application/json");
            if (token != null) {
                connection.setRequestProperty("Authorization", "Bearer " + token);
            }
            if (prefer != null) {
                connection.setRequestProperty("Prefer", prefer);
            }
            if (body != null) {
                byte[] data = body.toString().getBytes(StandardCharsets.UTF_8);
                connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                connection.setFixedLengthStreamingMode(data.length);
                try (OutputStream output = connection.getOutputStream()) {
                    output.write(data);
                }
            }
            int status = connection.getResponseCode();
            if (status < 200 || status >= 300) {
                throw new HttpError(status, errorCode(connection));
            }
            if (status == 204) {
                return "";
            }
            try (InputStream input = connection.getInputStream()) {
                return new String(readBytes(input, MAX_JSON_BYTES), StandardCharsets.UTF_8);
            }
        } finally {
            connection.disconnect();
        }
    }

    private static String errorCode(HttpsURLConnection connection) {
        try (InputStream input = connection.getErrorStream()) {
            if (input != null) {
                String code = new JSONObject(new String(readBytes(input, 4096), StandardCharsets.UTF_8))
                        .optString("code");
                return code.matches("[a-z_]+") ? code : "";
            }
        } catch (IOException | JSONException ignored) {
            // The HTTP status is sufficient when a response has no usable error code.
        }
        return "";
    }

    static byte[] downloadArtwork(Uri uri) throws IOException {
        HttpsURLConnection connection = open(new URL(uri.toString()));
        try {
            int status = connection.getResponseCode();
            if (status != 200) {
                throw new HttpError(status, "");
            }
            try (InputStream input = connection.getInputStream()) {
                return readBytes(input, 4 * 1024 * 1024);
            }
        } finally {
            connection.disconnect();
        }
    }

    private static HttpsURLConnection open(URL url) throws IOException {
        HttpsURLConnection connection = (HttpsURLConnection) url.openConnection();
        connection.setInstanceFollowRedirects(false);
        connection.setConnectTimeout(TIMEOUT_MS);
        connection.setReadTimeout(TIMEOUT_MS);
        return connection;
    }

    private static byte[] readBytes(InputStream input, int limit) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        for (int count; (count = input.read(buffer)) != -1; ) {
            if (count > limit - output.size()) {
                throw new IOException("Discover response is too large");
            }
            output.write(buffer, 0, count);
        }
        return output.toByteArray();
    }

    private <T> void run(ExecutorService worker, Task<T> task, Consumer<Result<T>> callback) {
        worker.execute(() -> {
            Result<T> result;
            try {
                result = new Result<>(task.run(), true, "");
            } catch (Exception error) {
                result = new Result<>(null, false, error instanceof HttpError http ? http.code : "");
            }
            Result<T> completed = result;
            main.post(() -> callback.accept(completed));
        });
    }

    private interface Task<T> {
        T run() throws Exception;
    }

    private static final class HttpError extends IOException {
        private final int status;
        private final String code;

        private HttpError(int status, String code) {
            super("Discover HTTP " + status);
            this.status = status;
            this.code = code;
        }
    }
}
