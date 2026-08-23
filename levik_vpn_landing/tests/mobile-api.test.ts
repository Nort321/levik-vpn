import { describe, expect, it, vi } from "vitest";

vi.mock("server-only", () => ({}));

import {
  assertFreshMobileTimestamp,
  mobileChallengeSchema,
  mobileRequestProof,
  readMobileJson,
} from "@/lib/server/mobile-api";

describe("mobile API boundary", () => {
  it("accepts the exact flat Android registration payload", async () => {
    const payload = JSON.stringify({
      publicKeySpki: "A".repeat(563),
      deviceLabel: "Nikita's Pixel",
      deviceModel: "Pixel 10 Pro",
      deviceOs: "Android 16",
      appVersion: "1.0.0",
      requestSigningAlgorithm: "PS256",
      profileEncryptionAlgorithm: "RSA-OAEP-256+A256GCM",
    });
    const request = new Request(
      "https://leviknet.com/api/mobile/v1/auth/challenge",
      {
        method: "POST",
        headers: {
          "Content-Type": "application/json",
          "Content-Length": Buffer.byteLength(payload).toString(),
        },
        body: payload,
      },
    );
    const parsed = await readMobileJson(request, mobileChallengeSchema);

    expect(parsed.value.deviceModel).toBe("Pixel 10 Pro");
    expect(parsed.body.toString()).toBe(payload);
  });

  it("rejects nested or extra device metadata", () => {
    expect(
      mobileChallengeSchema.safeParse({
        publicKeySpki: "A".repeat(563),
        device: {
          label: "Pixel",
          model: "Pixel",
          osVersion: "Android 16",
          appVersion: "1",
        },
      }).success,
    ).toBe(false);
  });

  it("accepts only a boolean account activation capability", () => {
    const payload = {
      publicKeySpki: "A".repeat(563),
      deviceLabel: "Pixel",
      deviceModel: "Pixel 10",
      deviceOs: "Android 16",
      appVersion: "2.0.0",
      requestSigningAlgorithm: "PS256",
      profileEncryptionAlgorithm: "RSA-OAEP-256+A256GCM",
    };
    expect(
      mobileChallengeSchema.safeParse({
        ...payload,
        accountActivationSupported: true,
      }).success,
    ).toBe(true);
    expect(
      mobileChallengeSchema.safeParse({
        ...payload,
        accountActivationSupported: "true",
      }).success,
    ).toBe(false);
  });

  it("requires canonical proof headers and a fresh timestamp", () => {
    const now = 1_785_175_200_000;
    const headers = new Headers({
      "X-Levik-Device-Id": "a".repeat(64),
      "X-Levik-Timestamp": String(now / 1_000),
      "X-Levik-Nonce": Buffer.alloc(16, 1).toString("base64url"),
      "X-Levik-Signature": "A".repeat(512),
    });
    const proof = mobileRequestProof(headers);

    expect(() =>
      assertFreshMobileTimestamp(proof.timestamp, now),
    ).not.toThrow();
    expect(() =>
      assertFreshMobileTimestamp(proof.timestamp, now + 120_001),
    ).toThrow("mobile API request");
  });
});
