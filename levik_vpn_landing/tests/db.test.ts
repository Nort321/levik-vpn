import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

const databaseMocks = vi.hoisted(() => {
  const poolQuery = vi.fn().mockResolvedValue({ rows: [] });
  const clientQuery = vi.fn().mockResolvedValue({ rows: [] });
  const release = vi.fn();
  const connect = vi.fn().mockResolvedValue({
    query: clientQuery,
    release,
  });
  const pool = { query: poolQuery, connect };
  const Pool = vi.fn(function PoolMock() {
    return pool;
  });

  return { Pool, clientQuery, connect, pool, poolQuery, release };
});

vi.mock("server-only", () => ({}));
vi.mock("pg", () => ({
  default: { Pool: databaseMocks.Pool },
}));
vi.mock("@/lib/server/env", () => ({
  getEnvironment: () => ({
    DB_HOST: "127.0.0.1",
    DB_PORT: 5432,
    DB_NAME: "leviknet",
    DB_USER: "levik_app",
    DB_PASSWORD: "database-password",
    DB_SSL: false,
  }),
}));

describe("database pool lifecycle", () => {
  beforeEach(() => {
    vi.resetModules();
    databaseMocks.Pool.mockClear();
    databaseMocks.poolQuery.mockClear();
    delete globalThis.__leviknetPool;
    vi.stubEnv("NODE_ENV", "production");
  });

  afterEach(() => {
    delete globalThis.__leviknetPool;
    vi.unstubAllEnvs();
  });

  it("reuses one Pool for every production database access", async () => {
    const database = await import("@/lib/server/db");

    const first = database.getDatabasePool();
    const second = database.getDatabasePool();
    await database.query("SELECT 1");

    expect(first).toBe(databaseMocks.pool);
    expect(second).toBe(first);
    expect(databaseMocks.Pool).toHaveBeenCalledTimes(1);
    expect(databaseMocks.poolQuery).toHaveBeenCalledTimes(1);
  });
});
