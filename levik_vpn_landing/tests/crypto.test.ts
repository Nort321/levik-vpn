import { describe, expect, it } from "vitest";

import {
  constantTimeEqual,
  decodeSecret,
  decryptString,
  encryptString,
  hmacHex,
  randomToken,
  sha256Hex,
} from "@/lib/server/crypto";

const KEY = Buffer.alloc(32, 7);

describe("security crypto primitives", () => {
  it("creates a 256-bit URL-safe opaque token", () => {
    const token = randomToken();
    expect(token).toMatch(/^[A-Za-z0-9_-]{43}$/);
    expect(Buffer.from(token, "base64url")).toHaveLength(32);
  });

  it("encrypts and authenticates a server-side secret", () => {
    const encrypted = encryptString("sensitive-value", KEY, "test-purpose");
    expect(encrypted).not.toContain("sensitive-value");
    expect(decryptString(encrypted, KEY, "test-purpose")).toBe(
      "sensitive-value",
    );
  });

  it("rejects a ciphertext reused for another purpose", () => {
    const encrypted = encryptString("sensitive-value", KEY, "purpose-a");
    expect(() => decryptString(encrypted, KEY, "purpose-b")).toThrow();
  });

  it("rejects tampered ciphertext", () => {
    const encrypted = encryptString("sensitive-value", KEY, "test-purpose");
    const parts = encrypted.split(".");
    parts[2] = `${parts[2]?.slice(0, -1)}A`;
    expect(() =>
      decryptString(parts.join("."), KEY, "test-purpose"),
    ).toThrow();
  });

  it("compares equal values without accepting length differences", () => {
    expect(constantTimeEqual("signature", "signature")).toBe(true);
    expect(constantTimeEqual("signature", "signature-extra")).toBe(false);
  });

  it("produces the expected SHA-256 representation", () => {
    expect(sha256Hex("abc")).toBe(
      "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
    );
  });

  it("matches the primary bridge HMAC interoperability vector", () => {
    const secret =
      "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8";
    const canonical = [
      "POST",
      "/levik-vpn-bot/internal/cabinet/v1/catalog?include=all",
      "1785175200",
      "00112233445566778899aabbccddeeff",
      "76aa4ac5-0693-4930-bc7a-850940d23e90",
      "6d2bfc0147054b3d0ad9dac8d06b6f65bf79270378498f8a20cb87db6f07e3b6",
      "93a23971a914e5eacbf0a8d25154cda309c3c1c72fbb9914d47c60f3cb681588",
    ].join("\n");

    expect(hmacHex(decodeSecret(secret), canonical)).toBe(
      "c1f3cd1db6d1dad157a39c3c367021f679ad9f12a1099e1d34e7feb7d47ee0c3",
    );
  });
});
