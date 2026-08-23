import { describe, expect, it, vi } from "vitest";

const mocks = vi.hoisted(() => ({
  clientQuery: vi.fn(),
}));

vi.mock("server-only", () => ({}));
vi.mock("@/lib/server/db", () => ({
  query: vi.fn(),
  withTransaction: async (
    callback: (client: { query: typeof mocks.clientQuery }) => Promise<unknown>,
  ) => callback({ query: mocks.clientQuery }),
}));

import { replyToSupportTicket } from "@/lib/server/account/support";

describe("support ticket ownership", () => {
  it("does not insert a reply when the ticket is not owned by the account", async () => {
    mocks.clientQuery.mockReset();
    mocks.clientQuery.mockResolvedValueOnce({ rowCount: 0, rows: [] });

    await expect(
      replyToSupportTicket({
        accountId: "018d1557-d946-7c03-8c42-f83a43d91c8e",
        ticketId: "028d1557-d946-7c03-8c42-f83a43d91c8e",
        message: "Please help",
      }),
    ).rejects.toMatchObject({ code: "support_ticket_not_found" });
    expect(mocks.clientQuery).toHaveBeenCalledTimes(1);
    expect(mocks.clientQuery.mock.calls[0]?.[0]).toContain("account_id = $2");
  });
});
