import {
  CopyObjectCommand,
  DeleteObjectCommand,
  GetObjectCommand,
  HeadObjectCommand,
  type S3Client,
} from "@aws-sdk/client-s3";
import { createClient } from "@supabase/supabase-js";
import { timingSafeEqual } from "node:crypto";
import { isSupportedAudio } from "./audio.ts";
import { createR2Client, signPlayback, signUpload } from "./presign.ts";

const MAX_FILE_BYTES = 20_000_000;
const PLAY_SECONDS = 3600;
const VERIFY_LEASE_MS = 10 * 60_000;
const RANGE_SIZE = 16 * 1024;
const UUID = /^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$/i;
const TABLE = "private_tracks";
const supabaseUrl = Deno.env.get("SUPABASE_URL")!;
const admin = createClient(supabaseUrl, Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!, {
  auth: { persistSession: false, autoRefreshToken: false },
});
const auth = createClient(supabaseUrl, Deno.env.get("SUPABASE_ANON_KEY")!, {
  auth: { persistSession: false, autoRefreshToken: false },
}).auth;

type Row = {
  id: string;
  owner_id: string | null;
  object_key: string;
  state: "pending" | "verifying" | "ready" | "deleting";
  size_bytes: number;
  mime_type: string;
  upload_until: string;
  finish_until: string;
  verify_until: string | null;
  verify_token: string | null;
  staging_pending: boolean;
};

class ApiError extends Error {
  constructor(readonly status: number, readonly code: string) {
    super(code);
  }
}

let storage: { client: S3Client; bucket: string } | undefined;
function r2() {
  if (storage) return storage;
  const account = Deno.env.get("R2_ACCOUNT_ID") ?? "";
  const bucket = Deno.env.get("R2_BUCKET") ?? "";
  const accessKeyId = Deno.env.get("R2_ACCESS_KEY_ID") ?? "";
  const secretAccessKey = Deno.env.get("R2_SECRET_ACCESS_KEY") ?? "";
  if (!/^[a-f0-9]{32}$/i.test(account) || !bucket || !accessKeyId || !secretAccessKey) {
    throw new ApiError(503, "storage_not_configured");
  }
  const endpoint = new URL(Deno.env.get("R2_ENDPOINT") || `https://${account}.r2.cloudflarestorage.com`);
  if (endpoint.protocol !== "https:" || endpoint.pathname !== "/" || endpoint.port ||
    endpoint.username || endpoint.password || endpoint.search || endpoint.hash ||
    !endpoint.hostname.startsWith(account.toLowerCase() + ".") ||
    !endpoint.hostname.endsWith(".r2.cloudflarestorage.com")) {
    throw new ApiError(503, "storage_not_configured");
  }
  const client = createR2Client(endpoint.origin, accessKeyId, secretAccessKey);
  storage = { client, bucket };
  return storage;
}

function stagingKey(row: Row): string {
  return "incoming/" + row.object_key.slice("music/".length);
}

function checked<T>(result: { data: T; error: unknown }): T {
  if (result.error) throw new ApiError(503, "database_unavailable");
  return result.data;
}

async function ownedRow(owner: string, id: unknown): Promise<Row> {
  if (typeof id !== "string" || !UUID.test(id)) throw new ApiError(400, "invalid_track");
  const row = checked(await admin.from(TABLE).select("*").eq("owner_id", owner).eq("id", id).maybeSingle());
  if (!row) throw new ApiError(404, "track_not_found");
  return row as Row;
}

function elapsed(timestamp: string | null): boolean {
  return timestamp === null || Date.parse(timestamp) <= Date.now();
}

async function begin(owner: string, data: Record<string, unknown>) {
  const size = data.size_bytes;
  const mime = data.mime_type;
  const title = typeof data.title === "string" ? data.title.trim() : "";
  const artist = typeof data.artist === "string" ? data.artist.trim() : "";
  const duration = data.duration_ms ?? 0;
  if (typeof size !== "number" || !Number.isInteger(size) || size < 1 || size > MAX_FILE_BYTES) {
    throw new ApiError(400, "invalid_size");
  }
  if (mime !== "audio/mpeg" && mime !== "audio/mp4") throw new ApiError(400, "invalid_type");
  if (!title || title.length > 200 || artist.length > 200 || typeof duration !== "number" ||
    !Number.isInteger(duration) || duration < 0 || duration > 86_400_000) {
    throw new ApiError(400, "invalid_metadata");
  }
  const { client, bucket } = r2();
  const result = await admin.rpc("reserve_private_track", {
    p_owner: owner, p_id: crypto.randomUUID(), p_title: title, p_artist: artist,
    p_duration: duration, p_size: size, p_mime: mime,
  });
  if (result.error?.message === "quota_exceeded" || result.error?.message === "too_many_uploads") {
    throw new ApiError(409, result.error.message);
  }
  const row = checked(result) as Row;
  try {
    const expires = Math.floor((Date.parse(row.upload_until) - Date.now()) / 1000);
    if (expires < 1) throw new ApiError(409, "upload_expired");
    const grant = await signUpload(client, bucket, stagingKey(row), size, mime, expires);
    return { id: row.id, upload_url: grant.url, headers: grant.headers };
  } catch (error) {
    // No URL was returned, so this reservation can be released immediately.
    await admin.from(TABLE).delete().eq("id", row.id).eq("state", "pending");
    throw error;
  }
}

function r2Status(error: unknown): number | undefined {
  return (error as { $metadata?: { httpStatusCode?: number } })?.$metadata?.httpStatusCode;
}

async function verify(row: Row): Promise<string> {
  const { client, bucket } = r2();
  const key = stagingKey(row);
  let head;
  try {
    head = await client.send(new HeadObjectCommand({ Bucket: bucket, Key: key }),
      { abortSignal: AbortSignal.timeout(10_000) });
  } catch (error) {
    if (r2Status(error) === 404) throw new ApiError(409, "upload_incomplete");
    throw error;
  }
  const etag = head.ETag;
  if (head.ContentLength !== row.size_bytes || head.ContentType !== row.mime_type || !etag) {
    throw new ApiError(422, "invalid_audio");
  }
  const ranges = new Map<number, Uint8Array>();
  async function chunk(offset: number) {
    const cached = ranges.get(offset);
    if (cached) return cached;
    if (ranges.size >= 16) throw new ApiError(422, "invalid_audio");
    const end = Math.min(offset + RANGE_SIZE, row.size_bytes) - 1;
    const response = await client.send(new GetObjectCommand({
      Bucket: bucket, Key: key, Range: `bytes=${offset}-${end}`, IfMatch: etag,
    }), { abortSignal: AbortSignal.timeout(6_000) });
    if (!response.Body || response.ContentRange !== `bytes ${offset}-${end}/${row.size_bytes}`) {
      await response.Body?.transformToWebStream().cancel();
      throw new ApiError(503, "storage_unavailable");
    }
    const bytes = await response.Body.transformToByteArray();
    if (bytes.length !== end - offset + 1) throw new ApiError(503, "storage_unavailable");
    ranges.set(offset, bytes);
    return bytes;
  }
  const valid = await isSupportedAudio(row.mime_type, row.size_bytes, async (offset, length) => {
    const first = Math.floor(offset / RANGE_SIZE) * RANGE_SIZE;
    const bytes = await chunk(first);
    const part = bytes.subarray(offset - first, offset - first + length);
    if (part.length === length) return part;
    const next = await chunk(first + RANGE_SIZE);
    const joined = new Uint8Array(length);
    joined.set(part);
    joined.set(next.subarray(0, length - part.length), part.length);
    return joined;
  });
  if (!valid) throw new ApiError(422, "invalid_audio");
  return etag;
}

async function finish(owner: string, id: unknown) {
  const row = await ownedRow(owner, id);
  if (row.state === "ready") return { id: row.id };
  if (row.state === "deleting" || elapsed(row.finish_until)) throw new ApiError(409, "upload_expired");
  if (row.state === "verifying" && !elapsed(row.verify_until)) throw new ApiError(409, "verification_busy");

  const token = crypto.randomUUID();
  let update = admin.from(TABLE).update({
    state: "verifying", verify_token: token, verify_until: new Date(Date.now() + VERIFY_LEASE_MS).toISOString(),
  }).eq("id", row.id).eq("owner_id", owner).eq("state", row.state);
  if (row.state === "verifying") update = update.eq("verify_token", row.verify_token!);
  if (!checked(await update.select("id").maybeSingle())) throw new ApiError(409, "verification_busy");

  let etag;
  try {
    etag = await verify(row);
  } catch (error) {
    const invalid = error instanceof ApiError && error.status === 422;
    checked(await admin.from(TABLE).update({
      state: invalid ? "deleting" : "pending", verify_token: null, verify_until: null,
    }).eq("id", row.id).eq("verify_token", token));
    throw error;
  }

  // Keep the lease after an ambiguous copy failure so cleanup can't race an in-flight copy.
  const { client, bucket } = r2();
  await client.send(new CopyObjectCommand({
    Bucket: bucket, Key: row.object_key, CopySource: `${bucket}/${stagingKey(row)}`,
    CopySourceIfMatch: etag, MetadataDirective: "REPLACE", ContentType: row.mime_type,
    CacheControl: "private, no-store",
  }), { abortSignal: AbortSignal.timeout(15_000) });
  const ready = checked(await admin.from(TABLE).update({
    state: "ready", verify_token: null, verify_until: null,
  }).eq("id", row.id).eq("owner_id", owner).eq("verify_token", token).select("id").maybeSingle());
  if (!ready) throw new ApiError(409, "upload_expired");
  return ready;
}

async function playback(owner: string, id: unknown) {
  const row = await ownedRow(owner, id);
  if (row.state !== "ready") throw new ApiError(404, "track_not_found");
  const { client, bucket } = r2();
  const url = await signPlayback(client, bucket, row.object_key, PLAY_SECONDS);
  return { url, expires_in: PLAY_SECONDS };
}

async function markDeleting(row: Row): Promise<Row | null> {
  if (row.state === "deleting") return row;
  if (row.state === "verifying" && !elapsed(row.verify_until)) return null;
  let update = admin.from(TABLE).update({ state: "deleting", verify_token: null, verify_until: null })
    .eq("id", row.id).eq("state", row.state);
  if (row.state === "verifying") update = update.eq("verify_token", row.verify_token!);
  return checked(await update.select("*").maybeSingle()) as Row | null;
}

async function removeObject(key: string) {
  const { client, bucket } = r2();
  await client.send(new DeleteObjectCommand({ Bucket: bucket, Key: key }), {
    abortSignal: AbortSignal.timeout(10_000),
  });
}

async function purge(row: Row) {
  await removeObject(row.object_key);
  // Leave the reservation and staged object in place while its write URL can still be used.
  if (!elapsed(row.upload_until)) return;
  await removeObject(stagingKey(row));
  checked(await admin.from(TABLE).delete().eq("id", row.id).eq("state", "deleting"));
}

async function remove(owner: string, id: unknown) {
  const row = await ownedRow(owner, id).catch((error) => {
    if (error instanceof ApiError && error.status === 404) return null;
    throw error;
  });
  if (row) {
    const deleting = await markDeleting(row);
    if (!deleting) throw new ApiError(409, "verification_busy");
    await purge(deleting);
  }
  return { deleted: true };
}

async function cleanup() {
  const now = new Date().toISOString();
  const candidates = checked(await admin.from(TABLE).select("*").or([
    `and(state.eq.pending,finish_until.lt.${now})`,
    `and(state.eq.verifying,finish_until.lt.${now},verify_until.lt.${now})`,
    `and(state.eq.deleting,upload_until.lt.${now})`,
    `and(state.eq.ready,staging_pending.eq.true,upload_until.lt.${now})`,
    "owner_id.is.null",
  ].join(",")).order("created_at").limit(20)) as Row[];
  let failed = 0;
  for (let offset = 0; offset < candidates.length; offset += 5) {
    const outcomes = await Promise.allSettled(candidates.slice(offset, offset + 5).map(async (row) => {
      if (row.state === "ready" && row.owner_id) {
        await removeObject(stagingKey(row));
        checked(await admin.from(TABLE).update({ staging_pending: false }).eq("id", row.id).eq("state", "ready"));
      } else {
        const deleting = await markDeleting(row);
        if (deleting) await purge(deleting);
      }
    }));
    failed += outcomes.filter((outcome) => outcome.status === "rejected").length;
  }
  if (failed) console.error("private-music cleanup failed rows:", failed);
  return { examined: candidates.length, failed };
}

async function body(request: Request): Promise<Record<string, unknown>> {
  const reader = request.body?.getReader();
  if (!reader) throw new ApiError(400, "invalid_request");
  const bytes = new Uint8Array(8192);
  let length = 0;
  for (;;) {
    const part = await reader.read();
    if (part.done) break;
    if (part.value.length > bytes.length - length) {
      await reader.cancel();
      throw new ApiError(413, "invalid_request");
    }
    bytes.set(part.value, length);
    length += part.value.length;
  }
  try {
    const result = JSON.parse(new TextDecoder().decode(bytes.subarray(0, length)));
    if (!result || typeof result !== "object" || Array.isArray(result)) throw new Error();
    return result;
  } catch {
    throw new ApiError(400, "invalid_request");
  }
}

function requireCleanupToken(request: Request) {
  const expected = Deno.env.get("R2_CLEANUP_TOKEN") ?? "";
  const supplied = request.headers.get("X-R2-Cleanup-Token") ?? "";
  const encoder = new TextEncoder();
  const left = encoder.encode(expected);
  const right = encoder.encode(supplied);
  if (left.length < 32 || left.length !== right.length || !timingSafeEqual(left, right)) {
    throw new ApiError(401, "unauthorized");
  }
}

Deno.serve(async (request) => {
  const headers = new Headers({ "Cache-Control": "no-store", Vary: "Origin" });
  const origin = Deno.env.get("R2_WEB_ORIGIN");
  if (origin && request.headers.get("Origin") === origin) {
    headers.set("Access-Control-Allow-Origin", origin);
    headers.set("Access-Control-Allow-Methods", "POST, OPTIONS");
    headers.set("Access-Control-Allow-Headers", "authorization, apikey, content-type, x-client-info");
  }
  if (request.method === "OPTIONS") return new Response(null, { status: 204, headers });
  try {
    if (request.method !== "POST") throw new ApiError(405, "invalid_request");
    if (Deno.env.get("R2_UPLOADS_ENABLED") !== "true") {
      throw new ApiError(503, "storage_not_configured");
    }
    const data = await body(request);
    if (data.action === "cleanup") {
      requireCleanupToken(request);
      return Response.json(await cleanup(), { headers });
    }
    const token = request.headers.get("Authorization")?.match(/^Bearer ([^ ]+)$/i)?.[1];
    if (!token) throw new ApiError(401, "unauthorized");
    const { data: jwt, error } = await auth.getClaims(token);
    const owner = jwt?.claims.sub;
    if (error || jwt?.claims.role !== "authenticated" || !owner || !UUID.test(owner)) {
      throw new ApiError(401, "unauthorized");
    }
    let result;
    switch (data.action) {
      case "begin": result = await begin(owner, data); break;
      case "finish": result = await finish(owner, data.id); break;
      case "play": result = await playback(owner, data.id); break;
      case "delete": result = await remove(owner, data.id); break;
      default: throw new ApiError(400, "invalid_request");
    }
    return Response.json(result, { headers });
  } catch (error) {
    const failure = error instanceof ApiError ? error : new ApiError(503, "storage_unavailable");
    return Response.json({ code: failure.code }, { status: failure.status, headers });
  }
});
