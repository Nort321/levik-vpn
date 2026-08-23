import "server-only";

import { sha256 } from "@/lib/server/crypto";
import { getEnvironment } from "@/lib/server/env";
import {
  MobileApiError,
} from "@/lib/server/mobile-api";
import {
  mobileRequestCanonical,
  type MobileRequestProof,
} from "@/lib/server/mobile-crypto";
import {
  GooglePlayIntegrityTokenRejectedError,
  GooglePlayIntegrityUnavailableError,
  GooglePlayIntegrityVerifier,
} from "@/lib/server/mobile-integrity-google";

export type MobileIntegrityVerdict = {
  requestHash: string;
  requestPackageName: string;
  appPackageName: string | null;
  certificateSha256Digests: readonly string[];
  appRecognized: boolean;
  licensed: boolean;
  meetsDeviceIntegrity: boolean;
  evaluatedAt: Date;
};

export type MobileIntegrityVerifier = {
  verify(input: {
    integrityToken: string;
    expectedRequestHash: string;
    expectedPackageName: string;
  }): Promise<MobileIntegrityVerdict>;
};

let configuredVerifier: MobileIntegrityVerifier | undefined;
let environmentVerifier: MobileIntegrityVerifier | undefined;

/**
 * Installs the production Google Play Integrity decoder. The verifier must
 * decode the token through Google's server API and return only normalized,
 * cryptographically verified verdict fields. No client-provided verdict is
 * accepted here.
 */
export function registerMobileIntegrityVerifier(
  verifier: MobileIntegrityVerifier,
): void {
  if (configuredVerifier) {
    throw new Error("Mobile integrity verifier is already registered");
  }
  configuredVerifier = verifier;
}

function integrityToken(headers: Headers): string | null {
  const token = headers.get("x-levik-integrity");
  if (!token) {
    return null;
  }
  if (
    token.length > 16 * 1_024 ||
    !/^[A-Za-z0-9._-]+$/.test(token)
  ) {
    throw new MobileApiError("invalid_integrity_token", 401);
  }
  return token;
}

export function mobileIntegrityRequestHash(
  method: string,
  path: string,
  proof: MobileRequestProof,
  accessToken: string,
  body: Buffer,
): string {
  const canonical = mobileRequestCanonical(
    method,
    path,
    proof,
    accessToken,
    body,
  );
  return sha256(canonical).toString("base64url");
}

export async function assertMobileAppIntegrity(input: {
  headers: Headers;
  method: string;
  path: string;
  proof: MobileRequestProof;
  accessToken: string;
  body: Buffer;
}): Promise<void> {
  const environment = getEnvironment();
  const token = integrityToken(input.headers);
  if (!token) {
    if (environment.MOBILE_PLAY_INTEGRITY_REQUIRED) {
      throw new MobileApiError("integrity_required", 401);
    }
    return;
  }
  const verifier =
    configuredVerifier ??
    (environmentVerifier ??= createEnvironmentVerifier(environment));
  if (!verifier) {
    throw new MobileApiError("integrity_verifier_unavailable", 503, true);
  }

  const expectedRequestHash = mobileIntegrityRequestHash(
    input.method,
    input.path,
    input.proof,
    input.accessToken,
    input.body,
  );
  let verdict: MobileIntegrityVerdict;
  try {
    verdict = await verifier.verify({
      integrityToken: token,
      expectedRequestHash,
      expectedPackageName: environment.MOBILE_ANDROID_PACKAGE_NAME,
    });
  } catch (error) {
    if (error instanceof GooglePlayIntegrityTokenRejectedError) {
      throw new MobileApiError("integrity_rejected", 403);
    }
    console.error("Google Play Integrity verification failed", {
      errorType:
        error instanceof Error ? error.name : "UnknownError",
      stage:
        error instanceof GooglePlayIntegrityUnavailableError
          ? error.stage
          : "verdict",
      status:
        error instanceof GooglePlayIntegrityUnavailableError
          ? error.status
          : undefined,
    });
    throw new MobileApiError("integrity_verification_unavailable", 503, true);
  }

  if (
    !mobileIntegrityVerdictAccepted({
      verdict,
      expectedRequestHash,
      expectedPackageName: environment.MOBILE_ANDROID_PACKAGE_NAME,
      allowedCertificateDigests:
        environment.mobileAndroidCertificateDigests,
    })
  ) {
    throw new MobileApiError("integrity_rejected", 403);
  }
}

export function mobileIntegrityVerdictAccepted(input: {
  verdict: MobileIntegrityVerdict;
  expectedRequestHash: string;
  expectedPackageName: string;
  allowedCertificateDigests: ReadonlySet<string>;
  now?: number;
}): boolean {
  const verdictAge = Math.abs(
    (input.now ?? Date.now()) - input.verdict.evaluatedAt.getTime(),
  );
  return (
    input.verdict.requestHash === input.expectedRequestHash &&
    input.verdict.requestPackageName === input.expectedPackageName &&
    input.verdict.appPackageName === input.expectedPackageName &&
    input.verdict.certificateSha256Digests.some((digest) =>
      input.allowedCertificateDigests.has(digest),
    ) &&
    input.verdict.appRecognized &&
    input.verdict.licensed &&
    input.verdict.meetsDeviceIntegrity &&
    Number.isFinite(verdictAge) &&
    verdictAge <= 2 * 60 * 1_000
  );
}

function createEnvironmentVerifier(
  environment: ReturnType<typeof getEnvironment>,
): MobileIntegrityVerifier | undefined {
  const email =
    environment.MOBILE_PLAY_INTEGRITY_SERVICE_ACCOUNT_EMAIL;
  const privateKey =
    environment.mobilePlayIntegrityServiceAccountPrivateKey;
  if (!email || !privateKey) {
    return undefined;
  }
  return new GooglePlayIntegrityVerifier(email, privateKey);
}
