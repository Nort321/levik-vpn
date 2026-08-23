import { beforeEach, describe, expect, it, vi } from "vitest";

vi.mock("server-only", () => ({}));

const mocks = vi.hoisted(() => ({
  beginDeviceLogin: vi.fn(),
  createLocalAccountLoginAttempt: vi.fn(),
  createAccountActivation: vi.fn(),
  bindMobileLogin: vi.fn(),
  authenticateMobileRegistration: vi.fn(),
  readMobileJson: vi.fn(),
}));

vi.mock("@/lib/server/account/activation", () => ({
  createAccountActivation: mocks.createAccountActivation,
}));
vi.mock("@/lib/server/login-service", () => ({
  beginDeviceLogin: mocks.beginDeviceLogin,
}));
vi.mock("@/lib/server/mobile-auth", () => ({
  bindMobileLogin: mocks.bindMobileLogin,
  authenticateMobileRegistration: mocks.authenticateMobileRegistration,
}));
vi.mock("@/lib/server/mobile-integrity", () => ({
  assertMobileAppIntegrity: vi.fn(),
}));
vi.mock("@/lib/server/mobile-api", () => ({
  assertMobileRequestTarget: vi.fn(),
  mobileChallengeSchema: {},
  MOBILE_NO_STORE_HEADERS: { "Cache-Control": "no-store" },
  mobileRequestProof: vi.fn(() => ({ timestamp: 1 })),
  readMobileJson: mocks.readMobileJson,
  MobileApiError: class MobileApiError extends Error {},
}));
vi.mock("@/lib/server/mobile-route", () => ({
  mobileErrorResponse: vi.fn(() => Response.json({ ok: false }, { status: 500 })),
}));
vi.mock("@/lib/server/rate-limit", () => ({
  consumeRateLimit: vi.fn(() => Promise.resolve({ allowed: true })),
}));
vi.mock("@/lib/server/security", () => ({
  clientAddressFromHeaders: vi.fn(() => "127.0.0.1"),
}));
vi.mock("@/lib/server/session-store", () => ({
  createLocalAccountLoginAttempt: mocks.createLocalAccountLoginAttempt,
}));

import { POST } from "@/app/api/mobile/v1/auth/challenge/route";

const baseValue = {
  publicKeySpki: "key",
  deviceLabel: "Pixel",
  deviceModel: "Pixel 9",
  deviceOs: "Android 16",
  appVersion: "2.0.0",
  requestSigningAlgorithm: "PS256",
  profileEncryptionAlgorithm: "RSA-OAEP-256+A256GCM",
};

describe("mobile account activation challenge", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    mocks.authenticateMobileRegistration.mockResolvedValue({
      deviceId: "device-0123456789",
    });
    mocks.createAccountActivation.mockResolvedValue({
      code: "ABCD-EFGH-JKMN-PQRS",
      uri: "https://leviknet.com/activate?code=ABCD-EFGH-JKMN-PQRS",
    });
  });

  it("does not call the Telegram bridge for an account-capable client", async () => {
    mocks.readMobileJson.mockResolvedValue({
      body: "{}",
      value: { ...baseValue, accountActivationSupported: true },
    });
    mocks.createLocalAccountLoginAttempt.mockResolvedValue({
      provider: "account_local",
      browserToken: "a".repeat(43),
      pollIntervalSeconds: 2,
      expiresAt: new Date("2026-08-23T00:10:00.000Z"),
    });

    const response = await POST(
      new Request("https://leviknet.com/api/mobile/v1/auth/challenge", {
        method: "POST",
      }),
    );
    const body: unknown = await response.json();

    expect(mocks.beginDeviceLogin).not.toHaveBeenCalled();
    expect(mocks.createLocalAccountLoginAttempt).toHaveBeenCalledOnce();
    expect(body).toMatchObject({
      ok: true,
      loginToken: "a".repeat(43),
      accountActivationSupported: true,
      activationCode: "ABCD-EFGH-JKMN-PQRS",
      pollIntervalSeconds: 2,
    });
    expect(body).not.toHaveProperty("verificationCode");
    expect(body).not.toHaveProperty("verificationUriComplete");
  });

  it("keeps the legacy bridge flow when the capability flag is absent", async () => {
    mocks.readMobileJson.mockResolvedValue({ body: "{}", value: baseValue });
    mocks.beginDeviceLogin.mockResolvedValue({
      provider: "legacy_bridge",
      browserToken: "b".repeat(43),
      deviceCode: "device-code",
      verificationCode: "ABCD",
      verificationUriComplete: "https://t.me/levikvpnbot?start=web_test",
      pollIntervalSeconds: 5,
      expiresAt: new Date("2026-08-23T00:10:00.000Z"),
    });

    const response = await POST(
      new Request("https://leviknet.com/api/mobile/v1/auth/challenge", {
        method: "POST",
      }),
    );
    const body: unknown = await response.json();

    expect(mocks.beginDeviceLogin).toHaveBeenCalledOnce();
    expect(mocks.createLocalAccountLoginAttempt).not.toHaveBeenCalled();
    expect(mocks.createAccountActivation).not.toHaveBeenCalled();
    expect(body).toMatchObject({
      verificationCode: "ABCD",
      verificationUriComplete: "https://t.me/levikvpnbot?start=web_test",
    });
    expect(body).not.toHaveProperty("accountActivationSupported");
  });
});
