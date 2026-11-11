# Set up private music on Cloudflare R2

Masheeds uses your existing Supabase project and account sessions. **Discover → My uploads** is private to the signed-in owner. The app sends audio directly to R2 and keeps track metadata in Supabase. Each file can use up to 20,000,000 bytes and each account can reserve up to 100,000,000 bytes.

Keep R2 disabled for a $0 deployment. The Edge Function disables R2 access by default, and the Android app hides My uploads by default. If you opt into R2, every dashboard and deployment step below is manual. R2 requires a subscription and can bill for storage and requests beyond the Standard monthly allowance. [R2 setup](https://developers.cloudflare.com/r2/get-started/) · [R2 pricing](https://developers.cloudflare.com/r2/pricing/)

## 1. Create the private bucket

1. Open **Cloudflare Dashboard → R2 Object Storage** and activate R2 for your Cloudflare account.
2. Choose **Create bucket**, name it `masheeds-private`, and use **Standard** storage.
3. Keep **Public Development URL** and public custom-domain access disabled.
4. Under **Settings → Object lifecycle rules**, add a rule to delete objects under `incoming/` after one day. This catches late or abandoned uploads. The scheduled cleanup below normally removes staging files sooner. Apply this rule only to `incoming/`. [Cloudflare object lifecycle](https://developers.cloudflare.com/r2/buckets/object-lifecycles/)

## 2. Generate bucket credentials

From **R2 Overview → Manage R2 API Tokens**, create an API token with **Object Read & Write** scoped to `masheeds-private`. Save the **Access Key ID** and **Secret Access Key** in your password manager. [Cloudflare R2 API tokens](https://developers.cloudflare.com/r2/api/tokens/)

## 3. Copy the account ID and endpoint

Copy the **Account ID** and the S3 endpoint shown in R2. For a standard global bucket, the endpoint is `https://<ACCOUNT_ID>.r2.cloudflarestorage.com`. Set `R2_ENDPOINT` explicitly if the bucket uses a jurisdiction-specific endpoint. Use the account endpoint without a bucket path. [R2 S3 endpoint](https://developers.cloudflare.com/r2/api/s3/api/)

## 4. Configure CORS

For a browser client, open the bucket's **Settings → CORS policy** and paste [the sample policy](../supabase/r2-cors.example.json). Replace `https://music.example.com` with your exact HTTPS web origin, without a trailing slash. Use the same origin for `R2_WEB_ORIGIN` on the Edge Function. Android sends direct requests without browser CORS checks.

The policy permits upload PUTs and GET/HEAD requests with byte ranges. Browser uploads should pass a File or Blob of the reserved size so the browser generates `Content-Length`. Send the MIME type and `If-None-Match: *` returned by the begin endpoint. [Cloudflare CORS setup](https://developers.cloudflare.com/r2/buckets/cors/)

## 5. Deploy the migration, function, and cleanup

1. Confirm Google sign-in works in the Android app against your existing Supabase project. Sign in to the Supabase CLI and link it to that project.
2. Copy [`.env.example`](../.env.example) to `.env`, set `R2_UPLOADS_ENABLED=true`, and fill in your R2 values. Generate a separate random cleanup token of at least 32 characters. Keep the file local. The function reads the Supabase URL, anon key, and service-role key from Supabase's hosted environment. The Android app uses its existing project URL and publishable key. [Supabase function secrets](https://supabase.com/docs/guides/functions/secrets)
3. From the repository root, run:

   ```powershell
   supabase link --project-ref YOUR_EXISTING_PROJECT_REF
   supabase db push
   supabase secrets set --env-file .env
   supabase functions deploy private-music --no-verify-jwt
   ```

   The function validates each user operation against the existing Supabase session JWT and checks ownership. Cleanup uses a separate server-only token. [Supabase Edge Function authorization](https://supabase.com/docs/guides/functions/auth-headers)

4. In your project's **SQL Editor**, run the following once with your project reference and the same cleanup token used in `.env`. This stores the token in Vault and schedules retries for expired reservations, staged objects, and pending deletions. [Supabase scheduled functions](https://supabase.com/docs/guides/functions/schedule-functions)

   ```sql
   create extension if not exists pg_cron;
   create extension if not exists pg_net;

   select vault.create_secret(
     'https://YOUR_EXISTING_PROJECT_REF.supabase.co/functions/v1/private-music',
     'masheeds_private_music_cleanup_url'
   );
   select vault.create_secret('YOUR_RANDOM_CLEANUP_TOKEN', 'masheeds_private_music_cleanup_token');

   select cron.schedule('masheeds-private-music-cleanup', '*/10 * * * *', $job$
     select net.http_post(
       url := (select decrypted_secret from vault.decrypted_secrets
               where name = 'masheeds_private_music_cleanup_url'),
       headers := jsonb_build_object(
         'Content-Type', 'application/json',
         'X-R2-Cleanup-Token', (select decrypted_secret from vault.decrypted_secrets
                                where name = 'masheeds_private_music_cleanup_token')
       ),
       body := '{"action":"cleanup"}'::jsonb,
       timeout_milliseconds := 120000
     );
   $job$);
   ```

## 6. Test your first song

1. Add `PRIVATE_UPLOADS_ENABLED=true` to the root `local.properties` and build the APK. Install it, sign in, and open **Discover → My uploads → +**. Choose a small MP3 or M4A. Watch the upload progress and wait for verification to finish.
2. Play the track, seek forward, pause, resume, and skip after adding a second track. Sign in to the same account on another device and play it there. A second account should see its own collection and receive a missing-track response if it requests the first account's track ID.
3. For a direct range check, request a playback URL from `private-music` with your session JWT in `Authorization`, your publishable key in `apikey`, and JSON `{"action":"play","id":"TRACK_UUID"}`. Save the returned URL in a local variable and run `curl.exe -sS -D - -o NUL -H "Range: bytes=0-1023" "$playbackUrl"`. Expect **206 Partial Content** and a `Content-Range` header. Use the URL locally while testing. [Cloudflare presigned URLs](https://developers.cloudflare.com/r2/api/s3/presigned-urls/)
4. Long-press the uploaded track and choose **Delete uploaded track**. The app hides it when deletion starts. R2 deletion and quota release finish after successful cleanup. If the original upload URL is still valid, cleanup waits for it to expire before removing its staged object.
5. Test a file larger than 20 MB, a non-audio file renamed to `.mp3`, and a sixth 20 MB reservation on an empty test account. The app should reject these. Interrupt an upload and check that the scheduled job eventually removes the reservation and staged object.
