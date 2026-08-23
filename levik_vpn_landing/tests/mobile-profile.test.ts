import { describe, expect, it, vi } from "vitest";

vi.mock("server-only", () => ({}));

import {
  mobileProfileSubscriptionExpiresAt,
  validateMobileProfileContent,
} from "@/lib/server/mobile-profile";

describe("mobile profile response boundary", () => {
  const issuedAt = new Date("2026-07-29T12:00:00.000Z");

  it("keeps the encrypted local profile when subscription expiry is absent", () => {
    expect(
      mobileProfileSubscriptionExpiresAt(null, issuedAt),
    ).toBeNull();
  });

  it("preserves the actual subscription expiry without a cache TTL", () => {
    expect(
      mobileProfileSubscriptionExpiresAt(
        "2027-07-29T12:00:00.000Z",
        issuedAt,
      ),
    ).toBe("2027-07-29T12:00:00.000Z");
  });

  it("rejects an already expired subscription", () => {
    expect(() =>
      mobileProfileSubscriptionExpiresAt(
        "2026-07-29T11:59:59.000Z",
        issuedAt,
      ),
    ).toThrow("mobile API request");
  });

  it("preserves current and future XHTTP/XMUX fields verbatim", () => {
    const source =
      '{"outbounds":[{"streamSettings":{"network":"xhttp","xhttpSettings":{"extra":{"xmux":{"maxConcurrency":16},"sessionIDPlacement":"path","sessionIDKey":"sid","sessionPlacement":"path","sessionKey":"legacy"}}}}]}';
    const response = new Response(source, {
      headers: { "Content-Type": "application/json; charset=utf-8" },
    });

    expect(
      validateMobileProfileContent(Buffer.from(source), response),
    ).toEqual({
      mediaType: "application/json",
      content: source,
    });
  });

  it.each([
    ["HTML media type", "<html>not a profile</html>", "text/html"],
    ["HTML sniffing", "<!doctype html><title>Error</title>", "text/plain"],
    ["NUL content", "vless://example\0hidden", "text/plain"],
    ["empty content", "  \n", "text/plain"],
  ])("rejects %s", (_name, content, mediaType) => {
    const response = new Response(content, {
      headers: { "Content-Type": mediaType },
    });
    expect(() =>
      validateMobileProfileContent(Buffer.from(content), response),
    ).toThrow("mobile API request");
  });
});
