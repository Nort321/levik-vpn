import "server-only";

import {
  constants,
  createCipheriv,
  createPublicKey,
  publicEncrypt,
  randomBytes,
  type KeyObject,
  verify,
} from "node:crypto";

import { sha256Hex } from "@/lib/server/crypto";

const RSA_MODULUS_BITS = 3_072;
const RSA_PUBLIC_EXPONENT = 65_537n;
const RSA_PSS_SALT_BYTES = 32;

export type MobileRequestProof = {
  deviceId: string;
  timestamp: string;
  nonce: string;
  signature: string;
};

export type ParsedMobilePublicKey = {
  deviceId: string;
  der: Buffer;
  key: KeyObject;
};

export type MobileRequestSigningAlgorithm = "PS256" | "RS256";
export type MobileProfileEncryptionAlgorithm =
  | "RSA-OAEP-256+A256GCM"
  | "RSA-OAEP+A256GCM";

export type EncryptedMobilePayload = {
  algorithm: MobileProfileEncryptionAlgorithm;
  encryptedKey: string;
  iv: string;
  ciphertext: string;
  aad: string;
};

function decodeCanonicalBase64Url(
  value: string,
  minimumBytes: number,
  maximumBytes: number,
): Buffer {
  if (!/^[A-Za-z0-9_-]+$/.test(value)) {
    throw new Error("Invalid base64url value");
  }
  const decoded = Buffer.from(value, "base64url");
  if (
    decoded.byteLength < minimumBytes ||
    decoded.byteLength > maximumBytes ||
    decoded.toString("base64url") !== value
  ) {
    throw new Error("Invalid base64url value");
  }
  return decoded;
}

export function parseMobilePublicKey(
  publicKeySpki: string,
): ParsedMobilePublicKey {
  const der = decodeCanonicalBase64Url(publicKeySpki, 256, 1_024);
  let key: KeyObject;
  try {
    key = createPublicKey({
      key: der,
      format: "der",
      type: "spki",
    });
  } catch {
    throw new Error("Invalid mobile public key");
  }

  const details = key.asymmetricKeyDetails;
  if (
    key.asymmetricKeyType !== "rsa" ||
    details?.modulusLength !== RSA_MODULUS_BITS ||
    details.publicExponent !== RSA_PUBLIC_EXPONENT
  ) {
    throw new Error("Mobile public key must be RSA-3072 with exponent 65537");
  }

  return {
    deviceId: sha256Hex(der),
    der,
    key,
  };
}

export function mobileRequestCanonical(
  method: string,
  path: string,
  proof: Pick<MobileRequestProof, "timestamp" | "nonce" | "deviceId">,
  accessToken: string,
  body: Buffer,
): string {
  return [
    "v1",
    method.toUpperCase(),
    path,
    proof.timestamp,
    proof.nonce,
    proof.deviceId,
    sha256Hex(accessToken),
    sha256Hex(body),
  ].join("\n");
}

export function verifyMobileRequestSignature(
  key: KeyObject,
  canonical: string,
  encodedSignature: string,
  algorithm: MobileRequestSigningAlgorithm,
): boolean {
  let signature: Buffer;
  try {
    signature = decodeCanonicalBase64Url(encodedSignature, 384, 384);
  } catch {
    return false;
  }
  try {
    const keyOptions =
      algorithm === "PS256"
        ? {
            key,
            padding: constants.RSA_PKCS1_PSS_PADDING,
            saltLength: RSA_PSS_SALT_BYTES,
          }
        : {
            key,
            padding: constants.RSA_PKCS1_PADDING,
          };
    return verify(
      "sha256",
      Buffer.from(canonical, "utf8"),
      keyOptions,
      signature,
    );
  } catch {
    return false;
  }
}

export function encryptMobilePayload(
  plaintext: Buffer,
  publicKey: KeyObject,
  additionalAuthenticatedData: Buffer,
  algorithm: MobileProfileEncryptionAlgorithm,
): EncryptedMobilePayload {
  const contentKey = randomBytes(32);
  const iv = randomBytes(12);
  const cipher = createCipheriv("aes-256-gcm", contentKey, iv);
  cipher.setAAD(additionalAuthenticatedData);
  const encrypted = Buffer.concat([
    cipher.update(plaintext),
    cipher.final(),
    cipher.getAuthTag(),
  ]);
  const encryptedKey = publicEncrypt(
    {
      key: publicKey,
      oaepHash:
        algorithm === "RSA-OAEP-256+A256GCM" ? "sha256" : "sha1",
      padding: constants.RSA_PKCS1_OAEP_PADDING,
    },
    contentKey,
  );

  return {
    algorithm,
    encryptedKey: encryptedKey.toString("base64url"),
    iv: iv.toString("base64url"),
    ciphertext: encrypted.toString("base64url"),
    aad: additionalAuthenticatedData.toString("base64url"),
  };
}
