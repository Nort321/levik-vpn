import { readFile } from "node:fs/promises";
import path from "node:path";

import { describe, expect, it } from "vitest";

async function source(file: string): Promise<string> {
  return readFile(path.join(process.cwd(), file), "utf8");
}

describe("maintenance deployment boundary", () => {
  it("denies internal API paths before the public reverse proxy", async () => {
    const caddyfile = await source("Caddyfile");
    const deny = caddyfile.indexOf("handle /api/internal/*");
    const proxy = caddyfile.indexOf("reverse_proxy 127.0.0.1:3000");

    expect(deny).toBeGreaterThan(-1);
    expect(proxy).toBeGreaterThan(deny);
    expect(caddyfile).not.toContain("reverse_proxy app:3000");
    expect(caddyfile).toContain("reverse_proxy 127.0.0.1:3000");
  });

  it("routes the checker host through the trusted client-address boundary", async () => {
    const caddyfile = await source("Caddyfile");
    const checker = caddyfile.split("check.leviknet.com {")[1];

    expect(checker).toBeDefined();
    expect(checker).toContain("handle /api/internal/*");
    expect(checker).toContain("header_up X-Levik-Client-IP {remote_host}");
    expect(checker).not.toContain("header_up -X-Levik-Client-IP");
    expect(checker).toContain("header_up X-Forwarded-Host check.leviknet.com");
  });

  it("preserves the public client address without exposing Next.js", async () => {
    const compose = await source("docker-compose.yml");
    const app = compose.split("\n  app:\n")[1]?.split("\n  maintenance:\n")[0];
    const caddy = compose.split("\n  caddy:\n")[1];

    expect(app).toContain('"127.0.0.1:3000:3000"');
    expect(app).toContain("- edge");
    expect(caddy).toContain("network_mode: host");
    expect(caddy).not.toContain('"80:80"');
    expect(caddy).not.toContain('"443:443"');
  });

  it("accepts bounded account and WebAuthn payloads at the edge", async () => {
    const caddyfile = await source("Caddyfile");
    const accountHandler = caddyfile.indexOf("handle /api/account/*");
    const genericHandler = caddyfile.indexOf("\n\thandle {", accountHandler);
    const accountBlock = caddyfile.slice(accountHandler, genericHandler);

    expect(accountHandler).toBeGreaterThan(-1);
    expect(genericHandler).toBeGreaterThan(accountHandler);
    expect(accountBlock).toContain("max_size 32KB");
    expect(accountBlock).toContain("header_up X-Levik-Client-IP {remote_host}");
  });

  it("requires the account migration and runtime grants for app health", async () => {
    const healthRoute = await source("app/api/health/route.ts");

    expect(healthRoute).toContain("SELECT 1 FROM accounts LIMIT 0");
  });

  it("keeps optional network probes off the public IP critical path", async () => {
    const dashboard = await source("components/check/ip-check-dashboard.tsx");
    const baseRoute = await source("app/api/check/route.ts");
    const detailsRoute = await source("app/api/check/details/route.ts");

    expect(dashboard).not.toContain(
      "Promise.all(SERVICE_TARGETS.map(probeService))",
    );
    expect(dashboard).toContain("/api/check/details");
    expect(baseRoute).toContain("getIpCheckBaseSnapshot");
    expect(detailsRoute).toContain("getIpCheckSnapshot");
  });

  it("uses isolated non-root workers and rotates every service log", async () => {
    const compose = await source("docker-compose.yml");
    const dockerfile = await source("Dockerfile");
    const workerSource = await source("scripts/maintenance-worker.mjs");
    const worker = compose
      .split("\n  maintenance:\n")[1]
      ?.split("\n  caddy:\n")[0];

    expect(worker).toContain("target: maintenance-worker");
    expect(worker).toContain("./secrets/maintenance.env");
    expect(worker).not.toContain("./secrets/app.env");
    expect(worker).not.toContain("./secrets/app-db.env");
    expect(workerSource).toContain('"X-Forwarded-Host": "leviknet.com"');
    expect(compose).toContain("target: geoip-updater");
    expect(compose).toContain(
      "geoip-data:/var/lib/leviknet/geoip:ro",
    );
    expect(dockerfile).toMatch(
      /^FROM node:24-alpine@sha256:[0-9a-f]{64} AS geoip-updater$/m,
    );
    expect(dockerfile).toContain('USER node\nCMD ["node", "scripts/geoip-updater.mjs"]');
    expect(
      compose.match(/^ {4}logging: \*default-logging$/gm),
    ).toHaveLength(7);
    expect(compose).toContain('max-size: "10m"');
    expect(compose).toContain('max-file: "3"');
    expect(compose).toContain(
      "/app/.next/cache:size=32m,mode=0755,uid=1001,gid=1001",
    );
  });

  it("streams APK uploads without buffering or finalizing the hash twice", async () => {
    const uploadRoute = await source("app/api/admin/updates/upload/route.ts");
    const uploadClient = await source("components/dashboard/updates-manager.tsx");

    expect(uploadRoute).toContain("request.body");
    expect(uploadRoute).not.toContain("request.formData()");
    expect(uploadRoute.match(/sha256\.digest\("hex"\)/g)).toHaveLength(1);
    expect(uploadRoute).toContain("storedSize !== nextOffset");
    expect(uploadRoute).toContain("Uploaded file is not an APK archive");
    expect(uploadClient).toContain("sendUploadChunk");
    expect(uploadClient).toContain("xhr.send(chunk)");
    expect(uploadClient).toContain("UPLOAD_RETRY_COUNT");
    expect(uploadClient).not.toContain("new FormData()");
  });

  it("keeps local APK artifacts out of source and Docker build contexts", async () => {
    const gitignore = await source(".gitignore");
    const dockerignore = await source(".dockerignore");

    expect(gitignore).toContain("public/downloads/*");
    expect(gitignore).toContain("*.apk");
    expect(dockerignore).toContain("public/downloads/*");
  });

  it("uses an atomic bounded lease claim for grant revocations", async () => {
    const maintenance = await source("lib/server/maintenance.ts");

    expect(maintenance).toContain("FOR UPDATE SKIP LOCKED");
    expect(maintenance).toContain("lease_token = $2");
    expect(maintenance).toContain("LIMIT $1");
  });
});

describe("database least privilege", () => {
  it("stores browser diagnostics without retaining a client IP", async () => {
    const browserMigration = await source("db/migrations/007_monitor_browser_checks.sql");
    const browserRoute = await source("app/api/monitor/v1/browser-checks/route.ts");

    expect(browserMigration).toContain("country_code text");
    expect(browserMigration).toContain("region text");
    expect(browserMigration).toContain("asn bigint");
    expect(browserMigration).not.toMatch(/client_ip|ip_address/i);
    expect(browserRoute).toContain("getLocalGeoData(clientAddress)");
    expect(browserRoute).not.toContain("client_ip");
  });

  it("revokes legacy broad grants and grants only explicit runtime access", async () => {
    const migration = await source("scripts/migrate.mjs");
    const privilegeSection = migration.slice(
      migration.indexOf(
        'await client.query("REVOKE CREATE ON SCHEMA public FROM PUBLIC")',
      ),
    );

    expect(privilegeSection).toContain(
      "REVOKE ALL PRIVILEGES ON ALL TABLES IN SCHEMA public",
    );
    expect(privilegeSection).not.toMatch(
      /GRANT\s+SELECT,\s+INSERT,\s+UPDATE,\s+DELETE\s+ON ALL TABLES/i,
    );
    expect(privilegeSection).toContain(
      "GRANT INSERT ON TABLE public.web_audit_events",
    );
    expect(privilegeSection).not.toMatch(
      /GRANT[\s\S]{0,200}public\.schema_migrations/i,
    );
  });
});
