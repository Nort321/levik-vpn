import "server-only";

import type { NextRequest } from "next/server";

import {
  getOptionalVpnSession,
  type VpnSession,
} from "@/lib/server/account/bridge-session";
import {
  assertCsrfToken,
  assertOriginHeader,
  RequestSecurityError,
} from "@/lib/server/security";

const MAX_FORM_BODY_BYTES = 16 * 1_024;

export function assertEncodedFormRequestHeaders(headers: Headers): void {
  const rawContentLength = headers.get("content-length");
  const contentType = headers.get("content-type") ?? "";
  if (
    rawContentLength === null ||
    !/^[1-9][0-9]{0,4}$/.test(rawContentLength) ||
    Number(rawContentLength) > MAX_FORM_BODY_BYTES ||
    headers.has("transfer-encoding") ||
    !contentType
      .toLowerCase()
      .startsWith("application/x-www-form-urlencoded")
  ) {
    throw new RequestSecurityError("Invalid form request", 415);
  }
}

export async function authenticateFormRequest(
  request: NextRequest,
  allowedFields: ReadonlySet<string>,
): Promise<{
  session: VpnSession;
  values: Readonly<Record<string, string>>;
}> {
  assertOriginHeader(request.headers);
  assertEncodedFormRequestHeaders(request.headers);

  const formData = await request.formData();
  const values: Record<string, string> = {};
  let count = 0;
  for (const [key, value] of formData.entries()) {
    count += 1;
    if (
      count > 12 ||
      !allowedFields.has(key) ||
      typeof value !== "string" ||
      value.length > 4_096 ||
      Object.hasOwn(values, key)
    ) {
      throw new RequestSecurityError("Invalid form payload", 400);
    }
    values[key] = value;
  }

  const session = await getOptionalVpnSession();
  if (!session) {
    throw new RequestSecurityError("Authentication required", 401);
  }
  assertCsrfToken(session.rawToken, values.csrf ?? null);
  return { session, values };
}

export function assertSameOriginNavigation(request: NextRequest): void {
  const fetchSite = request.headers.get("sec-fetch-site");
  if (fetchSite !== "same-origin") {
    throw new RequestSecurityError("Cross-site navigation is not allowed");
  }
}
