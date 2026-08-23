export const MAX_NOTE_CHARACTERS = 3_000;
export const MAX_NOTE_PLAINTEXT_BYTES = 12_000;
export const NOTE_ID_PATTERN = /^[A-Za-z0-9_-]{22}$/;

const VERSION_PREFIX = "v1.";
const KEY_BYTES = 32;
const IV_BYTES = 12;
const ID_BYTES = 16;
const encoder = new TextEncoder();

export type EncryptedNote = {
  id: string;
  keyFragment: string;
  keyCommitment: string;
  iv: string;
  ciphertext: string;
};

function toBase64Url(bytes: Uint8Array): string {
  let binary = "";
  for (const byte of bytes) binary += String.fromCharCode(byte);
  return btoa(binary)
    .replaceAll("+", "-")
    .replaceAll("/", "_")
    .replace(/=+$/, "");
}

function fromBase64Url(value: string): Uint8Array<ArrayBuffer> {
  if (!/^[A-Za-z0-9_-]+$/.test(value)) {
    throw new Error("invalid_base64url");
  }
  const padding = "=".repeat((4 - (value.length % 4)) % 4);
  const binary = atob(value.replaceAll("-", "+").replaceAll("_", "/") + padding);
  const bytes = new Uint8Array(binary.length);
  for (let index = 0; index < binary.length; index += 1) {
    bytes[index] = binary.charCodeAt(index);
  }
  return bytes;
}

function additionalData(id: string): Uint8Array<ArrayBuffer> {
  return new Uint8Array(encoder.encode(`levik-notes:v1:${id}`));
}

async function keyCommitment(
  id: string,
  keyBytes: Uint8Array<ArrayBuffer>,
): Promise<string> {
  const prefix = new Uint8Array(encoder.encode(`levik-notes:key:v1:${id}:`));
  const input = new Uint8Array(prefix.byteLength + keyBytes.byteLength);
  input.set(prefix);
  input.set(keyBytes, prefix.byteLength);
  return toBase64Url(new Uint8Array(await crypto.subtle.digest("SHA-256", input)));
}

export function noteCharacterCount(value: string): number {
  return Array.from(value).length;
}

export async function encryptNote(plaintext: string): Promise<EncryptedNote> {
  const plaintextBytes = encoder.encode(plaintext);
  if (
    noteCharacterCount(plaintext) < 1 ||
    noteCharacterCount(plaintext) > MAX_NOTE_CHARACTERS ||
    plaintextBytes.byteLength > MAX_NOTE_PLAINTEXT_BYTES
  ) {
    throw new Error("invalid_plaintext_length");
  }

  const idBytes = crypto.getRandomValues(new Uint8Array(ID_BYTES));
  const keyBytes = crypto.getRandomValues(new Uint8Array(KEY_BYTES));
  const ivBytes = crypto.getRandomValues(new Uint8Array(IV_BYTES));
  const id = toBase64Url(idBytes);
  const key = await crypto.subtle.importKey(
    "raw",
    keyBytes,
    { name: "AES-GCM" },
    false,
    ["encrypt"],
  );
  const ciphertext = await crypto.subtle.encrypt(
    { name: "AES-GCM", iv: ivBytes, additionalData: additionalData(id), tagLength: 128 },
    key,
    plaintextBytes,
  );

  return {
    id,
    keyFragment: `${VERSION_PREFIX}${toBase64Url(keyBytes)}`,
    keyCommitment: await keyCommitment(id, keyBytes),
    iv: toBase64Url(ivBytes),
    ciphertext: toBase64Url(new Uint8Array(ciphertext)),
  };
}

export function keyFromFragment(fragment: string): Uint8Array<ArrayBuffer> | null {
  if (!fragment.startsWith(VERSION_PREFIX)) return null;
  try {
    const key = fromBase64Url(fragment.slice(VERSION_PREFIX.length));
    return key.byteLength === KEY_BYTES ? key : null;
  } catch {
    return null;
  }
}

export async function commitmentForFragment(
  id: string,
  fragment: string,
): Promise<string | null> {
  const keyBytes = keyFromFragment(fragment);
  if (!NOTE_ID_PATTERN.test(id) || !keyBytes) return null;
  return keyCommitment(id, keyBytes);
}

export async function decryptNote(
  id: string,
  fragment: string,
  iv: string,
  ciphertext: string,
): Promise<string> {
  const keyBytes = keyFromFragment(fragment);
  if (!NOTE_ID_PATTERN.test(id) || !keyBytes) {
    throw new Error("invalid_note_key");
  }
  const ivBytes = fromBase64Url(iv);
  if (ivBytes.byteLength !== IV_BYTES) throw new Error("invalid_note_iv");
  const ciphertextBytes = fromBase64Url(ciphertext);
  const key = await crypto.subtle.importKey(
    "raw",
    keyBytes,
    { name: "AES-GCM" },
    false,
    ["decrypt"],
  );
  const plaintext = await crypto.subtle.decrypt(
    { name: "AES-GCM", iv: ivBytes, additionalData: additionalData(id), tagLength: 128 },
    key,
    ciphertextBytes,
  );
  return new TextDecoder("utf-8", { fatal: true }).decode(plaintext);
}
