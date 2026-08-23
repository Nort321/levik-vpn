import { beforeEach, describe, expect, it, vi } from "vitest";

vi.mock("server-only", () => ({}));

const mocks = vi.hoisted(() => ({
  clientQuery: vi.fn(),
}));

vi.mock("@/lib/server/db", () => ({
  query: vi.fn(),
  withTransaction: async (
    callback: (client: { query: typeof mocks.clientQuery }) => Promise<unknown>,
  ) => callback({ query: mocks.clientQuery }),
}));

import { revokeAccountSession } from "@/lib/server/account/session";

const ACCOUNT_ID = "018d1557-d946-7c03-8c42-f83a43d91c8e";
const SESSION_ID = "028d1557-d946-7c03-8c42-f83a43d91c8e";

describe("account session revocation", () => {
  beforeEach(() => {
    mocks.clientQuery.mockReset();
  });

  it("does not revoke a session that is not owned by the account", async () => {
    mocks.clientQuery
      .mockResolvedValueOnce({ rowCount: 1, rows: [{ account_id: ACCOUNT_ID }] })
      .mockResolvedValueOnce({ rowCount: 0, rows: [] });

    await expect(revokeAccountSession(ACCOUNT_ID, SESSION_ID)).resolves.toBe(false);
    expect(mocks.clientQuery).toHaveBeenCalledTimes(2);
    expect(mocks.clientQuery.mock.calls[0]?.[1]).toEqual([
      ACCOUNT_ID,
    ]);
    expect(mocks.clientQuery.mock.calls[0]?.[0]).toContain(
      "FROM accounts WHERE account_id = $1 FOR UPDATE",
    );
    expect(mocks.clientQuery.mock.calls[1]?.[1]).toEqual([
      ACCOUNT_ID,
      SESSION_ID,
    ]);
  });

  it("revokes both account and legacy grants for an owned legacy session", async () => {
    const tokenHash = Buffer.alloc(32, 1);
    const legacyTokenHash = Buffer.alloc(32, 2);
    mocks.clientQuery
      .mockResolvedValueOnce({ rowCount: 1, rows: [{ account_id: ACCOUNT_ID }] })
      .mockResolvedValueOnce({
        rowCount: 1,
        rows: [
          {
            token_hash: tokenHash,
            legacy_session_token_hash: legacyTokenHash,
          },
        ],
      })
      .mockResolvedValueOnce({ rowCount: 1, rows: [] })
      .mockResolvedValueOnce({
        rowCount: 1,
        rows: [{ active_count: 1 }],
      })
      .mockResolvedValue({ rowCount: 1, rows: [] });

    await expect(revokeAccountSession(ACCOUNT_ID, SESSION_ID)).resolves.toBe(true);

    expect(mocks.clientQuery).toHaveBeenCalledTimes(6);
    expect(mocks.clientQuery.mock.calls[2]?.[1]).toEqual([tokenHash]);
    expect(mocks.clientQuery.mock.calls[4]?.[1]).toEqual([legacyTokenHash]);
    expect(mocks.clientQuery.mock.calls[5]?.[1]).toEqual([
      legacyTokenHash,
      ACCOUNT_ID,
    ]);
  });

  it("detaches the bridge authorization when the last account session is revoked", async () => {
    const tokenHash = Buffer.alloc(32, 1);
    mocks.clientQuery
      .mockResolvedValueOnce({ rowCount: 1, rows: [{ account_id: ACCOUNT_ID }] })
      .mockResolvedValueOnce({
        rowCount: 1,
        rows: [{ token_hash: tokenHash, legacy_session_token_hash: null }],
      })
      .mockResolvedValueOnce({ rowCount: 1, rows: [] })
      .mockResolvedValueOnce({
        rowCount: 1,
        rows: [{ active_count: 0 }],
      })
      .mockResolvedValue({ rowCount: 1, rows: [] });

    await expect(revokeAccountSession(ACCOUNT_ID, SESSION_ID)).resolves.toBe(true);

    const authorizationUpdate = mocks.clientQuery.mock.calls.find(([sql]) =>
      String(sql).includes("UPDATE account_bridge_authorizations"),
    );
    expect(authorizationUpdate?.[0]).toContain("state = 'revoked'");
    expect(authorizationUpdate?.[0]).toContain(
      "bridge_session_token_hash = NULL",
    );
    expect(authorizationUpdate?.[0]).toContain(
      "requested_legacy_user_key = NULL",
    );
    expect(authorizationUpdate?.[0]).toContain("lease_token = NULL");
    expect(authorizationUpdate?.[0]).toContain("lease_expires_at = NULL");
  });
});
