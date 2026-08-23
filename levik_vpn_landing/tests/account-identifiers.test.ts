import { describe, expect, it, vi } from "vitest";

vi.mock("server-only", () => ({}));

import {
  cleanDisplayText,
  generateActivationCode,
  generateLevikId,
  generateRecoveryCode,
  normalizeActivationCode,
  normalizeLevikId,
  normalizeRecoveryCode,
} from "@/lib/server/account/identifiers";

describe("Levik Account identifiers", () => {
  it("generates canonical human-readable identifiers", () => {
    expect(generateLevikId()).toMatch(
      /^LVK-[A-HJ-NP-Z2-9]{4}-[A-HJ-NP-Z2-9]{4}-[A-HJ-NP-Z2-9]{4}$/,
    );
    expect(generateRecoveryCode()).toMatch(
      /^[A-HJ-NP-Z2-9]{4}(?:-[A-HJ-NP-Z2-9]{4}){4}$/,
    );
    expect(generateActivationCode()).toMatch(
      /^[A-HJ-NP-Z2-9]{4}(?:-[A-HJ-NP-Z2-9]{4}){3}$/,
    );
  });

  it("normalizes case without accepting ambiguous or shortened values", () => {
    expect(normalizeLevikId(" lvk-abcd-efgh-jkmn ")).toBe(
      "LVK-ABCD-EFGH-JKMN",
    );
    expect(normalizeRecoveryCode("abcd-efgh-jkmn-pqrs-tuvw")).toBe(
      "ABCD-EFGH-JKMN-PQRS-TUVW",
    );
    expect(normalizeActivationCode("abcd-efgh-jkmn-pqrs")).toBe(
      "ABCD-EFGH-JKMN-PQRS",
    );
    expect(() => normalizeLevikId("LVK-0000-AAAA-BBBB")).toThrow(
      "The account request could not be completed",
    );
  });

  it("truncates display text on Unicode code point boundaries", () => {
    expect(cleanDisplayText("😀😀", 1)).toBe("😀");
    expect(cleanDisplayText("\u0000  Nikita  ", 120)).toBe("Nikita");
  });
});
