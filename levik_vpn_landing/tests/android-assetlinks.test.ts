import { beforeEach, describe, expect, it, vi } from "vitest";

vi.mock("server-only", () => ({}));

const environment = vi.hoisted(() => ({
  MOBILE_ANDROID_PACKAGE_NAME: "com.leviknet.vpn",
  mobileAndroidCertificateDigests: new Set<string>(),
  BRIDGE_HMAC_SECRET: "must-not-leak",
}));

vi.mock("@/lib/server/env", () => ({ getEnvironment: () => environment }));

import { GET } from "@/app/.well-known/assetlinks.json/route";

describe("Android Asset Links", () => {
  beforeEach(() => {
    environment.mobileAndroidCertificateDigests.clear();
  });

  it("publishes the package and colon-separated SHA-256 fingerprint", async () => {
    environment.mobileAndroidCertificateDigests.add(
      Buffer.alloc(32, 0xc1).toString("base64url"),
    );
    const response = GET();
    const body: unknown = await response.json();

    expect(response.status).toBe(200);
    expect(response.headers.get("cache-control")).toContain("max-age=3600");
    expect(response.headers.get("content-type")).toMatch(/^application\/json/);
    expect(body).toEqual([
      {
        relation: ["delegate_permission/common.handle_all_urls"],
        target: {
          namespace: "android_app",
          package_name: "com.leviknet.vpn",
          sha256_cert_fingerprints: [Array(32).fill("C1").join(":")],
        },
      },
    ]);
    expect(JSON.stringify(body)).not.toContain("must-not-leak");
    expect(JSON.stringify(body)).not.toContain("BRIDGE_HMAC_SECRET");
  });

  it("fails closed without publishing an empty relation", async () => {
    const response = GET();
    expect(response.status).toBe(503);
    await expect(response.json()).resolves.toEqual([]);
    expect(response.headers.get("cache-control")).toContain("max-age=60");
  });
});
