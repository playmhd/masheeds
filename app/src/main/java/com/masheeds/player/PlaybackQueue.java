package com.masheeds.player;

import com.masheeds.player.TrackLibrary.Track;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

final class PlaybackQueue {
    private List<Track> items = Collections.emptyList();
    private List<Track> playlistOrder = Collections.emptyList();
    private final Set<String> visited = new LinkedHashSet<>();
    private String playlistId;
    private int index = -1;

    void select(List<Track> tracks, int selected, String source) {
        items = new ArrayList<>(tracks);
        playlistOrder = source == null ? Collections.emptyList() : items;
        index = selected;
        playlistId = source;
        visited.clear();
    }

    void clear() {
        select(Collections.emptyList(), -1, null);
    }

    Track current() {
        return index < 0 ? null : items.get(index);
    }

    String playlistId() {
        return playlistId;
    }

    boolean matches(List<Track> tracks, String source) {
        return Objects.equals(playlistId, source) && (source != null || items.equals(tracks));
    }

    void visitCurrent() {
        if (playlistId != null && current() != null) {
            visited.add(key(current()));
        }
    }

    boolean updatePlaylist(String id, List<Track> tracks) {
        if (!id.equals(playlistId) || current() == null || playlistOrder.equals(tracks)) {
            return false;
        }
        Track current = current();
        String currentKey = key(current);
        playlistOrder = new ArrayList<>(tracks);
        Map<String, Track> available = new HashMap<>();
        for (Track track : tracks) {
            available.put(key(track), track);
        }

        List<Track> reordered = new ArrayList<>();
        for (String played : visited) {
            if (!played.equals(currentKey) && available.containsKey(played)) {
                reordered.add(available.get(played));
            }
        }
        index = reordered.size();
        reordered.add(current);
        for (Track track : tracks) {
            String key = key(track);
            if (!key.equals(currentKey) && !visited.contains(key)) {
                reordered.add(track);
            }
        }
        items = reordered;
        return true;
    }

    boolean detachPlaylist(String id) {
        if (!id.equals(playlistId)) {
            return false;
        }
        playlistId = null;
        playlistOrder = Collections.emptyList();
        visited.clear();
        return true;
    }

    boolean removeTrack(String trackId) {
        Track current = current();
        if (!items.removeIf(track -> key(track).equals(trackId))) {
            return false;
        }
        index = items.indexOf(current);
        if (playlistId != null) {
            playlistOrder = new ArrayList<>(playlistOrder);
            playlistOrder.removeIf(track -> key(track).equals(trackId));
            visited.remove(trackId);
        }
        return true;
    }

    boolean canNext(boolean repeatAll) {
        return current() != null && (index + 1 < items.size() || (repeatAll && canWrap()));
    }

    boolean canPrevious(boolean repeatAll) {
        return current() != null && (index > 0 || (repeatAll && canWrap()));
    }

    boolean repeatsOnlyTrack() {
        List<Track> order = cycleOrder();
        return current() != null && order.size() == 1 && key(order.get(0)).equals(key(current()));
    }

    void next() {
        visitCurrent();
        if (index + 1 < items.size()) {
            index++;
        } else {
            select(cycleOrder(), 0, playlistId);
        }
    }

    void previous() {
        visitCurrent();
        if (index > 0) {
            index--;
        } else {
            List<Track> order = cycleOrder();
            int last = order.size() - 1;
            if (key(order.get(last)).equals(key(current()))) {
                last--;
            }
            select(order, last, playlistId);
        }
    }

    private boolean canWrap() {
        List<Track> order = cycleOrder();
        return order.size() > 1 || (order.size() == 1 && !repeatsOnlyTrack());
    }

    private List<Track> cycleOrder() {
        return playlistId == null ? items : playlistOrder;
    }

    private static String key(Track track) {
        return track.libraryUri().toString();
    }
}
