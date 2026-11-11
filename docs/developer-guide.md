# Masheeds developer guide

## Build from source

Open the project in Android Studio, or run the Gradle wrapper from the repository root. You’ll need JDK 17, Android SDK Platform 36, and Android SDK Build Tools 36.0.0.

On Windows, use PowerShell.

```powershell
.\gradlew.bat assembleDebug
```

On macOS or Linux, use the shell wrapper.

```sh
./gradlew assembleDebug
```

The build writes the APK to `app/build/outputs/apk/debug/app-debug.apk`.

## Set up Discover

1. For a new Supabase Free project, run the [Discover catalog migration](../supabase/migrations/20261110154641_discover_catalog_and_likes.sql), then the [Discover albums migration](../supabase/migrations/20261110225351_discover_track_albums.sql) in its SQL Editor. Keep the project on the Free plan and monitor its storage and transfer usage.
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

## Optional private uploads

Private uploads are disabled by default. Keep Masheeds on the Supabase Free plan and leave Cloudflare R2 disabled for a $0 deployment. Supabase can restrict online features if the project exceeds its Free quotas. [Supabase cost controls](https://supabase.com/docs/guides/platform/cost-control)

The [optional Cloudflare R2 setup guide](private-music-r2.md) covers the private MP3 and M4A upload integration and its billing requirements.
