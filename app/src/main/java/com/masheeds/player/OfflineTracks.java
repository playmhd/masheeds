package com.masheeds.player;

import android.app.DownloadManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Environment;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

final class OfflineTracks {
    record Item(TrackLibrary.Track track, long downloadId, int status, long bytes, long total) {
        boolean ready() { return status == DownloadManager.STATUS_SUCCESSFUL; }
        boolean failed() { return status == DownloadManager.STATUS_FAILED; }
    }

    private final DownloadManager manager;
    private final SharedPreferences store;
    private final Context context;

    OfflineTracks(Context context) {
        this.context = context.getApplicationContext();
        manager = context.getSystemService(DownloadManager.class);
        store = this.context.getSharedPreferences("offline_discover", Context.MODE_PRIVATE);
    }

    boolean eligible(TrackLibrary.Track track) {
        Uri project = Uri.parse(BuildConfig.SUPABASE_URL);
        String path = track.uri().getPath();
        return track.isOnline() && !track.isPrivateOnline() && track.downloadAllowed()
                && "https".equals(track.uri().getScheme()) && project.getAuthority() != null
                && project.getAuthority().equals(track.uri().getAuthority())
                && path != null && path.startsWith("/storage/v1/object/public/discover/");
    }

    void enqueue(TrackLibrary.Track track) {
        if (!eligible(track)) {
            throw new IllegalArgumentException("Track cannot be downloaded");
        }
        Item previous = item(track.onlineId());
        if (previous != null && !previous.failed() && previous.track().uri().equals(track.uri())) {
            return;
        }
        remove(track.onlineId());
        DownloadManager.Request request = new DownloadManager.Request(track.uri())
                .setTitle(track.title()).setMimeType("audio/mpeg")
                .setAllowedNetworkTypes(DownloadManager.Request.NETWORK_WIFI)
                .setAllowedOverMetered(false).setAllowedOverRoaming(false)
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalFilesDir(context, Environment.DIRECTORY_MUSIC,
                        "discover-" + track.onlineId() + ".mp3");
        long id = manager.enqueue(request);
        try {
            String metadata = DiscoverBackend.trackJson(track).put("download_id", id).toString();
            if (!store.edit().putString(track.onlineId(), metadata).commit()) {
                manager.remove(id);
                throw new IllegalStateException("Could not save download");
            }
        } catch (JSONException error) {
            manager.remove(id);
            throw new IllegalStateException("Could not save download", error);
        }
    }

    Item item(String trackId) {
        String value = store.getString(trackId, null);
        if (value == null) {
            return null;
        }
        try {
            JSONObject row = new JSONObject(value);
            TrackLibrary.Track track = DiscoverBackend.readTrack(row);
            if (track == null) {
                return null;
            }
            long id = row.getLong("download_id");
            try (Cursor cursor = manager.query(new DownloadManager.Query().setFilterById(id))) {
                if (cursor != null && cursor.moveToFirst()) {
                    return new Item(track, id,
                            cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)),
                            cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)),
                            cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)));
                }
            }
            return new Item(track, id, DownloadManager.STATUS_FAILED, 0, 0);
        } catch (JSONException | RuntimeException ignored) {
            return null;
        }
    }

    List<Item> all() {
        List<Item> items = new ArrayList<>();
        for (String id : store.getAll().keySet()) {
            Item item = item(id);
            if (item != null) {
                items.add(item);
            }
        }
        items.sort((left, right) -> Long.compare(right.downloadId(), left.downloadId()));
        return items;
    }

    boolean hasSavedMetadata() {
        return !store.getAll().isEmpty();
    }

    Uri source(TrackLibrary.Track track) {
        if (!track.isOnline() || track.isPrivateOnline()) {
            return null;
        }
        Item item = item(track.onlineId());
        if (item == null || !item.ready() || !item.track().uri().equals(track.uri())) {
            return null;
        }
        try {
            return manager.getUriForDownloadedFile(item.downloadId());
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    void remove(String trackId) {
        Item item = item(trackId);
        if (item != null) {
            manager.remove(item.downloadId());
        }
        store.edit().remove(trackId).apply();
    }

    void retainEligible(Map<String, Uri> eligibleSources) {
        for (String id : store.getAll().keySet()) {
            Item item = item(id);
            if (item == null || !item.track().uri().equals(eligibleSources.get(id))) {
                remove(id);
            }
        }
    }
}
