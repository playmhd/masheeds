package com.masheeds.player;

import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.Context;
import android.content.SharedPreferences;
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
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

final class TrackLibrary {
    private static final String REMOVED_TRACKS = "removed_tracks";

    record Track(Uri uri, Uri libraryUri, String title, String artist, long duration, long albumId, long modified) {
        String displayTitle(Context context) {
            return title.isEmpty() ? context.getString(R.string.untitled_track) : title;
        }
    }

    static List<Track> load(Context context, boolean scanDevice) {
        ContentResolver resolver = context.getContentResolver();
        Set<String> removed = preferences(context).getStringSet(REMOVED_TRACKS, Collections.emptySet());
        List<Track> tracks = new ArrayList<>();
        if (scanDevice) {
            loadDeviceTracks(resolver, tracks);
            tracks.removeIf(track -> removed.contains(track.libraryUri().toString()));
        }
        for (UriPermission permission : resolver.getPersistedUriPermissions()) {
            if (Thread.currentThread().isInterrupted()) {
                return tracks;
            }
            Uri uri = permission.getUri();
            if (!permission.isReadPermission()) {
                continue;
            }
            Uri libraryUri = libraryUri(context, uri);
            if (removed.contains(libraryUri.toString())
                    || tracks.stream().anyMatch(track -> libraryUri.equals(track.libraryUri()))) {
                continue;
            }
            Track track = readDocument(context, uri, libraryUri);
            if (track != null) {
                tracks.add(track);
            }
        }

        Collator collator = Collator.getInstance(context.getResources().getConfiguration().getLocales().get(0));
        tracks.sort((left, right) -> {
            int titleOrder = collator.compare(left.displayTitle(context), right.displayTitle(context));
            return titleOrder != 0 ? titleOrder : collator.compare(left.artist(), right.artist());
        });
        return tracks;
    }

    static void remove(Context context, Track track) {
        SharedPreferences preferences = preferences(context);
        Set<String> removed = new HashSet<>(preferences.getStringSet(REMOVED_TRACKS, Collections.emptySet()));
        if (removed.add(track.libraryUri().toString())) {
            preferences.edit().putStringSet(REMOVED_TRACKS, removed).apply();
        }
    }

    static void restoreDocument(Context context, Uri document) {
        SharedPreferences preferences = preferences(context);
        Set<String> removed = new HashSet<>(preferences.getStringSet(REMOVED_TRACKS, Collections.emptySet()));
        if (!removed.isEmpty() && removed.remove(libraryUri(context, document).toString())) {
            preferences.edit().putStringSet(REMOVED_TRACKS, removed).apply();
        }
    }

    private static SharedPreferences preferences(Context context) {
        return context.getSharedPreferences("library", Context.MODE_PRIVATE);
    }

    private static void loadDeviceTracks(ContentResolver resolver, List<Track> tracks) {
        Uri collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI;
        String[] columns = {
                MediaStore.Audio.Media._ID,
                MediaStore.Audio.Media.TITLE,
                MediaStore.Audio.Media.DISPLAY_NAME,
                MediaStore.Audio.Media.ARTIST,
                MediaStore.Audio.Media.DURATION,
                MediaStore.Audio.Media.ALBUM_ID,
                MediaStore.Audio.Media.ALBUM,
                MediaStore.Audio.Media.DATE_MODIFIED
        };
        try (Cursor cursor = resolver.query(collection, columns,
                MediaStore.Audio.Media.IS_MUSIC + " != 0", null, null)) {
            while (cursor != null && !Thread.currentThread().isInterrupted() && cursor.moveToNext()) {
                Uri uri = ContentUris.withAppendedId(collection, cursor.getLong(0));
                tracks.add(new Track(uri, uri, title(cursor.getString(1), cursor.getString(2)),
                        artist(cursor.getString(3)), cursor.getLong(4),
                        hasMetadata(cursor.getString(6)) ? cursor.getLong(5) : 0, cursor.getLong(7)));
            }
        } catch (SecurityException ignored) {
            // Permission can be revoked while a scan is in progress.
        }
    }

    private static Uri libraryUri(Context context, Uri uri) {
        if (Build.VERSION.SDK_INT < 29) {
            return uri;
        }
        try {
            Uri mediaUri = MediaStore.getMediaUri(context, uri);
            if (mediaUri != null && mediaUri.getPathSegments().contains("audio")) {
                return ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                        ContentUris.parseId(mediaUri));
            }
        } catch (IllegalArgumentException | SecurityException ignored) {
            // Some document providers do not have a corresponding MediaStore URI.
        }
        return uri;
    }

    private static Track readDocument(Context context, Uri uri, Uri libraryUri) {
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
        long albumId = indexedAlbumId(context.getContentResolver(), libraryUri);
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            retriever.setDataSource(context, uri);
            String name = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE);
            String artist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST);
            String duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            return new Track(uri, libraryUri, title(name, fileName), artist(artist),
                    duration == null ? 0 : Long.parseLong(duration), albumId, 0);
        } catch (RuntimeException ignored) {
            return new Track(uri, libraryUri, title(null, fileName), "", 0, albumId, 0);
        } finally {
            try {
                retriever.release();
            } catch (IOException ignored) {
                // Metadata read above is still usable.
            }
        }
    }

    private static long indexedAlbumId(ContentResolver resolver, Uri libraryUri) {
        if (!MediaStore.AUTHORITY.equals(libraryUri.getAuthority())) {
            return 0;
        }
        try (Cursor cursor = resolver.query(libraryUri,
                new String[]{MediaStore.Audio.Media.ALBUM_ID, MediaStore.Audio.Media.ALBUM}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst() && hasMetadata(cursor.getString(1))) {
                return cursor.getLong(0);
            }
        } catch (IllegalArgumentException | SecurityException ignored) {
            // A document grant does not always include access to the corresponding index row.
        }
        return 0;
    }

    private static String title(String title, String fileName) {
        if (hasMetadata(title)) {
            return title.trim();
        }
        if (hasMetadata(fileName)) {
            int extension = fileName.lastIndexOf('.');
            return extension > 0 ? fileName.substring(0, extension) : fileName;
        }
        return "";
    }

    private static String artist(String artist) {
        return hasMetadata(artist) ? artist.trim() : "";
    }

    private static boolean hasMetadata(String text) {
        return text != null && !text.trim().isEmpty() && !MediaStore.UNKNOWN_STRING.equals(text);
    }
}
