import { beforeEach, describe, expect, it, vi } from "vitest";

vi.mock("server-only", () => ({}));

type TestQueryResult = {
  rowCount: number;
  rows: Array<Record<string, unknown>>;
};

const mocks = vi.hoisted(() => ({
  clientQuery: vi.fn<
    (sql: string, parameters?: readonly unknown[]) => Promise<TestQueryResult>
  >(),
}));

vi.mock("@/lib/server/db", () => ({
  query: vi.fn(),
  withTransaction: async (
    callback: (client: { query: typeof mocks.clientQuery }) => Promise<unknown>,
  ) => callback({ query: mocks.clientQuery }),
}));

import { revokeIdentity } from "@/lib/server/account/identity";

const ACCOUNT_ID = "018d1557-d946-7c03-8c42-f83a43d91c8e";
const IDENTITY_ID = "028d1557-d946-4c03-8c42-f83a43d91c8e";
const USER_KEY = "usr_0123456789abcdefghijklmnop";

describe("Telegram identity revocation", () => {
  beforeEach(() => {
    mocks.clientQuery.mockReset();
  });

  it("revokes the legacy mapping, sessions, and grants atomically", async () => {
    mocks.clientQuery
      .mockResolvedValueOnce({
        rowCount: 1,
        rows: [
          {
            identity_id: IDENTITY_ID,
            provider: "telegram",
            provider_subject: USER_KEY,
          },
        ],
      })
      .mockResolvedValueOnce({
        rowCount: 1,
        rows: [{ method_count: 1 }],
      })
      .mockResolvedValue({ rowCount: 1, rows: [] });

    await expect(
      revokeIdentity(ACCOUNT_ID, IDENTITY_ID),
    ).resolves.toBeUndefined();

    const calls = mocks.clientQuery.mock.calls.map(([sql, parameters]) => ({
      sql: String(sql),
      parameters,
    }));
    expect(
      calls.some(
        ({ sql, parameters }) =>
          sql.includes("INSERT INTO web_grant_revocations") &&
          parameters?.[0] === ACCOUNT_ID &&
          parameters?.[1] === USER_KEY,
      ),
    ).toBe(true);
    expect(
      calls.some(
        ({ sql, parameters }) =>
          sql.includes("UPDATE web_sessions") &&
          sql.includes("session_kind = 'legacy'") &&
          parameters?.[1] === USER_KEY,
      ),
    ).toBe(true);
    expect(
      calls.some(
        ({ sql, parameters }) =>
          sql.includes("UPDATE account_sessions") &&
          sql.includes("auth_method = 'telegram'") &&
          parameters?.[0] === ACCOUNT_ID,
      ),
    ).toBe(true);
    const legacyLinkUpdate = calls.find(({ sql }) =>
      sql.includes("UPDATE legacy_account_links"),
    );
    expect(legacyLinkUpdate?.sql).toContain("revoked_at = COALESCE");
    expect(legacyLinkUpdate?.sql).toContain("user_key = 'revoked:'");
    expect(legacyLinkUpdate?.parameters).toEqual([
      ACCOUNT_ID,
      USER_KEY,
      IDENTITY_ID,
    ]);
  });
});
