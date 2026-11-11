package com.masheeds.player;

import android.content.ContentUris;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.util.LruCache;
import android.util.Size;

import com.masheeds.player.TrackLibrary.Track;

import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Consumer;

final class ArtworkLoader {
    static final int THUMBNAIL_SIZE = 160;
    static final int COVER_SIZE = 896;

    private record Key(Uri uri, Uri libraryUri, Uri artworkUri, long albumId, long modified, long coverRevision,
                       int size) {}
    private record Result(Bitmap bitmap) {}

    private static final ExecutorService WORKERS = Executors.newFixedThreadPool(2);
    private static final ExecutorService ONLINE_WORKERS = Executors.newFixedThreadPool(2);
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final LruCache<Key, Result> CACHE = new LruCache<>(
            (int) Math.min(12 * 1024 * 1024, Runtime.getRuntime().maxMemory() / 16)) {
        @Override protected int sizeOf(Key key, Result result) {
            return result.bitmap() == null ? 4_096 : Math.max(4_096, result.bitmap().getAllocationByteCount());
        }
    };

    private ArtworkLoader() {}

    static Future<?> load(Context context, Track track, int size, Consumer<Bitmap> onLoaded) {
        Key key = new Key(track.uri(), track.libraryUri(), track.artworkUri(), track.albumId(), track.modified(),
                track.isOnline() ? 0 : CustomArtwork.revision(), size);
        Result cached = CACHE.get(key);
        if (cached != null) {
            MAIN.post(() -> onLoaded.accept(cached.bitmap()));
            return null;
        }

        Context app = context.getApplicationContext();
        ExecutorService worker = track.isOnline() ? ONLINE_WORKERS : WORKERS;
        return worker.submit(() -> {
            Result result = CACHE.get(key);
            if (result == null) {
                result = new Result(read(app, track, size));
                if (result.bitmap() != null || !track.isOnline() || track.artworkUri() == null) {
                    CACHE.put(key, result);
                }
            }
            Bitmap bitmap = result.bitmap();
            MAIN.post(() -> onLoaded.accept(bitmap));
        });
    }

    static Future<?> loadOverride(Context context, String key, int size, Consumer<Bitmap> onLoaded) {
        Context app = context.getApplicationContext();
        return WORKERS.submit(() -> {
            Bitmap bitmap = CustomArtwork.loadOverride(app, key, size);
            MAIN.post(() -> onLoaded.accept(bitmap));
        });
    }

    private static Bitmap read(Context context, Track track, int size) {
        if (track.isOnline()) {
            return readOnlineArtwork(track.artworkUri(), size);
        }
        Bitmap bitmap = CustomArtwork.load(context, track, size);
        if (bitmap != null) {
            return bitmap;
        }
        bitmap = readEmbedded(context, track.uri(), size);
        if (bitmap != null) {
            return bitmap;
        }
        if (track.albumId() > 0) {
            Uri album = ContentUris.withAppendedId(MediaStore.Audio.Albums.EXTERNAL_CONTENT_URI, track.albumId());
            bitmap = readThumbnail(context, album, size);
        }
        if (bitmap == null && MediaStore.AUTHORITY.equals(track.libraryUri().getAuthority())) {
            bitmap = readThumbnail(context, track.libraryUri(), size);
        }
        return bitmap;
    }

    private static Bitmap readOnlineArtwork(Uri uri, int size) {
        if (uri == null) {
            return null;
        }
        try {
            return decode(DiscoverBackend.downloadArtwork(uri), size);
        } catch (IOException | RuntimeException ignored) {
            return null;
        }
    }

    private static Bitmap readEmbedded(Context context, Uri uri, int size) {
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            retriever.setDataSource(context, uri);
            byte[] encoded = retriever.getEmbeddedPicture();
            return encoded == null ? null : decode(encoded, size);
        } catch (RuntimeException ignored) {
            return null;
        } finally {
            try {
                retriever.release();
            } catch (IOException ignored) {
                // The decoded picture is independent of the retriever.
            }
        }
    }

    private static Bitmap decode(byte[] encoded, int size) {
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(encoded, 0, encoded.length, options);
        int longest = Math.max(options.outWidth, options.outHeight);
        if (longest <= 0) {
            return null;
        }
        options.inJustDecodeBounds = false;
        options.inSampleSize = 1;
        while (longest / (long) options.inSampleSize > 3L * size / 2) {
            options.inSampleSize *= 2;
        }
        Bitmap bitmap = BitmapFactory.decodeByteArray(encoded, 0, encoded.length, options);
        return bitmap == null ? null : fit(bitmap, size);
    }

    private static Bitmap readThumbnail(Context context, Uri uri, int size) {
        if (Build.VERSION.SDK_INT < 29) {
            return null;
        }
        try {
            Bitmap bitmap = context.getContentResolver().loadThumbnail(uri, new Size(size, size), null);
            return fit(bitmap, size);
        } catch (IOException | RuntimeException ignored) {
            return null;
        }
    }

    static Bitmap fit(Bitmap bitmap, int size) {
        int longest = Math.max(bitmap.getWidth(), bitmap.getHeight());
        if (longest <= size) {
            return bitmap;
        }
        int width = Math.max(1, Math.round(bitmap.getWidth() * (size / (float) longest)));
        int height = Math.max(1, Math.round(bitmap.getHeight() * (size / (float) longest)));
        return Bitmap.createScaledBitmap(bitmap, width, height, true);
    }
}
