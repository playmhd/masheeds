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
import android.graphics.Bitmap;
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
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.accessibility.AccessibilityNodeInfo;
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
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;
import android.window.OnBackInvokedCallback;
import android.window.OnBackInvokedDispatcher;

import com.masheeds.player.TrackLibrary.Track;
import com.masheeds.player.PlaylistStore.Playlist;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

import static android.view.ViewGroup.LayoutParams.MATCH_PARENT;
import static android.view.ViewGroup.LayoutParams.WRAP_CONTENT;

public final class MainActivity extends Activity {
    private static final int PLAYER = 0xFF173D30;
    private static final int PLAYER_MUTED = 0xFFC5D9CB;
    private static final int THUMBNAIL_DP = 48;
    private static final int PICK_FILES = 1;
    private static final int AUDIO_ACCESS = 2;
    private static final int PICK_COVER = 3;

    private enum Screen { LIBRARY, PLAYLISTS, DISCOVER, SETTINGS, PLAYER }

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService scanner = Executors.newSingleThreadExecutor();
    private final Runnable scan = this::refreshLibrary;
    private final Consumer<String> artworkChanged = key -> refreshArtworkRows();
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
            playback.refreshLanguage();
            playback.setRepeatMode(repeatMode);
            playback.setListener(MainActivity.this::renderPlayback);
            maybeRestorePlayback();
            renderPlayback();
        }

        @Override public void onServiceDisconnected(ComponentName name) {
            playback = null;
            renderPlayback();
        }
    };

    private PlaybackService playback;
    private Screen screen = Screen.LIBRARY;
    private Screen tab = Screen.LIBRARY;
    private RepeatMode repeatMode;
    private Locale appLocale;
    private boolean darkMode;
    private int ink;
    private int muted;
    private int accent;
    private int onAccent;
    private int accentSurface;
    private int surface;
    private int rippleColor;
    private boolean bound;
    private boolean observing;
    private boolean scrubbing;
    private boolean loaded;
    private boolean restoreLibraryReady;
    private boolean restoreAttempted;
    private String pendingCoverKey;
    private long coverRevision;
    private boolean keyboardOpen;
    private OnBackInvokedCallback backCallback;
    private Future<?> scanTask;
    private int scanVersion;
    private List<Track> tracks = Collections.emptyList();
    private Uri selectedTrack;
    private Uri scrubTrack;

    private LinearLayout tabShell;
    private LinearLayout bottomNavigation;
    private final Map<Screen, Button> navigationButtons = new EnumMap<>(Screen.class);
    private LinearLayout library;
    private PlaylistPage playlistPage;
    private LinearLayout libraryHeading;
    private LinearLayout trackHeading;
    private EditText search;
    private TextView count;
    private TrackAdapter adapter;
    private ListView trackList;
    private ScrollView placeholder;
    private ProgressBar loading;
    private ImageView emptyIcon;
    private TextView emptyTitle;
    private TextView emptyMessage;
    private LinearLayout miniPlayer;
    private LinearLayout miniDetails;
    private ImageView miniArtwork;
    private TextView miniTitle;
    private TextView miniArtist;
    private ImageButton miniToggle;
    private ImageButton miniNext;
    private ProgressBar miniProgress;
    private ScrollView playerScreen;
    private ImageView playerArtwork;
    private TextView playerTitle;
    private TextView playerArtist;
    private TextView elapsed;
    private TextView totalTime;
    private SeekBar seek;
    private ImageButton playerToggle;
    private ImageButton playerNext;
    private ImageButton playerPrevious;
    private ImageButton playerRepeat;
    private ImageButton playerTimer;
    private TextView timerStatus;
    private Button cancelTimer;
    private BaseAdapter timerChoices;
    private LinearLayout discoverScreen;
    private ScrollView settingsScreen;
    private TextView audioAccessSummary;

    @Override protected void attachBaseContext(Context base) {
        super.attachBaseContext(AppPreferences.configuredContext(base));
    }

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        repeatMode = RepeatMode.load(this);
        appLocale = AppPreferences.locale(this);
        darkMode = AppPreferences.isDark(this);
        ink = getColor(R.color.text_primary);
        muted = getColor(R.color.text_secondary);
        accent = getColor(R.color.accent);
        onAccent = getColor(R.color.on_accent);
        accentSurface = getColor(R.color.accent_surface);
        surface = getColor(R.color.surface);
        rippleColor = getColor(R.color.ripple);
        setVolumeControlStream(AudioManager.STREAM_MUSIC);
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(getColor(R.color.app_background));
        root.setLayoutDirection(getResources().getConfiguration().getLayoutDirection());
        root.setOnApplyWindowInsetsListener(this::applyInsets);
        if (Build.VERSION.SDK_INT >= 30) {
            getWindow().setDecorFitsSystemWindows(false);
        } else {
            int lightBars = darkMode ? 0 : View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
            getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    | lightBars);
        }

        FrameLayout content = new FrameLayout(this);
        int maxWidth = dp(640);
        int screenWidth = dp(getResources().getConfiguration().screenWidthDp);
        root.addView(content, new FrameLayout.LayoutParams(
                screenWidth > maxWidth ? maxWidth : MATCH_PARENT, MATCH_PARENT, Gravity.CENTER));
        tabShell = buildTabShell();
        content.addView(tabShell, new FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT));
        playerScreen = buildPlayerScreen();
        playerScreen.setVisibility(View.GONE);
        content.addView(playerScreen, new FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT));
        renderRepeatMode();
        showScreen(Screen.LIBRARY);
        setContentView(root);
        if (state != null) {
            pendingCoverKey = state.getString("pendingCover");
            search.setText(state.getString("query", ""));
            String savedScreen = state.getString("screen");
            String savedTab = state.getString("tab", savedScreen);
            playlistPage.restoreSelection(state.getString("playlist"));
            for (Screen destination : navigationButtons.keySet()) {
                if (destination.name().equals(savedTab)) {
                    showScreen(destination);
                    break;
                }
            }
            int scroll = state.getInt("settingsScroll");
            settingsScreen.post(() -> settingsScreen.scrollTo(0, scroll));
            if (Screen.PLAYER.name().equals(savedScreen)) {
                screen = Screen.PLAYER;
            }
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
                bottomNavigation.setVisibility(visible ? View.GONE : View.VISIBLE);
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
        CustomArtwork.addListener(artworkChanged);
        refreshArtworkRows();
        restoreLibraryReady = false;
        restoreAttempted = false;
        bound = bindService(new Intent(this, PlaybackService.class), connection, Context.BIND_AUTO_CREATE);
        handler.post(progressUpdate);
    }

    @Override public void onResume() {
        super.onResume();
        renderAudioAccess();
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
        CustomArtwork.removeListener(artworkChanged);
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
        state.putString("pendingCover", pendingCoverKey);
        state.putString("query", search.getText().toString());
        state.putString("screen", screen.name());
        state.putString("tab", tab.name());
        state.putString("playlist", playlistPage.selectedId);
        state.putInt("settingsScroll", settingsScreen.getScrollY());
        super.onSaveInstanceState(state);
    }

    // Android 13 and up use the registered OnBackInvokedCallback.
    @SuppressLint("GestureBackNavigation")
    @Override public void onBackPressed() {
        if (Build.VERSION.SDK_INT < 33 && screen != Screen.LIBRARY) {
            goBack();
        } else {
            super.onBackPressed();
        }
    }

    private LinearLayout buildTabShell() {
        LinearLayout shell = column();
        FrameLayout pages = new FrameLayout(this);
        shell.addView(pages, new LinearLayout.LayoutParams(MATCH_PARENT, 0, 1));
        library = buildLibrary();
        playlistPage = new PlaylistPage();
        discoverScreen = buildDiscoverScreen();
        settingsScreen = buildSettingsScreen();
        pages.addView(library, new FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT));
        pages.addView(playlistPage, new FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT));
        pages.addView(discoverScreen, new FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT));
        pages.addView(settingsScreen, new FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT));

        miniPlayer = buildMiniPlayer();
        miniPlayer.setVisibility(View.GONE);
        LinearLayout.LayoutParams miniSize = new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT);
        miniSize.setMargins(dp(20), dp(6), dp(20), dp(8));
        shell.addView(miniPlayer, miniSize);

        bottomNavigation = row();
        bottomNavigation.setPadding(dp(8), dp(4), dp(8), dp(4));
        bottomNavigation.setBackgroundColor(surface);
        addNavigationButton(Screen.LIBRARY, R.string.library_settings, R.drawable.ic_tracks);
        addNavigationButton(Screen.PLAYLISTS, R.string.playlists, R.drawable.ic_playlists);
        addNavigationButton(Screen.DISCOVER, R.string.discover, R.drawable.ic_discover);
        addNavigationButton(Screen.SETTINGS, R.string.settings, R.drawable.ic_settings);
        shell.addView(bottomNavigation, new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT));
        return shell;
    }

    private void addNavigationButton(Screen destination, int label, int icon) {
        Button navigation = button(label, muted, Color.TRANSPARENT);
        navigation.setCompoundDrawablesRelative(null, smallIcon(icon, muted), null, null);
        navigation.setCompoundDrawablePadding(dp(4));
        navigation.setTextSize(12);
        navigation.setPadding(dp(4), dp(6), dp(4), dp(6));
        navigation.setMinimumHeight(dp(56));
        navigation.setOnClickListener(view -> {
            if (screen != destination && AppPreferences.navigationHapticsEnabled(this)) {
                int feedback = Build.VERSION.SDK_INT >= 34
                        ? HapticFeedbackConstants.SEGMENT_TICK : HapticFeedbackConstants.CLOCK_TICK;
                view.performHapticFeedback(feedback);
            }
            if (screen == Screen.PLAYLISTS && destination == Screen.PLAYLISTS) {
                playlistPage.closePlaylist();
            }
            hideKeyboard();
            showScreen(destination);
        });
        LinearLayout.LayoutParams size = new LinearLayout.LayoutParams(0, WRAP_CONTENT, 1);
        size.setMargins(dp(2), 0, dp(2), 0);
        bottomNavigation.addView(navigation, size);
        navigationButtons.put(destination, navigation);
    }

    private LinearLayout buildLibrary() {
        LinearLayout page = column();
        page.setPadding(dp(20), dp(6), dp(20), dp(10));
        page.setFocusableInTouchMode(true);

        LinearLayout bar = row();
        ImageView logo = image(R.drawable.ic_masheeds);
        bar.addView(logo, new LinearLayout.LayoutParams(dp(38), dp(38)));
        TextView brand = text(R.string.app_name, 19, ink, true);
        LinearLayout.LayoutParams brandSize = new LinearLayout.LayoutParams(0, WRAP_CONTENT, 1);
        brandSize.setMarginStart(dp(10));
        bar.addView(brand, brandSize);
        ImageButton add = iconButton(R.drawable.ic_add, R.string.add_files, accent, accentSurface,
                48, 24, this::openPicker);
        bar.addView(add, new LinearLayout.LayoutParams(dp(48), dp(48)));
        page.addView(bar, new LinearLayout.LayoutParams(MATCH_PARENT, dp(58)));

        search = new EditText(this);
        search.setSingleLine(true);
        search.setHint(R.string.search_tracks);
        search.setTextColor(ink);
        search.setHintTextColor(muted);
        search.setTextSize(15);
        search.setInputType(InputType.TYPE_CLASS_TEXT);
        search.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        search.setPadding(dp(14), 0, dp(14), 0);
        search.setBackground(round(surface, 14));
        search.setCompoundDrawablesRelative(smallIcon(R.drawable.ic_search, muted), null, null, null);
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
        addWithMargin(page, search, MATCH_PARENT, dp(48), 8);

        libraryHeading = column();
        TextView title = text(R.string.library_title, 34, ink, true);
        markHeading(title);
        libraryHeading.addView(title);
        TextView subtitle = text(R.string.library_description, 15, muted, false);
        addWithMargin(libraryHeading, subtitle, MATCH_PARENT, WRAP_CONTENT, 3);
        addWithMargin(page, libraryHeading, MATCH_PARENT, WRAP_CONTENT, 18);

        trackHeading = row();
        TextView tracksLabel = text(R.string.tracks, 17, ink, true);
        markHeading(tracksLabel);
        trackHeading.addView(tracksLabel);
        count = text(0, 13, muted, false);
        LinearLayout.LayoutParams countSize = new LinearLayout.LayoutParams(0, WRAP_CONTENT, 1);
        countSize.setMarginStart(dp(10));
        trackHeading.addView(count, countSize);
        trackHeading.setVisibility(View.GONE);
        addWithMargin(page, trackHeading, MATCH_PARENT, dp(48), 6);

        FrameLayout results = new FrameLayout(this);
        page.addView(results, new LinearLayout.LayoutParams(MATCH_PARENT, 0, 1));
        trackList = styledList();
        adapter = new TrackAdapter();
        trackList.setAdapter(adapter);
        trackList.setOnItemClickListener((parent, row, position, id) -> selectTrack(tracks, adapter.getItem(position), null));
        trackList.setOnItemLongClickListener((parent, row, position, id) -> {
            hideKeyboard();
            openTrackMenu(adapter.getItem(position), null);
            return true;
        });
        trackList.setVisibility(View.GONE);
        results.addView(trackList, new FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT));
        placeholder = buildPlaceholder();
        placeholder.setVisibility(View.GONE);
        results.addView(placeholder, new FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT));
        loading = new ProgressBar(this);
        loading.setIndeterminateTintList(ColorStateList.valueOf(accent));
        loading.setContentDescription(getString(R.string.loading));
        results.addView(loading, new FrameLayout.LayoutParams(dp(32), dp(32), Gravity.CENTER));

        return page;
    }

    private ListView styledList() {
        ListView list = new ListView(this);
        styleList(list);
        return list;
    }

    private void styleList(ListView list) {
        list.setDivider(null);
        list.setClipToPadding(false);
        list.setPadding(0, 0, 0, dp(6));
        list.setSelector(android.R.color.transparent);
    }

    private ScrollView buildPlaceholder() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        LinearLayout panel = column();
        panel.setGravity(Gravity.CENTER);
        panel.setPadding(dp(16), dp(20), dp(16), dp(36));
        scroll.addView(panel);

        emptyIcon = image(R.drawable.ic_library);
        emptyIcon.setBackground(round(accentSurface, 28));
        emptyIcon.setPadding(dp(18), dp(18), dp(18), dp(18));
        panel.addView(emptyIcon, new LinearLayout.LayoutParams(dp(94), dp(94)));
        emptyTitle = text(0, 23, ink, true);
        emptyTitle.setGravity(Gravity.CENTER);
        addWithMargin(panel, emptyTitle, MATCH_PARENT, WRAP_CONTENT, 18);
        emptyMessage = text(R.string.no_matches_description, 15, muted, false);
        emptyMessage.setGravity(Gravity.CENTER);
        emptyMessage.setMaxWidth(dp(290));
        emptyMessage.setLineSpacing(dp(3), 1);
        addWithMargin(panel, emptyMessage, WRAP_CONTENT, WRAP_CONTENT, 6);
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
        miniArtwork = artworkTile(0xFF315647, 10);
        int thumbnailSize = dp(THUMBNAIL_DP);
        miniDetails.addView(miniArtwork, new LinearLayout.LayoutParams(thumbnailSize, thumbnailSize));
        LinearLayout labels = column();
        LinearLayout.LayoutParams labelSize = new LinearLayout.LayoutParams(0, WRAP_CONTENT, 1);
        labelSize.setMarginStart(dp(10));
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
        miniProgress.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
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

        FrameLayout header = screenHeader(text(R.string.now_playing, 16, ink, true));
        ImageButton close = iconButton(R.drawable.ic_down, R.string.close_player, accent, accentSurface,
                48, 24, this::goBack);
        addHeaderButton(header, close, Gravity.START);
        playerTimer = iconButton(R.drawable.ic_timer, R.string.sleep_timer, accent, accentSurface,
                48, 24, this::openSleepTimer);
        addHeaderButton(header, playerTimer, Gravity.END);
        page.addView(header, new LinearLayout.LayoutParams(MATCH_PARENT, dp(54)));

        int width = getResources().getConfiguration().screenWidthDp;
        int height = getResources().getConfiguration().screenHeightDp;
        int coverSize = dp(Math.min(320, Math.min(width - 48, Math.max(144, height - 330))));
        playerArtwork = artworkTile(accentSurface, 28);
        playerArtwork.setScaleType(ImageView.ScaleType.FIT_CENTER);
        FrameLayout coverPanel = new FrameLayout(this);
        coverPanel.addView(playerArtwork, new FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT));
        ImageButton addToPlaylist = iconButton(R.drawable.ic_add_to_playlist, R.string.add_to_playlist,
                accent, accentSurface, 48, 22, this::openAddToPlaylist);
        FrameLayout.LayoutParams actionPosition =
                new FrameLayout.LayoutParams(dp(48), dp(48), Gravity.END | Gravity.BOTTOM);
        actionPosition.setMarginEnd(dp(10));
        actionPosition.bottomMargin = dp(10);
        coverPanel.addView(addToPlaylist, actionPosition);
        addWithMargin(page, coverPanel, coverSize, coverSize, 22);

        playerTitle = text(0, 27, ink, true);
        playerTitle.setGravity(Gravity.CENTER);
        playerTitle.setMaxLines(2);
        playerTitle.setEllipsize(TextUtils.TruncateAt.END);
        addWithMargin(page, playerTitle, MATCH_PARENT, WRAP_CONTENT, 21);
        playerArtist = singleLineText(15, muted, false);
        playerArtist.setGravity(Gravity.CENTER);
        addWithMargin(page, playerArtist, MATCH_PARENT, WRAP_CONTENT, 4);

        seek = new SeekBar(this);
        seek.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        seek.setPadding(dp(6), 0, dp(6), 0);
        seek.setContentDescription(getString(R.string.seek));
        seek.setProgressTintList(ColorStateList.valueOf(accent));
        seek.setProgressBackgroundTintList(ColorStateList.valueOf(getColor(R.color.seek_track)));
        seek.setThumbTintList(ColorStateList.valueOf(accent));
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
        times.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        elapsed = text(0, 13, muted, false);
        totalTime = text(0, 13, muted, false);
        times.addView(elapsed, new LinearLayout.LayoutParams(0, WRAP_CONTENT, 1));
        times.addView(totalTime);
        page.addView(times, new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT));

        LinearLayout buttons = row();
        buttons.setGravity(Gravity.CENTER);
        buttons.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        playerPrevious = iconButton(R.drawable.ic_previous, R.string.previous_track, accent, accentSurface, 54, 26,
                () -> withPlayback(PlaybackService::previous));
        playerToggle = iconButton(R.drawable.ic_play, R.string.play, onAccent, accent, 72, 34,
                () -> withPlayback(PlaybackService::toggle));
        playerNext = iconButton(R.drawable.ic_next, R.string.next_track, accent, accentSurface, 54, 26,
                () -> withPlayback(PlaybackService::next));
        playerRepeat = iconButton(R.drawable.ic_repeat, repeatMode.label, muted, surface,
                48, 24, this::cycleRepeatMode);
        buttons.addView(playerPrevious, new LinearLayout.LayoutParams(dp(54), dp(54)));
        LinearLayout.LayoutParams toggleSize = new LinearLayout.LayoutParams(dp(72), dp(72));
        toggleSize.setMargins(dp(12), 0, dp(12), 0);
        buttons.addView(playerToggle, toggleSize);
        buttons.addView(playerNext, new LinearLayout.LayoutParams(dp(54), dp(54)));
        LinearLayout.LayoutParams repeatSize = new LinearLayout.LayoutParams(dp(48), dp(48));
        repeatSize.setMarginStart(dp(12));
        buttons.addView(playerRepeat, repeatSize);
        addWithMargin(page, buttons, MATCH_PARENT, WRAP_CONTENT, 22);
        return screen;
    }

    private LinearLayout buildDiscoverScreen() {
        LinearLayout page = column();
        page.setPadding(dp(20), dp(6), dp(20), dp(10));
        page.addView(screenHeader(text(R.string.discover, 16, ink, true)),
                new LinearLayout.LayoutParams(MATCH_PARENT, dp(54)));

        TextView message = text(R.string.discover_placeholder, 15, muted, false);
        message.setGravity(Gravity.CENTER);
        page.addView(message, new LinearLayout.LayoutParams(MATCH_PARENT, 0, 1));
        return page;
    }

    private ScrollView buildSettingsScreen() {
        ScrollView settings = new ScrollView(this);
        settings.setFillViewport(true);
        LinearLayout page = column();
        page.setPadding(dp(20), dp(6), dp(20), dp(24));
        settings.addView(page);
        page.addView(screenHeader(text(R.string.settings, 16, ink, true)),
                new LinearLayout.LayoutParams(MATCH_PARENT, dp(54)));

        TextView preferencesSection = text(R.string.preferences, 18, ink, true);
        markHeading(preferencesSection);
        addWithMargin(page, preferencesSection, MATCH_PARENT, WRAP_CONTENT, 24);
        LinearLayout preferencesCard = column();
        preferencesCard.setPadding(dp(2), dp(4), dp(2), dp(4));
        preferencesCard.setBackground(round(surface, 16));
        addWithMargin(page, preferencesCard, MATCH_PARENT, WRAP_CONTENT, 10);

        int[] themes = {R.string.theme_light, R.string.theme_dark};
        int theme = darkMode ? 1 : 0;
        Button themeButton = preferenceButton(R.string.theme, themes[theme],
                () -> choosePreference(R.string.theme, themes, theme, choice -> {
                    AppPreferences.setDark(this, choice == 1);
                    recreate();
                }));
        preferencesCard.addView(themeButton, new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT));

        int[] languages = {R.string.language_english, R.string.language_arabic};
        int language = appLocale.equals(AppPreferences.ARABIC) ? 1 : 0;
        Button languageButton = preferenceButton(R.string.app_language, languages[language],
                () -> choosePreference(R.string.app_language, languages, language, choice -> {
                    AppPreferences.setLocale(this, choice == 1 ? AppPreferences.ARABIC : Locale.ENGLISH);
                    withPlayback(PlaybackService::refreshLanguage);
                    recreate();
                }));
        preferencesCard.addView(languageButton, new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT));

        Switch haptics = new Switch(this);
        haptics.setText(R.string.navigation_haptics);
        haptics.setTextColor(ink);
        haptics.setTextSize(14);
        haptics.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        haptics.setMinHeight(dp(48));
        haptics.setPaddingRelative(dp(13), 0, dp(13), 0);
        haptics.setSwitchPadding(dp(12));
        haptics.setBackground(ripple(Color.TRANSPARENT, 14));
        haptics.setChecked(AppPreferences.navigationHapticsEnabled(this));
        haptics.setOnCheckedChangeListener((view, enabled) -> AppPreferences.setNavigationHapticsEnabled(this, enabled));
        preferencesCard.addView(haptics, new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT));

        TextView librarySection = text(R.string.library_settings, 18, ink, true);
        markHeading(librarySection);
        addWithMargin(page, librarySection, MATCH_PARENT, WRAP_CONTENT, 24);
        LinearLayout accessCard = column();
        accessCard.setPadding(dp(14), dp(14), dp(14), dp(14));
        accessCard.setBackground(round(surface, 16));
        addWithMargin(page, accessCard, MATCH_PARENT, WRAP_CONTENT, 10);
        audioAccessSummary = text(0, 15, muted, false);
        accessCard.addView(audioAccessSummary);
        Button scanButton = button(R.string.scan_device, onAccent, accent);
        scanButton.setOnClickListener(view -> scanDevice());
        addWithMargin(accessCard, scanButton, WRAP_CONTENT, dp(48), 12);
        Button audioAccess = button(R.string.audio_access, accent, accentSurface);
        audioAccess.setOnClickListener(view -> openAppSettings());
        addWithMargin(accessCard, audioAccess, WRAP_CONTENT, dp(48), 8);

        TextView version = text(0, 14, muted, false);
        version.setText(getString(R.string.app_version, BuildConfig.VERSION_NAME));
        addWithMargin(page, version, MATCH_PARENT, WRAP_CONTENT, 24);
        return settings;
    }

    private Button preferenceButton(int title, int selection, Runnable action) {
        Button preference = button(title, ink, Color.TRANSPARENT);
        preference.setText(getString(R.string.preference_value, getString(title), getString(selection)));
        preference.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        preference.setMinHeight(dp(48));
        preference.setOnClickListener(view -> action.run());
        return preference;
    }

    private void choosePreference(int title, int[] options, int selected, IntConsumer onSelection) {
        MasheedsDialog dialog = new MasheedsDialog(this, getText(title));
        for (int index = 0; index < options.length; index++) {
            int choice = index;
            dialog.addChoice(getText(options[index]), index == selected, () -> {
                dialog.dismiss();
                if (choice != selected) {
                    onSelection.accept(choice);
                }
            });
        }
        dialog.addCancel();
        dialog.show();
    }

    private FrameLayout screenHeader(TextView label) {
        FrameLayout header = new FrameLayout(this);
        markHeading(label);
        label.setGravity(Gravity.CENTER);
        FrameLayout.LayoutParams size = new FrameLayout.LayoutParams(
                MATCH_PARENT, WRAP_CONTENT, Gravity.START | Gravity.CENTER_VERTICAL);
        size.setMarginStart(dp(54));
        size.setMarginEnd(dp(54));
        header.addView(label, size);
        return header;
    }

    private void addHeaderButton(FrameLayout header, ImageButton button, int edge) {
        header.addView(button, new FrameLayout.LayoutParams(dp(48), dp(48), edge | Gravity.CENTER_VERTICAL));
    }

    private void cycleRepeatMode() {
        repeatMode = repeatMode.next();
        repeatMode.save(this);
        renderRepeatMode();
        if (playback != null) {
            playback.setRepeatMode(repeatMode);
        }
    }

    private void renderRepeatMode() {
        boolean enabled = repeatMode != RepeatMode.OFF;
        playerRepeat.setImageResource(repeatMode == RepeatMode.ONE ? R.drawable.ic_repeat_one : R.drawable.ic_repeat);
        playerRepeat.setColorFilter(enabled ? onAccent : muted);
        playerRepeat.setBackground(ripple(enabled ? accent : surface, 24));
        playerRepeat.setContentDescription(getString(R.string.change_repeat_mode, getString(repeatMode.label)));
    }

    private void renderAudioAccess() {
        audioAccessSummary.setText(hasAudioAccess() ? R.string.audio_access_allowed : R.string.audio_access_needed);
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
        if (requestCode == PICK_COVER) {
            String key = pendingCoverKey;
            pendingCoverKey = null;
            if (key != null && resultCode == RESULT_OK && data != null && data.getData() != null) {
                updateCover(key, data.getData());
            }
            return;
        }
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

    private void openTrackMenu(Track track, Playlist playlist) {
        String trackKey = CustomArtwork.trackKey(track);
        MasheedsDialog dialog = new MasheedsDialog(this, track.displayTitle(this));
        dialog.addMenuItem(R.string.set_track_cover, () -> {
            dialog.dismiss();
            openCoverPicker(trackKey);
        });
        if (playlist != null) {
            String albumKey = CustomArtwork.albumKey(track);
            if (CustomArtwork.hasCover(this, trackKey)) {
                dialog.addMenuItem(R.string.remove_track_cover, () -> {
                    dialog.dismiss();
                    updateCover(trackKey, null);
                });
            }
            if (CustomArtwork.hasCover(this, albumKey)) {
                dialog.addMenuItem(R.string.remove_album_cover, () -> {
                    dialog.dismiss();
                    updateCover(albumKey, null);
                });
            }
            dialog.addMenuItem(R.string.remove_from_playlist, () -> {
                dialog.dismiss();
                playlistPage.confirmRemoveTrack(playlist, track);
            });
        } else {
            dialog.addMenuItem(R.string.delete_track, () -> {
                dialog.dismiss();
                confirmDeleteTrack(track);
            });
        }
        dialog.addCancel();
        dialog.show();
    }

    private void confirmDeleteTrack(Track track) {
        MasheedsDialog dialog = new MasheedsDialog(this, getText(R.string.delete_track));
        dialog.addMessage(getString(R.string.delete_track_message, track.displayTitle(this)));
        dialog.addCancel();
        dialog.addAction(R.string.delete_track, true, () -> {
            String trackId = track.libraryUri().toString();
            TrackLibrary.remove(this, track);
            playlistPage.removeTrackFromAll(trackId);
            withPlayback(service -> service.removeTrack(trackId));
            PlaybackCheckpoint.forgetTrack(this, trackId);

            List<Track> remaining = new ArrayList<>(tracks);
            remaining.removeIf(item -> item.libraryUri().equals(track.libraryUri()));
            showLibraryTracks(remaining);
            refreshLibrary();
            dialog.dismiss();
        });
        dialog.show();
    }

    private void openCoverPicker(String key) {
        pendingCoverKey = key;
        Intent picker = new Intent(Intent.ACTION_OPEN_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType("image/*")
                .putExtra(Intent.EXTRA_LOCAL_ONLY, true)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            startActivityForResult(picker, PICK_COVER);
        } catch (ActivityNotFoundException error) {
            pendingCoverKey = null;
            Toast.makeText(this, R.string.picker_error, Toast.LENGTH_SHORT).show();
        }
    }

    private void updateCover(String key, Uri image) {
        CustomArtwork.update(this, key, image, success -> {
            if (!success && !isDestroyed()) {
                Toast.makeText(this, R.string.cover_update_error, Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void refreshArtworkRows() {
        long revision = CustomArtwork.revision();
        if (coverRevision != revision) {
            coverRevision = revision;
            adapter.notifyDataSetChanged();
            playlistPage.trackAdapter.notifyDataSetChanged();
            playlistPage.playlistAdapter.notifyDataSetChanged();
        }
    }

    private boolean rememberFile(Uri uri) {
        if (uri == null) {
            return false;
        }
        try {
            getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
            TrackLibrary.restoreDocument(this, uri);
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

    private void scanDevice() {
        if (hasAudioAccess()) {
            refreshLibrary();
            showScreen(Screen.LIBRARY);
        } else if (requiresPermissionSettings()) {
            openAppSettings();
        } else {
            requestPermissions(new String[]{audioPermission()}, AUDIO_ACCESS);
        }
    }

    private void openAppSettings() {
        startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", getPackageName(), null)));
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode == AUDIO_ACCESS && results.length > 0) {
            getPreferences(MODE_PRIVATE).edit().putBoolean("askedForAudio", true).apply();
            renderAudioAccess();
            watchDeviceLibrary();
            refreshLibrary();
            if (results[0] == PackageManager.PERMISSION_GRANTED) {
                showScreen(Screen.LIBRARY);
            }
        }
    }

    private void refreshLibrary() {
        boolean scanDevice = hasAudioAccess();
        int version = ++scanVersion;
        if (scanTask != null) {
            scanTask.cancel(true);
        }
        Context app = AppPreferences.configuredContext(getApplicationContext());
        scanTask = scanner.submit(() -> {
            List<Track> found = TrackLibrary.load(app, scanDevice);
            handler.post(() -> {
                if (!isDestroyed() && version == scanVersion) {
                    restoreLibraryReady = true;
                    showLibraryTracks(found);
                    maybeRestorePlayback();
                }
            });
        });
    }

    private void showLibraryTracks(List<Track> available) {
        tracks = available;
        loaded = true;
        renderLibrary();
        playlistPage.updateLibrary(available);
    }

    private void renderLibrary() {
        if (!loaded) {
            return;
        }
        String query = search.getText().toString().trim().toLowerCase(Locale.ROOT);
        List<Track> visible = new ArrayList<>();
        for (Track track : tracks) {
            if (query.isEmpty() || track.displayTitle(this).toLowerCase(Locale.ROOT).contains(query)
                    || track.artist().toLowerCase(Locale.ROOT).contains(query)) {
                visible.add(track);
            }
        }
        adapter.visible = visible;
        adapter.notifyDataSetChanged();
        boolean hasTracks = !tracks.isEmpty();
        search.setVisibility(hasTracks ? View.VISIBLE : View.GONE);
        trackHeading.setVisibility(hasTracks ? View.VISIBLE : View.GONE);
        int countLabel = query.isEmpty() ? R.plurals.track_count : R.plurals.match_count;
        count.setText(getResources().getQuantityString(countLabel, visible.size(), visible.size()));
        loading.setVisibility(View.GONE);
        trackList.setVisibility(visible.isEmpty() ? View.GONE : View.VISIBLE);
        placeholder.setVisibility(visible.isEmpty() ? View.VISIBLE : View.GONE);
        if (visible.isEmpty()) {
            emptyIcon.setVisibility(hasTracks ? View.GONE : View.VISIBLE);
            emptyTitle.setText(hasTracks ? R.string.no_matches_title : R.string.empty_title);
            emptyMessage.setVisibility(hasTracks ? View.VISIBLE : View.GONE);
        }
    }

    private void maybeRestorePlayback() {
        if (playback == null || restoreAttempted) {
            return;
        }
        if (playback.currentTrack() != null) {
            restoreAttempted = true;
            return;
        }
        if (!restoreLibraryReady) {
            return;
        }
        PlaybackCheckpoint checkpoint = PlaybackCheckpoint.load(this);
        if (checkpoint == null) {
            restoreAttempted = true;
            return;
        }
        Playlist playlist = playlistPage.findPlaylist(checkpoint.playlistId());
        List<Track> queue = playlist == null ? tracks : playlistPage.resolveTracks(playlist);
        int index = trackIndex(queue, checkpoint.trackId());
        if (index < 0 && playlist != null) {
            playlist = null;
            queue = tracks;
            index = trackIndex(queue, checkpoint.trackId());
        }
        if (index >= 0) {
            restoreAttempted = true;
            playback.restore(queue, index, playlist == null ? null : playlist.id(), checkpoint.position());
        }
    }

    private static int trackIndex(List<Track> queue, String trackId) {
        for (int index = 0; index < queue.size(); index++) {
            if (queue.get(index).libraryUri().toString().equals(trackId)) {
                return index;
            }
        }
        return -1;
    }

    private void selectTrack(List<Track> queue, Track track, String playlistId) {
        if (playback == null) {
            return;
        }
        restoreAttempted = true;
        hideKeyboard();
        Track current = playback.currentTrack();
        if (current != null && track.libraryUri().equals(current.libraryUri()) && playback.hasQueue(queue, playlistId)) {
            playback.toggle();
        } else {
            playback.play(queue, queue.indexOf(track), playlistId);
        }
    }

    private void renderPlayback() {
        Track current = playback == null ? null : playback.currentTrack();
        Uri uri = current == null ? null : current.uri();
        if (!Objects.equals(selectedTrack, uri)) {
            selectedTrack = uri;
            adapter.notifyDataSetChanged();
            playlistPage.trackAdapter.notifyDataSetChanged();
        }
        miniPlayer.setVisibility(current == null || keyboardOpen ? View.GONE : View.VISIBLE);
        Bitmap artwork = current == null ? null : playback.currentArtwork();
        displayArtwork(miniArtwork, artwork, R.drawable.ic_note, PLAYER_MUTED, 10);
        displayArtwork(playerArtwork, artwork, R.drawable.ic_record, null, 8);
        boolean timerActive = sleepTimerActive();
        playerTimer.setBackground(ripple(timerActive ? accent : accentSurface, 24));
        playerTimer.setColorFilter(timerActive ? onAccent : accent);
        playerTimer.setContentDescription(getString(timerActive ? R.string.sleep_timer_active : R.string.sleep_timer));
        updateTimerStatus();
        if (current == null) {
            if (screen == Screen.PLAYER) {
                showScreen(tab);
            }
            return;
        }
        String artist = current.artist().isEmpty() ? getString(R.string.audio_file) : current.artist();
        String title = current.displayTitle(this);
        miniTitle.setText(title);
        miniArtist.setText(artist);
        miniDetails.setContentDescription(getString(R.string.show_now_playing, title, artist));
        playerTitle.setText(title);
        playerArtist.setText(artist);
        boolean playing = playback.isPlaybackRequested();
        for (ImageButton button : new ImageButton[]{miniToggle, playerToggle}) {
            button.setImageResource(playing ? R.drawable.ic_pause : R.drawable.ic_play);
            button.setContentDescription(getString(playing ? R.string.pause : R.string.play));
        }
        setEnabled(miniNext, playback.canSkipNext());
        setEnabled(playerNext, playback.canSkipNext());
        setEnabled(playerPrevious, playback.canSeek() || playback.canSkipPrevious());
        if (screen == Screen.PLAYER && playerScreen.getVisibility() != View.VISIBLE) {
            openPlayer();
        }
        updateProgress();
    }

    private void updateProgress() {
        updateTimerStatus();
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

    private boolean sleepTimerActive() {
        return playback != null && (playback.sleepTimerMinutes() > 0 || playback.sleepTimerEndsWithTrack());
    }

    private void openAddToPlaylist() {
        Track track = playback == null ? null : playback.currentTrack();
        if (track != null) {
            playlistPage.addTrack(track);
        }
    }

    private void openSleepTimer() {
        if (playback == null || playback.currentTrack() == null) {
            return;
        }
        MasheedsDialog dialog = new MasheedsDialog(this, getText(R.string.sleep_timer));
        TextView statusView = dialog.addMessage("");
        timerStatus = statusView;
        int[] durations = {15, 30, 60};
        CharSequence[] labels = new CharSequence[durations.length + 1];
        for (int index = 0; index < durations.length; index++) {
            int minutes = durations[index];
            labels[index] = getResources().getQuantityString(R.plurals.sleep_timer_minutes, minutes, minutes);
        }
        labels[durations.length] = getText(R.string.sleep_timer_end);
        timerChoices = dialog.addRadioOptions(labels, index -> playback != null && (index == durations.length
                ? playback.sleepTimerEndsWithTrack() : playback.sleepTimerMinutes() == durations[index]), index -> {
                    if (index == durations.length) {
                        withPlayback(PlaybackService::setSleepTimerAtTrackEnd);
                    } else {
                        withPlayback(service -> service.setSleepTimer(durations[index]));
                    }
                    dialog.dismiss();
                });
        dialog.addCancel();
        cancelTimer = dialog.addAction(R.string.sleep_timer_cancel, false, () -> {
            withPlayback(PlaybackService::cancelSleepTimer);
            dialog.dismiss();
        });
        dialog.setOnDismissListener(ignored -> {
            if (timerStatus == statusView) {
                timerStatus = null;
                cancelTimer = null;
                timerChoices = null;
            }
        });
        updateTimerStatus();
        dialog.show();
    }

    private void updateTimerStatus() {
        if (timerStatus == null) {
            return;
        }
        String status = getString(R.string.sleep_timer_off);
        if (sleepTimerActive()) {
            status = playback.sleepTimerEndsWithTrack() ? getString(R.string.sleep_timer_end_status)
                    : getString(R.string.sleep_timer_remaining, formatTime(playback.sleepTimerRemaining() + 999));
        }
        if (!status.contentEquals(timerStatus.getText())) {
            timerStatus.setText(status);
        }
        int visibility = sleepTimerActive() ? View.VISIBLE : View.GONE;
        if (cancelTimer.getVisibility() != visibility) {
            cancelTimer.setVisibility(visibility);
            timerChoices.notifyDataSetChanged();
        }
    }

    private void openPlayer() {
        if (playback == null || playback.currentTrack() == null || playerScreen.isShown()) {
            return;
        }
        hideKeyboard();
        showScreen(Screen.PLAYER);
        playerScreen.scrollTo(0, 0);
    }

    private void goBack() {
        if (screen == Screen.PLAYER) {
            showScreen(tab);
        } else if (screen != Screen.PLAYLISTS || !playlistPage.goBack()) {
            showScreen(Screen.LIBRARY);
        }
    }

    private void showScreen(Screen target) {
        if (target != Screen.PLAYLISTS) {
            playlistPage.finishReordering();
        }
        screen = target;
        playerScreen.setVisibility(target == Screen.PLAYER ? View.VISIBLE : View.GONE);
        tabShell.setVisibility(target == Screen.PLAYER ? View.GONE : View.VISIBLE);
        if (target != Screen.PLAYER) {
            tab = target;
            library.setVisibility(target == Screen.LIBRARY ? View.VISIBLE : View.GONE);
            playlistPage.setVisibility(target == Screen.PLAYLISTS ? View.VISIBLE : View.GONE);
            discoverScreen.setVisibility(target == Screen.DISCOVER ? View.VISIBLE : View.GONE);
            settingsScreen.setVisibility(target == Screen.SETTINGS ? View.VISIBLE : View.GONE);
            navigationButtons.forEach((destination, button) -> {
                boolean selected = destination == target;
                int color = selected ? accent : muted;
                button.setSelected(selected);
                button.setTextColor(color);
                button.getCompoundDrawablesRelative()[1].setTint(color);
                button.setBackground(ripple(selected ? accentSurface : Color.TRANSPARENT, 14));
            });
            scrubbing = false;
            scrubTrack = null;
        }
        if (Build.VERSION.SDK_INT >= 33) {
            if (target == Screen.LIBRARY && backCallback != null) {
                getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback(backCallback);
                backCallback = null;
            } else if (target != Screen.LIBRARY && backCallback == null) {
                backCallback = this::goBack;
                getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                        OnBackInvokedDispatcher.PRIORITY_DEFAULT, backCallback);
            }
        }
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

    private String formatTime(long milliseconds) {
        long seconds = Math.max(0, milliseconds) / 1_000;
        return seconds < 3_600
                ? String.format(appLocale, "%d:%02d", seconds / 60, seconds % 60)
                : String.format(appLocale, "%d:%02d:%02d", seconds / 3_600, (seconds / 60) % 60, seconds % 60);
    }

    private final class PlaylistPage extends LinearLayout {
        private final List<Playlist> playlists = PlaylistStore.load(MainActivity.this);
        private final Map<String, Track> tracksByUri = new HashMap<>();
        private final TrackAdapter trackAdapter = new TrackAdapter();
        private final BaseAdapter playlistAdapter = new BaseAdapter() {
            @Override public int getCount() { return playlists.size(); }
            @Override public Playlist getItem(int position) { return playlists.get(position); }
            @Override public long getItemId(int position) { return position; }

            @Override public View getView(int position, View recycled, ViewGroup parent) {
                Playlist playlist = getItem(position);
                PlaylistRow row = recycled == null ? new PlaylistRow() : (PlaylistRow) recycled;
                row.bind(playlist, resolveTracks(playlist));
                return row;
            }
        };

        private final TextView title;
        private final TextView empty;
        private final ImageButton back;
        private final ImageButton add;
        private final ImageButton reorder;
        private final PlaylistReorderList list;
        private String selectedId;

        PlaylistPage() {
            super(MainActivity.this);
            setOrientation(VERTICAL);
            setPadding(dp(20), dp(6), dp(20), dp(10));
            setFocusableInTouchMode(true);

            title = singleLineText(16, ink, true);
            FrameLayout header = screenHeader(title);
            back = iconButton(R.drawable.ic_back, R.string.back_to_playlists, accent, accentSurface,
                    48, 24, this::closePlaylist);
            add = iconButton(R.drawable.ic_add, R.string.create_playlist, accent, accentSurface,
                    48, 24, this::addOrEditTracks);
            reorder = iconButton(R.drawable.ic_drag_handle, R.string.reorder_tracks, accent, accentSurface,
                    48, 24, this::toggleReordering);
            reorder.setTooltipText(getString(R.string.reorder_tracks));
            addHeaderButton(header, back, Gravity.START);
            FrameLayout.LayoutParams reorderSize =
                    new FrameLayout.LayoutParams(dp(48), dp(48), Gravity.END | Gravity.CENTER_VERTICAL);
            reorderSize.setMarginEnd(dp(54));
            header.addView(reorder, reorderSize);
            addHeaderButton(header, add, Gravity.END);
            addView(header, new LinearLayout.LayoutParams(MATCH_PARENT, dp(54)));

            FrameLayout results = new FrameLayout(MainActivity.this);
            LinearLayout.LayoutParams resultsSize = new LinearLayout.LayoutParams(MATCH_PARENT, 0, 1);
            resultsSize.topMargin = dp(10);
            addView(results, resultsSize);
            list = new PlaylistReorderList(MainActivity.this, this::moveTrack);
            styleList(list);
            list.setOnItemClickListener((parent, row, position, id) -> {
                if (selectedId == null) {
                    restoreSelection(playlists.get(position).id());
                } else {
                    selectTrack(trackAdapter.visible, trackAdapter.getItem(position), selectedId);
                }
            });
            list.setOnItemLongClickListener((parent, row, position, id) -> {
                if (selectedId == null) {
                    managePlaylist(playlists.get(position));
                } else {
                    openTrackMenu(trackAdapter.getItem(position), selectedPlaylist());
                }
                return true;
            });
            results.addView(list, new FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT));

            empty = text(0, 15, muted, false);
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(dp(16), 0, dp(16), 0);
            results.addView(empty, new FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.CENTER));
            render();
        }

        void restoreSelection(String id) {
            finishReordering();
            selectedId = id;
            if (selectedPlaylist() == null) {
                selectedId = null;
            }
            render();
        }

        boolean closePlaylist() {
            if (selectedId == null) {
                return false;
            }
            restoreSelection(null);
            return true;
        }

        boolean goBack() {
            return finishReordering() || closePlaylist();
        }

        private Playlist selectedPlaylist() {
            return findPlaylist(selectedId);
        }

        private Playlist findPlaylist(String id) {
            for (Playlist playlist : playlists) {
                if (playlist.id().equals(id)) {
                    return playlist;
                }
            }
            return null;
        }

        void render() {
            Playlist playlist = selectedPlaylist();
            title.setText(playlist == null ? getString(R.string.playlists) : playlist.name());
            back.setVisibility(playlist == null ? View.INVISIBLE : View.VISIBLE);
            add.setContentDescription(getString(playlist == null ? R.string.create_playlist : R.string.edit_playlist_tracks));

            BaseAdapter activeAdapter = playlistAdapter;
            int emptyMessage = R.string.no_playlists;
            if (playlist != null) {
                List<Track> available = resolveTracks(playlist);
                if (!available.equals(trackAdapter.visible)) {
                    list.resetDrag();
                }
                trackAdapter.visible = available;
                activeAdapter = trackAdapter;
                emptyMessage = playlist.tracks().isEmpty() ? R.string.empty_playlist
                        : loaded ? R.string.unavailable_playlist : R.string.loading;
            }
            boolean canReorder = playlist != null && trackAdapter.getCount() > 1;
            if (!canReorder) {
                finishReordering();
            }
            reorder.setVisibility(canReorder ? View.VISIBLE : View.GONE);
            FrameLayout.LayoutParams titleSize = (FrameLayout.LayoutParams) title.getLayoutParams();
            int titleEnd = dp(canReorder ? 108 : 54);
            if (titleSize.getMarginEnd() != titleEnd) {
                titleSize.setMarginEnd(titleEnd);
                title.setLayoutParams(titleSize);
            }
            if (list.getAdapter() != activeAdapter) {
                list.setAdapter(activeAdapter);
            } else {
                activeAdapter.notifyDataSetChanged();
            }
            boolean hasRows = activeAdapter.getCount() > 0;
            list.setVisibility(hasRows ? View.VISIBLE : View.GONE);
            empty.setVisibility(hasRows ? View.GONE : View.VISIBLE);
            empty.setText(emptyMessage);
        }

        private void updateLibrary(List<Track> libraryTracks) {
            tracksByUri.clear();
            for (Track track : libraryTracks) {
                tracksByUri.put(track.libraryUri().toString(), track);
            }
            render();
        }

        private List<Track> resolveTracks(Playlist playlist) {
            List<Track> members = new ArrayList<>();
            for (String uri : playlist.tracks()) {
                Track track = tracksByUri.get(uri);
                if (track != null) {
                    members.add(track);
                }
            }
            return members;
        }

        private void addOrEditTracks() {
            Playlist playlist = selectedPlaylist();
            if (playlist == null) {
                editName(null);
            } else {
                editTracks(playlist);
            }
        }

        private void editName(Playlist playlist) {
            editName(playlist, null);
        }

        private void editName(Playlist playlist, Track firstTrack) {
            int title = playlist == null ? R.string.create_playlist : R.string.rename_playlist;
            MasheedsDialog dialog = new MasheedsDialog(MainActivity.this, getText(title));
            EditText name = dialog.addInput(R.string.playlist_name, playlist == null ? "" : playlist.name());
            Runnable save = () -> {
                String value = name.getText().toString().trim();
                if (value.isEmpty()) {
                    name.setError(getString(R.string.playlist_name_required));
                    return;
                }
                if (playlist == null) {
                    List<String> members = firstTrack == null ? Collections.emptyList()
                            : Collections.singletonList(firstTrack.libraryUri().toString());
                    Playlist created = new Playlist(UUID.randomUUID().toString(), value, members);
                    playlists.add(created);
                    if (firstTrack == null) {
                        selectedId = created.id();
                    }
                    savePlaylists();
                } else {
                    replacePlaylist(playlist, new Playlist(playlist.id(), value, playlist.tracks()));
                }
                dialog.dismiss();
            };
            name.setOnEditorActionListener((field, action, event) -> {
                if (action == EditorInfo.IME_ACTION_DONE) {
                    save.run();
                    return true;
                }
                return false;
            });
            dialog.addCancel();
            dialog.addAction(R.string.save, true, save);
            dialog.show();
        }

        void addTrack(Track track) {
            MasheedsDialog dialog = new MasheedsDialog(MainActivity.this, getText(R.string.add_to_playlist));
            String key = track.libraryUri().toString();
            List<Playlist> choices = new ArrayList<>(playlists);
            if (!choices.isEmpty()) {
                CharSequence[] names = new CharSequence[choices.size()];
                for (int index = 0; index < names.length; index++) {
                    Playlist playlist = choices.get(index);
                    names[index] = playlist.tracks().contains(key)
                            ? getString(R.string.playlist_track_added, playlist.name()) : playlist.name();
                }
                dialog.addOptions(names, index -> {
                    Playlist playlist = choices.get(index);
                    if (!playlist.tracks().contains(key)) {
                        List<String> members = new ArrayList<>(playlist.tracks());
                        members.add(key);
                        replacePlaylist(playlist, new Playlist(playlist.id(), playlist.name(), members));
                    }
                    dialog.dismiss();
                });
            }
            dialog.addMenuItem(R.string.create_playlist, () -> {
                dialog.dismiss();
                editName(null, track);
            });
            dialog.addCancel();
            dialog.show();
        }

        private void toggleReordering() {
            hideKeyboard();
            requestFocus();
            setReordering(!trackAdapter.reordering);
        }

        private boolean finishReordering() {
            if (!trackAdapter.reordering) {
                return false;
            }
            setReordering(false);
            return true;
        }

        private void setReordering(boolean active) {
            trackAdapter.reordering = active;
            list.resetDrag();
            reorder.setImageResource(active ? R.drawable.ic_done : R.drawable.ic_drag_handle);
            reorder.setColorFilter(active ? onAccent : accent);
            reorder.setBackground(ripple(active ? accent : accentSurface, 24));
            String label = getString(active ? R.string.done_reordering : R.string.reorder_tracks);
            reorder.setContentDescription(label);
            reorder.setTooltipText(label);
            trackAdapter.notifyDataSetChanged();
        }

        private boolean moveTrack(String trackId, int target) {
            Playlist playlist = selectedPlaylist();
            int from = trackIndex(trackAdapter.visible, trackId);
            if (!trackAdapter.reordering || playlist == null || from < 0 || from == target
                    || target < 0 || target >= trackAdapter.getCount()) {
                return false;
            }
            List<String> order = new ArrayList<>();
            for (Track track : trackAdapter.visible) {
                order.add(track.libraryUri().toString());
            }
            Set<String> available = new LinkedHashSet<>(order);
            order.add(target, order.remove(from));
            Iterator<String> moved = order.iterator();
            List<String> members = new ArrayList<>(playlist.tracks());
            members.replaceAll(id -> available.contains(id) ? moved.next() : id);

            int first = list.getFirstVisiblePosition();
            View firstRow = list.getChildAt(0);
            int offset = firstRow == null ? 0 : firstRow.getTop();
            replacePlaylist(playlist, new Playlist(playlist.id(), playlist.name(), members));
            list.setSelectionFromTop(first, offset);
            return true;
        }

        private boolean moveTrackBy(String trackId, int delta) {
            return moveTrack(trackId, trackIndex(trackAdapter.visible, trackId) + delta);
        }

        private void editTracks(Playlist playlist) {
            List<Track> available = tracks;
            MasheedsDialog dialog = new MasheedsDialog(MainActivity.this, getText(R.string.edit_playlist_tracks));
            if (available.isEmpty()) {
                dialog.addMessage(getText(R.string.playlist_needs_tracks));
                dialog.addAction(android.R.string.ok, true, dialog::dismiss);
                dialog.show();
                return;
            }
            CharSequence[] labels = new CharSequence[available.size()];
            boolean[] checked = new boolean[available.size()];
            Set<String> members = new LinkedHashSet<>(playlist.tracks());
            for (int index = 0; index < available.size(); index++) {
                Track track = available.get(index);
                labels[index] = track.displayTitle(MainActivity.this);
                checked[index] = members.contains(track.libraryUri().toString());
            }
            dialog.addChecklist(labels, checked);
            dialog.addCancel();
            dialog.addAction(R.string.save, true, () -> {
                for (int index = 0; index < available.size(); index++) {
                    String uri = available.get(index).libraryUri().toString();
                    if (checked[index]) {
                        members.add(uri);
                    } else {
                        members.remove(uri);
                    }
                }
                replacePlaylist(playlist, new Playlist(playlist.id(), playlist.name(), new ArrayList<>(members)));
                dialog.dismiss();
            });
            dialog.show();
        }

        private void confirmRemoveTrack(Playlist playlist, Track track) {
            MasheedsDialog dialog = new MasheedsDialog(MainActivity.this, getText(R.string.remove_from_playlist));
            dialog.addMessage(getString(R.string.remove_from_playlist_message,
                    track.displayTitle(MainActivity.this), playlist.name()));
            dialog.addCancel();
            dialog.addAction(R.string.remove, true, () -> {
                List<String> members = new ArrayList<>(playlist.tracks());
                members.removeIf(track.libraryUri().toString()::equals);
                replacePlaylist(playlist, new Playlist(playlist.id(), playlist.name(), members));
                dialog.dismiss();
            });
            dialog.show();
        }

        private void removeTrackFromAll(String trackId) {
            boolean changed = false;
            for (int index = 0; index < playlists.size(); index++) {
                Playlist playlist = playlists.get(index);
                List<String> members = new ArrayList<>(playlist.tracks());
                if (members.removeIf(trackId::equals)) {
                    playlists.set(index, new Playlist(playlist.id(), playlist.name(), members));
                    changed = true;
                }
            }
            if (changed) {
                PlaylistStore.save(MainActivity.this, playlists);
            }
        }

        private void managePlaylist(Playlist playlist) {
            MasheedsDialog dialog = new MasheedsDialog(MainActivity.this, playlist.name());
            String coverKey = CustomArtwork.playlistKey(playlist.id());
            dialog.addMenuItem(R.string.set_album_cover, () -> {
                dialog.dismiss();
                openCoverPicker(coverKey);
            });
            Button removeCover = dialog.addMenuItem(R.string.remove_playlist_cover, () -> {
                dialog.dismiss();
                updateCover(coverKey, null);
            });
            MainActivity.setEnabled(removeCover, CustomArtwork.hasCover(MainActivity.this, coverKey));
            dialog.addMenuItem(R.string.rename_playlist, () -> {
                dialog.dismiss();
                editName(playlist);
            });
            dialog.addMenuItem(R.string.delete_playlist, () -> {
                dialog.dismiss();
                confirmDeletePlaylist(playlist);
            });
            dialog.addCancel();
            dialog.show();
        }

        private void confirmDeletePlaylist(Playlist playlist) {
            MasheedsDialog dialog = new MasheedsDialog(MainActivity.this, getText(R.string.delete_playlist));
            dialog.addMessage(getString(R.string.delete_playlist_message, playlist.name()));
            dialog.addCancel();
            dialog.addAction(R.string.delete_playlist, true, () -> {
                playlists.remove(playlist);
                savePlaylists();
                updateCover(CustomArtwork.playlistKey(playlist.id()), null);
                withPlayback(service -> service.detachPlaylist(playlist.id()));
                dialog.dismiss();
            });
            dialog.show();
        }

        private void replacePlaylist(Playlist original, Playlist replacement) {
            playlists.set(playlists.indexOf(original), replacement);
            savePlaylists();
            withPlayback(service -> service.updatePlaylist(replacement.id(), resolveTracks(replacement)));
        }

        private void savePlaylists() {
            PlaylistStore.save(MainActivity.this, playlists);
            render();
        }
    }

    private final class PlaylistRow extends LinearLayout {
        private final ImageView icon;
        private final TextView name;
        private String coverKey;
        private List<Track> coverTracks = Collections.emptyList();
        private Future<?> artworkRequest;
        private int artworkVersion;
        private long artworkRevision;

        PlaylistRow() {
            super(MainActivity.this);
            setGravity(Gravity.CENTER_VERTICAL);
            setPadding(dp(8), dp(8), dp(10), dp(8));
            setMinimumHeight(dp(65));
            setBackground(ripple(Color.TRANSPARENT, 14));

            icon = artworkTile(accentSurface, 12);
            int thumbnailSize = dp(THUMBNAIL_DP);
            addView(icon, new LinearLayout.LayoutParams(thumbnailSize, thumbnailSize));
            displayArtwork(icon, null, R.drawable.ic_playlists, accent, 11);

            name = singleLineText(16, ink, true);
            LinearLayout.LayoutParams nameSize = new LinearLayout.LayoutParams(0, WRAP_CONTENT, 1);
            nameSize.setMarginStart(dp(12));
            addView(name, nameSize);
        }

        void bind(Playlist playlist, List<Track> members) {
            name.setText(playlist.name());
            String key = CustomArtwork.playlistKey(playlist.id());
            long revision = CustomArtwork.revision();
            if (key.equals(coverKey) && members.equals(coverTracks) && artworkRevision == revision) {
                return;
            }
            coverKey = key;
            coverTracks = members;
            artworkRevision = revision;
            if (artworkRequest != null) {
                artworkRequest.cancel(false);
            }
            int version = ++artworkVersion;
            displayArtwork(icon, null, R.drawable.ic_playlists, accent, 11);
            artworkRequest = ArtworkLoader.loadOverride(MainActivity.this, key, ArtworkLoader.THUMBNAIL_SIZE,
                    bitmap -> displayCoverOrTryTrack(bitmap, version, 0));
        }

        private void displayCoverOrTryTrack(Bitmap bitmap, int version, int nextTrack) {
            if (version != artworkVersion || isDestroyed()) {
                return;
            }
            artworkRequest = null;
            if (bitmap != null) {
                displayArtwork(icon, bitmap, R.drawable.ic_playlists, accent, 11);
            } else if (nextTrack < coverTracks.size()) {
                artworkRequest = ArtworkLoader.load(MainActivity.this, coverTracks.get(nextTrack),
                        ArtworkLoader.THUMBNAIL_SIZE, cover -> displayCoverOrTryTrack(cover, version, nextTrack + 1));
            }
        }
    }

    private final class TrackAdapter extends BaseAdapter {
        private List<Track> visible = Collections.emptyList();
        private boolean reordering;

        @Override public int getCount() { return visible.size(); }
        @Override public Track getItem(int position) { return visible.get(position); }
        @Override public long getItemId(int position) { return position; }

        @Override public View getView(int position, View recycled, ViewGroup parent) {
            TrackRow row = recycled == null ? new TrackRow() : (TrackRow) recycled;
            Track track = getItem(position);
            row.bind(track);
            row.bindReorderHandle(track, reordering, position, getCount());
            return row;
        }
    }

    private final class TrackRow extends LinearLayout {
        private final ImageView icon;
        private final TextView title;
        private final TextView artist;
        private final TextView duration;
        private ImageButton handle;
        private Track artworkTrack;
        private Bitmap artwork;
        private Future<?> artworkRequest;
        private int artworkVersion;
        private long artworkRevision;

        TrackRow() {
            super(MainActivity.this);
            setGravity(Gravity.CENTER_VERTICAL);
            setPadding(dp(8), dp(8), dp(10), dp(8));
            setMinimumHeight(dp(65));
            icon = artworkTile(accentSurface, 12);
            int thumbnailSize = dp(THUMBNAIL_DP);
            addView(icon, new LinearLayout.LayoutParams(thumbnailSize, thumbnailSize));
            LinearLayout labels = column();
            LinearLayout.LayoutParams labelSize = new LinearLayout.LayoutParams(0, WRAP_CONTENT, 1);
            labelSize.setMarginStart(dp(12));
            labelSize.setMarginEnd(dp(10));
            addView(labels, labelSize);
            title = singleLineText(16, ink, true);
            artist = singleLineText(13, muted, false);
            labels.addView(title);
            addWithMargin(labels, artist, MATCH_PARENT, WRAP_CONTENT, 3);
            duration = text(0, 13, muted, false);
            addView(duration);
        }

        void bind(Track track) {
            boolean selected = track.uri().equals(selectedTrack);
            setBackground(ripple(selected ? accentSurface : Color.TRANSPARENT, 14));
            setSelected(selected);
            long revision = CustomArtwork.revision();
            if (!track.equals(artworkTrack) || artworkRevision != revision) {
                artworkTrack = track;
                artworkRevision = revision;
                artwork = null;
                if (artworkRequest != null) {
                    artworkRequest.cancel(false);
                }
                int version = ++artworkVersion;
                artworkRequest = ArtworkLoader.load(MainActivity.this, track, ArtworkLoader.THUMBNAIL_SIZE, bitmap -> {
                    if (version == artworkVersion && !isDestroyed()) {
                        artwork = bitmap;
                        renderArtwork(track.uri().equals(selectedTrack));
                    }
                });
            }
            renderArtwork(selected);
            title.setText(track.displayTitle(MainActivity.this));
            artist.setText(track.artist().isEmpty() ? getString(R.string.audio_file) : track.artist());
            duration.setText(track.duration() > 0 ? formatTime(track.duration()) : "");
        }

        private void bindReorderHandle(Track track, boolean active, int position, int count) {
            if (!active) {
                if (handle != null) {
                    handle.setVisibility(View.GONE);
                }
                return;
            }
            if (handle == null) {
                handle = new ImageButton(MainActivity.this);
                handle.setImageResource(R.drawable.ic_drag_handle);
                handle.setColorFilter(accent);
                handle.setBackground(ripple(Color.TRANSPARENT, 24));
                handle.setPadding(dp(12), dp(12), dp(12), dp(12));
                handle.setScaleType(ImageView.ScaleType.FIT_CENTER);
                handle.setFocusable(false);
                handle.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
                addView(handle, new LinearLayout.LayoutParams(dp(48), dp(48)));
            }
            String trackId = track.libraryUri().toString();
            handle.setVisibility(View.VISIBLE);
            handle.setContentDescription(getString(R.string.reorder_track, track.displayTitle(MainActivity.this)));
            handle.setOnLongClickListener(view -> playlistPage.list.beginDrag(this, view, trackId));
            handle.setAccessibilityDelegate(new View.AccessibilityDelegate() {
                @Override public void onInitializeAccessibilityNodeInfo(View host, AccessibilityNodeInfo info) {
                    super.onInitializeAccessibilityNodeInfo(host, info);
                    info.removeAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_LONG_CLICK);
                    if (position > 0) {
                        info.addAction(new AccessibilityNodeInfo.AccessibilityAction(
                                R.id.move_track_up, getString(R.string.move_track_up)));
                    }
                    if (position + 1 < count) {
                        info.addAction(new AccessibilityNodeInfo.AccessibilityAction(
                                R.id.move_track_down, getString(R.string.move_track_down)));
                    }
                }

                @Override public boolean performAccessibilityAction(View host, int action, Bundle args) {
                    if (action == R.id.move_track_up) {
                        return playlistPage.moveTrackBy(trackId, -1);
                    }
                    if (action == R.id.move_track_down) {
                        return playlistPage.moveTrackBy(trackId, 1);
                    }
                    return super.performAccessibilityAction(host, action, args);
                }
            });
        }

        private void renderArtwork(boolean selected) {
            icon.setBackground(round(selected ? accent : accentSurface, 12));
            displayArtwork(icon, artwork, selected ? R.drawable.ic_play : R.drawable.ic_note,
                    selected ? onAccent : accent, 11);
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

    private ImageView artworkTile(int fill, int radius) {
        ImageView tile = image(0);
        tile.setBackground(round(fill, radius));
        tile.setClipToOutline(true);
        tile.setScaleType(ImageView.ScaleType.CENTER_CROP);
        return tile;
    }

    private void displayArtwork(ImageView tile, Bitmap artwork, int fallback, Integer tint, int padding) {
        int inset = artwork == null ? dp(padding) : 0;
        tile.setPadding(inset, inset, inset, inset);
        tile.clearColorFilter();
        if (artwork != null) {
            tile.setImageBitmap(artwork);
        } else {
            tile.setImageResource(fallback);
            if (tint != null) {
                tile.setColorFilter(tint);
            }
        }
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
        return new RippleDrawable(ColorStateList.valueOf(rippleColor), round(color, radius), round(Color.WHITE, radius));
    }

    private void addWithMargin(LinearLayout parent, View child, int width, int height, int top) {
        LinearLayout.LayoutParams size = new LinearLayout.LayoutParams(width, height);
        size.topMargin = dp(top);
        parent.addView(child, size);
    }
}
