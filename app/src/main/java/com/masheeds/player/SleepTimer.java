package com.masheeds.player;

final class SleepTimer {
    private long deadline;
    private int minutes;
    private boolean endOfTrack;

    void start(int durationMinutes, long now) {
        minutes = durationMinutes;
        deadline = now + durationMinutes * 60_000L;
        endOfTrack = false;
    }

    void stopAtTrackEnd() {
        clear();
        endOfTrack = true;
    }

    void clear() {
        deadline = 0;
        minutes = 0;
        endOfTrack = false;
    }

    int minutes() { return minutes; }
    boolean endsWithTrack() { return endOfTrack; }
    boolean isDue(long now) { return minutes > 0 && now >= deadline; }
    long remaining(long now) { return Math.max(0, deadline - now); }
}
