package com.masheeds.player;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

final class PlaylistStore {
    record Playlist(String id, String name, List<String> tracks) {}

    private static final String KEY = "items";

    private PlaylistStore() {}

    static List<Playlist> load(Context context) {
        List<Playlist> playlists = new ArrayList<>();
        try {
            JSONArray saved = new JSONArray(preferences(context).getString(KEY, "[]"));
            for (int index = 0; index < saved.length(); index++) {
                JSONObject item = saved.optJSONObject(index);
                if (item == null || item.optString("id").isEmpty() || item.optString("name").isEmpty()) {
                    continue;
                }
                List<String> tracks = new ArrayList<>();
                JSONArray uris = item.optJSONArray("tracks");
                for (int track = 0; uris != null && track < uris.length(); track++) {
                    String uri = uris.optString(track);
                    if (!uri.isEmpty()) {
                        tracks.add(uri);
                    }
                }
                playlists.add(new Playlist(item.optString("id"), item.optString("name"), tracks));
            }
        } catch (JSONException ignored) {
            // An unreadable list cannot be displayed.
        }
        return playlists;
    }

    static void save(Context context, List<Playlist> playlists) {
        JSONArray saved = new JSONArray();
        for (Playlist playlist : playlists) {
            JSONObject item = new JSONObject();
            try {
                item.put("id", playlist.id());
                item.put("name", playlist.name());
                item.put("tracks", new JSONArray(playlist.tracks()));
            } catch (JSONException error) {
                throw new IllegalStateException("Could not encode playlist", error);
            }
            saved.put(item);
        }
        preferences(context).edit().putString(KEY, saved.toString()).apply();
    }

    private static SharedPreferences preferences(Context context) {
        return context.getSharedPreferences("playlists", Context.MODE_PRIVATE);
    }
}
