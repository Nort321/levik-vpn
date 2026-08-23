import { describe, expect, it, vi } from "vitest";

vi.mock("server-only", () => ({}));

const mocks = vi.hoisted(() => ({
  client: { query: vi.fn() },
  createAccount: vi.fn(),
  hashPassword: vi.fn(),
  setPasswordCredentialWithClient: vi.fn(),
  replaceRecoveryCodesWithClient: vi.fn(),
  createAccountSessionWithClient: vi.fn(),
  writeAuditEventWithClient: vi.fn(),
}));

vi.mock("@/lib/server/db", () => ({
  withTransaction: vi.fn((callback: (client: typeof mocks.client) => unknown) =>
    callback(mocks.client),
  ),
}));
vi.mock("@/lib/server/account/model", () => ({
  createAccount: mocks.createAccount,
}));
vi.mock("@/lib/server/account/password", () => ({
  hashPassword: mocks.hashPassword,
  setPasswordCredentialWithClient: mocks.setPasswordCredentialWithClient,
}));
vi.mock("@/lib/server/account/recovery", () => ({
  replaceRecoveryCodesWithClient: mocks.replaceRecoveryCodesWithClient,
}));
vi.mock("@/lib/server/account/session", () => ({
  createAccountSessionWithClient: mocks.createAccountSessionWithClient,
}));
vi.mock("@/lib/server/audit", () => ({
  writeAuditEventWithClient: mocks.writeAuditEventWithClient,
}));

import { enrollPasswordAccount } from "@/lib/server/account/enrollment";

describe("password account enrollment", () => {
  it("creates credentials, recovery codes, session and audit in one transaction", async () => {
    const account = {
      accountId: "74ea3429-0a7e-4f42-a4ef-50f019c13a39",
      levikId: "LVK-ABCD-EFGH-JKMN",
      displayName: "Nikita",
      status: "active",
      createdAt: new Date("2026-08-23T00:00:00.000Z"),
    };
    const material = {
      salt: Buffer.alloc(16),
      derivedKey: Buffer.alloc(32),
      parameters: { N: 65_536, r: 8, p: 1, keyLength: 32 },
    };
    const session = { publicId: "session-id" };
    mocks.hashPassword.mockResolvedValueOnce(material);
    mocks.createAccount.mockResolvedValueOnce(account);
    mocks.replaceRecoveryCodesWithClient.mockResolvedValueOnce(["CODE-1"]);
    mocks.createAccountSessionWithClient.mockResolvedValueOnce(session);

    const result = await enrollPasswordAccount({
      displayName: "Nikita",
      password: "correct horse battery staple",
      sessionContext: {
        userAgent: "test",
        clientAddress: "127.0.0.1",
      },
    });

    expect(mocks.setPasswordCredentialWithClient).toHaveBeenCalledWith(
      mocks.client,
      account.accountId,
      account.levikId,
      material,
    );
    expect(mocks.replaceRecoveryCodesWithClient).toHaveBeenCalledWith(
      mocks.client,
      account.accountId,
    );
    expect(mocks.createAccountSessionWithClient).toHaveBeenCalledWith(
      mocks.client,
      account.accountId,
      "password",
      expect.any(Object),
    );
    expect(mocks.writeAuditEventWithClient).toHaveBeenCalledWith(
      mocks.client,
      expect.objectContaining({
        eventType: "account.enrollment.password",
        accountId: account.accountId,
      }),
    );
    expect(result).toEqual({ account, session, recoveryCodes: ["CODE-1"] });
  });
});
