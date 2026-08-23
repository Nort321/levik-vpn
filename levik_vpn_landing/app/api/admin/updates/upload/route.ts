import { NextResponse } from "next/server";
import { createHash } from "node:crypto";
import { mkdir, rename, copyFile, open, rm, stat, writeFile } from "node:fs/promises";
import { createReadStream, createWriteStream } from "node:fs";
import { existsSync } from "node:fs";
import path from "node:path";
import { Readable } from "node:stream";
import { pipeline } from "node:stream/promises";
import type { ReadableStream as NodeWebReadableStream } from "node:stream/web";
import { requireSession } from "@/lib/server/browser-auth";
import { createAppUpdate, isAdminUser } from "@/lib/server/app-updates";

export const dynamic = "force-dynamic";

// Allow up to 15 minutes for large APK uploads
export const maxDuration = 900;

const MAX_APK_BYTES = 350 * 1024 * 1024;
const MAX_CHUNK_BYTES = 8 * 1024 * 1024;
const MAX_METADATA_HEADER_CHARS = 24_000;

type UploadMetadata = {
  versionCode?: unknown;
  versionName?: unknown;
  minSupportedVersionCode?: unknown;
  changelogRu?: unknown;
  changelogEn?: unknown;
  forceUpdate?: unknown;
  totalSize?: unknown;
};

function readUploadMetadata(request: Request): UploadMetadata | null {
  const encoded = request.headers.get("x-levik-update-metadata");
  if (
    !encoded ||
    encoded.length > MAX_METADATA_HEADER_CHARS ||
    !/^[A-Za-z0-9_-]+$/.test(encoded)
  ) {
    return null;
  }

  try {
    const parsed: unknown = JSON.parse(
      Buffer.from(encoded, "base64url").toString("utf8"),
    );
    return parsed && typeof parsed === "object" ? parsed : null;
  } catch {
    return null;
  }
}

export async function POST(request: Request) {
  let session: Awaited<ReturnType<typeof requireSession>>;
  try {
    session = await requireSession();
  } catch {
    // requireSession redirects unauthenticated visitors; respond with JSON for the API client.
    return NextResponse.json({ ok: false, error: "Authentication required. Please sign in again." }, { status: 401 });
  }
  try {
    if (!isAdminUser(session.userKey)) {
      return NextResponse.json({ ok: false, error: "Access denied. Administrator privileges required." }, { status: 403 });
    }

    if (request.headers.get("content-type") !== "application/vnd.android.package-archive") {
      return NextResponse.json({ ok: false, error: "APK content type is required" }, { status: 415 });
    }

    const contentLength = Number(request.headers.get("content-length"));
    if (!Number.isSafeInteger(contentLength) || contentLength <= 0) {
      return NextResponse.json({ ok: false, error: "APK content length is required" }, { status: 411 });
    }
    if (contentLength > MAX_CHUNK_BYTES) {
      return NextResponse.json({ ok: false, error: "Upload chunk exceeds the 8 MB limit" }, { status: 413 });
    }
    if (!request.body) {
      return NextResponse.json({ ok: false, error: "APK file is required" }, { status: 400 });
    }

    const metadata = readUploadMetadata(request);
    if (!metadata) {
      return NextResponse.json({ ok: false, error: "Valid upload metadata is required" }, { status: 400 });
    }

    const versionCode = Number(metadata.versionCode);
    if (!Number.isSafeInteger(versionCode) || versionCode <= 0) {
      return NextResponse.json({ ok: false, error: "Valid integer Version Code is required (e.g. 12)" }, { status: 400 });
    }

    const versionName = typeof metadata.versionName === "string" ? metadata.versionName.trim() : "";
    if (!versionName) {
      return NextResponse.json({ ok: false, error: "Version Name is required (e.g. 1.3.1)" }, { status: 400 });
    }

    const changelogRu = typeof metadata.changelogRu === "string" ? metadata.changelogRu.trim() : "";
    if (!changelogRu || !changelogRu.trim()) {
      return NextResponse.json({ ok: false, error: "Russian changelog is required" }, { status: 400 });
    }

    const changelogEn = typeof metadata.changelogEn === "string" ? metadata.changelogEn.trim() : "";
    const parsedMinVersionCode = Number(metadata.minSupportedVersionCode);
    const minSupportedVersionCode = Number.isSafeInteger(parsedMinVersionCode) && parsedMinVersionCode > 0
      ? parsedMinVersionCode
      : 1;
    const forceUpdate = metadata.forceUpdate === true;
    const totalSize = Number(metadata.totalSize);
    if (!Number.isSafeInteger(totalSize) || totalSize <= 0 || totalSize > MAX_APK_BYTES) {
      return NextResponse.json({ ok: false, error: "Valid APK total size is required" }, { status: 400 });
    }

    const uploadId = request.headers.get("x-levik-upload-id") || "";
    if (!/^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/.test(uploadId)) {
      return NextResponse.json({ ok: false, error: "Valid upload ID is required" }, { status: 400 });
    }
    const chunkOffset = Number(request.headers.get("x-levik-chunk-offset"));
    if (
      !Number.isSafeInteger(chunkOffset) ||
      chunkOffset < 0 ||
      chunkOffset >= totalSize ||
      contentLength > totalSize - chunkOffset
    ) {
      return NextResponse.json({ ok: false, error: "Invalid upload chunk range" }, { status: 400 });
    }

    // Stream the APK to disk while computing SHA-256 and size on the fly.
    // This avoids buffering a second full copy of the APK in memory.
    const cleanVersionName = versionName.replace(/[^a-zA-Z0-9._-]/g, "_");
    const fileName = `LevikVPN-${cleanVersionName}.apk`;

    const downloadsDir = "/var/lib/leviknet/downloads";
    try {
      if (!existsSync(downloadsDir)) {
        await mkdir(downloadsDir, { recursive: true });
      }
    } catch (err) {
      console.warn(`Could not ensure downloads dir ${downloadsDir}:`, err);
      return NextResponse.json({ ok: false, error: "Downloads storage is unavailable" }, { status: 500 });
    }

    const finalPath = path.join(downloadsDir, fileName);
    const tempPath = path.join(downloadsDir, `.upload-${uploadId}.part`);

    try {
      if (chunkOffset === 0) {
        await writeFile(tempPath, Buffer.alloc(0));
      } else {
        const currentSize = (await stat(tempPath)).size;
        if (currentSize < chunkOffset) {
          return NextResponse.json(
            { ok: false, error: "Upload offset is ahead of stored data", nextOffset: currentSize },
            { status: 409 },
          );
        }
        const fileHandle = await open(tempPath, "r+");
        try {
          await fileHandle.truncate(chunkOffset);
        } finally {
          await fileHandle.close();
        }
      }

      await pipeline(
        Readable.fromWeb(request.body as unknown as NodeWebReadableStream<Uint8Array>),
        createWriteStream(tempPath, { flags: "r+", start: chunkOffset }),
      );

      const nextOffset = chunkOffset + contentLength;
      const storedSize = (await stat(tempPath)).size;
      if (storedSize !== nextOffset) {
        throw new Error("Upload chunk was incomplete");
      }

      if (nextOffset < totalSize) {
        return NextResponse.json({ ok: true, complete: false, nextOffset });
      }
    } catch (err) {
      console.error("Failed to store APK upload chunk:", err);
      return NextResponse.json({ ok: false, error: "Failed to store APK upload chunk" }, { status: 500 });
    }

    const apkFile = await open(tempPath, "r");
    const apkSignature = Buffer.alloc(4);
    try {
      const { bytesRead } = await apkFile.read(apkSignature, 0, apkSignature.length, 0);
      if (bytesRead !== 4 || !apkSignature.equals(Buffer.from([0x50, 0x4b, 0x03, 0x04]))) {
        await rm(tempPath, { force: true });
        return NextResponse.json({ ok: false, error: "Uploaded file is not an APK archive" }, { status: 400 });
      }
    } finally {
      await apkFile.close();
    }

    const sha256 = createHash("sha256");
    let fileSize = 0;
    for await (const chunk of createReadStream(tempPath)) {
      const buffer = Buffer.from(chunk);
      sha256.update(buffer);
      fileSize += buffer.length;
    }
    if (fileSize !== totalSize) {
      await rm(tempPath, { force: true });
      return NextResponse.json({ ok: false, error: "Completed APK size does not match" }, { status: 400 });
    }
    const fileSha256 = sha256.digest("hex");
    await rename(tempPath, finalPath);

    // Best-effort copy into public/downloads for local dev / direct static serving.
    const publicDownloadsDir = path.join(process.cwd(), "public", "downloads");
    try {
      if (!existsSync(publicDownloadsDir)) {
        await mkdir(publicDownloadsDir, { recursive: true });
      }
      await copyFile(finalPath, path.join(publicDownloadsDir, fileName));
    } catch {
      // ignore — persistent volume is the canonical storage
    }

    // Keep a small manifest next to the APK for debugging/audit purposes.
    try {
      await writeFile(
        path.join(downloadsDir, `${fileName}.sha256`),
        `${fileSha256}  ${fileName}\n`,
      );
    } catch {
      // ignore
    }

    const downloadUrl = `https://leviknet.com/downloads/${fileName}`;

    const update = await createAppUpdate({
      versionCode,
      versionName,
      minSupportedVersionCode,
      fileName,
      downloadUrl,
      fileSize,
      sha256: fileSha256,
      changelogRu,
      changelogEn: changelogEn || null,
      forceUpdate,
      createdByUserKey: session.userKey,
    });

    return NextResponse.json({
      ok: true,
      complete: true,
      nextOffset: totalSize,
      message: `Версия ${versionName} (код ${versionCode}) успешно опубликована!`,
      update,
    });
  } catch (err: unknown) {
    console.error("Failed to upload and publish APK release:", err);
    const msg = err instanceof Error ? err.message : "Internal Server Error";
    return NextResponse.json({ ok: false, error: msg }, { status: 500 });
  }
}
