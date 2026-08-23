"use client";

import {
  AccountClientError,
  isRecord,
  requiredString,
} from "@/components/login/account-api-client";

function decodeBase64Url(value: unknown): ArrayBuffer {
  const encoded = requiredString(value, 1, 8_192);
  if (!/^[A-Za-z0-9_-]+$/.test(encoded)) {
    throw new AccountClientError("invalid_response");
  }
  const padded = `${encoded.replaceAll("-", "+").replaceAll("_", "/")}${"=".repeat((4 - (encoded.length % 4)) % 4)}`;
  let binary: string;
  try {
    binary = window.atob(padded);
  } catch {
    throw new AccountClientError("invalid_response");
  }
  const bytes = Uint8Array.from(binary, (character) => character.charCodeAt(0));
  return bytes.buffer;
}

function encodeBase64Url(value: ArrayBuffer): string {
  const bytes = new Uint8Array(value);
  let binary = "";
  for (const byte of bytes) binary += String.fromCharCode(byte);
  return window
    .btoa(binary)
    .replaceAll("+", "-")
    .replaceAll("/", "_")
    .replace(/=+$/, "");
}

function optionalNumber(value: unknown): number | undefined {
  return typeof value === "number" && Number.isFinite(value) && value >= 0
    ? value
    : undefined;
}

function parseTransports(value: unknown): AuthenticatorTransport[] | undefined {
  if (!Array.isArray(value)) return undefined;
  const isAuthenticatorTransport = (
    item: unknown,
  ): item is AuthenticatorTransport =>
    item === "ble" ||
    item === "hybrid" ||
    item === "internal" ||
    item === "nfc" ||
    item === "usb";
  const transports = value.filter(
    isAuthenticatorTransport,
  );
  return transports.length === value.length ? transports : undefined;
}

function parseCredentialDescriptors(
  value: unknown,
): PublicKeyCredentialDescriptor[] | undefined {
  if (value === undefined) return undefined;
  if (!Array.isArray(value) || value.length > 100) {
    throw new AccountClientError("invalid_response");
  }
  return value.map((item) => {
    if (!isRecord(item) || item.type !== "public-key") {
      throw new AccountClientError("invalid_response");
    }
    return {
      id: decodeBase64Url(item.id),
      type: "public-key" as const,
      transports: parseTransports(item.transports),
    };
  });
}

export function parseAuthenticationOptions(value: unknown): {
  ceremonyId: string;
  publicKey: PublicKeyCredentialRequestOptions;
} {
  if (!isRecord(value) || value.ok !== true || !isRecord(value.options)) {
    throw new AccountClientError("invalid_response");
  }
  const options = value.options;
  const userVerification =
    options.userVerification === "required" ||
    options.userVerification === "preferred" ||
    options.userVerification === "discouraged"
      ? options.userVerification
      : undefined;
  return {
    ceremonyId: requiredString(value.ceremonyId, 16, 512),
    publicKey: {
      challenge: decodeBase64Url(options.challenge),
      rpId:
        options.rpId === undefined
          ? undefined
          : requiredString(options.rpId, 1, 253),
      timeout: optionalNumber(options.timeout),
      userVerification,
      allowCredentials: parseCredentialDescriptors(options.allowCredentials),
    },
  };
}

export function serializeAuthenticationResponse(
  credential: PublicKeyCredential,
): Record<string, unknown> {
  const response = credential.response;
  if (!(response instanceof AuthenticatorAssertionResponse)) {
    throw new AccountClientError("invalid_response");
  }
  return {
    id: credential.id,
    rawId: encodeBase64Url(credential.rawId),
    type: credential.type,
    ...(credential.authenticatorAttachment
      ? { authenticatorAttachment: credential.authenticatorAttachment }
      : {}),
    clientExtensionResults: credential.getClientExtensionResults(),
    response: {
      authenticatorData: encodeBase64Url(response.authenticatorData),
      clientDataJSON: encodeBase64Url(response.clientDataJSON),
      signature: encodeBase64Url(response.signature),
      ...(response.userHandle
        ? { userHandle: encodeBase64Url(response.userHandle) }
        : {}),
    },
  };
}

export function parseRegistrationOptions(value: unknown): {
  ceremonyId: string;
  publicKey: PublicKeyCredentialCreationOptions;
} {
  if (!isRecord(value) || value.ok !== true || !isRecord(value.options)) {
    throw new AccountClientError("invalid_response");
  }
  const options = value.options;
  if (!isRecord(options.rp) || !isRecord(options.user)) {
    throw new AccountClientError("invalid_response");
  }
  if (!Array.isArray(options.pubKeyCredParams) || options.pubKeyCredParams.length === 0) {
    throw new AccountClientError("invalid_response");
  }
  const pubKeyCredParams = options.pubKeyCredParams.map((item) => {
    if (
      !isRecord(item) ||
      item.type !== "public-key" ||
      typeof item.alg !== "number" ||
      !Number.isInteger(item.alg)
    ) {
      throw new AccountClientError("invalid_response");
    }
    return { type: "public-key" as const, alg: item.alg };
  });
  const selection = isRecord(options.authenticatorSelection)
    ? options.authenticatorSelection
    : {};
  const residentKey =
    selection.residentKey === "required" ||
    selection.residentKey === "preferred" ||
    selection.residentKey === "discouraged"
      ? selection.residentKey
      : undefined;
  const userVerification =
    selection.userVerification === "required" ||
    selection.userVerification === "preferred" ||
    selection.userVerification === "discouraged"
      ? selection.userVerification
      : undefined;
  const attachment =
    selection.authenticatorAttachment === "platform" ||
    selection.authenticatorAttachment === "cross-platform"
      ? selection.authenticatorAttachment
      : undefined;
  const attestation =
    options.attestation === "none" ||
    options.attestation === "direct" ||
    options.attestation === "enterprise" ||
    options.attestation === "indirect"
      ? options.attestation
      : undefined;

  return {
    ceremonyId: requiredString(value.ceremonyId, 16, 512),
    publicKey: {
      challenge: decodeBase64Url(options.challenge),
      rp: {
        id:
          options.rp.id === undefined
            ? undefined
            : requiredString(options.rp.id, 1, 253),
        name: requiredString(options.rp.name, 1, 160),
      },
      user: {
        id: decodeBase64Url(options.user.id),
        name: requiredString(options.user.name, 1, 160),
        displayName: requiredString(options.user.displayName, 1, 160),
      },
      pubKeyCredParams,
      timeout: optionalNumber(options.timeout),
      excludeCredentials: parseCredentialDescriptors(options.excludeCredentials),
      authenticatorSelection: {
        authenticatorAttachment: attachment,
        residentKey,
        requireResidentKey:
          typeof selection.requireResidentKey === "boolean"
            ? selection.requireResidentKey
            : undefined,
        userVerification,
      },
      attestation,
    },
  };
}

export function serializeRegistrationResponse(
  credential: PublicKeyCredential,
): Record<string, unknown> {
  const response = credential.response;
  if (!(response instanceof AuthenticatorAttestationResponse)) {
    throw new AccountClientError("invalid_response");
  }
  return {
    id: credential.id,
    rawId: encodeBase64Url(credential.rawId),
    type: credential.type,
    ...(credential.authenticatorAttachment
      ? { authenticatorAttachment: credential.authenticatorAttachment }
      : {}),
    clientExtensionResults: credential.getClientExtensionResults(),
    response: {
      attestationObject: encodeBase64Url(response.attestationObject),
      clientDataJSON: encodeBase64Url(response.clientDataJSON),
      transports: response.getTransports(),
    },
  };
}

export function requirePublicKeyCredential(
  value: Credential | null,
): PublicKeyCredential {
  if (!(value instanceof PublicKeyCredential)) {
    throw new AccountClientError("cancelled");
  }
  return value;
}
