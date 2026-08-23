import { beforeEach, describe, expect, it, vi } from "vitest";

vi.mock("server-only", () => ({}));

type TestQueryResult = {
  rowCount: number;
  rows: Array<Record<string, unknown>>;
};

const mocks = vi.hoisted(() => ({
  query: vi.fn(),
  clientQuery: vi.fn<
    (sql: string, parameters?: readonly unknown[]) => Promise<TestQueryResult>
  >(),
  withTransaction: vi.fn(),
  linkLegacyAccountSession: vi.fn(),
}));

vi.mock("@/lib/server/db", () => ({
  query: mocks.query,
  withTransaction: mocks.withTransaction,
}));
vi.mock("@/lib/server/account/session", () => ({
  linkLegacyAccountSession: mocks.linkLegacyAccountSession,
}));

import {
  linkLegacyIdentity,
  revokeAccountDevice,
} from "@/lib/server/account/legacy";

const ACCOUNT_ID = "018d1557-d946-7c03-8c42-f83a43d91c8e";
const OTHER_ACCOUNT_ID = "028d1557-d946-7c03-8c42-f83a43d91c8e";
const DEVICE_ID = "038d1557-d946-7c03-8c42-f83a43d91c8e";

describe("account device ownership and legacy linking", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    mocks.withTransaction.mockImplementation(
      async (
        callback: (client: { query: typeof mocks.clientQuery }) => Promise<unknown>,
      ) => callback({ query: mocks.clientQuery }),
    );
  });

  it("does not revoke a device that is not owned by the account", async () => {
    mocks.clientQuery.mockResolvedValueOnce({ rowCount: 0, rows: [] });

    await expect(revokeAccountDevice(ACCOUNT_ID, DEVICE_ID)).resolves.toBe(false);
    expect(mocks.clientQuery).toHaveBeenCalledOnce();
    expect(mocks.clientQuery.mock.calls[0]?.[1]).toEqual([
      ACCOUNT_ID,
      DEVICE_ID,
    ]);
  });

  it("revokes sessions bound to an owned external device", async () => {
    mocks.clientQuery
      .mockResolvedValueOnce({
        rowCount: 1,
        rows: [{ external_device_id: "device-0123456789" }],
      })
      .mockResolvedValue({ rowCount: 1, rows: [] });

    await expect(revokeAccountDevice(ACCOUNT_ID, DEVICE_ID)).resolves.toBe(true);
    expect(mocks.clientQuery).toHaveBeenCalledTimes(5);
    for (const call of mocks.clientQuery.mock.calls.slice(1)) {
      expect(call[1]?.[0]).toBe(ACCOUNT_ID);
    }
    expect(mocks.clientQuery.mock.calls[2]?.[1]).toEqual([
      ACCOUNT_ID,
      "device-0123456789",
    ]);
    expect(mocks.clientQuery.mock.calls[4]?.[1]).toEqual([
      ACCOUNT_ID,
      "device-0123456789",
    ]);
  });

  it("rejects a Telegram identity already linked to another account", async () => {
    mocks.query.mockResolvedValueOnce({
      rowCount: 1,
      rows: [{ account_id: OTHER_ACCOUNT_ID }],
    });

    await expect(
      linkLegacyIdentity(ACCOUNT_ID, {
        rawToken: "B".repeat(43),
        tokenHash: Buffer.alloc(32, 1),
        publicId: "148d1557-d946-4c03-8c42-f83a43d91c8e",
        accountId: ACCOUNT_ID,
        authMethod: "password",
        deviceName: "Browser",
        authenticatedAt: new Date("2026-08-23T00:00:00.000Z"),
        createdAt: new Date("2026-08-23T00:00:00.000Z"),
        lastSeenAt: new Date("2026-08-23T00:00:00.000Z"),
        absoluteExpiresAt: new Date("2026-09-23T00:00:00.000Z"),
      }, {
        rawToken: "A".repeat(43),
        tokenHash: Buffer.alloc(32),
        publicId: "048d1557-d946-7c03-8c42-f83a43d91c8e",
        userKey: "usr_0123456789abcdefghijklmnop",
        userLabel: "Telegram user",
        deviceLabel: "Browser",
        grant: "legacy-grant",
        grantExpiresAt: new Date("2026-08-23T01:00:00.000Z"),
        createdAt: new Date("2026-08-23T00:00:00.000Z"),
        lastSeenAt: new Date("2026-08-23T00:00:00.000Z"),
        absoluteExpiresAt: new Date("2026-09-23T00:00:00.000Z"),
      }),
    ).rejects.toMatchObject({ code: "credential_conflict", status: 409 });
    expect(mocks.withTransaction).not.toHaveBeenCalled();
    expect(mocks.linkLegacyAccountSession).not.toHaveBeenCalled();
  });
});
