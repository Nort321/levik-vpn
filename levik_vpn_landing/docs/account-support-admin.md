# Levik Account support staff API

The staff API is intentionally server-side only. It reuses the existing
legacy browser session and the validated `ADMIN_USER_KEYS` allow-list; it does
not introduce a second admin credential or an account-role backdoor.

## Access

1. Sign in through the existing Telegram-backed cabinet as a user whose
   internal `userKey` is present in `ADMIN_USER_KEYS`.
2. `GET /api/account/v1/admin/support` returns up to 100 recently updated
   tickets and a short-lived session-derived `csrfToken`. An optional single
   `status` query accepts `open`, `waiting_for_support`, `waiting_for_user`, or
   `closed`.
3. Send that token as `X-Levik-CSRF` on every mutation. Requests must also have
   the exact production `Origin`; browser session cookies remain HttpOnly and
   must never be copied into tickets, logs, shell history, or chat.

Staff mutations:

- `POST /api/account/v1/admin/support/{ticketId}/reply` with
  `{"message":"..."}` adds a support-authored reply and moves the ticket to
  `waiting_for_user`.
- `PATCH /api/account/v1/admin/support/{ticketId}` with
  `{"status":"closed"}` (or another allowed state) changes status and manages
  `closed_at`.

All responses are private/no-store JSON. Authorization, CSRF, input limits,
rate limits, ticket existence, and reply limits are enforced server-side.
Reply/status changes and their staff `userKey` are written atomically to the
audit trail; message bodies and ticket subjects are not copied into audit
metadata or application logs.

There is no staff UI in this backend tranche. Operators should use a
same-origin internal client that can read the returned CSRF token without
exposing the HttpOnly session cookie.
