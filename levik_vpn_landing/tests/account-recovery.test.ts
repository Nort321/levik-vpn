import { beforeEach, describe, expect, it, vi } from "vitest";

const mocks = vi.hoisted(() => ({
  query: vi.fn(),
}));

vi.mock("server-only", () => ({}));
vi.mock("@/lib/server/db", () => ({
  query: mocks.query,
  withTransaction: vi.fn(),
}));
vi.mock("@/lib/server/env", () => ({
  getEnvironment: () => ({ AUDIT_HMAC_KEY: "D".repeat(43) }),
}));

import { authenticateRecoveryCode } from "@/lib/server/account/recovery";

describe("Levik Account recovery codes", () => {
  beforeEach(() => {
    mocks.query.mockReset();
  });

  it("consumes a recovery code atomically and refuses reuse", async () => {
    mocks.query
      .mockResolvedValueOnce({
        rowCount: 1,
        rows: [
          {
            account_id: "018d1557-d946-7c03-8c42-f83a43d91c8e",
            levik_id: "LVK-ABCD-EFGH-JKMN",
            display_name: "Test user",
            status: "active",
            created_at: new Date("2026-08-01T00:00:00.000Z"),
          },
        ],
      })
      .mockResolvedValueOnce({ rowCount: 0, rows: [] });

    await expect(
      authenticateRecoveryCode(
        "LVK-ABCD-EFGH-JKMN",
        "ABCD-EFGH-JKMN-PQRS-TUVW",
      ),
    ).resolves.toMatchObject({ levikId: "LVK-ABCD-EFGH-JKMN" });
    await expect(
      authenticateRecoveryCode(
        "LVK-ABCD-EFGH-JKMN",
        "ABCD-EFGH-JKMN-PQRS-TUVW",
      ),
    ).rejects.toMatchObject({ code: "invalid_credentials" });
    expect(mocks.query).toHaveBeenCalledTimes(2);
    expect(mocks.query.mock.calls[0]?.[0]).toContain("used_at = now()");
  });
});
