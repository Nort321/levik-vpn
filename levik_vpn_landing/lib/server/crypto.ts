import {
  createCipheriv,
  createDecipheriv,
  createHash,
  createHmac,
  randomBytes,
  timingSafeEqual,
} from "node:crypto";

const ENCRYPTED_VALUE_VERSION = "v1";

export function randomToken(byteLength = 32): string {
  return randomBytes(byteLength).toString("base64url");
}

export function sha256(value: string | Buffer): Buffer {
  return createHash("sha256").update(value).digest();
}

export function sha256Hex(value: string | Buffer): string {
  return sha256(value).toString("hex");
}

export function hmacBase64Url(key: Buffer, value: string): string {
  return createHmac("sha256", key).update(value).digest("base64url");
}

export function hmacHex(key: Buffer, value: string): string {
  return createHmac("sha256", key).update(value).digest("hex");
}

export function decodeSecret(value: string): Buffer {
  const decoded = Buffer.from(value, "base64url");
  if (decoded.byteLength !== 32) {
    throw new Error("Secret must decode to exactly 32 bytes");
  }
  return decoded;
}

export function constantTimeEqual(left: string, right: string): boolean {
  const leftBuffer = Buffer.from(left);
  const rightBuffer = Buffer.from(right);
  return (
    leftBuffer.byteLength === rightBuffer.byteLength &&
    timingSafeEqual(leftBuffer, rightBuffer)
  );
}

export function encryptString(
  plaintext: string,
  key: Buffer,
  purpose: string,
): string {
  const nonce = randomBytes(12);
  const cipher = createCipheriv("aes-256-gcm", key, nonce);
  cipher.setAAD(Buffer.from(`${ENCRYPTED_VALUE_VERSION}:${purpose}`));
  const ciphertext = Buffer.concat([
    cipher.update(plaintext, "utf8"),
    cipher.final(),
  ]);
  const authenticationTag = cipher.getAuthTag();
  return [
    ENCRYPTED_VALUE_VERSION,
    nonce.toString("base64url"),
    ciphertext.toString("base64url"),
    authenticationTag.toString("base64url"),
  ].join(".");
}

export function decryptString(
  encoded: string,
  key: Buffer,
  purpose: string,
): string {
  const [version, nonce, ciphertext, authenticationTag, extra] =
    encoded.split(".");
  if (
    version !== ENCRYPTED_VALUE_VERSION ||
    !nonce ||
    !ciphertext ||
    !authenticationTag ||
    extra
  ) {
    throw new Error("Encrypted value has an invalid format");
  }

  const decipher = createDecipheriv(
    "aes-256-gcm",
    key,
    Buffer.from(nonce, "base64url"),
  );
  decipher.setAAD(Buffer.from(`${version}:${purpose}`));
  decipher.setAuthTag(Buffer.from(authenticationTag, "base64url"));
  return Buffer.concat([
    decipher.update(Buffer.from(ciphertext, "base64url")),
    decipher.final(),
  ]).toString("utf8");
}
