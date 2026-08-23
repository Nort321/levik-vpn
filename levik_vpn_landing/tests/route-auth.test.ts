import { describe, expect, it, vi } from "vitest";

vi.mock("server-only", () => ({}));

import { assertEncodedFormRequestHeaders } from "@/lib/server/route-auth";
import { clientAddressFromHeaders } from "@/lib/server/security";

const validHeaders = () =>
  new Headers({
    "content-length": "128",
    "content-type": "application/x-www-form-urlencoded",
  });

describe("encoded form request headers", () => {
  it("accepts a bounded form with an explicit length", () => {
    expect(() => assertEncodedFormRequestHeaders(validHeaders())).not.toThrow();
  });

  it.each([
    ["missing length", {}],
    ["zero length", { "content-length": "0" }],
    ["non-decimal length", { "content-length": "1e3" }],
    ["oversized length", { "content-length": "16385" }],
    ["chunked body", { "transfer-encoding": "chunked" }],
  ])("rejects %s", (_name, overrides) => {
    const headers = validHeaders();
    if ("content-length" in overrides) {
      headers.set("content-length", overrides["content-length"]);
    } else if (Object.keys(overrides).length === 0) {
      headers.delete("content-length");
    }
    if ("transfer-encoding" in overrides) {
      headers.set("transfer-encoding", overrides["transfer-encoding"]);
    }

    expect(() => assertEncodedFormRequestHeaders(headers)).toThrow(
      "Invalid form request",
    );
  });
});

describe("trusted client address", () => {
  it("prefers the Caddy-owned header over framework proxy headers", () => {
    const headers = new Headers({
      "x-levik-client-ip": "31.77.162.26",
      "x-forwarded-for": "172.19.0.3",
    });

    expect(clientAddressFromHeaders(headers)).toBe("31.77.162.26");
  });

  it("keeps the forwarded-for fallback for trusted direct tests", () => {
    const headers = new Headers({ "x-forwarded-for": "203.0.113.10" });

    expect(clientAddressFromHeaders(headers)).toBe("203.0.113.10");
  });

  it("rejects address chains and malformed values", () => {
    expect(() =>
      clientAddressFromHeaders(
        new Headers({ "x-levik-client-ip": "203.0.113.10, 172.19.0.3" }),
      ),
    ).toThrow("Client address is unavailable");
    expect(() =>
      clientAddressFromHeaders(
        new Headers({ "x-levik-client-ip": "not-an-address" }),
      ),
    ).toThrow("Client address is invalid");
  });
});
