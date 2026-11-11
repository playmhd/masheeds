package com.masheeds.player;

import android.content.Context;
import android.content.SharedPreferences;

import com.masheeds.player.TrackLibrary.Track;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

final class RecentlyDeleted {
    private static final String KEY = "entries";
    private static final long RETENTION = TimeUnit.DAYS.toMillis(30);

    record Entry(String title, String playlistName, long removedAt) {}

    private RecentlyDeleted() {}

    static List<Entry> load(Context context) {
        SharedPreferences preferences = preferences(context);
        JSONArray saved = read(preferences);
        List<Entry> entries = recentEntries(saved, System.currentTimeMillis());
        if (entries.size() != saved.length()) {
            save(preferences, entries);
        }
        return entries;
    }

    static void record(Context context, List<Track> removed, String playlistName) {
        if (removed.isEmpty()) {
            return;
        }
        SharedPreferences preferences = preferences(context);
        long now = System.currentTimeMillis();
        List<Entry> entries = new ArrayList<>();
        for (Track track : removed) {
            entries.add(new Entry(track.title(), playlistName, now));
        }
        entries.addAll(recentEntries(read(preferences), now));
        save(preferences, entries);
    }

    private static JSONArray read(SharedPreferences preferences) {
        try {
            return new JSONArray(preferences.getString(KEY, "[]"));
        } catch (JSONException ignored) {
            return new JSONArray();
        }
    }

    private static List<Entry> recentEntries(JSONArray saved, long now) {
        List<Entry> entries = new ArrayList<>();
        for (int index = 0; index < saved.length(); index++) {
            JSONObject item = saved.optJSONObject(index);
            if (item != null) {
                long removedAt = item.optLong("removed_at");
                if (removedAt > 0 && removedAt >= now - RETENTION) {
                    entries.add(new Entry(item.optString("title"), item.optString("playlist", null), removedAt));
                }
            }
        }
        return entries;
    }

    private static void save(SharedPreferences preferences, List<Entry> entries) {
        JSONArray saved = new JSONArray();
        for (Entry entry : entries) {
            JSONObject item = new JSONObject();
            try {
                item.put("title", entry.title());
                item.put("playlist", entry.playlistName());
                item.put("removed_at", entry.removedAt());
            } catch (JSONException error) {
                throw new IllegalStateException("Could not encode removal history", error);
            }
            saved.put(item);
        }
        preferences.edit().putString(KEY, saved.toString()).apply();
    }

    private static SharedPreferences preferences(Context context) {
        return context.getSharedPreferences("recently_deleted", Context.MODE_PRIVATE);
    }
}
