# Masheeds

Masheeds is an offline music player for Android 8.0 and later. It has a searchable local library, a mini player for browsing, and a Now Playing screen with playback controls.

Album covers and other artwork from local tracks appear in the library, playlists, and player when available. Each playlist uses the cover of its first song with artwork by default. To choose a song’s cover, long-press the song in the Library or in a playlist and tap **Choose cover for this track**.

## Install and listen

On your Android device, download and open the debug APK from [GitHub Releases](https://github.com/playmhd/masheeds/releases).

1. Tap **+** in the Library to choose individual tracks, or open **Settings** → **Library** → **Scan device** and grant audio access to find music stored on your device.
2. Browse the library or search by track title or artist. Tap a track to start listening.
3. Tap the mini player to open **Now playing**, where you can pause, skip, or seek. On reopening Masheeds, tap **Play** on the paused mini player to continue from your saved position. You can also control playback from Android’s media controls.
4. Tap the repeat icon in **Now playing** to cycle through **Repeat off**, **Repeat all**, and **Repeat one**. Tap the timer icon to pause after 15, 30, or 60 minutes, or at the end of the current track.
5. Tap **Add to playlist** on the artwork in **Now playing** to add the current track. In a playlist, tap **+** to choose tracks or tap **Reorder tracks** to show drag handles on the rows. Hold a handle and drag the track to its new position. Each drop saves the new order and updates an active playlist’s queue. Tap the checkmark to hide the handles. Long-press a playlist to choose a custom cover, restore its default cover, rename it, or delete it.
6. Open **Settings** in the bottom bar to choose **Light** or **Dark**, switch between English and Arabic, or manage audio access. To turn off the vibration when switching tabs, disable **Navigation haptics** under **Preferences**.

Long-press a song in the Library and choose **Delete track** to remove it from Masheeds and its playlists. The original audio file stays on your device, and you can add it back using **+**. To remove a song from one playlist, long-press it inside that playlist and choose **Remove from playlist**.

The **Discover** tab displays a **Coming soon** placeholder.

## Build from source

Open the project in Android Studio, or build it with the Gradle wrapper. You’ll need JDK 17, Android SDK Platform 36, and Android SDK Build Tools 36.0.0.

On Windows, use PowerShell.

```powershell
.\gradlew.bat assembleDebug
```

On macOS or Linux, use the shell wrapper.

```sh
./gradlew assembleDebug
```

The build writes the APK to `app/build/outputs/apk/debug/app-debug.apk`.
