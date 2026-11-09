package com.masheeds.player;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.database.ContentObserver;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.MediaStore;
import android.provider.Settings;
import android.text.Editable;
import android.text.InputType;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;
import android.window.OnBackInvokedCallback;
import android.window.OnBackInvokedDispatcher;

import com.masheeds.player.TrackLibrary.Track;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static android.view.ViewGroup.LayoutParams.MATCH_PARENT;
import static android.view.ViewGroup.LayoutParams.WRAP_CONTENT;

public final class MainActivity extends Activity {
    private static final int BACKGROUND = 0xFFF8F9F5;
    private static final int INK = 0xFF192C23;
    private static final int MUTED = 0xFF52635A;
    private static final int GREEN = 0xFF245843;
    private static final int SOFT_GREEN = 0xFFE6EFE3;
    private static final int SURFACE = 0xFFEEF1EA;
    private static final int PLAYER = 0xFF173D30;
    private static final int PLAYER_MUTED = 0xFFC5D9CB;
    private static final int PICK_FILES = 1;
    private static final int AUDIO_ACCESS = 2;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService scanner = Executors.newSingleThreadExecutor();
    private final Runnable scan = this::refreshLibrary;
    private final Runnable progressUpdate = new Runnable() {
        @Override public void run() {
            updateProgress();
            handler.postDelayed(this, 350);
        }
    };
    private final ContentObserver mediaChanges = new ContentObserver(handler) {
        @Override public void onChange(boolean selfChange) {
            handler.removeCallbacks(scan);
            handler.postDelayed(scan, 350);
        }
    };
    private final ServiceConnection connection = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder binder) {
            playback = ((PlaybackService.LocalBinder) binder).service();
            playback.setListener(MainActivity.this::renderPlayback);
            renderPlayback();
        }

        @Override public void onServiceDisconnected(ComponentName name) {
            playback = null;
            renderPlayback();
        }
    };

    private PlaybackService playback;
    private boolean bound;
    private boolean observing;
    private boolean playerOpen;
    private boolean scrubbing;
    private boolean loaded;
    private boolean keyboardOpen;
    private OnBackInvokedCallback backCallback;
    private Future<?> scanTask;
    private int scanVersion;
    private List<Track> tracks = Collections.emptyList();
    private Uri selectedTrack;
    private Uri scrubTrack;

    private LinearLayout library;
    private LinearLayout libraryHeading;
    private LinearLayout trackHeading;
    private EditText search;
    private TextView count;
    private Button scanButton;
    private TrackAdapter adapter;
    private ListView trackList;
    private ScrollView placeholder;
    private ProgressBar loading;
    private ImageView emptyIcon;
    private TextView emptyTitle;
    private TextView emptyMessage;
    private Button emptyAction;
    private LinearLayout miniPlayer;
    private LinearLayout miniDetails;
    private TextView miniTitle;
    private TextView miniArtist;
    private ImageButton miniToggle;
    private ImageButton miniNext;
    private ProgressBar miniProgress;
    private ScrollView playerScreen;
    private TextView playerTitle;
    private TextView playerArtist;
    private TextView elapsed;
    private TextView totalTime;
    private SeekBar seek;
    private ImageButton playerToggle;
    private ImageButton playerNext;
    private ImageButton playerPrevious;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        setVolumeControlStream(AudioManager.STREAM_MUSIC);
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(BACKGROUND);
        root.setOnApplyWindowInsetsListener(this::applyInsets);
        if (Build.VERSION.SDK_INT >= 30) {
            getWindow().setDecorFitsSystemWindows(false);
        } else {
            getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        }

        FrameLayout content = new FrameLayout(this);
        int maxWidth = dp(640);
        int screenWidth = dp(getResources().getConfiguration().screenWidthDp);
        root.addView(content, new FrameLayout.LayoutParams(
                screenWidth > maxWidth ? maxWidth : MATCH_PARENT, MATCH_PARENT, Gravity.CENTER));
        library = buildLibrary();
        content.addView(library, new FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT));
        playerScreen = buildPlayerScreen();
        playerScreen.setVisibility(View.GONE);
        content.addView(playerScreen, new FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT));
        setContentView(root);
        if (state != null) {
            search.setText(state.getString("query", ""));
            playerOpen = state.getBoolean("playerOpen");
        }
    }

    private WindowInsets applyInsets(View view, WindowInsets insets) {
        if (Build.VERSION.SDK_INT >= 30) {
            android.graphics.Insets safe = insets.getInsets(
                    WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            int bottom = Math.max(safe.bottom, insets.getInsets(WindowInsets.Type.ime()).bottom);
            view.setPadding(safe.left, safe.top, safe.right, bottom);
            boolean visible = insets.isVisible(WindowInsets.Type.ime());
            if (keyboardOpen != visible) {
                keyboardOpen = visible;
                libraryHeading.setVisibility(visible ? View.GONE : View.VISIBLE);
                if (playback != null) {
                    miniPlayer.setVisibility(!visible && playback.currentTrack() != null
                            ? View.VISIBLE : View.GONE);
                }
            }
        } else {
            view.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
                    insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
        }
        return insets;
    }

    @Override public void onStart() {
        super.onStart();
        bound = bindService(new Intent(this, PlaybackService.class), connection, Context.BIND_AUTO_CREATE);
        handler.post(progressUpdate);
    }

    @Override public void onResume() {
        super.onResume();
        watchDeviceLibrary();
        refreshLibrary();
    }

    private void watchDeviceLibrary() {
        if (hasAudioAccess() && !observing) {
            getContentResolver().registerContentObserver(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                    true, mediaChanges);
            observing = true;
        }
    }

    @Override public void onStop() {
        handler.removeCallbacks(progressUpdate);
        handler.removeCallbacks(scan);
        if (observing) {
            getContentResolver().unregisterContentObserver(mediaChanges);
            observing = false;
        }
        if (playback != null) {
            playback.setListener(null);
            playback = null;
        }
        if (bound) {
            unbindService(connection);
            bound = false;
        }
        super.onStop();
    }

    @Override public void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        scanner.shutdownNow();
        super.onDestroy();
    }

    @Override public void onSaveInstanceState(Bundle state) {
        state.putString("query", search.getText().toString());
        state.putBoolean("playerOpen", playerOpen);
        super.onSaveInstanceState(state);
    }

    // Android 13 and up use the registered OnBackInvokedCallback.
    @SuppressLint("GestureBackNavigation")
    @Override public void onBackPressed() {
        if (Build.VERSION.SDK_INT < 33 && playerOpen) {
            showLibrary();
        } else {
            super.onBackPressed();
        }
    }

    private LinearLayout buildLibrary() {
        LinearLayout page = column();
        page.setPadding(dp(20), dp(6), dp(20), dp(10));
        page.setFocusableInTouchMode(true);

        LinearLayout bar = row();
        ImageView logo = image(R.drawable.ic_masheeds);
        bar.addView(logo, new LinearLayout.LayoutParams(dp(38), dp(38)));
        TextView brand = text(R.string.app_name, 19, INK, true);
        LinearLayout.LayoutParams brandSize = new LinearLayout.LayoutParams(0, WRAP_CONTENT, 1);
        brandSize.leftMargin = dp(10);
        bar.addView(brand, brandSize);
        Button add = button(R.string.add_files, GREEN, SOFT_GREEN);
        add.setCompoundDrawablesRelative(smallIcon(R.drawable.ic_add, GREEN), null, null, null);
        add.setCompoundDrawablePadding(dp(5));
        add.setOnClickListener(view -> openPicker());
        bar.addView(add, new LinearLayout.LayoutParams(WRAP_CONTENT, dp(48)));
        page.addView(bar, new LinearLayout.LayoutParams(MATCH_PARENT, dp(58)));

        libraryHeading = column();
        TextView title = text(R.string.library_title, 34, INK, true);
        markHeading(title);
        libraryHeading.addView(title);
        TextView subtitle = text(R.string.library_description, 15, MUTED, false);
        addWithMargin(libraryHeading, subtitle, MATCH_PARENT, WRAP_CONTENT, 3);
        addWithMargin(page, libraryHeading, MATCH_PARENT, WRAP_CONTENT, 18);

        search = new EditText(this);
        search.setSingleLine(true);
        search.setHint(R.string.search_tracks);
        search.setTextColor(INK);
        search.setHintTextColor(MUTED);
        search.setTextSize(15);
        search.setInputType(InputType.TYPE_CLASS_TEXT);
        search.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        search.setPadding(dp(14), 0, dp(14), 0);
        search.setBackground(round(SURFACE, 14));
        search.setCompoundDrawablesRelative(smallIcon(R.drawable.ic_search, MUTED), null, null, null);
        search.setCompoundDrawablePadding(dp(9));
        search.setOnEditorActionListener((field, action, event) -> {
            if (action == EditorInfo.IME_ACTION_SEARCH) {
                hideKeyboard();
                return true;
            }
            return false;
        });
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence text, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence text, int start, int before, int count) {
                renderLibrary();
                trackList.setSelection(0);
            }
            @Override public void afterTextChanged(Editable text) {}
        });
        search.setVisibility(View.GONE);
        addWithMargin(page, search, MATCH_PARENT, dp(48), 18);

        trackHeading = row();
        TextView tracksLabel = text(R.string.tracks, 17, INK, true);
        markHeading(tracksLabel);
        trackHeading.addView(tracksLabel);
        count = text(0, 13, MUTED, false);
        LinearLayout.LayoutParams countSize = new LinearLayout.LayoutParams(0, WRAP_CONTENT, 1);
        countSize.leftMargin = dp(10);
        trackHeading.addView(count, countSize);
        scanButton = button(R.string.scan_device, GREEN, Color.TRANSPARENT);
        scanButton.setOnClickListener(view -> requestAudioAccess());
        trackHeading.addView(scanButton, new LinearLayout.LayoutParams(WRAP_CONTENT, dp(48)));
        trackHeading.setVisibility(View.GONE);
        addWithMargin(page, trackHeading, MATCH_PARENT, dp(48), 6);

        FrameLayout results = new FrameLayout(this);
        page.addView(results, new LinearLayout.LayoutParams(MATCH_PARENT, 0, 1));
        trackList = new ListView(this);
        trackList.setDivider(null);
        trackList.setClipToPadding(false);
        trackList.setPadding(0, 0, 0, dp(6));
        trackList.setSelector(android.R.color.transparent);
        adapter = new TrackAdapter();
        trackList.setAdapter(adapter);
        trackList.setOnItemClickListener((parent, row, position, id) -> selectTrack(adapter.getItem(position)));
        trackList.setVisibility(View.GONE);
        results.addView(trackList, new FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT));
        placeholder = buildPlaceholder();
        placeholder.setVisibility(View.GONE);
        results.addView(placeholder, new FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT));
        loading = new ProgressBar(this);
        loading.setIndeterminateTintList(ColorStateList.valueOf(GREEN));
        loading.setContentDescription(getString(R.string.loading));
        results.addView(loading, new FrameLayout.LayoutParams(dp(32), dp(32), Gravity.CENTER));

        miniPlayer = buildMiniPlayer();
        miniPlayer.setVisibility(View.GONE);
        addWithMargin(page, miniPlayer, MATCH_PARENT, WRAP_CONTENT, 8);
        return page;
    }

    private ScrollView buildPlaceholder() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        LinearLayout panel = column();
        panel.setGravity(Gravity.CENTER);
        panel.setPadding(dp(16), dp(20), dp(16), dp(36));
        scroll.addView(panel);

        emptyIcon = image(R.drawable.ic_library);
        emptyIcon.setBackground(round(SOFT_GREEN, 28));
        emptyIcon.setPadding(dp(18), dp(18), dp(18), dp(18));
        panel.addView(emptyIcon, new LinearLayout.LayoutParams(dp(94), dp(94)));
        emptyTitle = text(0, 23, INK, true);
        emptyTitle.setGravity(Gravity.CENTER);
        addWithMargin(panel, emptyTitle, MATCH_PARENT, WRAP_CONTENT, 18);
        emptyMessage = text(0, 15, MUTED, false);
        emptyMessage.setGravity(Gravity.CENTER);
        emptyMessage.setMaxWidth(dp(290));
        emptyMessage.setLineSpacing(dp(3), 1);
        addWithMargin(panel, emptyMessage, WRAP_CONTENT, WRAP_CONTENT, 6);
        emptyAction = button(R.string.scan_device, Color.WHITE, GREEN);
        addWithMargin(panel, emptyAction, WRAP_CONTENT, dp(48), 18);
        return scroll;
    }

    private LinearLayout buildMiniPlayer() {
        LinearLayout panel = column();
        panel.setBackground(round(PLAYER, 18));
        panel.setClipToOutline(true);
        LinearLayout controls = row();
        controls.setPadding(dp(10), dp(7), dp(5), dp(7));
        panel.addView(controls, new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT));

        miniDetails = row();
        miniDetails.setBackground(ripple(Color.TRANSPARENT, 10));
        miniDetails.setOnClickListener(view -> openPlayer());
        controls.addView(miniDetails, new LinearLayout.LayoutParams(0, dp(50), 1));
        ImageView icon = noteTile(0xFF315647, PLAYER_MUTED, 10, 10);
        miniDetails.addView(icon, new LinearLayout.LayoutParams(dp(42), dp(42)));
        LinearLayout labels = column();
        LinearLayout.LayoutParams labelSize = new LinearLayout.LayoutParams(0, WRAP_CONTENT, 1);
        labelSize.leftMargin = dp(10);
        miniDetails.addView(labels, labelSize);
        miniTitle = singleLineText(15, Color.WHITE, true);
        miniArtist = singleLineText(12, PLAYER_MUTED, false);
        labels.addView(miniTitle);
        addWithMargin(labels, miniArtist, MATCH_PARENT, WRAP_CONTENT, 3);

        miniToggle = iconButton(R.drawable.ic_play, R.string.play, Color.WHITE, Color.TRANSPARENT, 48, 24,
                () -> withPlayback(PlaybackService::toggle));
        miniNext = iconButton(R.drawable.ic_next, R.string.next_track, Color.WHITE, Color.TRANSPARENT, 48, 24,
                () -> withPlayback(PlaybackService::next));
        controls.addView(miniToggle, new LinearLayout.LayoutParams(dp(48), dp(48)));
        controls.addView(miniNext, new LinearLayout.LayoutParams(dp(48), dp(48)));

        miniProgress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        miniProgress.setProgressTintList(ColorStateList.valueOf(0xFFAACDAF));
        miniProgress.setProgressBackgroundTintList(ColorStateList.valueOf(0xFF3D6051));
        miniProgress.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        panel.addView(miniProgress, new LinearLayout.LayoutParams(MATCH_PARENT, dp(3)));
        return panel;
    }

    private ScrollView buildPlayerScreen() {
        ScrollView screen = new ScrollView(this);
        screen.setFillViewport(true);
        LinearLayout page = column();
        page.setGravity(Gravity.CENTER_HORIZONTAL);
        page.setPadding(dp(20), dp(6), dp(20), dp(20));
        screen.addView(page);

        FrameLayout header = new FrameLayout(this);
        page.addView(header, new LinearLayout.LayoutParams(MATCH_PARENT, dp(54)));
        ImageButton close = iconButton(R.drawable.ic_down, R.string.back_to_library, GREEN, SOFT_GREEN,
                48, 24, this::showLibrary);
        header.addView(close, new FrameLayout.LayoutParams(dp(48), dp(48), Gravity.START | Gravity.CENTER_VERTICAL));
        TextView label = text(R.string.now_playing, 16, INK, true);
        markHeading(label);
        header.addView(label, new FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.CENTER));

        int width = getResources().getConfiguration().screenWidthDp;
        int height = getResources().getConfiguration().screenHeightDp;
        int coverSize = dp(Math.min(320, Math.min(width - 48, Math.max(144, height - 330))));
        ImageView cover = image(R.drawable.ic_record);
        cover.setScaleType(ImageView.ScaleType.FIT_CENTER);
        cover.setBackground(round(SOFT_GREEN, 28));
        cover.setPadding(dp(8), dp(8), dp(8), dp(8));
        addWithMargin(page, cover, coverSize, coverSize, 22);

        playerTitle = text(0, 27, INK, true);
        playerTitle.setGravity(Gravity.CENTER);
        playerTitle.setMaxLines(2);
        playerTitle.setEllipsize(TextUtils.TruncateAt.END);
        addWithMargin(page, playerTitle, MATCH_PARENT, WRAP_CONTENT, 21);
        playerArtist = singleLineText(15, MUTED, false);
        playerArtist.setGravity(Gravity.CENTER);
        addWithMargin(page, playerArtist, MATCH_PARENT, WRAP_CONTENT, 4);

        seek = new SeekBar(this);
        seek.setPadding(dp(6), 0, dp(6), 0);
        seek.setContentDescription(getString(R.string.seek));
        seek.setProgressTintList(ColorStateList.valueOf(GREEN));
        seek.setProgressBackgroundTintList(ColorStateList.valueOf(0xFFD6E1D7));
        seek.setThumbTintList(ColorStateList.valueOf(GREEN));
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar, int position, boolean fromUser) {
                if (fromUser) {
                    elapsed.setText(formatTime(position));
                    if (!scrubbing && playback != null) {
                        playback.seek(position);
                    }
                }
            }
            @Override public void onStartTrackingTouch(SeekBar bar) {
                scrubbing = true;
                scrubTrack = selectedTrack;
            }
            @Override public void onStopTrackingTouch(SeekBar bar) {
                scrubbing = false;
                if (playback != null && Objects.equals(scrubTrack, selectedTrack)) {
                    playback.seek(bar.getProgress());
                }
                scrubTrack = null;
            }
        });
        addWithMargin(page, seek, MATCH_PARENT, dp(46), 10);
        LinearLayout times = row();
        elapsed = text(0, 13, MUTED, false);
        totalTime = text(0, 13, MUTED, false);
        times.addView(elapsed, new LinearLayout.LayoutParams(0, WRAP_CONTENT, 1));
        times.addView(totalTime);
        page.addView(times, new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT));

        LinearLayout buttons = row();
        buttons.setGravity(Gravity.CENTER);
        playerPrevious = iconButton(R.drawable.ic_previous, R.string.previous_track, GREEN, SOFT_GREEN, 54, 26,
                () -> withPlayback(PlaybackService::previous));
        playerToggle = iconButton(R.drawable.ic_play, R.string.play, Color.WHITE, GREEN, 72, 34,
                () -> withPlayback(PlaybackService::toggle));
        playerNext = iconButton(R.drawable.ic_next, R.string.next_track, GREEN, SOFT_GREEN, 54, 26,
                () -> withPlayback(PlaybackService::next));
        buttons.addView(playerPrevious, new LinearLayout.LayoutParams(dp(54), dp(54)));
        LinearLayout.LayoutParams toggleSize = new LinearLayout.LayoutParams(dp(72), dp(72));
        toggleSize.setMargins(dp(18), 0, dp(18), 0);
        buttons.addView(playerToggle, toggleSize);
        buttons.addView(playerNext, new LinearLayout.LayoutParams(dp(54), dp(54)));
        addWithMargin(page, buttons, MATCH_PARENT, WRAP_CONTENT, 22);
        return screen;
    }

    private void openPicker() {
        hideKeyboard();
        Intent picker = new Intent(Intent.ACTION_OPEN_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType("audio/*")
                .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                .putExtra(Intent.EXTRA_LOCAL_ONLY, true)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        try {
            startActivityForResult(picker, PICK_FILES);
        } catch (ActivityNotFoundException error) {
            Toast.makeText(this, R.string.picker_error, Toast.LENGTH_SHORT).show();
        }
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != PICK_FILES || resultCode != RESULT_OK || data == null) {
            return;
        }
        boolean addedAll = true;
        ClipData files = data.getClipData();
        if (files != null) {
            for (int i = 0; i < files.getItemCount(); i++) {
                addedAll &= rememberFile(files.getItemAt(i).getUri());
            }
        } else {
            addedAll = rememberFile(data.getData());
        }
        if (!addedAll) {
            Toast.makeText(this, R.string.import_error, Toast.LENGTH_SHORT).show();
        }
        refreshLibrary();
    }

    private boolean rememberFile(Uri uri) {
        if (uri == null) {
            return false;
        }
        try {
            getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
            return true;
        } catch (SecurityException error) {
            return false;
        }
    }

    private String audioPermission() {
        return Build.VERSION.SDK_INT >= 33 ? Manifest.permission.READ_MEDIA_AUDIO
                : Manifest.permission.READ_EXTERNAL_STORAGE;
    }

    private boolean hasAudioAccess() {
        return checkSelfPermission(audioPermission()) == PackageManager.PERMISSION_GRANTED;
    }

    private boolean requiresPermissionSettings() {
        return getPreferences(MODE_PRIVATE).getBoolean("askedForAudio", false)
                && !shouldShowRequestPermissionRationale(audioPermission());
    }

    private void requestAudioAccess() {
        if (requiresPermissionSettings()) {
            startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.fromParts("package", getPackageName(), null)));
        } else {
            requestPermissions(new String[]{audioPermission()}, AUDIO_ACCESS);
        }
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode == AUDIO_ACCESS && results.length > 0) {
            getPreferences(MODE_PRIVATE).edit().putBoolean("askedForAudio", true).apply();
            watchDeviceLibrary();
            refreshLibrary();
        }
    }

    private void refreshLibrary() {
        boolean scanDevice = hasAudioAccess();
        int version = ++scanVersion;
        if (scanTask != null) {
            scanTask.cancel(true);
        }
        Context app = getApplicationContext();
        scanTask = scanner.submit(() -> {
            List<Track> found = TrackLibrary.load(app, scanDevice);
            handler.post(() -> {
                if (!isDestroyed() && version == scanVersion) {
                    tracks = found;
                    loaded = true;
                    renderLibrary();
                }
            });
        });
    }

    private void renderLibrary() {
        if (!loaded) {
            return;
        }
        String query = search.getText().toString().trim().toLowerCase(Locale.ROOT);
        List<Track> visible = new ArrayList<>();
        for (Track track : tracks) {
            if (query.isEmpty() || track.title().toLowerCase(Locale.ROOT).contains(query)
                    || track.artist().toLowerCase(Locale.ROOT).contains(query)) {
                visible.add(track);
            }
        }
        adapter.visible = visible;
        adapter.notifyDataSetChanged();
        boolean hasTracks = !tracks.isEmpty();
        boolean hasAccess = hasAudioAccess();
        int scanLabel = requiresPermissionSettings() ? R.string.open_settings : R.string.scan_device;
        search.setVisibility(hasTracks ? View.VISIBLE : View.GONE);
        trackHeading.setVisibility(hasTracks ? View.VISIBLE : View.GONE);
        scanButton.setVisibility(hasAccess ? View.GONE : View.VISIBLE);
        scanButton.setText(scanLabel);
        int countLabel = query.isEmpty() ? R.plurals.track_count : R.plurals.match_count;
        count.setText(getResources().getQuantityString(countLabel, visible.size(), visible.size()));
        loading.setVisibility(View.GONE);
        trackList.setVisibility(visible.isEmpty() ? View.GONE : View.VISIBLE);
        placeholder.setVisibility(visible.isEmpty() ? View.VISIBLE : View.GONE);
        if (visible.isEmpty()) {
            emptyIcon.setVisibility(hasTracks ? View.GONE : View.VISIBLE);
            emptyTitle.setText(hasTracks ? R.string.no_matches_title
                    : hasAccess ? R.string.empty_title : R.string.welcome_title);
            emptyMessage.setText(hasTracks ? R.string.no_matches_description
                    : hasAccess ? R.string.empty_description : R.string.welcome_description);
            emptyAction.setVisibility(hasTracks ? View.GONE : View.VISIBLE);
            emptyAction.setText(hasAccess ? R.string.add_files : scanLabel);
            emptyAction.setOnClickListener(view -> {
                if (hasAccess) {
                    openPicker();
                } else {
                    requestAudioAccess();
                }
            });
        }
    }

    private void selectTrack(Track track) {
        if (playback == null) {
            return;
        }
        hideKeyboard();
        if (track.uri().equals(selectedTrack)) {
            playback.toggle();
        } else {
            playback.play(tracks, tracks.indexOf(track));
        }
    }

    private void renderPlayback() {
        Track current = playback == null ? null : playback.currentTrack();
        Uri uri = current == null ? null : current.uri();
        if (!Objects.equals(selectedTrack, uri)) {
            selectedTrack = uri;
            adapter.notifyDataSetChanged();
        }
        miniPlayer.setVisibility(current == null || keyboardOpen ? View.GONE : View.VISIBLE);
        if (current == null) {
            showLibrary();
            return;
        }
        String artist = current.artist().isEmpty() ? getString(R.string.audio_file) : current.artist();
        miniTitle.setText(current.title());
        miniArtist.setText(artist);
        miniDetails.setContentDescription(getString(R.string.show_now_playing, current.title(), artist));
        playerTitle.setText(current.title());
        playerArtist.setText(artist);
        boolean playing = playback.isPlaybackRequested();
        for (ImageButton button : new ImageButton[]{miniToggle, playerToggle}) {
            button.setImageResource(playing ? R.drawable.ic_pause : R.drawable.ic_play);
            button.setContentDescription(getString(playing ? R.string.pause : R.string.play));
        }
        setEnabled(miniNext, playback.canSkipNext());
        setEnabled(playerNext, playback.canSkipNext());
        setEnabled(playerPrevious, playback.canSeek() || playback.canSkipPrevious());
        if (playerOpen && playerScreen.getVisibility() != View.VISIBLE) {
            openPlayer();
        }
        updateProgress();
    }

    private void updateProgress() {
        if (playback == null || playback.currentTrack() == null) {
            return;
        }
        int position = playback.position();
        int duration = playback.duration();
        miniProgress.setMax(Math.max(1, duration));
        miniProgress.setProgress(position);
        if (!scrubbing) {
            seek.setEnabled(playback.canSeek());
            seek.setMax(Math.max(1, duration));
            seek.setProgress(position);
            elapsed.setText(formatTime(position));
            long total = duration > 0 ? duration : playback.currentTrack().duration();
            totalTime.setText(formatTime(total));
        }
    }

    private void openPlayer() {
        if (playback == null || playback.currentTrack() == null || playerScreen.isShown()) {
            return;
        }
        hideKeyboard();
        playerOpen = true;
        library.setVisibility(View.GONE);
        playerScreen.setVisibility(View.VISIBLE);
        playerScreen.scrollTo(0, 0);
        if (Build.VERSION.SDK_INT >= 33) {
            backCallback = this::showLibrary;
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                    OnBackInvokedDispatcher.PRIORITY_DEFAULT, backCallback);
        }
    }

    private void showLibrary() {
        if (Build.VERSION.SDK_INT >= 33 && backCallback != null) {
            getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback(backCallback);
            backCallback = null;
        }
        playerOpen = false;
        scrubbing = false;
        scrubTrack = null;
        playerScreen.setVisibility(View.GONE);
        library.setVisibility(View.VISIBLE);
    }

    private void hideKeyboard() {
        getSystemService(InputMethodManager.class).hideSoftInputFromWindow(search.getWindowToken(), 0);
        search.clearFocus();
        library.requestFocus();
    }

    private void withPlayback(java.util.function.Consumer<PlaybackService> action) {
        if (playback != null) {
            action.accept(playback);
        }
    }

    private static void setEnabled(View button, boolean enabled) {
        button.setEnabled(enabled);
        button.setAlpha(enabled ? 1f : 0.4f);
    }

    private static void markHeading(View view) {
        if (Build.VERSION.SDK_INT >= 28) {
            view.setAccessibilityHeading(true);
        }
    }

    private static String formatTime(long milliseconds) {
        long seconds = Math.max(0, milliseconds) / 1_000;
        return seconds < 3_600
                ? String.format(Locale.getDefault(), "%d:%02d", seconds / 60, seconds % 60)
                : String.format(Locale.getDefault(), "%d:%02d:%02d", seconds / 3_600, (seconds / 60) % 60, seconds % 60);
    }

    private final class TrackAdapter extends BaseAdapter {
        private List<Track> visible = Collections.emptyList();

        @Override public int getCount() { return visible.size(); }
        @Override public Track getItem(int position) { return visible.get(position); }
        @Override public long getItemId(int position) { return position; }

        @Override public View getView(int position, View recycled, ViewGroup parent) {
            TrackRow row = recycled == null ? new TrackRow() : (TrackRow) recycled;
            row.bind(getItem(position));
            return row;
        }
    }

    private final class TrackRow extends LinearLayout {
        private final ImageView icon;
        private final TextView title;
        private final TextView artist;
        private final TextView duration;

        TrackRow() {
            super(MainActivity.this);
            setGravity(Gravity.CENTER_VERTICAL);
            setPadding(dp(8), dp(8), dp(10), dp(8));
            setMinimumHeight(dp(65));
            icon = noteTile(SOFT_GREEN, GREEN, 12, 11);
            addView(icon, new LinearLayout.LayoutParams(dp(44), dp(44)));
            LinearLayout labels = column();
            LinearLayout.LayoutParams labelSize = new LinearLayout.LayoutParams(0, WRAP_CONTENT, 1);
            labelSize.leftMargin = dp(12);
            labelSize.rightMargin = dp(10);
            addView(labels, labelSize);
            title = singleLineText(16, INK, true);
            artist = singleLineText(13, MUTED, false);
            labels.addView(title);
            addWithMargin(labels, artist, MATCH_PARENT, WRAP_CONTENT, 3);
            duration = text(0, 13, MUTED, false);
            addView(duration);
        }

        void bind(Track track) {
            boolean selected = track.uri().equals(selectedTrack);
            setBackground(ripple(selected ? SOFT_GREEN : Color.TRANSPARENT, 14));
            setSelected(selected);
            icon.setImageResource(selected ? R.drawable.ic_play : R.drawable.ic_note);
            icon.setColorFilter(selected ? Color.WHITE : GREEN);
            icon.setBackground(round(selected ? GREEN : SOFT_GREEN, 12));
            title.setText(track.title());
            artist.setText(track.artist().isEmpty() ? getString(R.string.audio_file) : track.artist());
            duration.setText(track.duration() > 0 ? formatTime(track.duration()) : "");
        }
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private LinearLayout row() {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        return row;
    }

    private LinearLayout column() {
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        return column;
    }

    private TextView text(int resource, int size, int color, boolean emphasis) {
        TextView text = new TextView(this);
        text.setTextSize(size);
        text.setTextColor(color);
        text.setIncludeFontPadding(false);
        if (emphasis) {
            text.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        }
        if (resource != 0) {
            text.setText(resource);
        }
        return text;
    }

    private TextView singleLineText(int size, int color, boolean emphasis) {
        TextView text = text(0, size, color, emphasis);
        text.setSingleLine(true);
        text.setEllipsize(TextUtils.TruncateAt.END);
        return text;
    }

    private Button button(int label, int color, int fill) {
        Button button = new Button(this, null, android.R.attr.borderlessButtonStyle);
        button.setText(label);
        button.setTextColor(color);
        button.setTextSize(14);
        button.setAllCaps(false);
        button.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        button.setPadding(dp(13), 0, dp(13), 0);
        button.setMinWidth(0);
        button.setMinimumWidth(0);
        button.setBackground(ripple(fill, 14));
        return button;
    }

    private ImageView image(int resource) {
        ImageView image = new ImageView(this);
        image.setImageResource(resource);
        image.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        return image;
    }

    private ImageView noteTile(int fill, int color, int radius, int padding) {
        ImageView tile = image(R.drawable.ic_note);
        tile.setBackground(round(fill, radius));
        tile.setColorFilter(color);
        tile.setPadding(dp(padding), dp(padding), dp(padding), dp(padding));
        return tile;
    }

    private ImageButton iconButton(int icon, int label, int color, int fill, int size, int iconSize, Runnable action) {
        ImageButton button = new ImageButton(this);
        button.setImageResource(icon);
        button.setColorFilter(color);
        button.setContentDescription(getString(label));
        button.setBackground(ripple(fill, size / 2));
        int padding = dp((size - iconSize) / 2);
        button.setPadding(padding, padding, padding, padding);
        button.setScaleType(ImageView.ScaleType.FIT_CENTER);
        button.setOnClickListener(view -> action.run());
        return button;
    }

    private Drawable smallIcon(int resource, int color) {
        Drawable icon = getDrawable(resource).mutate();
        icon.setTint(color);
        icon.setBounds(0, 0, dp(19), dp(19));
        return icon;
    }

    private GradientDrawable round(int color, int radius) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(radius));
        return drawable;
    }

    private RippleDrawable ripple(int color, int radius) {
        return new RippleDrawable(ColorStateList.valueOf(0x24488866), round(color, radius), round(Color.WHITE, radius));
    }

    private void addWithMargin(LinearLayout parent, View child, int width, int height, int top) {
        LinearLayout.LayoutParams size = new LinearLayout.LayoutParams(width, height);
        size.topMargin = dp(top);
        parent.addView(child, size);
    }
}
