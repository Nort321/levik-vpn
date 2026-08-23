"use client";

const MAX_RESPONSE_BYTES = 512 * 1_024;

export class AccountClientError extends Error {
  constructor(readonly code: string) {
    super("Levik Account request failed");
    this.name = "AccountClientError";
  }
}

export function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

export function requiredString(
  value: unknown,
  minimum = 1,
  maximum = 2_048,
): string {
  if (
    typeof value !== "string" ||
    value.length < minimum ||
    value.length > maximum
  ) {
    throw new AccountClientError("invalid_response");
  }
  return value;
}

export async function accountClientRequest(
  path: string,
  request: {
    method?: "GET" | "POST" | "PATCH" | "DELETE";
    body?: unknown;
    csrfToken?: string;
  } = {},
): Promise<unknown> {
  if (!path.startsWith("/") || path.includes("\\") || path.includes("//")) {
    throw new AccountClientError("invalid_request");
  }
  const headers = new Headers({ accept: "application/json" });
  if (request.body !== undefined) headers.set("content-type", "application/json");
  if (request.csrfToken) headers.set("X-Levik-CSRF", request.csrfToken);

  let response: Response;
  try {
    response = await fetch(`/api/account/v1${path}`, {
      method: request.method ?? "GET",
      headers,
      body: request.body === undefined ? undefined : JSON.stringify(request.body),
      cache: "no-store",
      credentials: "same-origin",
      redirect: "error",
      signal: AbortSignal.timeout(10_000),
    });
  } catch {
    throw new AccountClientError("temporarily_unavailable");
  }

  const text = await response.text();
  if (new TextEncoder().encode(text).byteLength > MAX_RESPONSE_BYTES) {
    throw new AccountClientError("invalid_response");
  }

  let payload: unknown;
  try {
    payload = text ? (JSON.parse(text) as unknown) : null;
  } catch {
    throw new AccountClientError("invalid_response");
  }

  if (!response.ok) {
    if (isRecord(payload) && payload.ok === false && isRecord(payload.error)) {
      throw new AccountClientError(requiredString(payload.error.code, 1, 80));
    }
    throw new AccountClientError("request_failed");
  }
  return payload;
}

export function accountErrorMessage(error: unknown): string {
  const code = error instanceof AccountClientError ? error.code : "request_failed";
  switch (code) {
    case "invalid_credentials":
      return "Levik ID или пароль не подошли.";
    case "invalid_recovery_code":
      return "Recovery-код не подошёл или уже был использован.";
    case "invalid_password":
      return "Парольная фраза должна содержать не менее 12 символов и не может быть коротким PIN.";
    case "invalid_request":
      return "Проверьте введённые данные и повторите запрос.";
    case "identity_conflict":
    case "credential_conflict":
      return "Этот способ входа уже связан с другим аккаунтом.";
    case "rate_limited":
      return "Слишком много попыток. Подождите и попробуйте позже.";
    case "reauthentication_required":
      return "Для этого действия нужен недавний вход. Выйдите из аккаунта, войдите снова и повторите действие.";
    case "expired":
    case "challenge_expired":
    case "auth_challenge_expired":
      return "Запрос истёк. Начните действие заново.";
    case "not_supported":
      return "Этот способ не поддерживается вашим браузером или устройством.";
    case "cancelled":
      return "Подтверждение отменено. Данные не изменены.";
    default:
      return "Levik Account временно недоступен. Попробуйте позже.";
  }
}

export function safeAccountReturnTo(value: string): string {
  if (!value.startsWith("/") || value.startsWith("//")) {
    return "/dashboard/account-security";
  }
  const url = new URL(value, "https://leviknet.com");
  const allowed =
    url.origin === "https://leviknet.com" &&
    (url.pathname.startsWith("/dashboard/account-") ||
      url.pathname.startsWith("/dashboard/identities") ||
      url.pathname.startsWith("/dashboard/passkeys") ||
      url.pathname.startsWith("/dashboard/recovery") ||
      url.pathname.startsWith("/dashboard/sessions") ||
      url.pathname.startsWith("/dashboard/devices") ||
      url.pathname.startsWith("/dashboard/support") ||
      url.pathname === "/activate" ||
      url.pathname === "/account/delete");
  return allowed
    ? `${url.pathname}${url.search}`
    : "/dashboard/account-security";
}
