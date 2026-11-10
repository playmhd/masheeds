package com.masheeds.player;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.graphics.drawable.Icon;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaMetadata;
import android.media.MediaPlayer;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.widget.Toast;

import com.masheeds.player.TrackLibrary.Track;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Future;

public final class PlaybackService extends Service {
    private static final String CHANNEL = "playback";
    private static final int NOTIFICATION_ID = 1;
    private static final String PREVIOUS = "previous";
    private static final String TOGGLE = "toggle";
    private static final String NEXT = "next";
    private static final String DISMISS = "dismiss";

    private final IBinder binder = new LocalBinder();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable endIdleSession = this::clearSession;
    private final AudioAttributes attributes = new AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build();

    private final BroadcastReceiver headphones = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (isPlaybackRequested() || interrupted) {
                pause();
            }
        }
    };

    private AudioManager audio;
    private AudioFocusRequest focusRequest;
    private NotificationManager notifications;
    private Context localizedContext;
    private MediaSession session;
    private PendingIntent openApp;
    private MediaPlayer player;
    private Bitmap currentArtwork;
    private Bitmap notificationArtwork;
    private Future<?> artworkRequest;
    private int artworkVersion;
    private Runnable listener;
    private List<Track> queue = Collections.emptyList();
    private RepeatMode repeatMode;
    private int index = -1;
    private boolean prepared;
    private boolean playWhenReady;
    private boolean interrupted;
    private boolean completed;
    private boolean hasFocus;
    private boolean foreground;
    private boolean activityBound;

    final class LocalBinder extends Binder {
        PlaybackService service() {
            return PlaybackService.this;
        }
    }

    @Override public void onCreate() {
        super.onCreate();
        repeatMode = RepeatMode.load(this);
        audio = getSystemService(AudioManager.class);
        focusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(attributes)
                .setWillPauseWhenDucked(true)
                .setOnAudioFocusChangeListener(this::onFocusChanged, handler)
                .build();
        notifications = getSystemService(NotificationManager.class);
        refreshLanguage();

        openApp = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE);
        session = new MediaSession(this, getString(R.string.app_name));
        session.setSessionActivity(openApp);
        session.setCallback(new MediaSession.Callback() {
            @Override public void onPlay() { resume(); }
            @Override public void onPause() { pause(); }
            @Override public void onStop() { clearSession(); }
            @Override public void onSkipToNext() { next(); }
            @Override public void onSkipToPrevious() { previous(); }
            @Override public void onSeekTo(long position) { seek(position); }
        }, handler);

        IntentFilter filter = new IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(headphones, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(headphones, filter);
        }
    }

    @Override public IBinder onBind(Intent intent) {
        onRebind(intent);
        return binder;
    }

    @Override public void onRebind(Intent intent) {
        activityBound = true;
        handler.removeCallbacks(endIdleSession);
    }

    @Override public boolean onUnbind(Intent intent) {
        activityBound = false;
        scheduleIdleSessionEnd();
        return true;
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        if (action != null) {
            switch (action) {
                case PREVIOUS -> previous();
                case TOGGLE -> toggle();
                case NEXT -> next();
                case DISMISS -> clearSession();
            }
        }
        if (currentTrack() == null) {
            stopSelf();
        }
        return START_NOT_STICKY;
    }

    void setListener(Runnable listener) {
        this.listener = listener;
    }

    void refreshLanguage() {
        if (localizedContext != null && AppPreferences.locale(this).equals(
                localizedContext.getResources().getConfiguration().getLocales().get(0))) {
            return;
        }
        localizedContext = AppPreferences.configuredContext(this);
        notifications.createNotificationChannel(new NotificationChannel(CHANNEL,
                localizedContext.getString(R.string.playback_channel), NotificationManager.IMPORTANCE_LOW));
        if (session != null) {
            publishState();
        }
    }

    Track currentTrack() {
        return index >= 0 ? queue.get(index) : null;
    }

    Bitmap currentArtwork() {
        return currentArtwork;
    }

    boolean isPlaybackRequested() {
        return playWhenReady;
    }

    boolean canSkipNext() {
        return index >= 0 && (index + 1 < queue.size() || wrapsQueue());
    }

    boolean canSkipPrevious() {
        return index > 0 || (index == 0 && wrapsQueue());
    }

    private boolean wrapsQueue() {
        return repeatMode == RepeatMode.ALL && queue.size() > 1;
    }

    void setRepeatMode(RepeatMode mode) {
        if (repeatMode != mode) {
            repeatMode = mode;
            publishState();
        }
    }

    boolean canSeek() {
        return prepared && duration() > 0;
    }

    int position() {
        return prepared ? player.getCurrentPosition() : 0;
    }

    int duration() {
        return prepared ? Math.max(0, player.getDuration()) : 0;
    }

    void play(List<Track> tracks, int selectedIndex) {
        queue = new ArrayList<>(tracks);
        load(selectedIndex);
    }

    boolean hasQueue(List<Track> tracks) {
        return queue.equals(tracks);
    }

    void toggle() {
        if (isPlaybackRequested()) {
            pause();
        } else {
            resume();
        }
    }

    void previous() {
        if (canSkipPrevious() && position() < 3_000) {
            load(index == 0 ? queue.size() - 1 : index - 1);
        } else {
            seek(0);
        }
    }

    void next() {
        if (canSkipNext()) {
            load((index + 1) % queue.size());
        }
    }

    void seek(long position) {
        if (!canSeek()) {
            return;
        }
        completed = false;
        player.seekTo(Math.max(0, Math.min(position, duration())), MediaPlayer.SEEK_CLOSEST);
        publishState();
    }

    private void load(int selectedIndex) {
        releasePlayer();
        index = selectedIndex;
        playWhenReady = true;
        interrupted = false;
        completed = false;
        if (!enterForeground()) {
            failPlayback();
            return;
        }

        try {
            MediaPlayer newPlayer = new MediaPlayer();
            player = newPlayer;
            newPlayer.setAudioAttributes(attributes);
            newPlayer.setWakeMode(this, PowerManager.PARTIAL_WAKE_LOCK);
            newPlayer.setOnPreparedListener(ready -> {
                if (ready != player) {
                    return;
                }
                prepared = true;
                if (playWhenReady) {
                    startPlayback();
                } else {
                    publishState();
                }
            });
            newPlayer.setOnSeekCompleteListener(seeked -> {
                if (seeked == player) {
                    publishState();
                }
            });
            newPlayer.setOnCompletionListener(this::onTrackCompleted);
            newPlayer.setOnErrorListener((failed, what, extra) -> {
                if (failed == player) {
                    failPlayback();
                }
                return true;
            });
            newPlayer.setDataSource(this, currentTrack().uri());
            newPlayer.prepareAsync();
            loadArtwork(currentTrack());
            publishState();
        } catch (IOException | RuntimeException error) {
            failPlayback();
        }
    }

    private void loadArtwork(Track track) {
        int version = artworkVersion;
        artworkRequest = ArtworkLoader.load(this, track, ArtworkLoader.COVER_SIZE, bitmap -> {
            if (version == artworkVersion && bitmap != null) {
                currentArtwork = bitmap;
                notificationArtwork = ArtworkLoader.fit(bitmap, 256);
                publishState();
            }
        });
    }

    private void onTrackCompleted(MediaPlayer finished) {
        if (finished != player) {
            return;
        }
        completed = true;
        if (!playWhenReady) {
            return;
        }
        if (repeatMode == RepeatMode.ONE || (repeatMode == RepeatMode.ALL && queue.size() == 1)) {
            startPlayback();
        } else if (canSkipNext()) {
            next();
        } else {
            pause();
        }
    }

    private void resume() {
        if (player == null || playWhenReady) {
            return;
        }
        if (!enterForeground()) {
            Toast.makeText(this, localizedContext.getText(R.string.playback_error), Toast.LENGTH_SHORT).show();
            return;
        }
        interrupted = false;
        playWhenReady = true;
        if (prepared) {
            startPlayback();
        } else {
            publishState();
        }
    }

    private void startPlayback() {
        if (!hasFocus) {
            hasFocus = audio.requestAudioFocus(focusRequest) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
            if (!hasFocus) {
                pause();
                Toast.makeText(this, localizedContext.getText(R.string.audio_focus_error), Toast.LENGTH_SHORT).show();
                return;
            }
        }
        try {
            if (completed) {
                completed = false;
                player.seekTo(0, MediaPlayer.SEEK_CLOSEST);
            }
            player.start();
            publishState();
        } catch (IllegalStateException error) {
            failPlayback();
        }
    }

    private void pause() {
        if (currentTrack() == null) {
            return;
        }
        interrupted = false;
        playWhenReady = false;
        pausePlayer();
        abandonFocus();
        leaveForeground(STOP_FOREGROUND_DETACH);
        scheduleIdleSessionEnd();
        publishState();
    }

    private void scheduleIdleSessionEnd() {
        handler.removeCallbacks(endIdleSession);
        if (!activityBound && !foreground && currentTrack() != null) {
            handler.postDelayed(endIdleSession, 5 * 60_000L);
        }
    }

    private void pausePlayer() {
        if (prepared && player.isPlaying()) {
            player.pause();
        }
    }

    private void onFocusChanged(int change) {
        if (!hasFocus) {
            return;
        }
        switch (change) {
            case AudioManager.AUDIOFOCUS_LOSS -> pause();
            case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
                 AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                if (playWhenReady) {
                    interrupted = true;
                    playWhenReady = false;
                    pausePlayer();
                    publishState();
                }
            }
            case AudioManager.AUDIOFOCUS_GAIN -> {
                if (interrupted) {
                    resume();
                }
            }
        }
    }

    private boolean enterForeground() {
        handler.removeCallbacks(endIdleSession);
        if (foreground) {
            return true;
        }
        try {
            startForegroundService(new Intent(this, PlaybackService.class));
            startForeground(NOTIFICATION_ID, notification(true));
            foreground = true;
            return true;
        } catch (IllegalStateException | SecurityException error) {
            return false;
        }
    }

    private void leaveForeground(int flags) {
        if (foreground) {
            stopForeground(flags);
            foreground = false;
        }
    }

    // MediaSession notifications are exempt from POST_NOTIFICATIONS.
    @SuppressLint("NotificationPermission")
    private void publishState() {
        Track track = currentTrack();
        if (track == null) {
            session.setActive(false);
            session.setMetadata(null);
            session.setPlaybackState(null);
        } else {
            int state = playWhenReady
                    ? (prepared ? PlaybackState.STATE_PLAYING : PlaybackState.STATE_BUFFERING)
                    : PlaybackState.STATE_PAUSED;
            long actions = PlaybackState.ACTION_PLAY_PAUSE | PlaybackState.ACTION_PLAY
                    | PlaybackState.ACTION_PAUSE | PlaybackState.ACTION_SKIP_TO_PREVIOUS
                    | PlaybackState.ACTION_STOP;
            if (canSkipNext()) {
                actions |= PlaybackState.ACTION_SKIP_TO_NEXT;
            }
            if (canSeek()) {
                actions |= PlaybackState.ACTION_SEEK_TO;
            }
            MediaMetadata.Builder metadata = new MediaMetadata.Builder()
                    .putString(MediaMetadata.METADATA_KEY_TITLE, track.displayTitle(localizedContext))
                    .putString(MediaMetadata.METADATA_KEY_ARTIST, track.artist())
                    .putLong(MediaMetadata.METADATA_KEY_DURATION, prepared ? duration() : track.duration());
            if (notificationArtwork != null) {
                metadata.putBitmap(MediaMetadata.METADATA_KEY_ART, notificationArtwork);
            }
            session.setMetadata(metadata.build());
            session.setPlaybackState(new PlaybackState.Builder()
                    .setActions(actions)
                    .setState(state, position(), state == PlaybackState.STATE_PLAYING ? 1f : 0f)
                    .build());
            session.setActive(true);
            notifications.notify(NOTIFICATION_ID, notification(foreground));
        }
        if (listener != null) {
            listener.run();
        }
    }

    private Notification notification(boolean ongoing) {
        Track track = currentTrack();
        boolean pause = playWhenReady;
        Notification.Builder builder = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_note)
                .setContentTitle(track.displayTitle(localizedContext))
                .setContentText(track.artist())
                .setContentIntent(openApp)
                .setDeleteIntent(command(DISMISS, 4))
                .setShowWhen(false)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setOngoing(ongoing)
                .addAction(action(R.drawable.ic_previous, R.string.previous_track, PREVIOUS, 1))
                .addAction(action(pause ? R.drawable.ic_pause : R.drawable.ic_play,
                        pause ? R.string.pause : R.string.play, TOGGLE, 2));
        if (notificationArtwork != null) {
            builder.setLargeIcon(notificationArtwork);
        }
        if (canSkipNext()) {
            builder.addAction(action(R.drawable.ic_next, R.string.next_track, NEXT, 3));
        }
        return builder.setStyle(new Notification.MediaStyle()
                .setMediaSession(session.getSessionToken())
                .setShowActionsInCompactView(canSkipNext() ? new int[]{0, 1, 2} : new int[]{0, 1}))
                .build();
    }

    private Notification.Action action(int icon, int title, String action, int requestCode) {
        return new Notification.Action.Builder(Icon.createWithResource(this, icon), localizedContext.getText(title),
                command(action, requestCode)).build();
    }

    private PendingIntent command(String action, int requestCode) {
        return PendingIntent.getService(this, requestCode,
                new Intent(this, PlaybackService.class).setAction(action), PendingIntent.FLAG_IMMUTABLE);
    }

    private void failPlayback() {
        clearSession();
        Toast.makeText(this, localizedContext.getText(R.string.playback_error), Toast.LENGTH_SHORT).show();
    }

    private void clearSession() {
        handler.removeCallbacks(endIdleSession);
        playWhenReady = false;
        interrupted = false;
        completed = false;
        index = -1;
        queue = Collections.emptyList();
        releasePlayer();
        abandonFocus();
        leaveForeground(STOP_FOREGROUND_REMOVE);
        notifications.cancel(NOTIFICATION_ID);
        publishState();
        stopSelf();
    }

    private void releasePlayer() {
        artworkVersion++;
        if (artworkRequest != null) {
            artworkRequest.cancel(false);
            artworkRequest = null;
        }
        currentArtwork = null;
        notificationArtwork = null;
        prepared = false;
        if (player != null) {
            player.release();
            player = null;
        }
    }

    private void abandonFocus() {
        if (hasFocus) {
            hasFocus = false;
            audio.abandonAudioFocusRequest(focusRequest);
        }
    }

    @Override public void onTaskRemoved(Intent rootIntent) {
        if (!foreground) {
            clearSession();
        }
    }

    @Override public void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        unregisterReceiver(headphones);
        releasePlayer();
        abandonFocus();
        notifications.cancel(NOTIFICATION_ID);
        session.release();
        super.onDestroy();
    }
}
