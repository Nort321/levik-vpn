import { beforeEach, describe, expect, it, vi } from "vitest";

vi.mock("server-only", () => ({}));

const mocks = vi.hoisted(() => ({
  claimLoginAttemptForPolling: vi.fn(),
  getLoginAttempt: vi.fn(),
  releaseLoginPoll: vi.fn(),
  createSessionFromAuthorization: vi.fn(),
  getAuthorizedAccountActivation: vi.fn(),
  issueBridgeAuthorizationForAccount: vi.fn(),
  markAccountActivationConsumed: vi.fn(),
}));

vi.mock("@/lib/server/audit", () => ({ writeAuditEvent: vi.fn() }));
vi.mock("@/lib/server/account/activation", () => ({
  getAuthorizedAccountActivation: mocks.getAuthorizedAccountActivation,
  issueBridgeAuthorizationForAccount: mocks.issueBridgeAuthorizationForAccount,
  markAccountActivationConsumed: mocks.markAccountActivationConsumed,
}));
vi.mock("@/lib/server/account/legacy", () => ({
  ensureLegacyAccount: vi.fn(),
  upsertAccountDevice: vi.fn(),
}));
vi.mock("@/lib/server/account/session", () => ({
  linkLegacyAccountSession: vi.fn(),
}));
vi.mock("@/lib/server/bridge/auth", () => ({
  getDeviceAuthorizationStatus: vi.fn(),
}));
vi.mock("@/lib/server/mobile-auth", () => ({
  authenticateMobileLoginRequest: vi.fn(() =>
    Promise.resolve({
      deviceId: "device-0123456789",
      device: { appVersion: "2.0.0", label: "Pixel" },
    }),
  ),
  promoteMobileLogin: vi.fn(),
}));
vi.mock("@/lib/server/mobile-api", () => ({
  assertMobileRequestTarget: vi.fn(),
  mobileLoginStatusSchema: {},
  MobileApiError: class MobileApiError extends Error {
    constructor(
      public readonly code: string,
      public readonly status: number,
    ) {
      super(code);
    }
  },
  MOBILE_NO_STORE_HEADERS: { "Cache-Control": "no-store" },
  mobileRequestProof: vi.fn(() => ({ timestamp: 1 })),
  readMobileJson: vi.fn(() =>
    Promise.resolve({
      body: "{}",
      value: { loginToken: "a".repeat(43) },
    }),
  ),
}));
vi.mock("@/lib/server/mobile-route", () => ({
  mobileErrorResponse: vi.fn((error: { status?: number; code?: string }) =>
    Response.json(
      { ok: false, error: error.code ?? "error" },
      { status: error.status ?? 500 },
    ),
  ),
}));
vi.mock("@/lib/server/rate-limit", () => ({
  consumeRateLimit: vi.fn(() => Promise.resolve({ allowed: true })),
}));
vi.mock("@/lib/server/security", () => ({
  clientAddressFromHeaders: vi.fn(() => "127.0.0.1"),
}));
vi.mock("@/lib/server/session-store", () => ({
  claimLoginAttemptForPolling: mocks.claimLoginAttemptForPolling,
  createSessionFromAuthorization: mocks.createSessionFromAuthorization,
  getLoginAttempt: mocks.getLoginAttempt,
  releaseLoginPoll: mocks.releaseLoginPoll,
}));

import { POST } from "@/app/api/mobile/v1/auth/status/route";

describe("mobile account activation polling", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    const attempt = {
      provider: "account_local",
      pollIntervalSeconds: 2,
      expiresAt: new Date("2026-08-23T00:10:00.000Z"),
    };
    mocks.claimLoginAttemptForPolling
      .mockResolvedValueOnce(attempt)
      .mockResolvedValueOnce(null);
    mocks.getLoginAttempt.mockResolvedValue(attempt);
    mocks.getAuthorizedAccountActivation.mockResolvedValue({
      activation_id: "74ea3429-0a7e-4f42-a4ef-50f019c13a39",
      account_id: "b859aa49-fb16-4c5b-8127-af25c44dd80b",
    });
    mocks.issueBridgeAuthorizationForAccount.mockResolvedValue({
      status: "authorized",
      grant: "g".repeat(43),
      grantExpiresIn: 3600,
      user: { userKey: "usr_0123456789abcdefghijklmnop", userLabel: "User" },
    });
    mocks.createSessionFromAuthorization.mockResolvedValue({
      rawToken: "s".repeat(43),
      userKey: "usr_0123456789abcdefghijklmnop",
      absoluteExpiresAt: new Date("2026-09-23T00:00:00.000Z"),
    });
  });

  it("claims before issuing a bridge grant so concurrent polls issue once", async () => {
    const makeRequest = () =>
      new Request("https://leviknet.com/api/mobile/v1/auth/status", {
        method: "POST",
      });
    const [first, second] = await Promise.all([
      POST(makeRequest()),
      POST(makeRequest()),
    ]);
    const bodies: unknown[] = await Promise.all([first.json(), second.json()]);

    expect(mocks.claimLoginAttemptForPolling).toHaveBeenCalledTimes(2);
    expect(mocks.issueBridgeAuthorizationForAccount).toHaveBeenCalledOnce();
    expect(
      mocks.claimLoginAttemptForPolling.mock.invocationCallOrder[0],
    ).toBeLessThan(
      mocks.issueBridgeAuthorizationForAccount.mock.invocationCallOrder[0] ?? 0,
    );
    const states = bodies.map((body) => {
      if (
        typeof body === "object" &&
        body !== null &&
        "state" in body &&
        typeof body.state === "string"
      ) {
        return body.state;
      }
      return null;
    });
    expect(states.sort()).toEqual([
      "authenticated",
      "pending",
    ]);
  });
});
