import "server-only";

import { randomBytes } from "node:crypto";

import { AccountApiError } from "@/lib/server/account/errors";

const HUMAN_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
const LEVIK_ID_PATTERN =
  /^LVK-[A-HJ-NP-Z2-9]{4}-[A-HJ-NP-Z2-9]{4}-[A-HJ-NP-Z2-9]{4}$/;
const RECOVERY_CODE_PATTERN =
  /^[A-HJ-NP-Z2-9]{4}(?:-[A-HJ-NP-Z2-9]{4}){4}$/;
const ACTIVATION_CODE_PATTERN =
  /^[A-HJ-NP-Z2-9]{4}(?:-[A-HJ-NP-Z2-9]{4}){3}$/;

function randomHumanCharacters(length: number): string {
  return [...randomBytes(length)]
    .map((value) => HUMAN_ALPHABET[value & 31])
    .join("");
}

function grouped(value: string, size = 4): string {
  return value.match(new RegExp(`.{1,${size}}`, "g"))?.join("-") ?? value;
}

export function generateLevikId(): string {
  return `LVK-${grouped(randomHumanCharacters(12))}`;
}

export function normalizeLevikId(value: string): string {
  const normalized = value.normalize("NFKC").trim().toUpperCase();
  if (!LEVIK_ID_PATTERN.test(normalized)) {
    throw new AccountApiError("invalid_levik_id", 400);
  }
  return normalized;
}

export function generateRecoveryCode(): string {
  return grouped(randomHumanCharacters(20));
}

export function normalizeRecoveryCode(value: string): string {
  const normalized = value.normalize("NFKC").trim().toUpperCase();
  if (!RECOVERY_CODE_PATTERN.test(normalized)) {
    throw new AccountApiError("invalid_recovery_code", 400);
  }
  return normalized;
}

export function generateActivationCode(): string {
  return grouped(randomHumanCharacters(16));
}

export function normalizeActivationCode(value: string): string {
  const normalized = value.normalize("NFKC").trim().toUpperCase();
  if (!ACTIVATION_CODE_PATTERN.test(normalized)) {
    throw new AccountApiError("invalid_activation_code", 400);
  }
  return normalized;
}

export function generateSupportReference(): string {
  return `SUP-${grouped(randomHumanCharacters(8))}`;
}

export function cleanDisplayText(
  value: string,
  maximumLength: number,
  fallback?: string,
): string {
  const withoutControls = [...value.normalize("NFC")]
    .filter((character) => {
      const codePoint = character.codePointAt(0) ?? 0;
      return codePoint >= 32 && codePoint !== 127;
    })
    .join("")
    .trim();
  const cleaned = [...withoutControls].slice(0, maximumLength).join("");
  if (!cleaned) {
    if (fallback) {
      return fallback;
    }
    throw new AccountApiError("invalid_text", 400);
  }
  return cleaned;
}
