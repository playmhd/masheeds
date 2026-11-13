package com.masheeds.player;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

final class ListeningHistory {
    private static final int LIMIT = 75;
    private static final long RESUME_MIN = 15_000L;

    record Entry(String id, int position, long duration) {
        boolean unfinished() {
            return position >= RESUME_MIN && duration - position >= 5_000L;
        }
    }

    private ListeningHistory() {}

    static List<Entry> read(Context context) {
        List<Entry> result = new ArrayList<>();
        try {
            JSONArray rows = new JSONArray(preferences(context).getString("items", "[]"));
            for (int index = 0; index < rows.length(); index++) {
                JSONObject row = rows.optJSONObject(index);
                if (row != null && !row.optString("id").isEmpty()) {
                    result.add(new Entry(row.optString("id"), row.optInt("position"), row.optLong("duration")));
                }
            }
        } catch (JSONException ignored) {
            // A damaged history does not prevent playback.
        }
        return result;
    }

    static void save(Context context, TrackLibrary.Track track, int position, long duration) {
        if (track.isPrivateOnline()) {
            return;
        }
        String id = track.libraryUri().toString();
        List<Entry> entries = read(context);
        Entry next = new Entry(id, position, duration);
        if (!entries.isEmpty() && next.equals(entries.get(0))) {
            return;
        }
        entries.removeIf(entry -> entry.id().equals(id));
        entries.add(0, next);
        JSONArray rows = new JSONArray();
        try {
            for (Entry entry : entries.subList(0, Math.min(LIMIT, entries.size()))) {
                rows.put(new JSONObject().put("id", entry.id()).put("position", entry.position())
                        .put("duration", entry.duration()));
            }
            preferences(context).edit().putString("items", rows.toString()).apply();
        } catch (JSONException error) {
            throw new IllegalStateException("Could not encode listening history", error);
        }
    }

    private static SharedPreferences preferences(Context context) {
        return context.getSharedPreferences("listening_history", Context.MODE_PRIVATE);
    }
}
