import { beforeEach, describe, expect, it, vi } from "vitest";

vi.mock("server-only", () => ({}));

type TestQueryResult = {
  rowCount: number;
  rows: Array<Record<string, unknown>>;
};

const databaseMocks = vi.hoisted(() => ({
  clientQuery: vi.fn<
    (sql: string, parameters?: readonly unknown[]) => Promise<TestQueryResult>
  >(),
  query: vi.fn<
    (sql: string, parameters?: readonly unknown[]) => Promise<TestQueryResult>
  >(),
}));

vi.mock("@/lib/server/db", () => ({
  query: databaseMocks.query,
  withTransaction: async (
    callback: (client: { query: typeof databaseMocks.clientQuery }) => Promise<unknown>,
  ) => callback({ query: databaseMocks.clientQuery }),
}));

vi.mock("@/lib/server/bridge/auth", () => ({
  revokeBridgeGrant: vi.fn(),
}));

import {
  type GrantRevocationClaim,
  isMaintenanceRequestAuthorized,
  retryPendingGrantRevocations,
  runMaintenance,
} from "@/lib/server/maintenance";

const maintenanceToken = "M".repeat(43);

function claim(
  overrides: Partial<GrantRevocationClaim> = {},
): GrantRevocationClaim {
  return {
    id: "1",
    leaseToken: "11111111-1111-4111-8111-111111111111",
    sessionTokenHash: Buffer.alloc(32, 1),
    idempotencyKey: "22222222-2222-4222-8222-222222222222",
    grantCiphertext: "encrypted-grant",
    grantExpiresAt: new Date("2030-01-01T00:00:00.000Z"),
    ...overrides,
  };
}

describe("maintenance authentication", () => {
  it("accepts only the exact bearer token", () => {
    expect(
      isMaintenanceRequestAuthorized(
        `Bearer ${maintenanceToken}`,
        maintenanceToken,
      ),
    ).toBe(true);
    expect(
      isMaintenanceRequestAuthorized(
        `Bearer ${"N".repeat(43)}`,
        maintenanceToken,
      ),
    ).toBe(false);
  });

  it.each([
    null,
    maintenanceToken,
    `bearer ${maintenanceToken}`,
    `Bearer ${maintenanceToken} extra`,
    `Bearer ${maintenanceToken}, Bearer ${maintenanceToken}`,
  ])("rejects a malformed authorization header", (authorization) => {
    expect(
      isMaintenanceRequestAuthorized(authorization, maintenanceToken),
    ).toBe(false);
  });
});

describe("grant revocation maintenance", () => {
  it("claims a bounded batch and completes an idempotent bridge revoke", async () => {
    const work = claim();
    const claimBatch = vi.fn(() => Promise.resolve([work]));
    const decryptGrant = vi.fn(() => "decrypted-grant");
    const revokeGrant = vi.fn(() => Promise.resolve());
    const complete = vi.fn(() => Promise.resolve(true));
    const fail = vi.fn(() => Promise.resolve(true));

    const summary = await retryPendingGrantRevocations({
      claim: claimBatch,
      decryptGrant,
      revokeGrant,
      complete,
      fail,
      now: () => new Date("2029-01-01T00:00:00.000Z"),
    });

    expect(claimBatch).toHaveBeenCalledWith(10);
    expect(revokeGrant).toHaveBeenCalledWith(
      "decrypted-grant",
      work.idempotencyKey,
    );
    expect(complete).toHaveBeenCalledWith(work);
    expect(fail).not.toHaveBeenCalled();
    expect(summary).toEqual({
      claimed: 1,
      completed: 1,
      failed: 0,
      leaseLost: 0,
    });
  });

  it("releases a failed item with a bounded error code for retry", async () => {
    const work = claim();
    const fail = vi.fn(() => Promise.resolve(true));

    const summary = await retryPendingGrantRevocations({
      claim: () => Promise.resolve([work]),
      decryptGrant: () => "decrypted-grant",
      revokeGrant: () =>
        Promise.reject(
          new Error("network details that must not be persisted"),
        ),
      complete: () => Promise.resolve(true),
      fail,
      now: () => new Date("2029-01-01T00:00:00.000Z"),
    });

    expect(fail).toHaveBeenCalledWith(
      work,
      "maintenance_revocation_failed",
    );
    expect(summary).toEqual({
      claimed: 1,
      completed: 0,
      failed: 1,
      leaseLost: 0,
    });
  });

  it("does not send an already expired grant back to the bridge", async () => {
    const work = claim({
      grantExpiresAt: new Date("2028-01-01T00:00:00.000Z"),
    });
    const decryptGrant = vi.fn(() => "decrypted-grant");
    const revokeGrant = vi.fn(() => Promise.resolve());

    const summary = await retryPendingGrantRevocations({
      claim: () => Promise.resolve([work]),
      decryptGrant,
      revokeGrant,
      complete: () => Promise.resolve(true),
      fail: () => Promise.resolve(true),
      now: () => new Date("2029-01-01T00:00:00.000Z"),
    });

    expect(decryptGrant).not.toHaveBeenCalled();
    expect(revokeGrant).not.toHaveBeenCalled();
    expect(summary.completed).toBe(1);
  });
});

describe("account bridge shadow-session maintenance", () => {
  beforeEach(() => {
    databaseMocks.clientQuery.mockReset();
    databaseMocks.query.mockReset();
  });

  it("revokes and detaches authorizations before shadow sessions are cleaned", async () => {
    databaseMocks.clientQuery.mockImplementation((sql: string) => {
      if (sql.includes("SELECT\n          claimed.id")) {
        return Promise.resolve({ rowCount: 0, rows: [] });
      }
      if (
        sql.includes("deleted_revocations AS") &&
        sql.includes("AS deleted_sessions")
      ) {
        return Promise.resolve({
          rowCount: 1,
          rows: [{ deleted_sessions: 1, deleted_revocations: 1 }],
        });
      }
      if (sql.includes("WITH expired AS")) {
        return Promise.resolve({
          rowCount: 1,
          rows: [{ revoked_count: 1, queued_count: 1 }],
        });
      }
      return Promise.resolve({ rowCount: 0, rows: [] });
    });

    await runMaintenance();

    const sqlCalls = databaseMocks.clientQuery.mock.calls.map(([sql]) => sql);
    const expirySql = sqlCalls.find((sql) => sql.includes("WITH expired AS"));
    const completedCleanupSql = sqlCalls.find(
      (sql) =>
        sql.includes("deleted_revocations AS") &&
        sql.includes("AS deleted_sessions"),
    );
    const expiredCleanupSql = sqlCalls.find(
      (sql) =>
        sql.includes("session.grant_expires_at <=") &&
        sql.includes("sessions_ready_for_deletion AS"),
    );

    for (const sql of [expirySql, completedCleanupSql, expiredCleanupSql]) {
      expect(sql).toContain("UPDATE account_bridge_authorizations");
      expect(sql).toContain("state = 'revoked'");
      expect(sql).toContain("bridge_session_token_hash = NULL");
      expect(sql).toContain("requested_legacy_user_key = NULL");
      expect(sql).toContain("lease_token = NULL");
      expect(sql).toContain("lease_expires_at = NULL");
    }
    expect(completedCleanupSql).toContain("sessions_ready_for_deletion AS");
    expect(expiredCleanupSql).toContain("DELETE FROM web_sessions AS session");
  });
});
