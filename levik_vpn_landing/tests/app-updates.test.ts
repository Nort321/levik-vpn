import { beforeEach, describe, expect, it, vi } from "vitest";

const adminPolicy = vi.hoisted(() => ({
  enabled: false,
  userKeys: new Set<string>(),
}));

vi.mock("server-only", () => ({}));
vi.mock("@/lib/server/db", () => ({
  query: vi.fn(),
  withTransaction: vi.fn(),
}));
vi.mock("@/lib/server/env", () => ({
  getEnvironment: () => ({
    FEATURE_ADMIN_UPDATES_ENABLED: adminPolicy.enabled,
    adminUserKeys: adminPolicy.userKeys,
  }),
}));

import { isAdminUser } from "@/lib/server/app-updates";

describe("app update administrator policy", () => {
  const allowedUserKey = `usr_${"b".repeat(20)}`;

  beforeEach(() => {
    adminPolicy.enabled = false;
    adminPolicy.userKeys = new Set<string>();
  });

  it("denies every user while admin updates are disabled", () => {
    adminPolicy.userKeys = new Set([allowedUserKey]);

    expect(isAdminUser(allowedUserKey)).toBe(false);
  });

  it("allows only configured users when the feature is enabled", () => {
    adminPolicy.enabled = true;
    adminPolicy.userKeys = new Set([allowedUserKey]);

    expect(isAdminUser(allowedUserKey)).toBe(true);
    expect(isAdminUser(`usr_${"c".repeat(20)}`)).toBe(false);
    expect(isAdminUser(null)).toBe(false);
  });
});
