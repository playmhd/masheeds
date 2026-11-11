export type ReadRange = (offset: number, length: number) => Promise<Uint8Array>;

const mpeg1Rates = [0, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320];
const mpeg2Rates = [0, 8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160];
const sampleRates = [44100, 48000, 32000];

function mp3Frame(bytes: Uint8Array, offset: number): number {
  if (bytes.length - offset < 4) return 0;
  const [a, b, c] = bytes.subarray(offset, offset + 3);
  const version = (b >> 3) & 3;
  if (a !== 255 || (b & 0xe0) !== 0xe0 || version === 1 || ((b >> 1) & 3) !== 1) return 0;
  const bitrate = (version === 3 ? mpeg1Rates : mpeg2Rates)[c >> 4];
  const samples = sampleRates[(c >> 2) & 3] / (version === 3 ? 1 : version === 2 ? 2 : 4);
  if (!bitrate || !samples) return 0;
  return Math.floor((version === 3 ? 144000 : 72000) * bitrate / samples) + ((c >> 1) & 1);
}

async function isMp3(size: number, read: ReadRange): Promise<boolean> {
  const header = await read(0, Math.min(size, 10));
  let start = 0;
  if (header.length === 10 && ascii(header, 0, 3) === "ID3") {
    if (header[3] < 2 || header[3] > 4 || header.subarray(6).some((byte) => byte > 127)) return false;
    const tagSize = header.subarray(6).reduce((value, byte) => value * 128 + byte, 0);
    start = 10 + tagSize + (header[3] === 4 && (header[5] & 0x10) !== 0 ? 10 : 0);
  }
  if (start > size - 8) return false;
  const window = await read(start, Math.min(6144, size - start));
  for (let offset = 0; offset + 4 <= Math.min(window.length, 4096); offset++) {
    const frame = mp3Frame(window, offset);
    if (!frame || offset + frame + 4 > window.length) continue;
    const next = mp3Frame(window, offset + frame);
    if (next && start + offset + frame + next <= size) return true;
  }
  return false;
}

function ascii(bytes: Uint8Array, start: number, length: number): string {
  return String.fromCharCode(...bytes.subarray(start, start + length));
}

type Box = { kind: string; body: number; end: number };

async function isM4a(size: number, read: ReadRange): Promise<boolean> {
  let remainingBoxes = 512;
  async function* boxes(start: number, end: number): AsyncGenerator<Box> {
    while (start < end) {
      if (--remainingBoxes < 0 || end - start < 8) throw new InvalidBoxes();
      const bytes = await read(start, Math.min(16, end - start));
      const data = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength);
      let length = data.getUint32(0);
      let header = 8;
      if (length === 1) {
        if (bytes.length < 16 || data.getUint32(8) !== 0) throw new InvalidBoxes();
        length = data.getUint32(12);
        header = 16;
      } else if (length === 0) {
        length = end - start;
      }
      if (length < header || start + length > end) throw new InvalidBoxes();
      yield { kind: ascii(bytes, 4, 4), body: start + header, end: start + length };
      start += length;
    }
  }

  let audio = false;
  let fileType = false;
  let media = false;
  try {
    for await (const root of boxes(0, size)) {
      if (root.kind === "ftyp" && root.end - root.body >= 8) fileType = true;
      if (root.kind === "mdat" && root.end > root.body) media = true;
      if (root.kind !== "moov") continue;
      for await (const track of boxes(root.body, root.end)) {
        if (track.kind !== "trak") continue;
        for await (const container of boxes(track.body, track.end)) {
          if (container.kind !== "mdia") continue;
          for await (const handler of boxes(container.body, container.end)) {
            if (handler.kind !== "hdlr" || handler.end - handler.body < 12) continue;
            const type = ascii(await read(handler.body + 8, 4), 0, 4);
            if (type === "vide") return false;
            if (type === "soun") audio = true;
          }
        }
      }
    }
  } catch (error) {
    if (error instanceof InvalidBoxes) return false;
    throw error;
  }
  return fileType && media && audio;
}

class InvalidBoxes extends Error {}

export async function isSupportedAudio(mime: string, size: number, read: ReadRange): Promise<boolean> {
  if (size < 12) return false;
  if (mime === "audio/mpeg") return await isMp3(size, read);
  if (mime === "audio/mp4") return await isM4a(size, read);
  return false;
}
