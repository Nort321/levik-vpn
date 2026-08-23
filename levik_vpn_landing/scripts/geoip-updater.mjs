import { createGunzip } from "node:zlib";
import {
  access,
  mkdir,
  rename,
  stat,
  unlink,
} from "node:fs/promises";
import { createWriteStream } from "node:fs";
import path from "node:path";
import process from "node:process";
import { Readable, Transform } from "node:stream";
import { pipeline } from "node:stream/promises";

const DATABASE_DIRECTORY = "/var/lib/leviknet/geoip";
const CHECK_INTERVAL_MILLISECONDS = 6 * 60 * 60_000;
const FRESHNESS_MILLISECONDS = 24 * 60 * 60_000;
const MAX_DATABASE_AGE_MILLISECONDS = 45 * 24 * 60 * 60_000;
const MAX_EXPANDED_BYTES = 256 * 1_024 * 1_024;
const DOWNLOAD_TIMEOUT_MILLISECONDS = 120_000;

const databases = [
  {
    remoteName: "dbip-city-lite",
    localName: "dbip-city-lite.mmdb",
    minimumBytes: 20 * 1_024 * 1_024,
  },
  {
    remoteName: "dbip-asn-lite",
    localName: "dbip-asn-lite.mmdb",
    minimumBytes: 1 * 1_024 * 1_024,
  },
];

function releaseCandidates(now = new Date()) {
  return Array.from({ length: 3 }, (_, offset) => {
    const date = new Date(Date.UTC(now.getUTCFullYear(), now.getUTCMonth() - offset, 1));
    return `${date.getUTCFullYear()}-${String(date.getUTCMonth() + 1).padStart(2, "0")}`;
  });
}

async function databaseIsHealthy(database, maximumAge) {
  try {
    const metadata = await stat(path.join(DATABASE_DIRECTORY, database.localName));
    return (
      metadata.isFile() &&
      metadata.size >= database.minimumBytes &&
      Date.now() - metadata.mtimeMs <= maximumAge
    );
  } catch {
    return false;
  }
}

async function assertHealthyDatabases() {
  const health = await Promise.all(
    databases.map((database) =>
      databaseIsHealthy(database, MAX_DATABASE_AGE_MILLISECONDS),
    ),
  );
  if (health.some((healthy) => !healthy)) {
    throw new Error("GeoIP databases are unavailable or stale");
  }
}

function byteLimit(maximumBytes) {
  let received = 0;
  return new Transform({
    transform(chunk, _encoding, callback) {
      received += chunk.length;
      if (received > maximumBytes) {
        callback(new Error("Expanded GeoIP database exceeds the size limit"));
        return;
      }
      callback(null, chunk);
    },
  });
}

async function downloadDatabase(database) {
  let lastStatus = 0;
  for (const release of releaseCandidates()) {
    const url =
      `https://download.db-ip.com/free/${database.remoteName}-${release}.mmdb.gz`;
    const response = await fetch(url, {
      headers: {
        Accept: "application/gzip, application/octet-stream",
        "User-Agent": "LevikNet-GeoIP-Updater/1.0",
      },
      redirect: "error",
      signal: AbortSignal.timeout(DOWNLOAD_TIMEOUT_MILLISECONDS),
    });
    lastStatus = response.status;
    if (response.status === 404) {
      await response.body?.cancel();
      continue;
    }
    if (!response.ok || !response.body) {
      await response.body?.cancel();
      throw new Error(`GeoIP download failed with status ${response.status}`);
    }

    const destination = path.join(DATABASE_DIRECTORY, database.localName);
    const temporary = `${destination}.${process.pid}.tmp`;
    try {
      await pipeline(
        Readable.fromWeb(response.body),
        createGunzip(),
        byteLimit(MAX_EXPANDED_BYTES),
        createWriteStream(temporary, {
          flags: "wx",
          mode: 0o644,
          flush: true,
        }),
      );
      const metadata = await stat(temporary);
      if (metadata.size < database.minimumBytes) {
        throw new Error("Downloaded GeoIP database is unexpectedly small");
      }
      await rename(temporary, destination);
      return;
    } catch (error) {
      await unlink(temporary).catch(() => {});
      throw error;
    }
  }
  throw new Error(`No current GeoIP release was found (last status ${lastStatus})`);
}

async function updateDatabases() {
  await mkdir(DATABASE_DIRECTORY, { recursive: true });
  for (const database of databases) {
    if (await databaseIsHealthy(database, FRESHNESS_MILLISECONDS)) continue;
    await downloadDatabase(database);
  }
  await assertHealthyDatabases();
}

async function run() {
  if (process.argv[2] === "--healthcheck") {
    await access(DATABASE_DIRECTORY);
    await assertHealthyDatabases();
    return;
  }

  try {
    await updateDatabases();
  } catch (error) {
    console.error("GeoIP update failed", {
      errorName: error instanceof Error ? error.name : "UnknownError",
    });
  } finally {
    setTimeout(run, CHECK_INTERVAL_MILLISECONDS);
  }
}

await run();
