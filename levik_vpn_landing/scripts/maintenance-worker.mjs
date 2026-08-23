import process from "node:process";

const INTERVAL_MILLISECONDS = 60_000;
const REQUEST_TIMEOUT_MILLISECONDS = 45_000;
const MAINTENANCE_URL =
  "http://app:3000/api/internal/maintenance";

const token = process.env.MAINTENANCE_TOKEN;
if (
  !token ||
  !/^[A-Za-z0-9_-]{43}$/.test(token) ||
  Buffer.from(token, "base64url").byteLength !== 32
) {
  throw new Error(
    "MAINTENANCE_TOKEN must be an unpadded 32-byte base64url secret",
  );
}

async function runCycle() {
  try {
    const response = await fetch(MAINTENANCE_URL, {
      method: "POST",
      headers: {
        Authorization: `Bearer ${token}`,
        "Content-Length": "0",
        "X-Forwarded-Host": "leviknet.com",
      },
      body: null,
      cache: "no-store",
      credentials: "omit",
      redirect: "error",
      signal: AbortSignal.timeout(REQUEST_TIMEOUT_MILLISECONDS),
    });
    await response.body?.cancel();
    if (!response.ok) {
      console.error("Maintenance request failed", {
        status: response.status,
      });
    }
  } catch (error) {
    console.error("Maintenance request could not be completed", {
      errorName: error instanceof Error ? error.name : "UnknownError",
    });
  } finally {
    setTimeout(runCycle, INTERVAL_MILLISECONDS);
  }
}

await runCycle();
