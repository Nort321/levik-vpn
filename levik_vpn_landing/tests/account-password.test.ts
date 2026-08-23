import { describe, expect, it, vi } from "vitest";

vi.mock("server-only", () => ({}));

import {
  hashPassword,
  verifyPasswordHash,
} from "@/lib/server/account/password";

describe("Levik Account password hashing", () => {
  it("uses a random salt and verifies the exact passphrase", async () => {
    const first = await hashPassword("correct horse battery staple");
    const second = await hashPassword("correct horse battery staple");

    expect(first.salt).toHaveLength(16);
    expect(first.derivedKey).toHaveLength(32);
    expect(first.salt.equals(second.salt)).toBe(false);
    await expect(
      verifyPasswordHash(
        "correct horse battery staple",
        first.salt,
        first.derivedKey,
      ),
    ).resolves.toBe(true);
    await expect(
      verifyPasswordHash("wrong passphrase", first.salt, first.derivedKey),
    ).resolves.toBe(false);
  });

  it("rejects a short password before storing it", async () => {
    await expect(hashPassword("short")).rejects.toMatchObject({
      code: "invalid_password",
    });
  });
});
