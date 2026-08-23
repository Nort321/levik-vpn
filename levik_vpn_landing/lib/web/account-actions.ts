"use server";

import { cookies, headers } from "next/headers";
import { redirect } from "next/navigation";
import { z } from "zod";

import { getEnvironment } from "@/lib/server/env";
import { SESSION_COOKIE_NAME } from "@/lib/server/browser-auth";
import { consumeRateLimit } from "@/lib/server/rate-limit";
import {
  assertSameOriginRequest,
  RequestSecurityError,
} from "@/lib/server/security";

const ACCOUNT_API_PREFIX = "/api/account/v1";
const ACCOUNT_COOKIE_NAME = "__Host-levik_account";
const MAX_RESPONSE_BYTES = 512 * 1_024;
const REQUEST_TIMEOUT_MS = 10_000;

const timestampSchema = z.string().datetime({ offset: true });
const identifierSchema = z.string().min(1).max(1_024);

const identitySchema = z
  .object({
    id: identifierSchema,
    provider: z.enum(["google", "telegram", "password"]),
    label: z.string().min(1).max(160),
    verifiedAt: timestampSchema,
    lastUsedAt: timestampSchema.nullable(),
  })
  .strict();

const passkeySchema = z
  .object({
    credentialId: identifierSchema,
    name: z.string().min(1).max(120),
    createdAt: timestampSchema,
    lastUsedAt: timestampSchema.nullable(),
  })
  .strict();

const accountSessionSchema = z
  .object({
    id: identifierSchema,
    deviceName: z.string().min(1).max(160),
    createdAt: timestampSchema,
    lastSeenAt: timestampSchema,
    current: z.boolean(),
  })
  .strict();

const accountDeviceSchema = z
  .object({
    id: identifierSchema,
    name: z.string().min(1).max(160),
    platform: z.string().min(1).max(80),
    createdAt: timestampSchema,
    lastSeenAt: timestampSchema.nullable(),
    current: z.boolean(),
  })
  .strict();

const accountOverviewResponseSchema = z
  .object({
    ok: z.literal(true),
    account: z
      .object({
        id: z.string().uuid(),
        levikId: z.string().min(3).max(64),
        displayName: z.string().min(1).max(160),
        status: z.enum(["active", "suspended", "deletion_pending"]),
        createdAt: timestampSchema,
      })
      .strict(),
    identities: z.array(identitySchema).max(12),
    passkeys: z.array(passkeySchema).max(24),
    sessions: z.array(accountSessionSchema).max(100),
    devices: z.array(accountDeviceSchema).max(100),
    recoveryCodesRemaining: z.number().int().min(0).max(100),
    entitlements: z
      .array(
        z
          .object({
            id: identifierSchema,
            source: z.string().min(1).max(80),
            status: z.string().min(1).max(80),
            expiresAt: timestampSchema.nullable(),
          })
          .strict(),
      )
      .max(100),
    csrfToken: z.string().min(20).max(512),
  })
  .strict();

const supportReplySchema = z
  .object({
    id: identifierSchema,
    author: z.enum(["account", "support", "system"]),
    body: z.string().min(1).max(8_000),
    createdAt: timestampSchema,
  })
  .strict();

const supportTicketSchema = z
  .object({
    id: identifierSchema,
    reference: z.string().min(1).max(80),
    subject: z.string().min(1).max(160),
    category: z.enum([
      "connection",
      "account",
      "subscription",
      "privacy",
      "other",
    ]),
    status: z.enum(["open", "waiting_for_user", "waiting_for_support", "closed"]),
    createdAt: timestampSchema,
    updatedAt: timestampSchema,
    replies: z.array(supportReplySchema).max(200),
  })
  .strict();

const supportOverviewResponseSchema = z
  .object({
    ok: z.literal(true),
    tickets: z.array(supportTicketSchema).max(100),
  })
  .strict();

const supportTicketResponseSchema = z
  .object({ ok: z.literal(true), ticket: supportTicketSchema })
  .strict();
const supportReplyResponseSchema = z
  .object({ ok: z.literal(true), reply: supportReplySchema })
  .strict();

const activationResponseSchema = z
  .object({
    ok: z.literal(true),
    activation: z
      .object({
        code: z.string().regex(/^[A-Za-z0-9-]{6,32}$/),
        device: z
          .object({
            name: z.string().min(1).max(160),
            platform: z.string().min(1).max(80),
          })
          .strict(),
        expiresAt: timestampSchema,
      })
      .strict(),
  })
  .strict();

const mutationResponseSchema = z.object({ ok: z.literal(true) }).passthrough();
const activationCompleteSchema = z
  .object({ ok: z.literal(true), state: z.literal("authorized") })
  .strict();

const apiErrorSchema = z
  .object({
    ok: z.literal(false),
    error: z
      .object({
        code: z.string().min(1).max(80),
        message: z.string().min(1).max(300),
        retryable: z.boolean(),
      })
      .strict(),
  })
  .strict();

const passwordSchema = z.string().min(12).max(256);
const csrfSchema = z.string().min(20).max(200);

export type AccountOverview = Omit<
  z.infer<typeof accountOverviewResponseSchema>,
  "ok"
>;
export type SupportOverview = Omit<
  z.infer<typeof supportOverviewResponseSchema>,
  "ok"
>;
export type ActivationView =
  | ({ state: "pending" } & z.infer<typeof activationResponseSchema>["activation"])
  | { state: "invalid" | "expired" | "completed" };

class AccountApiError extends Error {
  constructor(
    readonly code: string,
    readonly status: number,
  ) {
    super("Levik Account request failed");
    this.name = "AccountApiError";
  }
}

type ApiRequest = {
  method?: "GET" | "POST" | "PATCH" | "DELETE";
  body?: unknown;
  accountToken?: string;
  legacyToken?: string;
  csrfToken?: string;
};

function parseJson(text: string): unknown {
  if (!text) return null;
  try {
    return JSON.parse(text) as unknown;
  } catch {
    throw new AccountApiError("invalid_response", 502);
  }
}

async function requestAccountApi<Schema extends z.ZodType>(
  path: string,
  schema: Schema,
  request: ApiRequest = {},
): Promise<z.infer<Schema>> {
  if (!path.startsWith("/") || path.includes("\\") || path.includes("//")) {
    throw new Error("Invalid account API path");
  }

  const environment = getEnvironment();
  const requestHeaders = new Headers({
    accept: "application/json",
  });
  if (request.body !== undefined) {
    requestHeaders.set("content-type", "application/json");
  }
  if (request.accountToken) {
    const cookieValues = [`${ACCOUNT_COOKIE_NAME}=${request.accountToken}`];
    if (request.legacyToken) {
      cookieValues.push(`${SESSION_COOKIE_NAME}=${request.legacyToken}`);
    }
    requestHeaders.set("cookie", cookieValues.join("; "));
  }
  if (request.csrfToken) {
    requestHeaders.set("X-Levik-CSRF", request.csrfToken);
    requestHeaders.set("origin", environment.APP_ORIGIN);
  }

  let response: Response;
  try {
    response = await fetch(
      new URL(`${ACCOUNT_API_PREFIX}${path}`, environment.APP_ORIGIN),
      {
        method: request.method ?? "GET",
        headers: requestHeaders,
        body: request.body === undefined ? undefined : JSON.stringify(request.body),
        cache: "no-store",
        redirect: "manual",
        signal: AbortSignal.timeout(REQUEST_TIMEOUT_MS),
      },
    );
  } catch {
    throw new AccountApiError("temporarily_unavailable", 503);
  }

  const text = await response.text();
  if (Buffer.byteLength(text, "utf8") > MAX_RESPONSE_BYTES) {
    throw new AccountApiError("invalid_response", 502);
  }
  const payload = parseJson(text);

  if (!response.ok) {
    const parsedError = apiErrorSchema.safeParse(payload);
    throw new AccountApiError(
      parsedError.success ? parsedError.data.error.code : "request_failed",
      response.status,
    );
  }

  const parsed = schema.safeParse(payload);
  if (!parsed.success) {
    throw new AccountApiError("invalid_response", 502);
  }
  return parsed.data;
}

function boundedFormData(
  formData: FormData,
  allowedFields: ReadonlySet<string>,
): Record<string, string> {
  const result: Record<string, string> = {};
  let entries = 0;
  for (const [key, value] of formData.entries()) {
    entries += 1;
    if (
      entries > 16 ||
      !allowedFields.has(key) ||
      typeof value !== "string" ||
      value.length > 8_192 ||
      Object.hasOwn(result, key)
    ) {
      throw new RequestSecurityError("Invalid form payload", 400);
    }
    result[key] = value;
  }
  return result;
}

async function authorizeAccountMutation(
  formData: FormData,
  scope: string,
): Promise<{ accountToken: string; csrfToken: string; legacyToken?: string }> {
  await assertSameOriginRequest();
  const accountToken = (await cookies()).get(ACCOUNT_COOKIE_NAME)?.value;
  if (!accountToken) redirect("/login");
  const overview = await requestAccountApi(
    "",
    accountOverviewResponseSchema,
    { accountToken },
  );
  const csrf = csrfSchema.parse(formData.get("csrf"));
  if (csrf !== overview.csrfToken) {
    throw new RequestSecurityError("Invalid CSRF token", 403);
  }
  const limit = await consumeRateLimit({
    scope: `${scope}-user`,
    identifier: overview.account.id,
    limit: 20,
    windowSeconds: 10 * 60,
  });
  if (!limit.allowed) {
    throw new RequestSecurityError("Rate limit exceeded", 429);
  }
  const legacyToken = (await cookies()).get(SESSION_COOKIE_NAME)?.value;
  return { accountToken, csrfToken: csrf, legacyToken };
}

function actionErrorCode(error: unknown): string {
  if (!(error instanceof AccountApiError)) return "request_failed";
  switch (error.code) {
    case "invalid_credentials":
    case "invalid_recovery_code":
    case "rate_limited":
    case "reauthentication_required":
      return error.code;
    case "last_authentication_method":
      return "last_identity";
    case "credential_conflict":
      return "identity_conflict";
    case "auth_challenge_expired":
    case "activation_not_found":
      return "expired";
    default:
      return "temporarily_unavailable";
  }
}

function withResult(path: string, key: "notice" | "error", value: string): string {
  const url = new URL(path, "https://leviknet.com");
  url.searchParams.set(key, value);
  return `${url.pathname}${url.search}`;
}

function coarseWebPlatform(userAgent: string | null): string {
  const value = (userAgent ?? "").slice(0, 512);
  if (/Android/i.test(value)) return "Android web";
  if (/iPhone|iPad/i.test(value)) return "iOS web";
  if (/Windows/i.test(value)) return "Windows web";
  if (/Macintosh|Mac OS X/i.test(value)) return "macOS web";
  if (/Linux/i.test(value)) return "Linux web";
  return "Web browser";
}

export async function getAccountOverview(): Promise<AccountOverview> {
  const accountToken = (await cookies()).get(ACCOUNT_COOKIE_NAME)?.value;
  if (!accountToken) redirect("/login");
  const result = await requestAccountApi(
    "",
    accountOverviewResponseSchema,
    { accountToken },
  );
  return {
    account: result.account,
    identities: result.identities,
    passkeys: result.passkeys,
    sessions: result.sessions,
    devices: result.devices,
    recoveryCodesRemaining: result.recoveryCodesRemaining,
    entitlements: result.entitlements,
    csrfToken: result.csrfToken,
  };
}

export async function getOptionalAccountOverview(): Promise<AccountOverview | null> {
  const accountToken = (await cookies()).get(ACCOUNT_COOKIE_NAME)?.value;
  if (!accountToken) return null;
  try {
    const result = await requestAccountApi(
      "",
      accountOverviewResponseSchema,
      { accountToken },
    );
    return {
      account: result.account,
      identities: result.identities,
      passkeys: result.passkeys,
      sessions: result.sessions,
      devices: result.devices,
      recoveryCodesRemaining: result.recoveryCodesRemaining,
      entitlements: result.entitlements,
      csrfToken: result.csrfToken,
    };
  } catch {
    return null;
  }
}

export async function getSupportOverview(): Promise<
  SupportOverview & { csrfToken: string }
> {
  const accountToken = (await cookies()).get(ACCOUNT_COOKIE_NAME)?.value;
  if (!accountToken) redirect("/login");
  const [support, account] = await Promise.all([
    requestAccountApi("/support", supportOverviewResponseSchema, { accountToken }),
    requestAccountApi("", accountOverviewResponseSchema, { accountToken }),
  ]);
  return { tickets: support.tickets, csrfToken: account.csrfToken };
}

export async function getActivationView(code: string): Promise<ActivationView> {
  if (!/^[A-Za-z0-9-]{6,32}$/.test(code)) return { state: "invalid" };
  try {
    const result = await requestAccountApi(
      `/activation?code=${encodeURIComponent(code)}`,
      activationResponseSchema,
    );
    return { state: "pending", ...result.activation };
  } catch (error) {
    if (error instanceof AccountApiError && error.code === "expired") {
      return { state: "expired" };
    }
    return { state: "invalid" };
  }
}

export async function completeActivationAction(formData: FormData): Promise<void> {
  const raw = boundedFormData(formData, new Set(["csrf", "code"]));
  const parsed = z
    .object({
      csrf: csrfSchema,
      code: z.string().regex(/^[A-Za-z0-9-]{6,32}$/),
    })
    .strict()
    .parse(raw);
  const { accountToken, csrfToken } = await authorizeAccountMutation(
    formData,
    "account-activation-complete",
  );
  try {
    await requestAccountApi("/activation/complete", activationCompleteSchema, {
      method: "POST",
      body: { code: parsed.code },
      accountToken,
      csrfToken,
    });
  } catch (error) {
    redirect(withResult(`/activate?code=${encodeURIComponent(parsed.code)}`, "error", actionErrorCode(error)));
  }
  redirect(`/activate?code=${encodeURIComponent(parsed.code)}&notice=completed`);
}

export async function linkIdentityAction(formData: FormData): Promise<void> {
  const raw = boundedFormData(formData, new Set(["csrf", "provider"]));
  const parsed = z
    .object({ csrf: csrfSchema, provider: z.literal("telegram") })
    .strict()
    .parse(raw);
  const { accountToken, csrfToken, legacyToken } = await authorizeAccountMutation(
    formData,
    "account-identity-link",
  );
  try {
    await requestAccountApi("/identities", mutationResponseSchema, {
      method: "POST",
      body: { provider: parsed.provider },
      accountToken,
      legacyToken,
      csrfToken,
    });
  } catch (error) {
    redirect(withResult("/dashboard/identities", "error", actionErrorCode(error)));
  }
  redirect("/dashboard/identities?notice=identity_linked");
}

export async function setPasswordIdentityAction(formData: FormData): Promise<void> {
  const raw = boundedFormData(
    formData,
    new Set(["csrf", "password", "confirmPassword"]),
  );
  const parsed = z
    .object({
      csrf: csrfSchema,
      password: passwordSchema,
      confirmPassword: passwordSchema,
    })
    .strict()
    .refine((value) => value.password === value.confirmPassword, {
      path: ["confirmPassword"],
    })
    .parse(raw);
  const { accountToken, csrfToken } = await authorizeAccountMutation(
    formData,
    "account-password-set",
  );
  try {
    await requestAccountApi("/identities", mutationResponseSchema, {
      method: "POST",
      body: {
        provider: "password",
        password: parsed.password,
      },
      accountToken,
      csrfToken,
    });
  } catch (error) {
    redirect(withResult("/dashboard/identities", "error", actionErrorCode(error)));
  }
  redirect("/dashboard/identities?notice=password_saved");
}

export async function unlinkIdentityAction(formData: FormData): Promise<void> {
  const raw = boundedFormData(formData, new Set(["csrf", "identityId"]));
  const parsed = z
    .object({ csrf: csrfSchema, identityId: identifierSchema })
    .strict()
    .parse(raw);
  const { accountToken, csrfToken } = await authorizeAccountMutation(
    formData,
    "account-identity-unlink",
  );
  try {
    await requestAccountApi("/identities", mutationResponseSchema, {
      method: "DELETE",
      body: { identityId: parsed.identityId },
      accountToken,
      csrfToken,
    });
  } catch (error) {
    redirect(withResult("/dashboard/identities", "error", actionErrorCode(error)));
  }
  redirect("/dashboard/identities?notice=identity_removed");
}

export async function renamePasskeyAction(formData: FormData): Promise<void> {
  const raw = boundedFormData(
    formData,
    new Set(["csrf", "credentialId", "name"]),
  );
  const parsed = z
    .object({
      csrf: csrfSchema,
      credentialId: identifierSchema,
      name: z.string().trim().min(1).max(120),
    })
    .strict()
    .parse(raw);
  const { accountToken, csrfToken } = await authorizeAccountMutation(
    formData,
    "account-passkey-rename",
  );
  try {
    await requestAccountApi(
      `/passkeys/${encodeURIComponent(parsed.credentialId)}`,
      mutationResponseSchema,
      {
        method: "PATCH",
        body: { name: parsed.name },
        accountToken,
        csrfToken,
      },
    );
  } catch (error) {
    redirect(withResult("/dashboard/passkeys", "error", actionErrorCode(error)));
  }
  redirect("/dashboard/passkeys?notice=passkey_renamed");
}

export async function revokePasskeyAction(formData: FormData): Promise<void> {
  const raw = boundedFormData(formData, new Set(["csrf", "credentialId"]));
  const parsed = z
    .object({ csrf: csrfSchema, credentialId: identifierSchema })
    .strict()
    .parse(raw);
  const { accountToken, csrfToken } = await authorizeAccountMutation(
    formData,
    "account-passkey-revoke",
  );
  try {
    await requestAccountApi(
      `/passkeys/${encodeURIComponent(parsed.credentialId)}`,
      mutationResponseSchema,
      { method: "DELETE", accountToken, csrfToken },
    );
  } catch (error) {
    redirect(withResult("/dashboard/passkeys", "error", actionErrorCode(error)));
  }
  redirect("/dashboard/passkeys?notice=passkey_removed");
}

export async function revokeAccountSessionAction(formData: FormData): Promise<void> {
  const raw = boundedFormData(formData, new Set(["csrf", "sessionId"]));
  const parsed = z
    .object({ csrf: csrfSchema, sessionId: identifierSchema })
    .strict()
    .parse(raw);
  const { accountToken, csrfToken } = await authorizeAccountMutation(
    formData,
    "account-session-revoke",
  );
  try {
    await requestAccountApi(
      `/sessions/${encodeURIComponent(parsed.sessionId)}`,
      mutationResponseSchema,
      { method: "DELETE", accountToken, csrfToken },
    );
  } catch (error) {
    redirect(withResult("/dashboard/sessions", "error", actionErrorCode(error)));
  }
  redirect("/dashboard/sessions?notice=session_removed");
}

export async function revokeAccountDeviceAction(formData: FormData): Promise<void> {
  const raw = boundedFormData(formData, new Set(["csrf", "deviceId"]));
  const parsed = z
    .object({ csrf: csrfSchema, deviceId: identifierSchema })
    .strict()
    .parse(raw);
  const { accountToken, csrfToken } = await authorizeAccountMutation(
    formData,
    "account-device-revoke",
  );
  try {
    await requestAccountApi(
      `/devices/${encodeURIComponent(parsed.deviceId)}`,
      mutationResponseSchema,
      { method: "DELETE", accountToken, csrfToken },
    );
  } catch (error) {
    redirect(withResult("/dashboard/devices", "error", actionErrorCode(error)));
  }
  redirect("/dashboard/devices?notice=device_removed");
}

export async function logoutAccountAction(formData: FormData): Promise<void> {
  const raw = boundedFormData(formData, new Set(["csrf", "sessionId"]));
  const parsed = z
    .object({ csrf: csrfSchema, sessionId: identifierSchema })
    .strict()
    .parse(raw);
  const { accountToken, csrfToken } = await authorizeAccountMutation(
    formData,
    "account-logout",
  );
  try {
    await requestAccountApi(
      `/sessions/${encodeURIComponent(parsed.sessionId)}`,
      mutationResponseSchema,
      { method: "DELETE", accountToken, csrfToken },
    );
  } finally {
    (await cookies()).set(ACCOUNT_COOKIE_NAME, "", {
      httpOnly: true,
      secure: true,
      sameSite: "lax",
      path: "/",
      maxAge: 0,
    });
  }
  redirect("/login?notice=signed_out");
}

export async function createSupportTicketAction(formData: FormData): Promise<void> {
  const raw = boundedFormData(
    formData,
    new Set(["csrf", "category", "subject", "message", "includeDiagnostics"]),
  );
  const parsed = z
    .object({
      csrf: csrfSchema,
      category: supportTicketSchema.shape.category,
      subject: z.string().trim().min(5).max(160),
      message: z.string().trim().min(20).max(8_000),
      includeDiagnostics: z.enum(["yes"]).optional(),
    })
    .strict()
    .parse(raw);
  const { accountToken, csrfToken } = await authorizeAccountMutation(
    formData,
    "account-support-create",
  );
  const requestHeaders = parsed.includeDiagnostics ? await headers() : null;
  try {
    await requestAccountApi("/support", supportTicketResponseSchema, {
      method: "POST",
      body: {
        category: parsed.category,
        subject: parsed.subject,
        message: parsed.message,
        diagnostics:
          parsed.includeDiagnostics === "yes"
            ? { platform: coarseWebPlatform(requestHeaders?.get("user-agent") ?? null) }
            : undefined,
      },
      accountToken,
      csrfToken,
    });
  } catch (error) {
    redirect(withResult("/dashboard/support", "error", actionErrorCode(error)));
  }
  redirect("/dashboard/support?notice=ticket_created");
}

export async function replySupportTicketAction(formData: FormData): Promise<void> {
  const raw = boundedFormData(formData, new Set(["csrf", "ticketId", "message"]));
  const parsed = z
    .object({
      csrf: csrfSchema,
      ticketId: identifierSchema,
      message: z.string().trim().min(2).max(8_000),
    })
    .strict()
    .parse(raw);
  const { accountToken, csrfToken } = await authorizeAccountMutation(
    formData,
    "account-support-reply",
  );
  try {
    await requestAccountApi(
      `/support/${encodeURIComponent(parsed.ticketId)}/reply`,
      supportReplyResponseSchema,
      {
        method: "POST",
        body: { message: parsed.message },
        accountToken,
        csrfToken,
      },
    );
  } catch (error) {
    redirect(withResult("/dashboard/support", "error", actionErrorCode(error)));
  }
  redirect("/dashboard/support?notice=reply_sent");
}
