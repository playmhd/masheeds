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
    record Snapshot(List<Track> tracks, int currentIndex) {
        boolean canMove(int position) {
            return currentIndex >= 0 && position >= 0 && position < tracks.size() && position != currentIndex;
        }
    }

    private List<Track> items = Collections.emptyList();
    private List<Track> sourceOrder = Collections.emptyList();
    private final Set<String> visited = new LinkedHashSet<>();
    private String playlistId;
    private int index = -1;
    private boolean shuffle;

    void select(List<Track> tracks, int selected, String source) {
        sourceOrder = new ArrayList<>(tracks);
        items = new ArrayList<>(tracks);
        index = selected;
        playlistId = source;
        visited.clear();
        if (shuffle && current() != null) {
            Track current = items.remove(index);
            Collections.shuffle(items);
            items.add(0, current);
            index = 0;
        }
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
        return Objects.equals(playlistId, source) && (source != null || sourceOrder.equals(tracks));
    }

    Snapshot snapshot() {
        return new Snapshot(Collections.unmodifiableList(new ArrayList<>(items)), index);
    }

    boolean canJumpTo(int position) {
        return index >= 0 && position >= 0 && position < items.size() && position != index;
    }

    boolean jumpTo(int position) {
        if (!canJumpTo(position)) {
            return false;
        }
        // A jump advances past the intervening tracks for this pass through the queue.
        visitCurrent();
        for (int skipped = index + 1; skipped < position; skipped++) {
            visited.add(key(items.get(skipped)));
        }
        index = position;
        return true;
    }

    boolean playNext(Track track) {
        if (current() == null || key(current()).equals(key(track))) {
            return false;
        }
        List<Track> order = new ArrayList<>(items);
        List<Track> played = order.subList(0, index);
        played.removeIf(item -> key(item).equals(key(track)));
        index = played.size();
        order.subList(index + 1, order.size()).removeIf(item -> key(item).equals(key(track)));
        order.add(index + 1, track);
        applyManualOrder(order);
        return true;
    }

    boolean moveQueuedTrack(int from, int to) {
        if (!canJumpTo(from) || to < 0 || to >= items.size() || from == to) {
            return false;
        }
        List<Track> order = new ArrayList<>(items);
        order.add(to, order.remove(from));
        if (from < index && to >= index) {
            index--;
        } else if (from > index && to <= index) {
            index++;
        }
        applyManualOrder(order);
        visited.clear();
        for (int position = 0; position <= index; position++) {
            visited.add(key(order.get(position)));
        }
        return true;
    }

    boolean removeUpcoming(int position) {
        if (index < 0 || position <= index || position >= items.size()) {
            return false;
        }
        List<Track> order = new ArrayList<>(items);
        order.remove(position);
        applyManualOrder(order);
        return true;
    }

    private void applyManualOrder(List<Track> order) {
        items = order;
        sourceOrder = new ArrayList<>(order);
        playlistId = null;
    }

    void visitCurrent() {
        if (current() != null) {
            visited.add(key(current()));
        }
    }

    boolean setShuffle(boolean enabled) {
        if (shuffle == enabled) {
            return false;
        }
        shuffle = enabled;
        Track current = current();
        if (current != null) {
            if (enabled || !restoreSourceOrder(current)) {
                rebuildUpcoming(current);
            }
        }
        return true;
    }

    private boolean restoreSourceOrder(Track current) {
        String currentKey = key(current);
        for (int position = 0; position < sourceOrder.size(); position++) {
            if (key(sourceOrder.get(position)).equals(currentKey)) {
                items = new ArrayList<>(sourceOrder);
                items.set(position, current);
                index = position;
                return true;
            }
        }
        return false;
    }

    boolean updatePlaylist(String id, List<Track> tracks) {
        if (!id.equals(playlistId) || current() == null || sourceOrder.equals(tracks)) {
            return false;
        }
        sourceOrder = new ArrayList<>(tracks);
        rebuildUpcoming(current());
        return true;
    }

    private void rebuildUpcoming(Track current) {
        String currentKey = key(current);
        Map<String, Track> available = new HashMap<>();
        for (Track track : sourceOrder) {
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
        for (Track track : sourceOrder) {
            String key = key(track);
            if (!key.equals(currentKey) && !visited.contains(key)) {
                reordered.add(track);
            }
        }
        if (shuffle) {
            Collections.shuffle(reordered.subList(index + 1, reordered.size()));
        }
        items = reordered;
    }

    boolean detachPlaylist(String id) {
        if (!id.equals(playlistId)) {
            return false;
        }
        playlistId = null;
        if (!shuffle) {
            sourceOrder = new ArrayList<>(items);
        }
        visited.clear();
        return true;
    }

    boolean removeTrack(String trackId) {
        Track current = current();
        if (!items.removeIf(track -> key(track).equals(trackId))) {
            return false;
        }
        index = items.indexOf(current);
        sourceOrder.removeIf(track -> key(track).equals(trackId));
        visited.remove(trackId);
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
            wrap(true);
        }
    }

    void previous() {
        visitCurrent();
        if (index > 0) {
            index--;
        } else {
            wrap(false);
        }
    }

    private void wrap(boolean forward) {
        List<Track> order = cycleOrder();
        int position = forward ? 0 : order.size() - 1;
        if (shuffle) {
            List<Track> shuffled = new ArrayList<>(order);
            Collections.shuffle(shuffled);
            if (shuffled.size() > 1 && key(shuffled.get(position)).equals(key(current()))) {
                Collections.swap(shuffled, position, forward ? 1 : position - 1);
            }
            items = shuffled;
            index = position;
            visited.clear();
        } else {
            if (!forward && key(order.get(position)).equals(key(current()))) {
                position--;
            }
            select(order, position, playlistId);
        }
    }

    private boolean canWrap() {
        List<Track> order = cycleOrder();
        return order.size() > 1 || (order.size() == 1 && !repeatsOnlyTrack());
    }

    private List<Track> cycleOrder() {
        return playlistId == null && !shuffle ? items : sourceOrder;
    }

    private static String key(Track track) {
        return track.libraryUri().toString();
    }
}
