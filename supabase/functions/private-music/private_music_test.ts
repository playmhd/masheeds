import { strict as assert } from "node:assert";
import { isSupportedAudio } from "./audio.ts";
import { createR2Client, signPlayback, signUpload } from "./presign.ts";

const bytes = (...parts: Uint8Array[]) => Uint8Array.from(parts.flatMap((part) => [...part]));
const text = (value: string) => new TextEncoder().encode(value);
const probe = (mime: string, file: Uint8Array) =>
  isSupportedAudio(mime, file.length, (offset, length) => Promise.resolve(file.subarray(offset, offset + length)));

function box(kind: string, ...parts: Uint8Array[]) {
  const header = new Uint8Array(8);
  new DataView(header.buffer).setUint32(0, 8 + parts.reduce((sum, part) => sum + part.length, 0));
  header.set(text(kind), 4);
  return bytes(header, ...parts);
}

function track(kind: string) {
  return box("trak", box("mdia", box("hdlr", new Uint8Array(8), text(kind))));
}

Deno.test("MP3 probe accepts consecutive MPEG frames after ID3 and rejects a false signature", async () => {
  const frame = new Uint8Array(417);
  frame.set([0xff, 0xfb, 0x90, 0x00]);
  const id3 = bytes(text("ID3"), Uint8Array.of(3, 0, 0, 0, 0, 0, 4), new Uint8Array(4));
  assert.equal(await probe("audio/mpeg", bytes(id3, frame, frame)), true);
  assert.equal(await probe("audio/mpeg", bytes(id3, frame)), false);
  assert.equal(await probe("audio/mpeg", bytes(id3, text("arbitrary content"))), false);
});

Deno.test("M4A probe accepts audio metadata after media data and rejects video and broken boxes", async () => {
  const ftyp = box("ftyp", text("M4A "), new Uint8Array(4));
  const media = box("mdat", new Uint8Array(20));
  assert.equal(await probe("audio/mp4", bytes(ftyp, media, box("moov", track("soun")))), true);
  assert.equal(await probe("audio/mp4", bytes(ftyp, media, box("moov", track("soun"), track("vide")))), false);
  assert.equal(await probe("audio/mp4", bytes(ftyp, media, box("moov", track("soun")).subarray(0, 12))), false);
  assert.equal(await probe("audio/mp4", bytes(ftyp, media)), false);
});

Deno.test("real AWS presigner binds upload size and type and leaves playback Range open", async () => {
  const client = createR2Client("https://example.r2.cloudflarestorage.com", "test-access-key", "test-secret-not-real");
  const upload = await signUpload(client, "private", "incoming/owner/track", 100, "audio/mpeg", 300);
  const put = new URL(upload.url);
  assert.equal(put.pathname, "/private/incoming/owner/track");
  assert.equal(put.searchParams.get("X-Amz-Expires"), "300");
  assert.equal(put.searchParams.get("X-Amz-SignedHeaders"), "content-length;content-type;host;if-none-match");
  assert.equal(upload.headers["If-None-Match"], "*");
  const get = new URL(await signPlayback(client, "private", "music/owner/track", 3600));
  assert.equal(get.pathname, "/private/music/owner/track");
  assert.equal(get.searchParams.get("X-Amz-Expires"), "3600");
  assert.equal(get.searchParams.get("X-Amz-SignedHeaders"), "host");
  assert.notEqual(put.searchParams.get("X-Amz-Signature"), get.searchParams.get("X-Amz-Signature"));
  client.destroy();
});
