import { describe, expect, it } from "vitest";

import {
  commitmentForFragment,
  decryptNote,
  encryptNote,
  keyFromFragment,
  MAX_NOTE_CHARACTERS,
  noteCharacterCount,
} from "@/lib/notes/crypto";

describe("Levik Notes browser cryptography", () => {
  it("encrypts and decrypts Unicode text with a 256-bit URL-fragment key", async () => {
    const plaintext = "Секретное сообщение 🔐\nВторая строка";
    const encrypted = await encryptNote(plaintext);

    expect(encrypted.id).toMatch(/^[A-Za-z0-9_-]{22}$/);
    expect(keyFromFragment(encrypted.keyFragment)).toHaveLength(32);
    expect(await commitmentForFragment(encrypted.id, encrypted.keyFragment)).toBe(
      encrypted.keyCommitment,
    );
    await expect(
      decryptNote(
        encrypted.id,
        encrypted.keyFragment,
        encrypted.iv,
        encrypted.ciphertext,
      ),
    ).resolves.toBe(plaintext);
  });

  it("binds ciphertext authentication to the note id", async () => {
    const encrypted = await encryptNote("Нельзя перенести в другую заметку");
    const otherId = (await encryptNote("другая")).id;

    await expect(
      decryptNote(otherId, encrypted.keyFragment, encrypted.iv, encrypted.ciphertext),
    ).rejects.toThrow();
  });

  it("counts astral Unicode characters as one symbol and enforces the limit", async () => {
    expect(noteCharacterCount("🔐")).toBe(1);
    await expect(encryptNote("а".repeat(MAX_NOTE_CHARACTERS + 1))).rejects.toThrow(
      "invalid_plaintext_length",
    );
  });
});
