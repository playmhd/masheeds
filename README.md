# Masheeds

Masheeds is a music player for Android 8.0 and later. It plays local audio offline and can stream a curated catalog through Discover.

Album covers and other artwork from local tracks appear in the library, playlists, and player when available. Each playlist uses the cover of its first song with artwork by default. To choose a song’s cover, long-press the song in the Library or in a playlist and tap **Choose cover for this track**.

## Install and listen

On your Android device, download and open the debug APK from [GitHub Releases](https://github.com/playmhd/masheeds/releases).

1. Tap **+** in the Library to choose individual tracks, or open **Settings** → **Library** → **Scan device** and grant audio access to find music stored on your device.
2. Browse the library or search by track title or artist. Tap a track to start listening.
3. Tap the mini player to open **Now playing**, where you can pause, skip, or seek. On reopening Masheeds, tap **Play** on the paused mini player to continue from your saved position. You can also control playback from Android’s media controls.
4. Tap **Shuffle** on the left of the **Now playing** controls to mix the playback order. Tap the repeat icon to cycle through **Repeat off**, **Repeat all**, and **Repeat one**. Tap the timer icon to pause after 15, 30, or 60 minutes, or at the end of the current track.
5. Tap **Add to playlist** on the artwork in **Now playing** to add the current track. In a playlist, tap **+** to choose tracks or tap **Reorder tracks** to show drag handles on the rows. Hold a handle and drag the track to its new position. Each drop saves the new order and updates an active playlist’s queue. Tap the checkmark to hide the handles. Long-press a playlist to choose a custom cover, restore its default cover, rename it, or delete it.
6. Open **Settings** in the bottom bar to choose **Light** or **Dark**, switch between English and Arabic, or manage audio access. To turn off the vibration when switching tabs, disable **Navigation haptics** under **Preferences**.

Long-press a song in the Library and choose **Delete track** to remove it from Masheeds and its playlists. The original audio file stays on your device, and you can add it back using **+**. To remove a song from one playlist, long-press it inside that playlist and choose **Remove from playlist**.

Open **Settings → Library → Recently deleted** to see tracks removed from Masheeds or a playlist in the last 30 days.

In **Discover**, browse and stream tracks as a guest. Tap an album below **Quick picks** to see and play its tracks. Sign in with Google under **Settings → Account** to save and sync **Liked tracks**.

Private uploads are disabled by default. Keep Masheeds on the Supabase Free plan and leave Cloudflare R2 disabled for a $0 deployment. Supabase can restrict online features if the project exceeds its Free quotas. [Supabase cost controls](https://supabase.com/docs/guides/platform/cost-control)

The [optional Cloudflare R2 setup guide](docs/private-music-r2.md) covers the private MP3 and M4A upload integration and its billing requirements.

## Set up Discover

1. For a new Supabase Free project, run the [Discover catalog migration](supabase/migrations/20261110154641_discover_catalog_and_likes.sql), then the [Discover albums migration](supabase/migrations/20261110225351_discover_track_albums.sql) in its SQL Editor. Keep the project on the Free plan and monitor its storage and transfer usage.
2. In Google Cloud, configure the OAuth consent screen and create Web and Android OAuth clients. Register the Android package `com.masheeds.player` and the SHA-1 fingerprint of the certificate that signs the APK testers will install. Add test accounts to the consent screen while it is in testing. Enable the Google provider in Supabase with the Web client ID and secret, and keep nonce verification enabled. [Supabase Google setup](https://supabase.com/docs/guides/auth/social-sign-in/auth-google)
3. Set the project's HTTPS URL and **publishable** key from Supabase, and the Google **Web** client ID, before building the APK. The build accepts these names as environment variables, Gradle properties, or entries in the root `local.properties` file. Keep the OAuth client secret in the Google and Supabase dashboards.

   ```powershell
   $env:SUPABASE_URL = 'https://YOUR_PROJECT.supabase.co'
   $env:SUPABASE_PUBLISHABLE_KEY = 'sb_publishable_...'
   $env:GOOGLE_WEB_CLIENT_ID = 'YOUR_WEB_CLIENT_ID.apps.googleusercontent.com'
   .\gradlew.bat assembleDebug
   ```

4. Upload seekable MP3s and optional cover images you have permission to publish to the `discover` bucket. Set `cacheControl: '86400'` when uploading MP3s through the Storage SDK. In the Supabase Table Editor, insert a row into `discover_tracks` with the track title, artist or reciter, duration in milliseconds, and public HTTPS asset URLs. Set `published` to true after verifying the audio URL. Keep the row ID when replacing a track's files so existing likes still point to it. To withdraw a track, unpublish its row and remove its public audio and artwork objects. [Storage upload options](https://supabase.com/docs/reference/javascript/storage-from-upload)
   Set `album` on tracks that belong in the Albums section. Use the same album title and artist for tracks in a group. Masheeds uses the first available `artwork_url` in the group for the album tile.
5. Verify that a public audio URL returns `206 Partial Content` for a byte-range request. On an Android device, check guest streaming, seeking, background playback, and local playback in airplane mode. Use two Google accounts to confirm likes sync for the same account and are separate between accounts.

Local playback is available in builds without the Discover configuration.

Run `pwsh -File ./tools/update-discover-cache.ps1` to check cache headers and range responses for published MP3s in the project's `discover` bucket. Add `-Apply` to update their cache lifetime in place and verify their SHA-256 checksums. The script prompts for the project's existing server-side secret key without echoing it.

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
