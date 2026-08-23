import "server-only";

import { RequestSecurityError } from "@/lib/server/security";

const NOTE_HOST = "note.leviknet.com";
const NOTE_ORIGIN = `https://${NOTE_HOST}`;

export function assertNoteRequestHeaders(headers: Headers): void {
  const host = (headers.get("x-forwarded-host") ?? headers.get("host") ?? "")
    .split(":")[0]
    .toLowerCase();
  const origin = headers.get("origin");
  const fetchSite = headers.get("sec-fetch-site");
  const developmentOriginAllowed =
    process.env.NODE_ENV !== "production" &&
    origin !== null &&
    /^http:\/\/(?:localhost|127\.0\.0\.1)(?::\d+)?$/.test(origin);

  if (
    (host !== NOTE_HOST && process.env.NODE_ENV === "production") ||
    (origin !== NOTE_ORIGIN && !developmentOriginAllowed) ||
    (fetchSite !== null && fetchSite !== "same-origin")
  ) {
    throw new RequestSecurityError("Note request origin is not allowed");
  }
}
