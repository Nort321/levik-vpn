import { beforeEach, describe, expect, it, vi } from "vitest";
import { NextRequest } from "next/server";

vi.mock("server-only", () => ({}));

const mocks = vi.hoisted(() => ({
  authenticateAccountMutation: vi.fn(),
  revokeAccountSession: vi.fn(),
  clearAccountSessionCookie: vi.fn(),
  writeAuditEvent: vi.fn(),
}));

vi.mock("@/lib/server/account/http", () => ({
  authenticateAccountMutation: mocks.authenticateAccountMutation,
  accountJson: (body: unknown) => Response.json(body),
  accountErrorResponse: vi.fn(() => Response.json({ ok: false }, { status: 500 })),
}));
vi.mock("@/lib/server/account/session", () => ({
  revokeAccountSession: mocks.revokeAccountSession,
  clearAccountSessionCookie: mocks.clearAccountSessionCookie,
}));
vi.mock("@/lib/server/audit", () => ({
  writeAuditEvent: mocks.writeAuditEvent,
}));

import { DELETE } from "@/app/api/account/v1/sessions/[sessionId]/route";

const ACCOUNT_ID = "018d1557-d946-7c03-8c42-f83a43d91c8e";
const CURRENT_SESSION_ID = "028d1557-d946-7c03-8c42-f83a43d91c8e";
const OTHER_SESSION_ID = "038d1557-d946-7c03-8c42-f83a43d91c8e";

describe("account session route", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    mocks.authenticateAccountMutation.mockResolvedValue({
      accountId: ACCOUNT_ID,
      publicId: CURRENT_SESSION_ID,
    });
    mocks.revokeAccountSession.mockResolvedValue(true);
  });

  it("clears the account cookie when deleting the current session", async () => {
    const response = await DELETE(
      new NextRequest("https://leviknet.com/api/account/v1/sessions/current", {
        method: "DELETE",
      }),
      { params: Promise.resolve({ sessionId: CURRENT_SESSION_ID }) },
    );

    expect(response.status).toBe(200);
    expect(mocks.revokeAccountSession).toHaveBeenCalledWith(
      ACCOUNT_ID,
      CURRENT_SESSION_ID,
    );
    expect(mocks.clearAccountSessionCookie).toHaveBeenCalledOnce();
  });

  it("keeps the current cookie when deleting another owned session", async () => {
    const response = await DELETE(
      new NextRequest("https://leviknet.com/api/account/v1/sessions/other", {
        method: "DELETE",
      }),
      { params: Promise.resolve({ sessionId: OTHER_SESSION_ID }) },
    );

    expect(response.status).toBe(200);
    expect(mocks.revokeAccountSession).toHaveBeenCalledWith(
      ACCOUNT_ID,
      OTHER_SESSION_ID,
    );
    expect(mocks.clearAccountSessionCookie).not.toHaveBeenCalled();
  });
});
