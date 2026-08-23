import "server-only";

import pg, { type PoolClient, type QueryResultRow } from "pg";

import { getEnvironment } from "@/lib/server/env";

const { Pool } = pg;

declare global {
  var __leviknetPool: pg.Pool | undefined;
}

function createPool(): pg.Pool {
  const environment = getEnvironment();
  return new Pool({
    host: environment.DB_HOST,
    port: environment.DB_PORT,
    database: environment.DB_NAME,
    user: environment.DB_USER,
    password: environment.DB_PASSWORD,
    ssl: environment.DB_SSL ? { rejectUnauthorized: true } : false,
    max: 10,
    idleTimeoutMillis: 30_000,
    connectionTimeoutMillis: 5_000,
    application_name: "leviknet-web",
  });
}

export function getDatabasePool(): pg.Pool {
  globalThis.__leviknetPool ??= createPool();
  return globalThis.__leviknetPool;
}

export async function query<Row extends QueryResultRow>(
  text: string,
  values: readonly unknown[] = [],
): Promise<pg.QueryResult<Row>> {
  return getDatabasePool().query<Row>(text, [...values]);
}

export async function withTransaction<T>(
  callback: (client: PoolClient) => Promise<T>,
): Promise<T> {
  const client = await getDatabasePool().connect();
  try {
    await client.query("BEGIN");
    const value = await callback(client);
    await client.query("COMMIT");
    return value;
  } catch (error) {
    await client.query("ROLLBACK");
    throw error;
  } finally {
    client.release();
  }
}
