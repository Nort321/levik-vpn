import {
  constants,
  createDecipheriv,
  generateKeyPairSync,
  privateDecrypt,
  sign,
} from "node:crypto";

import { describe, expect, it, vi } from "vitest";

vi.mock("server-only", () => ({}));

import { sha256Hex } from "@/lib/server/crypto";
import {
  encryptMobilePayload,
  mobileRequestCanonical,
  parseMobilePublicKey,
  verifyMobileRequestSignature,
} from "@/lib/server/mobile-crypto";

function rsa3072() {
  return generateKeyPairSync("rsa", {
    modulusLength: 3_072,
    publicExponent: 0x10001,
  });
}

describe("mobile proof of possession", () => {
  it("derives the device id from SPKI and verifies the exact PSS canonical form", () => {
    const { privateKey, publicKey } = rsa3072();
    const der = publicKey.export({ format: "der", type: "spki" });
    expect(Buffer.isBuffer(der)).toBe(true);
    const encodedSpki = Buffer.from(der).toString("base64url");
    const parsed = parseMobilePublicKey(encodedSpki);
    const body = Buffer.from('{"subscriptionId":"example"}');
    const proof = {
      timestamp: "1785175200",
      nonce: Buffer.alloc(16, 9).toString("base64url"),
      deviceId: sha256Hex(Buffer.from(der)),
    };
    const canonical = mobileRequestCanonical(
      "post",
      "/api/mobile/v1/tunnel-profile",
      proof,
      "A".repeat(43),
      body,
    );
    const signature = sign("sha256", Buffer.from(canonical), {
      key: privateKey,
      padding: constants.RSA_PKCS1_PSS_PADDING,
      saltLength: 32,
    }).toString("base64url");

    expect(parsed.deviceId).toBe(proof.deviceId);
    expect(canonical).toBe(
      [
        "v1",
        "POST",
        "/api/mobile/v1/tunnel-profile",
        proof.timestamp,
        proof.nonce,
        proof.deviceId,
        sha256Hex("A".repeat(43)),
        sha256Hex(body),
      ].join("\n"),
    );
    expect(
      verifyMobileRequestSignature(
        parsed.key,
        canonical,
        signature,
        "PS256",
      ),
    ).toBe(true);
    expect(
      verifyMobileRequestSignature(
        parsed.key,
        `${canonical}\ntampered`,
        signature,
        "PS256",
      ),
    ).toBe(false);
  });

  it("supports Android 8-14 RS256 without weakening the RSA key size", () => {
    const { privateKey, publicKey } = rsa3072();
    const canonical = "v1\nPOST\n/path\n1\nnonce\ndevice\naccess\nbody";
    const signature = sign("sha256", Buffer.from(canonical), {
      key: privateKey,
      padding: constants.RSA_PKCS1_PADDING,
    }).toString("base64url");

    expect(
      verifyMobileRequestSignature(
        publicKey,
        canonical,
        signature,
        "RS256",
      ),
    ).toBe(true);
    expect(
      verifyMobileRequestSignature(
        publicKey,
        canonical,
        signature,
        "PS256",
      ),
    ).toBe(false);
  });

  it.each([
    ["RSA-OAEP-256+A256GCM" as const, "sha256"],
    ["RSA-OAEP+A256GCM" as const, "sha1"],
  ])("encrypts authenticated profile data with %s", (algorithm, oaepHash) => {
    const { privateKey, publicKey } = rsa3072();
    const plaintext = Buffer.from('{"version":1,"source":{"content":"secret"}}');
    const aad = Buffer.from("bound-device-and-subscription");
    const envelope = encryptMobilePayload(
      plaintext,
      publicKey,
      aad,
      algorithm,
    );

    const contentKey = privateDecrypt(
      {
        key: privateKey,
        oaepHash,
        padding: constants.RSA_PKCS1_OAEP_PADDING,
      },
      Buffer.from(envelope.encryptedKey, "base64url"),
    );
    const encrypted = Buffer.from(envelope.ciphertext, "base64url");
    const decipher = createDecipheriv(
      "aes-256-gcm",
      contentKey,
      Buffer.from(envelope.iv, "base64url"),
    );
    decipher.setAAD(Buffer.from(envelope.aad, "base64url"));
    decipher.setAuthTag(encrypted.subarray(encrypted.byteLength - 16));
    const decrypted = Buffer.concat([
      decipher.update(encrypted.subarray(0, encrypted.byteLength - 16)),
      decipher.final(),
    ]);

    expect(envelope.algorithm).toBe(algorithm);
    expect(Buffer.from(envelope.aad, "base64url")).toEqual(aad);
    expect(decrypted).toEqual(plaintext);
    expect(JSON.stringify(envelope)).not.toContain("secret");
  });
});
