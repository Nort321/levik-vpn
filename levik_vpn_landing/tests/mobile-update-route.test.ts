import { beforeEach, describe, expect, it, vi } from "vitest";

const updateMocks = vi.hoisted(() => ({
  getActiveAppUpdate: vi.fn(),
}));

vi.mock("server-only", () => ({}));
vi.mock("@/lib/server/app-updates", () => ({
  getActiveAppUpdate: updateMocks.getActiveAppUpdate,
}));

import { GET } from "@/app/api/mobile/v1/update/route";

describe("mobile update manifest", () => {
  beforeEach(() => {
    updateMocks.getActiveAppUpdate.mockReset();
  });

  it("returns an explicit null update when no release is active", async () => {
    updateMocks.getActiveAppUpdate.mockResolvedValue(null);

    const response = await GET();

    expect(response.status).toBe(200);
    expect(await response.json()).toEqual({ ok: true, update: null });
    expect(response.headers.get("cache-control")).toContain("no-store");
  });

  it("fails closed when the update database is unavailable", async () => {
    const errorLog = vi.spyOn(console, "error").mockImplementation(() => {});
    updateMocks.getActiveAppUpdate.mockRejectedValue(
      new Error("database unavailable"),
    );

    try {
      const response = await GET();

      expect(response.status).toBe(503);
      expect(await response.json()).toEqual({
        ok: false,
        code: "temporarily_unavailable",
      });
      expect(response.headers.get("cache-control")).toContain("no-store");
      expect(response.headers.get("retry-after")).toBe("60");
    } finally {
      errorLog.mockRestore();
    }
  });
});
