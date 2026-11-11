import { GetObjectCommand, PutObjectCommand, S3Client } from "@aws-sdk/client-s3";
import { getSignedUrl } from "@aws-sdk/s3-request-presigner";

export function createR2Client(endpoint: string, accessKeyId: string, secretAccessKey: string) {
  return new S3Client({
    region: "auto",
    endpoint,
    forcePathStyle: true,
    credentials: { accessKeyId, secretAccessKey },
    requestChecksumCalculation: "WHEN_REQUIRED",
    responseChecksumValidation: "WHEN_REQUIRED",
    defaultUserAgentProvider: () => Promise.resolve([["masheeds", "1.3"]]),
    maxAttempts: 2,
  });
}

export async function signUpload(
  client: S3Client, bucket: string, key: string, size: number, mime: string, expiresIn: number,
) {
  const names = ["content-length", "content-type", "if-none-match"];
  const url = await getSignedUrl(client, new PutObjectCommand({
    Bucket: bucket, Key: key, ContentLength: size, ContentType: mime, IfNoneMatch: "*",
  }), { expiresIn, signableHeaders: new Set(names) });
  const signed = new URL(url).searchParams.get("X-Amz-SignedHeaders")?.split(";") ?? [];
  if (!names.every((name) => signed.includes(name))) {
    throw new Error("Upload URL does not bind the required headers");
  }
  return { url, headers: { "Content-Length": String(size), "Content-Type": mime, "If-None-Match": "*" } };
}

export function signPlayback(client: S3Client, bucket: string, key: string, expiresIn: number) {
  // An unsigned Range header lets clients seek with the same temporary URL.
  return getSignedUrl(client, new GetObjectCommand({ Bucket: bucket, Key: key }), { expiresIn });
}
