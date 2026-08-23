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
  withTransaction: async (
    callback: (client: { query: typeof mocks.clientQuery }) => Promise<unknown>,
  ) => callback({ query: mocks.clientQuery }),
}));

import { confirmAccountDeletion } from "@/lib/server/account/deletion";

const ACCOUNT_ID = "018d1557-d946-7c03-8c42-f83a43d91c8e";
const CONFIRMATION_TOKEN = "A".repeat(43);

describe("account deletion confirmation", () => {
  beforeEach(() => {
    mocks.clientQuery.mockReset();
  });

  it("revokes access and anonymizes account-owned data atomically", async () => {
    mocks.clientQuery.mockImplementation((sql: string) => {
      if (sql.includes("FROM account_deletion_requests") && sql.includes("FOR UPDATE")) {
        return Promise.resolve({
          rowCount: 1,
          rows: [{ request_id: "request-1" }],
        });
      }
      return Promise.resolve({ rowCount: 1, rows: [] });
    });

    await expect(
      confirmAccountDeletion(ACCOUNT_ID, CONFIRMATION_TOKEN),
    ).resolves.toBeUndefined();

    const calls = mocks.clientQuery.mock.calls.map(([sql, parameters]) => ({
      sql: String(sql),
      parameters,
    }));
    expect(
      calls.some(({ sql, parameters }) =>
        sql.includes("INSERT INTO web_grant_revocations") &&
        parameters?.[0] === ACCOUNT_ID,
      ),
    ).toBe(true);
    expect(
      calls.some(({ sql, parameters }) =>
        sql.includes("UPDATE account_sessions SET revoked_at") &&
        parameters?.[0] === ACCOUNT_ID,
      ),
    ).toBe(true);
    const bridgeAuthorizationUpdate = calls.find(({ sql, parameters }) =>
      sql.includes("UPDATE account_bridge_authorizations") &&
      parameters?.[0] === ACCOUNT_ID,
    );
    expect(bridgeAuthorizationUpdate?.sql).toContain("state = 'revoked'");
    expect(bridgeAuthorizationUpdate?.sql).toContain(
      "bridge_session_token_hash = NULL",
    );
    expect(bridgeAuthorizationUpdate?.sql).toContain(
      "requested_legacy_user_key = NULL",
    );
    expect(bridgeAuthorizationUpdate?.sql).toContain("lease_token = NULL");
    expect(bridgeAuthorizationUpdate?.sql).toContain(
      "lease_expires_at = NULL",
    );
    expect(
      calls.some(({ sql, parameters }) =>
        sql.includes("UPDATE account_devices SET revoked_at") &&
        parameters?.[0] === ACCOUNT_ID,
      ),
    ).toBe(true);
    expect(
      calls.some(({ sql, parameters }) =>
        sql.includes("DELETE FROM password_credentials") &&
        parameters?.[0] === ACCOUNT_ID,
      ),
    ).toBe(true);
    expect(
      calls.some(({ sql, parameters }) =>
        sql.includes("provider_subject = 'deleted:'") &&
        parameters?.[0] === ACCOUNT_ID,
      ),
    ).toBe(true);
    expect(
      calls.some(({ sql, parameters }) =>
        sql.includes("DELETE FROM account_legacy_link_reservations") &&
        parameters?.[0] === ACCOUNT_ID,
      ),
    ).toBe(true);
    expect(
      calls.some(({ sql, parameters }) =>
        sql.includes("status = 'deleted'") && parameters?.[0] === ACCOUNT_ID,
      ),
    ).toBe(true);
    expect(
      calls.some(({ sql, parameters }) =>
        sql.includes("'account.deletion.confirm'") &&
        parameters?.[0] === ACCOUNT_ID,
      ),
    ).toBe(true);
  });

  it("does not mutate data when the token is not owned by the account", async () => {
    mocks.clientQuery.mockResolvedValueOnce({ rowCount: 0, rows: [] });

    await expect(
      confirmAccountDeletion(ACCOUNT_ID, CONFIRMATION_TOKEN),
    ).rejects.toMatchObject({
      code: "invalid_deletion_confirmation",
      status: 409,
    });
    expect(mocks.clientQuery).toHaveBeenCalledOnce();
    expect(mocks.clientQuery.mock.calls[0]?.[1]?.[0]).toBe(ACCOUNT_ID);
  });
});
