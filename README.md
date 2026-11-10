# Masheeds

Masheeds is an offline music player for Android 8.0 and later. It has a searchable local library, a mini player for browsing, and a Now Playing screen with playback controls.

Album covers and other artwork from local tracks appear in the library, playlists, and player when available.

## Install and listen

On your Android device, download and open the debug APK from [GitHub Releases](https://github.com/playmhd/masheeds/releases).

1. Tap **Scan device** and grant audio access to find music stored on your device, or tap **+** to choose individual tracks.
2. Browse the library or search by track title or artist. Tap a track to start listening.
3. Tap the mini player to open **Now playing**, where you can pause, skip, or seek. You can also control playback from Android’s media controls while using other apps.
4. Tap the repeat icon beside the playback controls in **Now playing** to cycle through **Repeat off**, **Repeat all**, and **Repeat one**. Your repeat mode is saved between launches.
5. Open **Playlists** in the bottom bar and tap **+** to create a playlist. Open it and tap **+** again to choose tracks. Long-press a playlist to rename or delete it.
6. Open **Settings** in the bottom bar to choose **Light** or **Dark**, switch between English and Arabic, or manage audio access. The app saves your theme and language.

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
