import { beforeEach, describe, expect, it, vi } from "vitest";

const mocks = vi.hoisted(() => ({
  challengeAllowed: true,
  query: vi.fn(),
  verifyAuthenticationResponse: vi.fn(),
  getAccountById: vi.fn(),
}));

vi.mock("server-only", () => ({}));
vi.mock("@simplewebauthn/server", () => ({
  generateAuthenticationOptions: vi.fn(),
  generateRegistrationOptions: vi.fn(),
  verifyAuthenticationResponse: mocks.verifyAuthenticationResponse,
  verifyRegistrationResponse: vi.fn(),
}));
vi.mock("@/lib/server/db", () => ({
  query: mocks.query,
  withTransaction: vi.fn(),
}));
vi.mock("@/lib/server/env", () => ({
  getEnvironment: () => ({
    NODE_ENV: "test",
    APP_ORIGIN: "https://leviknet.com",
  }),
}));
vi.mock("@/lib/server/account/model", () => ({
  getAccountById: mocks.getAccountById,
  getAccountByLevikId: vi.fn(),
}));

import {
  parseAuthenticationResponse,
  verifyPasskeyAuthentication,
} from "@/lib/server/account/passkey";

const RESPONSE = {
  id: "credential-id",
  rawId: "credential-id",
  type: "public-key",
  clientExtensionResults: {},
  response: {
    clientDataJSON: "Y2xpZW50",
    authenticatorData: "YXV0aGVudGljYXRvcg",
    signature: "c2lnbmF0dXJl",
  },
} as const;

describe("Levik Account passkey verification", () => {
  beforeEach(() => {
    mocks.challengeAllowed = true;
    mocks.query.mockReset();
    mocks.getAccountById.mockResolvedValue({
      accountId: "018d1557-d946-7c03-8c42-f83a43d91c8e",
      levikId: "LVK-ABCD-EFGH-JKMN",
      displayName: "Test user",
      status: "active",
      createdAt: new Date("2026-08-01T00:00:00.000Z"),
    });
    mocks.query.mockImplementation((text: string) => {
      if (text.includes("FROM webauthn_challenges")) {
        return Promise.resolve({
          rowCount: 1,
          rows: [
            {
              account_id: null,
              expires_at: new Date(Date.now() + 60_000),
              consumed_at: null,
            },
          ],
        });
      }
      if (text.includes("FROM passkey_credentials")) {
        return Promise.resolve({
          rowCount: 1,
          rows: [
            {
              credential_id: "credential-id",
              account_id: "018d1557-d946-7c03-8c42-f83a43d91c8e",
              public_key: Buffer.from("public-key"),
              signature_counter: 10,
              transports: ["internal"],
              name: "Phone",
              created_at: new Date(),
              last_used_at: null,
            },
          ],
        });
      }
      if (text.includes("UPDATE webauthn_challenges")) {
        return Promise.resolve({
          rowCount: mocks.challengeAllowed ? 1 : 0,
          rows: [],
        });
      }
      if (text.includes("UPDATE passkey_credentials")) {
        return Promise.resolve({ rowCount: 1, rows: [] });
      }
      throw new Error(`Unexpected query: ${text}`);
    });
    const verifyResponse = async (options: {
      expectedChallenge: (challenge: string) => Promise<boolean>;
    }) => {
        const { expectedChallenge } = options;
        const accepted = await expectedChallenge("challenge");
        if (!accepted) {
          throw new Error("challenge rejected");
        }
        return {
          verified: true,
          authenticationInfo: {
            credentialID: "credential-id",
            newCounter: 11,
            userVerified: true,
            credentialDeviceType: "singleDevice",
            credentialBackedUp: false,
            origin: "https://leviknet.com",
            rpID: "leviknet.com",
          },
        };
      };
    mocks.verifyAuthenticationResponse.mockImplementation(verifyResponse);
  });

  it("updates the counter only after consuming the one-time challenge", async () => {
    await expect(
      verifyPasskeyAuthentication({
        ceremonyId: "018d1557-d946-7c03-8c42-f83a43d91c8e",
        response: parseAuthenticationResponse(RESPONSE),
      }),
    ).resolves.toMatchObject({ levikId: "LVK-ABCD-EFGH-JKMN" });
    const challengeUpdate = mocks.query.mock.calls.find(([text]) =>
      String(text).includes("UPDATE webauthn_challenges"),
    );
    const counterUpdate = mocks.query.mock.calls.find(([text]) =>
      String(text).includes("UPDATE passkey_credentials"),
    );
    expect(challengeUpdate).toBeDefined();
    expect(counterUpdate?.[1]).toContain(10);
    expect(counterUpdate?.[1]).toContain(11);
  });

  it("rejects a replayed challenge before updating the counter", async () => {
    mocks.challengeAllowed = false;
    await expect(
      verifyPasskeyAuthentication({
        ceremonyId: "018d1557-d946-7c03-8c42-f83a43d91c8e",
        response: parseAuthenticationResponse(RESPONSE),
      }),
    ).rejects.toMatchObject({ code: "invalid_credentials" });
    expect(
      mocks.query.mock.calls.some(([text]) =>
        String(text).includes("UPDATE passkey_credentials"),
      ),
    ).toBe(false);
  });
});
