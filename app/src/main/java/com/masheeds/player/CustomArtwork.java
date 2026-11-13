package com.masheeds.player;

import android.content.ContentResolver;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ImageDecoder;
import android.graphics.Matrix;
import android.media.ExifInterface;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.AtomicFile;
import android.util.Size;

import com.masheeds.player.TrackLibrary.Track;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

final class CustomArtwork {
    private static final Object FILE_LOCK = new Object();
    private static final ExecutorService EDITOR = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final Set<Consumer<String>> LISTENERS = new LinkedHashSet<>();
    private static long revision;

    private CustomArtwork() {}

    static String trackKey(Track track) {
        return "track:" + track.libraryUri();
    }

    static String albumKey(Track track) {
        return track.albumId() > 0 ? "album:" + track.albumId() : null;
    }

    static String playlistKey(String playlistId) {
        return "playlist:" + playlistId;
    }

    static long revision() {
        return revision;
    }

    static void addListener(Consumer<String> listener) {
        LISTENERS.add(listener);
    }

    static void removeListener(Consumer<String> listener) {
        LISTENERS.remove(listener);
    }

    static boolean hasCover(Context context, String key) {
        return key != null && coverFile(context, key).isFile();
    }

    static Bitmap load(Context context, Track track, int size) {
        Bitmap bitmap = loadOverride(context, trackKey(track), size);
        return bitmap != null ? bitmap : loadOverride(context, albumKey(track), size);
    }

    // A null image removes the selected override.
    static void update(Context context, String key, Uri image, Consumer<Boolean> onComplete) {
        Context app = context.getApplicationContext();
        EDITOR.execute(() -> {
            boolean changed;
            try {
                if (image == null) {
                    synchronized (FILE_LOCK) {
                        new AtomicFile(coverFile(app, key)).delete();
                        changed = !hasCover(app, key);
                    }
                } else {
                    write(app, key, decodeSelection(app.getContentResolver(), image, ArtworkLoader.COVER_SIZE));
                    changed = true;
                }
            } catch (IOException | RuntimeException | OutOfMemoryError ignored) {
                changed = false;
            }
            boolean success = changed;
            MAIN.post(() -> {
                if (success) {
                    revision++;
                    for (Consumer<String> listener : new ArrayList<>(LISTENERS)) {
                        listener.accept(key);
                    }
                }
                onComplete.accept(success);
            });
        });
    }

    static Bitmap loadOverride(Context context, String key, int size) {
        if (key == null) {
            return null;
        }
        try (InputStream input = openCover(context, key)) {
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inSampleSize = Math.max(1, Integer.highestOneBit(ArtworkLoader.COVER_SIZE / size));
            Bitmap bitmap = BitmapFactory.decodeStream(input, null, options);
            return bitmap == null ? null : ArtworkLoader.fit(bitmap, size);
        } catch (IOException | RuntimeException ignored) {
            return null;
        }
    }

    private static InputStream openCover(Context context, String key) throws IOException {
        synchronized (FILE_LOCK) {
            return new AtomicFile(coverFile(context, key)).openRead();
        }
    }

    private static void write(Context context, String key, Bitmap bitmap) throws IOException {
        if (bitmap == null) {
            throw new IOException("Could not decode cover");
        }
        synchronized (FILE_LOCK) {
            AtomicFile file = new AtomicFile(coverFile(context, key));
            FileOutputStream output = file.startWrite();
            boolean finished = false;
            try {
                if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                    throw new IOException("Could not encode cover");
                }
                file.finishWrite(output);
                finished = true;
            } finally {
                if (!finished) {
                    file.failWrite(output);
                }
            }
            if (!file.getBaseFile().isFile()) {
                throw new IOException("Could not save cover");
            }
        }
    }

    static Bitmap decodeSelection(ContentResolver resolver, Uri uri, int size) throws IOException {
        if (Build.VERSION.SDK_INT >= 28) {
            return ImageDecoder.decodeBitmap(ImageDecoder.createSource(resolver, uri), (decoder, info, source) -> {
                Size original = info.getSize();
                int longest = Math.max(original.getWidth(), original.getHeight());
                decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
                if (longest > size) {
                    float scale = size / (float) longest;
                    decoder.setTargetSize(Math.max(1, Math.round(original.getWidth() * scale)),
                            Math.max(1, Math.round(original.getHeight() * scale)));
                }
            });
        }

        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        try (InputStream input = resolver.openInputStream(uri)) {
            BitmapFactory.decodeStream(input, null, options);
        }
        int longest = Math.max(options.outWidth, options.outHeight);
        if (longest <= 0) {
            return null;
        }
        options.inJustDecodeBounds = false;
        options.inSampleSize = 1;
        while (longest / (long) options.inSampleSize > 3L * size / 2) {
            options.inSampleSize *= 2;
        }
        try (InputStream input = resolver.openInputStream(uri)) {
            Bitmap bitmap = BitmapFactory.decodeStream(input, null, options);
            return bitmap == null ? null : orient(resolver, uri, ArtworkLoader.fit(bitmap, size));
        }
    }

    private static Bitmap orient(ContentResolver resolver, Uri uri, Bitmap bitmap) {
        try (InputStream input = resolver.openInputStream(uri)) {
            if (input == null) {
                return bitmap;
            }
            int orientation = new ExifInterface(input).getAttributeInt(ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL);
            Matrix matrix = new Matrix();
            switch (orientation) {
                case ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.setScale(-1, 1);
                case ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.setScale(1, -1);
                case ExifInterface.ORIENTATION_ROTATE_90 -> matrix.setRotate(90);
                case ExifInterface.ORIENTATION_ROTATE_180 -> matrix.setRotate(180);
                case ExifInterface.ORIENTATION_ROTATE_270 -> matrix.setRotate(270);
                case ExifInterface.ORIENTATION_TRANSPOSE -> {
                    matrix.setRotate(90);
                    matrix.postScale(-1, 1);
                }
                case ExifInterface.ORIENTATION_TRANSVERSE -> {
                    matrix.setRotate(270);
                    matrix.postScale(-1, 1);
                }
                default -> { return bitmap; }
            }
            return Bitmap.createBitmap(bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), matrix, true);
        } catch (IOException | RuntimeException ignored) {
            return bitmap;
        }
    }

    private static File coverFile(Context context, String key) {
        String name = UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8)) + ".png";
        return new File(new File(context.getFilesDir(), "covers"), name);
    }
}
