import { beforeEach, describe, expect, it, vi } from "vitest";

const mocks = vi.hoisted(() => ({
  query: vi.fn(),
  verifyIdToken: vi.fn(),
}));

vi.mock("server-only", () => ({}));
vi.mock("google-auth-library", () => ({
  OAuth2Client: vi.fn(function OAuth2ClientMock() {
    return { verifyIdToken: mocks.verifyIdToken };
  }),
}));
vi.mock("@/lib/server/db", () => ({
  query: mocks.query,
  withTransaction: vi.fn(),
}));
vi.mock("@/lib/server/env", () => ({
  getEnvironment: () => ({
    GOOGLE_WEB_CLIENT_ID:
      "123456789012-abcdefghijklmnopqrstuv.apps.googleusercontent.com",
    googleOAuthClientIds: new Set([
      "123456789012-abcdefghijklmnopqrstuv.apps.googleusercontent.com",
    ]),
  }),
}));

import { authenticateGoogle } from "@/lib/server/account/google";

describe("Google identity verification", () => {
  beforeEach(() => {
    mocks.query.mockReset();
    mocks.verifyIdToken.mockReset();
    mocks.query.mockResolvedValue({ rowCount: 1, rows: [{ ok: 1 }] });
  });

  it("rejects an ID token with a mismatched nonce", async () => {
    mocks.verifyIdToken.mockResolvedValue({
      getPayload: () => ({
        iss: "https://accounts.google.com",
        sub: "123456789012345678901",
        nonce: "B".repeat(43),
        name: "Google user",
      }),
    });

    await expect(
      authenticateGoogle("header.payload.signature".repeat(10), "A".repeat(43)),
    ).rejects.toMatchObject({ code: "invalid_credentials" });
    expect(
      mocks.query.mock.calls.some(([text]) =>
        String(text).includes("SET consumed_at = now()"),
      ),
    ).toBe(false);
  });

  it("fails closed when the official verifier rejects issuer or audience", async () => {
    mocks.verifyIdToken.mockRejectedValue(new Error("audience mismatch"));
    await expect(
      authenticateGoogle("header.payload.signature".repeat(10), "A".repeat(43)),
    ).rejects.toMatchObject({ code: "invalid_credentials" });
  });
});
