import { describe, expect, it, vi } from "vitest";

vi.mock("server-only", () => ({}));

import { rdapDataFromResponse } from "@/lib/server/rdap";

const response = {
  objectClassName: "ip network",
  handle: "31.0.0.0 - 31.255.255.255",
  startAddress: "31.0.0.0",
  endAddress: "31.255.255.255",
  ipVersion: "v4",
  name: "EU-ZZ-31",
  type: "ALLOCATED PA",
  country: "NL",
  events: [
    { eventAction: "registration", eventDate: "2010-05-18T12:00:00Z" },
    { eventAction: "last changed", eventDate: "2025-02-02T08:30:00Z" },
  ],
  cidr0_cidrs: [{ v4prefix: "31.0.0.0", length: 8 }],
};

describe("RDAP response normalization", () => {
  it("extracts a bounded registration record and CIDR", () => {
    expect(
      rdapDataFromResponse(
        response,
        "https://rdap.db.ripe.net/",
        "31.77.162.26",
      ),
    ).toEqual({
      networks: ["31.0.0.0/8"],
      data: {
        network: "31.0.0.0/8",
        registry: "RIPE NCC",
        name: "EU-ZZ-31",
        handle: "31.0.0.0 - 31.255.255.255",
        type: "ALLOCATED PA",
        countryCode: "NL",
        rangeStart: "31.0.0.0",
        rangeEnd: "31.255.255.255",
        registeredAt: "2010-05-18T12:00:00Z",
        updatedAt: "2025-02-02T08:30:00Z",
      },
    });
  });

  it("rejects a response for a different address range", () => {
    expect(
      rdapDataFromResponse(
        response,
        "https://rdap.db.ripe.net/",
        "8.8.8.8",
      ),
    ).toBeNull();
  });
});
