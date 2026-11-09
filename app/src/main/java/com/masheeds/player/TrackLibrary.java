package com.masheeds.player;

import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.Context;
import android.content.UriPermission;
import android.database.Cursor;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Build;
import android.provider.MediaStore;
import android.provider.OpenableColumns;

import java.io.IOException;
import java.text.Collator;
import java.util.ArrayList;
import java.util.List;

final class TrackLibrary {
    record Track(Uri uri, String title, String artist, long duration) {}

    static List<Track> load(Context context, boolean scanDevice) {
        ContentResolver resolver = context.getContentResolver();
        List<Track> tracks = new ArrayList<>();
        if (scanDevice) {
            loadDeviceTracks(resolver, tracks);
        }
        for (UriPermission permission : resolver.getPersistedUriPermissions()) {
            if (Thread.currentThread().isInterrupted()) {
                return tracks;
            }
            Uri uri = permission.getUri();
            if (permission.isReadPermission() && !isIndexed(context, uri, tracks)) {
                Track track = readDocument(context, uri);
                if (track != null) {
                    tracks.add(track);
                }
            }
        }

        Collator collator = Collator.getInstance();
        tracks.sort((left, right) -> {
            int titleOrder = collator.compare(left.title(), right.title());
            return titleOrder != 0 ? titleOrder : collator.compare(left.artist(), right.artist());
        });
        return tracks;
    }

    private static void loadDeviceTracks(ContentResolver resolver, List<Track> tracks) {
        Uri collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI;
        String[] columns = {
                MediaStore.Audio.Media._ID,
                MediaStore.Audio.Media.TITLE,
                MediaStore.Audio.Media.DISPLAY_NAME,
                MediaStore.Audio.Media.ARTIST,
                MediaStore.Audio.Media.DURATION
        };
        try (Cursor cursor = resolver.query(collection, columns,
                MediaStore.Audio.Media.IS_MUSIC + " != 0", null, null)) {
            while (cursor != null && !Thread.currentThread().isInterrupted() && cursor.moveToNext()) {
                Uri uri = ContentUris.withAppendedId(collection, cursor.getLong(0));
                tracks.add(new Track(uri, title(cursor.getString(1), cursor.getString(2)),
                        artist(cursor.getString(3)), cursor.getLong(4)));
            }
        } catch (SecurityException ignored) {
            // Permission can be revoked while a scan is in progress.
        }
    }

    private static boolean isIndexed(Context context, Uri uri, List<Track> tracks) {
        if (Build.VERSION.SDK_INT < 29 || tracks.isEmpty()) {
            return false;
        }
        try {
            Uri mediaUri = MediaStore.getMediaUri(context, uri);
            if (mediaUri != null && mediaUri.getPathSegments().contains("audio")) {
                String id = mediaUri.getLastPathSegment();
                return tracks.stream().anyMatch(track -> id.equals(track.uri().getLastPathSegment()));
            }
        } catch (IllegalArgumentException | SecurityException ignored) {
            // Some document providers do not have a corresponding MediaStore URI.
        }
        return false;
    }

    private static Track readDocument(Context context, Uri uri) {
        String fileName;
        try (Cursor cursor = context.getContentResolver().query(uri,
                new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor == null || !cursor.moveToFirst()) {
                return null;
            }
            fileName = cursor.getString(0);
        } catch (IllegalArgumentException | SecurityException ignored) {
            return null;
        }
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            retriever.setDataSource(context, uri);
            String name = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE);
            String artist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST);
            String duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            return new Track(uri, title(name, fileName), artist(artist),
                    duration == null ? 0 : Long.parseLong(duration));
        } catch (RuntimeException ignored) {
            return new Track(uri, title(null, fileName), "", 0);
        } finally {
            try {
                retriever.release();
            } catch (IOException ignored) {
                // Metadata read above is still usable.
            }
        }
    }

    private static String title(String title, String fileName) {
        if (hasMetadata(title)) {
            return title.trim();
        }
        if (hasMetadata(fileName)) {
            int extension = fileName.lastIndexOf('.');
            return extension > 0 ? fileName.substring(0, extension) : fileName;
        }
        return "Untitled track";
    }

    private static String artist(String artist) {
        return hasMetadata(artist) ? artist.trim() : "";
    }

    private static boolean hasMetadata(String text) {
        return text != null && !text.trim().isEmpty() && !MediaStore.UNKNOWN_STRING.equals(text);
    }
}
