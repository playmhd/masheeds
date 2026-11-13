package com.masheeds.player;

import android.content.Context;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigInteger;
import java.net.URL;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

import javax.net.ssl.HttpsURLConnection;

final class PrivateMusicTransfer {
    private static final long MAX_BYTES = 20_000_000L;
    private static final long SUBMISSION_MAX_BYTES = 10L * 1024 * 1024;
    private static final int SUBMISSION_THUMBNAIL_SIZE = 512;
    private static final int SUBMISSION_THUMBNAIL_MAX_BYTES = 512 * 1024;
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    record Audio(File file, String mime, String title, String artist, int duration) {}
    record Thumbnail(Bitmap preview, byte[] jpeg, String sha256) {}

    private PrivateMusicTransfer() {}

    static void upload(Context context, Uri document, String accountId, IntConsumer progress,
                       Consumer<String> completed) {
        Context app = context.getApplicationContext();
        DiscoverBackend backend = DiscoverBackend.get(app);
        WORKER.execute(() -> {
            Audio audio;
            try {
                audio = prepare(app, document, MAX_BYTES, false);
            } catch (InvalidFile error) {
                MAIN.post(() -> completed.accept(error.code));
                return;
            } catch (IOException | RuntimeException error) {
                MAIN.post(() -> completed.accept("upload_failed"));
                return;
            }
            backend.beginPrivateUpload(accountId, audio, result -> {
                if (!result.success()) {
                    audio.file().delete();
                    completed.accept(result.code().isEmpty() ? "upload_failed" : result.code());
                    return;
                }
                DiscoverBackend.UploadGrant grant = result.value();
                WORKER.execute(() -> {
                    boolean sent = false;
                    try {
                        put(audio, grant, progress);
                        sent = true;
                    } catch (IOException | RuntimeException ignored) {
                        // The reserved upload is abandoned below.
                    } finally {
                        audio.file().delete();
                    }
                    if (sent) {
                        backend.finishPrivateUpload(accountId, grant.id(), finish ->
                                completed.accept(finish.success() ? null
                                        : finish.code().isEmpty() ? "upload_failed" : finish.code()));
                    } else {
                        backend.deletePrivateTrack(accountId, grant.id(), ignored -> completed.accept("upload_failed"));
                    }
                });
            });
        });
    }

    static void loadSubmissionThumbnail(Context context, Uri audio, Uri image, Consumer<Thumbnail> completed) {
        Context app = context.getApplicationContext();
        WORKER.execute(() -> {
            Thumbnail thumbnail = null;
            try {
                Bitmap bitmap = image == null ? ArtworkLoader.readEmbedded(app, audio, SUBMISSION_THUMBNAIL_SIZE)
                        : CustomArtwork.decodeSelection(app.getContentResolver(), image, SUBMISSION_THUMBNAIL_SIZE);
                if (bitmap != null) {
                    // Flatten transparency on a neutral background and discard original image metadata.
                    Bitmap preview = Bitmap.createBitmap(bitmap.getWidth(), bitmap.getHeight(), Bitmap.Config.ARGB_8888);
                    Canvas canvas = new Canvas(preview);
                    canvas.drawColor(Color.WHITE);
                    canvas.drawBitmap(bitmap, null, new android.graphics.Rect(0, 0, preview.getWidth(), preview.getHeight()), null);
                    ByteArrayOutputStream output = new ByteArrayOutputStream();
                    if (preview.compress(Bitmap.CompressFormat.JPEG, 85, output)
                            && output.size() > 0 && output.size() <= SUBMISSION_THUMBNAIL_MAX_BYTES) {
                        byte[] bytes = output.toByteArray();
                        thumbnail = new Thumbnail(preview, bytes,
                                formatHash(MessageDigest.getInstance("SHA-256").digest(bytes)));
                    }
                }
            } catch (IOException | RuntimeException | NoSuchAlgorithmException | OutOfMemoryError ignored) {
                // An unreadable custom image can be replaced in the form.
            }
            Thumbnail result = thumbnail;
            MAIN.post(() -> completed.accept(result));
        });
    }

    static void submitForReview(Context context, Uri document, String owner, String title, String artist, Thumbnail thumbnail,
                                IntConsumer progress, Consumer<String> completed) {
        Context app = context.getApplicationContext();
        WORKER.execute(() -> {
            Audio audio;
            try {
                audio = prepare(app, document, SUBMISSION_MAX_BYTES, true);
            } catch (InvalidFile error) {
                MAIN.post(() -> completed.accept(error.code));
                return;
            } catch (IOException | RuntimeException error) {
                MAIN.post(() -> completed.accept("upload_failed"));
                return;
            }
            String hash;
            try {
                hash = sha256(audio.file());
            } catch (IOException | NoSuchAlgorithmException error) {
                audio.file().delete();
                MAIN.post(() -> completed.accept("upload_failed"));
                return;
            }
            DiscoverBackend.get(app).submitMusic(owner, audio, title, artist, hash, thumbnail, progress, result -> {
                audio.file().delete();
                completed.accept(result.success() ? null : result.code());
            });
        });
    }

    private static String sha256(File file) throws IOException, NoSuchAlgorithmException {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = new FileInputStream(file)) {
            byte[] buffer = new byte[64 * 1024];
            for (int count; (count = input.read(buffer)) != -1; ) {
                digest.update(buffer, 0, count);
            }
        }
        return formatHash(digest.digest());
    }

    private static String formatHash(byte[] hash) {
        return String.format(Locale.ROOT, "%064x", new BigInteger(1, hash));
    }

    private static Audio prepare(Context context, Uri document, long maxBytes, boolean mp3Only) throws IOException {
        String name = displayName(context, document);
        String lower = name.toLowerCase(Locale.ROOT);
        String mime = lower.endsWith(".mp3") ? "audio/mpeg"
                : lower.endsWith(".m4a") ? "audio/mp4" : null;
        if (mime == null || mp3Only && !"audio/mpeg".equals(mime)) {
            throw new InvalidFile("invalid_type");
        }
        File temp = File.createTempFile("masheeds-upload-", lower.endsWith(".mp3") ? ".mp3" : ".m4a",
                context.getCacheDir());
        boolean retained = false;
        try {
            try (InputStream input = context.getContentResolver().openInputStream(document);
                 OutputStream output = new FileOutputStream(temp)) {
                if (input == null) {
                    throw new IOException("Audio file unavailable");
                }
                byte[] buffer = new byte[32 * 1024];
                long size = 0;
                for (int count; (count = input.read(buffer)) != -1; ) {
                    size += count;
                    if (size > maxBytes) {
                        throw new InvalidFile("invalid_size");
                    }
                    output.write(buffer, 0, count);
                }
                if (size == 0) {
                    throw new InvalidFile("invalid_size");
                }
            }
            String title = name.substring(0, name.length() - 4).trim();
            String artist = "";
            int duration = 0;
            MediaMetadataRetriever metadata = new MediaMetadataRetriever();
            try {
                metadata.setDataSource(temp.getAbsolutePath());
                String embeddedTitle = metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE);
                String embeddedArtist = metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST);
                String embeddedDuration = metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
                if (embeddedTitle != null && !embeddedTitle.trim().isEmpty()) {
                    title = embeddedTitle.trim();
                }
                artist = embeddedArtist == null ? "" : embeddedArtist.trim();
                if (embeddedDuration != null) {
                    duration = (int) Math.max(0, Math.min(86_400_000, Long.parseLong(embeddedDuration)));
                }
                if (mp3Only && !"audio/mpeg".equals(metadata.extractMetadata(
                        MediaMetadataRetriever.METADATA_KEY_MIMETYPE))) {
                    throw new InvalidFile("invalid_audio");
                }
            } catch (RuntimeException ignored) {
                // Server-side format verification decides whether the file is playable.
            } finally {
                try {
                    metadata.release();
                } catch (IOException ignored) {
                    // Metadata already read is still usable.
                }
            }
            if (mp3Only && duration <= 0) {
                throw new InvalidFile("invalid_audio");
            }
            if (title.isEmpty()) {
                title = context.getString(R.string.untitled_track);
            }
            retained = true;
            return new Audio(temp, mime, limitText(title), limitText(artist), duration);
        } finally {
            if (!retained) {
                temp.delete();
            }
        }
    }

    private static String limitText(String text) {
        if (text.length() <= 200) {
            return text;
        }
        return text.substring(0, Character.isHighSurrogate(text.charAt(199)) ? 199 : 200);
    }

    private static String displayName(Context context, Uri document) throws IOException {
        try (Cursor cursor = context.getContentResolver().query(document,
                new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst() && cursor.getString(0) != null) {
                return cursor.getString(0);
            }
        }
        throw new IOException("Audio name unavailable");
    }

    private static void put(Audio audio, DiscoverBackend.UploadGrant grant, IntConsumer progress) throws IOException {
        if (audio.file().length() != grant.size() || !audio.mime().equals(grant.mime())) {
            throw new IOException("Upload grant does not match the file");
        }
        HttpsURLConnection connection = (HttpsURLConnection) new URL(grant.url().toString()).openConnection();
        Runnable deadline = connection::disconnect;
        MAIN.postDelayed(deadline, 5 * 60_000L);
        try {
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(15_000);
            connection.setReadTimeout(25_000);
            connection.setRequestMethod("PUT");
            connection.setRequestProperty("If-None-Match", "*");
            writeAudio(connection, audio, progress);
            int status = connection.getResponseCode();
            if (status < 200 || status >= 300) {
                throw new IOException("R2 upload failed");
            }
        } finally {
            MAIN.removeCallbacks(deadline);
            connection.disconnect();
        }
    }

    static void writeAudio(HttpsURLConnection connection, Audio audio, IntConsumer progress) throws IOException {
        long size = audio.file().length();
        connection.setDoOutput(true);
        connection.setFixedLengthStreamingMode(size);
        connection.setRequestProperty("Content-Type", audio.mime());
        try (InputStream input = new FileInputStream(audio.file()); OutputStream output = connection.getOutputStream()) {
            byte[] buffer = new byte[64 * 1024];
            long written = 0;
            int previous = -1;
            for (int count; (count = input.read(buffer)) != -1; ) {
                output.write(buffer, 0, count);
                written += count;
                int percent = (int) (written * 100 / size);
                if (percent != previous) {
                    previous = percent;
                    MAIN.post(() -> progress.accept(percent));
                }
            }
        }
    }

    private static final class InvalidFile extends IOException {
        private final String code;

        private InvalidFile(String code) {
            this.code = code;
        }
    }
}
