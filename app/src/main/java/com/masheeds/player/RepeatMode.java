package com.masheeds.player;

import android.content.Context;
import android.content.SharedPreferences;

enum RepeatMode {
    OFF(R.string.repeat_off),
    ALL(R.string.repeat_all),
    ONE(R.string.repeat_one);

    private static final String PREFERENCES = "playback";
    private static final String KEY = "repeat_mode";

    final int label;

    RepeatMode(int label) {
        this.label = label;
    }

    RepeatMode next() {
        return switch (this) {
            case OFF -> ALL;
            case ALL -> ONE;
            case ONE -> OFF;
        };
    }

    static RepeatMode load(Context context) {
        String stored = preferences(context).getString(KEY, OFF.name());
        for (RepeatMode mode : values()) {
            if (mode.name().equals(stored)) {
                return mode;
            }
        }
        return OFF;
    }

    void save(Context context) {
        preferences(context).edit().putString(KEY, name()).apply();
    }

    private static SharedPreferences preferences(Context context) {
        return context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE);
    }
}
