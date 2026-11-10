package com.masheeds.player;

import android.content.Context;
import android.content.SharedPreferences;

record PlaybackCheckpoint(String trackId, int position, String playlistId) {
    static PlaybackCheckpoint load(Context context) {
        SharedPreferences preferences = preferences(context);
        String track = preferences.getString("last_track", null);
        return track == null ? null : new PlaybackCheckpoint(track,
                Math.max(0, preferences.getInt("last_position", 0)),
                preferences.getString("last_playlist", null));
    }

    void save(Context context) {
        preferences(context).edit().putString("last_track", trackId)
                .putInt("last_position", position).putString("last_playlist", playlistId).apply();
    }

    static void forgetTrack(Context context, String trackId) {
        SharedPreferences preferences = preferences(context);
        if (trackId.equals(preferences.getString("last_track", null))) {
            preferences.edit().remove("last_track").remove("last_position").remove("last_playlist").apply();
        }
    }

    private static SharedPreferences preferences(Context context) {
        return context.getSharedPreferences("playback", Context.MODE_PRIVATE);
    }
}
