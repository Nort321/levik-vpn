import { NextRequest } from "next/server";
import { describe, expect, it, vi } from "vitest";

vi.mock("server-only", () => ({}));

const mocks = vi.hoisted(() => ({
  getSessionByToken: vi.fn(),
  adminUserKeys: new Set(["usr_admin_12345678901234567890"]),
}));

vi.mock("@/lib/server/session-store", () => ({
  getSessionByToken: mocks.getSessionByToken,
}));
vi.mock("@/lib/server/env", () => ({
  getEnvironment: () => ({ adminUserKeys: mocks.adminUserKeys }),
}));
vi.mock("@/lib/server/security", () => ({
  assertOriginHeader: vi.fn(),
  csrfTokenForSession: (rawToken: string) => `csrf-${rawToken}`,
}));

import {
  authenticateSupportAdminMutation,
  requireSupportAdmin,
} from "@/lib/server/account/support-admin";
import { csrfTokenForSession } from "@/lib/server/security";

const rawToken = "a".repeat(43);
const adminSession = {
  rawToken,
  userKey: "usr_admin_12345678901234567890",
};

function request(csrf?: string): NextRequest {
  return new NextRequest("https://leviknet.com/api/account/v1/admin/support", {
    headers: {
      cookie: `__Host-levik_session=${rawToken}`,
      origin: "https://leviknet.com",
      ...(csrf ? { "x-levik-csrf": csrf } : {}),
    },
  });
}

describe("support staff authorization", () => {
  it("requires the legacy session user key to be in ADMIN_USER_KEYS", async () => {
    mocks.getSessionByToken.mockResolvedValueOnce({
      ...adminSession,
      userKey: "usr_regular_1234567890123456789",
    });
    await expect(requireSupportAdmin(request())).rejects.toMatchObject({
      code: "access_denied",
    });
  });

  it("requires the existing legacy-session CSRF token for mutations", async () => {
    mocks.getSessionByToken.mockResolvedValue(adminSession);
    await expect(authenticateSupportAdminMutation(request())).rejects.toMatchObject({
      code: "csrf_failed",
    });
    await expect(
      authenticateSupportAdminMutation(
        request(csrfTokenForSession(adminSession.rawToken)),
      ),
    ).resolves.toMatchObject({ userKey: adminSession.userKey });
  });
});
