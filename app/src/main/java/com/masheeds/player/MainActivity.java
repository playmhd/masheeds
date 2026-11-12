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
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.MediaStore;
import android.provider.Settings;
import android.text.Editable;
import android.text.InputFilter;
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
import android.widget.HorizontalScrollView;
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

import java.io.IOException;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
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
    private static final int PICK_PRIVATE_MUSIC = 40;
    private static final int PICK_DISCOVER_SUBMISSION = 41;
    private static final int AUDIO_ACCESS = 2;
    private static final int PICK_COVER = 3;

    private enum Screen { LIBRARY, PLAYLISTS, DISCOVER, PROFILE, SETTINGS, RECENTLY_DELETED, PLAYER }
    private enum Load { IDLE, LOADING, READY, ERROR }

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
            playback.setShuffle(shuffleEnabled);
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
    private boolean shuffleEnabled;
    private Locale appLocale;
    private boolean darkMode;
    private boolean resumed;
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
    private String pendingUploadOwner;
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
    private ImageButton playerArtworkAction;
    private TextView playerTitle;
    private TextView playerArtist;
    private TextView elapsed;
    private TextView totalTime;
    private SeekBar seek;
    private ImageButton playerToggle;
    private ImageButton playerNext;
    private ImageButton playerPrevious;
    private ImageButton playerShuffle;
    private ImageButton playerRepeat;
    private ImageButton playerTimer;
    private TextView timerStatus;
    private Button cancelTimer;
    private BaseAdapter timerChoices;
    private DiscoverPage discoverScreen;
    private ScrollView settingsScreen;
    private ProfilePage profileScreen;
    private RecentlyDeletedPage recentlyDeletedPage;
    private TextView audioAccessSummary;
    private TextView accountStatus;
    private Button accountAction;

    @Override protected void attachBaseContext(Context base) {
        super.attachBaseContext(AppPreferences.configuredContext(base));
    }

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        repeatMode = RepeatMode.load(this);
        shuffleEnabled = AppPreferences.shuffleEnabled(this);
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
        renderShuffle();
        showScreen(Screen.LIBRARY);
        setContentView(root);
        if (state != null) {
            pendingCoverKey = state.getString("pendingCover");
            pendingUploadOwner = state.getString("pendingUploadOwner");
            profileScreen.restoreSubmissionPicker(state);
            search.setText(state.getString("query", ""));
            String albumName = state.getString("discoverAlbum");
            if (albumName != null) {
                discoverScreen.selectedAlbum = new DiscoverPage.AlbumKey(
                        albumName, state.getString("discoverAlbumArtist", ""));
                discoverScreen.section = DiscoverPage.Section.ALBUM;
            } else if (state.getBoolean("discoverAllTracks")) {
                discoverScreen.section = DiscoverPage.Section.ALL;
            }
            discoverScreen.search.setText(state.getString("discoverQuery", ""));
            String savedScreen = state.getString("screen");
            String savedTab = state.getString("tab", savedScreen);
            playlistPage.restoreSelection(state.getString("playlist"));
            for (Screen destination : Screen.values()) {
                if (destination != Screen.PLAYER && destination.name().equals(savedTab)) {
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
        resumed = true;
        renderAudioAccess();
        if (screen == Screen.DISCOVER) {
            discoverScreen.open();
        } else if (screen == Screen.SETTINGS || screen == Screen.PROFILE) {
            discoverScreen.refreshAccount();
        }
        if (screen == Screen.RECENTLY_DELETED) {
            recentlyDeletedPage.refresh();
        }
        watchDeviceLibrary();
        refreshLibrary();
    }

    @Override public void onPause() {
        resumed = false;
        discoverScreen.cancelCatalogRefresh();
        super.onPause();
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
        discoverScreen.cancelSignIn();
        profileScreen.cancelAvatarRequest();
        handler.removeCallbacksAndMessages(null);
        scanner.shutdownNow();
        super.onDestroy();
    }

    @Override public void onSaveInstanceState(Bundle state) {
        state.putString("pendingCover", pendingCoverKey);
        state.putString("pendingUploadOwner", pendingUploadOwner);
        profileScreen.saveSubmissionPicker(state);
        state.putString("query", search.getText().toString());
        state.putBoolean("discoverAllTracks", discoverScreen.section == DiscoverPage.Section.ALL);
        if (discoverScreen.selectedAlbum != null) {
            state.putString("discoverAlbum", discoverScreen.selectedAlbum.name());
            state.putString("discoverAlbumArtist", discoverScreen.selectedAlbum.artist());
        }
        state.putString("discoverQuery", discoverScreen.search.getText().toString());
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
        discoverScreen = new DiscoverPage();
        settingsScreen = buildSettingsScreen();
        profileScreen = new ProfilePage();
        recentlyDeletedPage = new RecentlyDeletedPage();
        pages.addView(library, new FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT));
        pages.addView(playlistPage, new FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT));
        pages.addView(discoverScreen, new FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT));
        pages.addView(settingsScreen, new FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT));
        pages.addView(profileScreen, new FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT));
        pages.addView(recentlyDeletedPage, new FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT));

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
        addNavigationButton(Screen.PROFILE, R.string.profile, R.drawable.ic_profile);
        addNavigationButton(Screen.SETTINGS, R.string.settings, R.drawable.ic_settings);
        shell.addView(bottomNavigation, new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT));
        return shell;
    }

    private void addNavigationButton(Screen destination, int label, int icon) {
        Button navigation = button(label, muted, Color.TRANSPARENT);
        navigation.setCompoundDrawablesRelative(null, smallIcon(icon, muted), null, null);
        navigation.setCompoundDrawablePadding(dp(4));
        navigation.setTextSize(12);
        navigation.setPadding(0, dp(6), 0, dp(6));
        navigation.setMinimumHeight(dp(56));
        navigation.setOnClickListener(view -> {
            if (navigationTab(screen) != destination && AppPreferences.navigationHapticsEnabled(this)) {
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
        playerArtworkAction = iconButton(R.drawable.ic_add_to_playlist, R.string.add_to_playlist,
                accent, accentSurface, 48, 22, this::onPlayerArtworkAction);
        FrameLayout.LayoutParams actionPosition =
                new FrameLayout.LayoutParams(dp(48), dp(48), Gravity.END | Gravity.BOTTOM);
        actionPosition.setMarginEnd(dp(10));
        actionPosition.bottomMargin = dp(10);
        coverPanel.addView(playerArtworkAction, actionPosition);
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
        boolean compactControls = width < 348;
        int skipSize = compactControls ? 48 : 54;
        int playSize = compactControls ? 64 : 72;
        int gap = dp(compactControls ? 4 : 8);
        playerShuffle = iconButton(R.drawable.ic_shuffle, R.string.shuffle_off, muted, surface,
                48, 24, this::toggleShuffle);
        playerPrevious = iconButton(R.drawable.ic_previous, R.string.previous_track, accent, accentSurface, skipSize, 26,
                () -> withPlayback(PlaybackService::previous));
        playerToggle = iconButton(R.drawable.ic_play, R.string.play, onAccent, accent, playSize, 34,
                () -> withPlayback(PlaybackService::toggle));
        playerNext = iconButton(R.drawable.ic_next, R.string.next_track, accent, accentSurface, skipSize, 26,
                () -> withPlayback(PlaybackService::next));
        playerRepeat = iconButton(R.drawable.ic_repeat, repeatMode.label, muted, surface,
                48, 24, this::cycleRepeatMode);
        LinearLayout.LayoutParams shuffleSize = new LinearLayout.LayoutParams(dp(48), dp(48));
        shuffleSize.setMarginEnd(gap);
        buttons.addView(playerShuffle, shuffleSize);
        buttons.addView(playerPrevious, new LinearLayout.LayoutParams(dp(skipSize), dp(skipSize)));
        LinearLayout.LayoutParams toggleSize = new LinearLayout.LayoutParams(dp(playSize), dp(playSize));
        toggleSize.setMargins(gap, 0, gap, 0);
        buttons.addView(playerToggle, toggleSize);
        buttons.addView(playerNext, new LinearLayout.LayoutParams(dp(skipSize), dp(skipSize)));
        LinearLayout.LayoutParams repeatSize = new LinearLayout.LayoutParams(dp(48), dp(48));
        repeatSize.setMarginStart(gap);
        buttons.addView(playerRepeat, repeatSize);
        addWithMargin(page, buttons, MATCH_PARENT, WRAP_CONTENT, 22);
        return screen;
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
        Button recentlyDeleted = button(R.string.recently_deleted, accent, accentSurface);
        recentlyDeleted.setOnClickListener(view -> showScreen(Screen.RECENTLY_DELETED));
        addWithMargin(accessCard, recentlyDeleted, WRAP_CONTENT, dp(48), 8);

        TextView accountSection = text(R.string.account, 18, ink, true);
        markHeading(accountSection);
        addWithMargin(page, accountSection, MATCH_PARENT, WRAP_CONTENT, 24);
        LinearLayout accountCard = column();
        accountCard.setPadding(dp(14), dp(14), dp(14), dp(14));
        accountCard.setBackground(round(surface, 16));
        addWithMargin(page, accountCard, MATCH_PARENT, WRAP_CONTENT, 10);
        accountStatus = text(R.string.account_signed_out, 15, muted, false);
        accountCard.addView(accountStatus);
        accountAction = button(R.string.google_sign_in, accent, accentSurface);
        accountAction.setOnClickListener(view -> discoverScreen.toggleAccount());
        addWithMargin(accountCard, accountAction, WRAP_CONTENT, dp(48), 8);

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

    private void toggleShuffle() {
        shuffleEnabled = !shuffleEnabled;
        AppPreferences.setShuffleEnabled(this, shuffleEnabled);
        renderShuffle();
        withPlayback(service -> service.setShuffle(shuffleEnabled));
    }

    private void renderShuffle() {
        playerShuffle.setSelected(shuffleEnabled);
        playerShuffle.setColorFilter(shuffleEnabled ? onAccent : muted);
        playerShuffle.setBackground(ripple(shuffleEnabled ? accent : surface, 24));
        playerShuffle.setContentDescription(getString(shuffleEnabled ? R.string.shuffle_on : R.string.shuffle_off));
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
        if (requestCode == PICK_DISCOVER_SUBMISSION) {
            profileScreen.onSubmissionFilePicked(resultCode == RESULT_OK && data != null ? data.getData() : null);
            return;
        }
        if (requestCode == PICK_PRIVATE_MUSIC) {
            String owner = pendingUploadOwner;
            pendingUploadOwner = null;
            if (owner != null && resultCode == RESULT_OK && data != null && data.getData() != null) {
                discoverScreen.upload(data.getData(), owner);
            }
            return;
        }
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
            RecentlyDeleted.record(this, Collections.singletonList(track), null);
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
            profileScreen.bindPublishedRows();
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
        Uri uri = current == null ? null : current.libraryUri();
        if (!Objects.equals(selectedTrack, uri)) {
            selectedTrack = uri;
            adapter.notifyDataSetChanged();
            playlistPage.trackAdapter.notifyDataSetChanged();
            discoverScreen.trackAdapter.notifyDataSetChanged();
            discoverScreen.bindQuickPickRows();
            profileScreen.bindPublishedRows();
        }
        miniPlayer.setVisibility(current == null || keyboardOpen ? View.GONE : View.VISIBLE);
        Bitmap artwork = current == null ? null : playback.currentArtwork();
        displayArtwork(miniArtwork, artwork, R.drawable.ic_note, PLAYER_MUTED, 10);
        displayArtwork(playerArtwork, artwork, R.drawable.ic_record, null, 8);
        renderPlayerArtworkAction(current);
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
        String subtitle = playback.isBuffering() ? getString(R.string.stream_buffering) : artist;
        miniTitle.setText(title);
        miniArtist.setText(subtitle);
        miniDetails.setContentDescription(getString(R.string.show_now_playing, title, subtitle));
        playerTitle.setText(title);
        playerArtist.setText(subtitle);
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

    private void onPlayerArtworkAction() {
        Track track = playback == null ? null : playback.currentTrack();
        if (track != null) {
            if (track.isOnline()) {
                discoverScreen.toggleLike(track);
            } else {
                playlistPage.addTrack(track);
            }
        }
    }

    private void renderPlayerArtworkAction(Track track) {
        boolean online = track != null && track.isOnline();
        if (track != null && track.isPrivateOnline()) {
            playerArtworkAction.setVisibility(View.GONE);
            return;
        }
        playerArtworkAction.setVisibility(!online || discoverScreen.canLike() ? View.VISIBLE : View.GONE);
        if (online) {
            discoverScreen.bindLikeButton(playerArtworkAction, track);
        } else {
            playerArtworkAction.setImageResource(R.drawable.ic_add_to_playlist);
            playerArtworkAction.setContentDescription(getString(R.string.add_to_playlist));
            setEnabled(playerArtworkAction, true);
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
        } else if (screen == Screen.RECENTLY_DELETED) {
            showScreen(Screen.SETTINGS);
        } else if (screen == Screen.DISCOVER && discoverScreen.goBack()) {
            return;
        } else if (screen != Screen.PLAYLISTS || !playlistPage.goBack()) {
            showScreen(Screen.LIBRARY);
        }
    }

    private void showScreen(Screen target) {
        if (target != Screen.PLAYLISTS) {
            playlistPage.finishReordering();
        }
        screen = target;
        if (target != Screen.DISCOVER) {
            discoverScreen.cancelCatalogRefresh();
        }
        playerScreen.setVisibility(target == Screen.PLAYER ? View.VISIBLE : View.GONE);
        tabShell.setVisibility(target == Screen.PLAYER ? View.GONE : View.VISIBLE);
        if (target != Screen.PLAYER) {
            tab = target;
            library.setVisibility(target == Screen.LIBRARY ? View.VISIBLE : View.GONE);
            playlistPage.setVisibility(target == Screen.PLAYLISTS ? View.VISIBLE : View.GONE);
            discoverScreen.setVisibility(target == Screen.DISCOVER ? View.VISIBLE : View.GONE);
            settingsScreen.setVisibility(target == Screen.SETTINGS ? View.VISIBLE : View.GONE);
            profileScreen.setVisibility(target == Screen.PROFILE ? View.VISIBLE : View.GONE);
            recentlyDeletedPage.setVisibility(target == Screen.RECENTLY_DELETED ? View.VISIBLE : View.GONE);
            if (target == Screen.RECENTLY_DELETED) {
                recentlyDeletedPage.refresh();
            }
            if (target == Screen.DISCOVER) {
                discoverScreen.open();
            } else if (target == Screen.SETTINGS || target == Screen.PROFILE) {
                discoverScreen.refreshAccount();
            }
            Screen selectedTab = navigationTab(target);
            navigationButtons.forEach((destination, button) -> {
                boolean selected = destination == selectedTab;
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

    private static Screen navigationTab(Screen page) {
        return page == Screen.RECENTLY_DELETED ? Screen.SETTINGS : page;
    }

    private void hideKeyboard() {
        getSystemService(InputMethodManager.class).hideSoftInputFromWindow(getWindow().getDecorView().getWindowToken(), 0);
        if (discoverScreen.search.hasFocus()) {
            discoverScreen.requestFocus();
        } else {
            search.clearFocus();
            library.requestFocus();
        }
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

    private final class DiscoverPage extends LinearLayout {
        private static final int QUICK_PICK_ROWS_PER_COLUMN = 4;
        private static final int QUICK_PICK_LIMIT = 3 * QUICK_PICK_ROWS_PER_COLUMN;
        private enum Section { BROWSE, ALL, ALBUM, LIKED, UPLOADS }

        private record AlbumKey(String name, String artist) {
            static AlbumKey of(Track track) { return new AlbumKey(track.album(), track.artist().trim()); }
        }

        private final DiscoverBackend backend = DiscoverBackend.get(MainActivity.this);
        private final Runnable refreshStaleCatalog = () -> loadCatalog(false);
        private final TextView headerTitle;
        private final ImageButton browseBack;
        private final ImageButton refreshCatalog;
        private final Button browseTab;
        private final Button likedTab;
        private final Button uploadsTab;
        private final ImageButton uploadButton;
        private final TextView uploadStatus;
        private final ProgressBar uploadProgress;
        private final EditText search;
        private final Button catalogRetry;
        private final LinearLayout quickPicksHeader;
        private final ScrollView browseResults;
        private final LinearLayout quickPickColumns;
        private final LinearLayout albumsSection;
        private final LinearLayout albumCards;
        private final List<TrackRow> quickPickRows = new ArrayList<>();
        private final ListView list;
        private final TextView status;
        private final Button statusAction;
        private final BaseAdapter trackAdapter = new BaseAdapter() {
            @Override public int getCount() { return visible.size(); }
            @Override public Track getItem(int position) { return visible.get(position); }
            @Override public long getItemId(int position) { return position; }

            @Override public View getView(int position, View recycled, ViewGroup parent) {
                TrackRow row = recycled == null ? new TrackRow() : (TrackRow) recycled;
                Track track = getItem(position);
                row.bind(track);
                if (!track.isPrivateOnline()) {
                    bindRowLike(row, track);
                } else if (row.likeButton != null) {
                    row.likeButton.setVisibility(View.GONE);
                }
                return row;
            }
        };

        private DiscoverBackend.CatalogSnapshot catalog;
        private List<Track> uploads = Collections.emptyList();
        private List<Track> visible = Collections.emptyList();
        private List<Track> quickPickTracks = Collections.emptyList();
        private Map<AlbumKey, Track> albumCovers = Collections.emptyMap();
        private AlbumKey selectedAlbum;
        private Set<String> likes = Collections.emptySet();
        private final Set<String> pendingLikes = new LinkedHashSet<>();
        private DiscoverBackend.Account account;
        private CancellationSignal signInRequest;
        private int accountGeneration;
        private boolean accountBusy;
        private int accountError;
        private Section section = Section.BROWSE;
        private boolean uploading;
        private int uploadedPercent = -1;
        private Load catalogState = Load.IDLE;
        private Load likesState = Load.IDLE;
        private Load uploadsState = Load.IDLE;

        DiscoverPage() {
            super(MainActivity.this);
            setOrientation(VERTICAL);
            setPadding(dp(20), dp(6), dp(20), dp(10));
            setFocusableInTouchMode(true);
            headerTitle = singleLineText(16, ink, true);
            headerTitle.setText(R.string.discover);
            FrameLayout header = screenHeader(headerTitle);
            browseBack = iconButton(R.drawable.ic_back, R.string.discover_back_to_browse,
                    accent, accentSurface, 48, 24, this::goBack);
            browseBack.setVisibility(View.GONE);
            addHeaderButton(header, browseBack, Gravity.START);
            refreshCatalog = iconButton(R.drawable.ic_refresh, R.string.discover_refresh,
                    accent, accentSurface, 48, 24, () -> loadCatalog(true));
            addHeaderButton(header, refreshCatalog, Gravity.END);
            uploadButton = iconButton(R.drawable.ic_add, R.string.upload_track, accent, accentSurface,
                    48, 24, this::pickUpload);
            uploadButton.setVisibility(View.GONE);
            addHeaderButton(header, uploadButton, Gravity.END);
            addView(header, new LinearLayout.LayoutParams(MATCH_PARENT, dp(54)));

            LinearLayout tabs = row();
            browseTab = button(R.string.discover_browse, accent, accentSurface);
            likedTab = button(R.string.discover_liked, accent, surface);
            uploadsTab = button(R.string.discover_uploads, accent, surface);
            uploadsTab.setVisibility(BuildConfig.PRIVATE_UPLOADS_ENABLED ? View.VISIBLE : View.GONE);
            for (Button tab : new Button[]{browseTab, likedTab, uploadsTab}) {
                tab.setPaddingRelative(dp(4), 0, dp(4), 0);
            }
            browseTab.setOnClickListener(view -> selectView(Section.BROWSE));
            likedTab.setOnClickListener(view -> selectView(Section.LIKED));
            uploadsTab.setOnClickListener(view -> selectView(Section.UPLOADS));
            tabs.addView(browseTab, new LinearLayout.LayoutParams(0, dp(48), 1));
            LinearLayout.LayoutParams likedSize = new LinearLayout.LayoutParams(0, dp(48), 1);
            likedSize.setMarginStart(dp(8));
            tabs.addView(likedTab, likedSize);
            LinearLayout.LayoutParams uploadsSize = new LinearLayout.LayoutParams(0, dp(48), 1);
            uploadsSize.setMarginStart(dp(8));
            tabs.addView(uploadsTab, uploadsSize);
            addWithMargin(this, tabs, MATCH_PARENT, WRAP_CONTENT, 8);

            search = new EditText(MainActivity.this);
            search.setSingleLine(true);
            search.setHint(R.string.discover_search);
            search.setTextColor(ink);
            search.setHintTextColor(muted);
            search.setTextSize(15);
            search.setPaddingRelative(dp(14), 0, dp(14), 0);
            search.setBackground(round(surface, 14));
            search.setInputType(InputType.TYPE_CLASS_TEXT);
            search.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
            search.setOnEditorActionListener((view, action, event) -> {
                if (action == EditorInfo.IME_ACTION_SEARCH) {
                    hideKeyboard();
                    return true;
                }
                return false;
            });
            search.addTextChangedListener(new TextWatcher() {
                @Override public void beforeTextChanged(CharSequence value, int start, int count, int after) {}
                @Override public void onTextChanged(CharSequence value, int start, int before, int count) {
                    render();
                    list.setSelection(0);
                }
                @Override public void afterTextChanged(Editable value) {}
            });
            addWithMargin(this, search, MATCH_PARENT, dp(48), 10);

            catalogRetry = button(R.string.discover_refresh_failed, accent, accentSurface);
            catalogRetry.setOnClickListener(view -> loadCatalog(true));
            addWithMargin(this, catalogRetry, MATCH_PARENT, dp(48), 8);

            uploadStatus = text(R.string.upload_preparing, 14, muted, false);
            uploadStatus.setVisibility(View.GONE);
            addWithMargin(this, uploadStatus, MATCH_PARENT, WRAP_CONTENT, 8);
            uploadProgress = new ProgressBar(MainActivity.this, null, android.R.attr.progressBarStyleHorizontal);
            uploadProgress.setMax(100);
            uploadProgress.setProgressTintList(ColorStateList.valueOf(accent));
            uploadProgress.setVisibility(View.GONE);
            addWithMargin(this, uploadProgress, MATCH_PARENT, dp(4), 4);

            quickPicksHeader = row();
            TextView quickPicksTitle = text(R.string.discover_quick_picks, 24, ink, true);
            markHeading(quickPicksTitle);
            quickPicksHeader.addView(quickPicksTitle, new LinearLayout.LayoutParams(0, WRAP_CONTENT, 1));
            Button viewAll = button(R.string.discover_view_all, accent, accentSurface);
            viewAll.setOnClickListener(view -> selectView(Section.ALL));
            LinearLayout.LayoutParams viewAllSize = new LinearLayout.LayoutParams(WRAP_CONTENT, dp(48));
            viewAllSize.setMarginStart(dp(8));
            quickPicksHeader.addView(viewAll, viewAllSize);
            addWithMargin(this, quickPicksHeader, MATCH_PARENT, WRAP_CONTENT, 10);

            FrameLayout results = new FrameLayout(MainActivity.this);
            LinearLayout.LayoutParams resultsSize = new LinearLayout.LayoutParams(MATCH_PARENT, 0, 1);
            resultsSize.topMargin = dp(8);
            addView(results, resultsSize);
            list = styledList();
            list.setAdapter(trackAdapter);
            list.setOnItemClickListener((parent, view, position, id) ->
                    selectTrack(visible, visible.get(position), null));
            list.setOnItemLongClickListener((parent, view, position, id) -> {
                Track track = visible.get(position);
                if (!track.isPrivateOnline()) {
                    return false;
                }
                confirmDeleteUpload(track);
                return true;
            });
            results.addView(list, new FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT));

            quickPickColumns = row();
            quickPickColumns.setGravity(Gravity.TOP);
            HorizontalScrollView carousel = new HorizontalScrollView(MainActivity.this) {
                @Override protected void onMeasure(int widthSpec, int heightSpec) {
                    int viewport = View.MeasureSpec.getSize(widthSpec);
                    int count = quickPickColumns.getChildCount();
                    int columnWidth = count > 1 ? viewport * 5 / 6 : viewport;
                    for (int index = 0; index < count; index++) {
                        quickPickColumns.getChildAt(index).getLayoutParams().width = columnWidth;
                    }
                    super.onMeasure(widthSpec, heightSpec);
                }
            };
            carousel.setHorizontalScrollBarEnabled(false);
            carousel.addView(quickPickColumns, new FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT));
            LinearLayout browseContent = column();
            browseContent.addView(carousel, new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT));
            albumsSection = column();
            TextView albumsTitle = text(R.string.discover_albums, 24, ink, true);
            markHeading(albumsTitle);
            albumsSection.addView(albumsTitle);
            albumCards = row();
            albumCards.setGravity(Gravity.TOP);
            HorizontalScrollView albumScroller = new HorizontalScrollView(MainActivity.this);
            albumScroller.setHorizontalScrollBarEnabled(false);
            albumScroller.addView(albumCards, new FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT));
            addWithMargin(albumsSection, albumScroller, MATCH_PARENT, WRAP_CONTENT, 12);
            addWithMargin(browseContent, albumsSection, MATCH_PARENT, WRAP_CONTENT, 20);
            browseResults = new ScrollView(MainActivity.this);
            browseResults.setPadding(0, 0, 0, dp(8));
            browseResults.addView(browseContent, new FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT));
            results.addView(browseResults, new FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT));

            LinearLayout empty = column();
            empty.setGravity(Gravity.CENTER);
            status = text(0, 15, muted, false);
            status.setGravity(Gravity.CENTER);
            empty.addView(status, new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT));
            statusAction = button(R.string.retry, accent, accentSurface);
            addWithMargin(empty, statusAction, WRAP_CONTENT, dp(48), 10);
            results.addView(empty, new FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.CENTER));
        }

        void open() {
            if (section != Section.UPLOADS) {
                loadCatalog(false);
            }
            refreshAccount();
            render();
        }

        private boolean goBack() {
            if (section != Section.ALL && section != Section.ALBUM) {
                return false;
            }
            selectView(Section.BROWSE);
            return true;
        }

        private void selectView(Section next) {
            if (section != next) {
                hideKeyboard();
                boolean clearAlbumSearch = section == Section.ALBUM && next == Section.BROWSE;
                section = next;
                if (next != Section.ALBUM) {
                    selectedAlbum = null;
                }
                if (clearAlbumSearch && search.length() > 0) {
                    search.setText("");
                }
                if (section == Section.UPLOADS) {
                    cancelCatalogRefresh();
                    loadUploads();
                } else {
                    loadCatalog(false);
                }
                render();
                list.setSelection(0);
            }
        }

        private void loadCatalog(boolean forceRefresh) {
            if (!DiscoverBackend.configured() || catalogState == Load.LOADING) {
                return;
            }
            if (!forceRefresh && catalog != null && catalog.fresh()) {
                scheduleCatalogRefresh();
                return;
            }
            cancelCatalogRefresh();
            catalogState = Load.LOADING;
            render();
            backend.catalog(forceRefresh, saved -> {
                if (!isDestroyed()) {
                    catalog = saved;
                    render();
                }
            }, result -> {
                if (isDestroyed()) {
                    return;
                }
                catalogState = result.success() ? Load.READY : Load.ERROR;
                if (result.success()) {
                    catalog = result.value();
                }
                scheduleCatalogRefresh();
                render();
            });
        }

        private void scheduleCatalogRefresh() {
            cancelCatalogRefresh();
            if (resumed && screen == Screen.DISCOVER && section != Section.UPLOADS && catalog != null) {
                if (catalogState == Load.READY) {
                    handler.postDelayed(refreshStaleCatalog, catalog.millisUntilStale());
                } else if (catalogState == Load.ERROR && catalog.usable()) {
                    handler.postDelayed(refreshStaleCatalog, catalog.millisUntilExpiry());
                }
            }
        }

        private void cancelCatalogRefresh() {
            handler.removeCallbacks(refreshStaleCatalog);
        }

        void refreshAccount() {
            if (!DiscoverBackend.signInConfigured() || accountBusy) {
                render();
                return;
            }
            accountBusy = true;
            accountError = 0;
            render();
            backend.account(this::finishAccount);
        }

        void toggleAccount() {
            if (accountBusy || uploading || profileScreen.submitting || !DiscoverBackend.signInConfigured()) {
                return;
            }
            if (account == null) {
                signIn();
                return;
            }
            accountBusy = true;
            changeAccount(null);
            render();
            backend.signOut(result -> {
                if (isDestroyed()) {
                    return;
                }
                GoogleSignIn.clear(MainActivity.this, handler::post);
                accountBusy = false;
                accountError = result.success() ? 0 : R.string.account_error;
                render();
            });
        }

        private void signIn() {
            if (accountBusy || !DiscoverBackend.signInConfigured()) {
                return;
            }
            hideKeyboard();
            accountBusy = true;
            accountError = 0;
            render();
            signInRequest = new CancellationSignal();
            try {
                GoogleSignIn.request(MainActivity.this, signInRequest, handler::post, result -> {
                    signInRequest = null;
                    if (isDestroyed()) {
                        return;
                    }
                    GoogleSignIn.Token token = result.token();
                    if (token != null) {
                        backend.signIn(token.idToken(), token.nonce(), this::finishAccount);
                    } else {
                        accountBusy = false;
                        accountError = result.cancelled() ? 0
                                : result.noCredential() ? R.string.google_account_needed : R.string.account_error;
                        render();
                    }
                });
            } catch (RuntimeException error) {
                signInRequest = null;
                accountBusy = false;
                accountError = R.string.account_error;
                render();
            }
        }

        void cancelSignIn() {
            if (signInRequest != null) {
                signInRequest.cancel();
            }
        }

        private void finishAccount(DiscoverBackend.Result<DiscoverBackend.Account> result) {
            if (isDestroyed()) {
                return;
            }
            accountBusy = false;
            accountError = result.success() ? 0 : R.string.account_error;
            if (result.success()) {
                changeAccount(result.value());
                loadLikes();
                if (section == Section.UPLOADS) {
                    loadUploads();
                }
            }
            if (screen == Screen.PROFILE && account != null) {
                profileScreen.loadPublishedTracks();
            }
            render();
        }

        private void changeAccount(DiscoverBackend.Account next) {
            String currentId = account == null ? null : account.id();
            String nextId = next == null ? null : next.id();
            if (!Objects.equals(currentId, nextId)) {
                if (currentId != null) {
                    withPlayback(PlaybackService::clearPrivatePlayback);
                }
                accountGeneration++;
                likes = Collections.emptySet();
                uploads = Collections.emptyList();
                pendingLikes.clear();
                likesState = Load.IDLE;
                uploadsState = Load.IDLE;
                profileScreen.clearPublishedTracks();
            }
            account = next;
        }

        private void loadUploads() {
            if (account == null || uploadsState == Load.LOADING) {
                return;
            }
            uploadsState = Load.LOADING;
            int generation = accountGeneration;
            backend.privateTracks(account.id(), result -> {
                if (isDestroyed() || generation != accountGeneration) {
                    return;
                }
                uploadsState = result.success() ? Load.READY : Load.ERROR;
                if (result.success()) {
                    uploads = result.value();
                }
                render();
            });
        }

        private void pickUpload() {
            if (account == null || uploading || pendingUploadOwner != null) {
                return;
            }
            hideKeyboard();
            Intent picker = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                    .setType("audio/*")
                    .putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"audio/mpeg", "audio/mp4", "audio/x-m4a"});
            try {
                pendingUploadOwner = account.id();
                startActivityForResult(picker, PICK_PRIVATE_MUSIC);
            } catch (ActivityNotFoundException error) {
                pendingUploadOwner = null;
                Toast.makeText(MainActivity.this, R.string.picker_error, Toast.LENGTH_SHORT).show();
            }
        }

        void upload(Uri document, String owner) {
            if (uploading || account != null && !account.id().equals(owner)) {
                return;
            }
            section = Section.UPLOADS;
            uploading = true;
            uploadedPercent = -1;
            render();
            PrivateMusicTransfer.upload(MainActivity.this, document, owner, percent -> {
                if (!isDestroyed()) {
                    uploadedPercent = percent;
                    renderUploadProgress();
                }
            }, code -> {
                if (isDestroyed()) {
                    return;
                }
                uploading = false;
                if (code == null) {
                    Toast.makeText(MainActivity.this, R.string.upload_complete, Toast.LENGTH_SHORT).show();
                } else {
                    int message = switch (code) {
                        case "invalid_size" -> R.string.upload_size_error;
                        case "invalid_type", "invalid_audio" -> R.string.upload_type_error;
                        case "quota_exceeded" -> R.string.upload_quota_error;
                        case "too_many_uploads" -> R.string.upload_pending_error;
                        case "storage_not_configured" -> R.string.upload_setup_needed;
                        default -> R.string.upload_error;
                    };
                    Toast.makeText(MainActivity.this, message, Toast.LENGTH_LONG).show();
                }
                if (account != null && account.id().equals(owner)) {
                    loadUploads();
                }
                render();
            });
        }

        private void confirmDeleteUpload(Track track) {
            if (account == null) {
                return;
            }
            String owner = account.id();
            MasheedsDialog dialog = new MasheedsDialog(MainActivity.this, getText(R.string.delete_upload));
            dialog.addMessage(getString(R.string.delete_upload_message, track.displayTitle(MainActivity.this)));
            dialog.addCancel();
            dialog.addAction(R.string.delete_upload, true, () -> {
                dialog.dismiss();
                backend.deletePrivateTrack(owner, track.onlineId(), result -> {
                    if (isDestroyed() || account == null || !account.id().equals(owner)) {
                        return;
                    }
                    if (result.success()) {
                        withPlayback(service -> service.removeTrack(track.libraryUri().toString()));
                    } else {
                        Toast.makeText(MainActivity.this, R.string.delete_upload_error, Toast.LENGTH_LONG).show();
                    }
                    loadUploads();
                });
            });
            dialog.show();
        }

        private void renderUploadProgress() {
            boolean visible = section == Section.UPLOADS && uploading;
            uploadStatus.setVisibility(visible ? View.VISIBLE : View.GONE);
            uploadProgress.setVisibility(visible ? View.VISIBLE : View.GONE);
            if (!visible) {
                return;
            }
            uploadProgress.setIndeterminate(uploadedPercent < 0 || uploadedPercent == 100);
            uploadProgress.setProgress(Math.max(0, uploadedPercent));
            if (uploadedPercent < 0) {
                uploadStatus.setText(R.string.upload_preparing);
            } else if (uploadedPercent == 100) {
                uploadStatus.setText(R.string.upload_verifying);
            } else {
                uploadStatus.setText(getString(R.string.upload_percent, uploadedPercent));
            }
        }

        private void loadLikes() {
            if (account == null || likesState == Load.LOADING) {
                return;
            }
            likesState = Load.LOADING;
            int generation = accountGeneration;
            backend.likes(account.id(), result -> {
                if (isDestroyed() || generation != accountGeneration) {
                    return;
                }
                likesState = result.success() ? Load.READY : Load.ERROR;
                if (result.success()) {
                    likes = result.value();
                }
                render();
            });
        }

        boolean canLike() {
            return account != null;
        }

        void toggleLike(Track track) {
            String id = track.onlineId();
            if (!canLike() || accountBusy || likesState != Load.READY || !pendingLikes.add(id)) {
                return;
            }
            boolean liked = !likes.contains(id);
            int generation = accountGeneration;
            render();
            backend.setLiked(account.id(), id, liked, result -> {
                if (isDestroyed() || generation != accountGeneration) {
                    return;
                }
                pendingLikes.remove(id);
                if (result.success()) {
                    if (liked) {
                        likes.add(id);
                    } else {
                        likes.remove(id);
                    }
                } else {
                    Toast.makeText(MainActivity.this, R.string.discover_likes_error, Toast.LENGTH_SHORT).show();
                    refreshAccount();
                }
                render();
            });
        }

        void bindLikeButton(ImageButton button, Track track) {
            boolean liked = likes.contains(track.onlineId());
            button.setImageResource(liked ? R.drawable.ic_liked : R.drawable.ic_like);
            button.setContentDescription(getString(liked ? R.string.unlike_track : R.string.like_track,
                    track.displayTitle(MainActivity.this)));
            MainActivity.setEnabled(button, canLike() && !accountBusy && likesState == Load.READY
                    && !pendingLikes.contains(track.onlineId()));
        }

        private void bindRowLike(TrackRow row, Track track) {
            if (row.likeButton == null && canLike()) {
                row.likeButton = iconButton(R.drawable.ic_like, R.string.discover_liked, accent, Color.TRANSPARENT,
                        48, 24, () -> {});
                row.likeButton.setFocusable(false);
                row.addView(row.likeButton, new LinearLayout.LayoutParams(dp(48), dp(48)));
            }
            if (row.likeButton != null) {
                row.likeButton.setVisibility(canLike() ? View.VISIBLE : View.GONE);
                row.likeButton.setOnClickListener(view -> toggleLike(track));
                bindLikeButton(row.likeButton, track);
            }
        }

        private void renderQuickPicks(List<Track> tracks) {
            List<Track> picks = tracks.subList(0, Math.min(tracks.size(), QUICK_PICK_LIMIT));
            if (!quickPickTracks.equals(picks)) {
                quickPickTracks = new ArrayList<>(picks);
                quickPickRows.clear();
                quickPickColumns.removeAllViews();
                for (int start = 0; start < picks.size(); start += QUICK_PICK_ROWS_PER_COLUMN) {
                    LinearLayout column = column();
                    LinearLayout.LayoutParams size = new LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT);
                    if (start > 0) {
                        size.setMarginStart(dp(10));
                    }
                    quickPickColumns.addView(column, size);
                    int end = Math.min(start + QUICK_PICK_ROWS_PER_COLUMN, picks.size());
                    for (Track track : picks.subList(start, end)) {
                        TrackRow row = new TrackRow();
                        row.duration.setVisibility(View.GONE);
                        row.setFocusable(true);
                        row.setOnClickListener(view -> selectTrack(visible, track, null));
                        quickPickRows.add(row);
                        column.addView(row, new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT));
                    }
                }
            }
            bindQuickPickRows();
        }

        private void bindQuickPickRows() {
            for (int index = 0; index < quickPickRows.size(); index++) {
                TrackRow row = quickPickRows.get(index);
                Track track = quickPickTracks.get(index);
                row.bind(track);
                bindRowLike(row, track);
            }
        }

        private void renderAlbums(List<Track> tracks) {
            Map<AlbumKey, Track> covers = new LinkedHashMap<>();
            for (Track track : tracks) {
                if (track.album().isEmpty()) {
                    continue;
                }
                AlbumKey album = AlbumKey.of(track);
                Track existingCover = covers.putIfAbsent(album, track);
                if (existingCover != null && existingCover.artworkUri() == null && track.artworkUri() != null) {
                    covers.put(album, track);
                }
            }
            albumsSection.setVisibility(covers.isEmpty() ? View.GONE : View.VISIBLE);
            if (albumCovers.equals(covers)) {
                return;
            }
            albumCovers = covers;
            albumCards.removeAllViews();
            for (Map.Entry<AlbumKey, Track> entry : covers.entrySet()) {
                AlbumKey album = entry.getKey();
                Track cover = entry.getValue();
                LinearLayout card = column();
                card.setBackground(ripple(Color.TRANSPARENT, 12));
                card.setFocusable(true);
                card.setOnClickListener(view -> {
                    selectedAlbum = album;
                    selectView(Section.ALBUM);
                });
                LinearLayout.LayoutParams cardSize = new LinearLayout.LayoutParams(dp(116), WRAP_CONTENT);
                cardSize.setMarginEnd(dp(12));
                albumCards.addView(card, cardSize);

                ImageView tile = artworkTile(accentSurface, 12);
                displayArtwork(tile, null, R.drawable.ic_record, null, 14);
                card.addView(tile, new LinearLayout.LayoutParams(MATCH_PARENT, dp(116)));
                if (cover.artworkUri() != null) {
                    ArtworkLoader.load(MainActivity.this, cover, ArtworkLoader.THUMBNAIL_SIZE, bitmap -> {
                        if (!isDestroyed() && bitmap != null) {
                            displayArtwork(tile, bitmap, R.drawable.ic_record, null, 14);
                        }
                    });
                }

                TextView title = singleLineText(15, ink, true);
                title.setText(album.name());
                addWithMargin(card, title, MATCH_PARENT, WRAP_CONTENT, 8);
                if (!album.artist().isEmpty()) {
                    TextView artist = singleLineText(13, muted, false);
                    artist.setText(album.artist());
                    addWithMargin(card, artist, MATCH_PARENT, WRAP_CONTENT, 4);
                }
            }
        }

        private void render() {
            renderAccount();
            boolean showingAll = section == Section.ALL;
            boolean showingAlbum = section == Section.ALBUM;
            boolean showingLikes = section == Section.LIKED;
            boolean showingUploads = section == Section.UPLOADS;
            boolean hasCatalog = catalog != null && catalog.usable();
            headerTitle.setText(showingAlbum ? selectedAlbum.name()
                    : getText(showingAll ? R.string.discover_all_tracks : R.string.discover));
            browseBack.setVisibility(showingAll || showingAlbum ? View.VISIBLE : View.GONE);
            refreshCatalog.setVisibility(DiscoverBackend.configured() && !showingLikes && !showingUploads
                    ? View.VISIBLE : View.GONE);
            MainActivity.setEnabled(refreshCatalog, catalogState != Load.LOADING);
            styleTab(browseTab, !showingLikes && !showingUploads);
            styleTab(likedTab, showingLikes);
            styleTab(uploadsTab, showingUploads);
            uploadButton.setVisibility(showingUploads && account != null ? View.VISIBLE : View.GONE);
            MainActivity.setEnabled(uploadButton, !uploading && !accountBusy);
            renderUploadProgress();

            int message = 0;
            int action = 0;
            Runnable next = null;
            if (!DiscoverBackend.configured()) {
                message = R.string.discover_setup_needed;
            } else if ((showingLikes || showingUploads) && account == null) {
                if (accountBusy) {
                    message = R.string.loading;
                } else {
                    message = accountError != 0 ? accountError
                            : showingUploads ? R.string.upload_sign_in : R.string.discover_sign_in_prompt;
                    if (DiscoverBackend.signInConfigured()) {
                        action = R.string.google_sign_in;
                        next = this::signIn;
                    }
                }
            } else if (showingUploads && uploadsState == Load.ERROR) {
                message = R.string.upload_list_error;
                action = R.string.retry;
                next = this::refreshAccount;
            } else if (showingUploads && uploadsState != Load.READY && uploads.isEmpty()) {
                message = R.string.loading;
            } else if (showingLikes && likesState == Load.ERROR) {
                message = R.string.discover_likes_error;
                action = R.string.retry;
                next = this::refreshAccount;
            } else if (!showingUploads && catalogState == Load.ERROR && !hasCatalog) {
                message = R.string.discover_catalog_error;
                action = R.string.retry;
                next = () -> loadCatalog(true);
            } else if ((!showingUploads && !hasCatalog)
                    || (showingLikes && likesState != Load.READY && likes.isEmpty())) {
                message = R.string.loading;
            }

            boolean searchable = message == 0;
            search.setVisibility(searchable ? View.VISIBLE : View.GONE);
            search.setHint(showingAlbum ? R.string.discover_search_album : R.string.discover_search);
            catalogRetry.setVisibility(searchable && !showingUploads && hasCatalog && catalogState == Load.ERROR
                    ? View.VISIBLE : View.GONE);
            String query = search.getText().toString().trim().toLowerCase(Locale.ROOT);
            boolean showingQuickPicks = section == Section.BROWSE && searchable && query.isEmpty();
            List<Track> matches = new ArrayList<>();
            if (searchable) {
                boolean hasTracks = false;
                for (Track track : showingUploads ? uploads : catalog.tracks()) {
                    if (showingAlbum && !selectedAlbum.equals(AlbumKey.of(track))) {
                        continue;
                    }
                    if (!showingLikes || likes.contains(track.onlineId())) {
                        hasTracks = true;
                        if (track.title().toLowerCase(Locale.ROOT).contains(query)
                                || track.artist().toLowerCase(Locale.ROOT).contains(query)) {
                            matches.add(track);
                        }
                    }
                }
                if (matches.isEmpty()) {
                    message = hasTracks ? R.string.no_matches_title : showingUploads ? R.string.upload_empty
                            : showingLikes ? R.string.discover_no_likes : showingAlbum
                            ? R.string.discover_empty_album : R.string.discover_no_tracks;
                }
            }
            visible = matches;
            quickPicksHeader.setVisibility(showingQuickPicks ? View.VISIBLE : View.GONE);
            browseResults.setVisibility(showingQuickPicks && !matches.isEmpty() ? View.VISIBLE : View.GONE);
            if (showingQuickPicks) {
                renderQuickPicks(matches);
                renderAlbums(matches);
            }
            trackAdapter.notifyDataSetChanged();
            list.setVisibility(!showingQuickPicks && !matches.isEmpty() ? View.VISIBLE : View.GONE);
            status.setVisibility(message == 0 ? View.GONE : View.VISIBLE);
            if (message != 0) {
                status.setText(message);
            }
            statusAction.setVisibility(action == 0 ? View.GONE : View.VISIBLE);
            if (next != null) {
                statusAction.setText(action);
                Runnable selected = next;
                statusAction.setOnClickListener(view -> selected.run());
            }
        }

        private void styleTab(Button button, boolean selected) {
            button.setSelected(selected);
            button.setBackground(ripple(selected ? accentSurface : surface, 14));
        }

        private void renderAccount() {
            boolean configured = DiscoverBackend.signInConfigured();
            int message = 0;
            if (accountBusy) {
                message = R.string.loading;
            } else if (!configured) {
                message = R.string.account_setup_needed;
            } else if (accountError != 0) {
                message = accountError;
            } else if (account == null) {
                message = R.string.account_signed_out;
            }
            if (message != 0) {
                accountStatus.setText(message);
            } else {
                accountStatus.setText(account.email().isEmpty() ? getString(R.string.account_signed_in) : account.email());
            }
            updateAccountAction(accountAction, configured);
            if (screen == Screen.PROFILE || account == null) {
                profileScreen.render(account, message, configured, !accountBusy && !uploading);
            }
            renderPlayerArtworkAction(playback == null ? null : playback.currentTrack());
        }

        private void updateAccountAction(Button button, boolean configured) {
            button.setText(account == null ? R.string.google_sign_in : R.string.sign_out);
            button.setVisibility(configured ? View.VISIBLE : View.GONE);
            MainActivity.setEnabled(button, !accountBusy && !uploading && !profileScreen.submitting);
        }
    }

    private final class ProfileBanner extends View {
        private static final int[] WAVEFORM = {
                0, 3, -4, 5, -7, 2, 12, -16, 8, -9, 24, -28, 14, -7, 12,
                -17, 8, -5, 9, -12, 5, -15, 19, -22, 11, -8, 4, -5, 14, -10,
                7, -19, 32, -24, 15, -5, 9, -7, 4, -3, 0
        };
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();
        private Shader backdrop;

        ProfileBanner() {
            super(MainActivity.this);
            setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        }

        @Override protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
            super.onSizeChanged(width, height, oldWidth, oldHeight);
            if (width > 0 && height > 0) {
                int base = darkMode ? PLAYER : accentSurface;
                backdrop = new LinearGradient(0, 0, width, height, base, surface, Shader.TileMode.CLAMP);
            }
        }

        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float width = getWidth();
            float height = getHeight();
            paint.setColor(accentSurface);
            paint.setStyle(Paint.Style.FILL);
            paint.setShader(backdrop);
            canvas.drawRect(0, 0, width, height, paint);
            paint.setShader(null);

            paint.setStyle(Paint.Style.STROKE);
            paint.setColor(accent);
            paint.setAlpha(27);
            paint.setStrokeWidth(dp(1));
            for (int index = 0; index < 6; index++) {
                float offset = index * height / 11f;
                path.rewind();
                path.moveTo(0, offset - height * 0.18f);
                path.cubicTo(width * 0.26f, offset + height * 0.58f,
                        width * 0.69f, offset - height * 0.32f, width, offset + height * 0.22f);
                canvas.drawPath(path, paint);
            }

            paint.setColor(accent);
            paint.setStrokeWidth(dp(2));
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeJoin(Paint.Join.ROUND);
            float left = width * 0.20f;
            float spacing = width * 0.60f / (WAVEFORM.length - 1);
            float center = height * 0.45f;
            path.rewind();
            path.moveTo(left, center);
            for (int index = 1; index < WAVEFORM.length; index++) {
                path.lineTo(left + spacing * index, center + height * WAVEFORM[index] / 145f);
            }
            canvas.drawPath(path, paint);
        }
    }

    private final class ProfilePage extends FrameLayout {
        private final ScrollView scroll;
        private final LinearLayout hero;
        private final FrameLayout compactHeader;
        private final TextView compactName;
        private final ImageView avatar;
        private final TextView name;
        private final TextView status;
        private final Button action;
        private final Button submit;
        private final TextView submissionStatus;
        private final LinearLayout publishedSection;
        private final LinearLayout publishedRows;
        private final TextView publishedMessage;
        private final Button publishedRetry;
        private List<Track> publishedTracks = Collections.emptyList();
        private Load publishedState = Load.IDLE;
        private DiscoverBackend.Account avatarAccount;
        private Future<?> avatarRequest;
        private String submissionPickerOwner;
        private boolean submitting;

        ProfilePage() {
            super(MainActivity.this);
            scroll = new ScrollView(MainActivity.this);
            scroll.setFillViewport(true);
            addView(scroll, new FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT));
            LinearLayout page = column();
            page.setPadding(0, 0, 0, dp(24));
            scroll.addView(page);

            hero = column();
            hero.setBackgroundColor(surface);
            page.addView(hero, new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT));
            FrameLayout artwork = new FrameLayout(MainActivity.this);
            hero.addView(artwork, new LinearLayout.LayoutParams(MATCH_PARENT, dp(184)));
            artwork.addView(new ProfileBanner(), new FrameLayout.LayoutParams(MATCH_PARENT, dp(148)));

            FrameLayout avatarBorder = new FrameLayout(MainActivity.this);
            avatarBorder.setBackground(round(surface, 42));
            avatarBorder.setPadding(dp(4), dp(4), dp(4), dp(4));
            FrameLayout.LayoutParams avatarPosition = new FrameLayout.LayoutParams(dp(84), dp(84),
                    Gravity.BOTTOM | Gravity.START);
            avatarPosition.setMarginStart(dp(16));
            artwork.addView(avatarBorder, avatarPosition);
            avatar = artworkTile(accentSurface, 38);
            displayArtwork(avatar, null, R.drawable.ic_profile, accent, 22);
            avatarBorder.addView(avatar, new FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT));

            LinearLayout details = column();
            details.setPadding(dp(20), dp(8), dp(20), dp(16));
            hero.addView(details, new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT));
            LinearLayout identity = new LinearLayout(MainActivity.this);
            identity.setGravity(Gravity.CENTER_VERTICAL);
            details.addView(identity, new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT));
            name = text(R.string.profile, 26, ink, true);
            name.setMaxLines(2);
            name.setEllipsize(TextUtils.TruncateAt.END);
            markHeading(name);
            identity.addView(name, new LinearLayout.LayoutParams(0, WRAP_CONTENT, 1));

            submit = button(R.string.submit_music, onAccent, accent);
            submit.setMinHeight(dp(48));
            submit.setMaxWidth(dp(148));
            submit.setOnClickListener(view -> pickSubmissionFile());
            LinearLayout.LayoutParams submitSize = new LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT);
            submitSize.setMarginStart(dp(12));
            identity.addView(submit, submitSize);

            status = text(R.string.account_signed_out, 15, muted, false);
            status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
            addWithMargin(details, status, MATCH_PARENT, WRAP_CONTENT, 10);
            action = button(R.string.google_sign_in, onAccent, accent);
            action.setMinHeight(dp(48));
            action.setOnClickListener(view -> {
                if (discoverScreen.account == null) {
                    discoverScreen.signIn();
                }
            });
            addWithMargin(details, action, WRAP_CONTENT, WRAP_CONTENT, 12);

            submissionStatus = text(R.string.submission_help, 14, muted, false);
            submissionStatus.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
            addWithMargin(details, submissionStatus, MATCH_PARENT, WRAP_CONTENT, 12);

            publishedSection = column();
            publishedSection.setPadding(dp(20), dp(20), dp(20), 0);
            page.addView(publishedSection, new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT));
            TextView publishedTitle = text(R.string.profile_published_tracks, 20, ink, true);
            markHeading(publishedTitle);
            publishedSection.addView(publishedTitle);
            publishedMessage = text(0, 15, muted, false);
            publishedMessage.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
            addWithMargin(publishedSection, publishedMessage, MATCH_PARENT, WRAP_CONTENT, 8);
            publishedRetry = button(R.string.retry, accent, accentSurface);
            publishedRetry.setMinHeight(dp(48));
            publishedRetry.setOnClickListener(view -> loadPublishedTracks());
            addWithMargin(publishedSection, publishedRetry, WRAP_CONTENT, WRAP_CONTENT, 8);
            publishedRows = column();
            addWithMargin(publishedSection, publishedRows, MATCH_PARENT, WRAP_CONTENT, 8);

            compactName = singleLineText(16, ink, true);
            compactHeader = screenHeader(compactName);
            compactHeader.setBackgroundColor(surface);
            compactHeader.setClickable(true);
            compactHeader.setVisibility(View.GONE);
            addView(compactHeader, new FrameLayout.LayoutParams(MATCH_PARENT, dp(52), Gravity.TOP));
            scroll.setOnScrollChangeListener((view, x, y, oldX, oldY) -> updateCompactHeader());
            hero.addOnLayoutChangeListener((view, left, top, right, bottom,
                    oldLeft, oldTop, oldRight, oldBottom) -> updateCompactHeader());

            submit.setVisibility(View.GONE);
            submissionStatus.setVisibility(View.GONE);
            publishedSection.setVisibility(View.GONE);
        }

        void render(DiscoverBackend.Account account, int message, boolean configured, boolean ready) {
            boolean hasDisplayName = account != null && !account.displayName().isEmpty()
                    && !account.displayName().equalsIgnoreCase(account.email());
            name.setText(hasDisplayName ? account.displayName() : getText(R.string.profile));
            if (account == null && !submitting) {
                submissionStatus.setText(R.string.submission_help);
            }
            submit.setVisibility(account == null ? View.GONE : View.VISIBLE);
            submissionStatus.setVisibility(account == null ? View.GONE : View.VISIBLE);
            MainActivity.setEnabled(submit, ready && !submitting && submissionPickerOwner == null);
            publishedSection.setVisibility(account == null ? View.GONE : View.VISIBLE);
            renderPublishedStatus();
            status.setVisibility(message == 0 ? View.GONE : View.VISIBLE);
            if (message != 0) {
                status.setText(message);
            }
            action.setVisibility(account == null && configured ? View.VISIBLE : View.GONE);
            MainActivity.setEnabled(action, ready && !submitting);
            compactName.setText(name.getText());
            updateCompactHeader();
            bindAvatar(account);
        }

        private void updateCompactHeader() {
            boolean collapsed = hero.getHeight() > 0 && scroll.getScrollY() > 0
                    && scroll.getScrollY() >= hero.getBottom() - dp(52);
            compactHeader.setVisibility(collapsed ? View.VISIBLE : View.GONE);
        }

        void loadPublishedTracks() {
            DiscoverBackend.Account account = discoverScreen.account;
            if (account == null || publishedState == Load.LOADING) {
                return;
            }
            int generation = discoverScreen.accountGeneration;
            publishedState = Load.LOADING;
            renderPublishedStatus();
            discoverScreen.backend.publishedTracks(account.id(), result -> {
                if (isDestroyed() || generation != discoverScreen.accountGeneration) {
                    return;
                }
                publishedState = result.success() ? Load.READY : Load.ERROR;
                if (result.success()) {
                    showPublishedTracks(result.value());
                }
                renderPublishedStatus();
            });
        }

        void clearPublishedTracks() {
            publishedState = Load.IDLE;
            showPublishedTracks(Collections.emptyList());
            scroll.scrollTo(0, 0);
        }

        private void showPublishedTracks(List<Track> tracks) {
            if (publishedTracks.equals(tracks)) {
                return;
            }
            for (int index = 0; index < publishedRows.getChildCount(); index++) {
                TrackRow row = (TrackRow) publishedRows.getChildAt(index);
                if (row.artworkRequest != null) {
                    row.artworkRequest.cancel(false);
                }
                row.artworkVersion++;
            }
            publishedRows.removeAllViews();
            publishedTracks = tracks;
            for (Track track : tracks) {
                TrackRow row = new TrackRow();
                row.setFocusable(true);
                row.setOnClickListener(view -> selectTrack(publishedTracks, track, null));
                publishedRows.addView(row, new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT));
            }
            bindPublishedRows();
        }

        void bindPublishedRows() {
            for (int index = 0; index < publishedTracks.size(); index++) {
                ((TrackRow) publishedRows.getChildAt(index)).bind(publishedTracks.get(index));
            }
        }

        private void renderPublishedStatus() {
            int label = 0;
            if (publishedState == Load.LOADING && publishedTracks.isEmpty()) {
                label = R.string.loading;
            } else if (publishedState == Load.ERROR) {
                label = R.string.profile_published_error;
            } else if (publishedState == Load.READY && publishedTracks.isEmpty()) {
                label = R.string.profile_published_empty;
            }
            publishedMessage.setVisibility(label == 0 ? View.GONE : View.VISIBLE);
            if (label != 0) {
                publishedMessage.setText(label);
            }
            publishedRetry.setVisibility(publishedState == Load.ERROR ? View.VISIBLE : View.GONE);
        }

        private void pickSubmissionFile() {
            DiscoverBackend.Account account = discoverScreen.account;
            if (account == null || submitting || submissionPickerOwner != null) {
                return;
            }
            Intent picker = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                    .setType("audio/*").putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"audio/mpeg", "audio/mp3"});
            try {
                submissionPickerOwner = account.id();
                discoverScreen.renderAccount();
                startActivityForResult(picker, PICK_DISCOVER_SUBMISSION);
            } catch (ActivityNotFoundException error) {
                submissionPickerOwner = null;
                discoverScreen.renderAccount();
                Toast.makeText(MainActivity.this, R.string.picker_error, Toast.LENGTH_SHORT).show();
            }
        }

        void saveSubmissionPicker(Bundle state) {
            state.putString("submissionOwner", submissionPickerOwner);
        }

        void restoreSubmissionPicker(Bundle state) {
            submissionPickerOwner = state.getString("submissionOwner");
        }

        void onSubmissionFilePicked(Uri document) {
            String owner = submissionPickerOwner;
            submissionPickerOwner = null;
            discoverScreen.renderAccount();
            if (owner != null && document != null) {
                openSubmissionForm(document, owner);
            }
        }

        private void openSubmissionForm(Uri document, String owner) {
            MasheedsDialog dialog = new MasheedsDialog(MainActivity.this, getText(R.string.submit_music));
            EditText title = dialog.addInput(R.string.submission_title, "");
            title.setFilters(new InputFilter[]{new InputFilter.LengthFilter(200)});
            EditText artist = dialog.addInput(R.string.submission_artist, "");
            artist.setFilters(new InputFilter[]{new InputFilter.LengthFilter(200)});
            title.requestFocus();
            dialog.addCancel();
            dialog.addAction(R.string.submission_send_for_review, true, () -> {
                String trackName = title.getText().toString().trim();
                String artistName = artist.getText().toString().trim();
                if (trackName.isEmpty()) {
                    title.setError(getText(R.string.submission_required));
                    title.requestFocus();
                    return;
                }
                if (artistName.isEmpty()) {
                    artist.setError(getText(R.string.submission_required));
                    artist.requestFocus();
                    return;
                }
                dialog.dismiss();
                submitTrack(document, owner, trackName, artistName);
            });
            dialog.show();
        }

        private void submitTrack(Uri document, String owner, String title, String artist) {
            submitting = true;
            submissionStatus.setText(R.string.upload_preparing);
            discoverScreen.renderAccount();
            PrivateMusicTransfer.submitForReview(MainActivity.this, document, owner, title, artist, percent -> {
                        if (!isDestroyed()) {
                            submissionStatus.setText(percent == 100 ? getText(R.string.upload_verifying)
                                    : getString(R.string.upload_percent, percent));
                        }
                    }, code -> {
                        if (isDestroyed()) {
                            return;
                        }
                        submitting = false;
                        int result = code == null ? R.string.submission_sent : switch (code) {
                            case "invalid_size" -> R.string.submission_size_error;
                            case "invalid_type", "invalid_audio" -> R.string.submission_type_error;
                            case "submission_account_limit" -> R.string.submission_account_limit;
                            case "submission_queue_full", "submission_in_review" -> R.string.submission_wait;
                            case "submission_setup_needed" -> R.string.submission_setup_needed;
                            default -> R.string.submission_error;
                        };
                        submissionStatus.setText(result);
                        discoverScreen.renderAccount();
                        if (screen != Screen.PROFILE) {
                            Toast.makeText(MainActivity.this, result, Toast.LENGTH_SHORT).show();
                        }
                    });
        }

        private void bindAvatar(DiscoverBackend.Account account) {
            if (Objects.equals(avatarAccount, account)) {
                return;
            }
            cancelAvatarRequest();
            avatarAccount = account;
            displayArtwork(avatar, null, R.drawable.ic_profile, accent, 22);
            if (account != null && account.avatarUri() != null) {
                avatarRequest = ArtworkLoader.loadRemote(account.avatarUri(), dp(88), bitmap -> {
                    if (!isDestroyed() && avatarAccount == account && bitmap != null) {
                        displayArtwork(avatar, bitmap, R.drawable.ic_profile, accent, 22);
                    }
                });
            }
        }

        void cancelAvatarRequest() {
            if (avatarRequest != null) {
                avatarRequest.cancel(true);
                avatarRequest = null;
            }
        }
    }

    private final class RecentlyDeletedPage extends LinearLayout {
        private final ListView list;
        private final TextView empty;
        private final DateFormat dates = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT, appLocale);
        private List<RecentlyDeleted.Entry> entries = Collections.emptyList();
        private final BaseAdapter historyAdapter = new BaseAdapter() {
            @Override public int getCount() { return entries.size(); }
            @Override public RecentlyDeleted.Entry getItem(int position) { return entries.get(position); }
            @Override public long getItemId(int position) { return position; }

            @Override public View getView(int position, View recycled, ViewGroup parent) {
                RemovedTrackRow row = recycled == null ? new RemovedTrackRow() : (RemovedTrackRow) recycled;
                row.bind(getItem(position));
                return row;
            }
        };

        RecentlyDeletedPage() {
            super(MainActivity.this);
            setOrientation(VERTICAL);
            setPadding(dp(20), dp(6), dp(20), dp(10));

            TextView title = singleLineText(16, ink, true);
            title.setText(R.string.recently_deleted);
            FrameLayout header = screenHeader(title);
            ImageButton back = iconButton(R.drawable.ic_back, R.string.back_to_settings, accent, accentSurface,
                    48, 24, () -> showScreen(Screen.SETTINGS));
            addHeaderButton(header, back, Gravity.START);
            addView(header, new LinearLayout.LayoutParams(MATCH_PARENT, dp(54)));

            FrameLayout results = new FrameLayout(MainActivity.this);
            addView(results, new LinearLayout.LayoutParams(MATCH_PARENT, 0, 1));
            list = styledList();
            list.setAdapter(historyAdapter);
            results.addView(list, new FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT));
            empty = text(R.string.no_recently_deleted, 15, muted, false);
            empty.setGravity(Gravity.CENTER);
            results.addView(empty, new FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.CENTER));
        }

        void refresh() {
            entries = RecentlyDeleted.load(MainActivity.this);
            historyAdapter.notifyDataSetChanged();
            list.setVisibility(entries.isEmpty() ? View.GONE : View.VISIBLE);
            empty.setVisibility(entries.isEmpty() ? View.VISIBLE : View.GONE);
        }

        private final class RemovedTrackRow extends LinearLayout {
            private final TextView title;
            private final TextView source;
            private final TextView date;

            RemovedTrackRow() {
                super(MainActivity.this);
                setOrientation(VERTICAL);
                setPadding(dp(8), dp(10), dp(8), dp(10));
                title = singleLineText(16, ink, true);
                source = singleLineText(13, muted, false);
                date = text(0, 13, muted, false);
                addView(title);
                addWithMargin(this, source, MATCH_PARENT, WRAP_CONTENT, 3);
                addWithMargin(this, date, MATCH_PARENT, WRAP_CONTENT, 2);
            }

            void bind(RecentlyDeleted.Entry entry) {
                title.setText(entry.title().isEmpty() ? getString(R.string.untitled_track) : entry.title());
                source.setText(entry.playlistName() == null ? getString(R.string.removed_from_masheeds)
                        : getString(R.string.removed_from_named_playlist, entry.playlistName()));
                date.setText(dates.format(new Date(entry.removedAt())));
            }
        }
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
                List<Track> removed = new ArrayList<>();
                for (int index = 0; index < available.size(); index++) {
                    String uri = available.get(index).libraryUri().toString();
                    if (checked[index]) {
                        members.add(uri);
                    } else if (members.remove(uri)) {
                        removed.add(available.get(index));
                    }
                }
                replacePlaylist(playlist, new Playlist(playlist.id(), playlist.name(), new ArrayList<>(members)));
                RecentlyDeleted.record(MainActivity.this, removed, playlist.name());
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
                if (members.removeIf(track.libraryUri().toString()::equals)) {
                    replacePlaylist(playlist, new Playlist(playlist.id(), playlist.name(), members));
                    RecentlyDeleted.record(MainActivity.this, Collections.singletonList(track), playlist.name());
                }
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
                RecentlyDeleted.record(MainActivity.this, resolveTracks(playlist), playlist.name());
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
        private ImageButton likeButton;
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
            boolean selected = track.libraryUri().equals(selectedTrack);
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
                        renderArtwork(track.libraryUri().equals(selectedTrack));
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
