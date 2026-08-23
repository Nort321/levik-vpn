import { describe, expect, it, vi } from "vitest";
import type { MobileIntegrityVerdict } from "@/lib/server/mobile-integrity";

vi.mock("server-only", () => ({}));

const mocks = vi.hoisted(() => ({
  environment: {
    MOBILE_PLAY_INTEGRITY_REQUIRED: false,
    MOBILE_ANDROID_PACKAGE_NAME: "com.leviknet.vpn",
    mobileAndroidCertificateDigests: new Set<string>(),
  },
  verify: vi.fn<
    (input: {
      integrityToken: string;
      expectedRequestHash: string;
      expectedPackageName: string;
    }) => Promise<MobileIntegrityVerdict>
  >(),
}));

vi.mock("@/lib/server/env", () => ({
  getEnvironment: () => mocks.environment,
}));

import {
  assertMobileAppIntegrity,
  mobileIntegrityVerdictAccepted,
  registerMobileIntegrityVerifier,
} from "@/lib/server/mobile-integrity";

const NOW = Date.parse("2026-07-29T18:00:00.000Z");
const CERTIFICATE = Buffer.alloc(32, 41).toString("base64url");
const BASE_VERDICT = {
  requestHash: "expected-request-hash",
  requestPackageName: "com.leviknet.vpn",
  appPackageName: "com.leviknet.vpn",
  certificateSha256Digests: [CERTIFICATE],
  appRecognized: true,
  licensed: true,
  meetsDeviceIntegrity: true,
  evaluatedAt: new Date(NOW),
} satisfies MobileIntegrityVerdict;

registerMobileIntegrityVerifier({ verify: mocks.verify });

function integrityInput(token?: string) {
  return {
    headers: new Headers(token ? { "x-levik-integrity": token } : {}),
    method: "POST",
    path: "/api/mobile/v1/auth/challenge",
    proof: {
      deviceId: "device-0123456789",
      timestamp: "1785348000",
      nonce: "nonce-0123456789",
      signature: "signature",
    },
    accessToken: "",
    body: Buffer.from("{}"),
  };
}

function accepted(
  verdict: MobileIntegrityVerdict = BASE_VERDICT,
): boolean {
  return mobileIntegrityVerdictAccepted({
    verdict,
    expectedRequestHash: "expected-request-hash",
    expectedPackageName: "com.leviknet.vpn",
    allowedCertificateDigests: new Set([CERTIFICATE]),
    now: NOW,
  });
}

describe("mobile integrity verdict policy", () => {
  it("accepts only the fully bound recognized verdict", () => {
    expect(accepted()).toBe(true);
  });

  it.each([
    ["request hash", { requestHash: "other" }],
    ["request package", { requestPackageName: "com.attacker.app" }],
    ["app package", { appPackageName: "com.attacker.app" }],
    ["certificate", { certificateSha256Digests: [] }],
    ["app recognition", { appRecognized: false }],
    ["licensing", { licensed: false }],
    ["device integrity", { meetsDeviceIntegrity: false }],
  ] satisfies Array<
    [string, Partial<MobileIntegrityVerdict>]
  >)("rejects a mismatched %s", (_name, change) => {
    expect(accepted({ ...BASE_VERDICT, ...change })).toBe(false);
  });

  it("rejects stale and future-dated verdicts outside the window", () => {
    expect(
      accepted({
        ...BASE_VERDICT,
        evaluatedAt: new Date(NOW - 2 * 60 * 1_000 - 1),
      }),
    ).toBe(false);
    expect(
      accepted({
        ...BASE_VERDICT,
        evaluatedAt: new Date(NOW + 2 * 60 * 1_000 + 1),
      }),
    ).toBe(false);
  });
});

describe("mobile integrity token presence policy", () => {
  it("allows an absent token in optional mode", async () => {
    mocks.environment.MOBILE_PLAY_INTEGRITY_REQUIRED = false;
    mocks.verify.mockReset();

    await expect(assertMobileAppIntegrity(integrityInput())).resolves.toBeUndefined();
    expect(mocks.verify).not.toHaveBeenCalled();
  });

  it("verifies a provided token even in optional mode", async () => {
    mocks.environment.MOBILE_PLAY_INTEGRITY_REQUIRED = false;
    mocks.environment.mobileAndroidCertificateDigests = new Set([CERTIFICATE]);
    mocks.verify.mockReset();
    mocks.verify.mockImplementationOnce(
      ({ expectedRequestHash, expectedPackageName }) => Promise.resolve({
        ...BASE_VERDICT,
        requestHash: expectedRequestHash,
        requestPackageName: expectedPackageName,
        appPackageName: expectedPackageName,
        evaluatedAt: new Date(),
      }),
    );

    await expect(
      assertMobileAppIntegrity(integrityInput("valid.integrity_token-1")),
    ).resolves.toBeUndefined();
    expect(mocks.verify).toHaveBeenCalledOnce();
  });

  it("rejects an invalid provided token in optional mode", async () => {
    mocks.environment.MOBILE_PLAY_INTEGRITY_REQUIRED = false;
    mocks.environment.mobileAndroidCertificateDigests = new Set([CERTIFICATE]);
    mocks.verify.mockReset();
    mocks.verify.mockImplementationOnce(
      ({ expectedRequestHash, expectedPackageName }) => Promise.resolve({
        ...BASE_VERDICT,
        requestHash: expectedRequestHash,
        requestPackageName: expectedPackageName,
        appPackageName: expectedPackageName,
        appRecognized: false,
        evaluatedAt: new Date(),
      }),
    );

    await expect(
      assertMobileAppIntegrity(integrityInput("invalid.integrity_token-1")),
    ).rejects.toMatchObject({ code: "integrity_rejected", status: 403 });
  });

  it("rejects an absent token in required mode", async () => {
    mocks.environment.MOBILE_PLAY_INTEGRITY_REQUIRED = true;
    mocks.verify.mockReset();

    await expect(assertMobileAppIntegrity(integrityInput())).rejects.toMatchObject({
      code: "integrity_required",
      status: 401,
    });
    expect(mocks.verify).not.toHaveBeenCalled();
  });
});
