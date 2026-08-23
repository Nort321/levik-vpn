import "server-only";

import { cookies } from "next/headers";
import { redirect } from "next/navigation";

import {
  getSessionByToken,
  type AuthenticatedSession,
} from "@/lib/server/session-store";
import { csrfTokenForSession } from "@/lib/server/security";

export const SESSION_COOKIE_NAME = "__Host-levik_session";
export const LOGIN_COOKIE_NAME = "__Host-levik_login";

export const sessionCookieOptions = {
  httpOnly: true,
  secure: true,
  sameSite: "lax" as const,
  path: "/",
  maxAge: 30 * 24 * 60 * 60,
};

export const loginCookieOptions = {
  httpOnly: true,
  secure: true,
  sameSite: "lax" as const,
  path: "/",
  maxAge: 10 * 60,
};

export async function getLoginBrowserToken(): Promise<string | null> {
  return (await cookies()).get(LOGIN_COOKIE_NAME)?.value ?? null;
}

export async function setLoginBrowserToken(rawToken: string): Promise<void> {
  (await cookies()).set(LOGIN_COOKIE_NAME, rawToken, loginCookieOptions);
}

export async function clearLoginBrowserToken(): Promise<void> {
  (await cookies()).set(LOGIN_COOKIE_NAME, "", {
    ...loginCookieOptions,
    maxAge: 0,
  });
}

export async function setSessionBrowserToken(rawToken: string): Promise<void> {
  (await cookies()).set(SESSION_COOKIE_NAME, rawToken, sessionCookieOptions);
}

export async function clearSessionBrowserToken(): Promise<void> {
  (await cookies()).set(SESSION_COOKIE_NAME, "", {
    ...sessionCookieOptions,
    maxAge: 0,
  });
}

export async function getOptionalSession(
  touch = true,
): Promise<AuthenticatedSession | null> {
  const rawToken = (await cookies()).get(SESSION_COOKIE_NAME)?.value;
  return rawToken ? getSessionByToken(rawToken, touch) : null;
}

export async function requireSession(): Promise<AuthenticatedSession> {
  const session = await getOptionalSession();
  if (!session) {
    redirect("/login");
  }
  return session;
}

export function csrfForSession(session: AuthenticatedSession): string {
  return csrfTokenForSession(session.rawToken);
}

export type { AuthenticatedSession };
