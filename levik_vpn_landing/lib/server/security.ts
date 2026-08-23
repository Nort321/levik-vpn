import "server-only";

import { isIP } from "node:net";

import { headers } from "next/headers";

import {
  constantTimeEqual,
  decodeSecret,
  hmacBase64Url,
} from "@/lib/server/crypto";
import { getEnvironment } from "@/lib/server/env";

export class RequestSecurityError extends Error {
  constructor(
    message: string,
    public readonly status = 403,
  ) {
    super(message);
    this.name = "RequestSecurityError";
  }
}

export async function assertSameOriginRequest(): Promise<void> {
  const requestHeaders = await headers();
  assertOriginHeader(requestHeaders);
}

export function assertOriginHeader(requestHeaders: Headers): void {
  const environment = getEnvironment();
  const origin = requestHeaders.get("origin");
  const fetchSite = requestHeaders.get("sec-fetch-site");

  if (origin !== environment.APP_ORIGIN) {
    throw new RequestSecurityError("Request origin is not allowed");
  }
  if (fetchSite && fetchSite !== "same-origin") {
    throw new RequestSecurityError("Cross-site request is not allowed");
  }
}

export function csrfTokenForSession(sessionToken: string): string {
  const environment = getEnvironment();
  return hmacBase64Url(
    decodeSecret(environment.CSRF_HMAC_KEY),
    `csrf:v1:${sessionToken}`,
  );
}

export function assertCsrfToken(
  sessionToken: string,
  suppliedToken: FormDataEntryValue | null,
): void {
  if (
    typeof suppliedToken !== "string" ||
    !constantTimeEqual(csrfTokenForSession(sessionToken), suppliedToken)
  ) {
    throw new RequestSecurityError("CSRF validation failed");
  }
}

export function clientAddressFromHeaders(requestHeaders: Headers): string {
  const clientAddress =
    requestHeaders.get("x-levik-client-ip") ??
    requestHeaders.get("x-forwarded-for");
  if (!clientAddress || clientAddress.includes(",")) {
    throw new RequestSecurityError("Client address is unavailable", 400);
  }

  const address = clientAddress.trim();
  if (isIP(address) === 0) {
    throw new RequestSecurityError("Client address is invalid", 400);
  }
  return address;
}
