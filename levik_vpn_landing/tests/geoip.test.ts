import { describe, expect, it, vi } from "vitest";

vi.mock("server-only", () => ({}));

import { geoDataFromRecords } from "@/lib/server/geoip";

describe("local GeoIP records", () => {
  it("combines city and ASN MMDB records without a network request", () => {
    expect(
      geoDataFromRecords(
        {
          country: { iso_code: "NL", names: { en: "Netherlands", ru: "Нидерланды" } },
          subdivisions: [{ names: { ru: "Северная Голландия" } }],
          city: { names: { ru: "Амстердам" } },
          location: { latitude: 52.3728, longitude: 4.8936 },
        },
        {
          autonomous_system_number: 213_520,
          autonomous_system_organization: "Senko Digital LLC",
        },
      ),
    ).toEqual({
      country: "Нидерланды",
      countryCode: "NL",
      region: "Северная Голландия",
      city: "Амстердам",
      timezone: "Europe/Amsterdam",
      flagEmoji: "🇳🇱",
      provider: "Senko Digital LLC",
      organization: "Senko Digital LLC",
      asn: 213_520,
    });
  });

  it("rejects malformed local database records", () => {
    expect(geoDataFromRecords({ country: { iso_code: "NLD" } }, { autonomous_system_number: -1 })).toBeNull();
  });
});
