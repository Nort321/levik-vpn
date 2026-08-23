import { describe, expect, it } from "vitest";

import { isIpCheckSnapshot } from "@/lib/ip-check";

describe("IP check payload validation", () => {
  const validSnapshot = {
    ip: "203.0.113.10",
    version: "IPv4",
    country: "Россия",
    countryCode: "RU",
    region: "Москва",
    city: "Москва",
    timezone: "Europe/Moscow",
    flagEmoji: "🇷🇺",
    provider: "Example ISP",
    organization: "Example Network",
    asn: 64_496,
    registration: {
      network: "203.0.113.0/24",
      registry: "APNIC",
      name: "TEST-NET-3",
      handle: "NET-203-0-113-0-1",
      type: "ALLOCATED PORTABLE",
      countryCode: "US",
      rangeStart: "203.0.113.0",
      rangeEnd: "203.0.113.255",
      registeredAt: "2010-01-01T00:00:00.000Z",
      updatedAt: "2026-01-01T00:00:00.000Z",
    },
    protection: "direct",
    checkedAt: "2026-08-14T07:00:00.000Z",
  } as const;

  it("accepts a complete checker snapshot", () => {
    expect(isIpCheckSnapshot(validSnapshot)).toBe(true);
  });

  it("rejects invalid IP version and protection state", () => {
    expect(isIpCheckSnapshot({ ...validSnapshot, version: "IPv5" })).toBe(false);
    expect(isIpCheckSnapshot({ ...validSnapshot, protection: "maybe" })).toBe(false);
  });
});
